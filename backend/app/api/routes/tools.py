from datetime import datetime
from typing import Any, NamedTuple

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    require_record_creator,
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
)
from app.schemas.records import (
    TOOL_CRAFT_NAME_MAX,
    ToolArtisanAssign,
    ToolCreate,
    ToolUpdate,
)
from app.services.access import guard_record_edit, record_edit_privilege
from app.services.concurrency import gather_reads
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    Relation,
    public_encode,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    attach_location,
    clean_data,
    client_key_replay,
    client_key_replay_after_violation,
    contains,
    count_and_page,
    decimal_to_string,
    hydrate_relations,
    include_of,
    merge_field_provenance,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)

router = APIRouter(prefix="/tools", tags=["tools"])

# What a tool carries on the wire. Reads load these in one parallel wave (see services/records.py
# for why — this list is the longest in the app, and it is why /tools was the slowest endpoint);
# writes still pass the derived ``INCLUDE`` to Prisma, so the two can never describe different tools.
RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "toolId", many=True),
    Relation("createdBy", "user", "createdById"),
    Relation("artisanLinks", "toolartisan", "toolId", many=True, include={"artisan": True}),
    # EVERY craft this tool is linked to, beside the singular ``craft`` above. ``craftId`` still holds
    # the FIRST of them and ``craftName`` the joined names, so every existing reader of either is
    # untouched; this is the list itself, and it is what a multi-craft picker ticks its boxes from.
    # ``many=True`` means ``key`` is the column on the CHILD — see ``Relation``.
    Relation("craftLinks", "toolcraft", "toolId", many=True, include={"craft": True}),
)
INCLUDE = include_of(RELATIONS)

#: The two join relations this route WRITES, and therefore the two it has to read back after writing
#: them. A create/update passes ``INCLUDE`` to Prisma and gets the links as they were BEFORE the link
#: statements ran, so the response would otherwise describe the save that did not happen.
#:
#: DERIVED FROM ``RELATIONS`` RATHER THAN RETYPED, for the reason ``include_of`` exists: a nested
#: ``include`` added to either one must not leave the write path re-reading a different shape from
#: the one every read path describes.
_LINK_RELATIONS = tuple(rel for rel in RELATIONS if rel.field in ("artisanLinks", "craftLinks"))

# TOOLDOCUMENTATION'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null``
# through for on this model, so emptying a box on the tool form actually empties the column instead
# of answering 200 and keeping the old value.
#
# PER-MODEL AND NOT GLOBAL, for the reason ``clean_data``'s ``clearable`` docstring gives: the global
# set cannot know which table a payload is bound for. Derived from ``model ToolDocumentation`` in
# prisma/schema.prisma, intersected with what ``ToolUpdate`` actually accepts. It OVERLAPS the
# product list without being it — a tool has ``height``/``width``/``thickness``/``weight``/``radius``
# beside the documented trio, and it has no ``size`` or ``costOfMaking`` — so the two must not be
# shared.
#
# ``height`` AND ``heightInches`` ARE BOTH HERE AND THEY ARE TWO DIFFERENT COLUMNS, and since
# 2026-09-15 they are two UNITS of one measurement: ``height`` is the centimetre box (the clients
# label it "Height (cm)"), ``heightInches`` is the inch box, and typing in either fills the other at
# 2.54 cm to the inch. ``width`` and ``breadthInches`` pair the same way. ``lengthInches`` stands
# alone and has no centimetre partner.
#
# THE PAIRING IS WHY BOTH HALVES MUST STAY CLEARABLE, and it is a stronger reason than the one that
# stood here (which was "a researcher who filled the wrong one of the pair could never empty it
# again"). Emptying one box of a pair now empties the other, on the client, in the same keystroke —
# so a save that could clear only one of them would leave a stale converted number behind, asserting
# a measurement the researcher had just deleted.
#
# WHAT IS STILL TRUE OF THE OLD ROWS: a tool recorded before the pairing can hold two numbers in
# ``height`` and ``heightInches`` that are not the same measurement, because nothing in the database
# can say what unit the older one was typed in. Migration 20260913120100 deliberately did not convert
# them and this change does not either; opening such a record rewrites neither box.
#
# Only valid because ``update_tool`` dumps with ``exclude_unset=True``; see the note at that call.
#
# DELIBERATELY ABSENT: ``craftName``/``place``/``artisanName``/``toolkitName`` (NOT NULL), the enums
# ``maker``/``traditionType`` and ``status``/``recordedAt``/``recordedTimezone`` (NOT NULL with
# defaults), ``artisanId``/``craftId``/``workshopId``/``locationId`` (already global), and the
# measurement trio ``measurementImageId``/``measurementAnalysis``/``measurementAnalysisStatus``,
# which ``services/media_queue`` owns and ``records.PROVENANCE_SKIP_FIELDS`` already classes as
# system-managed. ``extraMetadata`` is left out because naming it would be inert:
# ``merge_field_provenance`` rebuilds and reassigns that column further down this route.
# ``clientKey`` is on no update schema at all — see the products route for why.
#
# ``craftIds`` AND ``artisanIds`` ARE NOT HERE AND CANNOT BE: this tuple names COLUMNS, and those
# two are lists of ids that ``_pop_link_ids`` takes out of the body before anything reads it as
# columns. Emptying them is spelled ``[]`` rather than ``null`` for exactly that reason —
# ``clean_data`` drops a ``null`` for any name outside ``CLEARABLE_KEYS`` and this tuple, so
# "clear the links" would be indistinguishable from "leave them alone". ``ToolCreate.craftIds``
# refuses the null outright rather than letting the two answers collapse.
_CLEARABLE_COLUMNS = (
    "localName",
    "englishName",
    "processUsedIn",
    "material",
    "yearsInUse",
    "height",
    "width",
    "lengthInches",
    "breadthInches",
    "heightInches",
    "thickness",
    "weight",
    "radius",
    "replacementCost",
    "suggestionsForToolImprovement",
    "remarks",
)


# =================================================================================================
# THE TWO LISTS OF LINKED RECORDS A TOOL BODY CARRIES — ``craftIds`` and ``artisanIds``
#
# A tool is documented against SEVERAL crafts and SEVERAL artisans, and neither list is a column.
# ``ToolCraft`` and ``ToolArtisan`` hold them; ``craftId``/``craftName``/``artisanId`` are DERIVED
# from the first element and keep every filter, index, report and picker that reads them working
# exactly as before. See ``model ToolCraft`` in prisma/schema.prisma and migration 20260915100000.
#
# THREE ANSWERS, AND KEEPING THEM APART IS THE WHOLE CONTRACT:
#
#   absent  -> leave the stored links alone. An old client that has never heard of these keys, and
#              every PATCH that is only correcting a measurement, lands here.
#   ``[]``  -> no links. On a PATCH this DELETES every link row for the tool.
#   a list  -> exactly these links, in this order, replacing whatever is stored.
#
# ``null`` IS REFUSED AT THE SCHEMA (``ToolCreate._no_explicit_null_link_list``) because it could
# not be told from "absent" here: ``clean_data`` drops a ``None`` for anything outside
# ``CLEARABLE_KEYS``, and these are not columns so ``_CLEARABLE_COLUMNS`` cannot carry them either.
#
# THE ORDER THE CALLER SENT IS THE ORDER THAT IS STORED, and it is recoverable on read — element 0
# is in ``craftId``, and ``craftName`` names all of them in the same order, which is what
# ``_order_craft_links`` reads the list back through. The join table has no ordinal of its own.
# =================================================================================================

#: The refusal a caller meets for linking this tool to an artisan SOMEBODY ELSE created, without
#: standing on the tool itself. Spelled ONCE because two paths raise it now — ``POST
#: /tools/{id}/artisans`` and the ``artisanIds`` list on the record body — and a client meeting two
#: different sentences for one rule would reasonably conclude they were two different rules.
#:
#: IT LISTS FOUR PEOPLE BECAUSE THE GATE ADMITS FOUR. It used to name three, and the missing one was
#: not a wording slip: ``_may_manage_tool_links`` really did leave out the professor-outranks-author
#: clause that ``guard_record_edit`` has always applied. The sentence now says what the gate does.
_FOREIGN_ARTISAN_REFUSAL = (
    "Only the tool's owner, a professor senior to its author, an EDIT-grant collaborator, or an "
    "admin can assign this tool to artisans created by someone else; you may only assign it to "
    "your own artisans."
)


def _clean_link_ids(raw: Any) -> list[str] | None:
    """The link ids in a body value, or ``None`` when the caller sent no such key.

    Blank entries are dropped and duplicates are removed PRESERVING FIRST OCCURRENCE — the
    ``dict.fromkeys`` idiom ``assign_tool_artisans`` below already uses — because the order IS the
    contract here (element 0 becomes the scalar column) and a repeat is a picker echoing itself
    rather than a mistake worth losing a filled-in form over.

    A list that is empty AFTER cleaning stays ``[]`` and not ``None``: "every id I sent was blank"
    is a caller bug, and answering it with "leave the stored links alone" would hide it behind a 200.
    """
    if raw is None:
        return None
    return list(dict.fromkeys(str(value).strip() for value in raw if str(value).strip()))


def _pop_link_ids(data: dict[str, Any], key: str) -> list[str] | None:
    """:func:`_clean_link_ids` of ``data[key]``, taken OUT of the payload.

    POPPED AND NOT READ. Neither key is a column on ``ToolDocumentation``, so one left in ``data``
    reaches ``db.tooldocumentation.create(data=data)`` and is a 500 on the save rather than anything
    a researcher could act on.

    IT IS ALSO WHAT THE TWO SKIP REGISTRIES WOULD HAVE BEEN FOR, and neither gains an entry.
    ``access.REVISION_SKIP_FIELDS`` and ``records.PROVENANCE_SKIP_FIELDS`` never see these keys
    because this pop runs above ``guard_record_edit`` and above ``merge_field_provenance`` — so an
    entry in either would be inert and would read as though the ledger had been told to ignore a
    change somebody made. What those two DO see is the derived ``craftId``/``craftName``/
    ``artisanId``, which is the honest summary of a change of links: the ledger records that the
    tool's craft went from "Bandhani" to "Bandhani, Block Printing".
    """
    return _clean_link_ids(data.pop(key, None))


class _LinkSet(NamedTuple):
    """What one of a tool's two link lists is asking for, set against what is stored.

    ``changed`` IS THE QUESTION THE GUARD NEVER USED TO ASK. What stood here compared the stored row
    COUNT against zero — "is this relation populated?" — and read any populated relation as one the
    caller was replacing. Both tool clients send ``craftIds`` and ``artisanIds`` on EVERY save, the
    unchanged list included (the Android body class carries no ``extraMetadata`` to be refused
    first), so that turned every ordinary edit of somebody else's tool into a 403 about a selection
    nobody had touched — and, because the count was read after the row had been written, a 403 the
    researcher met AFTER their edit had already landed.

    SETS AND NOT LISTS, deliberately. ``ToolCraft``/``ToolArtisan`` carry nothing beyond the pair, so
    two orderings of the same ids are the same rows and a re-order is not a change to the RELATION.
    The order is carried by ``craftId``/``craftName``/``artisanId``, which ARE columns and are
    therefore guarded by ``assert_can_contribute_fields`` inside ``guard_record_edit`` exactly like
    every other populated column — so re-ordering a populated selection is still refused for an
    ordinary contributor, by the guard that owns the values that actually changed.
    """

    #: Is there anything stored for this relation today? (A count would have answered this one.)
    stored_is_populated: bool = False
    #: Does the caller's list DIFFER from it? ``False`` for an absent key and for an identical
    #: re-send, which is what both clients send on every save.
    changed: bool = False

    @property
    def replaces_populated(self) -> bool:
        """What ``assert_can_contribute_relation`` means by ``populated``: a relation somebody has
        already answered AND which this request is changing. An identical re-send changes nothing;
        an empty relation is one an ordinary contributor may fill. Neither is a replacement."""
        return self.changed and self.stored_is_populated


class _ToolLinks(NamedTuple):
    """Both of a tool's link lists, as :func:`_resolve_tool_links` found them. An absent key leaves
    the default ``_LinkSet()`` — nothing requested, nothing stored, nothing changed — so a caller can
    read both answers off it without re-testing for ``None`` at every use."""

    crafts: _LinkSet = _LinkSet()
    artisans: _LinkSet = _LinkSet()


async def _resolve_tool_links(
    data: dict[str, Any],
    craft_ids: list[str] | None,
    artisan_ids: list[str] | None,
    current_user: Any,
    *,
    tool: Any | None,
) -> _ToolLinks:
    """Check both id lists against what is stored, derive the scalar columns, and report what CHANGES.

    Mutates ``data``; returns what the caller needs in order to guard and to write — see
    :class:`_LinkSet`.

    EVERYTHING IS VALIDATED BEFORE ANYTHING IS WRITTEN, which is the rule ``assign_tool_artisans``
    already states for its own batch and which matters more here: this runs above
    ``guard_record_edit``, and that call ends in a COMMITTED ``RecordRevision`` row that this backend
    has no transaction to roll back. A refusal raised after it would leave a permanent ledger entry
    asserting an edit that was then turned down.

    THE READS ALL GO OUT TOGETHER, and on a PATCH there are now up to four of them: the crafts and
    the artisans being named, plus the link rows already stored for each list the caller sent. On
    this deployment every one is a cross-region round trip, so they are issued as one wave rather
    than four. The stored sets are what make the two questions below answerable at all — "is this
    caller ADDING a foreign artisan, or keeping one that is already linked?" and "is this request
    CHANGING the relation, or re-sending it?" — and neither can be answered by a row count.

    WHAT IS DERIVED, AND WHAT IS DELIBERATELY NOT:

    * ``craftId`` := the first craft, and ``craftName`` := every selected craft's name joined ``", "``
      in the caller's order. BOTH OVERRIDE whatever the body sent, because the server has to be able
      to re-derive ``craftName`` from ``craftIds`` alone — a body composed offline and replayed a
      fortnight later must still store a name that agrees with its links. The cost is that a hand
      correction typed into the craft-name box is lost while crafts are ticked; the clients mitigate
      it by writing the joined value into the box on every selection change, so the box never shows
      something other than what will be stored. The honest fix is an explicit override flag, not a
      heuristic that guesses from the previous value.
    * ``artisanName`` and ``place`` are NOT overridden. Both are NOT NULL, both are already
      populated, and both are things a researcher legitimately corrects by hand — "A. Khatri" to
      "Abdul Khatri" — so the body's values stand and an omitted key keeps the stored one. The
      CLIENT fills them from the first artisan's record on selection, exactly as the single-select
      always did.

    ``craftName`` IS ASSIGNED AFTER ``clean_data`` HAS RUN, deliberately: each ``Craft.name`` is
    already canonical (``clean_data`` title-cased it on the craft's own write), and running the
    joined string through ``title_case_fields`` again would re-case names nobody edited.

    AND IT IS BOUNDED BY THE SAME NUMBER THE SCHEMAS WILL ACCEPT BACK. ``ToolCreate``/``ToolUpdate``
    cap ``craftName`` at ``TOOL_CRAFT_NAME_MAX`` and this is the only code that can produce a value
    for it, so a join this route would store but those schemas would refuse is a record the
    researcher can never edit again — a 422 naming a box they never typed in, re-raised on every
    later save because the route re-derives the same string each time. The refusal below is what
    keeps the two numbers one number.
    """
    if craft_ids is None and artisan_ids is None:
        return _ToolLinks()

    # ONE WAVE, KEYED BY NAME RATHER THAN BY POSITION: all four reads are conditional, and popping a
    # positional list in the right order is the kind of thing that silently swaps two result sets the
    # day a fifth condition is added. Dicts keep insertion order, so the ``zip`` is total. Built
    # conditionally rather than asking for everything with an empty ``in`` list — ``hydrate_relations``
    # skips the query when there is nothing to look up, for the same reason.
    reads: dict[str, Any] = {}
    if craft_ids:
        reads["crafts"] = db.craft.find_many(where={"id": {"in": craft_ids}})
    if artisan_ids:
        reads["artisans"] = db.artisan.find_many(where={"id": {"in": artisan_ids}})
    # THE STORED LINKS, on the PATCH path, whenever the caller sent the key AT ALL — ``[]`` included,
    # because "delete every link" is the request most in need of the comparison. ``tool is None`` is
    # the CREATE path, where there is nothing stored to compare against by construction.
    if tool is not None and craft_ids is not None:
        reads["storedCrafts"] = db.toolcraft.find_many(where={"toolId": tool.id})
    if tool is not None and artisan_ids is not None:
        reads["storedArtisans"] = db.toolartisan.find_many(where={"toolId": tool.id})
    fetched = dict(zip(reads, await gather_reads(*reads.values())))

    stored_crafts = {link.craftId for link in fetched.get("storedCrafts") or []}
    stored_artisans = {link.artisanId for link in fetched.get("storedArtisans") or []}
    links = _ToolLinks(
        crafts=_LinkSet(
            stored_is_populated=bool(stored_crafts),
            changed=craft_ids is not None and set(craft_ids) != stored_crafts,
        ),
        artisans=_LinkSet(
            stored_is_populated=bool(stored_artisans),
            changed=artisan_ids is not None and set(artisan_ids) != stored_artisans,
        ),
    )

    if craft_ids:
        by_id = {craft.id: craft for craft in fetched.get("crafts") or []}
        for craft_id in craft_ids:
            if craft_id not in by_id:
                # 404 AND NOT 422: an id that names nothing is the same answer ``require_record``
                # gives for the tool itself, and a client cannot tell "you sent a bad id" from "that
                # craft has been deleted" any better than the server can. THERE IS NO 403 ARM HERE,
                # and that is not an omission: a ``Craft`` carries ``createdById`` but no assignment
                # rule exists anywhere in this product, so existence is the only question to ask.
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="Record not found"
                )
        joined = ", ".join(by_id[craft_id].name for craft_id in craft_ids)
        if len(joined) > TOOL_CRAFT_NAME_MAX:
            # THE ONE 422 THIS ROUTE RAISES BY HAND, and it is raised here because pydantic cannot
            # see this string — the body's ``craftName`` is about to be overwritten by it, so the
            # schema validated a value that will not be the one stored. Refused before the write,
            # like every other check in this function, so a tool is never stored carrying a name its
            # own update schema would reject. The message names the count and the length because
            # "shorten the craft name" is not advice a researcher can act on for a box the server
            # fills in.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"{len(craft_ids)} linked crafts name this tool's craft in {len(joined)} "
                    f"characters, and a tool record can hold {TOOL_CRAFT_NAME_MAX}. "
                    f"Link fewer crafts to this tool."
                ),
            )
        data["craftId"] = craft_ids[0]
        data["craftName"] = joined

    if artisan_ids:
        by_id = {artisan.id: artisan for artisan in fetched.get("artisans") or []}
        for artisan_id in artisan_ids:
            if artisan_id not in by_id:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="Record not found"
                )
        # ONLY THE IDS BEING ADDED ARE GATED, and that is the whole of this rule. Keeping a link that
        # is already on the tool is not an act of assignment — the assignment happened when the row
        # was written, by somebody who was allowed to write it — so re-sending it asks for no
        # permission. Gating the whole list instead refused every save by anyone but the owner, an
        # admin or an EDIT-grantee the moment a tool was linked to an artisan they had not created:
        # both clients send the list unchanged on every save, so a Professor correcting a typo in a
        # junior researcher's tool met a 403 about an artisan picker they never opened — the exact
        # case ``may_edit_lower_ranked_record`` exists to permit.
        #
        # ``tool is None`` is the CREATE path, where the caller is the row's creator by construction
        # (``data["createdById"] = current_user.id``, a few lines below the call) — so the gate can
        # only ever answer yes, and the round trip is not spent. On a PATCH it is spent only when
        # there IS a foreign addition to judge.
        #
        # THE GATE ASKS ``record_edit_privilege`` AND SO DOES ``update_tool``, a few lines apart, and
        # the repetition is deliberate rather than threaded through this signature. The two cannot
        # disagree — they are the same function — and it costs nothing for the callers who reach it:
        # an admin and the tool's own owner are answered with no query at all. Only a non-owner
        # adding somebody else's artisan pays the second pair of reads, and that request is already
        # spending a whole validation wave.
        if tool is not None:
            foreign_additions = [
                artisan_id
                for artisan_id in artisan_ids
                if artisan_id not in stored_artisans
                and getattr(by_id[artisan_id], "createdById", None) != current_user.id
            ]
            if foreign_additions and not await _may_manage_tool_links(tool, current_user):
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN, detail=_FOREIGN_ARTISAN_REFUSAL
                )
        data["artisanId"] = artisan_ids[0]

    return links


async def _write_link_rows(
    tool_id: str, craft_ids: list[str] | None, artisan_ids: list[str] | None
) -> bool:
    """Write a NEW tool's link rows. Returns whether anything was written.

    One insert per table rather than one per id: on this deployment every statement is a cross-region
    round trip, and a tool covering six crafts must not cost six of them. See
    ``workshops.replace_workshop_artisans`` for the same argument at forty.
    """
    wrote = False
    if craft_ids:
        await db.toolcraft.create_many(
            data=[{"toolId": tool_id, "craftId": cid} for cid in craft_ids]
        )
        wrote = True
    if artisan_ids:
        await db.toolartisan.create_many(
            data=[{"toolId": tool_id, "artisanId": aid} for aid in artisan_ids]
        )
        wrote = True
    return wrote


async def _replace_craft_links(tool_id: str, craft_ids: list[str]) -> None:
    """Rewrite a tool's craft links in two statements — the whole set, never a diff.

    ``ToolCraft`` carries nothing beyond the pair and its ``createdAt``, and ``_order_craft_links``
    reads the order out of ``craftName`` rather than out of ``createdAt``, so a recreated row is
    indistinguishable from a kept one. A diff would cost a read to compute and buy nothing.

    THE CALLER SKIPS THIS ENTIRELY WHEN THE SET IS UNCHANGED, which is not a diff either: the read
    that answers it (``_resolve_tool_links``' stored-link wave) has to happen anyway, to tell an
    unchanged re-send from a replacement before the contributor guard is applied. Both clients send
    the list on every save, so the common case is now two statements not sent rather than two
    statements that delete and rewrite the same rows.
    """
    await db.toolcraft.delete_many(where={"toolId": tool_id})
    if craft_ids:
        await db.toolcraft.create_many(
            data=[{"toolId": tool_id, "craftId": cid} for cid in craft_ids]
        )


async def _replace_artisan_links(tool_id: str, artisan_ids: list[str]) -> None:
    """The artisan links, rewritten in two statements — see above.

    WHAT STOOD HERE WAS WRONG, and it is quoted rather than deleted because it is the reason nothing
    ordered ``artisanLinks`` on the read path for a while: *"A replace restamps every ``createdAt``
    to now, so the list comes back in the order the caller sent — which is the order they ticked, and
    the only order they can predict."* The first half is true and the conclusion does not follow.
    ``create_many`` is ONE statement, so every row of one save is stamped with ONE ``createdAt``
    (``DEFAULT CURRENT_TIMESTAMP``, and CURRENT_TIMESTAMP is fixed for the whole transaction) — the
    timestamps do not order the batch, they TIE it, and the tie then falls to whatever the read
    happened to return. Tick order does not survive this table, and cannot until it has an ordinal
    column of its own.

    WHAT DOES SURVIVE IS WHICH ONE IS FIRST, and that is the half the clients actually depend on:
    ``artisanId`` is derived from element 0 and is the column every filter, report and map pin reads.
    :func:`_order_artisan_links` puts the link that ``artisanId`` names at the front of every read,
    so reopening a tool and saving it cannot silently re-point that column at somebody else.
    """
    await db.toolartisan.delete_many(where={"toolId": tool_id})
    if artisan_ids:
        await db.toolartisan.create_many(
            data=[{"toolId": tool_id, "artisanId": aid} for aid in artisan_ids]
        )


async def _repair_empty_links(
    tool_id: str, craft_ids: list[str] | None, artisan_ids: list[str] | None
) -> bool:
    """Write a replayed create's link rows ONLY where the stored tool has none. Returns whether it did.

    THE TRADE-OFF IS BETWEEN TWO SILENT FAILURES, and it exists because this backend has no
    transactions — the argument ``workshops._repair_empty_rosters`` makes in full, now true of this
    route too. ``create_tool`` commits the tool row and THEN writes the links, so a create that died
    in between leaves a tool whose craft picker is empty; and once a ``clientKey`` is on that row,
    every later replay answers 201 from it while the links stay gone and the outbox reports the entry
    as sent. THE SCALAR COLUMNS ARE NOT AT RISK — ``craftId``, ``craftName`` and ``artisanId`` were
    written by the same statement that wrote the row — so only the link rows can be missing.

    THE OTHER DIRECTION IS WORSE, WHICH IS WHY THIS IS CONDITIONAL. Rewriting a POPULATED link set
    would reinstate whatever the queued entry believed a fortnight ago over whatever a PATCH has
    since made true. So an empty set is repaired and a populated one is never touched, EVEN IF THE
    IDS DIFFER: a difference means the links were edited after the create, and the edit is newer.

    AN ID THAT NO LONGER EXISTS IS DROPPED RATHER THAN REFUSED, which is the one place this departs
    from the create path's 404. A craft deleted since the first landing would have taken its link row
    with it (``onDelete: Cascade``), so writing it back would be wrong; and 404-ing a replay would
    park a filled-in form in an outbox for ever, re-sent into the same refusal on every pass.
    """
    wrote = False
    if craft_ids and not await db.toolcraft.count(where={"toolId": tool_id}):
        live = {craft.id for craft in await db.craft.find_many(where={"id": {"in": craft_ids}})}
        wanted = [cid for cid in craft_ids if cid in live]
        if wanted:
            await db.toolcraft.create_many(
                data=[{"toolId": tool_id, "craftId": cid} for cid in wanted]
            )
            wrote = True
    if artisan_ids and not await db.toolartisan.count(where={"toolId": tool_id}):
        live = {a.id for a in await db.artisan.find_many(where={"id": {"in": artisan_ids}})}
        wanted = [aid for aid in artisan_ids if aid in live]
        if wanted:
            await db.toolartisan.create_many(
                data=[{"toolId": tool_id, "artisanId": aid} for aid in wanted]
            )
            wrote = True
    return wrote


def _order_craft_links(tool: dict[str, Any]) -> None:
    """Put an encoded tool's ``craftLinks`` into the order ``craftName`` records. Mutates ``tool``.

    THE JOIN TABLE HAS NO ORDINAL, and the order is part of the contract: element 0 of the list the
    client sent is what ``craftId`` holds, so a client that ticked three crafts and read them back in
    another order would show a different "first" craft from the one every filter in the app is using.
    ``craftName`` is the ordinal — it is written from the same list, in the same order, by
    ``_resolve_tool_links`` — so the string is what the list is sorted back through.

    ``Craft.name`` is ``@unique`` in this schema, so name -> position is injective and this is a total
    order over the links whose craft is named in the string. A link whose craft is NOT named there —
    a craft renamed since the save, a row written by a client that predates this rule — is appended
    in ``createdAt asc, id asc``. Stated rather than left to whatever order the database returned,
    because "the leftovers come last, oldest first" is something a client can render; "whatever
    Postgres felt like" is not.
    """
    names = [part.strip() for part in (tool.get("craftName") or "").split(",")]
    at = {name: index for index, name in enumerate(names) if name}
    links = tool.get("craftLinks") or []
    links.sort(
        key=lambda link: (
            at.get(((link.get("craft") or {}).get("name") or "").strip(), len(at)),
            link.get("createdAt") or "",
            link.get("id") or "",
        )
    )


def _order_artisan_links(tool: dict[str, Any]) -> None:
    """Put an encoded tool's ``artisanLinks`` in an order the NEXT save can be trusted with.
    Mutates ``tool``.

    THE LINK THAT ``artisanId`` NAMES COMES FIRST; everything else follows in ``createdAt asc,
    id asc``.

    WHY THIS EXISTS AT ALL. Both tool forms seed their artisan multi-select from ``artisanLinks`` and
    send the result back as ``artisanIds``, and ``_resolve_tool_links`` derives ``artisanId`` from
    element 0. So the list's first element is not decoration: it decides which artisan the column
    every filter, report and map pin reads will point at after the next save. Read it back in a
    different order and a researcher who opened a tool, fixed a typo in the remarks and saved has
    re-pointed ``artisanId`` at a different person — while ``artisanName`` and ``place``, which this
    route deliberately does NOT re-derive, keep naming the first one. The row then says one artisan
    and reads another, on a save that touched neither.

    AND THE ORDER WAS UNSPECIFIED, not merely unlucky. ``hydrate_relations`` issues
    ``toolartisan.find_many(where={"toolId": {"in": [...]}})`` with no ``order``, and Postgres returns
    rows in whatever order the chosen plan produces — index order on one plan, physical heap order on
    another, and after ``_replace_artisan_links``' delete-then-insert the heap order can be anything
    free space allowed.

    ``createdAt asc, id asc`` ALONE WOULD NOT HAVE BEEN ENOUGH, which is why the pin is first and not
    a tiebreak. ``_write_link_rows``/``_replace_artisan_links`` insert a whole set in ONE
    ``create_many``, so every row of one save carries an IDENTICAL ``createdAt`` and the tie falls to
    ``id`` — a cuid, i.e. a coin toss between the artisans the researcher ticked. ``artisanId`` is the
    only ordinal the artisan side has; ``artisanName`` cannot serve as one the way ``craftName`` does
    for crafts, because it names the FIRST artisan rather than all of them.

    ``createdAt``/``id`` STILL DECIDE THE REST, stated rather than left to the database, for the same
    reason ``_order_craft_links`` states its own leftovers rule: "the others follow, oldest first" is
    something a client can render and "whatever Postgres felt like" is not. A tool whose
    ``artisanId`` is null, or names an artisan with no link row, simply has no pinned element and
    falls through to that rule.
    """
    first = tool.get("artisanId")
    links = tool.get("artisanLinks") or []
    links.sort(
        key=lambda link: (
            0 if first is not None and link.get("artisanId") == first else 1,
            link.get("createdAt") or "",
            link.get("id") or "",
        )
    )


def _tool_payload(tool: Any) -> dict[str, Any]:
    """One tool on the wire: the ordinary encode, then both link lists into their stated order."""
    encoded = public_encode(tool)
    _order_craft_links(encoded)
    _order_artisan_links(encoded)
    return encoded


def _tool_page(tools: Any) -> list[dict[str, Any]]:
    """A page of tools on the wire. One encode for the page, then the ordering per row."""
    encoded = public_encode(tools)
    for tool in encoded:
        _order_craft_links(tool)
        _order_artisan_links(tool)
    return encoded


async def _assigned_artisans(tool_id: str) -> list[dict[str, Any]]:
    """All artisans a tool is assigned to (the many-to-many links), oldest first.

    ``id`` BREAKS THE TIE, and there is always a tie to break: a link set written by
    ``_write_link_rows`` or ``_replace_artisan_links`` goes in as one ``create_many`` and shares one
    ``createdAt``, so "oldest first" on its own left this endpoint returning one save's artisans in
    an unspecified order. The second key does not make the order meaningful — it makes it the SAME
    on every call, which is what a caller diffing two responses needs.

    IT IS DELIBERATELY NOT :func:`_order_artisan_links`' ORDER. This endpoint answers "which artisans
    is this tool assigned to", oldest link first, and ``POST /tools/{id}/artisans`` (which never
    touches ``artisanId``) is its writer. ``artisanLinks`` on the record answers "which artisans does
    the tool form tick, and which of them is the one ``artisanId`` holds". In practice the two agree
    for almost every row, because migration 20260916090000 backfilled each tool's ``artisanId`` link
    with the TOOL'S OWN ``createdAt`` — the oldest timestamp any of its links can carry."""
    links = await db.toolartisan.find_many(
        where={"toolId": tool_id},
        include={"artisan": True},
        order=[{"createdAt": "asc"}, {"id": "asc"}],
    )
    return public_encode([link.artisan for link in links if link.artisan])


@router.get("")
async def list_tools(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    place: str | None = None,
    maker: str | None = None,
    traditionType: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # Visibility is AND-composed so the search OR (assigned below) can never overwrite it.
    vis = await viewable_where(current_user)
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [
            {"toolkitName": contains(search)},
            {"localName": contains(search)},
            {"englishName": contains(search)},
            {"craftName": contains(search)},
            {"artisanName": contains(search)},
            {"place": contains(search)},
            {"processUsedIn": contains(search)},
            {"material": contains(search)},
            {"remarks": contains(search)},
        ]
    if craftId:
        # STILL THE SINGULAR COLUMN, AND ONLY THE FIRST OF A TOOL'S CRAFTS. A tool linked to three
        # crafts answers this filter under the first of them and not the other two, which is the
        # accepted consequence of keeping ``craftId`` meaning what it has always meant — every index,
        # report, carry-forward and data-browser branch reads it, and they all keep working unchanged.
        #
        # WIDENING IT IS A DECISION, NOT AN OVERSIGHT, which is why it is written down here rather
        # than left to be noticed. The shape it would take is the one ``artisans.list_artisans``
        # already uses for its ``workshopId`` arm:
        #
        #     {"OR": [{"craftId": craftId}, {"craftLinks": {"some": {"craftId": craftId}}}]}
        #
        # It is out of scope for the change that added the links (20260915100000) because it changes
        # what every existing craft-scoped count in the app MEANS, and that is a separate answer to
        # give the people reading those counts.
        where["craftId"] = craftId
    if artisanId:
        where["artisanId"] = artisanId
    if workshopId:
        where["workshopId"] = workshopId
    if place:
        where["place"] = contains(place)
    if maker:
        where["maker"] = maker
    if traditionType:
        where["traditionType"] = traditionType
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await count_and_page(
        db.tooldocumentation,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    return page_payload(_tool_page(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_tool(
    payload: ToolCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    # ── THE IDEMPOTENT REPLAY, ABOVE EVERY WRITE AND EVERY GATE IN THIS ROUTE ────────────────────
    #
    # See ``products.create_product`` for the argument in full: a create whose answer was lost is
    # sent again by the queue, and without this branch the second landing writes a second tool.
    # Above the gates, never below — on a replay the row already exists, so re-asking can only turn a
    # create that SUCCEEDED into a 403, and it keeps ``attach_location`` from minting a second
    # ``Location`` row per replay.
    #
    # A SECOND LANDING NOW DUPLICATES LINK ROWS AS WELL AS THE ROW ITSELF, which it did not when this
    # branch was written — the two ``create_many`` calls below are new. Answering from the stored row
    # writes neither, EXCEPT where that row has an empty link set, which is the one shape a create
    # that committed and then failed leaves behind; see :func:`_repair_empty_links`.
    replayed = await client_key_replay(
        db.tooldocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        if await _repair_empty_links(
            replayed.id, _clean_link_ids(payload.craftIds), _clean_link_ids(payload.artisanIds)
        ):
            await hydrate_relations([replayed], _LINK_RELATIONS)
        return _tool_payload(replayed)
    data = decimal_to_string(clean_data(payload.model_dump()))
    # THE TWO LINK LISTS COME OUT OF THE BODY HERE, above everything that reads ``data`` as columns,
    # and the ids are checked before the first write. ``craftId``/``craftName``/``artisanId`` are
    # derived from them, so ``merge_field_provenance`` below stamps the values that will actually be
    # stored rather than whatever the body happened to carry beside the lists.
    craft_ids = _pop_link_ids(data, "craftIds")
    artisan_ids = _pop_link_ids(data, "artisanIds")
    # The plan it returns is a PATCH's business — what changed, and whether a populated relation is
    # being replaced. Nothing is stored yet on this path, so both answers are "no" by construction.
    await _resolve_tool_links(data, craft_ids, artisan_ids, current_user, tool=None)
    data = await attach_location(data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.tooldocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by is_client_key_violation
        # The race the pre-read cannot close: two drains of the same queue in flight at once, each
        # finding no row and each planning an INSERT. Only the index can settle it. ``None`` means
        # re-raise, and this does.
        #
        # WHAT STOOD HERE SAID "A tool has no child writes, so there is nothing here to repair",
        # and it was true until 20260915100000 gave a tool two join tables. It now HAS child writes
        # — ``ToolCraft`` and ``ToolArtisan`` — so the loser of the race repairs the WINNER's, on
        # the same terms as the ordinary replay above: only where the winner has none.
        #
        # THE HANDLER STILL WRAPS THE ROW WRITE ALONE, and that is what keeps the sentence above
        # simple. The unique index is on the tool row, so a ``clientKey`` violation can only be
        # raised by this one statement and the link writes below are unreachable from it. Widening
        # the ``try`` to cover them would catch a link failure — not a ``clientKey`` violation, so
        # re-raised anyway — while reading as though a replay could be answered out of a
        # half-written create.
        raced = await client_key_replay_after_violation(
            db.tooldocumentation, payload.clientKey, exc, user_id=current_user.id, include=INCLUDE
        )
        if raced is None:
            raise
        if await _repair_empty_links(raced.id, craft_ids, artisan_ids):
            await hydrate_relations([raced], _LINK_RELATIONS)
        return _tool_payload(raced)
    # AFTER the row, because both link tables have a foreign key onto it, and outside the handler
    # above for the reason that handler gives. There is no transaction to put the three statements in
    # — this backend has no ``db.tx()`` call site at all — so a failure here leaves a tool whose links
    # are missing, and the ``clientKey`` replay is what repairs that on the queue's next pass.
    if await _write_link_rows(created.id, craft_ids, artisan_ids):
        # ``include=INCLUDE`` described the links as they were BEFORE those inserts, which for a new
        # row is two empty arrays. Re-read just the two relations rather than the whole tool again.
        await hydrate_relations([created], _LINK_RELATIONS)
    return _tool_payload(created)


@router.get("/{tool_id}")
async def get_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    await hydrate_relations([tool], RELATIONS)
    return _tool_payload(tool)


@router.patch("/{tool_id}")
async def update_tool(
    tool_id: str,
    payload: ToolUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    # The precondition is a QUESTION, not a column — popped before anything reads ``data``, and
    # checked above ``guard_record_edit`` because that call ends in a COMMITTED ``RecordRevision``
    # row and this backend has no transaction to roll one back with.
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(tool, expected_updated_at)
    # THE TWO LINK LISTS, TAKEN OUT OF THE BODY AND CHECKED WHILE NOTHING HAS BEEN WRITTEN YET.
    # ``None`` means the caller sent no such key and the stored links stand; ``[]`` means "no links"
    # and deletes them. Both the pop and the validation sit ABOVE ``guard_record_edit`` for the reason
    # the precondition above does: that call ends in a COMMITTED ``RecordRevision`` row and there is
    # no transaction here to roll one back, so a 404 for an unknown craft must be raised before it.
    # What the ledger then records is the derived ``craftId``/``craftName``/``artisanId``, which is
    # the honest summary of a change of links — see :func:`_pop_link_ids`.
    #
    # AND IT READS THE STORED LINK IDS, which is what lets the two refusals BELOW this line be
    # raised above the ledger as well: the foreign-artisan 403 needs to know which ids are actually
    # being added, and the contributor relation guard needs to know whether the list differs from
    # what is stored at all. A row count could answer neither.
    craft_ids = _pop_link_ids(data, "craftIds")
    artisan_ids = _pop_link_ids(data, "artisanIds")
    links = await _resolve_tool_links(data, craft_ids, artisan_ids, current_user, tool=tool)
    data = await attach_location(data)
    # Re-check workshop assignment + window if this edit moves the tool into/between workshops, so the
    # create-time guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != tool.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    # ── THE RELATION GUARD, AND WHY IT IS UP HERE AND NOT BESIDE THE LINK WRITES ─────────────────
    #
    # AN ORDINARY CONTRIBUTOR MAY FILL AN EMPTY RELATION AND NOT REPLACE A POPULATED ONE, which is
    # the relation-shaped half of ``assert_can_contribute_fields`` and the rule ``update_workshop``
    # applies to its rosters. Without it, "linked crafts" would be the one thing on this form a
    # stranger could blank, by sending ``[]``.
    #
    # IT USED TO SIT BELOW ``db.tooldocumentation.update``, and that was two defects rather than one.
    # It read ``db.toolcraft.count(...) > 0`` — "is this relation populated" — and never compared the
    # caller's list against the stored one, so an unchanged re-send was refused as a replacement;
    # and it ran after the column write and after ``guard_record_edit``'s committed
    # ``RecordRevision``, so the refusal arrived after the edit had landed. A contributor filling an
    # empty Material box on somebody else's tool was told the save failed, found Material saved, and
    # met the same 403 on every retry. Both tool clients send both lists on every save, so that was
    # not a corner: it was the ordinary edit.
    #
    # ``replaces_populated`` IS NOW THE QUESTION, computed in ``_resolve_tool_links`` from the stored
    # link rows it already had to read — a relation that is populated AND is actually being changed.
    # And the refusal is raised HERE, above both writes, which is the ordering rule this route
    # already states three times (``assert_expected_updated_at``, the unknown-craft 404, and the pop
    # itself): ``guard_record_edit`` ends in a COMMITTED ledger row and this backend has no
    # transaction to roll one back with.
    #
    # ``record_edit_privilege`` IS ``guard_record_edit``'S OWN ANSWER, ASKED EARLY — the same two
    # queries in the same order, handed back below so the pair costs what the single call used to.
    # Computing "privileged" any other way here would be a second answer to the one question
    # ``services/access`` exists to answer once.
    privileged = await record_edit_privilege(tool, current_user, "tool")
    if not privileged:
        assert_can_contribute_relation(
            tool, current_user, links.crafts.replaces_populated, "craftIds"
        )
        assert_can_contribute_relation(
            tool, current_user, links.artisans.replaces_populated, "artisanIds"
        )
    await guard_record_edit(tool, current_user, data, "tool", privileged=privileged)
    await apply_status_policy_update(current_user, tool, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=tool)
    pin_pending_if_late(data, current_user, check=check, record=tool)
    merge_field_provenance(data, current_user, previous=tool)
    resubmit_status(tool, current_user, data)
    updated = await db.tooldocumentation.update(where={"id": tool_id}, data=data, include=INCLUDE)
    # THE LINKS ARE REPLACED AFTER THE ROW, and there is no transaction around the pair — this backend
    # has no ``db.tx()`` call site at all, so the window between them is the same window
    # ``update_workshop`` already lives with for its two rosters. Every refusal this route can raise
    # is above the write now, so the only thing that can fail here is the database itself.
    #
    # A LIST THAT MATCHES WHAT IS STORED IS NOT WRITTEN AT ALL. Both clients send both lists on every
    # save, so the common PATCH used to delete and re-insert the same rows twice over for nothing —
    # and restamp their ``createdAt`` while it was at it. ``changed`` is free: the stored ids were
    # read above to tell a re-send from a replacement for the guard.
    if links.crafts.changed:
        await _replace_craft_links(tool_id, craft_ids)
    if links.artisans.changed:
        await _replace_artisan_links(tool_id, artisan_ids)
    if links.crafts.changed or links.artisans.changed:
        # ``include=INCLUDE`` described the links as they were BEFORE the statements above, so the
        # response would otherwise show the save that did not happen. Only the two written relations
        # are re-read; everything else on ``updated`` is already current — and an unchanged list is
        # already current too, which is why this is the same condition as the writes.
        await hydrate_relations([updated], _LINK_RELATIONS)
    return _tool_payload(updated)


@router.delete("/{tool_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.tooldocumentation, tool_id)
    await db.tooldocumentation.delete(where={"id": tool_id})


@router.get("/{tool_id}/artisans")
async def list_tool_artisans(tool_id: str, current_user: Any = Depends(get_current_user)) -> list[dict[str, Any]]:
    await require_record(db.tooldocumentation, tool_id)
    return await _assigned_artisans(tool_id)


@router.post("/{tool_id}/artisans")
async def assign_tool_artisans(
    tool_id: str,
    payload: ToolArtisanAssign,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """Assign the tool to the given artisans (idempotent: existing links are kept, new ones added).

    Permission: whoever ``guard_record_edit`` would call privileged for this tool — an admin, the
    tool's owner, a professor outranking its author, or a collaborator holding an EDIT-tier grant —
    may assign it to any artisan; anyone else may only assign it to artisans THEY created.
    Validation happens for the WHOLE batch before any link is written, so a rejected request never
    leaves partial state behind.

    THIS ENDPOINT GATES EVERY ID IT IS SENT, and the record body's ``artisanIds`` gates only the ids
    it ADDS. That is not an inconsistency: ``wanted`` below is already "the ids not currently linked",
    so both gate exactly the additions. The two just compute that set in different places.

    THIS IS ADDITIVE AND ``artisanIds`` ON THE RECORD BODY IS A REPLACEMENT, which is the whole
    difference between the two and the reason both exist. This endpoint is "also assign it to these
    people" — it never removes a link and it never touches ``artisanId``/``artisanName``/``place``.
    ``PATCH /tools/{id}`` with ``artisanIds`` is "the linked artisans are exactly these", deletes
    what is not in the list, and derives ``artisanId`` from the first of them. A form that shows a
    multi-select sends the second; a screen that adds one more maker to a tool sends this."""
    tool = await require_record(db.tooldocumentation, tool_id)
    may_assign_any = await _may_manage_tool_links(tool, current_user)
    existing = await db.toolartisan.find_many(where={"toolId": tool_id})
    have = {link.artisanId for link in existing}
    # Every artisan being added is fetched in ONE query and every link written in ONE insert. Asking
    # per artisan cost two cross-region round trips each, so assigning a tool to a workshop's worth
    # of makers took longer than recording the tool did.
    wanted = [aid for aid in dict.fromkeys(payload.artisanIds) if aid and aid not in have]
    if not wanted:
        return await _assigned_artisans(tool_id)
    artisans = await db.artisan.find_many(where={"id": {"in": wanted}})
    by_id = {a.id: a for a in artisans}
    for artisan_id in wanted:
        artisan = by_id.get(artisan_id)
        if artisan is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        if not may_assign_any and getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail=_FOREIGN_ARTISAN_REFUSAL
            )
    await db.toolartisan.create_many(
        data=[{"toolId": tool_id, "artisanId": aid} for aid in wanted]
    )
    return await _assigned_artisans(tool_id)


@router.delete("/{tool_id}/artisans/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def unassign_tool_artisan(
    tool_id: str,
    artisan_id: str,
    current_user: Any = Depends(get_current_user),
) -> None:
    """Remove a tool-artisan link. Whoever could have created the link can remove it: the tool's
    owner, an EDIT-grant collaborator, an admin, or the artisan's own creator (so a mistaken
    self-service link is reversible by the person who made it)."""
    tool = await require_record(db.tooldocumentation, tool_id)
    if not await _may_manage_tool_links(tool, current_user):
        artisan = await db.artisan.find_unique(where={"id": artisan_id})
        if not artisan or getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, the artisan's creator, a professor senior to the "
                "tool's author, an EDIT-grant collaborator, or an admin can unassign artisans from "
                "this tool.",
            )
    # One statement, and still a no-op when the link is already gone — reading the row back first
    # only bought us its id, at the price of another cross-region round trip.
    await db.toolartisan.delete_many(where={"toolId": tool_id, "artisanId": artisan_id})


async def _may_manage_tool_links(tool: Any, current_user: Any) -> bool:
    """May this caller assign the tool to artisans SOMEBODY ELSE created?

    "The same people who may edit the tool's populated fields (``guard_record_edit``) may manage its
    artisan links" is what this function has always claimed, and what it now does. It used to answer
    the question with its own three-clause copy of that rule — admin, owner, EDIT-grant — and the
    clause it left out was ``may_edit_lower_ranked_record``: A PROFESSOR MAY EDIT THE DATA OF ANYONE
    RANKED BELOW THEM, which ``guard_record_edit`` has honoured since it was written. So a Professor
    was privileged for a junior researcher's tool ROW and unprivileged for its LINKS, and the record
    body's ``artisanIds`` — which both clients send on every save — met a 403 they could not act on.
    Delegating rather than re-listing the clauses is the fix: two answers to one question is what
    produced the disagreement.

    The three call sites are the record body's ``artisanIds`` (via ``_resolve_tool_links``),
    ``POST /tools/{id}/artisans`` and ``DELETE /tools/{id}/artisans/{artisan_id}``, and all three
    mean the same thing by it."""
    return await record_edit_privilege(tool, current_user, "tool")
