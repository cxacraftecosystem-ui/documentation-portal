"""The Monday summary without a database: the IST calendar, who gets one, the e-mail, and the page's
shape (``app/services/weekly_digest.py``, ``app/services/mailer.py``).

The database half — idempotency, the opt-out at send time, the counts — is
``test_weekly_digest_db.py``.
"""

from __future__ import annotations

import asyncio
import re
from datetime import UTC, date, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import mailer, weekly_digest


def ist(year: int, month: int, day: int, hour: int, minute: int = 0) -> datetime:
    return datetime(year, month, day, hour, minute, tzinfo=mailer.IST)


# --- the calendar --------------------------------------------------------------------------------


def test_sunday_late_utc_is_already_monday_in_india() -> None:
    sunday_utc = datetime(2026, 10, 18, 23, 0, tzinfo=UTC)  # Monday 04:30 IST
    assert sunday_utc.astimezone(mailer.IST).weekday() == 0
    assert weekly_digest.week_start_for(sunday_utc) == date(2026, 10, 19)
    # Sunday 23:29 IST is still the week before.
    assert weekly_digest.week_start_for(datetime(2026, 10, 18, 17, 59, tzinfo=UTC)) == date(2026, 10, 12)


def test_the_send_window_is_monday_from_eight_to_midnight_ist() -> None:
    assert not weekly_digest.in_send_window(ist(2026, 10, 19, 7, 59))
    assert weekly_digest.in_send_window(ist(2026, 10, 19, 8, 0))
    assert weekly_digest.in_send_window(ist(2026, 10, 19, 23, 59))
    assert not weekly_digest.in_send_window(ist(2026, 10, 20, 0, 0))
    assert not weekly_digest.in_send_window(ist(2026, 10, 18, 9, 0))


def test_this_week_is_the_seven_days_before_monday_eight_ist() -> None:
    start, end = weekly_digest.week_window(date(2026, 10, 19))
    assert end == datetime(2026, 10, 19, 2, 30, tzinfo=UTC)
    assert start == datetime(2026, 10, 12, 2, 30, tzinfo=UTC)


# --- who -----------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("role", "can_review", "expected"),
    [
        ("MASTER_ADMIN", False, True),
        ("ADMIN", False, True),
        ("PROFESSOR", False, True),
        ("RESEARCHER", False, False),
        ("RESEARCHER", True, True),
        ("FIELD_CONTRIBUTOR", False, False),
        ("CROWDSOURCE_VOLUNTEER", False, False),
    ],
)
def test_who_gets_a_summary(role: str, can_review: bool, expected: bool) -> None:
    assert weekly_digest.is_authority(SimpleNamespace(role=role, canReview=can_review)) is expected


# --- the e-mail ----------------------------------------------------------------------------------


def _params(*, overdue: int = 2, title: str = "Records") -> dict[str, Any]:
    return {
        "digestId": "dg1",
        "recipientName": "Asha",
        "weekStart": "2026-10-19",
        "sections": [
            {"key": "waiting", "label": "Waiting for you", "total": 4,
             "lines": [{"key": "records", "label": "Records to review", "count": 3, "link": "/review"},
                       {"key": "tasks", "label": "Tasks to check", "count": 1, "link": "/settings/tasks"}],
             "items": [{"title": title, "link": "/review"}]},
            {"key": "overdue", "label": "Overdue", "total": overdue,
             "lines": [{"key": "tasks", "label": "Tasks past due", "count": overdue, "link": "/tasks"}] if overdue else [],
             "items": []},
            {"key": "new", "label": "New this week", "total": 11,
             "lines": [{"key": "artisans", "label": "Artisans", "count": 11, "link": "/artisans"}], "items": []},
        ],
        "coverage": {"label": "Districts documented", "value": 41, "of": None, "percent": None,
                     "scope": "Approved artisans, whole repository"},
    }


def test_the_subject_names_overdue_only_when_there_is_some() -> None:
    assert mailer.subject_for(mailer.WEEKLY_DIGEST, _params()) == "Your week: 4 waiting, 2 overdue"
    assert mailer.subject_for(mailer.WEEKLY_DIGEST, _params(overdue=0)) == "Your week: 4 waiting"
    assert mailer.WEEKLY_DIGEST in mailer.KINDS


def test_every_value_is_escaped() -> None:
    rendered = mailer.render(mailer.WEEKLY_DIGEST, _params(title="<script>alert(1)</script>"))
    assert "<script>" not in rendered.html
    assert "&lt;script&gt;" in rendered.html


def test_the_plain_text_says_what_the_html_says() -> None:
    rendered = mailer.render(mailer.WEEKLY_DIGEST, _params())
    for figure in ("Waiting for you: 4", "Overdue: 2", "New this week: 11", "Districts documented: 41"):
        assert figure in rendered.text
    # The same four figures sit in the HTML's large type.
    big = re.findall(r'font-size:22px;[^"]*">([^<]+)<', rendered.html)
    assert big == ["4", "2", "11", "41"]
    assert "/digest/dg1" in rendered.text and "/digest/dg1" in rendered.html
    assert "/settings" in rendered.text and "Turn it off in" in rendered.html
    assert "Open your digest" in rendered.html


def test_the_email_has_no_image_and_no_tracking() -> None:
    rendered = mailer.render(mailer.WEEKLY_DIGEST, _params())
    assert "<img" not in rendered.html.lower()
    assert "utm_" not in rendered.html


def test_a_percentage_coverage_reads_as_a_percentage() -> None:
    assert mailer.coverage_figure({"value": 41, "of": 58, "percent": 71}) == "71%"
    assert mailer.coverage_figure({"value": 41, "of": None, "percent": None}) == "41"


# --- the page ------------------------------------------------------------------------------------


def test_the_page_leaves_out_empty_lines_and_foreign_links() -> None:
    row = SimpleNamespace(
        id="dg1",
        weekStart=datetime(2026, 10, 19, tzinfo=UTC),
        createdAt=datetime(2026, 10, 19, 2, 31, tzinfo=UTC),
        updatedAt=datetime(2026, 10, 19, 2, 32, tzinfo=UTC),
        payload={
            "sections": [
                {"key": "waiting", "label": "Waiting for you", "total": 3, "items": [],
                 "lines": [{"key": "records", "label": "Records to review", "count": 3, "link": "https://evil.example"},
                           {"key": "tasks", "label": "Tasks to check", "count": 0, "link": "/tasks"}]},
            ],
            "coverage": {"label": "Districts documented", "value": 4, "of": None, "percent": None},
        },
    )
    page = weekly_digest.serialize_digest(row)
    assert page["weekStart"] == "2026-10-19"
    assert page["generatedAt"] == "2026-10-19T02:32:00+00:00"
    lines = page["sections"][0]["lines"]
    assert [line["key"] for line in lines] == ["records"]
    assert lines[0]["link"] is None
    summary = weekly_digest.summarize_digest(row)
    assert summary == {"id": "dg1", "weekStart": "2026-10-19", "waiting": 3, "overdue": 0, "newThisWeek": 0,
                       "coverage": row.payload["coverage"]}


def test_the_android_line_carries_all_four_figures() -> None:
    assert weekly_digest.body_line(_params()) == "4 waiting · 2 overdue · 11 new · Districts documented 41"


# --- the scheduler's cheap exits -----------------------------------------------------------------


class _NoDatabase:
    def __getattr__(self, name: str) -> Any:
        raise AssertionError(f"the scheduler touched the database ({name}) when it should not have")


def test_outside_the_window_and_with_the_kill_switch_off_nothing_is_read(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(weekly_digest, "db", _NoDatabase())
    monkeypatch.setattr(weekly_digest, "_last_check", None)
    on = SimpleNamespace(weekly_digest_enabled=True)
    off = SimpleNamespace(weekly_digest_enabled=False)
    tuesday = asyncio.run(weekly_digest.run_weekly_digests(now=ist(2026, 10, 20, 9), settings=on))
    assert tuesday["ran"] is False
    monday_off = asyncio.run(weekly_digest.run_weekly_digests(now=ist(2026, 10, 19, 9), settings=off, force=True))
    assert monday_off["ran"] is False


def test_inside_the_window_the_check_runs_at_most_every_ten_minutes(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str] = []

    class _Rows:
        async def find_many(self, **_kwargs: Any) -> list[Any]:
            calls.append("find_many")
            return []

        async def delete_many(self, **_kwargs: Any) -> int:
            return 0

    async def nobody() -> list[Any]:
        return []

    monkeypatch.setattr(weekly_digest, "db", SimpleNamespace(weeklydigest=_Rows()))
    monkeypatch.setattr(weekly_digest, "eligible_users", nobody)
    monkeypatch.setattr(weekly_digest, "_last_check", None)
    on = SimpleNamespace(weekly_digest_enabled=True)
    first = asyncio.run(weekly_digest.run_weekly_digests(now=ist(2026, 10, 19, 8, 0), settings=on))
    soon = asyncio.run(weekly_digest.run_weekly_digests(now=ist(2026, 10, 19, 8, 9), settings=on))
    later = asyncio.run(weekly_digest.run_weekly_digests(now=ist(2026, 10, 19, 8, 10), settings=on))
    assert (first["ran"], soon["ran"], later["ran"]) == (True, False, True)


def test_a_summary_that_wrote_nothing_stays_pending_for_repair(monkeypatch: pytest.MonkeyPatch) -> None:
    updates: list[dict[str, Any]] = []

    class _Rows:
        async def update(self, **kwargs: Any) -> None:
            updates.append(kwargs)

    async def wants(*_args: Any) -> bool:
        return True

    async def payload(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
        return {"waiting": 2, "overdue": 0, "newThisWeek": 0, "sections": [], "latest": {}}

    async def nothing_written(*_args: Any, **_kwargs: Any) -> Any:
        return SimpleNamespace(notification=None, email=None, emailed=False)

    monkeypatch.setattr(weekly_digest, "db", SimpleNamespace(weeklydigest=_Rows()))
    monkeypatch.setattr(weekly_digest, "wants_email", wants)
    monkeypatch.setattr(weekly_digest, "build_payload", payload)
    monkeypatch.setattr(weekly_digest, "figures", lambda _p: (2, 0, 0))
    monkeypatch.setattr(weekly_digest, "body_line", lambda _p: "2 waiting")
    monkeypatch.setattr(weekly_digest, "notify", nothing_written)
    outcome = asyncio.run(
        weekly_digest._complete(
            SimpleNamespace(id="dg_1"),
            SimpleNamespace(id="u_1"),
            date(2026, 10, 19),
            now=ist(2026, 10, 19, 8),
            districts=None,
        )
    )
    assert outcome == weekly_digest.PENDING
    assert updates == []
