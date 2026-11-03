import asyncio
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

import pytest

from app.services import workshop_access
from app.services.workshop_access import describe_workshop_submission

IST = timezone(timedelta(hours=5, minutes=30))


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _WorkshopDelegate:
    def __init__(self, row: Any):
        self.row = row

    async def find_unique(self, **_kwargs):
        return self.row


class _AssignmentDelegate:
    def __init__(self, rows: list[Any] | None = None):
        self.rows = rows or []

    async def find_many(self, **_kwargs):
        return list(self.rows)


class _Db:
    def __init__(self, workshop: Any, assignments: list[Any] | None = None):
        self.workshop = _WorkshopDelegate(workshop)
        self.workshopassignment = _AssignmentDelegate(assignments)


def _researcher():
    return _Row(id="usr_7", name="R. Menon", role="RESEARCHER")


def _admin():
    return _Row(id="usr_1", name="The Owner", role="ADMIN")


THIRD_TOOLKIT = _Row(
    id="wsp_3",
    title="Shristi O Anusandhan 3rd Toolkit Workshop for Mud Craft Tradition",
    startDate=datetime(2026, 9, 14, 0, 0, 0, tzinfo=UTC),
    endDate=datetime(2026, 9, 23, 23, 59, 59, 999000, tzinfo=UTC),
    date=datetime(2026, 9, 14, 0, 0, 0, tzinfo=UTC),
)


def _check(monkeypatch, workshop: Any, when: datetime, user: Any = None, assignments=None):
    monkeypatch.setattr(workshop_access, "db", _Db(workshop, assignments))
    return asyncio.run(describe_workshop_submission(user or _researcher(), workshop.id, when=when))


def _at_ist(year: int, month: int, day: int, hour: int = 0, minute: int = 0) -> datetime:
    return datetime(year, month, day, hour, minute, tzinfo=IST).astimezone(UTC)



def test_a_workshop_opening_this_morning_is_in_window_at_five_past_midnight_ist(monkeypatch):
    moment = datetime(2026, 9, 13, 19, 25, tzinfo=UTC)
    assert moment.astimezone(IST).strftime("%Y-%m-%d %H:%M") == "2026-09-14 00:55", "the premise"

    check = _check(monkeypatch, THIRD_TOOLKIT, moment)

    assert check.isOver is False
    assert check.outOfWindow is False
    assert check.notYetStarted is False
    assert check.needsAdminApproval is False


def test_the_last_five_and_a_half_hours_of_an_ist_day_still_belong_to_that_day(monkeypatch):
    assert _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 24, 5, 0)).isOver is True
    assert _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 23, 23, 59)).isOver is False



def test_not_started_in_window_and_over_are_three_distinguishable_answers(monkeypatch):
    early = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 12, 10, 0))
    during = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 18, 10, 0))
    over = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 25, 10, 0))

    assert (early.outOfWindow, early.isOver, early.notYetStarted) == (True, False, True)
    assert (during.outOfWindow, during.isOver, during.notYetStarted) == (False, False, False)
    assert (over.outOfWindow, over.isOver, over.notYetStarted) == (True, True, False)


def test_the_payload_gains_no_key_so_no_client_dto_has_to_change(monkeypatch):
    check = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 12, 10, 0))

    assert set(check.payload()) == {
        "workshopId",
        "title",
        "endDate",
        "isOver",
        "outOfWindow",
        "needsAdminApproval",
        "assigned",
        "canSubmit",
        "accessLevel",
        "requestStatus",
        "restricted",
        "canEdit",
    }



@pytest.mark.parametrize(
    "day, expected_over, expected_not_started",
    [
        (13, False, True),
        (14, False, False),
        (23, False, False),
        (24, True, False),
    ],
)
def test_both_boundary_days_belong_to_the_workshop_in_full(monkeypatch, day, expected_over, expected_not_started):
    check = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, day, 12, 0))

    assert check.isOver is expected_over
    assert check.notYetStarted is expected_not_started


def test_an_end_stored_at_midnight_keeps_its_last_day_too(monkeypatch):
    midnight_row = _Row(
        id="wsp_4",
        title="A workshop imported as bare dates",
        startDate=datetime(2026, 9, 14, 0, 0, tzinfo=UTC),
        endDate=datetime(2026, 9, 23, 0, 0, tzinfo=UTC),
    )

    assert _check(monkeypatch, midnight_row, _at_ist(2026, 9, 23, 18, 0)).isOver is False
    assert _check(monkeypatch, midnight_row, _at_ist(2026, 9, 24, 0, 5)).isOver is True


def test_a_day_stamped_with_an_ist_offset_is_the_same_day_as_one_stamped_utc(monkeypatch):
    ist_row = _Row(
        id="wsp_5",
        title="Entered on a handset",
        startDate=datetime(2026, 9, 14, 0, 0, tzinfo=IST),
        endDate=datetime(2026, 9, 23, 23, 59, 59, 999000, tzinfo=IST),
    )

    assert _check(monkeypatch, ist_row, _at_ist(2026, 9, 14, 0, 55)).outOfWindow is False
    assert _check(monkeypatch, ist_row, _at_ist(2026, 9, 23, 22, 0)).isOver is False
    assert _check(monkeypatch, ist_row, _at_ist(2026, 9, 24, 9, 0)).isOver is True



def test_a_workshop_with_no_dates_at_all_is_never_declared_out_of_window(monkeypatch):
    undated = _Row(id="wsp_6", title="Dates never recorded")

    check = _check(monkeypatch, undated, _at_ist(2026, 9, 18, 10, 0))

    assert (check.isOver, check.outOfWindow, check.needsAdminApproval) == (False, False, False)


def test_an_early_submission_is_gated_exactly_as_a_late_one_is(monkeypatch):
    end_only = _Row(id="wsp_7", title="Only an end recorded", endDate=datetime(2026, 9, 23, 23, 59, 59, tzinfo=UTC))

    early = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 10, 9, 0))
    assert (early.notYetStarted, early.needsAdminApproval) == (True, True)

    over = _check(monkeypatch, end_only, _at_ist(2026, 9, 30, 9, 0))
    assert (over.isOver, over.outOfWindow, over.needsAdminApproval) == (True, True, True)


def test_an_admin_sees_the_true_window_and_is_still_never_flagged(monkeypatch):
    check = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 30, 9, 0), user=_admin())

    assert check.isOver is True
    assert check.outOfWindow is True
    assert check.needsAdminApproval is False
    assert check.metadata == {}, "and nothing is stamped on an admin's record"


def test_the_stamp_records_the_window_verdict_the_gate_was_taken_from(monkeypatch):
    early = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 10, 9, 0))

    stamp = early.metadata["workshopSubmission"]
    assert stamp["outOfWindow"] is True
    assert stamp["needsAdminApproval"] is True
    assert stamp["windowStart"] == "2026-09-14T00:00:00+00:00"
    assert stamp["windowEnd"] == "2026-09-23T23:59:59.999000+00:00"
