"""The Monday summary against a REAL Postgres: idempotency, the opt-out at send time, mail off, the
empty week, the kill switch, the counts against the screens they link to, owner-only reads, and
retention (``app/services/weekly_digest.py``).

WHY A DATABASE, IN A SUITE THAT OTHERWISE HAS NONE. The guarantee under test is a unique index and
a compare-and-set: two passes racing for one person must produce one summary. An in-memory fake
would test the fake's idea of a unique violation, not Postgres's.

THE GATE IS DELIBERATELY NARROW. ``backend/.env`` names the LIVE production database, so this module
never reads it: it runs only when ``DATABASE_URL`` is EXPORTED in the environment AND names a host on
this machine AND something is listening there. Anything else — CI's unresolvable DSN included — skips
the whole module with one sentence. Start a throwaway database first, e.g.::

    docker run -d --name docs-digest-pg -e POSTGRES_USER=digest -e POSTGRES_PASSWORD=digest \\
        -e POSTGRES_DB=docs_digest -p 127.0.0.1:55434:5432 postgres:17-alpine
    export DATABASE_URL=postgresql://digest:digest@127.0.0.1:55434/docs_digest?connect_timeout=60
    prisma migrate deploy && python -m pytest tests/test_weekly_digest_db.py

The long ``connect_timeout`` is for Docker Desktop on Windows, whose port proxy can take more than
Prisma's default five seconds to accept a burst of connections (the concurrent-pass test opens one).

Every test TRUNCATES the tables it uses. Never point it at a database you want to keep.
"""

from __future__ import annotations

import asyncio
import os
import socket
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import pytest

_DSN = os.environ.get("DATABASE_URL", "")
_PARTS = urlsplit(_DSN) if _DSN else None
_LOCAL = bool(_PARTS and _PARTS.hostname in {"localhost", "127.0.0.1", "::1"})


def _listening() -> bool:
    if not _LOCAL or _PARTS is None:
        return False
    try:
        with socket.create_connection((_PARTS.hostname, _PARTS.port or 5432), timeout=1.0):
            return True
    except OSError:
        return False


pytestmark = pytest.mark.skipif(
    not _listening(),
    reason="needs an exported DATABASE_URL naming a running Postgres on this machine (never backend/.env)",
)

if _listening():  # imported only when the gate is open: these modules build a client from settings
    import httpx
    from fastapi import FastAPI

    from app.api.router import api_router
    from app.api.routes import review as review_routes
    from app.core import deps
    from app.core.config import get_settings
    from app.core.db import db
    from app.services import access_roster, weekly_digest


def _settings(*, mail: bool = True, enabled: bool = True) -> Any:
    return get_settings().model_copy(
        update={"mail_from_address": "summary@example.org" if mail else None, "weekly_digest_enabled": enabled}
    )


def _run(body: Callable[[], Awaitable[Any]]) -> Any:
    async def wrapper() -> Any:
        settings_host = urlsplit(get_settings().database_url).hostname
        assert settings_host in {"localhost", "127.0.0.1", "::1"}, "refusing a non-local database"
        await db.connect()
        try:
            await db.execute_raw(
                'TRUNCATE "User", "Location", "WeeklyDigest", "Notification", "EmailMessage" CASCADE'
            )
            return await body()
        finally:
            await db.disconnect()

    return asyncio.run(wrapper())


@pytest.fixture(autouse=True)
def _no_roster_gate(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(access_roster, "gate_enforced", lambda: False)
    monkeypatch.setattr(weekly_digest, "_last_check", None)


async def _user(role: str, *, can_review: bool = False, email: bool = True) -> Any:
    tag = uuid4().hex[:8]
    return await db.user.create(
        data={
            "email": f"{role.lower()}-{tag}@example.org" if email else f"no-mail-{tag}@example.invalid",
            "name": f"{role.title()} {tag}",
            "role": role,
            "canReview": can_review,
        }
    )


async def _artisan(creator: Any, *, status: str = "PENDING", created: datetime | None = None, location: Any = None) -> Any:
    data: dict[str, Any] = {"name": "A", "place": "P", "status": status, "createdBy": {"connect": {"id": creator.id}}}
    if created is not None:
        data["createdAt"] = created
    if location is not None:
        data["location"] = {"connect": {"id": location.id}}
    return await db.artisan.create(data=data)


async def _counts(user_id: str) -> tuple[int, int, int]:
    return (
        await db.weeklydigest.count(where={"userId": user_id}),
        await db.notification.count(where={"userId": user_id, "kind": weekly_digest.KIND}),
        await db.emailmessage.count(where={"recipientId": user_id, "kind": "WEEKLY_DIGEST"}),
    )


# --- who -----------------------------------------------------------------------------------------


def test_the_eligibility_matrix() -> None:
    async def body() -> None:
        admin = await _user("MASTER_ADMIN")
        professor = await _user("PROFESSOR")
        granted = await _user("RESEARCHER", can_review=True)
        researcher = await _user("RESEARCHER")
        volunteer = await _user("CROWDSOURCE_VOLUNTEER")
        ids = {u.id for u in await weekly_digest.eligible_users()}
        assert {admin.id, professor.id, granted.id} <= ids
        assert researcher.id not in ids and volunteer.id not in ids

    _run(body)


# --- once per person per week --------------------------------------------------------------------


def test_two_passes_write_one_summary_one_notice_one_email() -> None:
    async def body() -> None:
        professor = await _user("PROFESSOR")
        await _artisan(await _user("RESEARCHER"))
        settings = _settings()
        first = await weekly_digest.run_weekly_digests(force=True, settings=settings)
        second = await weekly_digest.run_weekly_digests(force=True, settings=settings)
        assert first["delivered"] == 1 and second["delivered"] == 0
        assert await _counts(professor.id) == (1, 1, 1)
        row = await db.weeklydigest.find_first(where={"userId": professor.id})
        assert row.status == weekly_digest.DELIVERED and row.notificationId and row.emailMessageId
        notice = await db.notification.find_unique(where={"id": row.notificationId})
        assert notice.link == f"/digest/{row.id}"
        assert notice.title == "Your week: 1 waiting"

    _run(body)


def test_two_concurrent_passes_still_write_one() -> None:
    async def body() -> None:
        professor = await _user("PROFESSOR")
        admin = await _user("ADMIN")
        await _artisan(await _user("RESEARCHER"))
        settings = _settings()
        await asyncio.gather(
            weekly_digest.run_weekly_digests(force=True, settings=settings),
            weekly_digest.run_weekly_digests(force=True, settings=settings),
        )
        assert await _counts(professor.id) == (1, 1, 1)
        assert await _counts(admin.id) == (1, 1, 1)

    _run(body)


def test_a_pass_that_died_after_the_insert_is_reclaimed_once(monkeypatch: pytest.MonkeyPatch) -> None:
    real_notify = weekly_digest.notify
    calls = {"n": 0}

    async def notify_once_broken(*args: Any, **kwargs: Any) -> Any:
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("worker died")
        return await real_notify(*args, **kwargs)

    monkeypatch.setattr(weekly_digest, "notify", notify_once_broken)

    async def body() -> None:
        professor = await _user("PROFESSOR")
        await _artisan(await _user("RESEARCHER"))
        settings = _settings()
        crashed = await weekly_digest.run_weekly_digests(force=True, settings=settings)
        assert crashed["failed"] == 1
        row = await db.weeklydigest.find_first(where={"userId": professor.id})
        assert row.status == weekly_digest.PENDING
        # Too fresh to reclaim: another pass may still be working on it.
        await weekly_digest.run_weekly_digests(force=True, settings=settings)
        assert await _counts(professor.id) == (1, 0, 0)
        await db.execute_raw(
            'UPDATE "WeeklyDigest" SET "updatedAt" = NOW() - INTERVAL \'20 minutes\' WHERE "id" = $1', row.id
        )
        healed = await weekly_digest.run_weekly_digests(force=True, settings=settings)
        assert healed["reclaimed"] == 1 and healed["delivered"] == 1
        await weekly_digest.run_weekly_digests(force=True, settings=settings)
        assert await _counts(professor.id) == (1, 1, 1)
        row = await db.weeklydigest.find_unique(where={"id": row.id})
        assert row.status == weekly_digest.DELIVERED

    _run(body)


# --- what stops it -------------------------------------------------------------------------------


def test_an_opt_out_is_honoured_at_send_time() -> None:
    async def body() -> None:
        professor = await _user("PROFESSOR")
        await db.userpreference.create(data={"user": {"connect": {"id": professor.id}}, "weeklyDigest": False})
        await _artisan(await _user("RESEARCHER"))
        tally = await weekly_digest.run_weekly_digests(force=True, settings=_settings())
        assert tally["optedOut"] == 1
        row = await db.weeklydigest.find_first(where={"userId": professor.id})
        assert row.status == weekly_digest.SKIPPED_OPTOUT and row.payload is None
        assert await _counts(professor.id) == (1, 0, 0)

    _run(body)


def test_with_mail_off_the_notice_still_goes() -> None:
    async def body() -> None:
        professor = await _user("PROFESSOR")
        await _artisan(await _user("RESEARCHER"))
        await weekly_digest.run_weekly_digests(force=True, settings=_settings(mail=False))
        assert await _counts(professor.id) == (1, 1, 0)
        row = await db.weeklydigest.find_first(where={"userId": professor.id})
        assert row.status == weekly_digest.DELIVERED and row.emailMessageId is None

    _run(body)


def test_an_empty_week_sends_nothing() -> None:
    async def body() -> None:
        professor = await _user("PROFESSOR")
        tally = await weekly_digest.run_weekly_digests(force=True, settings=_settings())
        assert tally["empty"] >= 1
        row = await db.weeklydigest.find_first(where={"userId": professor.id})
        assert row.status == weekly_digest.SKIPPED_EMPTY
        assert await _counts(professor.id) == (1, 0, 0)

    _run(body)


def test_the_kill_switch_writes_nothing() -> None:
    async def body() -> None:
        professor = await _user("PROFESSOR")
        await _artisan(await _user("RESEARCHER"))
        tally = await weekly_digest.run_weekly_digests(force=True, settings=_settings(enabled=False))
        assert tally["ran"] is False
        assert await _counts(professor.id) == (0, 0, 0)

    _run(body)


# --- the counts are the screens' counts ----------------------------------------------------------


def test_the_counts_follow_the_review_ladder_the_task_rule_and_the_week() -> None:
    async def body() -> None:
        now = datetime.now(UTC)
        week_start = weekly_digest.week_start_for(now)
        start, end = weekly_digest.week_window(week_start)
        professor = await _user("PROFESSOR")
        admin = await _user("ADMIN")
        researcher = await _user("RESEARCHER")
        # Reviewable by the professor (a researcher's), one of them stale; an admin's is not theirs.
        await _artisan(researcher)
        await _artisan(researcher, created=now - timedelta(days=15))
        await _artisan(admin)
        # Tasks the admin manages: one handed in, one past due, one past due but handed in.
        for status, due in (("SUBMITTED", None), ("OPEN", now - timedelta(days=1)), ("SUBMITTED", now - timedelta(days=1))):
            await db.assignedtask.create(
                data={"title": "T", "status": status, "dueAt": due,
                      "assignee": {"connect": {"id": researcher.id}}, "createdBy": {"connect": {"id": admin.id}}}
            )
        # New this week: one approved artisan inside the window, one before it.
        await _artisan(researcher, status="APPROVED", created=start + timedelta(hours=1))
        await _artisan(researcher, status="APPROVED", created=start - timedelta(hours=1))

        payload = await weekly_digest.build_payload(professor, week_start, now=now)
        sections = {s["key"]: s for s in payload["sections"]}
        lines = {s: {line["key"]: line["count"] for line in sections[s]["lines"]} for s in sections}
        queue = await review_routes.list_pending_reviews(reviewer=professor, page=1, page_size=20)
        assert lines["waiting"] == {"records": queue["total"]} == {"records": 2}
        assert lines["overdue"] == {"reviews": 1}
        assert lines["new"] == {"artisans": 1}
        assert all(s["items"] == [] for s in payload["sections"])

        admin_payload = await weekly_digest.build_payload(admin, week_start, now=now)
        admin_lines = {s["key"]: {line["key"]: line for line in s["lines"]} for s in admin_payload["sections"]}
        assert admin_lines["waiting"]["tasks"]["count"] == 2
        assert admin_lines["waiting"]["tasks"]["link"] == "/settings/tasks"
        assert admin_lines["overdue"]["tasks"]["count"] == 1
        assert end > start

    _run(body)


def test_districts_documented_counts_distinct_approved_pairs() -> None:
    async def body() -> None:
        researcher = await _user("RESEARCHER")

        async def place(state: str, district: str) -> Any:
            return await db.location.create(data={"latitude": 26.9, "longitude": 75.8, "state": state, "district": district})

        await _artisan(researcher, status="APPROVED", location=await place("Rajasthan", "Jaipur"))
        await _artisan(researcher, status="APPROVED", location=await place("rajasthan ", "jaipur"))
        await _artisan(researcher, status="APPROVED", location=await place("Rajasthan", "Ajmer"))
        await _artisan(researcher, status="PENDING", location=await place("Gujarat", "Kutch"))
        assert await weekly_digest.districts_documented() == 2

    _run(body)


# --- reading it back -----------------------------------------------------------------------------


def test_a_summary_is_its_owners_alone_and_the_switch_is_for_authorities() -> None:
    current: dict[str, Any] = {}
    app = FastAPI()
    app.include_router(api_router)
    app.dependency_overrides[deps.get_current_user] = lambda: current["user"]

    async def call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://digest.test") as client:
            return await client.request(method, path, json=body)

    async def body() -> None:
        professor = await _user("PROFESSOR")
        other = await _user("ADMIN")
        researcher = await _user("RESEARCHER")
        await _artisan(researcher)
        await weekly_digest.run_weekly_digests(force=True, settings=_settings())
        row = await db.weeklydigest.find_first(where={"userId": professor.id})

        current["user"] = professor
        mine = await call("GET", f"/api/notifications/digests/{row.id}")
        assert mine.status_code == 200, mine.text
        assert mine.json()["sections"][0]["lines"][0]["key"] == "records"
        state = (await call("GET", "/api/notifications/digest")).json()
        assert state["eligible"] is True and state["enabled"] is True
        assert state["latest"]["id"] == row.id and state["latest"]["waiting"] == 1
        off = await call("PUT", "/api/notifications/digest", {"enabled": False})
        assert off.status_code == 200 and off.json()["enabled"] is False

        current["user"] = other
        assert (await call("GET", f"/api/notifications/digests/{row.id}")).status_code == 404

        current["user"] = researcher
        refused = await call("PUT", "/api/notifications/digest", {"enabled": False})
        assert refused.status_code == 403
        assert refused.json()["detail"] == "The weekly summary is for reviewers and officials."
        assert (await call("GET", "/api/notifications/digest")).json() == {
            "eligible": False, "enabled": False, "emailAvailable": state["emailAvailable"],
            "schedule": "Mondays, 08:00 IST", "latest": None,
        }

    _run(body)


def test_summaries_older_than_twenty_six_weeks_are_purged() -> None:
    async def body() -> None:
        professor = await _user("PROFESSOR")
        this_week = weekly_digest.week_start_for(datetime.now(UTC))
        for weeks in (27, 25):
            day = this_week - timedelta(weeks=weeks)
            await db.weeklydigest.create(
                data={"user": {"connect": {"id": professor.id}}, "status": weekly_digest.SKIPPED_EMPTY,
                      "weekStart": datetime(day.year, day.month, day.day, tzinfo=UTC)}
            )
        tally = await weekly_digest.run_weekly_digests(force=True, settings=_settings())
        assert tally["purged"] == 1
        kept = await db.weeklydigest.find_many(where={"userId": professor.id})
        assert len(kept) == 2  # the 25-week-old one and this week's

    _run(body)
