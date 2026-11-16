from __future__ import annotations

import asyncio
import logging
import re
import time
from collections import OrderedDict
from datetime import datetime
from typing import Any

from app.questionnaire.common import aware, iso, naive_utc

logger = logging.getLogger(__name__)

WRITE_EVERY_SECONDS = 60
MAX_TRACKED_INSTALLS = 10_000
MAX_PENDING_WRITES = 32
MAX_COUNT = 1_000_000
MAX_LISTED = 500
INSTALL_ID = re.compile(r"^[A-Za-z0-9-]{8,64}$")
_last_write: OrderedDict[tuple[str, str], float] = OrderedDict()
_pending: set[asyncio.Task[Any]] = set()


def _header(scope: dict[str, Any], name: bytes) -> str:
    for key, value in scope.get("headers") or []:
        if key.lower() == name:
            return value.decode("latin-1").strip()
    return ""


def _count(text: str) -> int | None:
    if not text or not text.isdigit():
        return None
    value = int(text)
    return value if value <= MAX_COUNT else None


def _when(text: str) -> datetime | None:
    if not text:
        return None
    try:
        return aware(text)
    except ValueError:
        return None


def report_of(headers: dict[str, str]) -> dict[str, Any] | None:
    install = (headers.get("x-install-id") or "").strip()
    if not INSTALL_ID.match(install):
        return None
    pending = _count((headers.get("x-outbox-pending") or "").strip())
    failed = _count((headers.get("x-outbox-failed") or "").strip())
    if pending is None or failed is None:
        return None
    oldest = _when((headers.get("x-outbox-oldest") or "").strip()) if pending else None
    return {"installId": install, "pending": pending, "failed": min(failed, pending), "oldestAt": oldest}


def report_from_scope(scope: dict[str, Any]) -> dict[str, Any] | None:
    names = (b"x-install-id", b"x-outbox-pending", b"x-outbox-failed", b"x-outbox-oldest")
    return report_of({name.decode(): _header(scope, name) for name in names})


def should_write(user_id: str, install_id: str, *, now: float | None = None) -> bool:
    key = (user_id, install_id)
    moment = time.monotonic() if now is None else now
    last = _last_write.get(key)
    if last is not None and moment - last < WRITE_EVERY_SECONDS:
        return False
    _last_write[key] = moment
    _last_write.move_to_end(key)
    while len(_last_write) > MAX_TRACKED_INSTALLS:
        _last_write.popitem(last=False)
    return True


async def record(user_id: str, report: dict[str, Any], platform: str, app_version: str | None, *, client: Any = None) -> None:
    if client is None:
        from app.core.db import db as client
    await client.execute_raw(
        'INSERT INTO "DeviceOutboxReport" ("userId", "installId", "platform", "appVersion", "pending", "failed", "oldestAt", '
        '"reportedAt", "firstSeenAt") VALUES ($1, $2, $3, $4, $5, $6, $7::timestamp, now(), now()) '
        'ON CONFLICT ("userId", "installId") DO UPDATE SET "platform" = EXCLUDED."platform", "appVersion" = EXCLUDED."appVersion", '
        '"pending" = EXCLUDED."pending", "failed" = EXCLUDED."failed", "oldestAt" = EXCLUDED."oldestAt", "reportedAt" = EXCLUDED."reportedAt" '
        f'WHERE "DeviceOutboxReport"."reportedAt" <= EXCLUDED."reportedAt" - interval \'{WRITE_EVERY_SECONDS} seconds\'',
        user_id, report["installId"], platform, app_version, int(report["pending"]), int(report["failed"]),
        naive_utc(report["oldestAt"]) if report.get("oldestAt") else None,
    )


async def _record_quietly(user_id: str, report: dict[str, Any], platform: str, app_version: str | None) -> None:
    try:
        await record(user_id, report, platform, app_version)
    except Exception as exc:  # noqa: BLE001
        logger.info("outbox report not recorded (%s)", type(exc).__name__)


def _platform_of(scope: dict[str, Any]) -> tuple[str, str | None]:
    from app.services.app_versions import build_of

    build = build_of(scope)
    if build is None:
        return (_header(scope, b"x-app-platform").lower() or "unknown")[:16], (_header(scope, b"x-app-version") or None)
    platform, code, name = build
    return platform, (name or (str(code) if code else None))


class OutboxReportMiddleware:
    def __init__(self, app: Any) -> None:
        self.app = app

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        scope.setdefault("state", {})
        try:
            await self.app(scope, receive, send)
        finally:
            self._after(scope)

    def _after(self, scope: dict[str, Any]) -> None:
        try:
            from app.services.app_versions import SCOPE_USER_KEY

            state = scope.get("state") or {}
            user_id = state.get(SCOPE_USER_KEY) if isinstance(state, dict) else getattr(state, SCOPE_USER_KEY, None)
            if not user_id:
                return
            report = report_from_scope(scope)
            if report is None or len(_pending) >= MAX_PENDING_WRITES:
                return
            if not should_write(str(user_id), report["installId"]):
                return
            platform, app_version = _platform_of(scope)
            task = asyncio.get_running_loop().create_task(_record_quietly(str(user_id), report, platform, app_version))
            _pending.add(task)
            task.add_done_callback(_pending.discard)
        except Exception:  # noqa: BLE001
            return


async def holding(*, client: Any = None, limit: int = MAX_LISTED) -> dict[str, Any]:
    if client is None:
        from app.core.db import db as client
    rows = await client.query_raw(
        'SELECT d."userId", u."name", u."email", d."installId", d."platform", d."appVersion", d."pending", d."failed", '
        'd."oldestAt", d."reportedAt" FROM "DeviceOutboxReport" d JOIN "User" u ON u."id" = d."userId" '
        'WHERE d."pending" > 0 ORDER BY d."oldestAt" ASC NULLS LAST, d."reportedAt" ASC, d."installId" LIMIT $1',
        int(limit) + 1,
    )
    capped = len(rows) > limit
    items = [
        {
            "userId": str(r["userId"]), "name": r.get("name") or "", "email": r.get("email") or "",
            "installId": str(r["installId"]), "platform": r.get("platform") or "", "appVersion": r.get("appVersion"),
            "pending": int(r.get("pending") or 0), "failed": int(r.get("failed") or 0),
            "oldestAt": iso(r.get("oldestAt")), "reportedAt": iso(r.get("reportedAt")),
        }
        for r in rows[:limit]
    ]
    return {"items": items, "capped": capped, "limit": limit}
