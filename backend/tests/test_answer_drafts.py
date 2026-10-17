"""C28: AI-drafted answers from a transcript — inert drafts, section accept, machine-suggested origin.

No database and no provider: the route module's ``db``, ``get_settings`` and the drafting call are
replaced by fakes, and the pure half of ``services/answer_drafts`` is exercised directly.
"""

from __future__ import annotations

import pathlib
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import answer_drafts as routes
from app.services import answer_drafts as ad

pytestmark = pytest.mark.anyio

BACKEND = pathlib.Path(__file__).resolve().parents[1]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


# ── the pure half ──────────────────────────────────────────────────────────────────────────────


def test_parse_answers_keeps_only_asked_non_empty_answers():
    text = '```json\n{"answers": [{"n": 1, "answer": " Dabu "}, {"n": 2, "answer": ""}, {"n": 9, "answer": "stray"}, {"n": "x"}]}\n```'
    assert ad.parse_answers(text, 3) == {1: "Dabu"}
    assert ad.parse_answers("not json at all", 3) == {}
    assert ad.parse_answers(None, 3) == {}
    long = '{"answers": [{"n": 1, "answer": "' + "a" * (ad.ANSWER_MAX_CHARS + 50) + '"}]}'
    assert len(ad.parse_answers(long, 1)[1]) == ad.ANSWER_MAX_CHARS


def test_the_prompt_numbers_questions_and_forbids_inference():
    prompt = ad.build_prompt([(1, "Name of Artisan"), (2, "Craft")], "x" * (ad.TRANSCRIPT_MAX_CHARS + 10))
    assert "1. Name of Artisan" in prompt and "2. Craft" in prompt
    assert len(prompt) < ad.TRANSCRIPT_MAX_CHARS + 200
    assert "Never infer" in ad.SYSTEM_PROMPT and "empty string" in ad.SYSTEM_PROMPT


def test_a_persons_edit_keeps_the_machine_provenance():
    assert ad.origin_after_edit(None, "a", "b") is None
    assert ad.origin_after_edit(ad.ORIGIN_MACHINE, "a", "a") == ad.ORIGIN_MACHINE
    assert ad.origin_after_edit(ad.ORIGIN_MACHINE, "a", "b") == ad.ORIGIN_MACHINE_EDITED
    assert ad.origin_after_edit(ad.ORIGIN_MACHINE_EDITED, "b", "c") == ad.ORIGIN_MACHINE_EDITED
    assert ad.origin_after_edit(ad.ORIGIN_MACHINE, "a", "  ") is None


def test_the_ordinary_save_path_applies_the_origin_rule():
    src = (BACKEND / "app/api/routes/questionnaire.py").read_text(encoding="utf-8")
    body = src[src.index("async def upsert_responses") :]
    body = body[: body.index("\n@questionnaire_router")]
    assert "answer_drafts.origin_after_edit(" in body


def test_the_flag_defaults_to_off():
    from app.core.config import Settings

    assert Settings.model_fields["ai_answer_drafts_enabled"].default is False


# ── the routes, against fakes ─────────────────────────────────────────────────────────────────


def _match(row: dict[str, Any], where: dict[str, Any]) -> bool:
    for key, want in where.items():
        if key == "interviewId_questionId":
            if row.get("interviewId") != want["interviewId"] or row.get("questionId") != want["questionId"]:
                return False
            continue
        have = row.get(key)
        if isinstance(want, dict):
            if "in" in want and have not in want["in"]:
                return False
            if "not" in want and have == want["not"]:
                return False
        elif have != want:
            return False
    return True


class Table:
    def __init__(self, rows=None):
        self.rows = [dict(r) for r in rows or []]

    async def find_unique(self, where, include=None):
        hit = next((r for r in self.rows if _match(r, where)), None)
        return SimpleNamespace(**hit) if hit else None

    async def find_many(self, where=None, order=None):
        return [SimpleNamespace(**r) for r in self.rows if _match(r, where or {})]

    async def create(self, data):
        row = {"id": f"r{len(self.rows) + 1}", **data}
        self.rows.append(row)
        return SimpleNamespace(**row)

    async def update(self, where, data):
        for row in self.rows:
            if _match(row, where):
                row.update(data)
                return SimpleNamespace(**row)
        raise AssertionError(where)

    async def upsert(self, where, data):
        for row in self.rows:
            if _match(row, where):
                row.update(data["update"])
                return SimpleNamespace(**row)
        return await self.create(data["create"])

    async def update_many(self, where, data):
        n = 0
        for row in self.rows:
            if _match(row, where):
                row.update(data)
                n += 1
        return n


@pytest.fixture
def world(monkeypatch):
    fake = SimpleNamespace(
        questionnaireinterview=Table([{"id": "i1", "questionnaireId": "qn1", "createdById": "u1"}]),
        questionnairesection=Table([{"id": "s1", "questionnaireId": "qn1", "code": "A"}]),
        questionnairequestion=Table(
            [
                {"id": "q1", "sectionId": "s1", "isActive": True, "prompt": "History?", "sortOrder": 1},
                {"id": "q2", "sectionId": "s1", "isActive": True, "prompt": "Teacher?", "sortOrder": 2},
                {"id": "q3", "sectionId": "s1", "isActive": True, "prompt": "Answered already", "sortOrder": 3},
            ]
        ),
        questionnaireresponse=Table(
            [{"id": "resp3", "interviewId": "i1", "questionId": "q3", "answerText": "A person's words"}]
        ),
        questionnaireanswerdraft=Table(),
        mediafile=Table(
            [{"id": "m1", "questionnaireInterviewId": "i1", "transcriptText": "We learned from my mother.", "originalFilename": "a.webm"}]
        ),
    )
    monkeypatch.setattr(routes, "db", fake)
    monkeypatch.setattr(routes, "get_settings", lambda: SimpleNamespace(ai_answer_drafts_enabled=True))

    async def may_edit(*_a, **_k):
        return True

    async def required(model, record_id):
        row = await model.find_unique(where={"id": record_id})
        if row is None:
            raise HTTPException(status_code=404, detail="Record not found")
        return row

    monkeypatch.setattr(routes, "record_edit_privilege", may_edit)
    monkeypatch.setattr(routes, "require_record", required)
    asked: list[Any] = []

    async def fake_draft(questions, transcript, settings, *, user_id):
        asked.append((questions, transcript))
        return {1: "Since my grandfather", 2: "My mother"}, "anthropic", "claude-sonnet-5"

    monkeypatch.setattr(routes.ad, "draft_answers", fake_draft)
    return SimpleNamespace(db=fake, asked=asked)


USER = SimpleNamespace(id="u1", role="RESEARCHER")


async def test_everything_is_404_while_off(world, monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: SimpleNamespace(ai_answer_drafts_enabled=False))
    with pytest.raises(HTTPException) as exc:
        await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    assert exc.value.status_code == 404


async def test_drafting_asks_only_unanswered_questions_and_writes_no_answer(world):
    out = await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    questions, transcript = world.asked[0]
    assert [prompt for _, prompt in questions] == ["History?", "Teacher?"]
    assert transcript == "We learned from my mother."
    assert out["drafted"] == 2 and out["asked"] == 2
    drafts = world.db.questionnaireanswerdraft.rows
    assert {d["questionId"] for d in drafts} == {"q1", "q2"}
    assert all(d["status"] == "PENDING" and d["model"] == "claude-sonnet-5" for d in drafts)
    # Inert: not one answer was written by drafting.
    assert [r["questionId"] for r in world.db.questionnaireresponse.rows] == ["q3"]


async def test_accept_labels_kept_and_edited_answers_and_skips_empty(world):
    await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    out = await routes.accept_section(
        "i1",
        routes.AcceptIn(
            sectionId="s1",
            answers=[
                routes.AcceptedAnswer(questionId="q1", answerText="Since my grandfather"),
                routes.AcceptedAnswer(questionId="q2", answerText="My mother, Kamla"),
                routes.AcceptedAnswer(questionId="q3", answerText="ignored: no pending draft"),
            ],
        ),
        USER,
    )
    assert out == {"accepted": 1, "edited": 1, "skipped": 0}
    by_q = {r["questionId"]: r for r in world.db.questionnaireresponse.rows}
    assert by_q["q1"]["answerOrigin"] == ad.ORIGIN_MACHINE
    assert by_q["q2"]["answerOrigin"] == ad.ORIGIN_MACHINE_EDITED
    assert by_q["q3"]["answerText"] == "A person's words"
    statuses = {d["questionId"]: d["status"] for d in world.db.questionnaireanswerdraft.rows}
    assert statuses == {"q1": "ACCEPTED", "q2": "EDITED"}


async def test_an_answer_typed_since_the_draft_is_never_overwritten(world):
    await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    world.db.questionnaireresponse.rows.append(
        {"id": "resp1", "interviewId": "i1", "questionId": "q1", "answerText": "Typed meanwhile"}
    )
    out = await routes.accept_section(
        "i1", routes.AcceptIn(sectionId="s1", answers=[routes.AcceptedAnswer(questionId="q1", answerText="x")]), USER
    )
    assert out["skipped"] == 1
    assert next(r for r in world.db.questionnaireresponse.rows if r["questionId"] == "q1")["answerText"] == "Typed meanwhile"


async def test_no_transcript_is_a_422_and_a_stranger_is_refused(world, monkeypatch):
    with pytest.raises(HTTPException) as exc:
        await routes.draft_section("i1", routes.DraftIn(sectionId="s1", transcript="   "), USER)
    assert exc.value.status_code == 422

    async def no(*_a, **_k):
        return False

    monkeypatch.setattr(routes, "record_edit_privilege", no)
    with pytest.raises(HTTPException) as exc:
        await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    assert exc.value.status_code == 403


async def test_dismiss_marks_the_sections_pending_drafts(world):
    await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    out = await routes.dismiss_section("i1", routes.DismissIn(sectionId="s1"), USER)
    assert out["dismissed"] == 2
    assert {d["status"] for d in world.db.questionnaireanswerdraft.rows} == {"DISMISSED"}
