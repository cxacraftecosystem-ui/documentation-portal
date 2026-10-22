"""``Idempotency-Key``: a create sent twice is written once, and everything without the header is
untouched. See ``app/core/idempotency.py``.

NO DATABASE. The middleware runs in front of a small route that counts the records it writes, with
an in-memory store; the Prisma store is driven against a recording stub of its table.
"""

import asyncio
import itertools
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
from prisma.errors import UniqueViolationError

from app.core import idempotency
from app.core.idempotency import IdempotencyMiddleware, PrismaIdempotencyStore, StoredAnswer
from app.core.security import create_access_token


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
        row = self.rows.get((user_id, key))
        if row is None or row.status_code is not None or row.created_at != seen_created_at:
            return False
        self.rows[(user_id, key)] = StoredAnswer(fingerprint, None, None, None, datetime.now(UTC))
        return True

    async def complete(self, user_id, key, status_code, body, content_type):
        row = self.rows[(user_id, key)]
        self.rows[(user_id, key)] = StoredAnswer(row.fingerprint, status_code, body, content_type, row.created_at)

    async def release(self, user_id, key):
        self.rows.pop((user_id, key), None)


def _app(store: MemoryStore):
    records: list[dict] = []
    ids = itertools.count(1)
    app = FastAPI()

    @app.post("/api/crafts", status_code=201)
    async def create_craft(request: Request):
        body = await request.json()
        if body.get("name") == "boom":
            raise RuntimeError("the route failed")
        if body.get("name") == "refuse":
            from fastapi import HTTPException

            raise HTTPException(status_code=409, detail="A craft with this name exists")
        record = {"id": f"craft_{next(ids)}", "name": body["name"]}
        records.append(record)
        return record

    @app.post("/api/auth/login")
    async def login():
        record = {"token": f"t{next(ids)}"}
        records.append(record)
        return record

    app.add_middleware(IdempotencyMiddleware, store=store)
    return app, records


def _auth(user: str = "user_1") -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(user)}"}


def test_a_repeat_returns_the_same_record_and_writes_no_duplicate():
    store = MemoryStore()
    app, records = _app(store)
    client = TestClient(app)
    headers = {**_auth(), "Idempotency-Key": "4f1d2c7e-0000-4000-8000-000000000001"}

    first = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=headers)
    second = client.post("/api/crafts", json={"name": "Ajrakh"}, headers=headers)

    assert first.status_code == 201
    assert second.status_code == 201
    assert second.json() == first.json() == {"id": "craft_1", "name": "Ajrakh"}
    assert second.headers.get("idempotent-replayed") == "true"
    assert len(records) == 1


def test_without_the_header_every_post_runs_as_before():
    app, records = _app(MemoryStore())
    client = TestClient(app)
    client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_auth())
    client.post("/api/crafts", json={"name": "Ajrakh"}, headers=_auth())
    assert [r["id"] for r in records] == ["craft_1", "craft_2"]


def test_keys_are_per_account():
    app, records = _app(MemoryStore())
    client = TestClient(app)
    key = {"Idempotency-Key": "shared-key-0001"}
    a = client.post("/api/crafts", json={"name": "Ajrakh"}, headers={**_auth("user_a"), **key})
    b = client.post("/api/crafts", json={"name": "Ajrakh"}, headers={**_auth("user_b"), **key})
    assert a.json()["id"] != b.json()["id"]
    assert len(records) == 2


def test_the_same_key_for_a_different_body_is_refused():
    app, records = _app(MemoryStore())
    client = TestClient(app)
    headers = {**_auth(), "Idempotency-Key": "reused-key-0001"}
    client.post("/api/crafts", json={"name": "Ajrakh"}, headers=headers)
    other = client.post("/api/crafts", json={"name": "Bandhani"}, headers=headers)
    assert other.status_code == 422
    assert len(records) == 1


def test_a_refused_or_failed_create_is_not_remembered():
    store = MemoryStore()
    app, records = _app(store)
    client = TestClient(app, raise_server_exceptions=False)
    headers = {**_auth(), "Idempotency-Key": "failing-key-0001"}
    assert client.post("/api/crafts", json={"name": "refuse"}, headers=headers).status_code == 409
    assert store.rows == {}
    assert client.post("/api/crafts", json={"name": "boom"}, headers=headers).status_code == 500
    assert store.rows == {}


def test_a_repeat_while_the_first_is_running_is_told_to_retry():
    store = MemoryStore()
    app, records = _app(store)
    client = TestClient(app)
    asyncio.run(store.claim("user_1", "running-key-0001", "anything"))
    # The fingerprint must match for the in-progress answer; take it from what the request will send.
    store.rows[("user_1", "running-key-0001")] = StoredAnswer(
        idempotency._fingerprint("POST", "/api/crafts", b"", b'{"name":"Ajrakh"}'), None, None, None, datetime.now(UTC)
    )
    answer = client.post(
        "/api/crafts",
        content=b'{"name":"Ajrakh"}',
        headers={**_auth(), "Idempotency-Key": "running-key-0001", "Content-Type": "application/json"},
    )
    assert answer.status_code == 409
    assert answer.headers.get("idempotency-status") == "in-progress"
    assert records == []


def test_a_slow_first_request_is_not_overtaken_before_15_minutes():
    store = MemoryStore()
    app, records = _app(store)
    client = TestClient(app)
    body = b'{"name":"Ajrakh"}'
    store.rows[("user_1", "slow-key-000001")] = StoredAnswer(
        idempotency._fingerprint("POST", "/api/crafts", b"", body), None, None, None,
        datetime.now(UTC) - timedelta(minutes=10),
    )
    answer = client.post(
        "/api/crafts", content=body,
        headers={**_auth(), "Idempotency-Key": "slow-key-000001", "Content-Type": "application/json"},
    )
    assert answer.status_code == 409
    assert records == []


def test_routes_outside_the_create_list_are_never_stored():
    store = MemoryStore()
    app, records = _app(store)
    client = TestClient(app)
    headers = {**_auth(), "Idempotency-Key": "login-key-000001"}
    first = client.post("/api/auth/login", headers=headers)
    second = client.post("/api/auth/login", headers=headers)
    assert first.json() != second.json()
    assert "idempotent-replayed" not in second.headers
    assert store.rows == {}
    for path in ("/api/auth/login", "/api/datasets/token", "/api/secrets/OPENAI", "/api/me/ai-keys", "/api/two-factor/enable"):
        assert not idempotency.is_create_route(path)
    for path in ("/api/crafts", "/api/crafts/", "/api/questionnaire/interviews", "/api/data-access/comments",
                 "/api/questionnaires", "/api/questionnaires/q1/reuse", "/api/questionnaires/bundles/import"):
        assert idempotency.is_create_route(path)
    for path in ("/api/questionnaires/q1/upload", "/api/questionnaires/q1/restore", "/api/questionnaires/upload"):
        assert not idempotency.is_create_route(path)


def test_a_first_request_killed_mid_way_is_taken_over():
    store = MemoryStore()
    app, records = _app(store)
    client = TestClient(app)
    body = b'{"name":"Ajrakh"}'
    store.rows[("user_1", "stale-key-00001")] = StoredAnswer(
        idempotency._fingerprint("POST", "/api/crafts", b"", body), None, None, None,
        datetime.now(UTC) - timedelta(minutes=20),
    )
    answer = client.post(
        "/api/crafts", content=body,
        headers={**_auth(), "Idempotency-Key": "stale-key-00001", "Content-Type": "application/json"},
    )
    assert answer.status_code == 201
    assert len(records) == 1


def test_malformed_keys_and_anonymous_callers():
    app, records = _app(MemoryStore())
    client = TestClient(app)
    bad = client.post("/api/crafts", json={"name": "x"}, headers={**_auth(), "Idempotency-Key": "short"})
    assert bad.status_code == 422
    # No session: passed through untouched (a real route would answer 401 itself).
    anon = client.post("/api/crafts", json={"name": "x"}, headers={"Idempotency-Key": "anon-key-00001"})
    assert anon.status_code == 201
    assert len(records) == 1


def test_the_real_app_installs_it():
    from app.main import create_app

    app = create_app()
    assert any(m.cls is IdempotencyMiddleware for m in app.user_middleware)


class _Table:
    """A recording stand-in for ``db.idempotencykey``."""

    def __init__(self) -> None:
        self.rows: dict[tuple[str, str], SimpleNamespace] = {}

    async def find_unique(self, where):
        k = where["userId_key"]
        return self.rows.get((k["userId"], k["key"]))

    async def create(self, data):
        pair = (data["userId"], data["key"])
        if pair in self.rows:
            raise UniqueViolationError({"user_facing_error": {"message": "unique"}})
        self.rows[pair] = SimpleNamespace(statusCode=None, responseBody=None, contentType=None, **data)

    async def update(self, where, data):
        k = where["userId_key"]
        row = self.rows[(k["userId"], k["key"])]
        for name, value in data.items():
            setattr(row, name, value)

    async def update_many(self, where, data):
        changed = 0
        for (user, key), row in self.rows.items():
            if (user, key) != (where["userId"], where["key"]):
                continue
            if row.statusCode != where["statusCode"] or row.createdAt != where["createdAt"]:
                continue
            for name, value in data.items():
                setattr(row, name, value)
            changed += 1
        return changed

    async def delete_many(self, where):
        if "createdAt" in where:
            cutoff = where["createdAt"]["lt"]
            for pair in [p for p, r in self.rows.items() if r.createdAt < cutoff]:
                del self.rows[pair]
        else:
            self.rows.pop((where["userId"], where["key"]), None)


def test_the_prisma_store_round_trip_and_30_day_expiry():
    table = _Table()
    store = PrismaIdempotencyStore(SimpleNamespace(idempotencykey=table))

    async def scenario():
        assert await store.claim("u", "k-000001", "fp") is True
        assert await store.claim("u", "k-000001", "fp") is False
        await store.complete("u", "k-000001", 201, b'{"id":"c1"}', "application/json")
        stored = await store.get("u", "k-000001")
        assert stored.status_code == 201 and stored.body == b'{"id":"c1"}'
        table.rows[("u", "k-000001")].createdAt = datetime.now(UTC) - timedelta(days=29)
        assert (await store.get("u", "k-000001")).status_code == 201
        table.rows[("u", "k-000001")].createdAt = datetime.now(UTC) - timedelta(days=31)
        assert await store.get("u", "k-000001") is None
        assert table.rows == {}

    asyncio.run(scenario())


def test_two_repeats_racing_for_one_stale_marker_cannot_both_take_it():
    table = _Table()
    store = PrismaIdempotencyStore(SimpleNamespace(idempotencykey=table))

    async def scenario():
        assert await store.claim("u", "race-key-01", "fp") is True
        stale = datetime.now(UTC) - timedelta(minutes=20)
        table.rows[("u", "race-key-01")].createdAt = stale
        first = await store.take_over("u", "race-key-01", "fp", stale)
        second = await store.take_over("u", "race-key-01", "fp", stale)
        assert (first, second) == (True, False)
        # An answered key is never taken over, however old its marker looks.
        await store.complete("u", "race-key-01", 201, b"{}", "application/json")
        answered_at = table.rows[("u", "race-key-01")].createdAt
        assert await store.take_over("u", "race-key-01", "fp", answered_at) is False

    asyncio.run(scenario())


def test_end_to_end_a_lost_answer_replayed_from_the_outbox_files_one_record_with_its_media():
    """The outbox path end to end: the online create lands but its answer is lost, the handset
    replays the SAME bytes under the SAME key, gets the first record back, and attaches its photos
    to it. One record, with its media, and no duplicate."""
    store = MemoryStore()
    records: dict[str, dict] = {}
    media: list[dict] = []
    ids = itertools.count(1)
    app = FastAPI()

    @app.post("/api/crafts", status_code=201)
    async def create_craft(request: Request):
        body = await request.json()
        record = {"id": f"craft_{next(ids)}", "name": body["name"], "recordedAt": body["recordedAt"]}
        records[record["id"]] = record
        return record

    @app.post("/api/media/complete", status_code=201)
    async def complete_media(request: Request):
        body = await request.json()
        assert body["linkedRecordId"] in records, "media must attach to a record that exists"
        media.append(body)
        return {"id": f"media_{len(media)}"}

    app.add_middleware(IdempotencyMiddleware, store=store)
    client = TestClient(app)
    headers = {**_auth(), "Idempotency-Key": "outbox-key-000001", "Content-Type": "application/json"}
    wire = b'{"name":"Ajrakh","recordedAt":"2026-10-17T08:59:12.345Z"}'

    client.post("/api/crafts", content=wire, headers=headers)  # lands; the answer never reaches the phone
    replay = client.post("/api/crafts", content=wire, headers=headers)
    assert replay.status_code == 201
    assert replay.headers.get("idempotent-replayed") == "true"
    record_id = replay.json()["id"]
    attached = client.post(
        "/api/media/complete",
        json={"linkedRecordType": "craft", "linkedRecordId": record_id, "objectKey": "media/u/a.jpg"},
        headers=_auth(),
    )
    assert attached.status_code == 201
    assert list(records) == [record_id]
    assert [m["linkedRecordId"] for m in media] == [record_id]


def test_a_replay_whose_bytes_drifted_is_flagged_as_a_key_reuse_and_writes_nothing():
    """What the handset must never do (a fresh recordedAt on the replay), and the signal it gets if it
    does: a 422 the outbox can tell apart from a refusal of the record, so it parks the entry for
    review instead of dropping its photos."""
    store = MemoryStore()
    app, records = _app(store)
    client = TestClient(app)
    headers = {**_auth(), "Idempotency-Key": "drifted-key-0001", "Content-Type": "application/json"}
    client.post("/api/crafts", content=b'{"name":"Ajrakh","at":"09:00:00"}', headers=headers)
    drifted = client.post("/api/crafts", content=b'{"name":"Ajrakh","at":"09:00:07"}', headers=headers)
    assert drifted.status_code == 422
    assert drifted.headers.get("idempotency-status") == "key-reused"
    assert drifted.json()["code"] == "IDEMPOTENCY_KEY_REUSED"
    assert len(records) == 1
