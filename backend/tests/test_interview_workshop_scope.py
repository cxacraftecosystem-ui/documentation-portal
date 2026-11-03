import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeTable, PassThroughTx, install
from app.api.router import api_router
from app.api.routes import questionnaire
from app.core import deps
from app.services.record_filters import UNASSIGNED_WORKSHOP

W_FIRST = "wk_first_workshop"
W_SECOND = "wk_second_workshop"
W_THIRD = "wk_third_workshop"


def _user(role: str = "ADMIN") -> SimpleNamespace:
    return SimpleNamespace(
        id="u1",
        email="u1@example.test",
        name="Test",
        role=role,
        canManageCrafts=False,
        canManageWorkshops=False,
        canManageQuestionnaire=False,
        canDownloadDataset=False,
        canReview=False,
        canViewProvenance=False,
    )


class _Logged(FakeTable):
    def __init__(self, name: str, log: list, seed: list[dict[str, Any]] | None = None) -> None:
        super().__init__({}, seed, ())
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
    def __init__(self, **seeds: list[dict[str, Any]]) -> None:
        object.__setattr__(self, "log", [])
        object.__setattr__(self, "_tables", {})
        for name, rows in seeds.items():
            self._tables[name] = _Logged(name, self.log, rows)

    def __getattr__(self, name: str) -> Any:
        tables = object.__getattribute__(self, "_tables")
        if name not in tables:
            tables[name] = _Logged(name, object.__getattribute__(self, "log"), [])
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


def _call(path: str) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://scope.test") as client:
            return await client.get(f"/api{path}")

    return asyncio.run(run())


def _interview_where(db: _Db) -> dict[str, Any]:
    reads = db.reads("questionnaireinterview", "count")
    assert reads, "the interview list never reached its count"
    return reads[-1].get("where") or {}


def _selects(where: Any, row: dict[str, Any]) -> bool:
    if not where:
        return True
    for key, expected in where.items():
        if key == "AND":
            if not all(_selects(clause, row) for clause in expected):
                return False
        elif key == "OR":
            if not any(_selects(clause, row) for clause in expected):
                return False
        else:
            actual = row.get(key)
            if isinstance(expected, dict):
                if "in" in expected:
                    if actual not in expected["in"]:
                        return False
                elif "contains" in expected:
                    if str(expected["contains"]).lower() not in str(actual or "").lower():
                        return False
                else:
                    raise NotImplementedError(f"evaluator cannot match {key}={expected!r}")
            elif actual != expected:
                return False
    return True


_ROWS = [
    {"id": "iv-1", "title": "First workshop sitting", "workshopId": W_FIRST},
    {"id": "iv-2", "title": "Second workshop sitting", "workshopId": W_SECOND},
    {"id": "iv-3", "title": "Third workshop sitting", "workshopId": W_THIRD},
    {"id": "iv-4", "title": "Unlinked sitting", "workshopId": None},
]


def _selected_ids(where: dict[str, Any]) -> list[str]:
    return [row["id"] for row in _ROWS if _selects(where, row)]


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch):
    fake = _Db()
    install(monkeypatch, fake)
    _CURRENT["user"] = _user()
    yield fake
    _CURRENT["user"] = None



def test_one_workshop_in_scope_lists_only_that_workshops_interviews(db: _Db) -> None:
    response = _call(f"/questionnaire/interviews?workshopIds={W_THIRD}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert _selected_ids(where) == ["iv-3"], where


def test_several_workshops_are_one_scope_and_one_request(db: _Db) -> None:
    response = _call(f"/questionnaire/interviews?workshopIds={W_FIRST},{W_SECOND}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert _selected_ids(where) == ["iv-1", "iv-2"], where


def test_no_scope_lists_every_workshop_rather_than_none_of_them(db: _Db) -> None:
    response = _call("/questionnaire/interviews")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert "workshopId" not in where, where
    assert "workshopId" not in str(where.get("AND")), where
    assert _selected_ids(where) == ["iv-1", "iv-2", "iv-3", "iv-4"], where


def test_a_workshop_outside_the_scope_does_not_leak_into_the_page(db: _Db) -> None:
    response = _call(f"/questionnaire/interviews?workshopIds={W_FIRST}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    for row in _ROWS:
        if row["workshopId"] != W_FIRST:
            assert not _selects(where, row), f"{row['id']} leaked past the scope: {where}"


def test_the_scope_rides_the_read_predicate_rather_than_displacing_it(
    db: _Db, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def _restricted(_user: Any, owner_field: str = "createdById") -> dict[str, Any]:
        return {"status": "APPROVED"}

    monkeypatch.setattr(questionnaire, "viewable_where", _restricted)
    response = _call(f"/questionnaire/interviews?workshopIds={W_FIRST}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    conjuncts = where.get("AND") or []
    assert {"status": "APPROVED"} in conjuncts, f"the read predicate was dropped: {where}"
    assert any("workshopId" in str(clause) for clause in conjuncts), (
        f"the scope was dropped: {where}"
    )
    assert not _selects(where, {"id": "iv-x", "workshopId": W_FIRST, "status": "PENDING"}), where



def test_the_reserved_none_names_the_interviews_linked_to_no_workshop(db: _Db) -> None:
    response = _call(f"/questionnaire/interviews?workshopIds={UNASSIGNED_WORKSHOP}")

    where = _interview_where(db)
    assert response.status_code == 200, response.text
    assert _selected_ids(where) == ["iv-4"], where


def test_a_real_workshop_and_the_reserved_none_together_widen_to_both(db: _Db) -> None:
    response = _call(f"/questionnaire/interviews?workshopIds={W_THIRD},{UNASSIGNED_WORKSHOP}")

    where = _interview_where(db)
    assert response.status_code == 200, response.text
    assert _selected_ids(where) == ["iv-3", "iv-4"], where


def test_both_spellings_the_clients_build_are_accepted(db: _Db) -> None:
    joined = _call(f"/questionnaire/interviews?workshopIds={W_FIRST},{W_SECOND}")
    assert joined.status_code == 200, joined.text
    from_joined = _selected_ids(_interview_where(db))

    repeated = _call(f"/questionnaire/interviews?workshopIds={W_FIRST}&workshopIds={W_SECOND}")
    assert repeated.status_code == 200, repeated.text
    from_repeated = _selected_ids(_interview_where(db))

    assert from_joined == from_repeated == ["iv-1", "iv-2"]



def test_the_singular_workshop_id_still_narrows_for_every_existing_caller(db: _Db) -> None:
    response = _call(f"/questionnaire/interviews?workshopId={W_SECOND}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert where.get("workshopId") == W_SECOND, where
    assert _selected_ids(where) == ["iv-2"], where


def test_singular_and_plural_sent_together_both_narrow(db: _Db) -> None:
    response = _call(
        f"/questionnaire/interviews?workshopId={W_SECOND}&workshopIds={W_FIRST},{W_THIRD}"
    )

    where = _interview_where(db)
    assert response.status_code == 200, response.text
    assert _selected_ids(where) == [], where


def test_the_scope_survives_a_free_text_search(db: _Db) -> None:
    response = _call(f"/questionnaire/interviews?workshopIds={W_THIRD}&search=sitting")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert "OR" in where, "the free-text search went missing"
    assert any("workshopId" in str(clause) for clause in where.get("AND") or []), (
        f"the workshop scope was dropped by the search: {where}"
    )
    assert _selected_ids(where) == ["iv-3"], where



def test_the_page_is_capped_at_a_hundred_and_the_total_says_so(db: _Db) -> None:
    refused = _call("/questionnaire/interviews?pageSize=250")
    assert refused.status_code == 422, "the ceiling is not enforced at the signature"

    response = _call("/questionnaire/interviews?pageSize=100")
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["pageSize"] == 100
    assert "total" in body and "pages" in body, body
    take = [kwargs.get("take") for kwargs in db.reads("questionnaireinterview", "find_many")]
    assert take and all(value <= 100 for value in take), take
