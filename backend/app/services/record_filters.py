"""The repository's ONE filter language, as Prisma ``where`` clauses.

Every screen that narrows the corpus — the Search page, the View Data panel, Android's browse
screen, and now the map — asks the same eight questions: a free-text query, a craft, a place, an
artisan, a media type, which record types, and a date range. This module turns that vocabulary into
the per-bucket ``where`` clauses, once, so a second screen cannot quietly grow a second dialect of
it. A map that agreed with the search box about "Bagru, last 30 days" only most of the time would be
worse than no map: the two would disagree about how many records exist and there would be no way to
tell which was right.

WHY IT IS A SERVICE AND NOT A ROUTE HELPER. ``GET /search`` built these clauses inline, which was
fine while it was the only caller. It also meant the rules had no tests of their own — the only way
to exercise them was to stand up the route against a database. Lifting them here makes them
ordinary functions over dictionaries, which is what ``tests/test_record_filters.py`` now checks.

ROW VISIBILITY IS PART OF THE ANSWER, never an afterthought a caller might forget. Every clause
returned already has the read predicate from ``records.viewable_where`` AND-composed into it, and the
media bucket gets the ``uploadedById`` variant rather than ``createdById`` because that is the
column media is owned by. A caller cannot get an unfiltered clause out of this module.

That predicate is EMPTY today — reading the repository is open to every signed-in account, see the
banner comment above ``viewable_where`` in ``services/records`` — so composing it costs nothing and
changes no query plan. It is composed anyway, at the one place every search-shaped screen passes
through, because the day a read rule does appear it must appear on all of them at once.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import HTTPException, status

from app.services.concurrency import gather_reads
from app.services.records import add_date_range, contains, viewable_where

# The five buckets, in the order they are counted, read and returned everywhere in the app. Kept
# here beside the clauses they describe; ``api/routes/search.py`` re-exports its own SEARCH_TYPES
# from this tuple so the two lists cannot fall out of order.
RECORD_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools", "media")

# Which buckets carry a free-text ``place`` column. Media does NOT: a photo has no place of its own,
# it inherits the record it belongs to. Naming that here stops a caller from quietly filtering media
# to nothing by passing a place, which is what a blanket loop over the five buckets would do.
PLACED_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools")

# The value a client sends in ``workshopIds`` to mean "records that are not linked to any workshop".
#
# A workshop filter without this is unusable as a scope control: tick every workshop and the records
# filed before workshops existed vanish, with nothing on screen to say they were excluded. It is a
# reserved word rather than an empty string because an empty string is what a blank form field sends,
# and "the user has not chosen anything" must not mean "show me only the orphans".
UNASSIGNED_WORKSHOP = "none"


def resolve_workshop_ids(raw: list[str] | None) -> tuple[list[str], bool] | None:
    """Parse ``workshopIds`` into (real ids, include-unassigned), or ``None`` for "every workshop".

    Accepts the two spellings clients build query strings with — repeated parameters
    (``?workshopIds=a&workshopIds=b``) and one comma-joined value (``?workshopIds=a,b``) — for the
    same reason ``resolve_types`` does: the web and Android assemble them differently, and a scope
    that quietly covered everything because it was spelled the other way would look exactly like the
    control not working.

    ``None`` (absent, empty, or all-blank) means DO NOT FILTER. That is deliberately distinct from an
    empty selection: a caller that means "no workshops at all" has nothing to ask for, whereas the
    default state of the control is "all workshops" and must not be spelled the same way as a mistake.
    """
    if not raw:
        return None
    wanted = [
        part.strip()
        for value in raw
        for part in str(value).split(",")
        if part.strip()
    ]
    if not wanted:
        return None
    include_unassigned = any(value == UNASSIGNED_WORKSHOP for value in wanted)
    ids = list(dict.fromkeys(value for value in wanted if value != UNASSIGNED_WORKSHOP))
    return ids, include_unassigned


def workshop_clause(
    ids: list[str], include_unassigned: bool, *, is_workshop_table: bool = False
) -> dict[str, Any] | None:
    """A workshop predicate for one table, or ``None`` when the selection cannot narrow that table.

    THE WORKSHOP TABLE IS THE ODD ONE and getting it wrong empties the map. Every other table carries
    a ``workshopId`` FOREIGN KEY; a workshop row IS the workshop, so its predicate is on its own
    primary key. Filtering ``Workshop.workshopId`` would be filtering a column that does not exist,
    and asking Prisma for a column a model lacks is an error rather than an empty result — so this is
    a correctness fork, not a tidiness one. It is a keyword flag rather than a bucket-name comparison
    so that a caller outside the five map buckets (the questionnaire's interview scan, say) cannot
    take the wrong branch by passing a name this function has never heard of.

    ``include_unassigned`` widens the clause with ``workshopId: null``. For the workshop table itself
    it is meaningless — a workshop cannot be unassigned from itself — so an "unassigned only"
    selection correctly excludes every workshop row.
    """
    if is_workshop_table:
        return {"id": {"in": ids}} if ids else None
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
    if include_unassigned:
        branches.append({"workshopId": None})
    if not branches:
        return None
    # A single branch is written flat rather than as a one-armed OR: it is the same query to Postgres
    # and a far more readable one in a log.
    return branches[0] if len(branches) == 1 else {"OR": branches}


def artisan_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    """"Which artisans belong to these workshops" — an ``Artisan`` predicate, always non-empty.

    AN ARTISAN BELONGS TO A WORKSHOP THREE WAYS and all three count, which is why this is a shared
    helper rather than an inline clause on whichever screen needed it first. Two screens disagreeing
    about who was at a workshop is two screens disagreeing about what the workshop's data IS.

      1. ``Artisan.workshopId`` — the column added when every record type gained a workshop.
      2. The ``WorkshopArtisan`` roster, which carried the link before that column existed. Records
         predating the column have only this, and the ``GET /artisans?workshopId=`` filter has always
         honoured both.
      3. Having SAT IN an interview taken at the workshop. Without this a workshop whose roster was
         never filled in shows an empty artisan list while its interviews sit right there — which is
         the difference between "nobody was documented" and "nobody typed the roster".

    Returns an IMPOSSIBLE predicate rather than ``None`` when the selection can match nothing, so a
    caller cannot mistake "matches no artisan" for "do not filter" — the failure mode that shows the
    whole repository under a scope that excludes all of it.
    """
    branches: list[dict[str, Any]] = []
    if ids:
        branches.extend(
            [
                {"workshopId": {"in": ids}},
                {"workshops": {"some": {"workshopId": {"in": ids}}}},
                {"questionnaireInterviews": {"some": {"interview": {"is": {"workshopId": {"in": ids}}}}}},
            ]
        )
    if include_unassigned:
        # An artisan with no workshop column set. Deliberately NOT also "no roster row and no
        # interview": an artisan on a workshop's roster whose column was never backfilled is somebody
        # the workshop knows about, and calling them unassigned would double-count them.
        branches.append({"workshopId": None})
    return {"OR": branches} if branches else {"id": {"in": []}}


def craft_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    """"Which crafts belong to these workshops" — a ``Craft`` predicate, always non-empty.

    A CRAFT REACHES A WORKSHOP TWO WAYS and both count, which is why this is a shared helper rather
    than the bare ``workshopId`` column test every other table gets:

      1. ``Craft.workshopId`` — the column added when every record type gained a workshop.
      2. The ``WorkshopCraft`` join, which carried the link before that column existed and is still
         what ``POST``/``PATCH /crafts`` writes alongside the column (``link_workshop_craft``), what
         a workshop's "Crafts covered" picker reads, and what the export tree builds its craft
         folders from.

    THE TWO GENUINELY DISAGREE ON THE LIVE REPOSITORY. Every craft on it was created before the
    column existed, so all of them had a NULL ``workshopId`` and a perfectly good join row: a
    column-only predicate returned NOTHING for a workshop whose crafts were sitting right there. That
    is the repository's most repeated bug — a scope that renders empty over a full corpus and looks
    exactly like having no data. ``GET /crafts?workshopId=`` has always read both (see the note above
    its ``where`` clause); this is that reading, lifted into the shared vocabulary so a second screen
    cannot answer the question differently.

    Returns an IMPOSSIBLE predicate rather than ``None`` when the selection can match nothing, for
    the same reason ``artisan_workshop_clause`` does: "matches no craft" must not be mistakable for
    "do not filter".
    """
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
        branches.append({"workshops": {"some": {"workshopId": {"in": ids}}}})
    if include_unassigned:
        # Unassigned means BOTH readings are empty. A craft with a join row but no column is linked
        # to that workshop by every query in the app, so calling it unassigned would double-count it
        # — the same rule artisan_workshop_clause applies to a roster-only artisan.
        branches.append({"AND": [{"workshopId": None}, {"workshops": {"none": {}}}]})
    return {"OR": branches} if branches else {"id": {"in": []}}


def resolve_types(raw: list[str] | None) -> set[str]:
    """Which buckets this request covers. Absent, empty, or all-blank means all five.

    Accepts both spellings a client might reach for — repeated parameters
    (``?types=artisans&types=media``) and one comma-joined value (``?types=artisans,media``) —
    because the web and Android build query strings differently, and a filter that quietly covered
    everything because it was spelled the other way would look exactly like the filter not working.

    An unrecognised bucket name is a 422 rather than a silent omission. Dropping it would answer a
    request for "artisan" (singular, a plausible typo) with a perfectly well-formed empty result,
    and the client would report "no matches" for data that is sitting right there — a wrong answer
    dressed as a correct one.
    """
    if not raw:
        return set(RECORD_TYPES)
    wanted = {
        part.strip().lower()
        for value in raw
        for part in str(value).split(",")
        if part.strip()
    }
    if not wanted:
        return set(RECORD_TYPES)
    unknown = sorted(wanted - set(RECORD_TYPES))
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown search type{'s' if len(unknown) > 1 else ''}: {', '.join(unknown)}. "
                f"Valid types are {', '.join(RECORD_TYPES)}."
            ),
        )
    return wanted


async def build_record_wheres(
    user: Any,
    *,
    q: str | None = None,
    craft_id: str | None = None,
    place: str | None = None,
    artisan_id: str | None = None,
    media_type: str | None = None,
    date_from: datetime | None = None,
    date_to: datetime | None = None,
    workshop_ids: list[str] | None = None,
) -> dict[str, dict[str, Any]]:
    """One Prisma ``where`` per bucket, row visibility already folded in.

    Row visibility is resolved ONCE per owner column rather than once per bucket. It reads the grant
    table for anyone below professor, and the four record buckets all key off ``createdById``, so
    resolving it bucket by bucket issued the same query five times before the caller had asked for
    anything. The two remaining lookups are independent, so they go out together: free for a
    professor and above, one round trip instead of two for everybody else.
    """
    record_visibility, media_visibility = await gather_reads(
        viewable_where(user, owner_field="createdById"),
        viewable_where(user, owner_field="uploadedById"),
    )

    # Row-visibility joins each where under AND, so the free-text ORs assigned below never overwrite
    # it — and neither does anything else that needs its own OR.
    artisan_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    workshop_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    product_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    tool_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    media_where: dict[str, Any] = {"AND": [media_visibility]} if media_visibility else {}

    # Each filter below writes its own key, so every active one ANDs with the rest: a query plus a
    # place plus a date range narrows to the rows satisfying all three, never their union.
    if q:
        artisan_where["OR"] = [{"name": contains(q)}, {"localName": contains(q)}, {"place": contains(q)}]
        workshop_where["OR"] = [{"title": contains(q)}, {"place": contains(q)}, {"description": contains(q)}]
        product_where["OR"] = [
            {"productName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        tool_where["OR"] = [
            {"toolkitName": contains(q)},
            {"englishName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        media_where["OR"] = [
            {"originalFilename": contains(q)},
            {"caption": contains(q)},
            {"mimeType": contains(q)},
        ]

    if craft_id:
        artisan_where["craftId"] = craft_id
        product_where["craftId"] = craft_id
        tool_where["craftId"] = craft_id
    if place:
        artisan_where["place"] = contains(place)
        workshop_where["place"] = contains(place)
        product_where["place"] = contains(place)
        tool_where["place"] = contains(place)
    if artisan_id:
        product_where["artisanId"] = artisan_id
        tool_where["artisanId"] = artisan_id
    if media_type:
        media_where["mediaType"] = media_type

    # Workshops filter on startDate (matching the /workshops list route); rows created before
    # startDate existed fall back to the legacy single `date`. Nested under AND so it composes with
    # the free-text OR built above.
    if date_from or date_to:
        date_range: dict[str, Any] = {}
        if date_from:
            date_range["gte"] = date_from
        if date_to:
            date_range["lte"] = date_to
        workshop_where.setdefault("AND", []).append(
            {"OR": [{"startDate": date_range}, {"startDate": None, "date": date_range}]}
        )
    # Artisans were the one bucket the date range never reached: passing dateFrom returned every
    # artisan ever recorded alongside four correctly-filtered buckets, which reads as the filter
    # being broken rather than as artisans being exempt. Same column as the three below.
    add_date_range(artisan_where, "createdAt", date_from, date_to)
    add_date_range(product_where, "createdAt", date_from, date_to)
    add_date_range(tool_where, "createdAt", date_from, date_to)
    add_date_range(media_where, "createdAt", date_from, date_to)

    wheres = {
        "artisans": artisan_where,
        "workshops": workshop_where,
        "products": product_where,
        "tools": tool_where,
        "media": media_where,
    }

    # THE WORKSHOP SCOPE, applied last and to every bucket at once.
    #
    # A workshop is the unit the fieldwork actually happens in, so "the records from these workshops"
    # is the question every cross-workshop conclusion starts from — which is why it belongs in the
    # SHARED filter vocabulary rather than being bolted onto whichever screen needed it first. The map,
    # the search box, the completion matrix and the consolidated questionnaire all read it from here,
    # so they cannot disagree about what "this workshop" contains.
    #
    # Nested under AND, like the row filter and the workshop-date clause above, so it composes with
    # the free-text OR instead of overwriting it — and so a bucket that already has an AND (workshops,
    # once a date range is in play) gains a clause rather than losing one.
    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        for bucket, where in wheres.items():
            clause = workshop_clause(
                ids, include_unassigned, is_workshop_table=(bucket == "workshops")
            )
            if clause is not None:
                where.setdefault("AND", []).append(clause)
            else:
                # The selection cannot match anything in this bucket — "unassigned only" against the
                # workshops bucket is the real case. An impossible predicate is the honest answer: the
                # alternative is leaving the bucket unfiltered, which would show every workshop in the
                # repository under a scope that excludes them all.
                where.setdefault("AND", []).append({"id": {"in": []}})

    return wheres
