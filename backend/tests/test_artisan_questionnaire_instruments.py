"""``GET /artisans/{id}/questionnaire`` — one artisan, two instruments, two section "A"s.

================================================================================================
THE DEFECT: A SORT KEY THAT WAS COMPLETE UNTIL 2026-09-13 AND SILENTLY STOPPED BEING SO
================================================================================================

``20260913100000_questionnaire_instruments`` made one artisan legitimately interviewable ONCE PER
INSTRUMENT. Before it, ``QuestionnaireInterview.artisanSetKey`` was globally unique, so an artisan's
answers could only ever have come from one questionnaire and ``(sectionCode, sortOrder)`` was a
complete ordering.

After it, it is not — and the failure is not "badly ordered", it is INTERLEAVED. The 2nd Craft
Toolkit Workshop's corpus is coded RESP, A..W; the 3rd's is coded A..V. Every one of the 22 new
codes already existed, which is the collision the migration's header spends its opening paragraphs
on. So an artisan who sat for both workshops has two section "A"s, two section "B"s, and a
``sortOrder`` that restarts at 1 inside each. Sorting on the code alone lays 3rd-workshop A1 beside
2nd-workshop A1 under a single heading, with nothing on the row to say they came from different
instruments. A researcher reading that list sees ONE questionnaire answered inconsistently, and
section V is the case that makes it concrete: "International Exposure and Overseas Travel" in one
instrument, "NETWORK / ECOSYSTEM MAPPING" in the other. The two answers do not describe the same
subject at all.

Nothing raises. Both rows are real, both answers are correct, and the only thing wrong is which
order they are in and the absence of the one field that would let a client tell them apart — which
is why this is pinned with a test rather than left to be noticed on a screen.

WHY THE FAKE DATABASE. The live repository holds zero ``QuestionnaireResponse`` rows (every answer
in it is an audio clip attached to an interview), which is the same reason
``tests/test_questionnaire_consolidation.py`` gives for testing the other half of this feature the
same way. There is nothing to query, so the two-instrument case cannot be demonstrated against real
data at all.
"""

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

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
    """One answered ``QuestionnaireResponse``, with its question and interview hydrated."""
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
    """``(questionnaireId, sectionCode, sortOrder)`` per answer, in the order they were returned."""
    return [
        (row["questionnaireId"], row["sectionCode"], row["sortOrder"])
        for row in payload["answered"]
    ]


# --------------------------------------------------------------------------------------
# 1. The ordering
# --------------------------------------------------------------------------------------


def test_two_instruments_section_a_answers_are_not_interleaved(monkeypatch):
    """THE DEFECT, DIRECTLY. Both instruments code their first section "A" and restart ``sortOrder``
    at 1, so a sort on ``(sectionCode, sortOrder)`` alone pairs 2nd-workshop A1 with 3rd-workshop A1
    under one heading. Every 2nd-workshop answer must come out before any 3rd-workshop answer."""
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
    """``Questionnaire.sortOrder`` leads, NOT the id and not the date. It is the order the picker
    shows and the order ``services/questionnaire_consolidation`` already prints the same artisan's
    document in, so the two views of one artisan's answers cannot disagree about which workshop
    comes first."""
    payload = _ask(
        monkeypatch,
        responses=[
            _response("r1", questionnaire_id=W2, section_code="A", sort_order=1, prompt="W2", answer="x"),
            _response("r2", questionnaire_id=W3, section_code="A", sort_order=1, prompt="W3", answer="x"),
        ],
        # The 3rd workshop deliberately placed FIRST in the picker: the answer order must follow the
        # admin's choice rather than the instrument id, which sorts the other way round.
        instruments=[_instrument(W2, W2_TITLE, 9), _instrument(W3, W3_TITLE, 1)],
    )
    assert [row[0] for row in _rows(payload)] == [W3, W2]


def test_two_instruments_sharing_a_picker_position_still_do_not_interleave(monkeypatch):
    """THE TIE IS REAL, NOT DEFENSIVE. ``Questionnaire.sortOrder`` is deliberately NOT unique
    (schema.prisma:1175) — two instruments sharing a position is a cosmetic tie the schema accepts
    on purpose — so without a second component the interleaving comes straight back, and comes back
    NON-DETERMINISTICALLY: a different order on every load, from whatever order the database
    happened to return the rows in. The instrument id breaks it, so the grouping holds and repeated
    loads are stable."""
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
    """THE COMPATIBILITY HALF. Every repository that existed before the migration has one
    instrument, and adding a leading component to the sort key must not reshuffle a single one of
    those lists."""
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


# --------------------------------------------------------------------------------------
# 2. The two new wire fields
# --------------------------------------------------------------------------------------


def test_every_answer_names_the_instrument_it_was_given_on(monkeypatch):
    """WITHOUT THIS THERE IS NO ORDERING GOOD ENOUGH. Grouping the answers correctly puts the two
    section "A"s in separate runs, but a client rendering a flat list still prints two identical
    headings back to back and two prompts that are very often word-for-word the same. The id is for
    the client's grouping; the title is what a person reads."""
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
    """A retired instrument an admin has since hard-deleted, or a row a narrowed read did not
    return, must not take the whole route out with a KeyError or an AttributeError. The id is on the
    QUESTION and is therefore always present; only the human title is unavailable, and a missing
    title is a blank on a screen rather than a 500 on a field handset."""
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
    """Both clients read this payload and Kotlin's ``ignoreUnknownKeys`` means a RENAMED field is
    silent: the Android build would deserialise ``prompt`` as null and render an answer with no
    question above it, with nothing in any log. The two new keys are ADDITIVE, and that is asserted
    rather than assumed."""
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
    """The route's existing contract: ``answered`` holds NON-EMPTY answers. A whitespace-only row is
    a question somebody opened and left, not an answer, and it must not become a heading in a
    two-instrument list any more than it did in a one-instrument one."""
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
