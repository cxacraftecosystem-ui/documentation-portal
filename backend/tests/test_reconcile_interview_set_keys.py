import asyncio
import sys
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

import scripts.reconcile_interview_set_keys as reconcile

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"


def _interview(iv_id: str, questionnaire_id: str, artisan_ids: list[str], *, day: int = 1,
               stored_key: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        id=iv_id,
        questionnaireId=questionnaire_id,
        artisanSetKey=stored_key,
        createdAt=datetime(2026, 6, day, 9, 0, tzinfo=UTC),
        artisans=[SimpleNamespace(artisanId=aid) for aid in artisan_ids],
    )


class _InterviewDelegate:
    def __init__(self, rows: list, log: list, *, reconstructed: int = 0) -> None:
        self._rows = rows
        self._log = log
        self._reconstructed = reconstructed

    async def count(self, where=None):
        return self._reconstructed

    async def find_many(self, **_kwargs):
        return list(self._rows)

    async def update_many(self, where=None, data=None):
        self._log.append(("interview.update_many", where, data))
        return len(self._rows)

    async def update(self, where=None, data=None):
        self._log.append(("interview.update", where, data))
        return None

    async def delete(self, where=None):
        self._log.append(("interview.delete", where))
        return None


class _MediaDelegate:
    def __init__(self, counts: dict[str, int], log: list) -> None:
        self._counts = counts
        self._log = log

    async def count(self, where=None):
        return self._counts.get((where or {}).get("questionnaireInterviewId"), 0)

    async def update_many(self, where=None, data=None):
        self._log.append(("media.update_many", where, data))
        return 0


class _ResponseDelegate:
    def __init__(self, rows: list, log: list) -> None:
        self._rows = rows
        self._log = log

    async def find_many(self, where=None, **_kwargs):
        interview_id = (where or {}).get("interviewId")
        return [r for r in self._rows if r.interviewId == interview_id]

    async def find_first(self, where=None, **_kwargs):
        for row in self._rows:
            if row.interviewId == (where or {}).get("interviewId") and row.questionId == (
                where or {}
            ).get("questionId"):
                return row
        return None

    async def update(self, where=None, data=None):
        self._log.append(("response.update", where, data))
        return None


class _Counted:
    def __init__(self, n: int) -> None:
        self.n = n

    async def count(self, where=None):
        return self.n


class _Db:
    def __init__(self, interviews: list, *, media_counts=None, responses=None, recordings: int = 0,
                 reconstructed: int = 0) -> None:
        self.log: list = []
        self.questionnaireinterview = _InterviewDelegate(interviews, self.log, reconstructed=reconstructed)
        self.mediafile = _MediaDelegate(media_counts or {}, self.log)
        self.questionnaireresponse = _ResponseDelegate(responses or [], self.log)
        self.questionnairerecording = _Counted(recordings)


def _run(monkeypatch, interviews: list, *, execute: bool, **db_kwargs) -> _Db:
    fake = _Db(interviews, **db_kwargs)

    async def _noop() -> None:
        return None

    monkeypatch.setattr(reconcile, "db", fake)
    monkeypatch.setattr(reconcile, "connect_db", _noop)
    monkeypatch.setattr(reconcile, "disconnect_db", _noop)
    argv = ["reconcile_interview_set_keys"] + (["--execute"] if execute else [])
    monkeypatch.setattr(sys, "argv", argv)
    asyncio.run(reconcile.main())
    return fake


def _written_keys(fake: _Db) -> dict[str, object]:
    return {
        call[1]["id"]: call[2]["artisanSetKey"]
        for call in fake.log
        if call[0] == "interview.update"
    }


def _deleted_ids(fake: _Db) -> list[str]:
    return [call[1]["id"] for call in fake.log if call[0] == "interview.delete"]



def test_reconcile_groups_by_instrument(monkeypatch):
    second = _interview("iv-w2", W2, ["a2", "a1"], day=1)
    third = _interview("iv-w3", W3, ["a1", "a2"], day=8)
    fake = _run(monkeypatch, [second, third], execute=True)

    assert _deleted_ids(fake) == []
    assert not [c for c in fake.log if c[0] == "media.update_many"]
    assert _written_keys(fake) == {"iv-w2": "a1,a2", "iv-w3": "a1,a2"}


def test_two_sittings_on_two_instruments_are_not_even_reported_as_duplicates(monkeypatch, capsys):
    _run(
        monkeypatch,
        [_interview("iv-w2", W2, ["a1"]), _interview("iv-w3", W3, ["a1"])],
        execute=False,
    )
    out = capsys.readouterr().out
    assert "duplicate_set_groups=0" in out
    assert "consolidate set" not in out


def test_a_real_duplicate_on_one_instrument_is_still_consolidated(monkeypatch):
    keeper = _interview("iv-keep", W3, ["a1", "a2"], day=1)
    loser = _interview("iv-drop", W3, ["a2", "a1"], day=2)
    fake = _run(
        monkeypatch,
        [keeper, loser],
        execute=True,
        media_counts={"iv-keep": 4, "iv-drop": 1},
    )

    assert _deleted_ids(fake) == ["iv-drop"]
    assert _written_keys(fake) == {"iv-keep": "a1,a2"}
    moved = [c for c in fake.log if c[0] == "media.update_many"]
    assert moved, "the loser's media must be re-pointed at the survivor before it is deleted"
    assert all(c[2].get("questionnaireInterviewId") == "iv-keep" or
               c[2].get("linkedRecordId") == "iv-keep" for c in moved)



def test_the_write_back_puts_a_string_in_the_text_column_not_the_slot_tuple(monkeypatch):
    fake = _run(monkeypatch, [_interview("iv-1", W3, ["b", "a"])], execute=True)
    written = _written_keys(fake)["iv-1"]
    assert written == "a,b"
    assert isinstance(written, str)


def test_the_instrument_is_never_written_back_onto_the_interview(monkeypatch):
    fake = _run(monkeypatch, [_interview("iv-1", W3, ["a"])], execute=True)
    for call in fake.log:
        if call[0] in ("interview.update", "interview.update_many"):
            assert set((call[2] or {}).keys()) == {"artisanSetKey"}


def test_an_interview_with_no_artisans_is_left_out_of_the_slots_entirely(monkeypatch):
    fake = _run(
        monkeypatch,
        [_interview("iv-empty", W3, []), _interview("iv-real", W3, ["a"])],
        execute=True,
    )
    assert _deleted_ids(fake) == []
    assert _written_keys(fake) == {"iv-real": "a"}



def test_a_dry_run_writes_nothing_at_all(monkeypatch):
    keeper = _interview("iv-keep", W3, ["a1"], day=1)
    loser = _interview("iv-drop", W3, ["a1"], day=2)
    fake = _run(monkeypatch, [keeper, loser], execute=False, media_counts={"iv-keep": 2})
    assert fake.log == []



def test_the_slot_is_the_exact_column_pair_the_database_enforces(monkeypatch):
    _correct, by_slot = reconcile.group_by_slot(
        [
            _interview("iv-w2", W2, ["a1", "a2"]),
            _interview("iv-w3", W3, ["a2", "a1"]),
            _interview("iv-w3b", W3, ["a1", "a2"]),
        ]
    )
    assert set(by_slot) == {(W2, "a1,a2"), (W3, "a1,a2")}
    assert by_slot[(W2, "a1,a2")] == ["iv-w2"]
    assert by_slot[(W3, "a1,a2")] == ["iv-w3", "iv-w3b"]


def test_a_missing_instrument_raises_rather_than_folding_every_row_into_one_slot(monkeypatch):
    row = SimpleNamespace(
        id="iv-1",
        artisanSetKey=None,
        createdAt=datetime(2026, 6, 1, tzinfo=UTC),
        artisans=[SimpleNamespace(artisanId="a1")],
    )
    with pytest.raises(AttributeError):
        reconcile.group_by_slot([row])



@pytest.mark.parametrize("v2_rows", [{"recordings": 1}, {"reconstructed": 1}])
def test_execute_refuses_once_questionnaire_v2_rows_exist(monkeypatch, capsys, v2_rows):
    keeper = _interview("iv-keep", W3, ["a1"], day=1)
    loser = _interview("iv-drop", W3, ["a1"], day=2)
    fake = _run(monkeypatch, [keeper, loser], execute=True, media_counts={"iv-keep": 2}, **v2_rows)
    assert fake.log == []
    assert "REFUSED" in capsys.readouterr().out
