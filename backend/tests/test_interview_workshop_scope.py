"""The workshop scope on ``GET /questionnaire/interviews``.

WHAT GOES WRONG WITHOUT THESE, reported from the field on 2026-09-16: at the third workshop, the
Android questionnaire surfaces listed the interviews from the first and second workshops too. The
list route had a singular ``workshopId`` filter that worked perfectly and a client that never sent
it, so every browse and update picker on the handset saw the whole repository and the researcher had
to recognise which of three workshops a sitting came from by reading its title.

The route now also takes the PLURAL ``workshopIds`` from the shared filter vocabulary, because the
scope control on both clients is a multi-select and the singular cannot spell what it selects. These
tests fix the four behaviours a scope has to get right — one workshop, several, none at all, and one
that is out of scope — plus the two composition hazards this particular handler is built out of.

WHY THEY ASSERT THE ``where`` AND NOT THE ROWS, mostly. Like ``test_questionnaire_scope.py`` beside
them these drive the REAL router over HTTP with ``db`` replaced by a recording fake, so what is
checked is the predicate the handler actually built rather than a helper that was told the answer.
The shared ``FakeTable`` matcher understands equality, ``in`` and ``contains`` — not ``AND``/``OR``
nesting — so a test that asserted on rows it returned would go green over a scope that matched
nothing. Where the question is genuinely about WHICH ROWS SURVIVE, the recorded predicate is
evaluated against real-shaped rows by ``_selects`` below, which does understand the nesting. NOTHING
TOUCHES A DATABASE: ``backend/.env`` points at the live production pooler.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeTable, install
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
    """A ``FakeTable`` that records the kwargs of every read, so the ``where`` can be asserted."""

    def __init__(self, name: str, log: list, seed: list[dict[str, Any]] | None = None) -> None:
        super().__init__({}, seed, ())
        self._name = name
        self._log = log

    # ``where`` stays positional-or-keyword on all of these: handler code passes it by keyword but
    # ``FakeTable.update`` calls ``find_unique`` positionally, and a keyword-only override turns
    # every write into a TypeError.

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
    """``db``, auto-vivifying an empty logged table for anything asked of it.

    The list handler hydrates six relations per page, and a strict fake would turn each of those
    into a per-test registration exercise that tests nothing about the scope.
    """

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
    """The ``where`` the list handler built, taken off the count that precedes the page.

    From the COUNT rather than the find_many because ``count_and_page`` issues the two together over
    the same dictionary: were they ever to differ, the total would describe a different set from the
    rows on screen, and asserting the count's predicate is what would catch it.
    """
    reads = db.reads("questionnaireinterview", "count")
    assert reads, "the interview list never reached its count"
    return reads[-1].get("where") or {}


def _selects(where: Any, row: dict[str, Any]) -> bool:
    """Does this Prisma ``where`` select this row?

    A deliberately small evaluator covering exactly the shapes THIS handler emits — nested ``AND``
    and ``OR``, ``{"in": [...]}``, ``None`` for a null column, ``contains`` for the free-text
    search, and plain equality. It exists because the shared ``FakeTable`` matcher stops at flat
    equality, so "which interviews does this scope actually return" cannot be asked of the fake:
    every ``AND``/``OR`` predicate selects nothing there, and a leak test built on it would pass
    over a route that had no scope at all. An unknown operator RAISES rather than being skipped, so
    this can never quietly answer "selected" for a predicate it does not understand.
    """
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


#: One sitting per workshop plus one that names no workshop at all — the four cases every scope on
#: this route has to place. The unlinked one is not hypothetical: ``workshopId`` is nullable because
#: every interview recorded before the column existed has none.
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


# --- The four scopes ------------------------------------------------------------------------------


def test_one_workshop_in_scope_lists_only_that_workshops_interviews(db: _Db) -> None:
    """DEFECT (2) at its narrowest: the third workshop must not show the first two's sittings."""
    response = _call(f"/questionnaire/interviews?workshopIds={W_THIRD}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert _selected_ids(where) == ["iv-3"], where


def test_several_workshops_are_one_scope_and_one_request(db: _Db) -> None:
    """The reason the PLURAL had to exist at all.

    The scope control is a multi-select on both clients (``Set<String>`` on Android), so two ticked
    workshops is an ordinary state and not an edge case. Under the singular filter alone this
    request could only have been served by sending the first id and silently answering a narrower
    question than the one asked, or by firing one request per ticked workshop — the
    N-requests-for-N-ids shape ``list_artisans`` rejects in its own note. Both workshops' sittings
    come back; the third's does not.
    """
    response = _call(f"/questionnaire/interviews?workshopIds={W_FIRST},{W_SECOND}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert _selected_ids(where) == ["iv-1", "iv-2"], where


def test_no_scope_lists_every_workshop_rather_than_none_of_them(db: _Db) -> None:
    """ABSENT MEANS EVERY WORKSHOP, and it must not be spelled the same way as an empty selection.

    This is the distinction ``resolve_workshop_ids`` exists to draw, asserted at the route: a
    parser that read "the control is at its default" as "the user asked for nothing" would open
    every browse screen empty over a full repository, which looks exactly like having no data. The
    unlinked sitting is included too — nothing was asked about workshops, so nothing about
    workshops may narrow the page.
    """
    response = _call("/questionnaire/interviews")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert "workshopId" not in where, where
    assert "workshopId" not in str(where.get("AND")), where
    assert _selected_ids(where) == ["iv-1", "iv-2", "iv-3", "iv-4"], where


def test_a_workshop_outside_the_scope_does_not_leak_into_the_page(db: _Db) -> None:
    """THE LEAK TEST. A scope naming one workshop must be unsatisfiable by any other.

    Asserted over rows rather than by reading the clause, because the ways this leaks are ways the
    clause still LOOKS right: an ``OR`` that widens instead of narrowing, or a scope parked at a key
    something else overwrites, both leave a predicate with the correct workshop id sitting in it.
    Every interview from a workshop that was not asked for — and the unlinked one, which belongs to
    no workshop and so answers no workshop's question — must be absent.
    """
    response = _call(f"/questionnaire/interviews?workshopIds={W_FIRST}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    for row in _ROWS:
        if row["workshopId"] != W_FIRST:
            assert not _selects(where, row), f"{row['id']} leaked past the scope: {where}"


def test_the_scope_rides_the_read_predicate_rather_than_displacing_it(
    db: _Db, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The row filter and the workshop scope must BOTH apply, not whichever was written last.

    ``viewable_where`` returns an empty predicate today — reading the repository is open to every
    signed-in account — so no workshop is hidden from anybody and this cannot be tested by asking
    for a forbidden one: there are none, and a test that pretended otherwise would be asserting a
    policy this repository does not have. What CAN be tested, and is the thing that would actually
    leak the day a read rule appears, is whether the scope COMPOSES with that predicate or
    displaces it. So a non-empty one is substituted here and both are required to survive into the
    same ``AND``. Without this the route could pass every other test in this file while quietly
    dropping the row filter the moment a workshop scope was sent.
    """

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
    # And in the only terms that matter: a row the scope admits but the read rule forbids stays out.
    assert not _selects(where, {"id": "iv-x", "workshopId": W_FIRST, "status": "PENDING"}), where


# --- The reserved value, and the two spellings ----------------------------------------------------


def test_the_reserved_none_names_the_interviews_linked_to_no_workshop(db: _Db) -> None:
    """``none`` is a value of the scope, not an id — the same word ``list_artisans`` already answers
    to, which is why it had to arrive here in the same shape.

    Without it a workshop filter is unusable as a scope control: tick every workshop and the
    sittings recorded before interviews had a workshop column vanish, with nothing on screen to say
    they were excluded. Sent as a bare ``workshopId`` it would test the column against the literal
    string "none" and match nothing at all, which is why the singular could never have carried it.
    """
    response = _call(f"/questionnaire/interviews?workshopIds={UNASSIGNED_WORKSHOP}")

    where = _interview_where(db)
    assert response.status_code == 200, response.text
    assert _selected_ids(where) == ["iv-4"], where


def test_a_real_workshop_and_the_reserved_none_together_widen_to_both(db: _Db) -> None:
    """The case that forces the clause under ``AND`` rather than a plain key: it is an ``OR``."""
    response = _call(f"/questionnaire/interviews?workshopIds={W_THIRD},{UNASSIGNED_WORKSHOP}")

    where = _interview_where(db)
    assert response.status_code == 200, response.text
    assert _selected_ids(where) == ["iv-3", "iv-4"], where


def test_both_spellings_the_clients_build_are_accepted(db: _Db) -> None:
    """Android comma-joins the scope (``toQueryCsv``); the web repeats the parameter.

    A scope that quietly covered everything because it was spelled the other way would look exactly
    like the control not working — which is indistinguishable, on the handset, from defect (2) never
    having been fixed.
    """
    joined = _call(f"/questionnaire/interviews?workshopIds={W_FIRST},{W_SECOND}")
    assert joined.status_code == 200, joined.text
    from_joined = _selected_ids(_interview_where(db))

    repeated = _call(f"/questionnaire/interviews?workshopIds={W_FIRST}&workshopIds={W_SECOND}")
    assert repeated.status_code == 200, repeated.text
    from_repeated = _selected_ids(_interview_where(db))

    assert from_joined == from_repeated == ["iv-1", "iv-2"]


# --- Backward compatibility, and the composition hazard this handler is built out of --------------


def test_the_singular_workshop_id_still_narrows_for_every_existing_caller(db: _Db) -> None:
    """The plural ADDS to this route; it replaces nothing. Every caller that predates it — the web's
    interview lookup and every saved link — sends the singular and must be unaffected."""
    response = _call(f"/questionnaire/interviews?workshopId={W_SECOND}")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert where.get("workshopId") == W_SECOND, where
    assert _selected_ids(where) == ["iv-2"], where


def test_singular_and_plural_sent_together_both_narrow(db: _Db) -> None:
    """The rule ``list_artisans`` states for its own pair, asserted here so the two cannot drift.

    They intersect rather than one winning: a singular naming the second workshop under a plural
    naming the first and third selects nothing, which is the only answer true to both.
    """
    response = _call(
        f"/questionnaire/interviews?workshopId={W_SECOND}&workshopIds={W_FIRST},{W_THIRD}"
    )

    where = _interview_where(db)
    assert response.status_code == 200, response.text
    assert _selected_ids(where) == [], where


def test_the_scope_survives_a_free_text_search(db: _Db) -> None:
    """THE REGRESSION THIS ROUTE IS SHAPED TO CAUSE, and the reason the clause goes under ``AND``.

    ``where["OR"]`` is ASSIGNED OUTRIGHT by the free-text search. A workshop scope parked there is
    dropped the instant the researcher types in the search box — which is the browse screen's
    ordinary state, so the scope would appear to work right up until somebody searched, and then
    silently list every workshop's interviews again. Both must be present, and the search must not
    be able to re-admit a workshop the scope excluded.
    """
    response = _call(f"/questionnaire/interviews?workshopIds={W_THIRD}&search=sitting")

    assert response.status_code == 200, response.text
    where = _interview_where(db)
    assert "OR" in where, "the free-text search went missing"
    assert any("workshopId" in str(clause) for clause in where.get("AND") or []), (
        f"the workshop scope was dropped by the search: {where}"
    )
    # Every row's title contains "sitting", so a scope that had been dropped would select all four.
    assert _selected_ids(where) == ["iv-3"], where


# --- The page-size ceiling ------------------------------------------------------------------------


def test_the_page_is_capped_at_a_hundred_and_the_total_says_so(db: _Db) -> None:
    """THE CAP IS REAL, SHARED, AND REPORTED — the three facts that stop a client lying about it.

    Both clients ask for ``pageSize=100`` because that IS the ceiling: ``le=100`` on the signature
    refuses more with a 422, and ``normalize_pagination`` clamps to ``MAX_PAGE_SIZE`` besides, so
    even a caller that got past the signature could not obtain a bigger page. Raising it is
    therefore not a change to this route — the clamp is shared by every list route in the app.

    What the route DOES hand a client is ``total``: the count of every matching interview, not of
    the page. So ``len(items) < total`` is an exact, server-supplied truncation signal, and a
    client showing a capped list has no excuse for showing it silently. Asserted here because the
    honesty notice on both clients is built on this field being the FULL count rather than the
    page's.
    """
    refused = _call("/questionnaire/interviews?pageSize=250")
    assert refused.status_code == 422, "the ceiling is not enforced at the signature"

    response = _call("/questionnaire/interviews?pageSize=100")
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["pageSize"] == 100
    assert "total" in body and "pages" in body, body
    take = [kwargs.get("take") for kwargs in db.reads("questionnaireinterview", "find_many")]
    assert take and all(value <= 100 for value in take), take
