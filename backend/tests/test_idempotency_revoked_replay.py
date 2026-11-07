import itertools
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from app.core import deps
from app.core.idempotency import IdempotencyMiddleware, StoredAnswer
from app.core.security import create_access_token

KEY = "revoked-replay-key-0001"


class MemoryStore:
    def __init__(self) -> None:
        self.rows: dict[tuple[str, str], StoredAnswer] = {}

    async def get(self, user_id, key):
        return self.rows.get((user_id, key))

    async def claim(self, user_id, key, fingerprint):
        if (user_id, key) in self.rows:
            return False
        self.rows[(user_id, key)] = StoredAnswer(fingerprint, None, None, None, datetime.now(UTC))
        return True

    async def take_over(self, user_id, key, fingerprint, seen_created_at):
        return False

    async def complete(self, user_id, key, status_code, body, content_type):
        row = self.rows[(user_id, key)]
        self.rows[(user_id, key)] = StoredAnswer(row.fingerprint, status_code, body, content_type, row.created_at)

    async def release(self, user_id, key):
        self.rows.pop((user_id, key), None)


def _user(user_id: str = "user_1", **fields):
    values = {
        "id": user_id,
        "email": f"{user_id}@example.invalid",
        "sessionsValidFrom": None,
        "passwordHash": None,
        "mustChangePassword": False,
    }
    values.update(fields)
    return SimpleNamespace(**values)


@pytest.fixture
def accounts(monkeypatch):
    table: dict[str, SimpleNamespace] = {"user_1": _user()}

    async def resolve(user_id):
        return table.get(user_id)

    monkeypatch.setattr(deps, "resolve_user", resolve)
    return table


def _client():
    ids = itertools.count(1)
    app = FastAPI()

    @app.post("/api/crafts", status_code=201)
    async def create_craft(request: Request):
        body = await request.json()
        return {"id": f"craft_{next(ids)}", "name": body["name"]}

    app.add_middleware(IdempotencyMiddleware, store=MemoryStore())
    return TestClient(app)


def _headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Idempotency-Key": KEY}


def test_a_live_session_still_gets_the_stored_answer(accounts):
    client = _client()
    token = create_access_token("user_1")
    first = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    second = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    assert second.headers.get("idempotent-replayed") == "true"
    assert second.json() == first.json()


def test_a_revoked_session_is_not_served_the_stored_answer(accounts):
    client = _client()
    token = create_access_token("user_1")
    first = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    assert first.status_code == 201
    accounts["user_1"] = _user(sessionsValidFrom=datetime.now(UTC) + timedelta(minutes=5))
    replay = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    assert replay.headers.get("idempotent-replayed") is None
    assert replay.json() != first.json()


def test_a_deleted_account_is_not_served_the_stored_answer(accounts):
    client = _client()
    token = create_access_token("user_1")
    first = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    assert first.status_code == 201
    del accounts["user_1"]
    replay = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    assert replay.headers.get("idempotent-replayed") is None
    assert replay.json() != first.json()


def test_a_scoped_token_is_never_an_idempotency_caller(accounts):
    client = _client()
    token = create_access_token("user_1", {"scope": "dataset:read"})
    first = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    second = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_headers(token))
    assert second.headers.get("idempotent-replayed") is None
    assert second.json() != first.json()
