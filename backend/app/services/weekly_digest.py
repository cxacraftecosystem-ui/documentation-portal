"""THE MONDAY SUMMARY: one short note a week for the people who review and run the repository.

Every Monday at 08:00 IST each reviewer and administrator gets one summary — in-app always, and by
e-mail when mail is on — saying what waits for them, what is overdue, what came in during the week,
and one coverage number. The queue worker calls :func:`run_weekly_digests` on every pass; outside
Monday's send window that call costs no query.

WHO GETS ONE (:func:`is_authority`): Master Admin, Admin and Professor, and anybody holding a review
grant (``canReview``). Nobody who only uploads data. Each can switch it off in Settings
(``UserPreference.weeklyDigest``, opt-out: no row reads as on), and the switch is read AT SEND TIME.

THE SCHEDULE. Monday 08:00–23:59 IST is the send window. A worker that was down at 08:00 catches up
the same day; after midnight that week is skipped, never sent late. Inside the window the check runs
at most every :data:`CHECK_EVERY`.

IDEMPOTENT PER PERSON PER WEEK. The unique ``(userId, weekStart)`` pair on ``WeeklyDigest`` is the
key. Per person: insert the row PENDING (a unique violation means another pass has it: skip), build
the summary, :func:`app.services.notifications.notify`, mark it DELIVERED with the notice's and the
e-mail's ids. A row left PENDING for :data:`RECLAIM_AFTER` (the worker died between the insert and
the mark) is reclaimed with a compare-and-set on ``updatedAt`` and delivered once. A week with
nothing waiting, overdue or new is SKIPPED_EMPTY and sends nothing; an opted-out person is
SKIPPED_OPTOUT with no payload stored.

WHAT IT SAYS, AND WHAT IT NEVER SAYS. Counts and app paths only. Artisan records are personal data
held under consent, so no record label, name, place or note goes into the summary, its e-mail or its
stored payload. Every count uses the same rule as the screen it links to: the review queue's ladder
(:mod:`app.services.review_queue`), the task manager rule (``routes/tasks``), and the repository's
read scope (``records.viewable_where``). Log lines carry the digest id, the account id and counts —
never an address.

The designer portal has a module of the same name, the same table and the same routes; the two never
call each other.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, date, datetime, time, timedelta
from typing import Any

from prisma.errors import UniqueViolationError

from app.core.config import Settings, get_settings
from app.core.db import db
from app.core.roles import get_value, is_admin, role_value
from app.services import mailer, review_queue
from app.services.concurrency import gather_reads
from app.services.notifications import _app_path, notify, wants_email
from app.services.records import viewable_where
from app.services.task_status import LIVE_STATUSES, SUBMITTED
from prisma import Json

logger = logging.getLogger(__name__)

#: Monday, 08:00 India Standard Time. Constants, not settings: there is no choice of day or hour.
DIGEST_WEEKDAY = 0
DIGEST_HOUR_IST = 8
#: How often, inside the send window, a pass looks for people still owed this week's summary.
CHECK_EVERY = timedelta(minutes=10)
#: A PENDING row this old belongs to a pass that died; it is reclaimed and delivered.
RECLAIM_AFTER = timedelta(minutes=15)
#: People handled per batch, so a pass never reads the whole user table's work at once.
BATCH_SIZE = 50
#: Summaries are kept this long, then purged; rows also cascade away with their account.
RETENTION_WEEKS = 26
#: A record still PENDING this long after it was created is an overdue review.
REVIEW_OVERDUE_DAYS = 7
#: At most this many items per section (always none here: the Field Repository's summary is counts).
MAX_ITEMS = 5

PENDING = "PENDING"
DELIVERED = "DELIVERED"
SKIPPED_EMPTY = "SKIPPED_EMPTY"
SKIPPED_OPTOUT = "SKIPPED_OPTOUT"

PREFERENCE = "weeklyDigest"
KIND = "WEEKLY_DIGEST"
AUTHORITY_ROLES = frozenset({"MASTER_ADMIN", "ADMIN", "PROFESSOR"})
SCHEDULE = "Mondays, 08:00 IST"
NOT_ELIGIBLE_DETAIL = "The weekly summary is for reviewers and officials."

COVERAGE_LABEL = "Districts documented"
COVERAGE_SCOPE = "Approved artisans, whole repository"

#: "New this week": (line key, label, Prisma delegate, owner column, list page).
NEW_SOURCES: tuple[tuple[str, str, str, str, str], ...] = (
    ("artisans", "Artisans", "artisan", "createdById", "/artisans"),
    ("workshops", "Workshops", "workshop", "createdById", "/workshops"),
    ("products", "Products", "productdocumentation", "createdById", "/products"),
    ("tools", "Tools", "tooldocumentation", "createdById", "/tools"),
    ("processes", "Processes", "process", "createdById", "/processes"),
    ("interviews", "Interviews", "questionnaireinterview", "createdById", "/questionnaire"),
    ("media", "Media files", "mediafile", "uploadedById", "/media"),
)

#: The last time a pass looked inside the window (module-level, like the outbox's digest flush).
_last_check: datetime | None = None


# --------------------------------------------------------------------------------------
# The calendar
# --------------------------------------------------------------------------------------


def _aware(now: datetime) -> datetime:
    return now if now.tzinfo is not None else now.replace(tzinfo=UTC)


def week_start_for(now: datetime) -> date:
    """The IST calendar Monday of the week ``now`` falls in."""
    local = _aware(now).astimezone(mailer.IST).date()
    return local - timedelta(days=(local.weekday() - DIGEST_WEEKDAY) % 7)


def in_send_window(now: datetime) -> bool:
    """Monday 08:00 to 23:59 IST."""
    local = _aware(now).astimezone(mailer.IST)
    return local.weekday() == DIGEST_WEEKDAY and local.hour >= DIGEST_HOUR_IST


def week_window(week_start: date) -> tuple[datetime, datetime]:
    """"This week" for the summary sent on ``week_start``: [previous Monday 08:00 IST, this Monday
    08:00 IST), as UTC instants."""
    end = datetime.combine(week_start, time(DIGEST_HOUR_IST), mailer.IST).astimezone(UTC)
    return end - timedelta(days=7), end


def _week_key(week_start: date) -> datetime:
    """How a ``@db.Date`` column is written through Prisma: midnight UTC of that date."""
    return datetime(week_start.year, week_start.month, week_start.day, tzinfo=UTC)


def _date_text(value: Any) -> str | None:
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return str(value) if value else None


# --------------------------------------------------------------------------------------
# Who
# --------------------------------------------------------------------------------------


def is_authority(user: Any) -> bool:
    """Gets a summary: Professor and above, or anybody with a review grant."""
    return role_value(user) in AUTHORITY_ROLES or bool(get_value(user, "canReview"))


async def eligible_users() -> list[Any]:
    """Every account that gets a summary, in one query. An address is not required: the in-app half
    goes regardless. While the sign-in gate is on, an account whose roster row is not ACTIVE (a
    suspended reviewer) is left out — they could not open the summary anyway."""
    from app.services import access_roster

    users = await db.user.find_many(
        where={"OR": [{"role": {"in": sorted(AUTHORITY_ROLES)}}, {"canReview": True}]},
        order={"id": "asc"},
    )
    if not users or not access_roster.gate_enforced():
        return list(users)
    addresses = sorted({access_roster.normalise_email(u.email) for u in users if u.email})
    active = {
        row.email
        for row in await db.accessroster.find_many(
            where={"email": {"in": addresses}, "status": access_roster.ADMITTING_STATUS}
        )
    }
    return [
        u
        for u in users
        if access_roster.normalise_email(u.email) in active or access_roster.is_master_admin_email(u.email)
    ]


# --------------------------------------------------------------------------------------
# What
# --------------------------------------------------------------------------------------


def _line(key: str, label: str, count: int, link: str) -> dict[str, Any]:
    return {"key": key, "label": label, "count": int(count or 0), "link": _app_path(link)}


def _section(key: str, label: str, lines: list[dict[str, Any]]) -> dict[str, Any]:
    shown = [line for line in lines if line["count"] > 0]
    return {"key": key, "label": label, "total": sum(line["count"] for line in shown), "lines": shown, "items": []}


async def districts_documented() -> int:
    """Distinct (state, district) pairs among APPROVED artisans, repository-wide, compared trimmed
    and case-folded so "Jaipur" and "jaipur " are one district."""
    rows = await db.query_raw(
        'SELECT COUNT(*)::int AS "n" FROM ('
        ' SELECT DISTINCT lower(btrim(l."state")) AS s, lower(btrim(l."district")) AS d'
        ' FROM "Artisan" a JOIN "Location" l ON l."id" = a."locationId"'
        " WHERE a.\"status\" = 'APPROVED'"
        " AND COALESCE(btrim(l.\"state\"), '') <> '' AND COALESCE(btrim(l.\"district\"), '') <> ''"
        ") AS pairs"
    )
    return int((rows[0] or {}).get("n") or 0) if rows else 0


def coverage_payload(districts: int) -> dict[str, Any]:
    return {"label": COVERAGE_LABEL, "value": int(districts), "of": None, "percent": None, "scope": COVERAGE_SCOPE}


async def build_payload(
    user: Any,
    week_start: date,
    *,
    now: datetime | None = None,
    districts: int | None = None,
) -> dict[str, Any]:
    """The summary for one person: ``{"weekStart", "sections": [waiting, overdue, new], "coverage"}``.

    Every count is the rule of the screen it links to, for THIS person. All reads go out together in
    one bounded wave (``gather_reads``)."""
    now = _aware(now or datetime.now(UTC))
    start, end = week_window(week_start)
    review_cutoff = now - timedelta(days=REVIEW_OVERDUE_DAYS)
    reviewable = review_queue.pending_where(user)
    manager: dict[str, Any] = {} if is_admin(user) else {"createdById": user.id}
    tasks_link = "/settings/tasks" if is_admin(user) else "/tasks"
    view = await viewable_where(user)
    media_view = await viewable_where(user, owner_field="uploadedById")
    in_week = {"createdAt": {"gte": start, "lt": end}}

    reads: list[Any] = []
    if reviewable is not None:
        for _type, delegate in review_queue.PENDING_RECORD_DELEGATES:
            reads.append(getattr(db, delegate).count(where=reviewable))
        for _type, delegate in review_queue.PENDING_RECORD_DELEGATES:
            reads.append(
                getattr(db, delegate).count(where={**reviewable, "createdAt": {"lt": review_cutoff}})
            )
    reads.append(db.assignedtask.count(where={**manager, "status": SUBMITTED}))
    reads.append(
        db.assignedtask.count(where={**manager, "status": {"in": sorted(LIVE_STATUSES)}, "dueAt": {"lt": now}})
    )
    for _key, _label, delegate, owner, _link in NEW_SOURCES:
        scope = media_view if owner == "uploadedById" else view
        reads.append(getattr(db, delegate).count(where={"AND": [scope, in_week]}))
    if districts is None:
        reads.append(districts_documented())

    answers = await gather_reads(*reads)
    width = len(review_queue.PENDING_RECORD_DELEGATES)
    if reviewable is not None:
        to_review, stale_reviews = sum(answers[:width]), sum(answers[width : 2 * width])
        answers = answers[2 * width :]
    else:
        to_review = stale_reviews = 0
    to_check, past_due = answers[0], answers[1]
    fresh = answers[2 : 2 + len(NEW_SOURCES)]
    if districts is None:
        districts = answers[2 + len(NEW_SOURCES)]

    sections = [
        _section(
            "waiting",
            "Waiting for you",
            [
                _line("records", "Records to review", to_review, "/review"),
                _line("tasks", "Tasks to check", to_check, tasks_link),
            ],
        ),
        _section(
            "overdue",
            "Overdue",
            [
                _line("reviews", "Reviews waiting over a week", stale_reviews, "/review"),
                _line("tasks", "Tasks past due", past_due, tasks_link),
            ],
        ),
        _section(
            "new",
            "New this week",
            [
                _line(key, label, count, link)
                for (key, label, _delegate, _owner, link), count in zip(NEW_SOURCES, fresh, strict=True)
            ],
        ),
    ]
    return {"weekStart": week_start.isoformat(), "sections": sections, "coverage": coverage_payload(districts)}


def figures(payload: dict[str, Any]) -> tuple[int, int, int]:
    return mailer.digest_figures(payload)


def body_line(payload: dict[str, Any]) -> str:
    """The one line Android shows under the title: "4 waiting · 2 overdue · 11 new · Districts documented 41"."""
    waiting, overdue, new = figures(payload)
    coverage = payload.get("coverage") or {}
    return (
        f"{waiting} waiting · {overdue} overdue · {new} new · "
        f"{coverage.get('label') or COVERAGE_LABEL} {mailer.coverage_figure(coverage)}"
    )


# --------------------------------------------------------------------------------------
# Delivery
# --------------------------------------------------------------------------------------


async def _complete(
    row: Any, user: Any, week_start: date, *, now: datetime, districts: int | None, settings: Settings | None = None
) -> str:
    """Finish one claimed PENDING row: opt-out, build, notify, mark. Returns the row's new status."""
    if not await wants_email(user.id, PREFERENCE):
        await db.weeklydigest.update(where={"id": row.id}, data={"status": SKIPPED_OPTOUT})
        return SKIPPED_OPTOUT
    payload = await build_payload(user, week_start, now=now, districts=districts)
    waiting, overdue, new = figures(payload)
    if not (waiting or overdue or new):
        await db.weeklydigest.update(where={"id": row.id}, data={"status": SKIPPED_EMPTY})
        return SKIPPED_EMPTY
    delivery = await notify(
        user,
        KIND,
        mailer.digest_title(waiting, overdue),
        body_line(payload),
        f"/digest/{row.id}",
        email_kind=mailer.WEEKLY_DIGEST,
        email_params={"digestId": row.id, **payload},
        preference=PREFERENCE,
        settings=settings,
    )
    if delivery.notification is None and delivery.email is None:
        # notify never raises; nothing written means a failed write. Leave it PENDING for repair.
        logger.warning("Weekly digest %s for account %s wrote nothing; left for the next pass", row.id, user.id)
        return PENDING
    await db.weeklydigest.update(
        where={"id": row.id},
        data={
            "status": DELIVERED,
            "payload": Json(payload),
            "notificationId": get_value(delivery.notification, "id"),
            "emailMessageId": get_value(delivery.email, "id"),
        },
    )
    logger.info(
        "Weekly digest %s delivered to account %s (waiting=%d overdue=%d new=%d emailed=%s)",
        row.id, user.id, waiting, overdue, new, delivery.emailed,
    )
    return DELIVERED


async def deliver_one(
    user: Any,
    week_start: date,
    *,
    now: datetime | None = None,
    districts: int | None = None,
    settings: Settings | None = None,
) -> str | None:
    """This week's summary for one person, at most once. ``None`` when another pass already has it."""
    now = _aware(now or datetime.now(UTC))
    try:
        row = await db.weeklydigest.create(
            data={"user": {"connect": {"id": user.id}}, "weekStart": _week_key(week_start), "status": PENDING}
        )
    except UniqueViolationError:
        return None
    return await _complete(row, user, week_start, now=now, districts=districts, settings=settings)


async def _reclaim(
    week_start: date, now: datetime, districts: int | None, tally: dict[str, int], settings: Settings
) -> None:
    cutoff = now - RECLAIM_AFTER
    stale = await db.weeklydigest.find_many(
        where={"weekStart": _week_key(week_start), "status": PENDING, "updatedAt": {"lt": cutoff}},
        take=BATCH_SIZE * 4,
    )
    for row in stale:
        claimed = await db.weeklydigest.update_many(
            where={"id": row.id, "status": PENDING, "updatedAt": {"lt": cutoff}}, data={"updatedAt": now}
        )
        if not claimed:
            continue  # another pass reclaimed it first
        user = await db.user.find_unique(where={"id": row.userId})
        if user is None:
            continue
        try:
            outcome = await _complete(row, user, week_start, now=now, districts=districts, settings=settings)
        except Exception:
            logger.exception("Weekly digest %s could not be completed on reclaim", row.id)
            tally["failed"] += 1
            continue
        tally["reclaimed"] += 1
        _count_outcome(tally, outcome)


def _count_outcome(tally: dict[str, int], outcome: str | None) -> None:
    key = {DELIVERED: "delivered", SKIPPED_EMPTY: "empty", SKIPPED_OPTOUT: "optedOut"}.get(outcome or "", "skipped")
    tally[key] += 1


def _empty_tally() -> dict[str, int]:
    return {"delivered": 0, "empty": 0, "optedOut": 0, "skipped": 0, "reclaimed": 0, "failed": 0, "purged": 0}


async def run_weekly_digests(
    now: datetime | None = None, force: bool = False, settings: Settings | None = None
) -> dict[str, Any]:
    """One scheduler pass, called by the queue worker on every loop. Returns a tally.

    Off when ``WEEKLY_DIGEST_ENABLED`` is false. Outside Monday 08:00–23:59 IST it returns at once
    without a query; inside, it looks at most every :data:`CHECK_EVERY`. ``force`` skips the window
    and the throttle (tests)."""
    global _last_check
    settings = settings or get_settings()
    tally: dict[str, Any] = {"ran": False, **_empty_tally()}
    if not settings.weekly_digest_enabled:
        return tally
    now = _aware(now or datetime.now(UTC))
    if not force:
        if not in_send_window(now):
            return tally
        if _last_check is not None and timedelta(0) <= now - _last_check < CHECK_EVERY:
            return tally
        _last_check = now
    tally["ran"] = True
    week_start = week_start_for(now)

    done = {
        row.userId for row in await db.weeklydigest.find_many(where={"weekStart": _week_key(week_start)})
    }
    owed = [user for user in await eligible_users() if user.id not in done]
    districts = await districts_documented() if owed else None
    for offset in range(0, len(owed), BATCH_SIZE):
        for user in owed[offset : offset + BATCH_SIZE]:
            try:
                _count_outcome(tally, await deliver_one(user, week_start, now=now, districts=districts, settings=settings))
            except Exception:
                logger.exception("Weekly digest for account %s failed; it is reclaimed later", user.id)
                tally["failed"] += 1
        await asyncio.sleep(0)

    await _reclaim(week_start, now, districts, tally, settings)
    tally["purged"] = int(
        await db.weeklydigest.delete_many(
            where={"weekStart": {"lt": _week_key(week_start - timedelta(weeks=RETENTION_WEEKS))}}
        )
        or 0
    )
    if any(tally[key] for key in _empty_tally()):
        logger.info("Weekly digest pass for %s: %s", week_start.isoformat(), {k: tally[k] for k in _empty_tally()})
    return tally


# --------------------------------------------------------------------------------------
# Reading one back
# --------------------------------------------------------------------------------------


def _payload(row: Any) -> dict[str, Any]:
    value = get_value(row, "payload")
    return value if isinstance(value, dict) else {}


def serialize_digest(row: Any) -> dict[str, Any]:
    """The summary page's shape (``GET /notifications/digests/{id}``)."""
    payload = _payload(row)
    generated = get_value(row, "updatedAt") or get_value(row, "createdAt")
    if isinstance(generated, datetime):
        generated = _aware(generated).astimezone(UTC).isoformat()
    sections = []
    for section in payload.get("sections") or []:
        if not isinstance(section, dict):
            continue
        lines = [
            {**line, "link": _app_path(line.get("link"))}
            for line in section.get("lines") or []
            if isinstance(line, dict) and int(line.get("count") or 0) > 0
        ]
        items = [
            {**item, "link": _app_path(item.get("link"))}
            for item in (section.get("items") or [])[:MAX_ITEMS]
            if isinstance(item, dict)
        ]
        sections.append({**section, "lines": lines, "items": items})
    return {
        "id": get_value(row, "id"),
        "weekStart": _date_text(get_value(row, "weekStart")),
        "generatedAt": generated,
        "sections": sections,
        "coverage": payload.get("coverage"),
    }


def summarize_digest(row: Any) -> dict[str, Any]:
    """The Settings card's "latest" (``GET /notifications/digest``)."""
    payload = _payload(row)
    waiting, overdue, new = figures(payload)
    return {
        "id": get_value(row, "id"),
        "weekStart": _date_text(get_value(row, "weekStart")),
        "waiting": waiting,
        "overdue": overdue,
        "newThisWeek": new,
        "coverage": payload.get("coverage"),
    }
