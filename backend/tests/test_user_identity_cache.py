import asyncio
import re
import time
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from fastapi import Depends, FastAPI

from app.core import deps
from app.core.security import create_access_token

BACKEND_ROOT = Path(__file__).resolve().parents[1]


class _CountingUsers:
    def __init__(self, rows: dict, delay: float = 0.0) -> None:
        self.rows = dict(rows)
        self.delay = delay
        self.calls: list[str] = []

    async def find_unique(self, where: dict, **_: object):
        self.calls.append(where["id"])
        if self.delay:
            await asyncio.sleep(self.delay)
        return self.rows.get(where["id"])

    @property
    def query_count(self) -> int:
        return len(self.calls)


def _row(user_id: str, role: str = "RESEARCHER", **extra):
    return SimpleNamespace(id=user_id, email=f"{user_id}@example.test", role=role, **extra)


class _Identity:
    def __init__(self, users: _CountingUsers, knobs: SimpleNamespace) -> None:
        self.users = users
        self.knobs = knobs
        self.app = FastAPI()

        @self.app.get("/whoami")
        async def whoami(user=Depends(deps.get_current_user)):  # pragma: no cover
            return {"id": user.id, "role": deps.role_value(user)}

        @self.app.get("/admin-only")
        async def admin_only(user=Depends(deps.require_admin)):  # pragma: no cover
            return {"id": user.id}

    async def get(self, path: str, user_id: str, *, claims: dict | None = None):
        transport = httpx.ASGITransport(app=self.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://identity.test") as client:
            token = create_access_token(subject=user_id, extra_claims=claims)
            return await client.get(path, headers={"Authorization": f"Bearer {token}"})

    async def get_many(self, path: str, user_id: str, count: int):
        transport = httpx.ASGITransport(app=self.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://identity.test") as client:
            token = create_access_token(subject=user_id)
            headers = {"Authorization": f"Bearer {token}"}
            return await asyncio.gather(*(client.get(path, headers=headers) for _ in range(count)))


@pytest.fixture
def identity(monkeypatch: pytest.MonkeyPatch):
    def build(rows: dict | None = None, *, delay: float = 0.0, enabled: bool = True,
              ttl: float = 5.0, max_entries: int = 512) -> _Identity:
        users = _CountingUsers(rows if rows is not None else {"u1": _row("u1")}, delay=delay)
        knobs = SimpleNamespace(
            auth_user_cache_enabled=enabled,
            auth_user_cache_ttl_seconds=ttl,
            auth_user_cache_max_entries=max_entries,
        )
        monkeypatch.setattr(deps, "db", SimpleNamespace(user=users))
        monkeypatch.setattr(deps, "get_settings", lambda: knobs)
        return _Identity(users, knobs)

    deps.clear_user_cache()
    yield build
    deps.clear_user_cache()



def test_an_authenticated_request_costs_one_query_cold_and_none_warm(identity) -> None:
    stack = identity()

    first = asyncio.run(stack.get("/whoami", "u1"))
    cold = stack.users.query_count
    second = asyncio.run(stack.get("/whoami", "u1"))
    warm = stack.users.query_count - cold

    assert (first.status_code, second.status_code) == (200, 200)
    assert second.json() == {"id": "u1", "role": "RESEARCHER"}
    assert (cold, warm) == (1, 0)


def test_a_burst_of_concurrent_cold_requests_costs_one_query_not_twenty(identity) -> None:
    stack = identity(delay=0.05)

    responses = asyncio.run(stack.get_many("/whoami", "u1", 20))

    assert [r.status_code for r in responses] == [200] * 20
    assert stack.users.query_count == 1


def test_turning_the_cache_off_restores_a_query_per_request(identity) -> None:
    stack = identity(enabled=False)

    asyncio.run(stack.get("/whoami", "u1"))
    asyncio.run(stack.get("/whoami", "u1"))

    assert stack.users.query_count == 2
    assert not deps._user_cache



def test_a_demotion_lands_on_the_next_request_once_the_write_invalidates(identity) -> None:
    stack = identity({"boss": _row("boss", role="ADMIN")})

    async def scenario():
        before = await stack.get("/admin-only", "boss")
        stack.users.rows["boss"] = _row("boss", role="RESEARCHER")
        still_cached = await stack.get("/admin-only", "boss")
        deps.invalidate_cached_user("boss")
        after = await stack.get("/admin-only", "boss")
        return before, still_cached, after

    before, still_cached, after = asyncio.run(scenario())

    assert before.status_code == 200
    assert still_cached.status_code == 200
    assert after.status_code == 403


def test_a_demotion_lands_when_the_ttl_expires_even_with_no_invalidation(identity) -> None:
    stack = identity({"boss": _row("boss", role="ADMIN")}, ttl=0.05)

    async def scenario():
        before = await stack.get("/admin-only", "boss")
        stack.users.rows["boss"] = _row("boss", role="RESEARCHER")
        await asyncio.sleep(0.12)
        return before, await stack.get("/admin-only", "boss")

    before, after = asyncio.run(scenario())

    assert (before.status_code, after.status_code) == (200, 403)


def test_a_deleted_account_stops_authenticating_immediately(identity) -> None:
    stack = identity({"gone": _row("gone")})

    async def scenario():
        before = await stack.get("/whoami", "gone")
        del stack.users.rows["gone"]
        deps.invalidate_cached_user("gone")
        return before, await stack.get("/whoami", "gone")

    before, after = asyncio.run(scenario())

    assert (before.status_code, after.status_code) == (200, 401)
    assert after.json()["detail"] == "User no longer exists"


def test_a_missing_user_is_never_cached_so_it_401s_every_single_time(identity) -> None:
    stack = identity({})

    responses = [asyncio.run(stack.get("/whoami", "ghost")) for _ in range(3)]

    assert [r.status_code for r in responses] == [401, 401, 401]
    assert stack.users.query_count == 3
    assert not deps._user_cache


def test_the_role_claim_in_the_token_is_not_what_authorises_the_request(identity) -> None:
    stack = identity({"u1": _row("u1", role="RESEARCHER")})

    response = asyncio.run(
        stack.get("/admin-only", "u1", claims={"role": "MASTER_ADMIN", "email": "x@y.z"})
    )

    assert response.status_code == 403


def test_a_revocation_during_an_in_flight_read_is_not_undone_by_it(identity) -> None:
    stack = identity({"u1": _row("u1", role="ADMIN")}, delay=0.1)

    async def scenario():
        request = asyncio.ensure_future(stack.get("/whoami", "u1"))
        await asyncio.sleep(0.02)
        deps.invalidate_cached_user("u1")
        await request
        return len(deps._user_cache)

    assert asyncio.run(scenario()) == 0



def test_the_cache_is_capped_and_evicts_the_least_recently_used_identity(identity) -> None:
    stack = identity({f"u{n}": _row(f"u{n}") for n in range(1, 5)}, max_entries=3)

    async def scenario():
        for name in ("u1", "u2", "u3"):
            await stack.get("/whoami", name)
        await stack.get("/whoami", "u1")
        await stack.get("/whoami", "u4")

    asyncio.run(scenario())

    assert len(deps._user_cache) == 3
    assert set(deps._user_cache) == {"u1", "u3", "u4"}


def test_an_expired_entry_is_dropped_rather_than_left_to_accumulate(identity) -> None:
    stack = identity({"u1": _row("u1")}, ttl=0.01)

    asyncio.run(stack.get("/whoami", "u1"))
    time.sleep(0.05)
    asyncio.run(stack.get("/whoami", "u1"))

    assert stack.users.query_count == 2
    assert len(deps._user_cache) == 1


def test_no_future_outlives_the_request_that_created_it(identity) -> None:
    stack = identity(delay=0.02)

    asyncio.run(stack.get_many("/whoami", "u1", 5))

    assert deps._user_cache_inflight == {}



def test_every_module_that_writes_a_user_row_also_invalidates_the_identity_cache() -> None:
    writes = re.compile(r"db\.user\.(update|create|delete|upsert|update_many|delete_many)\b")
    sources = [
        path
        for directory in ("app", "scripts")
        for path in (BACKEND_ROOT / directory).rglob("*.py")
    ]
    assert sources, "found no backend sources to audit"

    writers = {}
    for path in sources:
        text = path.read_text(encoding="utf-8")
        if writes.search(text):
            writers[path.relative_to(BACKEND_ROOT).as_posix()] = "invalidate_cached_user" in text

    assert set(writers) == {
        "app/api/routes/access_roster.py",
        "app/api/routes/auth.py",
        "app/api/routes/two_factor.py",
        "app/api/routes/users.py",
        "app/services/two_factor.py",
        "scripts/seed_admin.py",
    }
    assert all(writers.values()), f"user writes with no cache invalidation: {writers}"
