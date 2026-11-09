from __future__ import annotations

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, run, seed, session_create

pytestmark = needs_db


def test_a_session_place_is_pinned_on_start_read_back_and_moved_or_cleared_on_edit(monkeypatch) -> None:
    from app.questionnaire import schemas as S, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        try:
            ids = await seed(client)
            pin = {"latitude": 26.8105, "longitude": 75.5466, "placeName": "Bagru, Jaipur, Rajasthan"}
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], place="Bagru, Jaipur", location=pin), ids.lead)
            assert {k: s["location"][k] for k in ("latitude", "longitude", "accuracy", "placeName")} == {**pin, "accuracy": None}
            moved = await sessions.patch_session(
                s["id"], S.SessionPatch(version=s["version"], place="Sanganer, Jaipur", location=S.SessionLocation(latitude=26.8213, longitude=75.7849, placeName="Sanganer")), ids.lead
            )
            assert moved["place"] == "Sanganer, Jaipur"
            assert {k: moved["location"][k] for k in ("latitude", "longitude", "accuracy", "placeName")} == {"latitude": 26.8213, "longitude": 75.7849, "accuracy": None, "placeName": "Sanganer"}
            kept = await sessions.patch_session(s["id"], S.SessionPatch(version=moved["version"], note="Second sitting"), ids.lead)
            assert kept["location"]["latitude"] == moved["location"]["latitude"]
            cleared = await sessions.patch_session(s["id"], S.SessionPatch(version=kept["version"], location=None), ids.lead)
            assert cleared["location"] is None and cleared["place"] == "Sanganer, Jaipur"
            again = await sessions.patch_session(s["id"], S.SessionPatch(version=cleared["version"], location=S.SessionLocation(latitude=26.8, longitude=75.5)), ids.lead)
            assert again["location"]["latitude"] == 26.8
            dropped = await sessions.patch_session(s["id"], S.SessionPatch(version=again["version"], clearLocation=True), ids.lead)
            assert dropped["location"] is None
        finally:
            await client.disconnect()

    run(go())


def test_a_session_carries_its_artisans_crafts_and_keeps_a_craft_set_by_hand(monkeypatch) -> None:
    from app.questionnaire import schemas as S, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        try:
            ids = await seed(client)
            other = f"{ids.craft}-dokra"
            await client.execute_raw('INSERT INTO "Craft" ("id", "name", "createdById", "updatedAt") VALUES ($1, $2, $3, now())', other, f"Dokra {other}", ids.admin.id)
            await client.execute_raw('UPDATE "Artisan" SET "craftId" = $1 WHERE "id" = $2', other, ids.artisans[1])
            block = (await client.query_raw('SELECT "name" FROM "Craft" WHERE "id" = $1', ids.craft))[0]["name"]
            dokra = f"Dokra {other}"
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
            assert sorted(s["crafts"]) == sorted([block, dokra])
            assert s["craftsByHand"] == []
            hand = await sessions.patch_session(s["id"], S.SessionPatch(version=s["version"], crafts=[block, dokra, "Weaving"], craftsByHand=["Weaving"]), ids.lead)
            assert sorted(hand["crafts"]) == sorted([block, dokra, "Weaving"])
            gone = await sessions.remove_participant(s["id"], ids.artisans[1], False, ids.lead)
            assert sorted(gone["crafts"]) == sorted([block, "Weaving"])
            back = await sessions.add_participant(s["id"], ids.artisans[1], None, ids.lead)
            assert sorted(back["crafts"]) == sorted([block, dokra, "Weaving"])
            chosen, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], crafts=["Only this"], craftsByHand=["Only this"]), ids.lead)
            assert chosen["crafts"] == ["Only this"]
        finally:
            await client.disconnect()

    run(go())
