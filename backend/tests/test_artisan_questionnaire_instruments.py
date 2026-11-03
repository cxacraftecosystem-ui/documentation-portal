import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import app.api.routes.artisans as artisans_route

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"

W2_TITLE = "2nd Craft Toolkit Workshop"
W3_TITLE = "3rd Craft Toolkit Workshop"

ARTISAN_ID = "artisan-1"


def _instrument(qid: str, title: str, sort_order: int) -> SimpleNamespace:
    return SimpleNamespace(id=qid, title=title, sortOrder=sort_order, isActive=True)


def _response(
    rid: str,
    *,
    questionnaire_id: str,
    section_code: str,
    sort_order: int,
    prompt: str,
    answer: str,
    interview_id: str = "iv-1",
) -> SimpleNamespace:
    return SimpleNamespace(
        id=rid,
        questionId=f"q-{rid}",
        answerText=answer,
        notes=None,
        interviewId=interview_id,
        createdAt=datetime(2026, 6, 1, tzinfo=UTC),
        question=SimpleNamespace(
            id=f"q-{rid}",
            questionnaireId=questionnaire_id,
            sectionCode=section_code,
            sectionTitle=f"Section {section_code}",
            sortOrder=sort_order,
            prompt=prompt,
        ),
        interview=SimpleNamespace(
            id=interview_id,
            title="Sitting",
            questionnaireId=questionnaire_id,
            interviewDate=None,
        ),
        answeredBy=SimpleNamespace(id="user-1", name="Researcher"),
    )


class _Delegate:
    def __init__(self, rows: list[Any]) -> None:
        self._rows = rows

    async def find_many(self, **_kwargs):
        return list(self._rows)

    async def find_unique(self, **_kwargs):
        return self._rows[0] if self._rows else None


class _Db:
    def __init__(self, *, responses: list[Any], instruments: list[Any]) -> None:
        self.artisan = _Delegate(
            [SimpleNamespace(id=ARTISAN_ID, name="Kanhu Charan Sahu", status="APPROVED")]
        )
        self.questionnaireresponse = _Delegate(responses)
        self.questionnaireinterview = _Delegate([])
        self.questionnaire = _Delegate(instruments)


def _ask(monkeypatch, *, responses: list[Any], instruments: list[Any]) -> dict[str, Any]:
    monkeypatch.setattr(
        artisans_route, "db", _Db(responses=responses, instruments=instruments)
    )
    return asyncio.run(artisans_route.get_artisan_questionnaire(ARTISAN_ID))


def _rows(payload: dict[str, Any]) -> list[tuple[str, str, int]]:
    return [
        (row["questionnaireId"], row["sectionCode"], row["sortOrder"])
        for row in payload["answered"]
    ]



def test_two_instruments_section_a_answers_are_not_interleaved(monkeypatch):
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W3, section_code="A", sort_order=1, prompt="W3 A1", answer="x"),
            _response("r2", questionnaire_id=W2, section_code="A", sort_order=1, prompt="W2 A1", answer="x"),
            _response("r3", questionnaire_id=W3, section_code="B", sort_order=1, prompt="W3 B1", answer="x"),
            _response("r4", questionnaire_id=W2, section_code="B", sort_order=1, prompt="W2 B1", answer="x"),
        ],
        instruments=[_instrument(W2, W2_TITLE, 1), _instrument(W3, W3_TITLE, 2)],
    )
    assert _rows(payload) == [
        (W2, "A", 1),
        (W2, "B", 1),
        (W3, "A", 1),
        (W3, "B", 1),
    ]


def test_the_instruments_own_picker_order_decides_which_account_reads_first(monkeypatch):
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W2, section_code="A", sort_order=1, prompt="W2", answer="x"),
            _response("r2", questionnaire_id=W3, section_code="A", sort_order=1, prompt="W3", answer="x"),
        ],
        instruments=[_instrument(W2, W2_TITLE, 9), _instrument(W3, W3_TITLE, 1)],
    )
    assert [row[0] for row in _rows(payload)] == [W3, W2]


def test_two_instruments_sharing_a_picker_position_still_do_not_interleave(monkeypatch):
    responses = [
        _response("r1", questionnaire_id=W3, section_code="A", sort_order=1, prompt="W3 A1", answer="x"),
        _response("r2", questionnaire_id=W2, section_code="A", sort_order=1, prompt="W2 A1", answer="x"),
        _response("r3", questionnaire_id=W3, section_code="A", sort_order=2, prompt="W3 A2", answer="x"),
        _response("r4", questionnaire_id=W2, section_code="A", sort_order=2, prompt="W2 A2", answer="x"),
    ]
    instruments = [_instrument(W2, W2_TITLE, 1), _instrument(W3, W3_TITLE, 1)]
    first = _rows(_ask(monkeypatch, responses=responses, instruments=instruments))
    second = _rows(_ask(monkeypatch, responses=list(reversed(responses)), instruments=instruments))

    assert [row[0] for row in first] == [W2, W2, W3, W3]
    assert first == second


def test_one_instruments_answers_still_read_in_section_then_question_order(monkeypatch):
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W2, section_code="B", sort_order=1, prompt="B1", answer="x"),
            _response("r2", questionnaire_id=W2, section_code="A", sort_order=2, prompt="A2", answer="x"),
            _response("r3", questionnaire_id=W2, section_code="A", sort_order=1, prompt="A1", answer="x"),
        ],
        instruments=[_instrument(W2, W2_TITLE, 1)],
    )
    assert _rows(payload) == [(W2, "A", 1), (W2, "A", 2), (W2, "B", 1)]



def test_every_answer_names_the_instrument_it_was_given_on(monkeypatch):
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W2, section_code="A", sort_order=1, prompt="A1", answer="x"),
            _response("r2", questionnaire_id=W3, section_code="A", sort_order=1, prompt="A1", answer="x"),
        ],
        instruments=[_instrument(W2, W2_TITLE, 1), _instrument(W3, W3_TITLE, 2)],
    )
    assert [(r["questionnaireId"], r["questionnaireTitle"]) for r in payload["answered"]] == [
        (W2, W2_TITLE),
        (W3, W3_TITLE),
    ]


def test_an_instrument_row_that_cannot_be_read_costs_the_title_and_nothing_else(monkeypatch):
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W2, section_code="A", sort_order=1, prompt="A1", answer="x")
        ],
        instruments=[],
    )
    assert payload["answered"][0]["questionnaireId"] == W2
    assert payload["answered"][0]["questionnaireTitle"] is None
    assert payload["total"] == 1


def test_the_existing_fields_on_an_answer_are_untouched(monkeypatch):
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W2, section_code="A", sort_order=3, prompt="How long?", answer="ten years")
        ],
        instruments=[_instrument(W2, W2_TITLE, 1)],
    )
    row = payload["answered"][0]
    assert set(row) == {
        "responseId",
        "questionId",
        "prompt",
        "sectionCode",
        "sectionTitle",
        "sortOrder",
        "questionnaireId",
        "questionnaireTitle",
        "answerText",
        "notes",
        "interviewId",
        "interviewTitle",
        "interviewDate",
        "answeredByName",
    }
    assert row["prompt"] == "How long?"
    assert row["answerText"] == "ten years"
    assert row["sortOrder"] == 3
    assert row["answeredByName"] == "Researcher"


def test_a_blank_answer_is_still_dropped_before_it_is_ever_ordered(monkeypatch):
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W2, section_code="A", sort_order=1, prompt="A1", answer="   "),
            _response("r2", questionnaire_id=W3, section_code="A", sort_order=1, prompt="A1", answer="real"),
        ],
        instruments=[_instrument(W2, W2_TITLE, 1), _instrument(W3, W3_TITLE, 2)],
    )
    assert _rows(payload) == [(W3, "A", 1)]
    assert payload["total"] == 1
