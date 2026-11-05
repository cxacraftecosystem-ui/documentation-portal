from __future__ import annotations

import importlib
from typing import Any
from urllib.parse import quote

from fastapi import HTTPException, status

from app.services.datareport.portal import current as current_portal

LIVE_KINDS = frozenset({"interview"})
RECORD_DELEGATES = {
    "artisan": "artisan",
    "workshop": "workshop",
    "craft": "craft",
    "product": "productdocumentation",
    "process": "process",
    "tool": "tooldocumentation",
    "interview": "questionnaireinterview",
}


def not_found(what: str = "Not found") -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=what)


def _value(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def _module(name: str) -> Any:
    return importlib.import_module(name)


def downloads_data(user: Any) -> bool:
    from app.core.deps import can_download_dataset

    return bool(can_download_dataset(user))


def _db() -> Any:
    from app.core.db import db

    return db


async def live_media(media_id: str) -> Any:
    browser = _module("app.api.routes.data_browser")
    slim = _module("app.services.data_explorer.slim")
    media = await slim.find_one(
        where={"AND": [{"id": media_id}, {"removedAt": None}]}, include=browser._NAMING_INCLUDE
    )
    if media is None:
        raise not_found("File not found")
    return media


async def _in_dataset_scope(user: Any, media_id: str) -> bool:
    if not downloads_data(user):
        return False
    explorer = _module("app.api.routes.data_explorer")
    try:
        await explorer.media_for(user, media_id)
    except HTTPException:
        return False
    return True


async def may_take_file(user: Any, media: Any) -> bool:
    records = _module("app.services.records")
    node = {
        "objectKey": _value(media, "objectKey") or "-",
        "uploadedById": _value(media, "uploadedById"),
        "linkedRecordType": _value(media, "linkedRecordType"),
        "linkedRecordId": _value(media, "linkedRecordId"),
    }
    scope = getattr(records, "media_url_scope", None)
    if callable(scope):
        urls, workshops = await scope(user)
        encoded = records.public_encode(node, user, media_urls=urls, media_workshops=workshops)
    else:
        encoded = records.public_encode(node, user, media_urls=await records.media_url_owners(user))
    return isinstance(encoded, dict) and "objectKey" in encoded


async def file_for(user: Any, media_id: str) -> Any:
    media = await live_media(media_id)
    if await may_take_file(user, media) or await _in_dataset_scope(user, media_id):
        return media
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You need access to this file. Ask its owner or an admin.")


async def transcript_for(user: Any, media_id: str) -> Any:
    from app.services import transcript_export, transcript_portal

    await live_media(media_id)
    full = await _db().mediafile.find_unique(where={"id": media_id})
    if full is None:
        raise not_found("Transcript not found")
    readable = str(full.id) in await transcript_portal.readable_ids([full], user)
    if not readable and not await _in_dataset_scope(user, media_id):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You need access to this transcript. Ask its owner or an admin.")
    if str(full.id) in await transcript_export.withheld_ids([str(full.id)]):
        raise not_found("Transcript not found")
    return full


def media_href(user: Any, media_id: str) -> str:
    safe = quote(str(media_id), safe="")
    return f"/data?preview={safe}" if downloads_data(user) else f"/media/{safe}"


async def record_href(user: Any, kind: str, record_id: str) -> str:
    portal = current_portal()
    if kind == "media":
        media = await live_media(record_id)
        return media_href(user, str(media.id))
    pattern = portal.open_paths.get(kind)
    if pattern is None:
        raise not_found()
    if kind == "designWorkshop":
        workshops = _module("app.services.design_workshops")
        await workshops.load_workshop_or_404(record_id, user)
    else:
        delegate = getattr(_db(), RECORD_DELEGATES.get(kind, ""), None)
        if delegate is None:
            raise not_found()
        found = await delegate.find_unique(where={"id": record_id})
        if found is None or (kind in LIVE_KINDS and _value(found, "deletedAt") is not None):
            raise not_found("Record not found")
    return pattern.format(id=quote(record_id, safe=""))
