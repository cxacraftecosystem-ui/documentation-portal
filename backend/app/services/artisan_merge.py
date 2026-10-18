"""Merging two artisan records that are the same person (C24): preview, merge, undo, redirect.

WHY. The Aadhaar and Pehchan unique indexes stop the SAME number being entered twice, but nothing
stops one person being entered twice without one — the same weaver documented at two workshops by
two researchers, one record with the Aadhaar and one without. Until now the only answer was to
delete one and lose what hung off it. This module folds the duplicate INTO the record that is kept.

WHAT A MERGE DOES, IN ORDER — AND WHY THE ORDER IS THE SAFETY. This backend has no transaction idiom
(zero ``db.tx()`` call sites; see ``routes/questionnaire.merge_interview_into``), so every step is a
committed write and an interruption between two of them is a state somebody holds. The order is
chosen so that every such state is recoverable by :func:`undo_merge`:

1. The ``ArtisanMerge`` row is written FIRST, status ``IN_PROGRESS``, holding the merged row's every
   column, the survivor's columns before and the intended after, and the id of every row that is
   about to move. From here on, whatever happens, the undo knows what to put back.
2. The merged row's identity numbers are cleared if the survivor is taking them (the unique indexes
   would otherwise refuse the survivor's update while the merged row still exists).
3. The survivor takes the picked fields.
4. Every child is re-pointed. A link row the survivor already holds (both sat in one interview, both
   are on one workshop's roster) is FOLDED — the merged one's copy is deleted, its full contents kept
   in step 1's row so the undo can recreate it.
5. The merged row is deleted. 6. The merge row is marked ``DONE``.

A merge that stopped half-way stays ``IN_PROGRESS``: a second merge of either artisan is refused
until it is undone, and the undo works on it exactly as on a finished one (every reverse write is
conditional on the row still pointing where the merge put it).

WHAT AN UNDO WILL NOT DO. It never overwrites an edit made after the merge: a survivor field somebody
changed since is left as it is and named in ``undoNotes``; a child that has been re-pointed again
since stays where it now is. The 30-day window is the owner's ruling; an unfinished merge can be
undone at any time.

ADMINS ONLY (owner's ruling) — enforced on the routes with ``require_admin``.
"""

from __future__ import annotations

import re
import time as monotonic_time
from datetime import UTC, date, datetime, timedelta
from typing import Any, Iterable

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder
from prisma import Json

from app.core.db import db
from app.core.deps import get_value, values_match
from app.services import aadhaar_fingerprint
from app.services.artisan_identity import mask_aadhaar
from app.services.concurrency import gather_reads

UNDO_WINDOW = timedelta(days=30)

STATUS_IN_PROGRESS = "IN_PROGRESS"
STATUS_DONE = "DONE"
STATUS_UNDONE = "UNDONE"

CHOICE_KEEP = "keep"
CHOICE_MERGE = "merge"
CHOICE_BOTH = "both"

# The relation attributes a Prisma ``Artisan`` can carry. Never part of a snapshot: a snapshot is the
# row's own columns, which is what a create can put back.
_RELATION_KEYS = {
    "craft",
    "location",
    "createdBy",
    "workshop",
    "workshops",
    "products",
    "tools",
    "toolLinks",
    "media",
    "questionnaireInterviews",
    "sectionStatuses",
}

# Columns a create must not be handed back.
_NOT_RESTORED = {"updatedAt"}


#: The columns that together hold an artisan's Aadhaar: the keyed fingerprint and the last four, and
#: the legacy full number while it still exists (services/aadhaar_fingerprint.py). They move as one.
_AADHAAR_COLUMNS: tuple[str, ...] = (
    aadhaar_fingerprint.FINGERPRINT_FIELD,
    aadhaar_fingerprint.LAST4_FIELD,
) + ((aadhaar_fingerprint.NUMBER_FIELD,) if aadhaar_fingerprint.LEGACY_COLUMN_PRESENT else ())


class Field:
    """One line of the field-by-field pick: what the admin sees and which columns it moves."""

    def __init__(self, key: str, label: str, columns: tuple[str, ...] | None = None, both: bool = False):
        self.key = key
        self.label = label
        self.columns = columns or (key,)
        # Long text the two records may BOTH hold something true in. "Both" joins them.
        self.both = both


#: The pickable fields, in the order the artisan form shows them. Status, owner and review stamps are
#: deliberately absent: the kept record's own review state stands, and the merged record's owner is
#: recorded on this merge's row rather than silently becoming the owner of the survivor.
FIELDS: tuple[Field, ...] = (
    Field("name", "Name"),
    Field("localName", "Local name"),
    Field("gender", "Gender"),
    Field("phone", "Phone"),
    Field("email", "Email"),
    Field("place", "Place"),
    Field("address", "Address"),
    Field("craftId", "Craft"),
    Field("workshopId", "Workshop"),
    Field("aadhaarNumber", "Aadhaar", _AADHAAR_COLUMNS),
    Field("pehchan", "Pehchan card", ("pehchanCardAvailable", "pehchanCardNumber")),
    Field("dateOfBirth", "Date of birth"),
    Field("craftStartDate", "Practising since"),
    Field("experience", "Experience", ("experienceYears", "experienceMonths")),
    Field("dos", "Do's", both=True),
    Field("donts", "Don'ts", both=True),
    Field("notes", "Notes", both=True),
    Field("locationId", "Captured location"),
)
FIELDS_BY_KEY = {field.key: field for field in FIELDS}

# The relations a preview counts and a merge moves, with the words both the preview and the history
# print. Order is the order they are listed in.
MOVE_LABELS: tuple[tuple[str, str, str], ...] = (
    ("products", "product record", "product records"),
    ("tools", "tool record", "tool records"),
    ("toolArtisan", "tool link", "tool links"),
    ("media", "media file", "media files"),
    ("mediaTagged", "tagged media file", "tagged media files"),
    ("interviewArtisan", "interview sitting", "interview sittings"),
    ("workshopArtisan", "workshop roster entry", "workshop roster entries"),
    ("sectionStatus", "questionnaire section verdict", "questionnaire section verdicts"),
    ("comments", "comment", "comments"),
    ("revisions", "revision", "revisions"),
    ("reviewLogs", "review decision", "review decisions"),
    ("scopeItems", "sharing scope entry", "sharing scope entries"),
    ("tasks", "task", "tasks"),
)


def _plural(count: int, singular: str, plural: str) -> str:
    return f"{count} {singular if count == 1 else plural}"


# ---------------------------------------------------------------------------------------------
# Snapshots — a row's own columns, as JSON, and back
# ---------------------------------------------------------------------------------------------


def unjson(value: Any) -> Any:
    """A Json column's value as plain data, whether it came back from Prisma or from a test fake."""
    return getattr(value, "data", value) if isinstance(value, Json) else value


def columns_of(row: Any) -> dict[str, Any]:
    """Every scalar column of ``row``, relations dropped."""
    if hasattr(row, "model_dump"):
        raw = row.model_dump()
    else:
        raw = dict(vars(row))
    return {key: unjson(value) for key, value in raw.items() if key not in _RELATION_KEYS}


def snapshot(row: Any) -> dict[str, Any]:
    """The row as JSON, noting which columns were datetimes so :func:`restorable` can put them back."""
    cols = columns_of(row)
    stamps = sorted(key for key, value in cols.items() if isinstance(value, (datetime, date)))
    encoded = jsonable_encoder(cols)
    encoded["__datetimes"] = stamps
    return encoded


def _parse_stamp(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return value
    return value


def restorable(snap: dict[str, Any], *, drop: Iterable[str] = ()) -> dict[str, Any]:
    """A create payload from a :func:`snapshot`: datetimes parsed, Json wrapped, NULLs omitted.

    NULLs are omitted rather than sent because every nullable column defaults to NULL anyway, and a
    bare ``None`` for a Json column is something prisma-client-py refuses.
    """
    stamps = set(snap.get("__datetimes") or [])
    dropped = set(drop) | _NOT_RESTORED | {"__datetimes"}
    data: dict[str, Any] = {}
    for key, value in snap.items():
        if key in dropped or value is None:
            continue
        if key in stamps:
            value = _parse_stamp(value)
        elif isinstance(value, (dict, list)):
            value = Json(value)
        data[key] = value
    return data


# ---------------------------------------------------------------------------------------------
# The field pick — pure
# ---------------------------------------------------------------------------------------------


def _has_value(row: dict[str, Any], field: Field) -> bool:
    if field.key == "pehchan":
        return bool(row.get("pehchanCardNumber")) or row.get("pehchanCardAvailable") is False
    return any(row.get(column) not in (None, "", []) for column in field.columns)


def _same(keep: dict[str, Any], merge: dict[str, Any], field: Field) -> bool:
    return all(values_match(keep.get(column), merge.get(column)) for column in field.columns)


def _aadhaars_differ(keep: dict[str, Any], merge: dict[str, Any]) -> bool:
    """Both records hold an Aadhaar and they are not the same number, whichever columns hold them."""
    fp = aadhaar_fingerprint.FINGERPRINT_FIELD
    if keep.get(fp) and merge.get(fp):
        return keep[fp] != merge[fp]
    legacy = aadhaar_fingerprint.NUMBER_FIELD
    for one, other in ((keep, merge), (merge, keep)):
        number = one.get(legacy)
        if number and aadhaar_fingerprint.has_aadhaar(other):
            try:
                return not aadhaar_fingerprint.holds(other, str(number))
            except aadhaar_fingerprint.FingerprintKeyMissing:
                return False
    return False


def default_choice(keep: dict[str, Any], merge: dict[str, Any], field: Field) -> str:
    """The kept record's answer where it has one, the other record's where only it does."""
    if _has_value(keep, field) or not _has_value(merge, field):
        return CHOICE_KEEP
    return CHOICE_MERGE


def _join_text(*values: Any) -> str | None:
    parts: list[str] = []
    for value in values:
        text = str(value or "").strip()
        if text and text not in parts:
            parts.append(text)
    return "\n".join(parts) if parts else None


def resolve_picks(
    keep: dict[str, Any], merge: dict[str, Any], picks: dict[str, str] | None
) -> tuple[dict[str, str], dict[str, Any]]:
    """The choice for every field and the survivor's columns that change. Refuses an unknown key."""
    picks = dict(picks or {})
    unknown = sorted(set(picks) - set(FIELDS_BY_KEY))
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Unknown fields: {', '.join(unknown)}.",
        )
    chosen: dict[str, str] = {}
    changes: dict[str, Any] = {}
    for field in FIELDS:
        choice = picks.get(field.key) or default_choice(keep, merge, field)
        if choice not in (CHOICE_KEEP, CHOICE_MERGE, CHOICE_BOTH) or (choice == CHOICE_BOTH and not field.both):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"{field.label}: choose one of the two records.",
            )
        chosen[field.key] = choice
        if choice == CHOICE_MERGE:
            for column in field.columns:
                if not values_match(keep.get(column), merge.get(column)):
                    changes[column] = merge.get(column)
        elif choice == CHOICE_BOTH:
            joined = _join_text(keep.get(field.key), merge.get(field.key))
            if not values_match(keep.get(field.key), joined):
                changes[field.key] = joined
    return chosen, changes


# ---------------------------------------------------------------------------------------------
# Display
# ---------------------------------------------------------------------------------------------


def _day(value: Any) -> str | None:
    value = _parse_stamp(value)
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return str(value) if value else None


def display_value(row: dict[str, Any], field: Field, names: dict[str, str]) -> str | None:
    """How a field reads on the comparison. Identity numbers are always masked here."""
    if field.key == "aadhaarNumber":
        return aadhaar_fingerprint.stored_mask(row)
    if field.key == "pehchan":
        number = row.get("pehchanCardNumber")
        if number:
            return f"Card ending {str(number)[-4:]}"
        if row.get("pehchanCardAvailable") is False:
            return "No card"
        return None
    if field.key in ("craftId", "workshopId"):
        value = row.get(field.key)
        return names.get(value, "Unknown") if value else None
    if field.key == "locationId":
        return "Captured" if row.get("locationId") else None
    if field.key in ("dateOfBirth", "craftStartDate"):
        return _day(row.get(field.key))
    if field.key == "experience":
        years, months = row.get("experienceYears"), row.get("experienceMonths")
        if years is None and months is None:
            return None
        parts = []
        if years is not None:
            parts.append(f"{years} y")
        if months:
            parts.append(f"{months} m")
        return " ".join(parts) or "0 y"
    value = row.get(field.key)
    return None if value in (None, "") else str(value)


# ---------------------------------------------------------------------------------------------
# Reading what a merge would move
# ---------------------------------------------------------------------------------------------


def artisan_set_key(artisan_ids: Iterable[str]) -> str | None:
    """MIRRORS ``routes/questionnaire.artisan_set_key`` — sorted, de-duplicated, comma-joined."""
    unique = sorted({aid for aid in artisan_ids if aid})
    return ",".join(unique) if unique else None


async def _empty() -> list[Any]:
    return []


async def _read_children(merged_id: str, survivor_id: str) -> dict[str, Any]:
    """Every row that names ``merged_id``, plus the survivor's link rows it may collide with."""
    (
        workshop_links,
        survivor_workshop_links,
        tool_links,
        survivor_tool_links,
        interview_links,
        survivor_interview_links,
        section_statuses,
        survivor_section_statuses,
        products,
        tools,
        media,
        media_tagged,
        comments,
        revisions,
        review_logs,
        scope_items,
        tasks_listing,
        tasks_record,
    ) = await gather_reads(
        db.workshopartisan.find_many(where={"artisanId": merged_id}),
        db.workshopartisan.find_many(where={"artisanId": survivor_id}),
        db.toolartisan.find_many(where={"artisanId": merged_id}),
        db.toolartisan.find_many(where={"artisanId": survivor_id}),
        db.questionnaireinterviewartisan.find_many(where={"artisanId": merged_id}),
        db.questionnaireinterviewartisan.find_many(where={"artisanId": survivor_id}),
        db.questionnairesectionstatus.find_many(where={"artisanId": merged_id}),
        db.questionnairesectionstatus.find_many(where={"artisanId": survivor_id}),
        db.productdocumentation.find_many(where={"artisanId": merged_id}),
        db.tooldocumentation.find_many(where={"artisanId": merged_id}),
        db.mediafile.find_many(where={"artisanId": merged_id}),
        # A cuid is unique across tables, so the id alone finds a clip tagged with this artisan —
        # whatever spelling of the type the client that tagged it used.
        db.mediafile.find_many(where={"linkedRecordId": merged_id}),
        db.entrycomment.find_many(where={"recordId": merged_id}),
        db.recordrevision.find_many(where={"recordId": merged_id}),
        db.reviewlog.find_many(where={"recordId": merged_id}),
        db.dataaccessscopeitem.find_many(where={"recordId": merged_id}),
        db.assignedtask.find_many(where={"artisanIds": {"has": merged_id}}),
        db.assignedtask.find_many(where={"recordId": merged_id}),
    )
    tasks: dict[str, Any] = {}
    for task in [*tasks_listing, *tasks_record]:
        tasks[task.id] = task
    return {
        "workshopArtisan": (workshop_links, survivor_workshop_links),
        "toolArtisan": (tool_links, survivor_tool_links),
        "interviewArtisan": (interview_links, survivor_interview_links),
        "sectionStatus": (section_statuses, survivor_section_statuses),
        "products": products,
        "tools": tools,
        "media": media,
        "mediaTagged": media_tagged,
        "comments": comments,
        "revisions": revisions,
        "reviewLogs": review_logs,
        "scopeItems": scope_items,
        "tasks": list(tasks.values()),
    }


def _split_links(merged_rows: list[Any], survivor_rows: list[Any], key: str) -> tuple[list[str], list[Any]]:
    """(other-side ids that MOVE, merged rows that FOLD into a survivor row already there)."""
    held = {getattr(row, key) for row in survivor_rows}
    moved = [getattr(row, key) for row in merged_rows if getattr(row, key) not in held]
    folded = [row for row in merged_rows if getattr(row, key) in held]
    return moved, folded


async def plan_moves(merged: Any, survivor: Any, final_name: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """What the merge will move, as the JSON stored on the merge row, and any BLOCKERS.

    The one blocker found here is an interview collision: two sittings on one questionnaire, one
    holding the survivor and one holding the merged artisan with otherwise the same people, would
    become the same artisan set — which ``@@unique([questionnaireId, artisanSetKey])`` refuses. Those
    sittings have to be merged first, on the interview screen, which already knows how to.
    """
    merged_id, survivor_id = merged.id, survivor.id
    children = await _read_children(merged_id, survivor_id)
    moves: dict[str, Any] = {}

    for key, column in (
        ("workshopArtisan", "workshopId"),
        ("toolArtisan", "toolId"),
        ("interviewArtisan", "interviewId"),
        ("sectionStatus", "sectionId"),
    ):
        merged_rows, survivor_rows = children[key]
        moved, folded = _split_links(merged_rows, survivor_rows, column)
        moves[key] = {"column": column, "moved": moved, "folded": [snapshot(row) for row in folded]}

    def renamed(rows: list[Any]) -> list[dict[str, Any]]:
        # A denormalised ``artisanName`` that spells the merged artisan's name follows the record to
        # the survivor's final name; one somebody typed differently is left alone.
        out = []
        for row in rows:
            old = getattr(row, "artisanName", None)
            follows = bool(old) and str(old).strip().casefold() == str(merged.name or "").strip().casefold()
            out.append({"id": row.id, "artisanName": old, "rename": follows and old != final_name})
        return out

    moves["products"] = renamed(children["products"])
    moves["tools"] = renamed(children["tools"])
    moves["media"] = [row.id for row in children["media"]]
    moves["mediaTagged"] = [row.id for row in children["mediaTagged"]]
    moves["comments"] = [row.id for row in children["comments"]]
    moves["revisions"] = [row.id for row in children["revisions"]]
    moves["reviewLogs"] = [row.id for row in children["reviewLogs"]]

    # Sharing scope: unique on (grantId, recordType, recordId), so a grant that already lists the
    # survivor folds.
    scope_rows = children["scopeItems"]
    survivor_scope = (
        await db.dataaccessscopeitem.find_many(
            where={"recordId": survivor_id, "grantId": {"in": sorted({row.grantId for row in scope_rows})}}
        )
        if scope_rows
        else []
    )
    held_grants = {(row.grantId, row.recordType) for row in survivor_scope}
    moves["scopeItems"] = {
        "moved": [row.id for row in scope_rows if (row.grantId, row.recordType) not in held_grants],
        "folded": [snapshot(row) for row in scope_rows if (row.grantId, row.recordType) in held_grants],
    }

    task_moves = []
    for task in children["tasks"]:
        listed = list(getattr(task, "artisanIds", None) or [])
        after = []
        for aid in listed:
            aid = survivor_id if aid == merged_id else aid
            if aid not in after:
                after.append(aid)
        task_moves.append(
            {
                "id": task.id,
                "before": listed,
                "after": after,
                "recordId": getattr(task, "recordId", None) == merged_id,
            }
        )
    moves["tasks"] = task_moves

    # Interview set keys: every sitting the merged artisan is in gets a new key.
    blockers: list[dict[str, Any]] = []
    sittings = sorted({row.interviewId for row in children["interviewArtisan"][0]})
    key_moves: list[dict[str, Any]] = []
    if sittings:
        interviews, links = await gather_reads(
            db.questionnaireinterview.find_many(where={"id": {"in": sittings}}),
            db.questionnaireinterviewartisan.find_many(where={"interviewId": {"in": sittings}}),
        )
        members: dict[str, set[str]] = {}
        for link in links:
            members.setdefault(link.interviewId, set()).add(link.artisanId)
        claimed: dict[tuple[Any, str], str] = {}
        for interview in interviews:
            people = {survivor_id if aid == merged_id else aid for aid in members.get(interview.id, set())}
            new_key = artisan_set_key(people)
            key_moves.append({"id": interview.id, "before": interview.artisanSetKey, "after": new_key})
            if not new_key:
                continue
            slot = (interview.questionnaireId, new_key)
            other = claimed.get(slot)
            if other is None:
                clash = await db.questionnaireinterview.find_first(
                    where={"questionnaireId": interview.questionnaireId, "artisanSetKey": new_key}
                )
                if clash is not None and clash.id != interview.id and clash.id not in sittings:
                    other = clash.id
            if other is not None and other != interview.id:
                titles = {row.id: row.title for row in interviews}
                other_title = titles.get(other)
                if other_title is None:
                    other_row = await db.questionnaireinterview.find_unique(where={"id": other})
                    other_title = getattr(other_row, "title", None) or "another interview"
                blockers.append(
                    {
                        "code": "interview_set_collision",
                        "message": (
                            f"“{interview.title}” and “{other_title}” would become the same interview. "
                            "Merge those two interviews first."
                        ),
                        "interviewIds": [interview.id, other],
                    }
                )
            claimed[slot] = interview.id
    moves["interviewKeys"] = key_moves
    return moves, blockers


def move_counts(moves: dict[str, Any]) -> list[dict[str, Any]]:
    """The moves as {key, label, count, folded}, non-empty only, for the preview and the history."""
    out = []
    for key, singular, plural in MOVE_LABELS:
        entry = moves.get(key)
        if entry is None:
            continue
        if isinstance(entry, dict):
            count = len(entry.get("moved") or []) + len(entry.get("folded") or [])
            folded = len(entry.get("folded") or [])
        else:
            count, folded = len(entry), 0
        if count:
            out.append({"key": key, "label": _plural(count, singular, plural), "count": count, "folded": folded})
    return out


# ---------------------------------------------------------------------------------------------
# Preview
# ---------------------------------------------------------------------------------------------


async def _names_for(rows: list[dict[str, Any]]) -> dict[str, str]:
    craft_ids = sorted({row.get("craftId") for row in rows if row.get("craftId")})
    workshop_ids = sorted({row.get("workshopId") for row in rows if row.get("workshopId")})
    crafts, workshops = await gather_reads(
        db.craft.find_many(where={"id": {"in": craft_ids}}) if craft_ids else _empty(),
        db.workshop.find_many(where={"id": {"in": workshop_ids}}) if workshop_ids else _empty(),
    )
    names = {row.id: row.name for row in crafts}
    names.update({row.id: row.title for row in workshops})
    return names


async def _load_pair(keep_id: str, merge_id: str) -> tuple[Any, Any]:
    if not keep_id or not merge_id:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Choose two artisans.")
    if keep_id == merge_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Choose two different artisans."
        )
    keep, merge = await gather_reads(
        db.artisan.find_unique(where={"id": keep_id}),
        db.artisan.find_unique(where={"id": merge_id}),
    )
    if keep is None or merge is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Artisan not found.")
    return keep, merge


async def _unfinished_for(*artisan_ids: str) -> Any | None:
    return await db.artisanmerge.find_first(
        where={
            "status": STATUS_IN_PROGRESS,
            "OR": [{"survivorId": {"in": list(artisan_ids)}}, {"mergedId": {"in": list(artisan_ids)}}],
        }
    )


def _side(row: dict[str, Any], names: dict[str, str], owner: Any) -> dict[str, Any]:
    return {
        "id": row["id"],
        "name": row.get("name"),
        "place": row.get("place"),
        "craft": names.get(row.get("craftId")) if row.get("craftId") else None,
        "status": str(getattr(row.get("status"), "value", row.get("status")) or ""),
        "createdAt": jsonable_encoder(row.get("createdAt")),
        "createdByName": getattr(owner, "name", None),
    }


async def preview_merge(keep_id: str, merge_id: str) -> dict[str, Any]:
    """Everything the admin needs to decide: both records side by side, the moves, the blockers."""
    keep_row, merge_row = await _load_pair(keep_id, merge_id)
    keep, merge = columns_of(keep_row), columns_of(merge_row)
    owners = await db.user.find_many(where={"id": {"in": sorted({keep["createdById"], merge["createdById"]})}})
    owner_by_id = {row.id: row for row in owners}
    names = await _names_for([keep, merge])
    _chosen, changes = resolve_picks(keep, merge, None)
    final_name = changes.get("name", keep.get("name"))
    moves, blockers = await plan_moves(merge_row, keep_row, final_name)
    if await _unfinished_for(keep_id, merge_id) is not None:
        blockers.insert(
            0,
            {"code": "merge_unfinished", "message": "An earlier merge of one of these did not finish. Undo it first."},
        )
    warnings: list[str] = []
    # Merging away an artisan that KEPT an earlier merge still inside its undo window ends that
    # undo for good: the earlier survivor would no longer exist to be split apart again.
    now = datetime.now(UTC)
    for earlier in await db.artisanmerge.find_many(where={"survivorId": merge_id, "status": STATUS_DONE}):
        if can_undo(earlier, now):
            warnings.append(
                f"{merge.get('name') or 'This artisan'} kept an earlier merge of {earlier.mergedName}. "
                "Merging it away means that merge can no longer be undone."
            )
    if _aadhaars_differ(keep, merge):
        warnings.append("Their Aadhaar numbers differ. Check that these are the same person.")
    if keep.get("pehchanCardNumber") and merge.get("pehchanCardNumber") and keep["pehchanCardNumber"] != merge["pehchanCardNumber"]:
        warnings.append("Their Pehchan card numbers differ. Check that these are the same person.")
    fields = []
    for field in FIELDS:
        keep_value = display_value(keep, field, names)
        merge_value = display_value(merge, field, names)
        if keep_value is None and merge_value is None:
            continue
        fields.append(
            {
                "key": field.key,
                "label": field.label,
                "keep": keep_value,
                "merge": merge_value,
                "same": _same(keep, merge, field),
                "default": default_choice(keep, merge, field),
                "allowBoth": field.both,
            }
        )
    return {
        "keep": _side(keep, names, owner_by_id.get(keep["createdById"])),
        "merge": _side(merge, names, owner_by_id.get(merge["createdById"])),
        "fields": fields,
        "moves": move_counts(moves),
        "blockers": blockers,
        "warnings": warnings,
        "canMerge": not blockers,
        "undoDays": UNDO_WINDOW.days,
    }


# ---------------------------------------------------------------------------------------------
# The merge
# ---------------------------------------------------------------------------------------------


def _merge_metadata(keep: dict[str, Any], merge: dict[str, Any], chosen: dict[str, str], merge_id: str, by: Any, at: datetime) -> dict[str, Any]:
    """The survivor's ``extraMetadata`` after the merge: its own, plus the field contributions of the
    fields it took, plus a note of the merge."""
    meta = dict(keep.get("extraMetadata") or {})
    theirs = dict((merge.get("extraMetadata") or {}).get("fieldProvenance") or {})
    if theirs:
        provenance = dict(meta.get("fieldProvenance") or {})
        for field in FIELDS:
            if chosen.get(field.key) == CHOICE_MERGE:
                for column in field.columns:
                    if column in theirs:
                        provenance[column] = theirs[column]
        meta["fieldProvenance"] = provenance
    merged_list = list(meta.get("mergedArtisans") or [])
    merged_list.append(
        {"mergeId": merge_id, "artisanId": merge["id"], "name": merge.get("name"), "at": at.isoformat(), "by": get_value(by, "id")}
    )
    meta["mergedArtisans"] = merged_list
    return meta


async def _relink(table: Any, column: str, ids: list[str], *, frm: str, to: str) -> int:
    """Re-point link rows (``artisanId`` frm -> to) whose other side is in ``ids``."""
    if not ids:
        return 0
    return int(await table.update_many(where={"artisanId": frm, column: {"in": ids}}, data={"artisanId": to}) or 0)


async def _repoint(table: Any, ids: list[str], column: str, *, frm: str, to: str) -> int:
    """Re-point ``column`` frm -> to on the rows in ``ids`` that still say ``frm``."""
    if not ids:
        return 0
    return int(await table.update_many(where={"id": {"in": ids}, column: frm}, data={column: to}) or 0)


_LINK_TABLES = {
    "workshopArtisan": "workshopartisan",
    "toolArtisan": "toolartisan",
    "interviewArtisan": "questionnaireinterviewartisan",
    "sectionStatus": "questionnairesectionstatus",
}


async def _delete_folded(name: str, entry: dict[str, Any], merged_id: str) -> None:
    folded = entry.get("folded") or []
    if not folded:
        return
    table = getattr(db, _LINK_TABLES[name])
    column = entry["column"]
    await table.delete_many(where={"artisanId": merged_id, column: {"in": [snap[column] for snap in folded]}})


async def merge_artisans(keep_id: str, merge_id: str, picks: dict[str, str] | None, user: Any) -> dict[str, Any]:
    keep_row, merge_row = await _load_pair(keep_id, merge_id)
    if await _unfinished_for(keep_id, merge_id) is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An earlier merge of one of these did not finish. Undo it first.",
        )
    keep, merge = columns_of(keep_row), columns_of(merge_row)
    chosen, changes = resolve_picks(keep, merge, picks)
    final_name = changes.get("name", keep.get("name"))
    moves, blockers = await plan_moves(merge_row, keep_row, final_name)
    if blockers:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "merge_blocked", "message": blockers[0]["message"], "blockers": blockers},
        )
    now = datetime.now(UTC)
    survivor_after = {**keep, **changes}

    # 1. The audit row first — the undo's whole knowledge.
    record = await db.artisanmerge.create(
        data={
            "survivorId": keep_id,
            "mergedId": merge_id,
            "survivorName": str(final_name or ""),
            "mergedName": str(merge.get("name") or ""),
            "status": STATUS_IN_PROGRESS,
            "picks": Json(chosen),
            "survivorBefore": Json(snapshot(keep_row)),
            "survivorAfter": Json(jsonable_encoder(survivor_after)),
            "mergedSnapshot": Json(snapshot(merge_row)),
            "moves": Json(moves),
            "mergedById": get_value(user, "id"),
        }
    )
    metadata = _merge_metadata(keep, merge, chosen, record.id, user, now)

    # 2. Free the identity numbers the survivor is taking.
    freed = {column: None for column in _IDENTITY_COLUMNS if column in changes and merge.get(column)}
    if freed:
        await db.artisan.update(where={"id": merge_id}, data=freed)

    # 3. The survivor takes the picked fields.
    survivor_data: dict[str, Any] = dict(changes)
    survivor_data["extraMetadata"] = Json(metadata)
    await db.artisan.update(where={"id": keep_id}, data=survivor_data)
    if changes:
        await db.recordrevision.create(
            data={
                "recordType": "artisan",
                "recordId": keep_id,
                "editedById": get_value(user, "id"),
                "changes": Json(_ledger(keep, changes)),
            }
        )

    # 4. Re-point every child; fold the link rows the survivor already holds.
    for name, table_name in _LINK_TABLES.items():
        entry = moves[name]
        await _delete_folded(name, entry, merge_id)
        await _relink(getattr(db, table_name), entry["column"], entry["moved"], frm=merge_id, to=keep_id)
    for key, table in (("products", db.productdocumentation), ("tools", db.tooldocumentation)):
        rows = moves[key]
        await _repoint(table, [row["id"] for row in rows], "artisanId", frm=merge_id, to=keep_id)
        renames = [row["id"] for row in rows if row["rename"]]
        if renames:
            await table.update_many(where={"id": {"in": renames}}, data={"artisanName": str(final_name)})
    await _repoint(db.mediafile, moves["media"], "artisanId", frm=merge_id, to=keep_id)
    await _repoint(db.mediafile, moves["mediaTagged"], "linkedRecordId", frm=merge_id, to=keep_id)
    await _repoint(db.entrycomment, moves["comments"], "recordId", frm=merge_id, to=keep_id)
    await _repoint(db.recordrevision, moves["revisions"], "recordId", frm=merge_id, to=keep_id)
    await _repoint(db.reviewlog, moves["reviewLogs"], "recordId", frm=merge_id, to=keep_id)
    scope = moves["scopeItems"]
    if scope["folded"]:
        await db.dataaccessscopeitem.delete_many(where={"id": {"in": [snap["id"] for snap in scope["folded"]]}})
    await _repoint(db.dataaccessscopeitem, scope["moved"], "recordId", frm=merge_id, to=keep_id)
    for task in moves["tasks"]:
        data: dict[str, Any] = {}
        if task["before"] != task["after"]:
            data["artisanIds"] = {"set": task["after"]}
        if task["recordId"]:
            data["recordId"] = keep_id
        if data:
            await db.assignedtask.update(where={"id": task["id"]}, data=data)
    for entry in moves["interviewKeys"]:
        if entry["before"] != entry["after"]:
            await db.questionnaireinterview.update(where={"id": entry["id"]}, data={"artisanSetKey": entry["after"]})

    # 5. The merged row goes. 6. Done.
    await db.artisan.delete(where={"id": merge_id})
    done = await db.artisanmerge.update(
        where={"id": record.id}, data={"status": STATUS_DONE, "completedAt": datetime.now(UTC)}
    )
    forget_redirects()
    return merge_summary(done or record, now=datetime.now(UTC))


# ---------------------------------------------------------------------------------------------
# History and undo
# ---------------------------------------------------------------------------------------------


def _aware(value: Any) -> datetime | None:
    value = _parse_stamp(value)
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    return None


def undo_until(record: Any) -> datetime | None:
    created = _aware(getattr(record, "createdAt", None))
    return created + UNDO_WINDOW if created else None


def can_undo(record: Any, now: datetime) -> bool:
    state = getattr(record, "status", None)
    if state == STATUS_IN_PROGRESS:
        return True
    if state != STATUS_DONE:
        return False
    until = undo_until(record)
    return until is not None and now <= until


def merge_summary(record: Any, *, now: datetime, by_name: str | None = None, undone_by_name: str | None = None) -> dict[str, Any]:
    moves = unjson(getattr(record, "moves", None)) or {}
    notes = unjson(getattr(record, "undoNotes", None)) or {}
    until = undo_until(record)
    return {
        "id": record.id,
        "status": record.status,
        "survivorId": record.survivorId,
        "survivorName": record.survivorName,
        "mergedId": record.mergedId,
        "mergedName": record.mergedName,
        "mergedByName": by_name,
        "createdAt": jsonable_encoder(getattr(record, "createdAt", None)),
        "undoneAt": jsonable_encoder(getattr(record, "undoneAt", None)),
        "undoneByName": undone_by_name,
        "undoUntil": until.isoformat() if until else None,
        "canUndo": can_undo(record, now),
        "moves": move_counts(moves),
        "kept": list(notes.get("kept") or []),
    }


async def merge_history(limit: int = 50) -> dict[str, Any]:
    rows = await db.artisanmerge.find_many(order={"createdAt": "desc"}, take=limit + 1)
    truncated = len(rows) > limit
    rows = rows[:limit]
    user_ids = sorted({uid for row in rows for uid in (row.mergedById, getattr(row, "undoneById", None)) if uid})
    users = await db.user.find_many(where={"id": {"in": user_ids}}) if user_ids else []
    names = {row.id: row.name for row in users}
    now = datetime.now(UTC)
    return {
        "items": [
            merge_summary(
                row,
                now=now,
                by_name=names.get(row.mergedById),
                undone_by_name=names.get(getattr(row, "undoneById", None)),
            )
            for row in rows
        ],
        "truncated": truncated,
        "undoDays": UNDO_WINDOW.days,
    }


async def _recreate(table: Any, snaps: list[dict[str, Any]]) -> int:
    made = 0
    for snap in snaps:
        try:
            await table.create(data=restorable(snap))
            made += 1
        except Exception:  # noqa: BLE001 - a link that is already back again is not a failure
            continue
    return made


async def undo_merge(merge_id: str, user: Any) -> dict[str, Any]:
    record = await db.artisanmerge.find_unique(where={"id": merge_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Merge not found.")
    now = datetime.now(UTC)
    if record.status == STATUS_UNDONE:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="This merge was already undone.")
    if not can_undo(record, now):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Merges can be undone for {UNDO_WINDOW.days} days. This one is older.",
        )
    survivor_id, merged_id = record.survivorId, record.mergedId
    survivor_row = await db.artisan.find_unique(where={"id": survivor_id})
    if survivor_row is None:
        # Gone because a later merge folded it into another record, or because someone deleted it.
        later = await db.artisanmerge.find_first(where={"mergedId": survivor_id, "status": STATUS_DONE})
        if later is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"{record.survivorName} has since been merged into {later.survivorName}. "
                "Undo that merge first.",
            )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="The kept artisan has since been deleted, so this merge cannot be undone.",
        )
    before = unjson(record.survivorBefore) or {}
    after = unjson(record.survivorAfter) or {}
    merged_snap = unjson(record.mergedSnapshot) or {}
    moves = unjson(record.moves) or {}
    current = columns_of(survivor_row)
    kept: list[str] = []

    # 1. Put the survivor's fields back — only those still as the merge left them.
    stamps = set(before.get("__datetimes") or [])
    revert: dict[str, Any] = {}
    for field in FIELDS:
        for column in field.columns:
            if values_match(before.get(column), after.get(column)):
                continue
            now_value = jsonable_encoder(current.get(column))
            if values_match(now_value, after.get(column)):
                value = before.get(column)
                revert[column] = _parse_stamp(value) if column in stamps else value
            elif field.label not in kept:
                kept.append(field.label)
    meta_now = current.get("extraMetadata") or {}
    stripped = _strip_merge_note(meta_now, record.id)
    if jsonable_encoder(meta_now) != jsonable_encoder(stripped):
        # The merge's own note is removed; everything else written since stays.
        revert["extraMetadata"] = Json(stripped)
    if revert:
        await db.artisan.update(where={"id": survivor_id}, data=revert)
        plain = {k: v for k, v in revert.items() if k != "extraMetadata"}
        if plain:
            await db.recordrevision.create(
                data={
                    "recordType": "artisan",
                    "recordId": survivor_id,
                    "editedById": get_value(user, "id"),
                    "changes": Json(_ledger(current, plain)),
                }
            )

    # 2. Bring the merged artisan back, with its own id.
    if await db.artisan.find_unique(where={"id": merged_id}) is None:
        drop: list[str] = []
        for column in (aadhaar_fingerprint.FINGERPRINT_FIELD, *_LEGACY_AADHAAR, "pehchanCardNumber"):
            value = merged_snap.get(column)
            if value:
                holder = await db.artisan.find_first(where={column: value})
                if holder is not None:
                    if column == "pehchanCardNumber":
                        drop.append(column)
                        kept.append("Pehchan card (still on the kept record)")
                    else:
                        # The Aadhaar's columns go together, or the row would carry half an identity.
                        drop.extend(c for c in _AADHAAR_COLUMNS if c not in drop)
                        if "Aadhaar (still on the kept record)" not in kept:
                            kept.append("Aadhaar (still on the kept record)")
        if not aadhaar_fingerprint.LEGACY_COLUMN_PRESENT:
            drop.append(aadhaar_fingerprint.NUMBER_FIELD)
        await db.artisan.create(data=restorable(merged_snap, drop=drop))

    # 3. Move every child back that still sits where the merge put it.
    for name, table_name in _LINK_TABLES.items():
        entry = moves.get(name) or {}
        table = getattr(db, table_name)
        await _relink(table, entry.get("column", ""), entry.get("moved") or [], frm=survivor_id, to=merged_id)
        await _recreate(table, entry.get("folded") or [])
    for key, table in (("products", db.productdocumentation), ("tools", db.tooldocumentation)):
        rows = moves.get(key) or []
        await _repoint(table, [row["id"] for row in rows], "artisanId", frm=survivor_id, to=merged_id)
        for row in rows:
            if row.get("rename") and row.get("artisanName"):
                await table.update_many(
                    where={"id": row["id"], "artisanName": record.survivorName}, data={"artisanName": row["artisanName"]}
                )
    await _repoint(db.mediafile, moves.get("media") or [], "artisanId", frm=survivor_id, to=merged_id)
    await _repoint(db.mediafile, moves.get("mediaTagged") or [], "linkedRecordId", frm=survivor_id, to=merged_id)
    await _repoint(db.entrycomment, moves.get("comments") or [], "recordId", frm=survivor_id, to=merged_id)
    await _repoint(db.recordrevision, moves.get("revisions") or [], "recordId", frm=survivor_id, to=merged_id)
    await _repoint(db.reviewlog, moves.get("reviewLogs") or [], "recordId", frm=survivor_id, to=merged_id)
    scope = moves.get("scopeItems") or {}
    await _repoint(db.dataaccessscopeitem, scope.get("moved") or [], "recordId", frm=survivor_id, to=merged_id)
    await _recreate(db.dataaccessscopeitem, scope.get("folded") or [])
    for task in moves.get("tasks") or []:
        row = await db.assignedtask.find_unique(where={"id": task["id"]})
        if row is None:
            continue
        data: dict[str, Any] = {}
        if task["before"] != task["after"] and list(getattr(row, "artisanIds", None) or []) == task["after"]:
            data["artisanIds"] = {"set": task["before"]}
        if task.get("recordId") and getattr(row, "recordId", None) == survivor_id:
            data["recordId"] = merged_id
        if data:
            await db.assignedtask.update(where={"id": task["id"]}, data=data)
    sittings = [entry["id"] for entry in moves.get("interviewKeys") or []]
    if sittings:
        links = await db.questionnaireinterviewartisan.find_many(where={"interviewId": {"in": sittings}})
        members: dict[str, set[str]] = {}
        for link in links:
            members.setdefault(link.interviewId, set()).add(link.artisanId)
        for interview_id in sittings:
            try:
                await db.questionnaireinterview.update(
                    where={"id": interview_id}, data={"artisanSetKey": artisan_set_key(members.get(interview_id, set()))}
                )
            except Exception:  # noqa: BLE001 - a sitting whose set is taken again keeps its key
                kept.append("An interview's artisan set")

    done = await db.artisanmerge.update(
        where={"id": record.id},
        data={
            "status": STATUS_UNDONE,
            "undoneAt": now,
            "undoneById": get_value(user, "id"),
            "undoNotes": Json({"kept": kept}),
        },
    )
    forget_redirects()
    return merge_summary(done or record, now=now)


def _strip_merge_note(meta: Any, merge_id: str) -> dict[str, Any]:
    meta = dict(meta or {})
    notes = [note for note in (meta.get("mergedArtisans") or []) if note.get("mergeId") != merge_id]
    if notes:
        meta["mergedArtisans"] = notes
    else:
        meta.pop("mergedArtisans", None)
    return meta


# ---------------------------------------------------------------------------------------------
# Likely duplicates
# ---------------------------------------------------------------------------------------------

_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)


def fold_name(value: Any) -> str:
    """A name as compared for duplicates: case, spacing and punctuation folded away."""
    return " ".join(_NON_WORD.sub(" ", str(value or "")).casefold().split())


def phone_key(value: Any) -> str | None:
    digits = re.sub(r"\D", "", str(value or ""))
    return digits[-10:] if len(digits) >= 10 else None


def find_candidates(rows: list[Any], limit: int = 50) -> tuple[list[dict[str, Any]], bool]:
    """Pairs that look like one person: the same phone number, or the same name in the same place.

    Pure over rows, so it is tested without a database. A pair is reported once, older record first
    (the one usually worth keeping).
    """
    def created(row: Any) -> datetime:
        return _aware(getattr(row, "createdAt", None)) or datetime.min.replace(tzinfo=UTC)

    ordered = sorted(rows, key=created)
    groups: dict[tuple[str, str], list[Any]] = {}
    for row in ordered:
        phone = phone_key(getattr(row, "phone", None))
        if phone:
            groups.setdefault(("phone", phone), []).append(row)
        name = fold_name(getattr(row, "name", None))
        if name:
            groups.setdefault(("name", f"{name}|{fold_name(getattr(row, 'place', None))}"), []).append(row)
    seen: set[tuple[str, str]] = set()
    pairs: list[dict[str, Any]] = []
    for (reason, _key), members in groups.items():
        if len(members) < 2:
            continue
        first = members[0]
        for other in members[1:]:
            pair = (first.id, other.id)
            if pair in seen:
                continue
            seen.add(pair)
            pairs.append(
                {
                    "keepId": first.id,
                    "mergeId": other.id,
                    "reason": "Same phone number" if reason == "phone" else "Same name and place",
                    "keep": {"id": first.id, "name": first.name, "place": first.place},
                    "merge": {"id": other.id, "name": other.name, "place": other.place},
                }
            )
    pairs.sort(key=lambda pair: pair["keep"]["name"] or "")
    return pairs[:limit], len(pairs) > limit


async def merge_candidates(limit: int = 50) -> dict[str, Any]:
    rows = await db.artisan.find_many(order={"createdAt": "asc"})
    pairs, truncated = find_candidates(list(rows), limit)
    return {"items": pairs, "truncated": truncated}


# ---------------------------------------------------------------------------------------------
# The redirect — queued writes that still name a merged artisan
# ---------------------------------------------------------------------------------------------

_REDIRECT_TTL_SECONDS = 60.0
_redirects: dict[str, Any] = {"at": None, "map": {}}


def forget_redirects() -> None:
    _redirects["at"] = None
    _redirects["map"] = {}


async def merged_redirects() -> dict[str, str]:
    """{merged id: surviving id}, chains followed, cached for a minute in this process.

    A merge or undo in this process clears the cache at once; the minute only bounds how stale a
    second process can be.
    """
    at = _redirects["at"]
    if at is not None and monotonic_time.monotonic() - at < _REDIRECT_TTL_SECONDS:
        return _redirects["map"]
    try:
        rows = await db.artisanmerge.find_many(where={"status": STATUS_DONE})
    except Exception:  # noqa: BLE001 - a redirect must never be the reason a write fails
        # (A process running before the migration, or a database blip: no redirect, and ask again
        # next time rather than caching the failure for a minute.)
        return {}
    direct = {row.mergedId: row.survivorId for row in rows}
    resolved: dict[str, str] = {}
    for merged in direct:
        target, hops = direct[merged], 0
        while target in direct and hops < 20:
            target, hops = direct[target], hops + 1
        resolved[merged] = target
    _redirects["map"] = resolved
    _redirects["at"] = monotonic_time.monotonic()
    return resolved


def _names_ids(payload: Any) -> bool:
    for key in ("artisanId", "artisanIds", "linkedRecordId"):
        if getattr(payload, key, None):
            return True
    return False


async def redirect_merged_artisans(payload: Any) -> Any:
    """Point a request body's artisan ids at the survivor of any merge, in place, and return it.

    THE OFFLINE REPLAY CASE. A phone that saved a product against an artisan, then sat in a village
    with no signal while an admin merged that artisan away, replays the save later naming an id that
    no longer exists — a foreign-key refusal, and an outbox entry stuck for ever. The survivor IS that
    person, so the write lands there. Only fields the caller actually sent are touched, so a PATCH's
    ``exclude_unset`` reading is unchanged.
    """
    if payload is None or not _names_ids(payload):
        return payload
    redirects = await merged_redirects()
    if not redirects:
        return payload
    single = getattr(payload, "artisanId", None)
    if isinstance(single, str) and single in redirects:
        payload.artisanId = redirects[single]
    many = getattr(payload, "artisanIds", None)
    if isinstance(many, list) and any(aid in redirects for aid in many):
        out: list[str] = []
        for aid in many:
            aid = redirects.get(aid, aid)
            if aid not in out:
                out.append(aid)
        payload.artisanIds = out
    tagged = getattr(payload, "linkedRecordId", None)
    kind = str(getattr(payload, "linkedRecordType", "") or "").strip().lower()
    if isinstance(tagged, str) and kind == "artisan" and tagged in redirects:
        payload.linkedRecordId = redirects[tagged]
    return payload


_LEGACY_AADHAAR: tuple[str, ...] = (
    (aadhaar_fingerprint.NUMBER_FIELD,) if aadhaar_fingerprint.LEGACY_COLUMN_PRESENT else ()
)
#: The unique identity columns a merge frees on the merged row before the survivor takes them.
_IDENTITY_COLUMNS = (aadhaar_fingerprint.FINGERPRINT_FIELD, *_LEGACY_AADHAAR, "pehchanCardNumber")
#: The columns that hold a raw identity number, which a snapshot keeps only for its undo window.
#: ``aadhaarNumber`` stays named after the column's drop: a snapshot taken before it is JSON and
#: still holds the number until it is scrubbed.
_RAW_IDENTITY_COLUMNS = (aadhaar_fingerprint.NUMBER_FIELD, "pehchanCardNumber")


def _ledger(before: dict[str, Any], changes: dict[str, Any]) -> dict[str, Any]:
    """The edit-history entry for a merge or its undo. The Aadhaar is recorded as changed, never by
    value: one valueless entry under ``aadhaarNumber``, as every other artisan edit records it."""
    out = {column: {"old": jsonable_encoder(before.get(column)), "new": jsonable_encoder(value)} for column, value in changes.items()}
    if any(column in out for column in _AADHAAR_COLUMNS):
        for column in _AADHAAR_COLUMNS:
            out.pop(column, None)
        after = {**before, **changes}
        out[aadhaar_fingerprint.NUMBER_FIELD] = aadhaar_fingerprint.ledger_entry(
            aadhaar_fingerprint.has_aadhaar(before), aadhaar_fingerprint.has_aadhaar(after)
        )
    return out


def _scrub(snap: Any) -> dict[str, Any]:
    snap = dict(snap or {})
    if snap.get("aadhaarNumber"):
        snap["aadhaarNumber"] = mask_aadhaar(snap["aadhaarNumber"])
    if snap.get("pehchanCardNumber"):
        snap["pehchanCardNumber"] = "XXXX" + str(snap["pehchanCardNumber"])[-4:]
    return snap


async def scrub_expired_merge_snapshots(now: datetime | None = None) -> int:
    """Mask the identity numbers held in merges past their undo window. Returns how many.

    The snapshots carry the raw numbers only so an undo can put them back; once an undo is no longer
    possible there is no reason to keep a second copy of anybody's Aadhaar. Run nightly by the queue
    worker beside the workshop filing.
    """
    now = now or datetime.now(UTC)
    rows = await db.artisanmerge.find_many(where={"createdAt": {"lt": now - UNDO_WINDOW}})
    scrubbed = 0
    for row in rows:
        if row.status == STATUS_IN_PROGRESS:
            continue
        merged = unjson(row.mergedSnapshot) or {}
        before = unjson(row.survivorBefore) or {}
        after = unjson(row.survivorAfter) or {}
        raw_left = any(
            part.get(column) and not str(part.get(column)).startswith("XXXX")
            for part in (merged, before, after)
            for column in _RAW_IDENTITY_COLUMNS
        )
        if not raw_left:
            continue
        await db.artisanmerge.update(
            where={"id": row.id},
            data={
                "mergedSnapshot": Json(_scrub(merged)),
                "survivorBefore": Json(_scrub(before)),
                "survivorAfter": Json(_scrub(after)),
            },
        )
        scrubbed += 1
    return scrubbed


async def survivor_of(artisan_id: str) -> str | None:
    """The artisan a merged-away id now lives on, or None."""
    return (await merged_redirects()).get(artisan_id)


async def created_merged_record(merged_id: str, user_id: str | None) -> bool:
    """Whether ``user_id`` created the artisan record ``merged_id`` before it was merged away.

    THE QUEUED-REPLAY POLICY. A researcher's phone queues an edit of the artisan THEY recorded; an
    admin then merges that artisan into a duplicate someone else recorded. The redirect lands the
    edit on the survivor, which the researcher does not own, so the ordinary owner check would
    refuse it and the entry would sit in their outbox for ever. They recorded this same person, and
    the merge folded their record into this one, so a write that names THEIR old id is treated as
    theirs. A write naming the survivor's own id gets no such allowance.
    """
    if not user_id:
        return False
    try:
        rows = await db.artisanmerge.find_many(where={"mergedId": merged_id, "status": STATUS_DONE})
    except Exception:  # noqa: BLE001 - never the reason a write fails
        return False
    return any((unjson(row.mergedSnapshot) or {}).get("createdById") == user_id for row in rows)
