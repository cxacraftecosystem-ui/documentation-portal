"""Which app builds are in use (audit H22; DESIGN 5.6, owner decision 5).

Owner decision 5 stops old builds CREATING sessions 30 days after every active device runs the new
build. That needs a measurement, and this is it: a pure-ASGI middleware notes, for every signed-in
request, the account, the platform and the build NUMBER the client sends, and the last time it was
seen. ``GET /api/admin/app-versions`` reads it back.

THE HEADER CONTRACT, the same on every request from both clients (``services/questionnaire_v2/shims``
compares the same headers for the cutoff):

* ``X-App-Platform``: ``android`` or ``web``;
* ``X-App-Version-Code``: the build number, a positive integer (Android ``BuildConfig.VERSION_CODE``;
  the web's build number). Only the number rises: the version NAME was reset to 0.0.1 on 21 Oct;
* ``X-App-Version``: the version name, for people to read; never compared.

A build that sends none of them predates the contract. An Android one (an ``okhttp`` user agent) is
noted as version code 0; anything else without the headers (a script, a dataset token) is not noted.

Cheap by design on a swap-bound box: nothing is written on the request path. One row per (account,
platform, code) is upserted after the response, at most once per ten minutes per process, and a
failure is logged and dropped.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

logger = logging.getLogger(__name__)

#: Where ``get_current_user`` leaves the signed-in account's id for the middleware.
SCOPE_USER_KEY = "appVersionUserId"
THROTTLE_SECONDS = 600
_last_write: dict[tuple[str, str, int], float] = {}
_pending: set[asyncio.Task[Any]] = set()


def note_user(connection: Any, user_id: str | None) -> None:
    """Called by ``get_current_user``: remember who this request is, for the middleware."""
    if not user_id:
        return
    try:
        connection.scope.setdefault("state", {})[SCOPE_USER_KEY] = str(user_id)
    except Exception:  # noqa: BLE001 - telemetry never fails a request
        return


def _header(scope: dict[str, Any], name: bytes) -> str:
    for key, value in scope.get("headers") or []:
        if key.lower() == name:
            return value.decode("latin-1").strip()
    return ""


def build_of(scope: dict[str, Any]) -> tuple[str, int, str | None] | None:
    """``(platform, versionCode, versionName)`` for this request, or None when it is not an app."""
    from app.services.questionnaire_v2.shims import client_build

    headers = {
        "x-app-platform": _header(scope, b"x-app-platform"),
        "x-app-version-code": _header(scope, b"x-app-version-code"),
        "x-app-version": _header(scope, b"x-app-version"),
    }
    if not any(headers.values()):
        agent = _header(scope, b"user-agent").lower()
        if "okhttp" not in agent and "android" not in agent:
            return None
    platform, code, name = client_build(headers)
    return platform, int(code or 0), name


def should_write(user_id: str, platform: str, code: int, *, now: float | None = None) -> bool:
    key = (user_id, platform, code)
    moment = time.monotonic() if now is None else now
    last = _last_write.get(key)
    if last is not None and moment - last < THROTTLE_SECONDS:
        return False
    _last_write[key] = moment
    return True


async def record(user_id: str, platform: str, code: int, name: str | None, *, client: Any = None) -> None:
    if client is None:
        from app.core.db import db as client
    await client.execute_raw(
        'INSERT INTO "AppClientVersion" ("userId", "platform", "versionCode", "versionName", "firstSeenAt", "lastSeenAt") '
        'VALUES ($1, $2, $3, $4, now(), now()) ON CONFLICT ("userId", "platform", "versionCode") DO UPDATE SET '
        '"lastSeenAt" = now(), "versionName" = COALESCE(EXCLUDED."versionName", "AppClientVersion"."versionName")',
        user_id, platform, code, name,
    )


async def _record_quietly(user_id: str, platform: str, code: int, name: str | None) -> None:
    try:
        await record(user_id, platform, code, name)
    except Exception as exc:  # noqa: BLE001 - telemetry never fails anything
        logger.info("app version not recorded (%s)", type(exc).__name__)


class AppVersionMiddleware:
    """Pure ASGI: runs the request untouched, then notes the build of a signed-in caller."""

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
            state = scope.get("state") or {}
            user_id = state.get(SCOPE_USER_KEY) if isinstance(state, dict) else getattr(state, SCOPE_USER_KEY, None)
            if not user_id:
                return
            build = build_of(scope)
            if build is None:
                return
            platform, code, name = build
            if not should_write(str(user_id), platform, code):
                return
            task = asyncio.get_running_loop().create_task(_record_quietly(str(user_id), platform, code, name))
            _pending.add(task)
            task.add_done_callback(_pending.discard)
        except Exception:  # noqa: BLE001 - telemetry never fails a request
            return


async def summary(*, days: int = 30, client: Any = None) -> dict[str, Any]:
    """``GET /api/admin/app-versions``: per platform and build, how many accounts used it in the last
    ``days`` and when it was last seen, and how many accounts are still below each platform's
    create cutoff (the number decision 5 waits on to reach zero)."""
    from app.core.config import get_settings

    if client is None:
        from app.core.db import db as client
    rows = await client.query_raw(
        'SELECT v."platform", v."versionCode", max(v."versionName") AS "versionName", count(DISTINCT v."userId") AS users, '
        'max(v."lastSeenAt") AS "lastSeenAt" FROM "AppClientVersion" v '
        "WHERE v.\"lastSeenAt\" > now() - make_interval(days => $1) GROUP BY 1, 2 ORDER BY 1, 2 DESC",
        int(days),
    )
    latest = await client.query_raw(
        'SELECT DISTINCT ON (v."userId", v."platform") v."userId", v."platform", v."versionCode", v."lastSeenAt", u."name" '
        'FROM "AppClientVersion" v JOIN "User" u ON u."id" = v."userId" '
        "WHERE v.\"lastSeenAt\" > now() - make_interval(days => $1) "
        'ORDER BY v."userId", v."platform", v."lastSeenAt" DESC, v."versionCode" DESC',
        int(days),
    )
    settings = get_settings()
    minimums = {
        "android": settings.questionnaire_min_android_version_code_for_create,
        "web": settings.questionnaire_min_web_version_code_for_create,
    }

    def iso(value: Any) -> str | None:
        return value.isoformat() if hasattr(value, "isoformat") else (str(value) if value else None)

    below: dict[str, int] = {"android": 0, "web": 0}
    newest: dict[str, int] = {}
    for r in latest:
        code = int(r["versionCode"])
        newest[str(r["platform"])] = max(newest.get(str(r["platform"]), 0), code)
    for r in latest:
        platform = str(r["platform"])
        bar = minimums.get(platform) or newest.get(platform, 0)
        if int(r["versionCode"]) < bar:
            below[platform] = below.get(platform, 0) + 1
    return {
        "days": int(days),
        "builds": [
            {"platform": r["platform"], "versionCode": int(r["versionCode"]), "versionName": r.get("versionName"),
             "users": int(r["users"]), "lastSeenAt": iso(r.get("lastSeenAt"))}
            for r in rows
        ],
        "accounts": [
            {"userId": str(r["userId"]), "name": r.get("name"), "platform": r["platform"], "versionCode": int(r["versionCode"]),
             "lastSeenAt": iso(r.get("lastSeenAt"))}
            for r in latest
        ],
        "minimumVersionCodeForCreate": minimums,
        # Accounts whose latest build is below the cutoff (or, with none set, below the newest build seen).
        "accountsBelow": below,
    }
