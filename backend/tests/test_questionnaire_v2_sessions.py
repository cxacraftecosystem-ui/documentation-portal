"""Sessions are dated events (DESIGN 3.7, 3.12; tests 1, 2, 17 and 35a). Real Postgres."""

from __future__ import annotations

import pytest
from fastapi import HTTPException
from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, run, seed, session_create, settings_v2

pytestmark = needs_db


def _code(exc: pytest.ExceptionInfo) -> str:
    return exc.value.detail["code"]


def test_two_sessions_with_the_same_artisans_are_two_rows_and_a_repeated_client_key_is_the_same_row(monkeypatch) -> None:
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        first, created1 = await sessions.create_session(session_create(ids, ids.artisans[:2], key="same-key-" + ids.tag), ids.lead)
        second, created2 = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        replay, created3 = await sessions.create_session(session_create(ids, ids.artisans[:2], key="same-key-" + ids.tag), ids.lead)
        assert created1 and created2 and not created3
        assert first["id"] != second["id"]
        assert replay["id"] == first["id"]
        assert first["mode"] == "GROUP" and first["participantCount"] == 2
        assert [r["role"] for r in first["researchers"]] == ["LEAD"]
        assert len(first["joinCode"]) == 6
        await client.disconnect()

    run(go())


def test_the_title_is_generated_from_the_fields_and_follows_every_change(monkeypatch) -> None:
    from app.questionnaire.schemas import ParticipantPatch, SessionPatch
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], note="Under the neem tree"), ids.lead)
        craft = (await client.query_raw('SELECT "name" FROM "Craft" WHERE "id" = $1', ids.craft))[0]["name"]
        expected_craft = "-".join(w[0].upper() + w[1:].lower() if not any(c.isdigit() for c in w) else w for w in craft.split())
        assert s["title"] == f"Interview_2026-06-20_Bagru-Jaipur_{expected_craft}_Giriraj-Prasad-Chhipa"
        assert s["note"] == "Under the neem tree" and s["legacyTitle"] is None
        s = await sessions.add_participant(s["id"], ids.artisans[1], None, ids.lead)
        assert s["title"].startswith("Group-Interview_2026-06-20_Bagru-Jaipur_") and s["title"].endswith("_2-Artisans")
        s = await sessions.patch_session(s["id"], SessionPatch(version=s["version"], sessionDate="2026-06-21", place="Sanganer, Jaipur"), ids.lead)
        assert s["title"].startswith("Group-Interview_2026-06-21_Sanganer-Jaipur_")
        with pytest.raises(HTTPException) as stale:
            await sessions.patch_session(s["id"], SessionPatch(version=1, note="old"), ids.lead)
        assert _code(stale) == "STALE_VERSION" and stale.value.detail["current"]["id"] == s["id"]
        s = await sessions.patch_participant(s["id"], ids.artisans[1], ParticipantPatch(leftAt="2026-06-21T07:00:00Z"), ids.lead)
        assert any(p["leftAt"] for p in s["participants"])
        await client.disconnect()

    run(go())


def test_a_client_title_is_refused_with_title_is_generated(monkeypatch) -> None:
    from fastapi.testclient import TestClient

    from app.core import deps
    from app.main import app

    async def setup() -> object:
        client = await connect()
        ids = await seed(client)
        await client.disconnect()
        return ids

    ids = run(setup())
    settings_v2(monkeypatch, True)
    app.dependency_overrides[deps.get_current_user] = lambda: ids.lead
    try:
        http = TestClient(app)
        body = session_create(ids, ids.artisans[:1]).model_dump(mode="json")
        refused = http.post("/api/questionnaire/sessions", json={**body, "title": "section F"})
        assert refused.status_code == 422
        assert refused.json()["detail"]["code"] == "TITLE_IS_GENERATED"
        settings_v2(monkeypatch, False)
        dark = http.post("/api/questionnaire/sessions", json=body)
        assert dark.status_code == 404 and dark.json()["detail"]["code"] == "NOT_FOUND"
        config = http.get("/api/questionnaire/config").json()
        assert config["v2Enabled"] is False and config["contractVersion"] == 1
    finally:
        app.dependency_overrides.pop(deps.get_current_user, None)


def test_open_match_finds_a_same_day_overlapping_session_by_any_researcher(monkeypatch) -> None:
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        mine, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.other)
        found = await sessions.open_match(questionnaire_id=ids.q, workshop_id=ids.workshop, artisan_ids=[ids.artisans[1]], day="2026-06-20")
        assert [i["id"] for i in found["items"]] == [mine["id"]]
        assert found["items"][0]["researcher"]["id"] == ids.other.id
        none = await sessions.open_match(questionnaire_id=ids.q, workshop_id=ids.workshop, artisan_ids=[ids.artisans[2]], day="2026-06-20")
        assert none["items"] == []
        await sessions.finish(mine["id"], ids.other)
        closed = await sessions.open_match(questionnaire_id=ids.q, workshop_id=ids.workshop, artisan_ids=[ids.artisans[1]], day="2026-06-20")
        assert closed["items"] == []
        await client.disconnect()

    run(go())


def test_join_code_merge_continue_and_the_delete_and_participant_refusals(monkeypatch) -> None:
    from app.questionnaire.schemas import ContinueRequest
    from app.questionnaire import sessions
    from qv2_fixtures import record_take, t

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        a, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        b, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.other)
        joined = await sessions.join(a["joinCode"].lower(), ids.other)
        assert {r["userId"] for r in joined["researchers"]} == {ids.lead.id, ids.other.id}
        with pytest.raises(HTTPException) as unknown:
            await sessions.join("ZZZZZZ", ids.other)
        assert _code(unknown) == "JOIN_CODE_UNKNOWN"
        await record_take(client, ids, b["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(1))
        # A session with recordings is soft-deleted and restorable (owner decision 3, 21 Oct 2026),
        # no longer refused with HAS_RECORDINGS.
        await sessions.delete_session(b["id"], ids.admin)
        with pytest.raises(HTTPException) as hidden:
            await sessions.session_payload(client, b["id"])
        assert _code(hidden) == "SESSION_UNKNOWN"
        await sessions.restore_session(b["id"], ids.admin)
        with pytest.raises(HTTPException) as takes:
            await sessions.remove_participant(b["id"], ids.artisans[1], False, ids.other)
        assert _code(takes) == "PARTICIPANT_HAS_TAKES" and takes.value.detail["count"] == 1
        # Merging and deleting are content admins' acts since 2026-10-20; the session's own
        # researcher is refused both.
        with pytest.raises(HTTPException) as refused:
            await sessions.merge_into(b["id"], a["id"], ids.other)
        assert refused.value.status_code == 403
        merged = await sessions.merge_into(b["id"], a["id"], ids.admin)
        assert merged["participantCount"] == 2 and merged["counts"]["takes"] == 1
        detached = await sessions.remove_participant(a["id"], ids.artisans[1], True, ids.lead)
        assert detached["participantCount"] == 1
        empty, _ = await sessions.create_session(session_create(ids, ids.artisans[2:3]), ids.lead)
        with pytest.raises(HTTPException) as creator:
            await sessions.delete_session(empty["id"], ids.lead)
        assert creator.value.status_code == 403
        await sessions.delete_session(empty["id"], ids.admin)
        cont = await sessions.continue_sessions(
            ContinueRequest(fromSessionId=a["id"], artisanIds=ids.artisans[:2], split="EACH", sessionDate="2026-06-22"), ids.lead
        )
        assert len(cont["sessions"]) == 2 and all(s["purpose"] == "FOLLOW_UP" for s in cont["sessions"])
        assert cont["firstGap"][ids.artisans[0]]["code"] == "B"
        await client.disconnect()

    run(go())


def test_a_volunteer_cannot_start_a_session_but_records_into_one_they_are_on(monkeypatch) -> None:
    from app.questionnaire import sessions
    from qv2_fixtures import record_take, t

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        with pytest.raises(HTTPException) as refused:
            await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.vol)
        assert _code(refused) == "FORBIDDEN"
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await sessions.add_researcher_route(s["id"], ids.vol.id, "VOLUNTEER", ids.lead)
        take = await record_take(client, ids, s["id"], ids.vol, targets=[{"sectionId": ids.sections["B"]}], at=t(1))
        assert take["recordedBy"]["id"] == ids.vol.id
        await client.disconnect()

    run(go())


def test_a_scanned_qr_never_takes_the_session_from_its_lead(monkeypatch) -> None:
    """Audit 2026-10-20: a scanner's create (Android's offline join) used to become the session, and
    the lead's own create then returned it without adding the lead, so every queued edit was a 403."""
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        key = "qr-key-" + ids.tag
        # The web scanner joins by key and creates nothing while the lead is offline.
        with pytest.raises(HTTPException) as not_synced:
            await sessions.join_by_key(key, ids.other)
        assert _code(not_synced) == "SESSION_UNKNOWN" and "synced" in not_synced.value.detail["message"]
        # A handset scanner's offline join makes a stand-in with no LEAD.
        stand_in, created = await sessions.create_session(
            session_create(ids, ids.artisans[:1], key=key, researchers=[{"userId": ids.other.id, "role": "CONTRIBUTOR"}]), ids.other
        )
        assert created and [r["role"] for r in stand_in["researchers"]] == ["CONTRIBUTOR"]
        # A volunteer's offline join before anything synced is told to wait, not that they can't start one.
        with pytest.raises(HTTPException) as volunteer:
            await sessions.create_session(
                session_create(ids, ids.artisans[:1], key="vol-" + ids.tag, researchers=[{"userId": ids.vol.id, "role": "VOLUNTEER"}]), ids.vol
            )
        assert _code(volunteer) == "SESSION_UNKNOWN"
        # The lead's own create arrives: the lead takes the session over with what they entered.
        lead, created = await sessions.create_session(
            session_create(ids, ids.artisans[:2], key=key, place="Bagru, Jaipur", note="Courtyard", purpose="FOLLOW_UP",
                           researchers=[{"userId": ids.prof.id, "role": "CONTRIBUTOR"}]),
            ids.lead,
        )
        assert not created and lead["id"] == stand_in["id"]
        assert lead["place"] == "Bagru, Jaipur" and lead["note"] == "Courtyard" and lead["purpose"] == "FOLLOW_UP"
        roles = {r["userId"]: r["role"] for r in lead["researchers"]}
        assert roles == {ids.lead.id: "LEAD", ids.other.id: "CONTRIBUTOR", ids.prof.id: "CONTRIBUTOR"}
        assert lead["participantCount"] == 2
        row = await sessions.require_session(client, lead["id"])
        assert str(row["createdById"]) == ids.lead.id and await sessions.can_work_in(client, ids.lead, row)
        # A replay by the lead changes nothing; a later scanner is added, not promoted.
        again, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], key=key, place="Elsewhere"), ids.lead)
        assert again["place"] == "Bagru, Jaipur" and again["participantCount"] == 2
        joined = await sessions.join_by_key(key, ids.vol)
        assert {r["userId"]: r["role"] for r in joined["researchers"]}[ids.vol.id] == "VOLUNTEER"
        await client.disconnect()

    run(go())


def test_an_empty_session_with_an_upload_on_its_way_is_not_deleted(monkeypatch) -> None:
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await client.execute_raw(
            'INSERT INTO "MediaUploadIntent" ("id", "objectKey", "clientClipKey", "sessionId", "uploaderId", "sizeBytes", '
            '"contentType", "expiresAt") VALUES ($1, $2, $3, $4, $5, 10, \'audio/mp4\', now() + interval \'1 day\')',
            "intent-" + ids.tag, "key-" + ids.tag, "clip-" + ids.tag, s["id"], ids.lead.id,
        )
        with pytest.raises(HTTPException) as pending:
            await sessions.delete_session(s["id"], ids.admin)
        assert _code(pending) == "HAS_RECORDINGS" and pending.value.detail["pending"] == 1
        await client.execute_raw('UPDATE "MediaUploadIntent" SET "closedAt" = now() WHERE "sessionId" = $1', s["id"])
        await sessions.delete_session(s["id"], ids.admin)
        await client.disconnect()

    run(go())
