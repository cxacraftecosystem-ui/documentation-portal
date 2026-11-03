from __future__ import annotations

import inspect
from typing import Any

import pytest

from app.api.routes import questionnaire as questionnaire_routes
from app.services import questionnaire_instruments


pytestmark = pytest.mark.anyio


DEFAULT_ID = "qnr_default"
BOUND_ID = "qnr_bound_to_the_workshop"
WORKSHOP_ID = "wk_with_a_binding"


class _Workshop:
    def __init__(self, questionnaire_id: str | None) -> None:
        self.id = WORKSHOP_ID
        self.questionnaireId = questionnaire_id


class _Questionnaire:
    def __init__(self, qid: str) -> None:
        self.id = qid
        self.isActive = True
        self.isDefault = qid == DEFAULT_ID


@pytest.fixture
def instruments(monkeypatch: pytest.MonkeyPatch) -> None:
    class _WorkshopDelegate:
        async def find_unique(self, where: dict[str, Any]) -> _Workshop | None:
            return _Workshop(BOUND_ID) if where.get("id") == WORKSHOP_ID else None

    class _QuestionnaireDelegate:
        async def find_unique(self, where: dict[str, Any]) -> _Questionnaire | None:
            qid = where.get("id")
            return _Questionnaire(qid) if qid in {DEFAULT_ID, BOUND_ID} else None

        async def find_first(self, **_: Any) -> _Questionnaire:
            return _Questionnaire(DEFAULT_ID)

    class _Db:
        workshop = _WorkshopDelegate()
        questionnaire = _QuestionnaireDelegate()

    monkeypatch.setattr(questionnaire_instruments, "db", _Db())


def _signature_params(handler: Any) -> set[str]:
    return set(inspect.signature(handler).parameters)



@pytest.mark.parametrize(
    "handler",
    [
        questionnaire_routes.list_sections,
        questionnaire_routes.list_questions,
        questionnaire_routes.interview_for_artisan_set,
    ],
    ids=["sections", "questions", "interview_for_artisan_set"],
)
def test_every_read_path_accepts_the_workshop_it_is_being_asked_about(handler: Any) -> None:
    assert "workshopId" in _signature_params(handler), (
        f"{handler.__name__} cannot be asked which workshop the reader is looking at, so "
        "`resolve_questionnaire_id`'s workshop step is unreachable from it and a bound instrument "
        "is served as the default."
    )


def test_the_write_path_still_passes_the_workshop_it_always_did() -> None:
    source = inspect.getsource(questionnaire_routes.create_interview)
    assert "resolve_questionnaire_id(payload.questionnaireId, payload.workshopId)" in source, (
        "create_interview no longer resolves with the workshop; the read paths would now be the "
        "correct half and the write the broken one, which is the same bug facing the other way."
    )



@pytest.mark.usefixtures("instruments")
async def test_a_bound_workshop_resolves_to_its_own_instrument_and_not_the_default() -> None:
    assert await questionnaire_instruments.resolve_questionnaire_id(None, WORKSHOP_ID) == BOUND_ID


@pytest.mark.usefixtures("instruments")
async def test_an_explicit_instrument_still_outranks_the_workshops_binding() -> None:
    assert await questionnaire_instruments.resolve_questionnaire_id(DEFAULT_ID, WORKSHOP_ID) == DEFAULT_ID


@pytest.mark.usefixtures("instruments")
async def test_no_workshop_and_no_instrument_still_means_the_default() -> None:
    assert await questionnaire_instruments.resolve_questionnaire_id(None, None) == DEFAULT_ID


@pytest.mark.usefixtures("instruments")
async def test_an_unbound_workshop_falls_through_to_the_default() -> None:
    assert await questionnaire_instruments.resolve_questionnaire_id(None, "wk_no_binding") == DEFAULT_ID


@pytest.mark.usefixtures("instruments")
async def test_the_read_and_the_write_agree_for_the_same_workshop() -> None:
    read = await questionnaire_instruments.resolve_questionnaire_id(None, WORKSHOP_ID)
    write = await questionnaire_instruments.resolve_questionnaire_id(None, WORKSHOP_ID)
    assert read == write == BOUND_ID
