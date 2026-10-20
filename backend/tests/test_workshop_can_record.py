"""``canRecord`` on ``GET /workshops`` rows: which workshops the caller may record in.

Both clients pre-fill the questionnaire's workshop with the most recent row carrying it, and the
Android read cache keeps the list, so the flag must agree with the write gate
(``enforce_workshop_submission``) exactly. No database: the assignment delegate is a stub.
"""

import asyncio
from typing import Any

from app.services import workshop_access
from app.services.workshop_access import recordable_workshop_ids


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _AssignmentDelegate:
    def __init__(self, rows: list[Any]):
        self.rows = rows
        self.calls = 0

    async def find_many(self, where=None, **_kwargs):
        self.calls += 1
        wanted = set(where["workshopId"]["in"])
        return [r for r in self.rows if r.workshopId in wanted]


class _Db:
    def __init__(self, rows: list[Any]):
        self.workshopassignment = _AssignmentDelegate(rows)


RESEARCHER = _Row(id="usr_7", role="RESEARCHER")
ADMIN = _Row(id="usr_1", role="ADMIN")

ROWS = [
    # ws_curated: an admin put somebody else on it, so it is closed to the researcher.
    _Row(workshopId="ws_curated", userId="usr_9", status="GRANTED", accessLevel="CONTRIBUTE", assignedById="usr_1"),
    # ws_mine: curated, and the researcher holds a CONTRIBUTE grant.
    _Row(workshopId="ws_mine", userId="usr_9", status="GRANTED", accessLevel="CONTRIBUTE", assignedById="usr_1"),
    _Row(workshopId="ws_mine", userId="usr_7", status="GRANTED", accessLevel="CONTRIBUTE", assignedById="usr_1"),
    # ws_view: curated, the researcher may only read.
    _Row(workshopId="ws_view", userId="usr_7", status="GRANTED", accessLevel="VIEW", assignedById="usr_1"),
    # ws_pending: open (no admin-assigned row), the researcher's own request is pending.
    _Row(workshopId="ws_pending", userId="usr_7", status="PENDING", accessLevel="CONTRIBUTE", assignedById=None),
]
IDS = ["ws_open", "ws_curated", "ws_mine", "ws_view", "ws_pending"]


def test_recordable_matches_the_write_gate_for_a_researcher(monkeypatch):
    fake = _Db(ROWS)
    monkeypatch.setattr(workshop_access, "db", fake)
    got = asyncio.run(recordable_workshop_ids(RESEARCHER, IDS))
    assert got == {"ws_open", "ws_mine", "ws_pending"}
    assert fake.workshopassignment.calls == 1, "one query for the whole page"


def test_each_flag_agrees_with_resolve_workshop_access(monkeypatch):
    monkeypatch.setattr(workshop_access, "db", _Db(ROWS))
    got = asyncio.run(recordable_workshop_ids(RESEARCHER, IDS))

    class _One:
        def __init__(self, wid):
            self.wid = wid

        async def find_many(self, where=None, **_kwargs):
            return [r for r in ROWS if r.workshopId == where["workshopId"]]

    for wid in IDS:
        monkeypatch.setattr(workshop_access, "db", type("D", (), {"workshopassignment": _One(wid)})())
        access = asyncio.run(workshop_access.resolve_workshop_access(RESEARCHER, wid))
        assert (wid in got) == access.at_least("CONTRIBUTE"), wid


def test_an_admin_may_record_everywhere_without_a_query(monkeypatch):
    fake = _Db(ROWS)
    monkeypatch.setattr(workshop_access, "db", fake)
    assert asyncio.run(recordable_workshop_ids(ADMIN, IDS)) == set(IDS)
    assert fake.workshopassignment.calls == 0


def test_an_empty_page_asks_nothing(monkeypatch):
    fake = _Db(ROWS)
    monkeypatch.setattr(workshop_access, "db", fake)
    assert asyncio.run(recordable_workshop_ids(RESEARCHER, [])) == set()
    assert fake.workshopassignment.calls == 0
