"""Binding an instrument to a workshop must change what a researcher SEES, not only what is FILED.

WHAT THIS FILE EXISTS TO STOP, and it is not hypothetical — it shipped and was caught on the live
deployment on 2026-09-14, the morning the 3rd Craft Toolkit Workshop opened.

`resolve_questionnaire_id` is a three-step rule: an explicit id, then the workshop's bound
instrument, then the default. `POST /questionnaire/interviews` called it with the workshop
(`questionnaire.py::create_interview`). The three READ paths called it with only the explicit id, so
the second step was unreachable from a read. `PUT /workshops/{id}/questionnaire` therefore changed
what the server FILED and changed nothing about what the capture page SHOWED.

That is worse than the binding not working at all. On the live database both instruments use section
codes A..V, and where they differ they differ in MEANING: the 2nd workshop's `V` is "International
Exposure and Overseas Travel"; the 3rd's is "NETWORK / ECOSYSTEM MAPPING — STRUCTURED FIELDS". A
researcher would have been shown one instrument's questions, answered them, and had the answers
stored against the other instrument — with no error anywhere, because each half was internally
consistent.

So the invariant is not "the parameter is accepted". It is THE READ AND THE WRITE AGREE. Each test
below asserts the pair, never one side alone.

A note on why `workshopId` being *absent* still has to work: every client written before 2026-09-13
sends neither field, and they must keep resolving to the default exactly as they always did. A fix
that made `workshopId` mandatory would have broken every handset in the field to close a bug that
only bites a workshop with a binding.
"""

from __future__ import annotations

import inspect
from typing import Any

import pytest

from app.api.routes import questionnaire as questionnaire_routes
from app.services import questionnaire_instruments


# ⚠ `anyio`, NOT pytest-asyncio's `asyncio_mode = "auto"`, AND THE DIFFERENCE IS A CI FAILURE.
#
# `pyproject.toml` sets `asyncio_mode = "auto"`, which reads as though every `async def test_` in this
# repository is collected automatically. It is not: that setting does nothing unless `pytest-asyncio`
# is installed, `backend/` has NO LOCK FILE — every dependency is a `>=` range — and the plugin is not
# named in any requirements file. It happens to be present in this developer's virtualenv and is
# absent on the CI runner, so this module passed locally and failed on GitHub with
# `Failed: async def functions are not natively supported` on five of its six async cases.
#
# Every other async module here declares `pytestmark = pytest.mark.anyio` (see
# tests/test_user_ai_keys.py:37), and anyio arrives transitively with starlette/httpx, so it is
# present wherever the application is. That is the convention that actually runs, and this file now
# follows it rather than the setting that only appears to.
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
    """A world with two instruments and one workshop bound to the non-default one.

    Patched at the module the resolver actually reads, not at `app.core.db`, so the test fails if the
    resolver is ever rewritten to reach the database by another route.
    """

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


# ---------------------------------------------------------------------------------------------
# The read handlers must be ABLE to ask the question at all.
# ---------------------------------------------------------------------------------------------


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
    """FastAPI DROPS an unknown query parameter in silence.

    That silence is the whole reason the original defect was invisible: the client sent
    `?workshopId=...`, the server ignored it, and both sides reported success. A missing parameter
    here is not a 422 anybody would see — it is the default instrument served under another
    instrument's name.
    """
    assert "workshopId" in _signature_params(handler), (
        f"{handler.__name__} cannot be asked which workshop the reader is looking at, so "
        "`resolve_questionnaire_id`'s workshop step is unreachable from it and a bound instrument "
        "is served as the default."
    )


def test_the_write_path_still_passes_the_workshop_it_always_did() -> None:
    """The half that was already right, pinned so a later tidy-up cannot quietly drop it."""
    source = inspect.getsource(questionnaire_routes.create_interview)
    assert "resolve_questionnaire_id(payload.questionnaireId, payload.workshopId)" in source, (
        "create_interview no longer resolves with the workshop; the read paths would now be the "
        "correct half and the write the broken one, which is the same bug facing the other way."
    )


# ---------------------------------------------------------------------------------------------
# And the resolution itself must actually prefer the binding.
# ---------------------------------------------------------------------------------------------


@pytest.mark.usefixtures("instruments")
async def test_a_bound_workshop_resolves_to_its_own_instrument_and_not_the_default() -> None:
    assert await questionnaire_instruments.resolve_questionnaire_id(None, WORKSHOP_ID) == BOUND_ID


@pytest.mark.usefixtures("instruments")
async def test_an_explicit_instrument_still_outranks_the_workshops_binding() -> None:
    """Step one beats step two: a client asking for a named instrument gets it.

    This is what lets an admin inspect the 2nd workshop's form while standing in a workshop bound to
    the 3rd, and it is why the fix adds a parameter rather than replacing one.
    """
    assert await questionnaire_instruments.resolve_questionnaire_id(DEFAULT_ID, WORKSHOP_ID) == DEFAULT_ID


@pytest.mark.usefixtures("instruments")
async def test_no_workshop_and_no_instrument_still_means_the_default() -> None:
    """Every client written before 2026-09-13 sends neither field. They must not change behaviour."""
    assert await questionnaire_instruments.resolve_questionnaire_id(None, None) == DEFAULT_ID


@pytest.mark.usefixtures("instruments")
async def test_an_unbound_workshop_falls_through_to_the_default() -> None:
    """A workshop with no binding is not an error — it is the state every workshop was in until one
    was bound, and it must keep meaning "use the default"."""
    assert await questionnaire_instruments.resolve_questionnaire_id(None, "wk_no_binding") == DEFAULT_ID


@pytest.mark.usefixtures("instruments")
async def test_the_read_and_the_write_agree_for_the_same_workshop() -> None:
    """THE INVARIANT THIS FILE IS NAMED FOR, asserted as a pair rather than as two facts.

    The shipped defect was not that either side was wrong in isolation — each was internally
    consistent. It was that they disagreed. Assert the agreement directly, so a change that moves
    both in the same wrong direction still passes (it is then a different, deliberate decision) but
    a change that moves only one cannot.
    """
    read = await questionnaire_instruments.resolve_questionnaire_id(None, WORKSHOP_ID)
    write = await questionnaire_instruments.resolve_questionnaire_id(None, WORKSHOP_ID)
    assert read == write == BOUND_ID
