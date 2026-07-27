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
returned already has the six-tier predicate from ``visibility_where`` AND-composed into it, and the
media bucket gets the ``uploadedById`` variant rather than ``createdById`` because that is the
column media is owned by. A caller cannot get an unfiltered clause out of this module.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import HTTPException, status

from app.services.concurrency import gather_reads
from app.services.records import add_date_range, contains, visibility_where

# The five buckets, in the order they are counted, read and returned everywhere in the app. Kept
# here beside the clauses they describe; ``api/routes/search.py`` re-exports its own SEARCH_TYPES
# from this tuple so the two lists cannot fall out of order.
RECORD_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools", "media")

# Which buckets carry a free-text ``place`` column. Media does NOT: a photo has no place of its own,
# it inherits the record it belongs to. Naming that here stops a caller from quietly filtering media
# to nothing by passing a place, which is what a blanket loop over the five buckets would do.
PLACED_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools")


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
) -> dict[str, dict[str, Any]]:
    """One Prisma ``where`` per bucket, row visibility already folded in.

    Row visibility is resolved ONCE per owner column rather than once per bucket. It reads the grant
    table for anyone below professor, and the four record buckets all key off ``createdById``, so
    resolving it bucket by bucket issued the same query five times before the caller had asked for
    anything. The two remaining lookups are independent, so they go out together: free for a
    professor and above, one round trip instead of two for everybody else.
    """
    record_visibility, media_visibility = await gather_reads(
        visibility_where(user, owner_field="createdById"),
        visibility_where(user, owner_field="uploadedById"),
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

    return {
        "artisans": artisan_where,
        "workshops": workshop_where,
        "products": product_where,
        "tools": tool_where,
        "media": media_where,
    }
