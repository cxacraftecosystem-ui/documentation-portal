"""Every questionnaire read and write is scoped to ONE instrument — asserted at the route.

WHAT GOES WRONG WITHOUT THESE. From 2026-09-13 two instruments exist whose section codes collide
entirely: the 2nd Craft Toolkit Workshop runs RESP, A..W and the 3rd runs A..V. Nothing in that
collision raises. A read that forgets its `questionnaireId` returns both instruments' rows and every
map keyed by a section code resolves to whichever one the database returned last — so the completion
matrix turns the wrong instrument's cells green, a stale client's answers land on a sitting they were
never given in, and a task is scoped to sections its assignee's form does not contain. Each test
below names the specific one it prevents.

These drive the REAL routers over HTTP with `db` replaced by a recording fake, so what is asserted is
the `where` the handler actually built — not a helper that was told the answer. NOTHING TOUCHES A
DATABASE: `backend/.env` points at the live production pooler.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, HTTPException

from access_roster_fakes import FakeTable, install
from app.api.router import api_router
from app.api.routes import questionnaire, workshops
from app.core import deps

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"

LOCATION = {"latitude": 22.57, "longitude": 88.36, "state": "West Bengal", "district": "Kolkata"}

#: The submission-window columns `workshop_access.describe_workshop_submission` reads off a workshop
#: row. A fake workshop without them raises AttributeError deep inside the create path, which would
#: read as a scope failure and is not one.
_WORKSHOP_DATES = {"date": None, "startDate": None, "endDate": None}


def _user(role: str, **grants: Any) -> SimpleNamespace:
    flags = {
        "canManageCrafts": False,
        "canManageWorkshops": False,
        "canManageQuestionnaire": False,
        "canDownloadDataset": False,
        "canReview": False,
        "canViewProvenance": False,
    }
    flags.update(grants)
    return SimpleNamespace(id="u1", email="u1@example.test", name="Test", role=role, **flags)


class _Logged(FakeTable):
    """A `FakeTable` that also records the kwargs of every READ.

    `FakeTable` already records writes (`.writes`); what these tests are about is the `where` a
    handler hands to a read, so those are recorded too.
    """

    def __init__(
        self,
        name: str,
        log: list,
        seed: list[dict[str, Any]] | None = None,
        unique_keys: tuple[tuple[str, ...], ...] = (),
    ) -> None:
        super().__init__({}, seed, unique_keys)
        self._name = name
        self._log = log

    # `where` stays POSITIONAL-OR-KEYWORD on every one of these: handler code passes it by keyword,
    # but `FakeTable.update` calls `self.find_unique(where)` positionally, and a keyword-only
    # override turns every write into a TypeError.

    async def find_many(self, where: Any = None, **kwargs: Any) -> Any:
        self._log.append((self._name, "find_many", {"where": where, **kwargs}))
        return await super().find_many(where, **kwargs)

    async def find_first(self, where: Any = None, **kwargs: Any) -> Any:
        self._log.append((self._name, "find_first", {"where": where, **kwargs}))
        return await super().find_first(where, **kwargs)

    async def find_unique(self, where: Any = None, **kwargs: Any) -> Any:
        self._log.append((self._name, "find_unique", {"where": where, **kwargs}))
        return await super().find_unique(where, **kwargs)

    async def count(self, where: Any = None, **kwargs: Any) -> int:
        self._log.append((self._name, "count", {"where": where, **kwargs}))
        return await super().count(where, **kwargs)


class _Db:
    """`db`, auto-vivifying an empty logged table for anything asked of it.

    DELIBERATELY the opposite of `access_roster_fakes.FakeDb`'s strict rule, and the reason is what
    each fake is for. That one asserts WHICH tables the sign-in gate touches, so an unmodelled table
    must raise. These tests assert the `where` a questionnaire handler builds; the handlers behind
    them hydrate six relations apiece, and a strict fake would turn every one of those into a
    per-test registration exercise that tests nothing.
    """

    #: The composite uniques the questionnaire tables actually carry. Registered here because three
    #: of the tests below are ABOUT one of them, and against a fake with no uniqueness a test about
    #: a unique index passes over broken code.
    UNIQUE_KEYS: dict[str, tuple[tuple[str, ...], ...]] = {
        "questionnaire": (("title",),),
        "questionnairesection": (
            ("questionnaireId", "code"),
            ("questionnaireId", "sortOrder"),
        ),
        "questionnaireinterview": (("questionnaireId", "artisanSetKey"),),
        "questionnaireresponse": (("interviewId", "questionId"),),
    }

    def __init__(self, **seeds: list[dict[str, Any]]) -> None:
        object.__setattr__(self, "log", [])
        object.__setattr__(self, "_tables", {})
        for name, rows in seeds.items():
            self._tables[name] = _Logged(name, self.log, rows, self.UNIQUE_KEYS.get(name, ()))

    def __getattr__(self, name: str) -> Any:
        tables = object.__getattribute__(self, "_tables")
        if name not in tables:
            tables[name] = _Logged(
                name,
                object.__getattribute__(self, "log"),
                [],
                type(self).UNIQUE_KEYS.get(name, ()),
            )
        return tables[name]

    def reads(self, table: str, op: str | None = None) -> list[dict[str, Any]]:
        return [
            kwargs
            for name, operation, kwargs in self.log
            if name == table and (op is None or operation == op)
        ]


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://scope.test") as client:
            return await client.request(method, f"/api{path}", json=body)

    return asyncio.run(run())


def _instruments() -> list[dict[str, Any]]:
    return [
        {"id": W2, "title": "2nd Craft Toolkit Workshop", "sortOrder": 1, "isActive": True,
         "isDefault": True, "description": None, "createdById": None},
        {"id": W3, "title": "3rd Craft Toolkit Workshop", "sortOrder": 2, "isActive": True,
         "isDefault": False, "description": None, "createdById": None},
    ]


def _sections() -> list[dict[str, Any]]:
    """Both instruments have a section "A". They mean different things by it."""
    return [
        {"id": "w2-a", "questionnaireId": W2, "code": "A", "title": "Personal details",
         "sortOrder": 1, "isActive": True},
        {"id": "w2-b", "questionnaireId": W2, "code": "B", "title": "Family", "sortOrder": 2,
         "isActive": True},
        {"id": "w3-a", "questionnaireId": W3, "code": "A", "title": "Origin and journey",
         "sortOrder": 1, "isActive": True},
    ]


def _questions() -> list[dict[str, Any]]:
    return [
        {"id": "w2-q1", "questionnaireId": W2, "sectionId": "w2-a", "sectionCode": "A",
         "sectionTitle": "Personal details", "prompt": "Your name?", "sortOrder": 1,
         "isActive": True},
        {"id": "w3-q1", "questionnaireId": W3, "sectionId": "w3-a", "sectionCode": "A",
         "sectionTitle": "Origin and journey", "prompt": "Where from?", "sortOrder": 1,
         "isActive": True},
    ]


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch) -> _Db:
    fake = _Db(
        questionnaire=_instruments(),
        questionnairesection=_sections(),
        questionnairequestion=_questions(),
    )
    install(monkeypatch, fake)
    _CURRENT["user"] = _user("ADMIN")
    yield fake
    _CURRENT["user"] = None


# --- Reads ----------------------------------------------------------------------------------------


def test_listing_sections_narrows_to_the_resolved_instrument(db: _Db) -> None:
    response = _call("GET", f"/questionnaire/sections?questionnaireId={W3}")

    assert response.status_code == 200, response.text
    assert [s["code"] for s in response.json()] == ["A"]
    wheres = [kwargs.get("where") for kwargs in db.reads("questionnairesection", "find_many")]
    assert any(where and where.get("questionnaireId") == W3 for where in wheres), wheres


def test_listing_sections_with_no_instrument_named_lands_on_the_default(db: _Db) -> None:
    """Every client that predates the parameter sends nothing, and must keep getting exactly the
    instrument it has always got."""
    response = _call("GET", "/questionnaire/sections")

    assert response.status_code == 200, response.text
    assert [s["code"] for s in response.json()] == ["A", "B"]


def test_the_interview_list_filter_is_a_plain_key_that_survives_a_free_text_search(db: _Db) -> None:
    """WHERE the filter lands, not just that it is applied.

    `list_interviews` builds its `where` in three places that do not compose: the free-text search
    ASSIGNS `where["OR"]` outright, so a clause parked there is dropped the moment a caller also
    passes `search=`; and `where["AND"]` does not exist until it is assigned from `and_filters` near
    the end, so writing into it early is a KeyError. Every other scalar filter on this route is a
    plain top-level key, and this asserts the instrument filter is too — under a request that
    exercises both hazards at once.
    """
    response = _call("GET", f"/questionnaire/interviews?questionnaireId={W3}&search=bagru")

    assert response.status_code == 200, response.text
    wheres = [kwargs.get("where") or {} for kwargs in db.reads("questionnaireinterview", "count")]
    assert wheres, "the list never reached its count"
    for where in wheres:
        assert where.get("questionnaireId") == W3, where
        assert "OR" in where, "the free-text search went missing"
        assert "questionnaireId" not in str(where.get("OR")), "the filter was parked under OR"
        assert "questionnaireId" not in str(where.get("AND")), "the filter was parked under AND"


def test_the_completion_payload_names_the_instrument_it_is_about_and_has_no_duplicate_section_codes(
    db: _Db,
) -> None:
    """The matrix's COLUMNS come from a section read inside `completion_matrix` itself, separate
    from the three inside `_derived_completed_sections`. Narrow only the latter and the result is a
    46-column matrix — two "A", two "B", … — whose derived half is single-instrument: half the
    columns permanently blank, the other half looking right. The duplicate-code assertion is the
    half that catches it; "names its instrument" alone goes green over the whole thing."""
    response = _call("GET", f"/questionnaire/completion?questionnaireId={W3}")

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["questionnaireId"] == W3
    assert payload["questionnaireTitle"] == "3rd Craft Toolkit Workshop"
    codes = [s["code"] for s in payload["sections"]]
    assert codes == ["A"]
    assert len(codes) == len(set(codes)), codes


# --- Interview creates ----------------------------------------------------------------------------


def _interview_body(**extra: Any) -> dict[str, Any]:
    return {"title": "A sitting", "location": LOCATION, "artisanIds": ["a1"], **extra}


@pytest.fixture()
def creatable(db: _Db, monkeypatch: pytest.MonkeyPatch) -> _Db:
    """`create_interview` past its relation hydration, which is not what these tests are about."""
    async def noop(*_args: Any, **_kwargs: Any) -> None:
        return None

    monkeypatch.setattr(questionnaire, "hydrate_relations", noop)
    monkeypatch.setattr(questionnaire, "replace_interview_artisans", noop)
    return db


def test_an_interview_create_with_no_questionnaire_lands_on_the_default(creatable: _Db) -> None:
    response = _call("POST", "/questionnaire/interviews", _interview_body())

    assert response.status_code == 201, response.text
    created = creatable.questionnaireinterview.rows[-1]
    assert created.questionnaireId == W2


def test_an_interview_create_at_a_bound_workshop_lands_on_that_workshops_instrument(
    creatable: _Db,
) -> None:
    asyncio.run(
        creatable.workshop.create(data={"id": "w-1", "title": "Dehradun", "questionnaireId": W3, **_WORKSHOP_DATES})
    )

    response = _call("POST", "/questionnaire/interviews", _interview_body(workshopId="w-1"))

    assert response.status_code == 201, response.text
    assert creatable.questionnaireinterview.rows[-1].questionnaireId == W3


def test_a_non_manager_may_not_override_a_bound_workshops_instrument(creatable: _Db) -> None:
    """An explicit instrument contradicting the workshop's binding is a deliberate act — a pilot —
    and only somebody who could change the binding anyway may perform it. For everybody else it is
    far more likely a stale client, and a stale client filing 3rd-workshop answers at a 2nd-workshop
    event is invisible until somebody reads the report."""
    asyncio.run(
        creatable.workshop.create(data={"id": "w-1", "title": "Almora", "questionnaireId": W2, **_WORKSHOP_DATES})
    )
    body = _interview_body(workshopId="w-1", questionnaireId=W3)

    _CURRENT["user"] = _user("RESEARCHER")
    refused = _call("POST", "/questionnaire/interviews", body)
    _CURRENT["user"] = _user("PROFESSOR")
    allowed = _call("POST", "/questionnaire/interviews", body)

    assert refused.status_code == 422, refused.text
    assert "different questionnaire" in refused.json()["detail"]
    assert allowed.status_code == 201, allowed.text
    assert creatable.questionnaireinterview.rows[-1].questionnaireId == W3


def test_two_sittings_for_the_same_artisans_on_two_instruments_do_not_fold(
    creatable: _Db,
) -> None:
    """The global `artisanSetKey` unique meant one artisan set held exactly one interview
    repository-wide, so the same five artisans sitting again for the 3rd instrument FOLDED into
    their 2nd-workshop sitting and the new answers landed on the old interview."""
    asyncio.run(
        creatable.questionnaireinterview.create(
            data={"id": "iv-w2", "title": "Second workshop", "questionnaireId": W2,
                  "artisanSetKey": "a1"}
        )
    )

    response = _call("POST", "/questionnaire/interviews", _interview_body(questionnaireId=W3))

    assert response.status_code == 201, response.text
    # The lookup asks about BOTH columns; on the set key alone it would have found iv-w2.
    lookups = creatable.reads("questionnaireinterview", "find_first")
    assert any(
        kwargs.get("where", {}).get("questionnaireId") == W3
        and kwargs.get("where", {}).get("artisanSetKey") == "a1"
        for kwargs in lookups
    ), lookups
    assert len(creatable.questionnaireinterview.rows) == 2


def test_an_answer_for_another_instruments_question_is_refused(creatable: _Db) -> None:
    """A client holding a stale section list would otherwise write its answers onto a sitting of
    the other instrument, where the matrix, the document and the CSV all file them under headings
    they were never given under."""
    body = _interview_body(
        questionnaireId=W3, responses=[{"questionId": "w2-q1", "answerText": "Vikram."}]
    )

    response = _call("POST", "/questionnaire/interviews", body)

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "different questionnaire" in detail
    assert "w2-q1" in detail


# --- Structure writes -----------------------------------------------------------------------------


def test_a_section_reorder_spanning_two_instruments_is_refused(db: _Db) -> None:
    """`@@unique([questionnaireId, sortOrder])` is scoped, so a list spanning two instruments would
    renumber BOTH of them 1..n — silently reordering an instrument the caller never opened."""
    response = _call(
        "POST", "/questionnaire/sections/reorder", {"sectionIds": ["w2-a", "w3-a"]}
    )

    assert response.status_code == 422, response.text
    assert "one questionnaire" in response.json()["detail"]
    assert db.questionnairesection.writes == []


def test_a_question_cannot_be_re_parented_into_another_instrument(db: _Db) -> None:
    response = _call("PATCH", "/questionnaire/questions/w2-q1", {"sectionId": "w3-a"})

    assert response.status_code == 422, response.text
    assert "cannot be moved between questionnaires" in response.json()["detail"]
    assert db.questionnairequestion.writes == []


def test_a_question_reorder_into_another_instruments_section_is_refused(db: _Db) -> None:
    """`reorder_questions` writes `section_question_data(section)` onto every id it is handed, with
    no check that the question belongs there — it is a re-parent wearing a reorder's name, and the
    ONE route that can produce a row with questionnaireId=W2 and sectionId=<a W3 section>. Such a
    row is invisible to BOTH instruments' `section_payloads` while its answers stay alive under
    `Restrict`."""
    response = _call(
        "POST", "/questionnaire/questions/reorder", {"sectionId": "w3-a", "questionIds": ["w2-q1"]}
    )

    assert response.status_code == 422, response.text
    assert "cannot be moved between questionnaires" in response.json()["detail"]
    # THE WRITE, not just the status code: a test that asserted only the 422 would pass on a
    # handler that had already moved the rows before raising.
    assert db.questionnairequestion.writes == []


def test_a_question_reorder_returns_the_sections_of_its_own_instrument(db: _Db) -> None:
    """The happy path, which is also the TypeError guard. `section_payloads` gained a required
    first positional; this route used to call it with no arguments at all, which is a 500 on every
    question reorder from both clients and from the builder's drag path."""
    response = _call(
        "POST", "/questionnaire/questions/reorder", {"sectionId": "w3-a", "questionIds": ["w3-q1"]}
    )

    assert response.status_code == 200, response.text
    assert [s["code"] for s in response.json()] == ["A"]
    assert all(s["questionnaireId"] == W3 for s in response.json())


def test_adding_a_section_without_naming_an_instrument_lands_on_the_default(db: _Db) -> None:
    """`QuestionnaireSectionCreate.questionnaireId` is OPTIONAL on purpose: the backend deploys days
    before the builders that learn to send it, and a required field would 422 every un-updated
    builder's "add a section" in the meantime."""
    response = _call("POST", "/questionnaire/sections", {"code": "Z", "title": "New"})

    assert response.status_code == 201, response.text
    assert response.json()["questionnaireId"] == W2


def test_a_new_sections_position_is_computed_within_its_own_instrument(db: _Db) -> None:
    """`next_section_sort_order` read the GLOBAL max before this change, so the first section added
    to the 3rd instrument got max(every instrument)+1 — 4 here, against an instrument whose sections
    run 1..1. That collides with nothing, so nothing raises; it just puts the section in a silently
    wrong place, and the gap compounds."""
    response = _call(
        "POST", "/questionnaire/sections", {"questionnaireId": W3, "code": "B", "title": "Second"}
    )

    assert response.status_code == 201, response.text
    assert response.json()["sortOrder"] == 2


def test_a_duplicate_section_code_in_one_instrument_is_a_409_not_a_500(db: _Db) -> None:
    response = _call(
        "POST", "/questionnaire/sections", {"questionnaireId": W2, "code": "A", "title": "Again"}
    )

    assert response.status_code == 409, response.text
    assert "already exists" in response.json()["detail"]


# --- Tasks ----------------------------------------------------------------------------------------


def test_task_sections_must_belong_to_the_workshops_instrument(db: _Db) -> None:
    """`resolve_scope` driven directly rather than through POST /tasks: the rule under test is the
    subset rule, and the create route's assignee/rank plumbing is a different test's subject.

    `sectionIds` is a plain `String[]` column, so Postgres stores an id from any instrument at all
    and the task then scopes to sections that are not on the form its assignee opens — assigned,
    reporting against a denominator of zero, impossible to complete."""
    from app.api.routes.tasks import resolve_scope

    asyncio.run(db.workshop.create(data={"id": "w-1", "title": "Almora", "questionnaireId": W2, **_WORKSHOP_DATES}))

    with pytest.raises(HTTPException) as excinfo:
        asyncio.run(
            resolve_scope(
                workshop_id="w-1",
                record_types=[],
                artisan_ids=[],
                section_ids=["w3-a"],
                target_count=None,
            )
        )

    assert excinfo.value.status_code == 422
    assert "belong to a different questionnaire" in str(excinfo.value.detail)

    # The workshop's own instrument is accepted, so the rule narrows nothing it should not.
    scope = asyncio.run(
        resolve_scope(
            workshop_id="w-1",
            record_types=[],
            artisan_ids=[],
            section_ids=["w2-a"],
            target_count=None,
        )
    )
    assert scope.sectionIds == ["w2-a"]


def test_task_sections_may_not_span_two_instruments_even_with_no_workshop(db: _Db) -> None:
    from app.api.routes.tasks import resolve_scope

    with pytest.raises(HTTPException) as excinfo:
        asyncio.run(
            resolve_scope(
                workshop_id=None,
                record_types=[],
                artisan_ids=[],
                section_ids=["w2-a", "w3-a"],
                target_count=None,
            )
        )

    assert excinfo.value.status_code == 422
    assert "one questionnaire" in str(excinfo.value.detail)


# --- Rebinding a workshop -------------------------------------------------------------------------


@pytest.fixture()
def rebindable(db: _Db, monkeypatch: pytest.MonkeyPatch) -> _Db:
    async def noop(*_args: Any, **_kwargs: Any) -> None:
        return None

    monkeypatch.setattr(workshops, "hydrate_relations", noop)
    asyncio.run(db.workshop.create(data={"id": "w-1", "title": "Almora", "questionnaireId": W2, **_WORKSHOP_DATES}))
    return db


def test_rebinding_a_workshop_with_open_section_tasks_is_a_409(rebindable: _Db) -> None:
    asyncio.run(
        rebindable.assignedtask.create(
            data={"id": "t-1", "workshopId": "w-1", "status": "OPEN", "recordTypes": [],
                  "sectionIds": ["w2-a"], "description": "Answer section A"}
        )
    )

    response = _call("PUT", "/workshops/w-1/questionnaire", {"questionnaireId": W3})

    assert response.status_code == 409, response.text
    assert "open task(s)" in response.json()["detail"]
    # The binding did NOT move: a refusal that had already written would be worse than no refusal.
    assert rebindable.workshop.rows[0].questionnaireId == W2


def test_a_rebind_with_reassign_cancels_a_questionnaire_only_task_and_empties_a_mixed_one(
    rebindable: _Db,
) -> None:
    """`_clear_task_sections` clears, never remaps: there is no honest mapping from "section D of
    the 2nd workshop's instrument" to any section of the 3rd's — the codes collide and the meanings
    do not. And a task left with NO work in it is cancelled rather than left standing, because
    `resolve_scope` refuses to create one and `_derived_target` contributes nothing for one, so it
    would report progress against a denominator of zero forever."""
    asyncio.run(
        rebindable.assignedtask.create(
            data={"id": "t-only", "workshopId": "w-1", "status": "OPEN", "recordTypes": [],
                  "sectionIds": ["w2-a"], "description": "Answer section A"}
        )
    )
    asyncio.run(
        rebindable.assignedtask.create(
            data={"id": "t-mixed", "workshopId": "w-1", "status": "OPEN",
                  "recordTypes": ["product"], "sectionIds": ["w2-a"], "description": "Products too"}
        )
    )

    response = _call(
        "PUT", "/workshops/w-1/questionnaire", {"questionnaireId": W3, "reassignTasks": True}
    )

    assert response.status_code == 200, response.text
    tasks = {row.id: row for row in rebindable.assignedtask.rows}
    assert tasks["t-only"].status == "CANCELLED"
    assert "Cancelled automatically" in tasks["t-only"].description
    assert tasks["t-mixed"].status == "OPEN"
    assert tasks["t-mixed"].sectionIds == {"set": []}
    assert tasks["t-mixed"].recordTypes == ["product"]
    assert rebindable.workshop.rows[0].questionnaireId == W3

    # THE INVARIANT `resolve_scope` enforces on create and which nothing else was enforcing here.
    for task in rebindable.assignedtask.rows:
        if task.status in {"OPEN", "IN_PROGRESS"}:
            sections = task.sectionIds
            sections = sections.get("set", []) if isinstance(sections, dict) else sections
            assert (task.recordTypes or []) or sections, f"{task.id} has no work left in it"


def test_detaching_a_workshop_is_allowed_and_leaves_it_on_the_default(rebindable: _Db) -> None:
    response = _call("PUT", "/workshops/w-1/questionnaire", {"questionnaireId": None})

    assert response.status_code == 200, response.text
    assert rebindable.workshop.rows[0].questionnaireId is None


def test_a_retired_instrument_cannot_be_bound_to_a_workshop(rebindable: _Db) -> None:
    asyncio.run(rebindable.questionnaire.update(where={"id": W3}, data={"isActive": False}))

    response = _call("PUT", "/workshops/w-1/questionnaire", {"questionnaireId": W3})

    assert response.status_code == 422, response.text
    assert "retired" in response.json()["detail"]
