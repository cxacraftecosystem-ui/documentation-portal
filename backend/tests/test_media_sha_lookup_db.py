from __future__ import annotations

import hashlib

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, settings_v2, t

pytestmark = needs_db


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def test_a_file_already_uploaded_or_still_uploading_is_found_by_its_digest(monkeypatch) -> None:
    from app.questionnaire import recordings, sessions
    from app.questionnaire.schemas import PresignRequest, ShaLookup, ShaLookupRequest

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        try:
            ids = await seed(client)
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            other, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.other)
            landed, queued, unknown = _sha(f"landed {ids.tag}"), _sha(f"queued {ids.tag}"), _sha(f"unknown {ids.tag}")
            take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0),
                                     contentSha256=landed.upper())
            await recordings.presign(PresignRequest.model_validate({
                "linkedRecordType": "questionnaire_session", "sessionId": other["id"], "clientClipKey": f"k-{ids.tag}",
                "sizeBytes": 3400000, "contentType": "audio/mp4", "extension": "m4a", "contentSha256": queued,
            }), ids.other, client=client)
            found = await recordings.sha_lookup(ShaLookupRequest(sha256s=[unknown, queued, landed.upper(), landed], sessionId=s["id"]), ids.lead)
            ShaLookup.model_validate(found)
            by = {item["sha"]: item for item in found["items"]}
            assert [item["sha"] for item in found["items"]] == [queued, landed]
            assert by[landed]["recordingId"] == take["id"] and by[landed]["sessionId"] == s["id"]
            assert by[landed]["sameSession"] is True and by[landed]["pending"] is False and by[landed]["uploadedAt"]
            assert by[queued] == {**by[queued], "recordingId": None, "sessionId": other["id"], "sameSession": False, "pending": True}
            elsewhere = await recordings.sha_lookup(ShaLookupRequest(sha256s=[landed], sessionId=other["id"]), ids.other)
            assert elsewhere["items"][0]["sameSession"] is False
            await recordings.remove(take["id"], ids.admin, client=client)
            assert (await recordings.sha_lookup(ShaLookupRequest(sha256s=[landed]), ids.lead))["items"] == []
        finally:
            await client.disconnect()

    run(go())


def test_the_route_is_dark_until_the_flag_and_refuses_a_bad_digest_with_the_v2_error(monkeypatch) -> None:
    from fastapi.testclient import TestClient

    from app.core import deps
    from app.main import app

    app.dependency_overrides[deps.get_current_user] = lambda: type("U", (), {"id": "u1", "role": "RESEARCHER"})()
    try:
        http = TestClient(app)
        settings_v2(monkeypatch, False)
        assert http.post("/api/media/sha-lookup", json={"sha256s": [_sha("x")]}).json()["detail"]["code"] == "NOT_FOUND"
        settings_v2(monkeypatch, True)
        refused = http.post("/api/media/sha-lookup", json={"sha256s": ["not-a-digest"]})
        assert refused.status_code == 422 and refused.json()["detail"]["code"] == "VALIDATION_FAILED"
    finally:
        app.dependency_overrides.pop(deps.get_current_user, None)
