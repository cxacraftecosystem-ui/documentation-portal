"""The duplicate finder's API: the form's check, the hold at create, re-scoring, and decisions.

The rate limit and the wire shape run everywhere (the scorer is replaced). Everything that needs
the pair store runs against a throwaway Postgres (see ``tests/_duplicates_db.py``).
"""

from __future__ import annotations

import json
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.api.routes import artisan_duplicates as routes
from app.api.routes import artisans as artisan_routes
from app.schemas.duplicates import DuplicateCheckIn
from app.schemas.records import ArtisanCreate
from app.services import artisan_duplicates as dup
import _duplicates_db as dbt

pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


# ── Everywhere ────────────────────────────────────────────────────────────────────────────────


async def test_check_is_rate_limited_per_user(monkeypatch):
    async def no_candidates(*_a, **_k):
        return []

    async def no_craft(*_a, **_k):
        return None, None

    monkeypatch.setattr(dup, "candidates_for_profile", no_candidates)
    monkeypatch.setattr(routes, "_craft_for", no_craft)
    routes.reset_check_budget()
    user = SimpleNamespace(id="u-rate", role="RESEARCHER")
    body = DuplicateCheckIn(name="Giriraj Prasad")
    for _ in range(routes.CHECKS_PER_MINUTE):
        assert await routes.check_duplicates(body, current_user=user) == {"candidates": []}
    with pytest.raises(HTTPException) as caught:
        await routes.check_duplicates(body, current_user=user)
    assert caught.value.status_code == 429
    assert "Retry-After" in caught.value.headers
    # Another user's budget is their own.
    assert await routes.check_duplicates(body, current_user=SimpleNamespace(id="u-other")) == {"candidates": []}
    routes.reset_check_budget()


def test_the_check_body_refuses_unknown_keys_and_takes_the_aadhaar_in_the_body_only():
    with pytest.raises(Exception):
        DuplicateCheckIn(name="A", aadhaarNumber="234123412346")
    assert DuplicateCheckIn(name="A", aadhaar="2341 2341 2346").aadhaar


def test_create_accepts_at_most_twenty_acknowledged_ids():
    body = dbt.payload("Giriraj Prasad", craftName="Ajrakh", ack=[f"id{i}" for i in range(21)])
    with pytest.raises(Exception):
        ArtisanCreate(**body)
    body["duplicateAcknowledged"] = [f"id{i}" for i in range(20)]
    assert len(ArtisanCreate(**body).duplicateAcknowledged) == 20
    body.pop("duplicateAcknowledged")
    assert ArtisanCreate(**body).duplicateAcknowledged is None


# ── Against a real database ───────────────────────────────────────────────────────────────────


@pytest.fixture(scope="module")
async def conn():
    if not dbt.DB_TESTS:
        pytest.skip("needs ARTISAN_DUPLICATE_DB_TESTS=1 and a throwaway DATABASE_URL")
    db = await dbt.connect()
    yield db
    await dbt.disconnect()


@pytest.fixture
async def world(conn):
    admin = await dbt.make_user(conn, "ADMIN")
    craft = await conn.craft.create(data={"name": f"Craft {dbt.word()}", "createdById": admin.id})
    routes.reset_check_budget()
    return SimpleNamespace(db=conn, admin=admin, craft=craft)


async def _pairs_of(db, artisan_id):
    return await db.artisanduplicatecandidate.find_many(
        where={"OR": [{"artisanAId": artisan_id}, {"artisanBId": artisan_id}]}
    )


@dbt.skip_without_db
async def test_check_caps_at_five_and_returns_no_identifier(world):
    name = dbt.person()
    for _ in range(7):
        await dbt.make_artisan(
            world.db, world.admin, name=name, phone="9825012345", dateOfBirth="1971-04-02",
            craftId=world.craft.id,
        )
    body = DuplicateCheckIn(name=name, state="Gujarat", district="Kachchh", phone="98250 12345")
    out = await routes.check_duplicates(body, current_user=world.admin)
    candidates = out["candidates"]
    assert len(candidates) == dup.CHECK_MAX_CANDIDATES
    assert all(c["score"] >= dup.SHOW_THRESHOLD for c in candidates)
    text = json.dumps(out, default=str)
    assert "9825012345" not in text and "XXXX" not in text and "dateOfBirth" not in text
    assert candidates[0]["artisan"]["birthYear"] == 1971
    assert set(candidates[0]["artisan"]) >= {"id", "name", "place", "state", "district", "craft", "createdAt"}


@dbt.skip_without_db
async def test_check_matches_a_typed_aadhaar_and_names_it_only_then(world):
    number = dbt.valid_aadhaar()
    stored = await dbt.make_artisan(world.db, world.admin, aadhaar=number)
    typed = await routes.check_duplicates(
        DuplicateCheckIn(name=dbt.person(), aadhaar=number), current_user=world.admin
    )
    assert typed["candidates"][0]["artisan"]["id"] == stored.id
    assert typed["candidates"][0]["score"] == 1.0
    assert "AADHAAR" in [r["code"] for r in typed["candidates"][0]["reasons"]]
    assert number not in json.dumps(typed) and number[-4:] not in json.dumps(typed)
    untyped = await routes.check_duplicates(
        DuplicateCheckIn(name=stored.name, state="Gujarat", district="Kachchh"), current_user=world.admin
    )
    for candidate in untyped["candidates"]:
        assert "AADHAAR" not in [r["code"] for r in candidate["reasons"]]


@dbt.skip_without_db
async def test_check_excludes_the_record_being_edited(world):
    name = dbt.person()
    a = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345")
    out = await routes.check_duplicates(
        DuplicateCheckIn(name=name, phone="9825012345", state="Gujarat", district="Kachchh", excludeArtisanId=a.id),
        current_user=world.admin,
    )
    assert a.id not in [c["artisan"]["id"] for c in out["candidates"]]


@dbt.skip_without_db
async def test_check_reads_through_the_record_read_rule(world, monkeypatch):
    from app.services import records

    name = dbt.person()
    rows = [await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345") for _ in range(3)]
    hidden = [rows[0].id, rows[1].id]

    async def narrowed(user, owner_field="createdById"):
        return {"id": {"not_in": hidden}}

    monkeypatch.setattr(records, "viewable_where", narrowed)
    out = await routes.check_duplicates(
        DuplicateCheckIn(name=name, phone="9825012345", state="Gujarat", district="Kachchh"), current_user=world.admin
    )
    assert [c["artisan"]["id"] for c in out["candidates"]] == [rows[2].id]


async def _create(world, body):
    return await artisan_routes.create_artisan(ArtisanCreate(**body), current_user=world.admin)


@dbt.skip_without_db
async def test_create_without_the_field_is_never_held_and_stores_the_pair(world):
    name = dbt.person()
    first = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345", craftId=world.craft.id)
    created = await _create(world, dbt.payload(name, craftId=world.craft.id, phone="9825012345"))
    pairs = await _pairs_of(world.db, created["id"])
    assert [p for p in pairs if first.id in (p.artisanAId, p.artisanBId)]
    pair = next(p for p in pairs if first.id in (p.artisanAId, p.artisanBId))
    assert str(pair.source) in ("CREATE", "DuplicateSource.CREATE")
    assert pair.creatorSaidDifferent is False


@dbt.skip_without_db
async def test_create_with_an_empty_list_is_held(world):
    name = dbt.person()
    first = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345", craftId=world.craft.id)
    before = await world.db.artisan.count(where={"name": name})
    with pytest.raises(HTTPException) as caught:
        await _create(world, dbt.payload(name, craftId=world.craft.id, phone="9825012345", ack=[]))
    assert caught.value.status_code == 409
    detail = caught.value.detail
    assert detail["code"] == "artisan_possible_duplicate"
    assert detail["existingArtisan"]["id"] == first.id
    assert detail["message"].startswith(f"This may be {name}")
    assert detail["candidates"][0]["score"] >= dup.HOLD_THRESHOLD
    assert "9825012345" not in json.dumps(detail, default=str)
    assert await world.db.artisan.count(where={"name": name}) == before


@dbt.skip_without_db
async def test_create_with_the_candidate_acknowledged_saves_and_remembers_it(world):
    name = dbt.person()
    first = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345", craftId=world.craft.id)
    created = await _create(world, dbt.payload(name, craftId=world.craft.id, phone="9825012345", ack=[first.id]))
    pair = next(p for p in await _pairs_of(world.db, created["id"]) if first.id in (p.artisanAId, p.artisanBId))
    assert pair.creatorSaidDifferent is True


@dbt.skip_without_db
async def test_a_below_hold_candidate_never_holds(world):
    name = dbt.person()
    await dbt.make_artisan(world.db, world.admin, name=name, place="Mandvi")  # name + district = 0.70
    created = await _create(world, dbt.payload(name, craftId=world.craft.id, place="Rapar", ack=[]))
    assert created["id"]


@dbt.skip_without_db
async def test_the_kill_switch_turns_the_hold_off(world, monkeypatch):
    name = dbt.person()
    await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345", craftId=world.craft.id)
    monkeypatch.setattr(artisan_routes, "get_settings", lambda: SimpleNamespace(artisan_duplicate_hold=False))
    created = await _create(world, dbt.payload(name, craftId=world.craft.id, phone="9825012345", ack=[]))
    assert created["id"]
    assert await _pairs_of(world.db, created["id"])


@dbt.skip_without_db
async def test_an_idempotent_replay_returns_the_stored_answer_and_does_not_hold(world):
    import httpx

    from app.core.security import create_access_token
    from app.main import app

    name = dbt.person()
    token = create_access_token(world.admin.id)
    body = dbt.payload(name, craftId=world.craft.id, phone="9825012345", ack=[])
    headers = {"Authorization": f"Bearer {token}", "Idempotency-Key": f"dupes-{dbt.word()}"}
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        first = await client.post("/api/artisans", json=body, headers=headers)
        assert first.status_code == 201, first.text
        # The same create again: by now the first record would hold it, but the replay never runs it.
        again = await client.post("/api/artisans", json=body, headers=headers)
    assert again.status_code == 201
    assert again.headers.get("Idempotent-Replayed") == "true"
    assert again.json()["id"] == first.json()["id"]
    assert await world.db.artisan.count(where={"name": name}) == 1


@dbt.skip_without_db
async def test_an_edit_rescores(world):
    from app.schemas.records import ArtisanUpdate

    name = dbt.person()
    a = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345", craftId=world.craft.id)
    b = await dbt.make_artisan(world.db, world.admin, name=dbt.person(), phone="9000000001", state="Rajasthan", district="Jaipur")
    assert not [p for p in await _pairs_of(world.db, b.id) if a.id in (p.artisanAId, p.artisanBId)]
    await artisan_routes.update_artisan(
        b.id, ArtisanUpdate(name=name, phone="9825012345", craftId=world.craft.id), current_user=world.admin
    )
    assert [p for p in await _pairs_of(world.db, b.id) if a.id in (p.artisanAId, p.artisanBId)]


@dbt.skip_without_db
async def test_a_dismissed_pair_stays_dismissed_through_a_rescan(world):
    name = dbt.person()
    a = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345")
    b = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345")
    await dup.rescore_artisan(b.id)
    pair = next(p for p in await _pairs_of(world.db, b.id) if a.id in (p.artisanAId, p.artisanBId))
    decided = await routes.dismiss_pair(pair.id, routes.DismissIn(note=None), current_user=world.admin)
    assert decided["status"] == "NOT_DUPLICATE"
    await world.db.execute_raw(
        'UPDATE "ArtisanDuplicateCandidate" SET "score" = 0.61 WHERE "id" = $1', pair.id
    )
    await dup.run_within_scan()
    await dup.rescore_artisan(a.id)
    after = await world.db.artisanduplicatecandidate.find_unique(where={"id": pair.id})
    assert str(after.status).endswith("NOT_DUPLICATE")
    assert after.score > 0.61  # the score is refreshed, the decision is not
    reopened = await routes.reopen_pair(pair.id, current_user=world.admin)
    assert reopened["status"] == "OPEN"


@dbt.skip_without_db
async def test_the_queue_lists_counts_and_sorts(world):
    name = dbt.person()
    a = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345")
    b = await dbt.make_artisan(world.db, world.admin, name=name, phone="9825012345")
    await dup.rescore_artisan(b.id)
    page = await routes.list_pairs(_=world.admin, status_filter="OPEN", kind="WITHIN", band=None,
                                   artisanId=a.id, page=1, pageSize=20)
    assert page["items"] and page["items"][0]["a"]["id"] in (a.id, b.id)
    assert set(page["counts"]) == {"VERY_LIKELY", "LIKELY", "POSSIBLE"}
    item = page["items"][0]
    assert item["remote"] is None and item["kind"] == "WITHIN"
    assert "9825012345" not in json.dumps(page, default=str)
    with pytest.raises(HTTPException):
        await routes.list_pairs(_=world.admin, status_filter="NOPE", kind=None, band=None,
                                artisanId=None, page=1, pageSize=20)
