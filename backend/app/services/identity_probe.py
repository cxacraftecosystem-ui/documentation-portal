from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from app.core import db as core_db
from app.core.fastapi_lazy import HTTPException, status

ACTION_MISS = "IDENTITY_MISS"
MISS_WINDOW = timedelta(days=1)
MISS_LIMIT = 100
TOO_MANY_MISSES = "Too many Aadhaar or Pehchan numbers matched nobody today. Try again tomorrow."


def unlimited(user: Any) -> bool:
    from app.core.roles import has_rank

    return bool(has_rank(user, "PROFESSOR"))


def _user_id(user: Any) -> str | None:
    from app.core.roles import get_value

    value = get_value(user, "id")
    return str(value) if value else None


async def misses_left(user: Any) -> int | None:
    if unlimited(user):
        return None
    user_id = _user_id(user)
    if not user_id:
        return 0
    since = datetime.now(UTC) - MISS_WINDOW
    used = await core_db.db.artisancardevent.count(
        where={"userId": user_id, "action": ACTION_MISS, "createdAt": {"gte": since}}
    )
    return max(0, MISS_LIMIT - int(used))


async def may_probe(user: Any) -> bool:
    left = await misses_left(user)
    return left is None or left > 0


async def assert_may_probe(user: Any) -> None:
    if not await may_probe(user):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=TOO_MANY_MISSES)


async def record_miss(user: Any) -> None:
    if unlimited(user):
        return
    await core_db.db.artisancardevent.create(
        data={"action": ACTION_MISS, "source": "WEB", "userId": _user_id(user), "count": 1}
    )
