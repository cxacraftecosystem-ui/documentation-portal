"""THE FIRST FIVE AND A HALF HOURS OF EVERY WORKSHOP DAY, AND THE WORD "ENDED".

WHAT WENT WRONG IN THE FIELD. The owner opened a record form for the 3rd Toolkit Workshop at 00:55
IST on 2026-09-14 — the workshop's opening morning, running 14–23 Sept — and the form said:

    "This workshop ended on 24 Sept 2026. Saving now is recorded as a late submission."

It had not ended. It had not started. Two independent defects produced one sentence, and this file
pins both of them:

  1. ``describe_workshop_submission`` compared ``datetime.now(UTC)`` against a startDate stored as a
     day. 00:55 IST is 19:25Z on the PREVIOUS day, so ``now < start`` fired and the workshop reported
     out of window — for the first 5h30m of every IST day of its run, and it stayed "in window" for
     the last 5h30m of the day after it ended. See WORKSHOP_TZ in
     ``app/services/workshop_access.py``.
  2. ``outOfWindow`` is TWO-SIDED — before the start OR after the end — and the clients printed the
     after-the-end half of it for both (``frontend/components/forms/WorkshopSelect.tsx:287``,
     ``android/.../MainActivity.kt:3856``). So a workshop that had not opened was announced as over.

NO DATABASE. The two delegates this service reads are recording stubs and the service is the real
one; the whole subject here is arithmetic on dates, and a live Postgres would only make the clock
harder to pin. Every case names its instant explicitly rather than leaning on ``datetime.now`` —
a window test that passes because of when it was run is not a test.
"""

import asyncio
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

import pytest

from app.services import workshop_access
from app.services.workshop_access import describe_workshop_submission

IST = timezone(timedelta(hours=5, minutes=30))


class _Row:
    """A stored row answering ``None`` for any column a test did not set."""

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


# The production row, byte for byte. The columns are DAYS: the form writes the start at midnight and
# the end at the last millisecond, and both are meant as IST calendar days (14 Sept .. 23 Sept).
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
    """An instant named on the clock the researcher is actually reading, then stated in UTC."""
    return datetime(year, month, day, hour, minute, tzinfo=IST).astimezone(UTC)


# --------------------------------------------------------------------------------------
# The regression itself
# --------------------------------------------------------------------------------------


def test_a_workshop_opening_this_morning_is_in_window_at_five_past_midnight_ist(monkeypatch):
    """THE BUG THIS FILE EXISTS FOR, pinned to the real numbers off the real row.

    2026-09-13T19:25Z IS 00:55 IST on 14 Sept, the workshop's first day. Nothing about it is
    outside the window, and no sentence about it may contain the word "ended".
    """
    moment = datetime(2026, 9, 13, 19, 25, tzinfo=UTC)
    assert moment.astimezone(IST).strftime("%Y-%m-%d %H:%M") == "2026-09-14 00:55", "the premise"

    check = _check(monkeypatch, THIRD_TOOLKIT, moment)

    assert check.isOver is False
    assert check.outOfWindow is False
    assert check.notYetStarted is False
    assert check.needsAdminApproval is False


def test_the_last_five_and_a_half_hours_of_an_ist_day_still_belong_to_that_day(monkeypatch):
    """The other side of the same skew, and the one that would have gone unnoticed for longer.

    23:30 IST on the 24th is 18:00Z on the 24th — a UTC comparison against an end stored at
    23:59:59.999 on the 23rd gets this one right by accident. 05:00 IST on the 24th is 23:30Z on the
    23rd, which the old arithmetic called in-window: the day after a workshop closed, its approval
    gate was off until half past ten in the morning.
    """
    assert _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 24, 5, 0)).isOver is True
    assert _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 23, 23, 59)).isOver is False


# --------------------------------------------------------------------------------------
# Three states, and the two booleans that spell them
# --------------------------------------------------------------------------------------


def test_not_started_in_window_and_over_are_three_distinguishable_answers(monkeypatch):
    """The whole point of the fix: the wire tells the three apart with no new field.

    ``isOver`` is the after-the-end half alone; ``outOfWindow`` is the union. A client reading
    ``outOfWindow && !isOver`` therefore has "has not started yet" exactly, which is what
    ``WorkshopSubmissionCheck.notYetStarted`` computes and what both clients now branch on.
    """
    early = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 12, 10, 0))
    during = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 18, 10, 0))
    over = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 25, 10, 0))

    assert (early.outOfWindow, early.isOver, early.notYetStarted) == (True, False, True)
    assert (during.outOfWindow, during.isOver, during.notYetStarted) == (False, False, False)
    assert (over.outOfWindow, over.isOver, over.notYetStarted) == (True, True, False)


def test_the_payload_gains_no_key_so_no_client_dto_has_to_change(monkeypatch):
    """The wire is the contract with two clients that ship on their own schedules.

    ``frontend/lib/types.ts`` and ``android/.../data/ApiModels.kt`` are hand-maintained mirrors of
    this dict. A thirteenth key would have to land in all three files at once, and a client that
    missed the edit would read the missing discriminator as ``false`` and print the old wrong
    sentence anyway. ``notYetStarted`` is therefore a derived property, never a key.
    """
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


# --------------------------------------------------------------------------------------
# The window is inclusive at BOTH ends, on IST days
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "day, expected_over, expected_not_started",
    [
        (13, False, True),  # the eve — genuinely early
        (14, False, False),  # opening day, in full
        (23, False, False),  # closing day, in full: a workshop ending today has not ended
        (24, True, False),  # the morning after
    ],
)
def test_both_boundary_days_belong_to_the_workshop_in_full(monkeypatch, day, expected_over, expected_not_started):
    """Midday on each boundary day, which is where a researcher actually stands when they type.

    Android states the same invariant as a sentence in the sibling repository —
    ``a workshop ending today has not ended`` in
    ``designer-portal/android/.../ui/WorkshopOptionsTest.kt`` — and its reason is this one: one day
    out marks a workshop the researcher is standing in as over, and the confirmation dialog then
    asks them to confirm a late submission that is not late.
    """
    check = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, day, 12, 0))

    assert check.isOver is expected_over
    assert check.notYetStarted is expected_not_started


def test_an_end_stored_at_midnight_keeps_its_last_day_too(monkeypatch):
    """Not every row is written at 23:59:59.999, and the rule may not depend on that.

    The old ``end + timedelta(days=1)`` was inclusive only by arithmetic accident: a row whose
    endDate landed at 00:00 on the 23rd (a date-only import, an Android client that sent a bare day)
    got a window that closed at midnight ON the 23rd, losing the whole of the last day. Naming the
    DAY makes the inclusive rule true for both shapes.
    """
    midnight_row = _Row(
        id="wsp_4",
        title="A workshop imported as bare dates",
        startDate=datetime(2026, 9, 14, 0, 0, tzinfo=UTC),
        endDate=datetime(2026, 9, 23, 0, 0, tzinfo=UTC),
    )

    assert _check(monkeypatch, midnight_row, _at_ist(2026, 9, 23, 18, 0)).isOver is False
    assert _check(monkeypatch, midnight_row, _at_ist(2026, 9, 24, 0, 5)).isOver is True


def test_a_day_stamped_with_an_ist_offset_is_the_same_day_as_one_stamped_utc(monkeypatch):
    """A boundary column is the day the researcher TYPED, whatever offset the writer attached.

    Both clients can send an offset-bearing stamp. ``2026-09-23T23:59:59.999+05:30`` and
    ``...+00:00`` are both "the 23rd" to the person who entered them, and normalising either into
    some other zone before reading the date is precisely the move that turns the 23rd into the 24th
    on screen.
    """
    ist_row = _Row(
        id="wsp_5",
        title="Entered on a handset",
        startDate=datetime(2026, 9, 14, 0, 0, tzinfo=IST),
        endDate=datetime(2026, 9, 23, 23, 59, 59, 999000, tzinfo=IST),
    )

    assert _check(monkeypatch, ist_row, _at_ist(2026, 9, 14, 0, 55)).outOfWindow is False
    assert _check(monkeypatch, ist_row, _at_ist(2026, 9, 23, 22, 0)).isOver is False
    assert _check(monkeypatch, ist_row, _at_ist(2026, 9, 24, 9, 0)).isOver is True


# --------------------------------------------------------------------------------------
# What the window does and does not decide
# --------------------------------------------------------------------------------------


def test_a_workshop_with_no_dates_at_all_is_never_declared_out_of_window(monkeypatch):
    """A blank column is not evidence of lateness, and must not pin real fieldwork to PENDING.

    Same ruling as Android's ``a workshop with no dates at all is not declared over``.
    """
    undated = _Row(id="wsp_6", title="Dates never recorded")

    check = _check(monkeypatch, undated, _at_ist(2026, 9, 18, 10, 0))

    assert (check.isOver, check.outOfWindow, check.needsAdminApproval) == (False, False, False)


def test_an_early_submission_is_gated_exactly_as_a_late_one_is(monkeypatch):
    """Being outside the window is what trips the approval gate, on either side of it.

    Documenting a workshop that has not happened is at least as much a claim needing review as
    documenting one that has, so the flag stays — only the SENTENCE the clients print about it
    changes ("early", not "ended"). The old expression, which ANDed in a start date, is also why a
    workshop carrying only an endDate could read over-but-not-late and waive the gate entirely.
    """
    end_only = _Row(id="wsp_7", title="Only an end recorded", endDate=datetime(2026, 9, 23, 23, 59, 59, tzinfo=UTC))

    early = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 10, 9, 0))
    assert (early.notYetStarted, early.needsAdminApproval) == (True, True)

    over = _check(monkeypatch, end_only, _at_ist(2026, 9, 30, 9, 0))
    assert (over.isOver, over.outOfWindow, over.needsAdminApproval) == (True, True, True)


def test_an_admin_sees_the_true_window_and_is_still_never_flagged(monkeypatch):
    """Admins ARE the approval authority; gating them behind their own approval is a deadlock.

    Unchanged by this fix, and asserted here because the fix rewrote the expression that feeds it.
    """
    check = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 30, 9, 0), user=_admin())

    assert check.isOver is True
    assert check.outOfWindow is True
    assert check.needsAdminApproval is False
    assert check.metadata == {}, "and nothing is stamped on an admin's record"


def test_the_stamp_records_the_window_verdict_the_gate_was_taken_from(monkeypatch):
    """The record carries WHY it was pinned, and the early case must be legible in it later.

    ``extraMetadata.workshopSubmission`` is what keeps a flagged record PENDING across every
    subsequent edit (``stamp_workshop_submission``), so its ``outOfWindow`` has to agree with the
    check that produced it rather than be recomputed by whoever reads it next.
    """
    early = _check(monkeypatch, THIRD_TOOLKIT, _at_ist(2026, 9, 10, 9, 0))

    stamp = early.metadata["workshopSubmission"]
    assert stamp["outOfWindow"] is True
    assert stamp["needsAdminApproval"] is True
    assert stamp["windowStart"] == "2026-09-14T00:00:00+00:00"
    assert stamp["windowEnd"] == "2026-09-23T23:59:59.999000+00:00"
