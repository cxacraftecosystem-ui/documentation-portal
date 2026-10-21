"""Coverage computed on read (DESIGN 3.1-3.5; tests 9-16 and 32). Real Postgres."""

from __future__ import annotations

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

pytestmark = needs_db


def _check(ids, artisans, targets, session_id=None, sha=None):
    from app.schemas.questionnaire_v2 import CoverageCheckRequest

    return CoverageCheckRequest.model_validate(
        {"questionnaireId": ids.q, "artisanIds": artisans, "targets": targets, "sessionId": session_id, "contentSha256": sha}
    )


def test_positions_are_per_artisan_and_target_and_a_continuation_shares_its_parent(monkeypatch) -> None:
    from app.services.questionnaire_v2 import coverage, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        giriraj, meena = ids.artisans[0], ids.artisans[1]
        solo, _ = await sessions.create_session(session_create(ids, [giriraj]), ids.lead)
        group, _ = await sessions.create_session(session_create(ids, [giriraj, meena], day="2026-06-21"), ids.lead)
        target = [{"sectionId": ids.sections["D"]}]
        first = await record_take(client, ids, solo["id"], ids.lead, targets=target, at=t(0))
        together = await record_take(client, ids, group["id"], ids.lead, targets=target, at=t(30),
                                     subjects=[{"artisanId": giriraj, "role": "ANSWERED", "repeatReason": "NEW_SETTING"},
                                               {"artisanId": meena, "role": "ANSWERED"}])
        positions = {s["artisanId"]: s["position"] for s in together["subjects"]}
        assert positions == {giriraj: 2, meena: 1}
        reasons = {s["artisanId"]: s["repeatReason"] for s in together["subjects"]}
        assert reasons == {giriraj: "NEW_SETTING", meena: None}
        part2 = await record_take(client, ids, group["id"], ids.lead, targets=target, at=t(30.2), continuationOfId=together["id"],
                                  ackedRecordingIds=[first["id"], together["id"]])
        assert {s["position"] for s in part2["subjects"]} == {2, 1}
        rows = await coverage.take_rows(client, ids.q, artisan_ids=[giriraj])
        assert sorted({(str(r["recordingId"]), r["position"]) for r in rows}) == sorted(
            {(first["id"], 1), (together["id"], 2), (part2["id"], 2)}
        )
        assert part2["downloadName"].endswith("_Recording_D-All-P2.m4a")
        await client.disconnect()

    run(go())


def test_the_hierarchy_rule_and_present_subjects_and_titles_never_count(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import SessionPatch
    from app.services.questionnaire_v2 import coverage, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        a, b = ids.artisans[0], ids.artisans[1]
        s, _ = await sessions.create_session(session_create(ids, [a, b]), ids.lead)
        await sessions.patch_session(s["id"], SessionPatch(version=s["version"], note="section F"), ids.lead)
        await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0),
                          subjects=[{"artisanId": a, "role": "ANSWERED"}, {"artisanId": b, "role": "PRESENT"}])
        lineage_d3 = ids.questions["D3"]
        after_section = await coverage.check(_check(ids, [a, b], [{"questionLineageId": lineage_d3}], s["id"]), ids.lead)
        per = {p["artisanId"]: p for p in after_section["targets"][0]["perArtisan"]}
        assert per[a]["state"] == "VIA_SECTION" and per[a]["viaSection"] is True
        assert per[b]["state"] == "NOT_RECORDED", "PRESENT never gains coverage"
        await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["F2"]}], at=t(1),
                          subjects=[{"artisanId": b, "role": "ANSWERED"}])
        section_after_questions = await coverage.check(_check(ids, [b], [{"sectionId": ids.sections["F"]}], s["id"]), ids.lead)
        assert section_after_questions["targets"][0]["perArtisan"][0]["state"] == "PARTIAL"
        f_takes = await coverage.take_rows(client, ids.q, target_keys=[f"S:{ids.sections['F']}"])
        assert f_takes == [], "a title saying 'section F' has no effect on coverage"
        await client.disconnect()

    run(go())


def test_remove_then_restore_leaves_the_view_identical_and_removed_media_is_invisible(monkeypatch) -> None:
    from app.services.questionnaire_v2 import coverage, recordings, sessions
    from app.services.questionnaire_v2.live import live_media_where

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0))
        before = await coverage.take_rows(client, ids.q)
        await recordings.remove(take["id"], ids.admin, client=client)
        assert await coverage.take_rows(client, ids.q) == []
        assert await client.mediafile.count(where=live_media_where({"id": take["mediaFileId"]}, always=True)) == 0
        await recordings.restore(take["id"], ids.lead, client=client)
        after = await coverage.take_rows(client, ids.q)
        strip = lambda rows: [{k: v for k, v in r.items() if k != "changeSeq"} for r in rows]  # noqa: E731
        assert strip(after) == strip(before)
        await client.disconnect()

    run(go())


def test_a_reword_keeps_the_lineage_and_a_reorder_moves_no_code(monkeypatch) -> None:
    from app.services.questionnaire_v2 import coverage, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        d3 = ids.questions["D3"]
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": d3}], at=t(0))
        assert take["targets"][0]["displayCode"] == "D3" and take["targets"][0]["askedQuestionId"] == d3
        new_id = f"qD3b{ids.tag}"
        await client.execute_raw(
            'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", "sortOrder", "sectionId", "updatedAt") '
            "VALUES ($1, $2, 'D', 'Section D', 'Which tools do you use the most?', 3, $3, now())", new_id, ids.q, ids.sections["D"],
        )
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "retiredAt" = now(), "isActive" = false, "supersededById" = $2 WHERE "id" = $1', d3, new_id)
        row = await client.query_raw('SELECT "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "id" = $1', new_id)
        assert row[0]["lineageId"] == d3 and row[0]["displayCode"] == "D3"
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "sortOrder" = 9 WHERE "id" = $1', ids.questions["D1"])
        codes = await client.query_raw('SELECT "id", "displayCode" FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1', ids.q)
        assert {r["id"]: r["displayCode"] for r in codes}[ids.questions["D1"]] == "D1"
        check = await coverage.check(_check_req(ids, d3), ids.lead)
        assert check["targets"][0]["perArtisan"][0]["state"] == "RECORDED"
        assert check["targets"][0]["label"].startswith("D3 Which tools")
        await client.disconnect()

    def _check_req(ids, lineage):
        return _check(ids, ids.artisans[:1], [{"questionLineageId": lineage}])

    run(go())


def test_the_primary_take_rule_in_order() -> None:
    from app.services.questionnaire_v2.coverage import choose_primary

    def take(rid, hour, roster=2, reason=None, by="u1"):
        from datetime import UTC, datetime, timedelta

        return {"recordingId": rid, "capturedAt": datetime(2026, 7, 1, tzinfo=UTC) + timedelta(hours=hour),
                "rosterCount": roster, "reason": reason, "recordedById": by}

    takes = [take("g1", 0, roster=3), take("s1", 1, roster=1), take("r1", 2, roster=1, reason="REDO")]
    assert choose_primary(takes, answer_source="g1", explicit="s1") == "g1"
    assert choose_primary(takes, answer_source=None, explicit="s1") == "s1"
    assert choose_primary(takes, answer_source=None, explicit=None) == "r1"
    other_redo = [take("g1", 0, roster=3), take("s1", 1, roster=1, by="u1"), take("r1", 2, roster=1, reason="REDO", by="u2")]
    assert choose_primary(other_redo, answer_source=None, explicit=None) == "s1"
    assert choose_primary(other_redo, answer_source=None, explicit=None, senior_redoers={"u2"}) == "r1"
    assert choose_primary([take("g1", 0, roster=3), take("g2", 1, roster=4)], answer_source=None, explicit=None) == "g1"
    assert choose_primary([take("g1", 0, roster=3), take("g2", 1, roster=4, reason="RE_INTERVIEW", by="u9")],
                          answer_source=None, explicit=None) == "g1", "a re-interview never demotes the earlier account"
    assert choose_primary([take("g1", 0, roster=3), take("r1", 1, roster=1, reason="RE_INTERVIEW", by="u9")],
                          answer_source=None, explicit=None) == "g1", "not even a solo re-interview after a group take"
    assert choose_primary([take("g1", 0, roster=3), take("n1", 1, roster=1, reason="NEW_SETTING")],
                          answer_source=None, explicit=None) == "n1", "a solo take in a new setting still wins rule 4"
    assert choose_primary([take("l1", 0, roster=1, reason="RE_INTERVIEW", by="u9"), take("o1", 1, roster=1, by="u2")],
                          answer_source=None, explicit=None) == "l1", "a late upload captured first is the first account"


def test_the_snapshot_carries_edits_and_removals_since_a_sequence(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import RecordingPatch
    from app.services.questionnaire_v2 import coverage, recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        one = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0))
        two = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(1))
        base = await coverage.snapshot(ids.q, ids.workshop, None)
        assert {x["recordingId"] for x in base["takes"]} == {one["id"], two["id"]}
        seq = int(base["asOfSeq"])
        await recordings.patch(one["id"], RecordingPatch(version=one["version"], targets=[{"sectionId": ids.sections["F"]}]), ids.lead, client=client)
        await recordings.remove(two["id"], ids.admin, client=client)
        delta = await coverage.snapshot(ids.q, ids.workshop, seq)
        assert {x["recordingId"] for x in delta["takes"]} == {one["id"]}
        assert {x["targetKey"] for x in delta["takes"]} == {f"S:{ids.sections['F']}"}
        assert delta["removedRecordingIds"] == [two["id"]]
        grid = await coverage.grid(ids.q, artisan_ids=ids.artisans[:2], client=client)
        f = next(sec for sec in grid["sections"] if sec["code"] == "F")
        assert {c["state"] for c in f["cells"]} == {"RECORDED"}
        await client.disconnect()

    run(go())
