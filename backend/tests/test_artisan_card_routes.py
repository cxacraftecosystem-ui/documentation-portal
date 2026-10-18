"""The artisan-card routes: lazy issue, reissue, resolve, the miss budget, the PDFs and the backfill.

The routes are called as functions against ``tests/artisan_card_fakes.FakeDB``, which keeps the
two constraints the card code relies on (unique code, one live card per artisan). The migration's
partial index is checked against the SQL itself; ``test_artisan_card_db.py`` runs the real thing
against a throwaway Postgres when one is configured.
"""

from __future__ import annotations

import asyncio
import io
import json
import math
import pathlib
import re
from types import SimpleNamespace

import pytest
from artisan_card_fakes import FakeDB
from fastapi import HTTPException
from pypdf import PdfReader

from app.api.routes import artisan_cards as routes
from app.api.routes import artisans as artisan_routes
from app.services import artisan_card
from app.services import artisan_card_store as store

pytestmark = pytest.mark.anyio

ROOT = pathlib.Path(__file__).resolve().parents[2]
KEY = json.loads((ROOT / "shared" / "artisan-card-vectors.json").read_text(encoding="utf-8"))[
    "tags"
]["key"]
MIGRATION = (
    ROOT / "backend" / "prisma" / "migrations" / "20261023090000_artisan_cards" / "migration.sql"
)
MM = 72 / 25.4


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _settings(**over):
    base = {
        "artisan_cards_enabled": True,
        "artisan_card_key": KEY,
        "artisan_card_key_version": 1,
        "artisan_card_key_previous": None,
        "artisan_card_issuer": "F",
        "artisan_card_resolve_miss_limit": 20,
        "cross_portal_identity_enabled": True,
        "cross_portal_token": "t" * 40,
        "aadhaar_fingerprint_key": "k" * 40,
    }
    base.update(over)
    return SimpleNamespace(**base)


USER = SimpleNamespace(id="u1", role="RESEARCHER")
OTHER = SimpleNamespace(id="u2", role="RESEARCHER")


def _artisan(i: int, **over):
    row = {
        "id": f"a{i}",
        "name": f"Artisan {i}",
        "localName": None,
        "place": "Bhuj",
        "aadhaarNumber": None,
        "aadhaarFingerprint": None,
        "aadhaarLast4": "9012",
        "status": "APPROVED",
        "craftId": "c1",
        "locationId": None,
        "workshopId": None,
        "workshops": [],
        "createdById": "u1",
    }
    row.update(over)
    return row


@pytest.fixture
def world(monkeypatch):
    fake = FakeDB(
        artisan=[
            _artisan(1, locationId="l1"),
            _artisan(2, aadhaarLast4=None, workshopId="w1"),
            *[_artisan(i, workshopId="w2") for i in range(3, 15)],
        ],
        craft=[{"id": "c1", "name": "Bandhej"}],
        location=[{"id": "l1", "village": "Nirona", "district": "Kachchh"}],
        workshop=[{"id": "w1"}, {"id": "w2"}],
    )
    state = {"settings": _settings(), "edit": True}
    monkeypatch.setattr(routes, "db", fake)
    monkeypatch.setattr(routes, "get_settings", lambda: state["settings"])
    monkeypatch.setattr(artisan_routes, "db", fake)
    monkeypatch.setattr(artisan_routes, "get_settings", lambda: state["settings"])

    async def edit_privilege(record, user, record_type):
        return state["edit"]

    async def owners(viewer):
        return None

    monkeypatch.setattr(routes, "record_edit_privilege", edit_privilege)
    monkeypatch.setattr(routes, "media_url_owners", owners)
    return SimpleNamespace(db=fake, state=state)


def _events(fake, action):
    return [e for e in fake.artisancardevent.rows if e["action"] == action]


async def _resolve(text, user=USER):
    return await routes.resolve_card(routes.ResolveIn(input=text, source="WEB"), current_user=user)


# ── Issue ────────────────────────────────────────────────────────────────────────────────────────


async def test_lazy_issue_happens_once_even_under_concurrent_reads(world):
    answers = await asyncio.gather(*(routes.get_card("a1", current_user=USER) for _ in range(8)))
    codes = {a["code"] for a in answers}
    assert len(codes) == 1
    live = [
        c for c in world.db.artisancard.rows if c["artisanId"] == "a1" and c["revokedAt"] is None
    ]
    assert len(live) == 1
    assert len(_events(world.db, "ISSUE")) == 1


async def test_card_payload_face_and_codes(world):
    card = await routes.get_card("a1", current_user=USER)
    assert card["shortCode"] == artisan_card.short_code("F", card["code"])
    assert card["token"] == artisan_card.payload(KEY, "F", 1, card["code"])
    assert card["face"] == {
        "name": "Artisan 1",
        "localName": None,
        "craft": "Bandhej",
        "cluster": "Nirona",
        "aadhaarLast4": "9012",
        "photoMediaId": None,
    }
    assert card["qrSvg"].startswith("<svg")
    assert card["replacedCards"] == 0
    assert "a1" not in card["token"] and "a1" not in card["qrSvg"]


async def test_cluster_falls_back_to_district_then_place(world):
    assert (
        store.cluster_of({"location": {"village": None, "district": "Kachchh"}, "place": "x"})
        == "Kachchh"
    )
    assert store.cluster_of({"location": None, "place": "Bhuj"}) == "Bhuj"


async def test_unknown_artisan_is_404(world):
    with pytest.raises(HTTPException) as caught:
        await routes.get_card("nope", current_user=USER)
    assert caught.value.status_code == 404


async def test_routes_are_404_and_status_disabled_when_inactive(world):
    world.state["settings"] = _settings(artisan_card_key=None)
    assert await routes.card_status(USER) == {"enabled": False, "issuer": None}
    for call in (
        routes.get_card("a1", current_user=USER),
        routes.card_pdf("a1", current_user=USER),
        _resolve("F-01234-56789-S5"),
        routes.reissue_card("a1", routes.ReissueIn(reason="LOST"), current_user=USER),
        routes.sheet_pdf(routes.SheetIn(artisanIds=["a1"]), current_user=USER),
    ):
        with pytest.raises(HTTPException) as caught:
            await call
        assert caught.value.status_code == 404
    world.state["settings"] = _settings()
    assert await routes.card_status(USER) == {"enabled": True, "issuer": "F"}


# ── Reissue and resolve ──────────────────────────────────────────────────────────────────────────


async def test_reissue_revokes_and_the_old_code_answers_replaced(world):
    first = await routes.get_card("a1", current_user=USER)
    second = await routes.reissue_card("a1", routes.ReissueIn(reason="LOST"), current_user=USER)
    assert second["code"] != first["code"]
    assert second["replacedCards"] == 1
    old = next(c for c in world.db.artisancard.rows if c["code"] == first["code"])
    assert old["revokedReason"] == "LOST" and old["revokedById"] == "u1"
    assert await _resolve(first["shortCode"]) == {"status": "REPLACED", "artisanId": "a1"}
    found = await _resolve(second["token"])
    assert found["status"] == "FOUND" and found["via"] == "LOCAL"
    assert found["artisan"] == {
        "id": "a1",
        "name": "Artisan 1",
        "craft": "Bandhej",
        "cluster": "Nirona",
        "place": "Bhuj",
        "aadhaarLast4": "9012",
    }
    assert len(_events(world.db, "REISSUE")) == 1


async def test_reissue_needs_edit_rights(world):
    world.state["edit"] = False
    with pytest.raises(HTTPException) as caught:
        await routes.reissue_card("a1", routes.ReissueIn(reason="DAMAGED"), current_user=OTHER)
    assert caught.value.status_code == 403


async def test_resolve_bad_check_is_422_bad_tag_and_unknown_are_404(world):
    card = await routes.get_card("a1", current_user=USER)
    short = card["shortCode"]
    wrong = short[:-1] + ("0" if short[-1] != "0" else "1")
    with pytest.raises(HTTPException) as caught:
        await _resolve(wrong)
    assert caught.value.status_code == 422
    assert caught.value.detail == "Check the code. One character is wrong."
    forged = card["token"][:-1] + ("0" if card["token"][-1] != "0" else "1")
    with pytest.raises(HTTPException) as caught:
        await _resolve(forged)
    assert caught.value.status_code == 404
    assert caught.value.detail == "No artisan matches this card."
    with pytest.raises(HTTPException) as caught:
        await _resolve(artisan_card.short_code("F", "ZZZZZZZZZZ"))
    assert caught.value.detail == "No artisan matches this card."
    assert len(_events(world.db, "RESOLVE_MISS")) == 2


async def test_resolve_of_a_deleted_artisan_is_404(world):
    card = await routes.get_card("a1", current_user=USER)
    world.db.artisan.rows = [a for a in world.db.artisan.rows if a["id"] != "a1"]
    with pytest.raises(HTTPException) as caught:
        await _resolve(card["shortCode"])
    assert caught.value.status_code == 404


async def test_miss_limit_is_429_and_hits_never_count(world):
    world.state["settings"] = _settings(artisan_card_resolve_miss_limit=3)
    card = await routes.get_card("a1", current_user=USER)
    for _ in range(10):
        assert (await _resolve(card["shortCode"]))["status"] == "FOUND"
    for _ in range(3):
        with pytest.raises(HTTPException) as caught:
            await _resolve(artisan_card.short_code("F", "ZZZZZZZZZZ"))
        assert caught.value.status_code == 404
    with pytest.raises(HTTPException) as caught:
        await _resolve(card["shortCode"])
    assert caught.value.status_code == 429
    # Another account's budget is its own.
    assert (await _resolve(card["shortCode"], user=OTHER))["status"] == "FOUND"


async def test_designer_card_resolves_through_the_link_and_is_refused_unlinked(world):
    designer_code = "7K3QM9XA2F"
    with pytest.raises(HTTPException) as caught:
        await _resolve(artisan_card.short_code("D", designer_code))
    assert caught.value.status_code == 404
    assert caught.value.detail == (
        "This card is from the Design Prototype Workshop and this artisan isn't linked here."
    )
    world.db.crossportalartisanlink.rows.append(
        {
            "id": "l1",
            "artisanId": "a2",
            "remoteArtisanId": "dp1",
            "matchedBy": "AADHAAR",
            "remoteCardCode": designer_code,
        }
    )
    found = await _resolve(artisan_card.short_code("D", designer_code))
    assert found["status"] == "FOUND" and found["via"] == "LINK"
    assert found["artisan"]["id"] == "a2"
    # A QR from the designer portal carries a tag this server cannot check; the link is the proof.
    found = await _resolve(f"CXA1:D1:{designer_code}:{'0' * 16}")
    assert found["artisan"]["id"] == "a2"
    # With the cross-portal channel off, the same card is the unlinked answer.
    world.state["settings"] = _settings(cross_portal_identity_enabled=False)
    with pytest.raises(HTTPException) as caught:
        await _resolve(artisan_card.short_code("D", designer_code))
    assert caught.value.status_code == 404


# ── PDFs ─────────────────────────────────────────────────────────────────────────────────────────


def _text(reader: PdfReader) -> str:
    return "\n".join(page.extract_text() or "" for page in reader.pages)


def _size_mm(page) -> tuple[float, float]:
    box = page.mediabox
    return round(float(box.width) / MM, 1), round(float(box.height) / MM, 1)


async def test_single_card_pdf_is_id1_and_masks_aadhaar(world):
    response = await routes.card_pdf("a1", size="id1", photo=0, current_user=USER)
    assert response.media_type == "application/pdf"
    reader = PdfReader(io.BytesIO(response.body))
    assert len(reader.pages) == 1
    assert _size_mm(reader.pages[0]) == (85.6, 54.0)
    text = _text(reader)
    assert "XXXX XXXX 9012" in text
    assert not re.search(r"\d{12}", text)
    card = await routes.get_card("a1", current_user=USER)
    assert card["shortCode"] in text
    disposition = response.headers["Content-Disposition"]
    assert re.search(r'filename="FieldRepository_Artisan_\d{4}-\d{2}-\d{2}_[A-Za-z0-9_-]*Artisan-1_Artisan-Card\.pdf"', disposition)
    assert len(_events(world.db, "PRINT")) == 1


async def test_a6_pdf_and_no_aadhaar_line_without_last4(world):
    response = await routes.card_pdf("a2", size="a6", photo=0, current_user=USER)
    reader = PdfReader(io.BytesIO(response.body))
    assert _size_mm(reader.pages[0]) == (105.0, 148.0)
    assert "Aadhaar" not in _text(reader)


async def test_photo_is_printed_when_present(world, monkeypatch):
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (1200, 1600), (180, 120, 90)).save(buffer, format="JPEG")
    world.db.mediafile.rows.append(
        {
            "id": "m1",
            "artisanId": "a1",
            "mediaType": "IMAGE",
            "uploadedById": "u1",
            "objectKey": "k/1.jpg",
            "sizeBytes": len(buffer.getvalue()),
            "createdAt": 1,
        }
    )
    from app.services import s3

    monkeypatch.setattr(s3, "get_object_bytes", lambda key: buffer.getvalue())
    card = await routes.get_card("a1", current_user=USER)
    assert card["face"]["photoMediaId"] == "m1"
    with_photo = await routes.card_pdf("a1", size="id1", photo=1, current_user=USER)
    without = await routes.card_pdf("a1", size="id1", photo=0, current_user=USER)
    assert _image_count(with_photo.body) == 1
    assert _image_count(without.body) == 0


def _image_count(pdf: bytes) -> int:
    """Image XObjects placed on the first page: the photo is the only raster a card draws."""
    resources = PdfReader(io.BytesIO(pdf)).pages[0].get("/Resources") or {}
    xobjects = resources.get("/XObject") or {}
    return sum(1 for ref in xobjects.values() if ref.get_object().get("/Subtype") == "/Image")


async def test_sheet_for_a_workshop_is_a4_with_ceil_n_over_ten_pages(world):
    response = await routes.sheet_pdf(
        routes.SheetIn(workshopId="w2", size="id1", photo=False), USER
    )
    reader = PdfReader(io.BytesIO(response.body))
    assert len(reader.pages) == math.ceil(12 / 10)
    assert _size_mm(reader.pages[0]) == (210.0, 297.0)
    assert not re.search(r"\d{12}", _text(reader))
    # Every artisan got a card, issued together, and one PRINT row counts them.
    issued = {c["artisanId"] for c in world.db.artisancard.rows}
    assert {f"a{i}" for i in range(3, 15)} <= issued
    prints = _events(world.db, "PRINT")
    assert prints[-1]["count"] == 12


async def test_sheet_a6_is_one_card_per_page(world):
    response = await routes.sheet_pdf(
        routes.SheetIn(artisanIds=["a1", "a2", "a3"], size="a6", photo=False), USER
    )
    reader = PdfReader(io.BytesIO(response.body))
    assert len(reader.pages) == 3
    assert _size_mm(reader.pages[0]) == (105.0, 148.0)


async def test_sheet_refuses_the_whole_request_when_any_artisan_is_unknown(world):
    with pytest.raises(HTTPException) as caught:
        await routes.sheet_pdf(routes.SheetIn(artisanIds=["a1", "ghost", "ghost2"]), USER)
    assert caught.value.status_code == 422
    assert caught.value.detail == "2 of these artisans can't be printed by you."
    assert world.db.artisancard.rows == []
    assert _events(world.db, "PRINT") == []


async def test_sheet_needs_exactly_one_source(world):
    for payload in (
        routes.SheetIn(),
        routes.SheetIn(artisanIds=["a1"], workshopId="w1"),
        routes.SheetIn(designWorkshopId="dw1"),
    ):
        with pytest.raises(HTTPException) as caught:
            await routes.sheet_pdf(payload, USER)
        assert caught.value.status_code == 422


def test_sheet_caps_at_two_hundred():
    with pytest.raises(ValueError):
        routes.SheetIn(artisanIds=[f"a{i}" for i in range(201)])


# ── The register and the backfill ────────────────────────────────────────────────────────────────


async def test_register_rows_carry_card_last4_and_linked_codes(world, monkeypatch):
    world.db.crossportalartisanlink.rows.append(
        {
            "id": "l1",
            "artisanId": "a1",
            "remoteArtisanId": "dp1",
            "matchedBy": "AADHAAR",
            "remoteCardCode": "7K3QM9XA2F",
        }
    )
    rows = [{"id": "a1", "name": "Artisan 1", "aadhaarLast4": "9012"}, {"id": "a2", "name": "B"}]
    await artisan_routes._attach_cards(rows, USER)
    assert rows[0]["card"]["shortCode"] == artisan_card.short_code("F", rows[0]["card"]["code"])
    assert rows[0]["card"]["token"].startswith("CXA1:F1:")
    assert rows[0]["linkedCardCodes"] == ["7K3QM9XA2F"]
    assert rows[1]["linkedCardCodes"] == [] and rows[1]["aadhaarLast4"] is None
    world.state["settings"] = _settings(artisan_cards_enabled=False)
    plain = [{"id": "a1"}]
    await artisan_routes._attach_cards(plain, USER)
    assert plain == [{"id": "a1"}]


async def test_a_card_failure_never_fails_the_register_or_a_create(world, monkeypatch):
    async def broken(*args, **kwargs):
        raise RuntimeError("ArtisanCard table missing")

    monkeypatch.setattr(store, "ensure_cards", broken)
    monkeypatch.setattr(store, "ensure_card", broken)
    rows = [{"id": "a1", "name": "Artisan 1"}]
    await artisan_routes._attach_cards(rows, USER)
    assert rows == [{"id": "a1", "name": "Artisan 1"}]
    from app.api.routes import cross_portal as peer_routes

    assert await peer_routes._card_code("a1", world.state["settings"]) is None


async def test_backfill_is_idempotent(world):
    from scripts import backfill_artisan_cards as backfill

    dry = await backfill.run(world.db, world.state["settings"], apply=False, batch=5)
    assert dry.would_issue == 14 and world.db.artisancard.rows == []
    first = await backfill.run(world.db, world.state["settings"], apply=True, batch=5)
    assert first.issued == 14 and first.missing == 0
    second = await backfill.run(world.db, world.state["settings"], apply=True, batch=5)
    assert second.issued == 0 and second.had_card == 14
    assert len(world.db.artisancard.rows) == 14


def test_migration_holds_one_live_card_per_artisan_and_is_additive():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert re.search(
        r'CREATE UNIQUE INDEX IF NOT EXISTS "ArtisanCard_one_live_per_artisan"\s+ON "ArtisanCard"\("artisanId"\) WHERE "revokedAt" IS NULL;',
        sql,
    )
    assert 'ADD COLUMN IF NOT EXISTS "remoteCardCode" TEXT;' in sql
    for destructive in ("DROP TABLE", "DROP COLUMN", "DELETE FROM", "TRUNCATE", "ALTER COLUMN"):
        assert destructive not in sql.upper()
