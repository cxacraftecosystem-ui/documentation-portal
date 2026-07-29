"""The evidence ladder that maps a record with no ``workshopId`` to the workshop it belongs to.

Every rule below is one a refactor could break silently, and each one broke a real screen when it was
absent:

  * ambiguity must LOSE and must STOP the ladder. A rung naming two workshops falling through to the
    date window would pick one of the two, and the row would then look mapped;
  * a rung with NO evidence must fall through, or an interview with no artisans could never be mapped
    at all;
  * a workshop's window must cover the whole of a single day, or a recording stamped at 18:40 falls
    outside a workshop whose only date is that morning's midnight;
  * nothing may ever be written over an existing link.

These are pure functions over values, which is why they are testable without a database — the reads
live in ``run_ladder`` and the decisions live here.
"""

from datetime import UTC, datetime, timedelta

from app.services.workshop_inference import (
    REASON_AMBIGUOUS,
    REASON_NO_EVIDENCE,
    RUNG_ARTISANS,
    RUNG_PARENT,
    RUNG_WINDOW,
    build_windows,
    decide,
    distinct,
    stamp_of,
    title_of,
    windows_containing,
)


class FakeWorkshop:
    def __init__(self, id, title="A workshop", date=None, startDate=None, endDate=None):
        self.id = id
        self.title = title
        self.date = date
        self.startDate = startDate
        self.endDate = endDate


class FakeRow:
    def __init__(self, **columns):
        for key, value in columns.items():
            setattr(self, key, value)


def at(day: int, hour: int = 12) -> datetime:
    """A moment in June 2026, the month the live corpus was recorded in."""
    return datetime(2026, 6, day, hour, tzinfo=UTC)


# The live workshop: 17-27 June 2026, endDate set to the last millisecond of the 27th.
LIVE = FakeWorkshop(
    "w-live",
    title="2nd Toolkit Workshop",
    date=at(17, 0),
    startDate=at(17, 0),
    endDate=datetime(2026, 6, 27, 23, 59, 59, 999000, tzinfo=UTC),
)


# --- Windows --------------------------------------------------------------------------------------


def test_a_workshop_with_only_the_legacy_date_covers_that_whole_day():
    """The column arrived before ``startDate``/``endDate`` did, and a record stamped in the evening has
    to fall inside a workshop whose only date is that morning's midnight."""
    [window] = build_windows([FakeWorkshop("w1", date=at(17, 0))])
    assert window.contains(at(17, 0))
    assert window.contains(at(17, 18))
    assert window.contains(at(17, 23))
    assert not window.contains(at(18, 12))


def test_startdate_is_preferred_over_the_legacy_date():
    [window] = build_windows([FakeWorkshop("w1", date=at(1), startDate=at(17, 0), endDate=at(20))])
    assert window.start == at(17, 0)
    # The whole of the END DAY is inside the window, so the exclusive end is the next midnight.
    assert window.end == at(21, 0)


def test_an_end_date_at_midnight_still_covers_its_whole_day():
    """The failure this closes was silent and client-specific. Android submits dates as start-of-day
    instants, so a workshop it created has ``endDate`` at MIDNIGHT — and reading that as an instant
    excluded the workshop's entire final day from the WINDOW rung."""
    [window] = build_windows([FakeWorkshop("w1", startDate=at(17, 0), endDate=at(27, 0))])
    assert window.contains(at(27, 0))
    assert window.contains(at(27, 18))
    assert window.contains(at(27, 23))
    assert not window.contains(at(28, 0))


def test_a_single_day_workshop_has_a_window_at_all():
    """``workshops.normalize_workshop_dates`` copies ``startDate`` into ``endDate`` whenever the payload
    omits one, so ``endDate == startDate`` is the COMMON shape, not an edge case. Reading it as an instant
    produced a zero-length window in which the WINDOW rung could never fire — for an Android-created
    single-day workshop the rung simply did not exist."""
    [window] = build_windows([FakeWorkshop("w1", startDate=at(17, 0), endDate=at(17, 0))])
    assert window.end > window.start
    assert window.contains(at(17, 9))
    assert window.contains(at(17, 22))
    assert not window.contains(at(18, 9))


def test_the_last_millisecond_end_the_web_form_sends_does_not_leak_into_the_next_day():
    """The web form sends ``…T23:59:59.999``. Adding a day to that raw instant — which is what the
    late-submission check does, deliberately, to avoid flagging a 23:59 save — would claim almost all of
    the following day for CLASSIFICATION purposes, which is a different question."""
    end = datetime(2026, 6, 27, 23, 59, 59, 999000, tzinfo=UTC)
    [window] = build_windows([FakeWorkshop("w1", startDate=at(17, 0), endDate=end)])
    assert window.contains(at(27, 23))
    assert not window.contains(at(28, 0))
    assert not window.contains(at(28, 12))


def test_a_workshop_with_no_date_at_all_is_skipped_rather_than_made_infinite():
    """A window with no start cannot contain anything; starting it at the epoch would make it contain
    EVERYTHING, which is the one outcome worse than leaving the rows unmapped."""
    assert build_windows([FakeWorkshop("w1")]) == []


def test_an_end_before_the_start_is_read_as_a_single_day_not_reversed():
    [window] = build_windows([FakeWorkshop("w1", startDate=at(17, 0), endDate=at(10))])
    assert window.start == at(17, 0)
    assert window.end == at(17, 0) + timedelta(days=1)


def test_a_naive_stamp_does_not_raise_when_compared_with_an_aware_window():
    """One legacy row with a naive stamp must not take the whole admin report down."""
    [window] = build_windows([FakeWorkshop("w1", startDate=datetime(2026, 6, 17), endDate=None)])
    assert window.contains(at(17, 6))


def test_windows_containing_reports_every_overlapping_workshop():
    other = FakeWorkshop("w-other", startDate=at(20), endDate=at(22))
    windows = build_windows([LIVE, other])
    assert windows_containing(at(19), windows) == ["w-live"]
    assert sorted(windows_containing(at(21), windows)) == ["w-live", "w-other"]
    assert windows_containing(at(30), windows) == []
    assert windows_containing(None, windows) == []


# --- The ladder -----------------------------------------------------------------------------------


def test_the_first_rung_with_evidence_decides():
    plan = decide("r1", "A row", [(RUNG_PARENT, ["w-parent"]), (RUNG_WINDOW, ["w-window"])])
    assert plan.workshopId == "w-parent"
    assert plan.rung == RUNG_PARENT


def test_a_rung_with_no_evidence_falls_through_to_the_next():
    plan = decide("r1", "A row", [(RUNG_ARTISANS, []), (RUNG_WINDOW, ["w-window"])])
    assert plan.workshopId == "w-window"
    assert plan.rung == RUNG_WINDOW


def test_an_ambiguous_rung_stops_the_ladder_instead_of_being_broken_by_a_weaker_one():
    """The rule this whole module turns on. An interview whose artisans span two workshops needs a
    person; letting the date window break the tie would map it to one of the two and it would then be
    indistinguishable from a row that had real evidence."""
    plan = decide(
        "r1",
        "A row",
        [(RUNG_ARTISANS, ["w-a", "w-b"]), (RUNG_WINDOW, ["w-live"])],
    )
    assert plan.workshopId is None
    assert plan.rung is None
    assert plan.reason == REASON_AMBIGUOUS
    assert plan.candidates == ["w-a", "w-b"]


def test_repeated_evidence_for_one_workshop_is_not_ambiguous():
    """Three artisans all belonging to the same workshop is agreement, not conflict — and the artisan
    map holds a workshop twice for anyone carried by both the column and the roster."""
    plan = decide("r1", "A row", [(RUNG_ARTISANS, ["w-live", "w-live", "w-live"])])
    assert plan.workshopId == "w-live"
    assert plan.rung == RUNG_ARTISANS


def test_no_evidence_anywhere_is_reported_as_such_and_never_guessed():
    plan = decide("r1", "A row", [(RUNG_PARENT, [None]), (RUNG_ARTISANS, []), (RUNG_WINDOW, [])])
    assert plan.workshopId is None
    assert plan.reason == REASON_NO_EVIDENCE
    assert plan.candidates == []


def test_distinct_drops_blanks_and_keeps_first_seen_order():
    assert distinct(["b", None, "a", "", "b"]) == ["b", "a"]


# --- Stamps and titles ----------------------------------------------------------------------------


def test_recordedat_beats_createdat_because_it_is_when_the_work_happened():
    """A record captured offline and synced days later has two different days on it, and the workshop
    was running on the first."""
    row = FakeRow(recordedAt=at(19), createdAt=at(30))
    assert stamp_of(row, "recordedAt", "createdAt") == at(19)


def test_a_missing_stamp_falls_through_to_the_next_column():
    row = FakeRow(recordedAt=None, interviewDate=None, createdAt=at(19))
    assert stamp_of(row, "recordedAt", "interviewDate", "createdAt") == at(19)
    assert stamp_of(FakeRow(createdAt=None), "createdAt") is None


def test_title_falls_back_rather_than_rendering_an_empty_row():
    assert title_of(FakeRow(productName="", craftName="Dabu"), "productName", "craftName") == "Dabu"
    assert title_of(FakeRow(name=None), "name") == "Untitled record"


# --- The shapes the live corpus actually had -------------------------------------------------------


def test_the_live_interviews_resolve_by_artisan_consensus():
    """Twenty-four of the twenty-five unassigned interviews had artisans, and every one of those
    artisans belonged to the single workshop — by BOTH the column and the roster."""
    windows = build_windows([LIVE])
    plan = decide(
        "i1",
        "Om Prakash, Akola, Dabu Hand block Printing, Rajasthan",
        [
            (RUNG_ARTISANS, ["w-live", "w-live"]),
            (RUNG_WINDOW, windows_containing(at(19), windows)),
        ],
    )
    assert (plan.workshopId, plan.rung) == ("w-live", RUNG_ARTISANS)


def test_the_one_artisan_less_interview_resolves_by_its_date():
    """"D and N Sanjay Kumar" had zero artisans, so the artisan rung had nothing to say — and falling
    through to the window is exactly why an empty rung must not stop the ladder."""
    windows = build_windows([LIVE])
    plan = decide(
        "i2",
        "D and N Sanjay Kumar",
        [(RUNG_ARTISANS, []), (RUNG_WINDOW, windows_containing(at(24), windows))],
    )
    assert (plan.workshopId, plan.rung) == ("w-live", RUNG_WINDOW)


def test_a_record_captured_after_the_workshop_ended_stays_unmapped():
    """The honest answer, and the reason "only one workshop exists so everything is its" is not a rung:
    132 of the unassigned media files were parentless, and some were uploaded days after the workshop
    closed."""
    windows = build_windows([LIVE])
    plan = decide(
        "m1",
        "misc.jpg",
        [(RUNG_PARENT, [None]), (RUNG_WINDOW, windows_containing(at(30), windows))],
    )
    assert plan.workshopId is None
    assert plan.reason == REASON_NO_EVIDENCE
