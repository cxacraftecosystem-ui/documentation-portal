from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from app.services.field_voices.sources import Sources
from app.services.field_voices.stages import StagePlacement

PORTAL = "FIELD_REPOSITORY"

MERGES_SQL = (
    'SELECT "mergedId", "survivorId" FROM "ArtisanMerge" WHERE "status" = \'DONE\' '
    'ORDER BY "completedAt", "createdAt", "id"'
)
SHARING_REFUSED_SQL = (
    'SELECT "artisanId" FROM "ArtisanSharingConsent" WHERE "granted" = FALSE AND "artisanId" = ANY($1::text[])'
)


async def survivors(client: Any) -> dict[str, str]:
    return {str(r["mergedId"]): str(r["survivorId"]) for r in await client.query_raw(MERGES_SQL)}


async def extra_refusals(client: Any, artisan_ids: list[str]) -> set[str]:
    wanted = sorted({str(a) for a in artisan_ids if a})
    if not wanted:
        return set()
    return {str(r["artisanId"]) for r in await client.query_raw(SHARING_REFUSED_SQL, wanted)}


async def designer_profiles(client: Any, user_ids: Iterable[str]) -> dict[str, dict[str, Any]]:
    return {}


async def never_media(client: Any) -> set[str]:
    return set()


async def designer_photos(client: Any) -> dict[str, str]:
    return {}


async def design_workshops(client: Any, ids: Iterable[str]) -> dict[str, dict[str, Any]]:
    return {}


async def designers(client: Any, design_workshop_ids: Iterable[str]) -> dict[str, list[str]]:
    return {}


async def media_design_workshops(client: Any, sources: Sources) -> dict[str, str]:
    return {}


async def stage_rows_gone(client: Any, row_ids: Iterable[str]) -> set[str]:
    return set()


async def stage_media(client: Any) -> list[StagePlacement]:
    return []


async def stage_images(client: Any) -> list[StagePlacement]:
    return []
