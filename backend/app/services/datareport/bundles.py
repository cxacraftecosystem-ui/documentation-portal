from __future__ import annotations

import hashlib
from datetime import UTC, datetime
from typing import Any

LABEL_MAX = 200
IDS_MAX = 100000


def digest(media_ids: list[str]) -> str:
    joined = "\n".join(sorted(set(media_ids)))
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


async def store(db: Any, owner_id: str, label: str, media_ids: list[str], *, path: str = "", sizes: dict[str, int] | None = None) -> str:
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))[:IDS_MAX]
    key = digest(ids)
    found = await db.databundle.find_first(where={"ownerId": owner_id, "digest": key})
    if found is not None:
        return str(found.id)
    size = sum(int((sizes or {}).get(i, 0) or 0) for i in ids)
    try:
        made = await db.databundle.create(
            data={
                "ownerId": owner_id,
                "label": (label or "Report files").strip()[:LABEL_MAX] or "Report files",
                "path": path or "",
                "digest": key,
                "mediaIds": ids,
                "fileCount": len(ids),
                "bytes": size,
            }
        )
    except Exception:
        again = await db.databundle.find_first(where={"ownerId": owner_id, "digest": key})
        if again is None:
            raise
        return str(again.id)
    return str(made.id)


async def load(db: Any, bundle_id: str) -> Any | None:
    if not bundle_id or len(bundle_id) > 64:
        return None
    row = await db.databundle.find_unique(where={"id": bundle_id})
    if row is None:
        return None
    try:
        await db.databundle.update(where={"id": bundle_id}, data={"lastOpenedAt": datetime.now(UTC)})
    except Exception:  # noqa: BLE001 - the stamp is a convenience
        pass
    return row
