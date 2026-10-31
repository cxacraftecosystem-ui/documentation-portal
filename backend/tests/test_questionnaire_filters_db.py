"""Recording chips and the shared filters on a real, migrated Postgres (owner, 31 Oct 2026).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (shared/questionnaire.sha256).
It seeds only tables both portals have, then holds the sessions list, the recordings list, the
forms library's narrowing and the filter options to what the filters say, and counts the reads:
a page costs the same number of statements for three takes or eleven (no N+1).

The database is the throwaway one each portal's CI migrates: documentation-portal's
``QV2_TEST_DATABASE_URL``, designer-portal's ``DATABASE_URL`` through ``conftest.needs_db``.
"""

from __future__ import annotations

import asyncio
import os
import sys
import uuid
from datetime import date
from types import SimpleNamespace
from typing import Any

import pytest

_QV2 = os.environ.get("QV2_TEST_DATABASE_URL", "").strip()
if _QV2:  # documentation-portal
    URL = _QV2
    needs = pytest.mark.skipif(False, reason="")
else:  # designer-portal
    try:
        from conftest import (
            DATABASE_URL as URL,  # type: ignore[no-redef]
            needs_db as needs,
        )
    except ImportError:
        URL = ""
        needs = pytest.mark.skipif(True, reason="no migrated throwaway database for the questionnaire")

pytestmark = needs


class Counting:
    """The Prisma client, counting the statements the package sends through it."""

    def __init__(self, inner: Any) -> None:
        self._inner = inner
        self.reads = 0

    async def query_raw(self, *args: Any, **kwargs: Any) -> Any:
        self.reads += 1
        return await self._inner.query_raw(*args, **kwargs)

    async def execute_raw(self, *args: Any, **kwargs: Any) -> Any:
        self.reads += 1
        return await self._inner.execute_raw(*args, **kwargs)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._inner, name)


async def _connect() -> Any:
    from prisma import Prisma

    client = Prisma(datasource={"url": URL})
    await client.connect()
    return client


def _patch_db(monkeypatch: pytest.MonkeyPatch, client: Any) -> None:
    import app.core.db as core_db
    from prisma import Prisma

    monkeypatch.setattr(core_db, "db", client)
    for name, module in list(sys.modules.items()):
        if name.startswith("app.") and isinstance(getattr(module, "db", None), Prisma):
            monkeypatch.setattr(module, "db", client)


def _user(uid: str, name: str) -> SimpleNamespace:
    return SimpleNamespace(id=uid, email=f"{uid}@example.test", name=name, role="RESEARCHER", canReview=False,
                           canDownloadDataset=False, canViewProvenance=False, canManageQuestionnaire=False)


async def _seed(c: Any) -> SimpleNamespace:
    t = uuid.uuid4().hex[:10]
    ids = SimpleNamespace(tag=t)
    for key in ("lead", "other"):
        uid = f"u{key}{t}"
        await c.execute_raw(
            'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, \'RESEARCHER\'::"UserRole", now())',
            uid, f"{uid}@example.test", f"{key.title()} {t}",
        )
        setattr(ids, key, _user(uid, f"{key.title()} {t}"))
    ids.form = f"f{t}"
    await c.execute_raw(
        'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "isShared", "kind", "sortOrder", "updatedAt") '
        "VALUES ($1, $2, $3, true, 'MARKET_SURVEY', 1, now())",
        ids.form, f"Chip Survey {t}", ids.lead.id,
    )
    base = 900000 + 2 * int(t[:4], 16)
    ids.sec = {}
    for n, (letter, title) in enumerate((("A", f"Materials {t}"), ("B", f"Tools {t}"))):
        sid = f"s{letter}{t}"
        await c.execute_raw(
            'INSERT INTO "QuestionnaireSection" ("id", "questionnaireId", "code", "title", "sortOrder", "updatedAt") '
            "VALUES ($1, $2, $3, $4, $5, now())",
            sid, ids.form, f"{letter}{t.upper()}", title, base + n,
        )
        ids.sec[letter] = sid
    ids.q = {}
    for key, letter, prompt in (("q1", "A", f"Which yarn {t}?"), ("q2", "A", "Which dye?"), ("q3", "B", "Which loom?")):
        qid = f"{key}{t}"
        await c.execute_raw(
            'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", "sortOrder", '
            '"sectionId", "updatedAt") VALUES ($1, $2, $3, $4, $5, $6, $7, now())',
            qid, ids.form, f"{letter}{t.upper()}", f"{'Materials' if letter == 'A' else 'Tools'} {t}", prompt, int(key[1]),
            ids.sec[letter],
        )
        lineage = (await c.query_raw('SELECT COALESCE("lineageId", "id") AS l FROM "QuestionnaireQuestion" WHERE "id" = $1', qid))[0]["l"]
        ids.q[key] = str(lineage)
    ids.a = {}
    for key, name in (("a1", "Giriraj"), ("a2", "Meena"), ("a3", "Ravi")):
        aid = f"{key}{t}"
        await c.execute_raw(
            'INSERT INTO "Artisan" ("id", "name", "place", "createdById", "updatedAt") VALUES ($1, $2, \'Bagru\', $3, now())',
            aid, f"{name} {t}", ids.lead.id,
        )
        ids.a[key] = aid
    return ids


async def _session(c: Any, ids: SimpleNamespace, by: Any, day: str, artisans: list[str], *, source: str = "APP") -> str:
    from app.questionnaire import schemas as S, sessions

    create = S.SessionCreate(
        clientKey=f"ck-{uuid.uuid4().hex[:12]}", questionnaireId=ids.form, sessionDate=date.fromisoformat(day),
        participants=[S.ParticipantInput(artisanId=a) for a in artisans],
    )
    session, _created = await sessions.create_session(create, by, client=c)
    if source != "APP":
        await c.execute_raw('UPDATE "QuestionnaireInterview" SET "source" = $1::"SessionSource" WHERE "id" = $2', source, session["id"])
    return str(session["id"])


async def _take(c: Any, session_id: str, by: Any, at: str, *, section: str | None = None, lineage: str | None = None,
                subjects: list[tuple[str, str]], transcript: str | None = None, text: str | None = None) -> str:
    n = uuid.uuid4().hex[:12]
    mid, rid = f"m{n}", f"r{n}"
    await c.execute_raw(
        'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
        '"uploadedById", "questionnaireInterviewId", "transcriptStatus", "transcriptText", "updatedAt") '
        "VALUES ($1, $2, 'AUDIO'::\"MediaType\", 'audio/mp4', 1000, 'test-bucket', $3, $4, $5, $6, $7, now())",
        mid, f"clip-{n}.m4a", f"media/{by.id}/{n}/clip.m4a", by.id, session_id, transcript, text,
    )
    await c.execute_raw(
        'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", "durationMs", '
        '"rosterCount", "recordedById", "provenance", "updatedAt") '
        "VALUES ($1, $2, $3, $4::timestamp, 'DEVICE'::\"CapturedAtSource\", 61000, $5, $6, 'NATIVE'::\"RecordingProvenance\", now())",
        rid, mid, f"k-{n}", at, len(subjects), by.id,
    )
    if section:
        await c.execute_raw('INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId") VALUES ($1, $2, $3)',
                            rid, f"S:{section}", section)
    if lineage:
        await c.execute_raw(
            'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "questionLineageId") VALUES ($1, $2, $3)',
            rid, f"Q:{lineage}", lineage,
        )
    for artisan, role in subjects:
        await c.execute_raw(
            'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named") VALUES ($1, $2, $3::"SubjectRole", false)',
            rid, artisan, role,
        )
    return rid


async def _world(c: Any) -> SimpleNamespace:
    from app.questionnaire import answers, schemas as S

    ids = await _seed(c)
    ids.s1 = await _session(c, ids, ids.lead, "2026-10-20", [ids.a["a1"], ids.a["a2"]])
    ids.s2 = await _session(c, ids, ids.other, "2026-10-22", [ids.a["a3"]], source="UPLOAD")
    ids.t1 = await _take(c, ids.s1, ids.lead, "2026-10-20T05:00:00", section=ids.sec["A"],
                         subjects=[(ids.a["a1"], "ANSWERED"), (ids.a["a2"], "PRESENT")], transcript="COMPLETED",
                         text=f"the loom {ids.tag} came from Bagru")
    # 20:00 UTC on the 21st is the 22nd in India: captured on another day than its session.
    ids.t2 = await _take(c, ids.s1, ids.lead, "2026-10-21T20:00:00", lineage=ids.q["q3"], subjects=[(ids.a["a1"], "ANSWERED")])
    ids.t3 = await _take(c, ids.s2, ids.other, "2026-10-22T06:00:00", section=ids.sec["B"],
                         subjects=[(ids.a["a3"], "ANSWERED")], transcript="FAILED")
    # A typed answer in s2 on a question of section A: the section filter finds the session by it.
    saved = await answers.put_answers(S.AnswersPut(items=[S.AnswerItem(
        artisanId=ids.a["a3"], questionLineageId=ids.q["q1"], sessionId=ids.s2, text="Cotton")]), ids.other)
    assert saved["results"][0]["status"] == "SAVED", saved
    return ids


def _run(monkeypatch: pytest.MonkeyPatch, body: Any) -> None:
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "questionnaire_v2", True, raising=False)

    async def go() -> None:
        client = await _connect()
        _patch_db(monkeypatch, client)
        try:
            ids = await _world(client)
            await body(client, ids)
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_the_sessions_list_takes_every_filter(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import filters, sessions

    async def body(c: Any, ids: SimpleNamespace) -> None:
        async def found(**params: Any) -> set[str]:
            f = filters.parse({"questionnaireId": ids.form, **params})
            return {s["id"] for s in (await sessions.list_sessions(client=c, filters=f, limit=100))["items"]}

        both = {ids.s1, ids.s2}
        assert await found() == both
        assert await found(artisanId=ids.a["a1"]) == {ids.s1}
        assert await found(artisanId=f'{ids.a["a1"]},{ids.a["a3"]}') == both, "values of one filter are OR-ed"
        assert await found(sectionId=ids.sec["A"]) == both, "s1 by its take, s2 by its typed answer"
        assert await found(sectionId=ids.sec["B"]) == both, "s1's question take is in section B, s2's take too"
        assert await found(questionLineageId=ids.q["q3"]) == {ids.s1}
        assert await found(transcript="HAS") == {ids.s1}
        assert await found(transcript="NONE") == {ids.s2}
        assert await found(transcript="FAILED") == {ids.s2}
        assert await found(source="UPLOAD") == {ids.s2}
        assert await found(dateFrom="2026-10-21") == {ids.s2}
        assert await found(dateFrom="2026-10-20", dateTo="2026-10-20") == {ids.s1}
        assert await found(researcherId=ids.other.id) == {ids.s2}
        assert await found(kind="MARKET_SURVEY") == both and await found(kind="NONE") == set()
        assert await found(q=f"Meena {ids.tag}") == {ids.s1}, "free text finds a session by an artisan's name"
        assert await found(q=f"Other {ids.tag}") == {ids.s2}, "and by a researcher's"
        assert await found(artisanId=ids.a["a1"], source="UPLOAD") == set(), "filters are AND-ed"
        listed = (await sessions.list_sessions(client=c, filters=filters.parse({"questionnaireId": ids.form}), limit=100))["items"]
        one = next(s for s in listed if s["id"] == ids.s1)
        assert one["chips"]["questionnaire"]["title"] == f"Chip Survey {ids.tag}"
        assert [a["name"] for a in one["chips"]["artisans"]] == [f"Giriraj {ids.tag}", f"Meena {ids.tag}"]
        assert one["chips"]["sessionDate"] == "2026-10-20" and one["chips"]["targets"] == []

    _run(monkeypatch, body)


def test_the_recordings_list_filters_pages_and_carries_chips(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import filters

    async def body(c: Any, ids: SimpleNamespace) -> None:
        async def found(**params: Any) -> list[str]:
            f = filters.parse({"questionnaireId": ids.form, **params})
            return [r["id"] for r in (await filters.list_recordings(f, ids.lead, limit=100, client=c))["items"]]

        assert await found() == [ids.t3, ids.t2, ids.t1], "newest capture first"
        assert await found(artisanId=ids.a["a2"]) == [ids.t1], "an artisan who was only present finds the take"
        assert await found(sectionId=ids.sec["A"]) == [ids.t1]
        assert set(await found(sectionId=ids.sec["B"])) == {ids.t2, ids.t3}, "a question of the section counts"
        assert await found(questionLineageId=ids.q["q3"]) == [ids.t2]
        assert set(await found(dateFrom="2026-10-22", dateTo="2026-10-22")) == {ids.t2, ids.t3}, "session day or capture day"
        assert await found(transcript="HAS") == [ids.t1]
        assert set(await found(transcript="NONE")) == {ids.t2, ids.t3}
        assert await found(researcherId=ids.other.id) == [ids.t3]
        assert await found(source="UPLOAD") == [ids.t3]
        assert await found(q=f"loom {ids.tag}") == [ids.t1], "free text reads the transcript"
        assert await found(q=f"Materials {ids.tag}") == [ids.t1], "and the section's title"
        assert await found(q=f"Ravi {ids.tag}") == [ids.t3], "and the artisans"

        first = await filters.list_recordings(filters.parse({"questionnaireId": ids.form}), ids.lead, limit=2, client=c)
        assert [r["id"] for r in first["items"]] == [ids.t3, ids.t2] and first["nextCursor"]
        rest = await filters.list_recordings(filters.parse({"questionnaireId": ids.form}), ids.lead, limit=2,
                                             cursor=first["nextCursor"], client=c)
        assert [r["id"] for r in rest["items"]] == [ids.t1] and rest["nextCursor"] is None

        by_id = {r["id"]: r for r in (await filters.list_recordings(filters.parse({"questionnaireId": ids.form}), ids.lead, client=c))["items"]}
        chips1 = by_id[ids.t1]["chips"]
        assert chips1["targets"] == [{"kind": "SECTION", "sectionId": ids.sec["A"], "sectionCode": f"A{ids.tag.upper()}",
                                      "sectionTitle": f"Materials {ids.tag}"}]
        assert [(a["name"], a["role"]) for a in chips1["artisans"]] == [(f"Giriraj {ids.tag}", "ANSWERED"), (f"Meena {ids.tag}", "PRESENT")]
        assert chips1["questionnaire"] == {"id": ids.form, "title": f"Chip Survey {ids.tag}", "kind": "MARKET_SURVEY"}
        assert chips1["sessionDate"] == "2026-10-20" and chips1["capturedOn"] is None and chips1["durationMs"] == 61000
        assert chips1["researcher"]["name"] == f"Lead {ids.tag}" and chips1["source"] == "APP"
        chips2 = by_id[ids.t2]["chips"]
        assert chips2["targets"][0]["kind"] == "QUESTION" and chips2["targets"][0]["sectionTitle"] == f"Tools {ids.tag}"
        assert chips2["capturedOn"] == "2026-10-22", "captured on the 22nd in India, its session is the 20th's"

    _run(monkeypatch, body)


def test_the_forms_library_and_the_filter_options(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import chips, filters

    async def body(c: Any, ids: SimpleNamespace) -> None:
        assert await filters.form_ids_matching(filters.parse({}), client=c) is None, "nothing set narrows nothing"
        assert ids.form in await filters.form_ids_matching(filters.parse({"q": f"yarn {ids.tag}"}), client=c), "a question's prompt"
        assert ids.form in await filters.form_ids_matching(filters.parse({"q": f"Ravi {ids.tag}"}), client=c), "a session's artisan"
        assert ids.form in await filters.form_ids_matching(filters.parse({"artisanId": ids.a["a3"], "source": "UPLOAD"}), client=c)
        assert ids.form not in await filters.form_ids_matching(filters.parse({"artisanId": ids.a["a1"], "source": "UPLOAD"}), client=c)

        options = await filters.facets(field_name="artisans", q=ids.tag, client=c)
        counted = {o["id"]: o["count"] for o in options["artisans"]}
        assert counted == {ids.a["a1"]: 1, ids.a["a2"]: 1, ids.a["a3"]: 1}
        questions = await filters.facets(field_name="questions", questionnaire_ids=[ids.form], client=c)
        assert {o["id"] for o in questions["questions"]} == set(ids.q.values())
        sections = await filters.facets(field_name="sections", questionnaire_ids=[ids.form], client=c)
        assert [o["title"] for o in sections["sections"]] == [f"Materials {ids.tag}", f"Tools {ids.tag}"]

        # The media list's chips: a take gets them in full.
        media = (await c.query_raw('SELECT "mediaFileId" AS m FROM "QuestionnaireRecording" WHERE "id" = $1', ids.t3))[0]["m"]
        by_media = await chips.for_media(c, [str(media), "not-a-take"])
        assert set(by_media) == {str(media)} and by_media[str(media)]["artisans"][0]["id"] == ids.a["a3"]
        assert str(media) in await filters.media_ids_matching(filters.parse({"artisanId": ids.a["a3"]}), client=c)

    _run(monkeypatch, body)


def test_a_page_costs_the_same_reads_for_three_takes_or_eleven(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import filters, recordings, sessions

    async def body(c: Any, ids: SimpleNamespace) -> None:
        f = filters.parse({"questionnaireId": ids.form, "transcript": "HAS,NONE,FAILED", "sectionId": f'{ids.sec["A"]},{ids.sec["B"]}'})

        async def reads() -> tuple[int, int, int, int]:
            takes = Counting(c)
            listed = await filters.list_recordings(f, ids.lead, limit=100, client=takes)
            session_list = Counting(c)
            await sessions.list_sessions(client=session_list, filters=f, limit=100)
            serial = Counting(c)
            rows = await recordings.recording_rows(c, session_ids=[ids.s1, ids.s2])
            await recordings.serialize_recordings(serial, rows, ids.lead)
            return takes.reads, session_list.reads, serial.reads, len(listed["items"])

        few_takes, few_sessions, few_serial, few = await reads()
        for n in range(8):
            await _take(c, ids.s1 if n % 2 else ids.s2, ids.lead, f"2026-10-2{n % 3}T0{n}:00:00", section=ids.sec["A"],
                        subjects=[(ids.a["a1"], "ANSWERED"), (ids.a["a3"], "PRESENT")])
        many_takes, many_sessions, many_serial, many = await reads()
        assert (few, many) == (3, 11)
        assert many_takes == few_takes, f"{few_takes} reads for 3 takes, {many_takes} for 11: the recordings list is batched"
        assert many_sessions == few_sessions, f"{few_sessions} reads, then {many_sessions}: the sessions list is batched"
        assert many_serial == few_serial, f"{few_serial} reads, then {many_serial}: the chips are read once per list"
        assert many_takes <= 24 and many_sessions <= 16, (many_takes, many_sessions)

    _run(monkeypatch, body)
