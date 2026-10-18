"""Merge two records of one artisan, keep everything either held, and undo it within 30 days.

AN ADMIN ACTION, NEVER AUTOMATIC. ``POST /artisan-duplicates/{id}/merge`` with a survivor and a side
per field. One transaction (30 s) does, in order:

1. lock both rows, check ``expectedUpdatedAt`` and the blockers;
2. snapshot the record that will be deleted (``to_jsonb`` of the row, minus ``aadhaarNumber``,
   which a merge refuses to touch while it is populated), the survivor's columns and both
   sharing-consent rows;
3. clear the deleted record's unique keys, then write the chosen values onto the survivor;
4. re-point every row that names the deleted record (:data:`MERGE_REPOINT`) with raw ``UPDATE``
   ``... RETURNING``, logging every moved key in ``ArtisanMerge.moves``. Where a unique key would
   collide, the deleted record's row is deleted and snapshotted (the survivor's row wins). The
   designer portal's links (``CrossPortalArtisanLink``) move the same way, de-duplicated;
5. sharing consent: the more recent of the two decisions wins and is kept on the survivor. If that
   decision is a withdrawal, the survivor's designer-portal links are removed, as a withdrawal
   always does (``routes/cross_portal.set_consent``);
6. a ``RecordRevision`` on the survivor naming the merge and the side taken for each field;
7. hard-delete the merged row (its duplicate pairs cascade away).

After the commit the survivor is re-scored.

A MERGED ID KEEPS WORKING. :func:`resolve` follows ``ArtisanMerge.mergedId`` -> ``survivorId``
(skipping undone merges, up to five hops): ``GET /artisans/{id}`` answers 404 ``artisan_merged`` with
the survivor's id, :func:`redirect_payload` / :func:`redirect_ids` rewrite a write that still names
the old id (an offline queue, a stale picker), and the designer portal's peer calls resolve a merged
id the same way (``routes/cross_portal``).

UNDO puts the deleted row back with its own id from the snapshot, sets each survivor column back
only where it still holds what the merge wrote (anything edited since is kept and reported), moves
back only the logged rows still pointing at the survivor, re-inserts deleted collision rows whose
key is still free and restores both consent rows. After 30 days :func:`maybe_purge` clears the
snapshot and the move log; the merge record and its redirect stay forever.

``tests/test_artisan_merge_registry.py`` parses ``schema.prisma`` and fails when a table that points
at Artisan, or carries ``recordType``/``recordId``, is in neither :data:`MERGE_REPOINT`,
:data:`SPECIAL_REPOINT` nor :data:`NOT_REPOINTED`.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException
from prisma import Json

from app.core.db import db
from app.services import aadhaar_fingerprint, artisan_duplicates

logger = logging.getLogger("app.artisan_merge")

UNDO_DAYS = artisan_duplicates.UNDO_DAYS
TX_TIMEOUT = timedelta(seconds=30)
TX_MAX_WAIT = timedelta(seconds=10)
RESOLVE_MAX_HOPS = 5

# ── The registry ──────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Repoint:
    """One column that can name an artisan. ``key`` identifies a row apart from that column (text
    columns); ``unique_with`` are the other columns of a unique key the column is part of;
    ``where`` narrows a polymorphic table to artisan rows; ``name_column`` is a printed copy of the
    artisan's name kept in step (its old value is logged)."""

    table: str
    column: str
    key: tuple[str, ...] = ("id",)
    where: str = "TRUE"
    unique_with: tuple[str, ...] = ()
    name_column: str | None = None
    note: str = ""


MERGE_REPOINT: tuple[Repoint, ...] = (
    Repoint("ProductDocumentation", "artisanId", name_column="artisanName"),
    Repoint("ToolDocumentation", "artisanId", name_column="artisanName"),
    Repoint("MediaFile", "artisanId"),
    Repoint(
        "MediaFile",
        "linkedRecordId",
        where="lower(coalesce(t.\"linkedRecordType\", '')) = 'artisan'",
        note="the tag a media row was captured against",
    ),
    Repoint("WorkshopArtisan", "artisanId", key=("workshopId",), unique_with=("workshopId",)),
    Repoint("ToolArtisan", "artisanId", unique_with=("toolId",)),
    Repoint(
        "QuestionnaireInterviewArtisan",
        "artisanId",
        key=("interviewId",),
        unique_with=("interviewId",),
        note="artisanSetKey is recomputed for every interview touched",
    ),
    Repoint("QuestionnaireSectionStatus", "artisanId", unique_with=("sectionId",)),
    Repoint("RecordRevision", "recordId", where="lower(t.\"recordType\") = 'artisan'"),
    Repoint("ReviewLog", "recordId", where="t.\"recordType\"::text = 'ARTISAN'"),
    Repoint("EntryComment", "recordId", where="lower(t.\"recordType\") = 'artisan'"),
    Repoint(
        "DataAccessScopeItem",
        "recordId",
        where="lower(t.\"recordType\") = 'artisan'",
        unique_with=("grantId", "recordType"),
    ),
    Repoint("AssignedTask", "recordId", where="lower(coalesce(t.\"recordType\", '')) = 'artisan'"),
    Repoint(
        "CrossPortalArtisanLink",
        "artisanId",
        unique_with=("remoteArtisanId",),
        note="a designer-portal link; one per remote record, so a duplicate is dropped",
    ),
)

#: Handled by dedicated code in this module rather than by the generic repoint.
SPECIAL_REPOINT = {
    "AssignedTask.artisanIds": "array of ids; replaced in place, de-duplicated, old list logged",
    "ArtisanSharingConsent.artisanId": "the more recent decision wins; both rows are snapshotted",
}

#: Tables that name an artisan and are deliberately not re-pointed, and why.
NOT_REPOINTED = {
    "CrossPortalAccessLog": "an access log is a fact about what happened to that id",
    "ArtisanDuplicateCandidate": "the merged record's pairs cascade away with it",
    "ArtisanMerge": "the merge record itself",
}


def _q(name: str) -> str:
    return '"' + name.replace('"', "") + '"'


# ── Fields ────────────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class FieldGroup:
    key: str
    label: str
    columns: tuple[str, ...]
    both: bool = False
    locked: bool = False


FIELD_GROUPS: tuple[FieldGroup, ...] = (
    FieldGroup("name", "Name", ("name",)),
    FieldGroup("localName", "Local name", ("localName",)),
    FieldGroup("gender", "Gender", ("gender",)),
    FieldGroup("phone", "Phone", ("phone",)),
    FieldGroup("email", "Email", ("email",)),
    FieldGroup("place", "Place", ("place",)),
    FieldGroup("dateOfBirth", "Date of birth", ("dateOfBirth",)),
    FieldGroup("address", "Address", ("address",), both=True),
    FieldGroup("notes", "Notes", ("notes",), both=True),
    FieldGroup("dos", "Do's", ("dos",), both=True),
    FieldGroup("donts", "Don'ts", ("donts",), both=True),
    FieldGroup("experience", "Experience", ("craftStartDate", "experienceYears", "experienceMonths")),
    FieldGroup("craftId", "Craft", ("craftId",)),
    FieldGroup("location", "Location", ("locationId",)),
    FieldGroup("workshopId", "Workshop", ("workshopId",)),
    FieldGroup("status", "Review status", ("status", "reviewNotes", "reviewedById", "reviewedAt")),
    FieldGroup("aadhaar", "Aadhaar", ("aadhaarFingerprint", "aadhaarLast4"), locked=True),
    FieldGroup("pehchan", "Pehchan card", ("pehchanCardAvailable", "pehchanCardNumber"), locked=True),
)
FIELD_KEYS = {g.key: g for g in FIELD_GROUPS}
UNIQUE_KEYS = ("aadhaarFingerprint", "aadhaarNumber", "pehchanCardNumber")
_STATUS_RANK = {"APPROVED": 4, "PENDING": 3, "NEEDS_REVISION": 2, "DRAFT": 1, "REJECTED": 0}


def _empty(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return not value.strip()
    return False


def _group_empty(group: FieldGroup, row: dict[str, Any]) -> bool:
    if group.key == "pehchan":
        return _empty(row.get("pehchanCardNumber"))
    if group.key == "aadhaar":
        return _empty(row.get("aadhaarFingerprint"))
    if group.key == "status":
        return _empty(row.get("status"))
    return all(_empty(row.get(col)) for col in group.columns)


def _group_value(group: FieldGroup, row: dict[str, Any]) -> tuple[Any, ...]:
    return tuple(row.get(col) for col in group.columns)


def _side(letter: str, a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any]:
    return a if letter == "A" else b


def _other(letter: str) -> str:
    return "B" if letter == "A" else "A"


def default_choice(group: FieldGroup, survivor: str, a: dict[str, Any], b: dict[str, Any]) -> str:
    """The side a field takes when the admin leaves it alone."""
    keep, other = _side(survivor, a, b), _side(_other(survivor), a, b)
    if group.locked:
        if not _group_empty(group, keep):
            return survivor
        return _other(survivor) if not _group_empty(group, other) else survivor
    if group.key == "status":
        rk = _STATUS_RANK.get(str(keep.get("status")), -1)
        ro = _STATUS_RANK.get(str(other.get("status")), -1)
        return survivor if rk >= ro else _other(survivor)
    if _group_empty(group, keep):
        return survivor if _group_empty(group, other) else _other(survivor)
    if (
        group.both
        and not _group_empty(group, other)
        and _group_value(group, keep) != _group_value(group, other)
    ):
        return "BOTH"
    return survivor


def options_for(group: FieldGroup, survivor: str, a: dict[str, Any], b: dict[str, Any]) -> list[str]:
    if group.locked:
        return [default_choice(group, survivor, a, b)]
    return ["A", "B", "BOTH"] if group.both else ["A", "B"]


def _join_text(first: Any, second: Any) -> Any:
    if _empty(first):
        return second
    if _empty(second):
        return first
    if first == second:
        return first
    return f"{first}\n{second}"


def _deep_merge(keep: Any, other: Any) -> Any:
    """Survivor keys win; keys only the other record has are added; nested dicts merge the same way."""
    if not isinstance(keep, dict):
        return keep if keep is not None else other
    if not isinstance(other, dict):
        return keep
    out = dict(keep)
    for key, value in other.items():
        out[key] = value if key not in out else _deep_merge(out[key], value)
    return out


def plan_writes(
    survivor: str, choices: dict[str, str], a: dict[str, Any], b: dict[str, Any]
) -> tuple[dict[str, Any], dict[str, str]]:
    """The columns to write onto the survivor, and the side each field took. Pure."""
    keep, other = _side(survivor, a, b), _side(_other(survivor), a, b)
    taken: dict[str, str] = {}
    writes: dict[str, Any] = {}
    for group in FIELD_GROUPS:
        choice = choices.get(group.key) or default_choice(group, survivor, a, b)
        taken[group.key] = choice
        if choice == "BOTH":
            for col in group.columns:
                joined = _join_text(a.get(col), b.get(col))
                if joined != keep.get(col):
                    writes[col] = joined
            continue
        source = _side(choice, a, b)
        if source is keep:
            continue
        for col in group.columns:
            if source.get(col) != keep.get(col):
                writes[col] = source.get(col)
    # Field provenance follows the side chosen; everything else in extraMetadata deep-merges.
    meta = _deep_merge(keep.get("extraMetadata"), other.get("extraMetadata"))
    if isinstance(meta, dict):
        keep_meta = keep.get("extraMetadata") if isinstance(keep.get("extraMetadata"), dict) else {}
        other_meta = other.get("extraMetadata") if isinstance(other.get("extraMetadata"), dict) else {}
        provenance_keep = keep_meta.get("fieldProvenance")
        provenance_other = other_meta.get("fieldProvenance")
        provenance = dict(provenance_keep) if isinstance(provenance_keep, dict) else {}
        for group in FIELD_GROUPS:
            if taken[group.key] != _other(survivor):
                continue
            for col in group.columns:
                stamp = provenance_other.get(col) if isinstance(provenance_other, dict) else None
                if stamp is not None:
                    provenance[col] = stamp
                else:
                    provenance.pop(col, None)
        if provenance or "fieldProvenance" in meta:
            meta["fieldProvenance"] = provenance
    if meta != keep.get("extraMetadata"):
        writes["extraMetadata"] = meta
    return writes, taken


def validate_choices(
    choices: dict[str, str], survivor: str, a: dict[str, Any], b: dict[str, Any]
) -> None:
    for key, value in choices.items():
        group = FIELD_KEYS.get(key)
        if group is None:
            raise HTTPException(
                status_code=422,
                detail={"code": "merge_bad_choice", "message": f"Unknown field: {key}.", "field": key},
            )
        options = options_for(group, survivor, a, b)
        if value not in options:
            message = (
                f"{group.label} comes from whichever record holds it."
                if group.locked
                else f"{group.label} can be {', '.join(options)}."
            )
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "merge_locked_choice" if group.locked else "merge_bad_choice",
                    "message": message,
                    "field": key,
                },
            )


# ── Reading rows ──────────────────────────────────────────────────────────────────────────────


def jsonish(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


async def _rows(client: Any, ids: list[str], *, lock: bool = False) -> dict[str, dict[str, Any]]:
    sql = 'SELECT to_jsonb(a) AS "row" FROM "Artisan" a WHERE a."id" = ANY($1::text[])'
    if lock:
        sql += ' ORDER BY a."id" FOR UPDATE'
    rows = await client.query_raw(sql, ids)
    out: dict[str, dict[str, Any]] = {}
    for row in rows:
        data = jsonish(row["row"])
        if isinstance(data, dict):
            data["extraMetadata"] = jsonish(data.get("extraMetadata"))
            out[data["id"]] = data
    return out


async def _consents(client: Any, ids: list[str]) -> dict[str, dict[str, Any]]:
    rows = await client.query_raw(
        'SELECT to_jsonb(c) AS "row" FROM "ArtisanSharingConsent" c WHERE c."artisanId" = ANY($1::text[])',
        ids,
    )
    out: dict[str, dict[str, Any]] = {}
    for row in rows:
        data = jsonish(row["row"])
        if isinstance(data, dict):
            out[data["artisanId"]] = data
    return out


def parse_ts(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    text = str(value).replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _same_instant(a: Any, b: Any) -> bool:
    pa, pb = parse_ts(a), parse_ts(b)
    if pa is None or pb is None:
        return pa is pb
    return abs((pa - pb).total_seconds()) < 0.001


# ── Redirects ─────────────────────────────────────────────────────────────────────────────────


async def resolve(artisan_id: str | None, *, client: Any = None) -> str | None:
    """The survivor a merged id now lives on, or None when ``artisan_id`` was never merged (or the
    merge was undone). Follows a chain of up to five merges."""
    if not artisan_id:
        return None
    reader = client or db
    current = artisan_id
    moved = False
    for _ in range(RESOLVE_MAX_HOPS):
        row = await reader.artisanmerge.find_first(where={"mergedId": current, "undoneAt": None})
        if row is None:
            break
        current = row.survivorId
        moved = True
    return current if moved else None


async def merged_among(ids: list[str], *, client: Any = None) -> set[str]:
    """Which of ``ids`` were merged away (and not undone). One read."""
    wanted = sorted({i for i in ids if isinstance(i, str) and i})
    if not wanted:
        return set()
    reader = client or db
    rows = await reader.artisanmerge.find_many(where={"mergedId": {"in": wanted}, "undoneAt": None})
    return {row.mergedId for row in rows}


async def redirect_ids(ids: list[str] | None, *, client: Any = None) -> list[str] | None:
    """``ids`` with every merged id replaced by its survivor, order kept, duplicates dropped."""
    if not ids:
        return ids
    merged = await merged_among(ids, client=client)
    if not merged:
        return ids
    out: list[str] = []
    for value in ids:
        target = await resolve(value, client=client) if value in merged else None
        final = target or value
        if final not in out:
            out.append(final)
    return out


async def redirect_payload(data: dict[str, Any] | None, *, client: Any = None) -> dict[str, Any] | None:
    """Rewrite ``artisanId``, ``artisanIds`` and an artisan-tagged ``linkedRecordId`` in place."""
    if not isinstance(data, dict):
        return data
    if isinstance(data.get("artisanId"), str):
        target = await resolve(data["artisanId"], client=client)
        if target:
            data["artisanId"] = target
    if isinstance(data.get("artisanIds"), list):
        data["artisanIds"] = await redirect_ids(data["artisanIds"], client=client)
    if str(data.get("linkedRecordType") or "").lower() == "artisan" and isinstance(
        data.get("linkedRecordId"), str
    ):
        target = await resolve(data["linkedRecordId"], client=client)
        if target:
            data["linkedRecordId"] = target
    return data


async def redirect_model(model: Any, *, client: Any = None) -> Any:
    """:func:`redirect_payload` for a request model, before the route reads it.

    Routes read ``artisanIds`` both from the dumped dict and from the model itself (the tool and
    interview link writers, the idempotent replays), so the model is rewritten in place and every
    later read agrees. Only a field the caller sent can change, so ``exclude_unset`` is unaffected."""
    if model is None:
        return model
    value = getattr(model, "artisanId", None)
    if isinstance(value, str):
        target = await resolve(value, client=client)
        if target:
            model.artisanId = target
    ids = getattr(model, "artisanIds", None)
    if isinstance(ids, list) and ids:
        redirected = await redirect_ids(ids, client=client)
        if redirected != ids:
            model.artisanIds = redirected
    linked = getattr(model, "linkedRecordId", None)
    if isinstance(linked, str) and str(getattr(model, "linkedRecordType", "") or "").lower() == "artisan":
        target = await resolve(linked, client=client)
        if target:
            model.linkedRecordId = target
    return model


def merged_detail(survivor_id: str) -> dict[str, Any]:
    return {
        "code": "artisan_merged",
        "message": "This artisan was merged into another record.",
        "survivorId": survivor_id,
    }


async def raise_if_merged(artisan_id: str) -> None:
    """404 ``artisan_merged`` when ``artisan_id`` names a merged record; nothing otherwise."""
    survivor = await resolve(artisan_id)
    if survivor:
        raise HTTPException(status_code=404, detail=merged_detail(survivor))


# ── Blockers ──────────────────────────────────────────────────────────────────────────────────


async def _interview_plan(
    client: Any, merged_id: str, survivor_id: str
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """The new ``artisanSetKey`` of every interview the merged record sits in, and the collisions.

    One interview per artisan set PER INSTRUMENT (``@@unique([questionnaireId, artisanSetKey])``),
    so a collision is a second interview of the same instrument already covering the new set."""
    from app.api.routes.questionnaire import artisan_set_key

    links = await client.questionnaireinterviewartisan.find_many(where={"artisanId": merged_id})
    interview_ids = sorted({link.interviewId for link in links})
    if not interview_ids:
        return [], []
    interviews = await client.questionnaireinterview.find_many(
        where={"id": {"in": interview_ids}}, include={"artisans": True}
    )
    plan: list[dict[str, Any]] = []
    for interview in interviews:
        ids = [
            survivor_id if link.artisanId == merged_id else link.artisanId
            for link in interview.artisans or []
        ]
        plan.append(
            {
                "id": interview.id,
                "title": interview.title,
                "questionnaireId": interview.questionnaireId,
                "from": interview.artisanSetKey,
                "to": artisan_set_key(ids),
            }
        )
    collisions: list[dict[str, Any]] = []
    moving = {p["id"] for p in plan}
    seen: dict[tuple[str, str], dict[str, Any]] = {}
    for entry in plan:
        key = entry["to"]
        if not key:
            continue
        scoped = (entry["questionnaireId"], key)
        if scoped in seen:
            collisions.append(
                {
                    "sourceId": entry["id"],
                    "targetId": seen[scoped]["id"],
                    "title": entry["title"],
                    "targetTitle": seen[scoped]["title"],
                }
            )
            continue
        seen[scoped] = entry
        holder = await client.questionnaireinterview.find_first(
            where={"questionnaireId": entry["questionnaireId"], "artisanSetKey": key}
        )
        if holder is not None and holder.id not in moving:
            collisions.append(
                {
                    "sourceId": entry["id"],
                    "targetId": holder.id,
                    "title": entry["title"],
                    "targetTitle": holder.title,
                }
            )
    return plan, collisions


async def blockers_for(
    client: Any, a: dict[str, Any], b: dict[str, Any], survivor: str, *, pair_id: str | None = None
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    fa, fb = a.get("aadhaarFingerprint"), b.get("aadhaarFingerprint")
    if fa and fb and fa != fb:
        out.append({"code": "different_aadhaar", "message": "The two records hold different Aadhaar numbers."})
    pa, pb = a.get("pehchanCardNumber"), b.get("pehchanCardNumber")
    if pa and pb and pa != pb:
        out.append(
            {"code": "different_pehchan", "message": "The two records hold different Pehchan card numbers."}
        )
    if aadhaar_fingerprint.LEGACY_COLUMN_PRESENT and (a.get("aadhaarNumber") or b.get("aadhaarNumber")):
        out.append(
            {
                "code": "aadhaar_backfill_pending",
                "message": "An Aadhaar number on one record is still waiting for the fingerprint backfill.",
            }
        )
    keep, gone = (a, b) if survivor == "A" else (b, a)
    _, collisions = await _interview_plan(client, gone["id"], keep["id"])
    for clash in collisions:
        out.append(
            {
                "code": "interview_set_collision",
                "message": (
                    f"“{clash['title']}” would cover the same artisans as “{clash['targetTitle']}”. "
                    "Merge the two interviews first."
                ),
                "action": {
                    "kind": "merge_interviews",
                    "sourceId": clash["sourceId"],
                    "targetId": clash["targetId"],
                    **({"duplicatePairId": pair_id} if pair_id else {}),
                },
            }
        )
    return out


# ── Preview ───────────────────────────────────────────────────────────────────────────────────

RELATED_KEYS = ("media", "products", "tools", "workshops", "interviews", "comments", "revisions", "reviewLogs")


async def related_counts(client: Any, artisan_id: str) -> dict[str, int]:
    rows = await client.query_raw(
        """SELECT
          (SELECT count(*) FROM "MediaFile" m WHERE m."artisanId" = $1
             OR (lower(coalesce(m."linkedRecordType", '')) = 'artisan' AND m."linkedRecordId" = $1)) AS "media",
          (SELECT count(*) FROM "ProductDocumentation" p WHERE p."artisanId" = $1) AS "products",
          (SELECT count(*) FROM "ToolDocumentation" t WHERE t."artisanId" = $1
             OR EXISTS (SELECT 1 FROM "ToolArtisan" x WHERE x."toolId" = t."id" AND x."artisanId" = $1)) AS "tools",
          (SELECT count(*) FROM "WorkshopArtisan" w WHERE w."artisanId" = $1) AS "workshops",
          (SELECT count(*) FROM "QuestionnaireInterviewArtisan" q WHERE q."artisanId" = $1) AS "interviews",
          (SELECT count(*) FROM "EntryComment" c WHERE lower(c."recordType") = 'artisan' AND c."recordId" = $1) AS "comments",
          (SELECT count(*) FROM "RecordRevision" r WHERE lower(r."recordType") = 'artisan' AND r."recordId" = $1) AS "revisions",
          (SELECT count(*) FROM "ReviewLog" l WHERE l."recordType"::text = 'ARTISAN' AND l."recordId" = $1) AS "reviewLogs"
        """,
        artisan_id,
    )
    row = rows[0] if rows else {}
    return {key: int(row.get(key) or 0) for key in RELATED_KEYS}


def default_survivor(a: dict[str, Any], b: dict[str, Any], ca: dict[str, int], cb: dict[str, int]) -> str:
    """The record with more related rows; on a tie, the older one."""
    ta, tb = sum(ca.values()), sum(cb.values())
    if ta != tb:
        return "A" if ta > tb else "B"
    da, db_ = parse_ts(a.get("createdAt")), parse_ts(b.get("createdAt"))
    if da and db_ and db_ < da:
        return "B"
    return "A"


async def _display_maps(client: Any, rows: list[dict[str, Any]]) -> dict[str, dict[str, str]]:
    def ids(col: str) -> list[str]:
        return sorted({r.get(col) for r in rows if r.get(col)})

    crafts = await client.craft.find_many(where={"id": {"in": ids("craftId")}}) if ids("craftId") else []
    locations = (
        await client.location.find_many(where={"id": {"in": ids("locationId")}}) if ids("locationId") else []
    )
    workshops = (
        await client.workshop.find_many(where={"id": {"in": ids("workshopId")}}) if ids("workshopId") else []
    )

    def where_of(loc: Any) -> str:
        parts = [getattr(loc, k, None) for k in ("village", "district", "state")]
        text = ", ".join(p for p in parts if p)
        return text or getattr(loc, "placeName", None) or getattr(loc, "address", None) or "Location on file"

    return {
        "craftId": {c.id: c.name for c in crafts},
        "locationId": {loc.id: where_of(loc) for loc in locations},
        "workshopId": {w.id: w.title for w in workshops},
    }


def _display(group: FieldGroup, row: dict[str, Any], maps: dict[str, dict[str, str]]) -> str | None:
    if group.key == "aadhaar":
        return aadhaar_fingerprint.mask_last4(row.get("aadhaarLast4"))
    if group.key == "pehchan":
        if row.get("pehchanCardNumber"):
            return "Card number on file"
        return "No card" if row.get("pehchanCardAvailable") is False else None
    if group.key == "experience":
        parts = []
        if row.get("experienceYears") is not None:
            parts.append(f"{row['experienceYears']} years")
        if row.get("experienceMonths"):
            parts.append(f"{row['experienceMonths']} months")
        if not parts and row.get("craftStartDate"):
            parts.append(f"Since {str(row['craftStartDate'])[:10]}")
        return " ".join(parts) or None
    if group.key == "status":
        value = row.get("status")
        return str(value).replace("_", " ").capitalize() if value else None
    col = group.columns[0]
    value = row.get(col)
    if value is None or value == "":
        return None
    if col in maps:
        return maps[col].get(value) or "On file"
    if col == "dateOfBirth":
        return str(value)[:10]
    return str(value)


def _side_summary(row: dict[str, Any], maps: dict[str, dict[str, str]]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "updatedAt": row.get("updatedAt"),
        "createdAt": row.get("createdAt"),
        "name": row.get("name"),
        "place": row.get("place"),
        "craft": maps["craftId"].get(row.get("craftId") or ""),
        "status": row.get("status"),
    }


async def load_pair(pair_id: str, *, client: Any = None) -> Any:
    reader = client or db
    pair = await reader.artisanduplicatecandidate.find_unique(where={"id": pair_id})
    if pair is None:
        raise HTTPException(status_code=404, detail="Pair not found")
    return pair


def _enum(value: Any) -> str:
    return str(getattr(value, "value", value))


def consent_view(consents: dict[str, dict[str, Any]], a_id: str, b_id: str) -> dict[str, Any] | None:
    """The decision a merge keeps: the more recent of the two sides' (``from`` names the side)."""
    ca, cb = consents.get(a_id), consents.get(b_id)
    if ca is None and cb is None:
        return None
    if ca is None:
        side, row = "B", cb
    elif cb is None:
        side, row = "A", ca
    else:
        ta, tb = parse_ts(ca.get("recordedAt")), parse_ts(cb.get("recordedAt"))
        side, row = ("B", cb) if (tb and ta and tb > ta) else ("A", ca)
    return {
        "result": "GRANTED" if row.get("granted") else "WITHDRAWN",
        "from": side,
        "recordedAt": row.get("recordedAt"),
    }


async def preview(pair: Any) -> dict[str, Any]:
    """Everything the merge screen draws."""
    base = {
        "id": pair.id,
        "kind": _enum(pair.kind),
        "score": pair.score,
        "band": pair.band,
        "reasons": jsonish(pair.reasons) or [],
        "status": _enum(pair.status),
    }
    if base["kind"] != "WITHIN" or not pair.artisanBId:
        raise HTTPException(status_code=404, detail="Pair not found")
    rows = await _rows(db, [pair.artisanAId, pair.artisanBId])
    a, b = rows.get(pair.artisanAId), rows.get(pair.artisanBId)
    if a is None or b is None:
        raise HTTPException(status_code=404, detail="Pair not found")
    maps = await _display_maps(db, [a, b])
    ca, cb = await related_counts(db, a["id"]), await related_counts(db, b["id"])
    survivor = default_survivor(a, b, ca, cb)
    fields = []
    for group in FIELD_GROUPS:
        entry: dict[str, Any] = {
            "key": group.key,
            "label": group.label,
            "a": _display(group, a, maps),
            "b": _display(group, b, maps),
            "default": default_choice(group, survivor, a, b),
            "options": options_for(group, survivor, a, b),
            "defaults": {"A": default_choice(group, "A", a, b), "B": default_choice(group, "B", a, b)},
        }
        if group.locked:
            entry["locked"] = True
        fields.append(entry)
    links = await db.crossportalartisanlink.find_many(where={"artisanId": {"in": [a["id"], b["id"]]}})
    linked = {
        "a": sum(1 for link in links if link.artisanId == a["id"]),
        "b": sum(1 for link in links if link.artisanId == b["id"]),
    }
    return {
        **base,
        "a": _side_summary(a, maps),
        "b": _side_summary(b, maps),
        "defaultSurvivor": survivor,
        "fields": fields,
        "related": {"a": ca, "b": cb},
        "blockers": await blockers_for(db, a, b, survivor, pair_id=pair.id),
        "warnings": [],
        "consent": consent_view(await _consents(db, [a["id"], b["id"]]), a["id"], b["id"]),
        "linkedFromDesignerPortal": linked,
    }


# ── The merge ─────────────────────────────────────────────────────────────────────────────────


async def _snapshot_collisions(tx: Any, spec: Repoint, merged: str, survivor: str) -> list[dict[str, Any]]:
    if not spec.unique_with:
        return []
    t = _q(spec.table)
    col = _q(spec.column)
    same = " AND ".join(f"s.{_q(c)} = t.{_q(c)}" for c in spec.unique_with)
    s_where = spec.where.replace("t.", "s.")
    rows = await tx.query_raw(
        f'SELECT to_jsonb(t) AS "row" FROM {t} t WHERE t.{col} = $1 AND {spec.where} '
        f"AND EXISTS (SELECT 1 FROM {t} s WHERE s.{col} = $2 AND {s_where} AND {same})",
        merged,
        survivor,
    )
    snaps = [jsonish(r["row"]) for r in rows]
    for snap in snaps:
        key_where = " AND ".join(f"{_q(k)} = ${i + 2}" for i, k in enumerate(spec.key))
        await tx.execute_raw(
            f"DELETE FROM {t} WHERE {col} = $1 AND {key_where}", merged, *[snap[k] for k in spec.key]
        )
    return snaps


async def _repoint(tx: Any, spec: Repoint, merged: str, survivor: str, survivor_name: str) -> list[dict[str, Any]]:
    t = _q(spec.table)
    col = _q(spec.column)
    keys = ", ".join(f"o.{_q(k)}" for k in spec.key)
    join = " AND ".join(f"{t}.{_q(k)} = o.{_q(k)}" for k in spec.key)
    name_select = f', o.{_q(spec.name_column)} AS "__name"' if spec.name_column else ""
    name_old = f", t.{_q(spec.name_column)}" if spec.name_column else ""
    name_set = f", {_q(spec.name_column)} = $3" if spec.name_column else ""
    params: list[Any] = [survivor, merged] + ([survivor_name] if spec.name_column else [])
    sql = (
        f"WITH o AS (SELECT {', '.join(f't.{_q(k)}' for k in spec.key)}{name_old} FROM {t} t "
        f"WHERE t.{col} = $2 AND {spec.where} FOR UPDATE) "
        f"UPDATE {t} SET {col} = $1{name_set} FROM o WHERE {join} "
        f"AND {t}.{col} = $2 RETURNING {keys}{name_select}"
    )
    rows = await tx.query_raw(sql, *params)
    moved = []
    for row in rows:
        entry: dict[str, Any] = {"key": {k: row[k] for k in spec.key}}
        if spec.name_column:
            entry["name"] = row.get("__name")
        moved.append(entry)
    return moved


async def _repoint_task_arrays(tx: Any, merged: str, survivor: str) -> list[dict[str, Any]]:
    rows = await tx.query_raw(
        'SELECT "id", "artisanIds" FROM "AssignedTask" WHERE $1 = ANY("artisanIds") FOR UPDATE', merged
    )
    moved = []
    for row in rows:
        old = list(jsonish(row["artisanIds"]) or [])
        new: list[str] = []
        for value in old:
            final = survivor if value == merged else value
            if final not in new:
                new.append(final)
        await tx.execute_raw(
            'UPDATE "AssignedTask" SET "artisanIds" = $2::text[] WHERE "id" = $1', row["id"], new
        )
        moved.append({"id": row["id"], "from": old, "to": new})
    return moved


async def _recompute_set_keys(tx: Any, interview_ids: list[str]) -> list[dict[str, Any]]:
    from app.api.routes.questionnaire import artisan_set_key

    changed: list[dict[str, Any]] = []
    if not interview_ids:
        return changed
    interviews = await tx.questionnaireinterview.find_many(
        where={"id": {"in": sorted(set(interview_ids))}}, include={"artisans": True}
    )
    # Two passes so a key can move between two interviews of the same merge without a transient clash.
    plans = []
    for interview in interviews:
        new_key = artisan_set_key([link.artisanId for link in interview.artisans or []])
        if new_key != interview.artisanSetKey:
            plans.append((interview.id, interview.artisanSetKey, new_key))
    for interview_id, _, _ in plans:
        await tx.execute_raw(
            'UPDATE "QuestionnaireInterview" SET "artisanSetKey" = NULL WHERE "id" = $1', interview_id
        )
    for interview_id, old, new in plans:
        await tx.execute_raw(
            'UPDATE "QuestionnaireInterview" SET "artisanSetKey" = $2 WHERE "id" = $1', interview_id, new
        )
        changed.append({"id": interview_id, "from": old, "to": new})
    return changed


async def _write_survivor(tx: Any, survivor_id: str, writes: dict[str, Any]) -> None:
    allowed = {c for g in FIELD_GROUPS for c in g.columns} | {"extraMetadata"} | set(UNIQUE_KEYS)
    cols = [c for c in writes if c in allowed]
    if not cols:
        await tx.execute_raw(
            "UPDATE \"Artisan\" SET \"updatedAt\" = (now() AT TIME ZONE 'utc') WHERE \"id\" = $1", survivor_id
        )
        return
    sets = ", ".join(f"{_q(c)} = r.{_q(c)}" for c in cols)
    await tx.execute_raw(
        f"UPDATE \"Artisan\" SET {sets}, \"updatedAt\" = (now() AT TIME ZONE 'utc') "
        f'FROM jsonb_populate_record(NULL::"Artisan", $2::jsonb) r WHERE "Artisan"."id" = $1',
        survivor_id,
        json.dumps({c: writes[c] for c in cols}, default=str),
    )


async def _write_consent(tx: Any, row: dict[str, Any]) -> None:
    """Insert or replace one ``ArtisanSharingConsent`` row from its ``to_jsonb`` snapshot."""
    await tx.execute_raw(
        'INSERT INTO "ArtisanSharingConsent" SELECT * FROM jsonb_populate_record(NULL::"ArtisanSharingConsent", $1::jsonb) '
        'ON CONFLICT ("artisanId") DO UPDATE SET "granted" = EXCLUDED."granted", "note" = EXCLUDED."note", '
        '"recordedById" = EXCLUDED."recordedById", "recordedAt" = EXCLUDED."recordedAt", "updatedAt" = EXCLUDED."updatedAt"',
        json.dumps(row, default=str),
    )


def _http(code: int, detail: Any) -> HTTPException:
    return HTTPException(status_code=code, detail=detail)


async def merge(
    pair: Any,
    *,
    survivor: str,
    choices: dict[str, str],
    expected_updated_at: dict[str, Any] | None,
    reason: str | None,
    user: Any,
    settings: Any = None,
) -> dict[str, Any]:
    if _enum(pair.kind) != "WITHIN" or not pair.artisanBId:
        raise _http(422, {"code": "merge_cross_portal", "message": "Records in two portals are linked, never merged."})
    if survivor not in ("A", "B"):
        raise _http(422, {"code": "merge_bad_choice", "message": "Choose which record to keep."})
    a_id, b_id = pair.artisanAId, pair.artisanBId
    keep_id, gone_id = (a_id, b_id) if survivor == "A" else (b_id, a_id)

    current = await _rows(db, [a_id, b_id])
    if a_id not in current or b_id not in current:
        raise _http(409, {"code": "record_changed", "message": "One of these records no longer exists."})
    validate_choices(choices, survivor, current[a_id], current[b_id])

    result = await _merge_tx(pair, a_id, b_id, survivor, keep_id, gone_id, choices, expected_updated_at, reason, user)
    await artisan_duplicates.rescore_artisan(keep_id)
    result["warnings"] = []
    return result


async def _merge_tx(
    pair: Any,
    a_id: str,
    b_id: str,
    survivor: str,
    keep_id: str,
    gone_id: str,
    choices: dict[str, str],
    expected_updated_at: dict[str, Any] | None,
    reason: str | None,
    user: Any,
) -> dict[str, Any]:
    now = datetime.now(UTC)
    async with db.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        # 1. Lock, check the precondition and the blockers.
        rows = await _rows(tx, [a_id, b_id], lock=True)
        if a_id not in rows or b_id not in rows:
            raise _http(409, {"code": "record_changed", "message": "One of these records no longer exists."})
        a, b = rows[a_id], rows[b_id]
        if expected_updated_at:
            for side, row in (("a", a), ("b", b)):
                want = expected_updated_at.get(side)
                if want and not _same_instant(want, row.get("updatedAt")):
                    raise _http(409, {"code": "record_changed", "message": "One of these records changed. Review it again."})
        blockers = await blockers_for(tx, a, b, survivor, pair_id=pair.id)
        if blockers:
            raise _http(409, {"code": "merge_blocked", "blockers": blockers})
        keep, gone = (a, b) if survivor == "A" else (b, a)

        # 2. Snapshot (never the legacy number: a merge is refused while one is held).
        writes, taken = plan_writes(survivor, choices, a, b)
        consents = await _consents(tx, [keep_id, gone_id])
        snapshot: dict[str, Any] = {
            "merged": {k: v for k, v in gone.items() if k != "aadhaarNumber"},
            "survivorBefore": {c: keep.get(c) for c in writes},
            "consent": {"survivor": consents.get(keep_id), "merged": consents.get(gone_id)},
        }

        # 3. Free the unique keys, then write the survivor.
        # The last four go with the fingerprint: "Artisan_aadhaarFingerprint_pair" wants both or neither.
        legacy = '"aadhaarNumber" = NULL, ' if aadhaar_fingerprint.LEGACY_COLUMN_PRESENT else ""
        await tx.execute_raw(
            'UPDATE "Artisan" SET "aadhaarFingerprint" = NULL, "aadhaarLast4" = NULL, '
            + legacy
            + '"pehchanCardNumber" = NULL WHERE "id" = $1',
            gone_id,
        )
        await _write_survivor(tx, keep_id, writes)
        after = (await _rows(tx, [keep_id]))[keep_id]
        snapshot["survivorAfter"] = {c: after.get(c) for c in writes}
        # So an undo with nothing edited since puts the survivor back exactly, timestamp included.
        snapshot["survivorUpdatedAt"] = {"before": keep.get("updatedAt"), "after": after.get("updatedAt")}
        survivor_name = after.get("name") or keep.get("name") or ""

        # 4. Re-point.
        moves: dict[str, Any] = {"repoint": {}, "deleted": {}}
        for spec in MERGE_REPOINT:
            label = f"{spec.table}.{spec.column}"
            deleted = await _snapshot_collisions(tx, spec, gone_id, keep_id)
            if deleted:
                moves["deleted"][label] = deleted
            moved = await _repoint(tx, spec, gone_id, keep_id, survivor_name)
            if moved:
                moves["repoint"][label] = moved
        moves["taskArrays"] = await _repoint_task_arrays(tx, gone_id, keep_id)
        touched = [m["key"]["interviewId"] for m in moves["repoint"].get("QuestionnaireInterviewArtisan.artisanId", [])]
        touched += [d["interviewId"] for d in moves["deleted"].get("QuestionnaireInterviewArtisan.artisanId", [])]
        moves["interviewKeys"] = await _recompute_set_keys(tx, touched)

        # 5. Consent: the more recent decision wins and lives on the survivor.
        decision = consent_view(consents, keep_id, gone_id)
        if decision is not None:
            winner = consents[keep_id] if decision["from"] == "A" else consents[gone_id]
            if decision["from"] == "B":
                await _write_consent(tx, {**winner, "artisanId": keep_id})
            snapshot["consent"]["kept"] = "survivor" if decision["from"] == "A" else "merged"
            snapshot["consent"]["survivorAfter"] = {**winner, "artisanId": keep_id}
            if not winner.get("granted"):
                removed = await tx.query_raw(
                    'DELETE FROM "CrossPortalArtisanLink" WHERE "artisanId" = $1 RETURNING to_jsonb("CrossPortalArtisanLink") AS "row"',
                    keep_id,
                )
                moves["withdrawnLinks"] = [jsonish(r["row"]) for r in removed]

        # 6. The merge record, and the revision on the survivor.
        record = await tx.artisanmerge.create(
            data={
                "survivorId": keep_id,
                "mergedId": gone_id,
                "mergedById": getattr(user, "id", None),
                "reason": (reason or None),
                "choices": Json(taken),
                "snapshot": Json(snapshot),
                "moves": Json(moves),
                "undoUntil": now + timedelta(days=UNDO_DAYS),
            }
        )
        await tx.recordrevision.create(
            data={
                "recordType": "artisan",
                "recordId": keep_id,
                "editedById": getattr(user, "id", None),
                "changes": Json(
                    {
                        "merge": {
                            "mergeId": record.id,
                            "mergedId": gone_id,
                            "fields": taken,
                            # Read by the edit-history panel, which prints old → new.
                            "old": "Two records",
                            "new": "Merged into this record",
                        }
                    }
                ),
            }
        )

        # 7. Delete the merged record (its pairs and consent row cascade).
        await tx.execute_raw('DELETE FROM "Artisan" WHERE "id" = $1', gone_id)

    return {
        "merge": {
            "id": record.id,
            "survivorId": keep_id,
            "mergedId": gone_id,
            "undoUntil": record.undoUntil,
        },
        "survivorId": keep_id,
    }


# ── Undo ──────────────────────────────────────────────────────────────────────────────────────


def _values_equal(a: Any, b: Any) -> bool:
    if a == b:
        return True
    pa = parse_ts(a) if isinstance(a, str) else None
    pb = parse_ts(b) if isinstance(b, str) else None
    return pa is not None and pb is not None and pa == pb


async def undo(merge_id: str, *, user: Any, settings: Any = None) -> dict[str, Any]:
    record = await db.artisanmerge.find_unique(where={"id": merge_id})
    if record is None:
        raise _http(404, "Merge not found")
    if record.undoneAt is not None:
        raise _http(409, {"code": "merge_undo_expired", "message": "This merge was already undone."})
    now = datetime.now(UTC)
    until = parse_ts(record.undoUntil)
    if record.snapshot is None or record.snapshotPurgedAt is not None or until is None or until < now:
        raise _http(409, {"code": "merge_undo_expired", "message": "Merges can be undone for 30 days."})
    later = await db.artisanmerge.find_first(where={"mergedId": record.survivorId, "undoneAt": None})
    if later is not None:
        raise _http(
            409,
            {"code": "merge_undo_later_first", "message": "The kept record was merged again. Undo that merge first."},
        )
    snapshot = jsonish(record.snapshot) or {}
    moves = jsonish(record.moves) or {}
    merged_row = snapshot.get("merged") or {}
    keep_id, gone_id = record.survivorId, record.mergedId

    if (await _rows(db, [keep_id])).get(keep_id) is None:
        raise _http(409, {"code": "merge_undo_conflict", "message": "The kept record no longer exists."})

    kept_edits: list[str] = []
    skipped: list[str] = []
    async with db.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        current = (await _rows(tx, [keep_id], lock=True))[keep_id]
        # The restored record's identity keys must be free.
        fp, pc = merged_row.get("aadhaarFingerprint"), merged_row.get("pehchanCardNumber")
        before = snapshot.get("survivorBefore") or {}
        after = snapshot.get("survivorAfter") or {}
        # A FIELD GOES BACK WHOLE OR NOT AT ALL. A group's columns travel together (the fingerprint
        # and its last four are "both or neither" in the database), so one edited column keeps the
        # survivor's current value for every column of its group.
        group_of = {c: g.key for g in FIELD_GROUPS for c in g.columns}
        edited = {group_of.get(col, col) for col, wrote in after.items() if not _values_equal(current.get(col), wrote)}
        kept_edits.extend(sorted(edited))
        revert: dict[str, Any] = {col: before.get(col) for col in after if group_of.get(col, col) not in edited}
        clauses = []
        if fp:
            clauses.append({"aadhaarFingerprint": fp})
        if pc:
            clauses.append({"pehchanCardNumber": pc})
        if clauses:
            holder = await tx.artisan.find_first(where={"OR": clauses, "id": {"not": keep_id}})
            survivor_keeps_key = (fp and revert.get("aadhaarFingerprint", current.get("aadhaarFingerprint")) == fp) or (
                pc and revert.get("pehchanCardNumber", current.get("pehchanCardNumber")) == pc
            )
            if holder is not None or survivor_keeps_key:
                raise _http(
                    409,
                    {"code": "merge_undo_conflict", "message": "Another record now holds this Aadhaar or Pehchan number."},
                )
        await _write_survivor(tx, keep_id, revert)
        stamps = snapshot.get("survivorUpdatedAt") or {}
        if not kept_edits and stamps.get("before") and _same_instant(current.get("updatedAt"), stamps.get("after")):
            await tx.execute_raw(
                'UPDATE "Artisan" SET "updatedAt" = $2::timestamp WHERE "id" = $1', keep_id, stamps["before"]
            )
        # 1. The deleted row, with its own id.
        await tx.execute_raw(
            'INSERT INTO "Artisan" SELECT * FROM jsonb_populate_record(NULL::"Artisan", $1::jsonb)',
            json.dumps(merged_row, default=str),
        )
        # Links a consent withdrawal removed come back first, as they stood after the re-point, so
        # the move-back below returns the merged record's ones to it.
        for link in moves.get("withdrawnLinks") or []:
            if not await _reinsert(tx, "CrossPortalArtisanLink", link):
                skipped.append("CrossPortalArtisanLink.artisanId")
        # 3. Move back what still points at the survivor; re-insert collision rows.
        by_label = {f"{s.table}.{s.column}": s for s in MERGE_REPOINT}
        for label, moved in (moves.get("repoint") or {}).items():
            spec = by_label.get(label)
            if spec is not None:
                await _move_back(tx, spec, moved, keep_id, gone_id)
        for label, collision_rows in (moves.get("deleted") or {}).items():
            spec = by_label.get(label)
            if spec is None:
                continue
            for row in collision_rows:
                if not await _reinsert(tx, spec.table, row):
                    skipped.append(label)
        for task in moves.get("taskArrays") or []:
            await tx.execute_raw(
                'UPDATE "AssignedTask" SET "artisanIds" = $2::text[] WHERE "id" = $1 AND "artisanIds" = $3::text[]',
                task["id"],
                task["from"],
                task["to"],
            )
        interviews = [i["id"] for i in moves.get("interviewKeys") or []]
        interviews += [m["key"]["interviewId"] for m in (moves.get("repoint") or {}).get("QuestionnaireInterviewArtisan.artisanId", [])]
        interviews += [d["interviewId"] for d in (moves.get("deleted") or {}).get("QuestionnaireInterviewArtisan.artisanId", [])]
        await _recompute_set_keys(tx, interviews)
        # 5. Both consent rows as they were, and any links a withdrawal removed.
        consent = snapshot.get("consent") or {}
        if consent.get("merged"):
            await _write_consent(tx, consent["merged"])
        if consent.get("kept") == "merged":
            if consent.get("survivor"):
                await _write_consent(tx, consent["survivor"])
            else:
                await tx.execute_raw('DELETE FROM "ArtisanSharingConsent" WHERE "artisanId" = $1', keep_id)
        # 6. Revisions on both records, and the merge marked undone.
        for record_id in (keep_id, gone_id):
            await tx.recordrevision.create(
                data={
                    "recordType": "artisan",
                    "recordId": record_id,
                    "editedById": getattr(user, "id", None),
                    "changes": Json(
                        {
                            "mergeUndone": {
                                "mergeId": record.id,
                                "keptSurvivorEdits": sorted(set(kept_edits)),
                                "old": "Merged",
                                "new": "Separate records again",
                            }
                        }
                    ),
                }
            )
        updated = await tx.artisanmerge.update(
            where={"id": record.id}, data={"undoneAt": now, "undoneById": getattr(user, "id", None)}
        )
    await artisan_duplicates.rescore_artisan(gone_id)
    await artisan_duplicates.rescore_artisan(keep_id)
    return {
        "merge": merge_payload(updated),
        "restoredArtisanId": gone_id,
        "keptSurvivorEdits": sorted(set(kept_edits)),
        "skippedRows": sorted(set(skipped)),
    }


async def _reinsert(tx: Any, table: str, row: dict[str, Any]) -> bool:
    inserted = await tx.query_raw(
        f"INSERT INTO {_q(table)} SELECT * FROM jsonb_populate_record(NULL::{_q(table)}, $1::jsonb) "
        'ON CONFLICT DO NOTHING RETURNING 1 AS "ok"',
        json.dumps(row, default=str),
    )
    return bool(inserted)


async def _move_back(tx: Any, spec: Repoint, moved: list[dict[str, Any]], keep_id: str, gone_id: str) -> None:
    if not moved:
        return
    t = _q(spec.table)
    col = _q(spec.column)
    if spec.name_column:
        for entry in moved:
            key_where = " AND ".join(f"{_q(k)} = ${i + 4}" for i, k in enumerate(spec.key))
            await tx.execute_raw(
                f"UPDATE {t} SET {col} = $1, {_q(spec.name_column)} = $3 WHERE {col} = $2 AND {key_where}",
                gone_id,
                keep_id,
                entry.get("name"),
                *[entry["key"][k] for k in spec.key],
            )
        return
    for entry in moved:
        key_where = " AND ".join(f"{_q(k)} = ${i + 3}" for i, k in enumerate(spec.key))
        await tx.execute_raw(
            f"UPDATE {t} SET {col} = $1 WHERE {col} = $2 AND {key_where}",
            gone_id,
            keep_id,
            *[entry["key"][k] for k in spec.key],
        )


# ── Listing, purge ────────────────────────────────────────────────────────────────────────────


def merge_payload(record: Any) -> dict[str, Any]:
    return {
        "id": record.id,
        "survivorId": record.survivorId,
        "mergedId": record.mergedId,
        "undoUntil": record.undoUntil,
        "undoneAt": record.undoneAt,
    }


def can_undo(record: Any, *, now: datetime | None = None) -> bool:
    now = now or datetime.now(UTC)
    until = parse_ts(record.undoUntil)
    return (
        record.undoneAt is None
        and record.snapshot is not None
        and record.snapshotPurgedAt is None
        and until is not None
        and until > now
    )


def merged_name(record: Any) -> str | None:
    snapshot = jsonish(record.snapshot)
    if not isinstance(snapshot, dict):
        return None
    merged = snapshot.get("merged")
    return merged.get("name") if isinstance(merged, dict) else None


_DAY = timedelta(days=1)
_next_purge: datetime | None = None


async def purge(*, now: datetime | None = None) -> int:
    """Clear the snapshot and the move log of every merge past its undo window."""
    now = now or datetime.now(UTC)
    return await db.execute_raw(
        """UPDATE "ArtisanMerge"
           SET "snapshot" = NULL, "moves" = NULL, "crossPortal" = NULL, "snapshotPurgedAt" = $1::timestamp
           WHERE "snapshotPurgedAt" IS NULL AND "undoUntil" < $1::timestamp""",
        now.astimezone(UTC).replace(tzinfo=None).isoformat(),
    )


async def maybe_purge(settings: Any = None, *, now: datetime | None = None) -> int | None:
    """At most once a day per process, from the queue worker."""
    global _next_purge
    now = now or datetime.now(UTC)
    if _next_purge is not None and now < _next_purge:
        return None
    _next_purge = now.replace(microsecond=0) + _DAY
    return await purge(now=now)
