"""The authenticated-identity cache, and the authorisation properties it must not break.

``get_current_user`` reads the user row on every authenticated request, and that row is what says
whether the caller is still a MASTER_ADMIN, still a RESEARCHER, or still an account at all. Caching
it buys a cross-region round trip on every request and costs a window in which those answers can be
out of date, so the tests here are almost all about the window rather than the saving: a demotion
must land, a deletion must land, a rotation of authority must not survive in memory, and none of it
may fall back on the role claim the token happens to carry.

Nothing here touches a database. ``db.user`` is replaced by a counter that records every
``find_unique`` it is asked for, which is what makes "one query, not N" a measurement rather than an
assertion about code that looks right.
"""

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
    """Stands in for ``db.user``: hands back rows and remembers every lookup it was asked for."""

    def __init__(self, rows: dict, delay: float = 0.0) -> None:
        self.rows = dict(rows)
        self.delay = delay
        self.calls: list[str] = []

    async def find_unique(self, where: dict, **_: object):
        self.calls.append(where["id"])
        if self.delay:
            # A cold window wide enough for several requests to arrive inside it; without this the
            # "N concurrent" test would be measuring how fast a dict lookup returns, not dedupe.
            await asyncio.sleep(self.delay)
        return self.rows.get(where["id"])

    @property
    def query_count(self) -> int:
        return len(self.calls)


def _row(user_id: str, role: str = "RESEARCHER", **extra):
    return SimpleNamespace(id=user_id, email=f"{user_id}@example.test", role=role, **extra)


class _Identity:
    """Everything a test needs to drive the dependency: the stub database, the knobs, the app."""

    def __init__(self, users: _CountingUsers, knobs: SimpleNamespace) -> None:
        self.users = users
        self.knobs = knobs
        self.app = FastAPI()

        @self.app.get("/whoami")
        async def whoami(user=Depends(deps.get_current_user)):  # pragma: no cover - via HTTP
            return {"id": user.id, "role": deps.role_value(user)}

        @self.app.get("/admin-only")
        async def admin_only(user=Depends(deps.require_admin)):  # pragma: no cover - via HTTP
            return {"id": user.id}

    async def get(self, path: str, user_id: str, *, claims: dict | None = None):
        transport = httpx.ASGITransport(app=self.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://identity.test") as client:
            token = create_access_token(subject=user_id, extra_claims=claims)
            return await client.get(path, headers={"Authorization": f"Bearer {token}"})

    async def get_many(self, path: str, user_id: str, count: int):
        """*count* requests issued together on one event loop, i.e. a page load's burst."""
        transport = httpx.ASGITransport(app=self.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://identity.test") as client:
            token = create_access_token(subject=user_id)
            headers = {"Authorization": f"Bearer {token}"}
            return await asyncio.gather(*(client.get(path, headers=headers) for _ in range(count)))


@pytest.fixture
def identity(monkeypatch: pytest.MonkeyPatch):
    """Build an isolated identity stack; the factory takes the rows and the cache knobs."""

    def build(rows: dict | None = None, *, delay: float = 0.0, enabled: bool = True,
              ttl: float = 5.0, max_entries: int = 512) -> _Identity:
        users = _CountingUsers(rows if rows is not None else {"u1": _row("u1")}, delay=delay)
        knobs = SimpleNamespace(
            auth_user_cache_enabled=enabled,
            auth_user_cache_ttl_seconds=ttl,
            auth_user_cache_max_entries=max_entries,
        )
        # Only the cache's own settings are stubbed. Token signing still goes through the real
        # settings, so these requests carry genuine HS256 bearer tokens.
        monkeypatch.setattr(deps, "db", SimpleNamespace(user=users))
        monkeypatch.setattr(deps, "get_settings", lambda: knobs)
        return _Identity(users, knobs)

    deps.clear_user_cache()
    yield build
    deps.clear_user_cache()


# --- What it saves -------------------------------------------------------------------------------


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


# --- What it must not break ----------------------------------------------------------------------


def test_a_demotion_lands_on_the_next_request_once_the_write_invalidates(identity) -> None:
    stack = identity({"boss": _row("boss", role="ADMIN")})

    async def scenario():
        before = await stack.get("/admin-only", "boss")
        # The write an admin route makes: the row is now RESEARCHER...
        stack.users.rows["boss"] = _row("boss", role="RESEARCHER")
        still_cached = await stack.get("/admin-only", "boss")
        # ...and the write's invalidate_cached_user call is what makes it visible.
        deps.invalidate_cached_user("boss")
        after = await stack.get("/admin-only", "boss")
        return before, still_cached, after

    before, still_cached, after = asyncio.run(scenario())

    assert before.status_code == 200
    # Proof the cache is genuinely holding the identity — otherwise the next assertion proves
    # nothing about invalidation.
    assert still_cached.status_code == 200
    assert after.status_code == 403


def test_a_demotion_lands_when_the_ttl_expires_even_with_no_invalidation(identity) -> None:
    """The backstop for a role changed by psql, the seed script, or another worker."""
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
        del stack.users.rows["gone"]  # what db.user.delete does
        deps.invalidate_cached_user("gone")
        return before, await stack.get("/whoami", "gone")

    before, after = asyncio.run(scenario())

    assert (before.status_code, after.status_code) == (200, 401)
    assert after.json()["detail"] == "User no longer exists"


def test_a_missing_user_is_never_cached_so_it_401s_every_single_time(identity) -> None:
    stack = identity({})

    responses = [asyncio.run(stack.get("/whoami", "ghost")) for _ in range(3)]

    assert [r.status_code for r in responses] == [401, 401, 401]
    # Three requests, three queries: a negative result is not an entry that can expire into a yes.
    assert stack.users.query_count == 3
    assert not deps._user_cache


def test_the_role_claim_in_the_token_is_not_what_authorises_the_request(identity) -> None:
    """A seven-day token minted before a demotion still says ADMIN; only the row decides."""
    stack = identity({"u1": _row("u1", role="RESEARCHER")})

    response = asyncio.run(
        stack.get("/admin-only", "u1", claims={"role": "MASTER_ADMIN", "email": "x@y.z"})
    )

    assert response.status_code == 403


def test_a_revocation_during_an_in_flight_read_is_not_undone_by_it(identity) -> None:
    """The write can commit while a slow read is already on the wire; the read must not win."""
    stack = identity({"u1": _row("u1", role="ADMIN")}, delay=0.1)

    async def scenario():
        request = asyncio.ensure_future(stack.get("/whoami", "u1"))
        await asyncio.sleep(0.02)  # the query is out, the answer is not back
        deps.invalidate_cached_user("u1")
        await request
        return len(deps._user_cache)

    assert asyncio.run(scenario()) == 0


# --- What it costs -------------------------------------------------------------------------------


def test_the_cache_is_capped_and_evicts_the_least_recently_used_identity(identity) -> None:
    stack = identity({f"u{n}": _row(f"u{n}") for n in range(1, 5)}, max_entries=3)

    async def scenario():
        for name in ("u1", "u2", "u3"):
            await stack.get("/whoami", name)
        await stack.get("/whoami", "u1")  # u2 is now the least recently used
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


# --- Coverage of the invalidation contract itself -------------------------------------------------


def test_every_module_that_writes_a_user_row_also_invalidates_the_identity_cache() -> None:
    """The audit that keeps requirement "invalidate on every write" true as the code moves.

    A cached identity is only as safe as the completeness of its invalidation, and completeness is
    exactly the property a reviewer cannot see from any one diff. So it is checked mechanically:
    any module that writes a User row must also name ``invalidate_cached_user``. Adding a new
    promotion or deactivation path therefore fails here rather than in production.
    """
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

    # The known set, so that a write path DISAPPEARING (moved into a helper the regex misses) is as
    # visible as a new one arriving without invalidation.
    assert set(writers) == {
        "app/api/routes/auth.py",
        "app/api/routes/users.py",
        "scripts/seed_admin.py",
    }
    assert all(writers.values()), f"user writes with no cache invalidation: {writers}"
