from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Any

QUEUED = "QUEUED"
COMPLETED = "COMPLETED"
FAILED = "FAILED"

DEFAULT_PRIORITY = 100
DEFAULT_MAX_ATTEMPTS = 3

SUPERSEDED_WHERE = (
    'f."status" = \'FAILED\' AND EXISTS (SELECT 1 FROM "MediaProcessingJob" c '
    'WHERE c."mediaFileId" = f."mediaFileId" AND c."jobType" = f."jobType" '
    'AND c."status" = \'COMPLETED\' '
    'AND COALESCE(c."completedAt", c."createdAt") > COALESCE(f."completedAt", f."createdAt"))'
)
SUPERSEDED_SQL = f'SELECT f."id" FROM "MediaProcessingJob" f WHERE {SUPERSEDED_WHERE}'
SUPERSEDED_COUNT_SQL = (
    'SELECT f."jobType"::text AS "jobType", COUNT(*)::int AS n FROM "MediaProcessingJob" f '
    f'WHERE {SUPERSEDED_WHERE} GROUP BY f."jobType" ORDER BY f."jobType"'
)
SUPERSEDED_DELETE_SQL = f'DELETE FROM "MediaProcessingJob" f WHERE {SUPERSEDED_WHERE}'


def _default_client() -> Any:
    from app.core.db import db

    return db


def _in_transaction(client: Any) -> bool:
    probe = getattr(client, "is_transaction", None)
    return bool(probe()) if callable(probe) else False


@asynccontextmanager
async def atomic(client: Any = None) -> AsyncIterator[Any]:
    client = client if client is not None else _default_client()
    begin = getattr(client, "tx", None)
    if not callable(begin) or _in_transaction(client):
        yield client
        return
    async with begin() as tx:
        yield tx


def _name(raw: Any) -> str:
    return str(getattr(raw, "value", raw) or "").split(".")[-1]


def _status(job: Any) -> str:
    return _name(getattr(job, "status", None)).upper()


async def clear_failed(client: Any, media_id: str, job_type: str, *, keep_id: str | None = None) -> int:
    where: dict[str, Any] = {"mediaFileId": media_id, "jobType": job_type, "status": FAILED}
    if keep_id:
        where["id"] = {"not": keep_id}
    return await client.mediaprocessingjob.delete_many(where=where)


def _reopen_data(data: dict[str, Any], now: datetime) -> dict[str, Any]:
    return {
        "status": QUEUED,
        "priority": data.get("priority", DEFAULT_PRIORITY),
        "maxAttempts": data.get("maxAttempts", DEFAULT_MAX_ATTEMPTS),
        "attempts": 0,
        "error": None,
        "lockedAt": None,
        "lockedBy": None,
        "startedAt": None,
        "completedAt": None,
        "runAfter": data.get("runAfter", now),
        "createdAt": now,
        "requestedById": data.get("requestedById"),
        "productId": data.get("productId"),
        "toolId": data.get("toolId"),
        "openKey": data.get("openKey"),
    }


async def open_job(data: dict[str, Any], *, client: Any = None) -> Any:
    media_id = str(data["mediaFileId"])
    job_type = _name(data["jobType"])
    async with atomic(client) as tx:
        failed = await tx.mediaprocessingjob.find_first(
            where={"mediaFileId": media_id, "jobType": job_type, "status": FAILED},
            order={"createdAt": "desc"},
        )
        if failed is not None:
            reopened = await tx.mediaprocessingjob.update_many(
                where={"id": failed.id, "status": FAILED},
                data=_reopen_data(data, datetime.now(UTC)),
            )
            if reopened == 1:
                await clear_failed(tx, media_id, job_type, keep_id=failed.id)
                return await tx.mediaprocessingjob.find_unique(where={"id": failed.id})
        await clear_failed(tx, media_id, job_type)
        return await tx.mediaprocessingjob.create(data=data)


async def complete_job(job_id: str, data: dict[str, Any], *, client: Any = None) -> Any:
    async with atomic(client) as tx:
        job = await tx.mediaprocessingjob.update(where={"id": job_id}, data=data)
        media_id = getattr(job, "mediaFileId", None)
        job_type = getattr(job, "jobType", None)
        if media_id and job_type and _status(job) == COMPLETED:
            await clear_failed(tx, str(media_id), _name(job_type))
        return job


async def complete_open_jobs(
    media_id: str, job_type: str, data: dict[str, Any], *, client: Any = None
) -> int:
    async with atomic(client) as tx:
        done = await tx.mediaprocessingjob.update_many(
            where={"mediaFileId": media_id, "jobType": job_type, "status": QUEUED},
            data=data,
        )
        await clear_failed(tx, media_id, job_type)
        return done


async def superseded_failed_ids(client: Any = None) -> list[str]:
    client = client if client is not None else _default_client()
    rows = await client.query_raw(SUPERSEDED_SQL)
    return [str(row["id"]) for row in rows]


async def without_superseded(where: dict[str, Any], *, client: Any = None) -> dict[str, Any]:
    ids = await superseded_failed_ids(client)
    if not ids:
        return where
    return {"AND": [where, {"id": {"not_in": ids}}]}


async def superseded_counts(client: Any = None) -> dict[str, int]:
    client = client if client is not None else _default_client()
    rows = await client.query_raw(SUPERSEDED_COUNT_SQL)
    return {str(row["jobType"]): int(row["n"]) for row in rows}


async def delete_superseded(client: Any = None) -> int:
    client = client if client is not None else _default_client()
    return int(await client.execute_raw(SUPERSEDED_DELETE_SQL))
