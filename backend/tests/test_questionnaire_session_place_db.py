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
            assert s["location"] == {**pin, "accuracy": None}
            moved = await sessions.patch_session(
                s["id"], S.SessionPatch(version=s["version"], place="Sanganer, Jaipur", location=S.SessionLocation(latitude=26.8213, longitude=75.7849, placeName="Sanganer")), ids.lead
            )
            assert moved["place"] == "Sanganer, Jaipur"
            assert moved["location"] == {"latitude": 26.8213, "longitude": 75.7849, "accuracy": None, "placeName": "Sanganer"}
            kept = await sessions.patch_session(s["id"], S.SessionPatch(version=moved["version"], note="Second sitting"), ids.lead)
            assert kept["location"] == moved["location"]
            cleared = await sessions.patch_session(s["id"], S.SessionPatch(version=kept["version"], location=None), ids.lead)
            assert cleared["location"] is None and cleared["place"] == "Sanganer, Jaipur"
            again = await sessions.patch_session(s["id"], S.SessionPatch(version=cleared["version"], location=S.SessionLocation(latitude=26.8, longitude=75.5)), ids.lead)
            assert again["location"]["latitude"] == 26.8
            dropped = await sessions.patch_session(s["id"], S.SessionPatch(version=again["version"], clearLocation=True), ids.lead)
            assert dropped["location"] is None
        finally:
            await client.disconnect()

    run(go())
