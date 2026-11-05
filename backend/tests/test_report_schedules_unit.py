from __future__ import annotations

import asyncio
import email
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest

from app.core.config import get_settings
from app.services import mailer
from app.services import report_schedules as rs

KOLKATA = "Asia/Kolkata"


def _zone_or_skip(name: str) -> None:
    try:
        ZoneInfo(name)
    except Exception:  # noqa: BLE001 - no tz database on this host
        pytest.skip(f"no tz database entry for {name} here")


def _row(**extra):
    data = {
        "id": "rs1",
        "savedViewId": "v1",
        "weekday": 0,
        "hourLocal": 8,
        "timezone": KOLKATA,
        "options": {},
        "pausedAt": None,
        "lastSlotAt": None,
        "retryAfter": None,
        "lastSentAt": None,
        "lastError": None,
        "lastFileName": None,
        "lastFileBytes": None,
        "lastAttached": None,
        "createdAt": datetime(2026, 11, 1, tzinfo=UTC),
        "savedView": SimpleNamespace(name="Bagru media", path="by-workshop/w1"),
    }
    data.update(extra)
    return SimpleNamespace(**data)


def test_the_latest_monday_slot_in_india_is_0230_utc():
    monday_morning = datetime(2026, 11, 9, 3, 0, tzinfo=UTC)
    assert rs.latest_slot(monday_morning, 0, 8, KOLKATA) == datetime(2026, 11, 9, 2, 30, tzinfo=UTC)
    assert rs.next_slot(monday_morning, 0, 8, KOLKATA) == datetime(2026, 11, 16, 2, 30, tzinfo=UTC)


def test_before_the_hour_the_slot_is_last_week():
    just_before = datetime(2026, 11, 9, 2, 29, tzinfo=UTC)
    assert rs.latest_slot(just_before, 0, 8, KOLKATA) == datetime(2026, 11, 2, 2, 30, tzinfo=UTC)
    assert rs.next_slot(just_before, 0, 8, KOLKATA) == datetime(2026, 11, 9, 2, 30, tzinfo=UTC)


def test_the_slot_lands_on_the_local_weekday_not_the_utc_one():
    sunday_utc = datetime(2026, 11, 8, 20, 0, tzinfo=UTC)
    assert sunday_utc.astimezone(rs.zone(KOLKATA)).weekday() == 0
    slot = rs.latest_slot(sunday_utc, 0, 1, KOLKATA)
    assert slot == datetime(2026, 11, 8, 19, 30, tzinfo=UTC)


@pytest.mark.parametrize("weekday", range(7))
def test_every_weekday_gives_a_slot_on_that_local_day(weekday):
    now = datetime(2026, 11, 11, 12, 0, tzinfo=UTC)
    slot = rs.latest_slot(now, weekday, 23, KOLKATA)
    local = slot.astimezone(rs.zone(KOLKATA))
    assert local.weekday() == weekday
    assert local.hour == 23
    assert timedelta(0) <= now - slot < timedelta(days=7)


def test_a_london_slot_follows_summer_time():
    _zone_or_skip("Europe/London")
    summer = datetime(2026, 7, 6, 12, 0, tzinfo=UTC)
    winter = datetime(2026, 12, 7, 12, 0, tzinfo=UTC)
    assert rs.latest_slot(summer, 0, 9, "Europe/London") == datetime(2026, 7, 6, 8, 0, tzinfo=UTC)
    assert rs.latest_slot(winter, 0, 9, "Europe/London") == datetime(2026, 12, 7, 9, 0, tzinfo=UTC)


def test_the_next_slot_across_a_clock_change_keeps_the_local_hour():
    _zone_or_skip("America/New_York")
    before_change = datetime(2026, 10, 27, 12, 0, tzinfo=UTC)
    following = rs.next_slot(before_change, 0, 8, "America/New_York")
    assert following == datetime(2026, 11, 2, 13, 0, tzinfo=UTC)
    assert following.astimezone(ZoneInfo("America/New_York")).hour == 8


def test_due_only_once_per_slot():
    now = datetime(2026, 11, 9, 3, 0, tzinfo=UTC)
    slot = datetime(2026, 11, 9, 2, 30, tzinfo=UTC)
    assert rs.due_slot(_row(lastSlotAt=datetime(2026, 11, 2, 2, 30, tzinfo=UTC)), now) == slot
    assert rs.due_slot(_row(lastSlotAt=slot), now) is None
    assert rs.due_slot(_row(lastSlotAt=slot.replace(tzinfo=None)), now) is None


def test_paused_waiting_or_stale_schedules_are_not_due():
    now = datetime(2026, 11, 9, 3, 0, tzinfo=UTC)
    old = datetime(2026, 11, 2, 2, 30, tzinfo=UTC)
    assert rs.due_slot(_row(lastSlotAt=old, pausedAt=now), now) is None
    assert rs.due_slot(_row(lastSlotAt=old, retryAfter=now + timedelta(minutes=5)), now) is None
    assert rs.due_slot(_row(lastSlotAt=old, retryAfter=now - timedelta(minutes=5)), now) is not None
    late = datetime(2026, 11, 12, 3, 0, tzinfo=UTC)
    assert rs.due_slot(_row(lastSlotAt=old), late) is None


def test_settling_a_slot_never_moves_it_back():
    now = datetime(2026, 11, 9, 10, 0, tzinfo=UTC)
    sent_today = datetime(2026, 11, 9, 2, 30, tzinfo=UTC)
    moved = rs.settle_slot(_row(lastSlotAt=sent_today), now, 1, 8, KOLKATA)
    assert moved == sent_today
    fresh = rs.settle_slot(_row(lastSlotAt=None), now, 1, 8, KOLKATA)
    assert fresh == rs.latest_slot(now, 1, 8, KOLKATA)


def test_the_next_run_of_a_due_schedule_is_now():
    now = datetime(2026, 11, 9, 3, 0, tzinfo=UTC)
    shaped = rs.schedule_out(_row(lastSlotAt=datetime(2026, 11, 2, 2, 30, tzinfo=UTC)), now)
    assert shaped["nextRunAt"] == now.isoformat()
    sent = rs.schedule_out(_row(lastSlotAt=datetime(2026, 11, 9, 2, 30, tzinfo=UTC)), now)
    assert sent["nextRunAt"] == datetime(2026, 11, 16, 2, 30, tzinfo=UTC).isoformat()
    assert rs.schedule_out(_row(pausedAt=now), now)["nextRunAt"] is None


def test_the_schedule_shape():
    shaped = rs.schedule_out(
        _row(lastFileName="Report.xlsx", lastFileBytes=2048, lastAttached=True, lastError="x"),
        datetime(2026, 11, 9, 3, 0, tzinfo=UTC),
    )
    assert set(shaped) == {
        "id", "savedViewId", "viewName", "path", "weekday", "hourLocal", "timezone", "label",
        "options", "paused", "nextRunAt", "lastSentAt", "lastError", "lastFile", "createdAt",
    }
    assert shaped["label"] == "every Monday at 08:00 IST"
    assert shaped["viewName"] == "Bagru media"
    assert shaped["lastFile"] == {"fileName": "Report.xlsx", "sizeBytes": 2048, "attached": True}
    assert shaped["options"]["answers"] == "both"


def test_labels_name_other_zones():
    assert rs.schedule_label(6, 23, "Europe/London") == "every Sunday at 23:00 Europe/London"


@pytest.mark.parametrize("value", [-1, 7, "1", True, None, 1.0])
def test_weekday_is_checked(value):
    with pytest.raises(rs.Invalid):
        rs.weekday_of(value)


@pytest.mark.parametrize("value", [-1, 24, "8", False])
def test_hour_is_checked(value):
    with pytest.raises(rs.Invalid):
        rs.hour_of(value)


def test_timezone_defaults_and_refuses_nonsense():
    assert rs.timezone_of(None) == KOLKATA
    assert rs.timezone_of("  ") == KOLKATA
    assert rs.timezone_of(KOLKATA) == KOLKATA
    for bad in ("Mars/Olympus", 5, "x" * 80):
        with pytest.raises(rs.Invalid):
            rs.timezone_of(bad)


def test_options_keep_the_report_choices_and_switches():
    picked = rs.options_of(
        {"sheets": ["Media"], "answers": "english", "links": False, "locale": "hi",
         "extras": {"quotes": True, "charts": False}, "thumbnails": True}
    )
    assert picked == {
        "sheets": ["Media"], "answers": "english", "rows": "all", "links": False,
        "locale": "hi", "thumbnails": True, "extras": {"quotes": True, "charts": False},
    }
    assert rs.options_of(None)["sheets"] is None
    for bad in ({"answers": "both ways"}, {"locale": "fr"}, {"extras": {"q": "yes"}},
                {"extras": {"bad key": True}}, {"thumbnails": "1"}, []):
        with pytest.raises(rs.Invalid):
            rs.options_of(bad)


def test_unsubscribe_tokens_are_stable_per_schedule_and_unguessable():
    first = rs.unsubscribe_token("rs1")
    assert first == rs.unsubscribe_token("rs1")
    assert first != rs.unsubscribe_token("rs2")
    assert len(first) >= 40
    assert rs.token_digest(first) != first
    assert rs.clean_token(" " + first + " ") == first
    assert rs.clean_token("") is None
    assert rs.clean_token("x" * 300) is None
    assert rs.clean_token("bad\ntoken") is None


def test_the_file_name_comes_from_the_report_response():
    assert rs.file_name_of('attachment; filename="DesignWorkshop_Dataset_Report.xlsx"') == (
        "DesignWorkshop_Dataset_Report.xlsx"
    )
    assert rs.file_name_of(None).endswith(".xlsx")


def test_the_storage_key_sits_under_the_schedule():
    key = rs.storage_key_for("rs1", "Report.xlsx")
    assert key.startswith("report-schedules/rs1/")
    assert key.endswith("/Report.xlsx")


def test_the_runner_stays_off_when_switched_off_or_without_mail():
    off = get_settings().model_copy(update={"report_schedules_enabled": False})
    assert asyncio.run(rs.run_report_schedules(force=True, settings=off)) == {
        "ran": False, "reason": "disabled"
    }
    no_mail = get_settings().model_copy(
        update={"report_schedules_enabled": True, "mail_from_address": None}
    )
    assert asyncio.run(rs.run_report_schedules(force=True, settings=no_mail))["reason"] == (
        "mail not configured"
    )


def _params(**extra):
    return {
        "recipientName": "Asha",
        "scheduleId": "rs1",
        "viewId": "v1",
        "viewName": "Bagru media",
        "fileName": "Report.xlsx",
        "sizeBytes": 5 * 1024 * 1024,
        "generatedAt": "2026-11-09T02:30:00+00:00",
        "scheduleLabel": "every Monday at 08:00 IST",
        **extra,
    }


def test_the_attached_mail_points_at_the_view_and_carries_unsubscribe():
    rendered = mailer.render(mailer.REPORT_SCHEDULE, _params(attachmentKey="k"), secret="tok")
    assert rendered.subject == "Your weekly report: “Bagru media”"
    assert "attached (5.0 MB)" in rendered.text
    assert "/data?viewId=v1" in rendered.text
    assert "/report-schedule/unsubscribe?token=tok" in rendered.text
    assert "every Monday at 08:00 IST" in rendered.text
    assert dict(rendered.headers)["List-Unsubscribe"].endswith("/report-schedule/unsubscribe?token=tok>")


def test_a_large_file_is_linked_not_attached():
    rendered = mailer.render(mailer.REPORT_SCHEDULE, _params(), secret="tok")
    assert "too large to attach" in rendered.text
    assert "/data/report-schedules/rs1" in rendered.text
    assert "Download the report" in rendered.html


def test_the_mail_needs_its_token():
    with pytest.raises(ValueError):
        mailer.render(mailer.REPORT_SCHEDULE, _params(), secret=None)


def test_the_raw_message_carries_the_workbook():
    settings = get_settings().model_copy(update={"mail_from_address": "reports@example.org"})
    raw = mailer.raw_message(
        to="asha@example.org",
        subject="Your weekly report: “Bagru media”",
        text="Hello",
        html_body="<p>Hello</p>",
        settings=settings,
        attachments=(mailer.Attachment("Report.xlsx", rs.XLSX_MIME, b"PK\x03\x04data"),),
        headers=(("List-Unsubscribe", "<https://example.org/u>"),),
    )
    parsed = email.message_from_bytes(raw)
    assert parsed["List-Unsubscribe"] == "<https://example.org/u>"
    parts = [part for part in parsed.walk() if part.get_filename()]
    assert [part.get_filename() for part in parts] == ["Report.xlsx"]
    assert parts[0].get_payload(decode=True) == b"PK\x03\x04data"
    assert parts[0].get_content_type() == rs.XLSX_MIME


def test_size_words():
    assert mailer.size_words(512) == "512 bytes"
    assert mailer.size_words(2048) == "2 KB"
    assert mailer.size_words(3 * 1024 * 1024) == "3.0 MB"
