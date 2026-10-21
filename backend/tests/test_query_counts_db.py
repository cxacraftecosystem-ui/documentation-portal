"""How many database round trips the list and batch paths make, pinned against a real Postgres.

Each test builds the same scene twice, small and large, and counts the round trips of ONE call with
``query_counter.counting`` (every Prisma call, raw SQL included). A read path that costs the same
however long its list is says so with an equality between the two counts and a pinned number; a
write path that must touch every item pins its fixed reads and its cost per item separately. A
change that brings an N+1 back fails here with both numbers in the message.

Runs where the other questionnaire v2 suites run (``QV2_TEST_DATABASE_URL``, a migrated throwaway
database); CI's database job sets it. ``QUERY_COUNT_REPORT=<file>`` appends every measured number
to that file, which is how the before/after table of the change that added this module was made.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t
from query_counter import counting, report

pytestmark = needs_db


def _put(*items: Any) -> Any:
    from app.schemas.questionnaire_v2 import AnswersPut

    return AnswersPut.model_validate({"items": list(items)})


async def _world(monkeypatch: Any, **seed_args: Any) -> tuple[Any, SimpleNamespace]:
    client = await connect()
    patch_db(monkeypatch, client)
    no_outside_calls(monkeypatch)
    return client, await seed(client, **seed_args)


# ── a session's recordings (GET /questionnaire/v2/sessions/{id}) ──────────────────────────────


async def _session_with_takes(monkeypatch: Any, takes: int) -> int:
    from app.api.routes import questionnaire_v2 as routes
    from app.services import consent_gate
    from app.services.questionnaire_v2 import sessions

    client, ids = await _world(monkeypatch, artisans=3)
    try:
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:3]), ids.lead)
        made = []
        for n in range(takes):
            made.append(await record_take(
                client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions[f"F{n % 7 + 1}"]}], at=t(n * 0.1),
                subjects=[{"artisanId": ids.artisans[n % 3], "role": "ANSWERED"}],
            ))
        # The last take continues the one before it (a part number to work out), and one artisan has
        # said no (a consent verdict that is not the default).
        await client.execute_raw(
            'UPDATE "QuestionnaireRecording" SET "continuationOfId" = $1 WHERE "id" = $2', made[-2]["id"], made[-1]["id"]
        )
        await consent_gate.record_artisan_consent(
            ids.artisans[1], state="REFUSED", basis="VERBAL_RECORDED", actor_id=ids.lead.id, client=client
        )
        with counting(client) as seen:
            payload = await routes.sessions_get(s["id"], ids.lead)
        assert len(payload["recordings"]) == takes
        assert payload["recordings"][-1]["downloadName"] and payload["recordings"][-1]["continuationOfId"] == made[-2]["id"]
        verdicts = {r["subjects"][0]["artisanId"]: r["consent"]["verdict"] for r in payload["recordings"]}
        assert verdicts[ids.artisans[1]] == consent_gate.REFUSED and verdicts[ids.artisans[0]] == consent_gate.ALLOW
        return seen.count
    finally:
        await client.disconnect()


def test_a_sessions_recordings_cost_the_same_reads_however_many_there_are(monkeypatch) -> None:
    small = run(_session_with_takes(monkeypatch, 2))
    large = run(_session_with_takes(monkeypatch, 8))
    report("sessions_get", takes2=small, takes8=large)
    assert small == large == 26, (small, large)


# ── one artisan's record (GET /artisans/{id}/record) ──────────────────────────────────────────


async def _record_over_sessions(monkeypatch: Any, sittings: int) -> int:
    from app.services.questionnaire_v2 import record, sessions

    client, ids = await _world(monkeypatch, artisans=2)
    try:
        artisan = ids.artisans[0]
        for n in range(sittings):
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2], day=f"2026-06-{20 + n}"), ids.lead)
            for k in range(2):
                await record_take(
                    client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions[f"D{k + 1}"]}],
                    at=t(k * 0.1, base=datetime(2026, 6, 20 + n, 6, 0, tzinfo=UTC)),
                    subjects=[{"artisanId": artisan, "role": "ANSWERED"}],
                )
        with counting(client) as seen:
            out = await record.artisan_record(artisan, ids.lead, client=client)
        timeline = out["instruments"][0]["sessions"]
        assert len(timeline) == sittings and all(len(entry["recordings"]) == 2 for entry in timeline)
        return seen.count
    finally:
        await client.disconnect()


def test_an_artisans_record_reads_every_session_at_once(monkeypatch) -> None:
    small = run(_record_over_sessions(monkeypatch, 1))
    large = run(_record_over_sessions(monkeypatch, 4))
    report("artisan_record", sessions1=small, sessions4=large)
    assert small == large == 32, (small, large)


# ── the corpus feed (GET /questionnaire/v2/corpus.jsonl) ──────────────────────────────────────


async def _corpus_over_artisans(monkeypatch: Any, people: int) -> int:
    from app.services.questionnaire_v2 import answers, corpus, sessions

    client, ids = await _world(monkeypatch, artisans=people)
    try:
        s, _ = await sessions.create_session(session_create(ids, ids.artisans), ids.lead)
        for n, artisan in enumerate(ids.artisans):
            await record_take(
                client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["B1"]}], at=t(n * 0.1),
                subjects=[{"artisanId": artisan, "role": "ANSWERED"}],
            )
        saved = await answers.put_answers(_put(*[
            {"artisanId": a, "questionLineageId": ids.questions["B1"], "text": f"Answer {a}", "sessionId": s["id"]}
            for a in ids.artisans
        ]), ids.lead)
        assert {r["status"] for r in saved["results"]} == {"SAVED"}
        with counting(client) as seen:
            out = await corpus.corpus(ids.q, status=None, client=client)
        assert len(out["answers"]) == people and len(out["recordings"]) == people
        assert all(row["primaryRecordingId"] for row in out["answers"])
        return seen.count
    finally:
        await client.disconnect()


def test_the_corpus_feed_reads_primaries_and_consent_once_for_everyone(monkeypatch) -> None:
    small = run(_corpus_over_artisans(monkeypatch, 2))
    large = run(_corpus_over_artisans(monkeypatch, 5))
    report("corpus", artisans2=small, artisans5=large)
    assert small == large == 10, (small, large)


# ── saving answers (PUT /questionnaire/v2/answers) ────────────────────────────────────────────


async def _save_answers(monkeypatch: Any, count: int) -> tuple[int, int]:
    from app.services.questionnaire_v2 import answers, sessions

    client, ids = await _world(monkeypatch, artisans=1, sections={"F": 8})
    try:
        s, _ = await sessions.create_session(session_create(ids, ids.artisans), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["F"]}], at=t(0),
                                 subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED"}])
        items = [
            {"artisanId": ids.artisans[0], "questionLineageId": ids.questions[f"F{n + 1}"], "text": f"Answer {n}",
             "sessionId": s["id"], "sourceRecordingId": take["id"]}
            for n in range(count)
        ]
        with counting(client) as seen:
            out = await answers.put_answers(_put(*items), ids.lead)
        assert [r["status"] for r in out["results"]] == ["SAVED"] * count
        return seen.count, seen.transactions
    finally:
        await client.disconnect()


def test_saving_answers_reads_once_for_the_batch_and_writes_per_item(monkeypatch) -> None:
    (small, tx_small), (large, tx_large) = run(_save_answers(monkeypatch, 2)), run(_save_answers(monkeypatch, 6))
    report("put_answers", items2=small, items6=large, tx2=tx_small, tx6=tx_large)
    per_item = (large - small) // 4
    fixed = small - 2 * per_item
    # Three writes per item (the answer row, its revision, the pointer to it) in one transaction
    # each; every read is made once for the whole batch.
    assert (fixed, per_item) == (4, 3), (small, large)
    assert (tx_small, tx_large) == (2, 6)


# ── answers typed into an old build (the v1 shim) ─────────────────────────────────────────────


async def _old_build_responses(monkeypatch: Any, count: int) -> int:
    from app.services.questionnaire_v2 import sessions, shims

    client, ids = await _world(monkeypatch, artisans=2, sections={"F": 8})
    try:
        s, _ = await sessions.create_session(session_create(ids, ids.artisans), ids.lead)
        responses = [SimpleNamespace(questionId=ids.questions[f"F{n + 1}"], answerText=f"Old answer {n}", answerSource="TYPED")
                     for n in range(count)]
        with counting(client) as seen:
            await shims.responses_to_answers(s["id"], ids.q, responses, ids.lead)
        written = await client.query_raw('SELECT count(*)::int AS n FROM "QuestionnaireAnswer" WHERE "questionnaireId" = $1', ids.q)
        assert written[0]["n"] == 2 * count
        return seen.count
    finally:
        await client.disconnect()


def test_old_build_responses_read_their_questions_and_answers_once(monkeypatch) -> None:
    small = run(_old_build_responses(monkeypatch, 2))
    large = run(_old_build_responses(monkeypatch, 6))
    report("shim_responses", responses2=small, responses6=large)
    # Two participants, so every response is two answers of three writes each.
    per_response = (large - small) // 4
    assert (small - 2 * per_response, per_response) == (6, 6), (small, large)


# ── draft answers from a take's transcript (GET /recordings/{id}/proposals) ───────────────────


async def _proposals(monkeypatch: Any, questions: int) -> int:
    from app.services.questionnaire_v2 import answers, sessions

    client, ids = await _world(monkeypatch, artisans=2, sections={"F": 8})
    try:
        s, _ = await sessions.create_session(session_create(ids, ids.artisans), ids.lead)
        take = await record_take(
            client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions[f"F{n + 1}"]} for n in range(questions)],
            at=t(0), subjects=[{"artisanId": a, "role": "ANSWERED"} for a in ids.artisans],
        )
        await client.execute_raw(
            'UPDATE "MediaFile" SET "transcriptStatus" = \'COMPLETED\', "transcriptText" = \'We use teak and chisels\' WHERE "id" = $1',
            take["mediaFileId"],
        )
        await client.execute_raw('UPDATE "QuestionnaireRecordingSubject" SET "named" = true WHERE "recordingId" = $1', take["id"])
        with counting(client) as seen:
            out = await answers.proposals(take["id"], ids.lead, client=client)
        assert len(out["proposals"]) == questions
        drafts = await client.query_raw('SELECT count(*)::int AS n FROM "QuestionnaireTakeDraft" WHERE "recordingId" = $1', take["id"])
        assert drafts[0]["n"] == 2 * questions
        return seen.count
    finally:
        await client.disconnect()


def test_proposals_write_every_draft_and_read_existing_answers_once(monkeypatch) -> None:
    small = run(_proposals(monkeypatch, 1))
    large = run(_proposals(monkeypatch, 5))
    report("proposals", questions1=small, questions5=large)
    assert small == large == 13, (small, large)


# ── the designer portal's match call (POST /cross-portal/peer/artisans/match) ────────────────


async def _peer_match(monkeypatch: Any, matches: int) -> int:
    from app.api.routes import cross_portal as routes
    from app.services.cross_portal import MATCHED_BY_PEHCHAN  # noqa: F401 - the module the route speaks for

    client, ids = await _world(monkeypatch, artisans=6)
    try:
        await client.execute_raw('TRUNCATE "CrossPortalAccessLog"')
        items = []
        for n, artisan in enumerate(ids.artisans[:matches]):
            number = f"PEH{ids.tag}{n}".upper()
            await client.execute_raw('UPDATE "Artisan" SET "pehchanCardNumber" = $1 WHERE "id" = $2', number, artisan)
            await client.execute_raw(
                'INSERT INTO "ArtisanSharingConsent" ("artisanId", "granted", "updatedAt") VALUES ($1, true, now())', artisan
            )
            items.append({"ref": f"dp-{n}", "pehchanCardNumber": number})
        monkeypatch.setattr(routes, "_signed", lambda settings, request, body, status_code=200: body)
        request = SimpleNamespace(headers={})
        with counting(client) as seen:
            out = await routes.peer_match(routes.PeerMatch(items=items), request, SimpleNamespace())
        assert len(out["matches"]) == matches
        logged = await client.query_raw('SELECT count(*)::int AS n FROM "CrossPortalAccessLog"')
        assert logged[0]["n"] == matches + 1
        return seen.count
    finally:
        await client.disconnect()


def test_a_peer_match_logs_every_match_in_one_write(monkeypatch) -> None:
    small = run(_peer_match(monkeypatch, 1))
    large = run(_peer_match(monkeypatch, 4))
    report("peer_match", matches1=small, matches4=large)
    assert small == large == 5, (small, large)


# ── the queue worker's idle tick ──────────────────────────────────────────────────────────────


async def _idle_tick(monkeypatch: Any, *, held: int) -> tuple[int, tuple[bool, bool]]:
    from app.services import email_outbox, mailer, media_queue

    client, ids = await _world(monkeypatch, artisans=1)
    try:
        await client.execute_raw('DELETE FROM "MediaProcessingJob"')
        await client.execute_raw('DELETE FROM "EmailMessage"')
        monkeypatch.setattr(mailer, "mail_configured", lambda settings=None: True)
        for n in range(held):
            media = await client.mediafile.create(data={
                "originalFilename": f"held{n}.m4a", "mediaType": "AUDIO", "mimeType": "audio/mp4", "sizeBytes": 1,
                "bucket": "b", "objectKey": f"k/{ids.tag}/{n}", "url": "u", "uploadedBy": {"connect": {"id": ids.lead.id}},
            })
            await client.mediaprocessingjob.create(data={"jobType": "TRANSCRIPTION", "mediaFileId": media.id})
        # What a tick cost before the probe: both drains, every time.
        with counting(client) as drains:
            await media_queue.process_next_media_jobs(limit=3, worker_id="test")
            await email_outbox.process_next_email_jobs(worker_id="test")
        report("worker_drains_unprobed", held=held, queries=drains.count)
        with counting(client) as seen:
            media_due, mail_due = await media_queue.queue_work_due()
            if media_due:
                await media_queue.process_next_media_jobs(limit=3, worker_id="test")
            if mail_due:
                await email_outbox.process_next_email_jobs(worker_id="test")
        return seen.count, (media_due, mail_due)
    finally:
        # Leave no queued job behind for the suites that drain this shared queue after this one.
        await client.execute_raw(
            'DELETE FROM "MediaProcessingJob" WHERE "mediaFileId" IN (SELECT "id" FROM "MediaFile" WHERE "objectKey" LIKE $1)',
            f"k/{ids.tag}/%",
        )
        await client.disconnect()


def test_an_idle_worker_tick_is_one_read(monkeypatch) -> None:
    idle, flags = run(_idle_tick(monkeypatch, held=0))
    # Transcription is paused (the seed's AppSetting) with jobs waiting: the settings row decides.
    held, held_flags = run(_idle_tick(monkeypatch, held=3))
    report("worker_idle_tick", empty=idle, paused_with_held_jobs=held)
    assert (idle, flags) == (1, (False, False))
    assert (held, held_flags) == (2, (False, False))


# ── telling every admin about a workshop-access request ──────────────────────────────────────


async def _workshop_request(monkeypatch: Any, admins: int) -> tuple[int, int]:
    from app.services import email_outbox, mailer

    client, ids = await _world(monkeypatch, artisans=1)
    try:
        for n in range(admins):
            await client.execute_raw(
                'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, \'ADMIN\', now())',
                f"adm{n}{ids.tag}", f"adm{n}{ids.tag}@example.test", f"Admin {n}",
            )
        monkeypatch.setattr(mailer, "mail_configured", lambda settings=None: True)
        with counting(client) as seen:
            sent = await email_outbox.notify_workshop_access_request(requester=ids.lead, workshop_titles=["Bagru"], note=None)
        # Every administrator in the (shared) database is told, these and any seeded before.
        assert sent >= admins
        return seen.count, sent
    finally:
        await client.disconnect()


def test_a_workshop_access_request_reads_the_admins_once(monkeypatch) -> None:
    small, sent_small = run(_workshop_request(monkeypatch, 1))
    large, sent_large = run(_workshop_request(monkeypatch, 4))
    report("workshop_access_request", queries_small=small, told_small=sent_small, queries_large=large, told_large=sent_large)
    # One read of the admins and one of their opt-outs, then the one queued e-mail each must cost.
    assert (small - sent_small, large - sent_large) == (2, 2), (small, sent_small, large, sent_large)


# ── assigning one scope to several people (POST /tasks/batch) ─────────────────────────────────


async def _task_batch(monkeypatch: Any, people: int) -> int:
    from app.api.routes import tasks
    from app.schemas.tasks import TaskBatchCreate

    client, ids = await _world(monkeypatch, artisans=1)
    try:
        assignees = []
        for n in range(people):
            uid = f"res{n}{ids.tag}"
            await client.execute_raw(
                'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, \'RESEARCHER\', now())',
                uid, f"{uid}@example.test", f"Researcher {n}",
            )
            assignees.append(uid)
        payload = TaskBatchCreate.model_validate(
            {"title": "Tool survey", "assigneeIds": assignees, "recordTypes": ["tool"], "targetCount": 3}
        )
        with counting(client) as seen:
            out = await tasks.create_task_batch(payload, current_user=ids.admin)
        assert [task["assigneeId"] for task in out["tasks"]] == assignees
        return seen.count
    finally:
        await client.disconnect()


def test_a_task_batch_checks_and_writes_its_assignees_in_one_go(monkeypatch) -> None:
    small = run(_task_batch(monkeypatch, 1))
    large = run(_task_batch(monkeypatch, 5))
    report("task_batch", assignees1=small, assignees5=large)
    # The assignees' accounts, the insert and the read-back are three round trips however many people
    # there are; what remains per person is their own derived-progress COUNT (run concurrently).
    assert (small - 1, large - 5) == (3, 3), (small, large)


# ── the batched consent verdict says what the one-at-a-time verdict says ──────────────────────


def test_batched_consent_verdicts_match_asking_one_recording_at_a_time(monkeypatch) -> None:
    """``consent_gate.verdict_details`` (what the lists now use) against ``verdict_detail`` (what
    they used per row) over every shape the rule distinguishes: a take with subjects, a take with
    none, its own grant, a refusal by a subject, and media outside the questionnaire reached through
    its artisan, its product and its interview."""
    from app.services import consent_gate
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client, ids = await _world(monkeypatch, artisans=3)
        try:
            s, _ = await sessions.create_session(session_create(ids, ids.artisans), ids.lead)
            with_subjects = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0),
                                              subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED"},
                                                        {"artisanId": ids.artisans[1], "role": "PRESENT"}])
            no_subjects = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0.1))
            granted = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["F"]}], at=t(0.2),
                                        subjects=[{"artisanId": ids.artisans[2], "role": "ANSWERED"}])
            await consent_gate.record_artisan_consent(ids.artisans[1], state="REFUSED", basis="VERBAL_RECORDED",
                                                      actor_id=ids.lead.id, client=client)
            await consent_gate.record_recording_consent(granted["mediaFileId"], state="GRANTED", actor_id=ids.lead.id, client=client)
            loose = []
            for n, link in enumerate(({"artisan": ids.artisans[1]}, {"artisan": ids.artisans[2]}, {"interview": s["id"]})):
                data: dict[str, Any] = {
                    "originalFilename": f"loose{n}.m4a", "mediaType": "AUDIO", "mimeType": "audio/mp4", "sizeBytes": 1,
                    "bucket": "b", "objectKey": f"loose/{ids.tag}/{n}", "url": "u", "uploadedBy": {"connect": {"id": ids.lead.id}},
                }
                if "artisan" in link:
                    data["artisan"] = {"connect": {"id": link["artisan"]}}
                else:
                    data["questionnaireInterview"] = {"connect": {"id": link["interview"]}}
                loose.append((await client.mediafile.create(data=data)).id)
            media = [with_subjects["mediaFileId"], no_subjects["mediaFileId"], granted["mediaFileId"], *loose, "no-such-media"]
            one_by_one = {m: await consent_gate.verdict_detail(m, client=client) for m in media}
            with counting(client) as seen:
                batched = await consent_gate.verdict_details(media, client=client)
            assert batched == one_by_one
            assert one_by_one[with_subjects["mediaFileId"]]["verdict"] == consent_gate.REFUSED
            assert one_by_one[granted["mediaFileId"]]["verdict"] == consent_gate.ALLOW
            assert seen.count <= 4
        finally:
            await client.disconnect()

    run(go())
