import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, HTTPException

from access_roster_fakes import FakeTable, PassThroughTx, install
from app.api.router import api_router
from app.api.routes import questionnaire, workshops
from app.core import deps

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"

LOCATION = {"latitude": 22.57, "longitude": 88.36, "state": "West Bengal", "district": "Kolkata"}

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


class _Db(PassThroughTx):
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



def test_listing_sections_narrows_to_the_resolved_instrument(db: _Db) -> None:
    response = _call("GET", f"/questionnaire/sections?questionnaireId={W3}")

    assert response.status_code == 200, response.text
    assert [s["code"] for s in response.json()] == ["A"]
    wheres = [kwargs.get("where") for kwargs in db.reads("questionnairesection", "find_many")]
    assert any(where and where.get("questionnaireId") == W3 for where in wheres), wheres


def test_listing_sections_with_no_instrument_named_lands_on_the_default(db: _Db) -> None:
    response = _call("GET", "/questionnaire/sections")

    assert response.status_code == 200, response.text
    assert [s["code"] for s in response.json()] == ["A", "B"]


def test_the_interview_list_filter_is_a_plain_key_that_survives_a_free_text_search(db: _Db) -> None:
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
    response = _call("GET", f"/questionnaire/completion?questionnaireId={W3}")

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["questionnaireId"] == W3
    assert payload["questionnaireTitle"] == "3rd Craft Toolkit Workshop"
    codes = [s["code"] for s in payload["sections"]]
    assert codes == ["A"]
    assert len(codes) == len(set(codes)), codes



def _interview_body(**extra: Any) -> dict[str, Any]:
    return {"title": "A sitting", "location": LOCATION, "artisanIds": ["a1"], **extra}


@pytest.fixture()
def creatable(db: _Db, monkeypatch: pytest.MonkeyPatch) -> _Db:
    async def noop(*_args: Any, **_kwargs: Any) -> None:
        return None

    monkeypatch.setattr(questionnaire, "hydrate_relations", noop)
    monkeypatch.setattr(questionnaire, "replace_interview_artisans", noop)
    asyncio.run(db.artisan.create(data={"id": "a1"}))
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
    asyncio.run(
        creatable.questionnaireinterview.create(
            data={"id": "iv-w2", "title": "Second workshop", "questionnaireId": W2,
                  "artisanSetKey": "a1"}
        )
    )

    response = _call("POST", "/questionnaire/interviews", _interview_body(questionnaireId=W3))

    assert response.status_code == 201, response.text
    lookups = creatable.reads("questionnaireinterview", "find_first")
    assert any(
        kwargs.get("where", {}).get("questionnaireId") == W3
        and kwargs.get("where", {}).get("artisanSetKey") == "a1"
        for kwargs in lookups
    ), lookups
    assert len(creatable.questionnaireinterview.rows) == 2


def test_an_answer_for_another_instruments_question_is_refused(creatable: _Db) -> None:
    body = _interview_body(
        questionnaireId=W3, responses=[{"questionId": "w2-q1", "answerText": "Vikram."}]
    )

    response = _call("POST", "/questionnaire/interviews", body)

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "different questionnaire" in detail
    assert "w2-q1" in detail



def test_a_section_reorder_spanning_two_instruments_is_refused(db: _Db) -> None:
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
    response = _call(
        "POST", "/questionnaire/questions/reorder", {"sectionId": "w3-a", "questionIds": ["w2-q1"]}
    )

    assert response.status_code == 422, response.text
    assert "cannot be moved between questionnaires" in response.json()["detail"]
    assert db.questionnairequestion.writes == []


def test_a_question_reorder_returns_the_sections_of_its_own_instrument(db: _Db) -> None:
    response = _call(
        "POST", "/questionnaire/questions/reorder", {"sectionId": "w3-a", "questionIds": ["w3-q1"]}
    )

    assert response.status_code == 200, response.text
    assert [s["code"] for s in response.json()] == ["A"]
    assert all(s["questionnaireId"] == W3 for s in response.json())


def test_adding_a_section_without_naming_an_instrument_lands_on_the_default(db: _Db) -> None:
    response = _call("POST", "/questionnaire/sections", {"code": "Z", "title": "New"})

    assert response.status_code == 201, response.text
    assert response.json()["questionnaireId"] == W2


def test_a_new_sections_position_is_computed_within_its_own_instrument(db: _Db) -> None:
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



def test_task_sections_must_belong_to_the_workshops_instrument(db: _Db) -> None:
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
    assert "open task" in response.json()["detail"]
    assert rebindable.workshop.rows[0].questionnaireId == W2


def test_a_rebind_with_reassign_cancels_a_questionnaire_only_task_and_empties_a_mixed_one(
    rebindable: _Db,
) -> None:
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
    assert "Cancelled because this workshop's questionnaire changed" in tasks["t-only"].description
    assert tasks["t-mixed"].status == "OPEN"
    assert tasks["t-mixed"].sectionIds == {"set": []}
    assert tasks["t-mixed"].recordTypes == ["product"]
    assert rebindable.workshop.rows[0].questionnaireId == W3

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
