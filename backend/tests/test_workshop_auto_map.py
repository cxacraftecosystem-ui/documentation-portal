"""The nightly workshop filing (A6): only the clear-cut rows, once a night, and the card's new counts.

  * the job files PARENT-rung rows and nothing else — a row the artisans or the dates merely suggest
    is an admin's call, and filing it unasked would be a guess dressed as a fact;
  * it runs once per local night however many processes ask, because the night is CLAIMED;
  * the admin card's payload leads with what still needs a person, and lists those rows first.
"""

import asyncio
from datetime import UTC, datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeTable, install
from app.api.router import api_router
from app.core import deps
from app.services import workshop_auto_map, workshop_inference
from app.services.workshop_auto_map import is_due, maybe_run_nightly_auto_map, night_start
from app.services.workshop_inference import (
    RUNG_ARTISANS,
    RUNG_PARENT,
    RUNG_WINDOW,
    BucketPlan,
    LadderRun,
    RowPlan,
    BUCKET_KEYS,
    payload_for,
)

# A fixed offset rather than ZoneInfo, so the suite runs on a checkout without tzdata; IST has no DST.
IST = timezone(timedelta(hours=5, minutes=30))


class _Db:
    def __init__(self, **seeds: list[dict[str, Any]]) -> None:
        object.__setattr__(self, "_tables", {name: FakeTable({}, rows) for name, rows in seeds.items()})

    def __getattr__(self, name: str) -> Any:
        tables = object.__getattribute__(self, "_tables")
        if name not in tables:
            tables[name] = FakeTable({}, [])
        return tables[name]


def _run() -> LadderRun:
    plans = {bucket: BucketPlan(bucket=bucket) for bucket in BUCKET_KEYS}
    media = plans["media"]
    media.rows = [
        RowPlan(id="m-parent", title="clip.m4a", workshopId="w1", rung=RUNG_PARENT, candidates=["w1"]),
        RowPlan(id="m-window", title="photo.jpg", workshopId="w1", rung=RUNG_WINDOW, candidates=["w1"]),
        RowPlan(id="m-stuck", title="lost.jpg", reason="NO_EVIDENCE"),
    ]
    media.unassigned = 3
    interviews = plans["interviews"]
    interviews.rows = [RowPlan(id="i-art", title="Sitting", workshopId="w1", rung=RUNG_ARTISANS, candidates=["w1"])]
    interviews.unassigned = 1
    return LadderRun(plans=plans, workshopTitles={"w1": "2nd Toolkit Workshop"}, windows=[])


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch):
    fake = _Db(
        appsetting=[{"id": "singleton", "workshopAutoMapEnabled": True, "workshopAutoMapLastRunAt": None, "batchTimezone": "Asia/Kolkata"}],
        mediafile=[
            {"id": "m-parent", "workshopId": None},
            {"id": "m-window", "workshopId": None},
            {"id": "m-stuck", "workshopId": None},
        ],
        questionnaireinterview=[{"id": "i-art", "workshopId": None}],
    )
    install(monkeypatch, fake)

    async def fake_ladder() -> LadderRun:
        return _run()

    monkeypatch.setattr(workshop_inference, "run_ladder", fake_ladder)
    workshop_auto_map._last_checked.clear()
    workshop_auto_map._last_scrubbed.clear()
    return fake


def _workshop_of(fake: _Db, table: str, row_id: str) -> Any:
    return next(row.workshopId for row in getattr(fake, table).rows if row.id == row_id)


# --- the window, pure ---------------------------------------------------------------------------


def test_the_night_starts_at_two_local_and_not_before() -> None:
    before = datetime(2026, 10, 20, 1, 30, tzinfo=IST)
    after = datetime(2026, 10, 20, 2, 5, tzinfo=IST)
    assert night_start(before, IST) is None
    assert night_start(after, IST) == datetime(2026, 10, 20, 2, 0, tzinfo=IST).astimezone(UTC)


def test_it_is_due_once_per_night() -> None:
    now = datetime(2026, 10, 20, 3, 0, tzinfo=IST)
    assert is_due(None, now, IST) is True
    assert is_due(datetime(2026, 10, 19, 2, 1, tzinfo=IST), now, IST) is True
    assert is_due(datetime(2026, 10, 20, 2, 1, tzinfo=IST), now, IST) is False
    # A worker that was down overnight runs it during the day instead of skipping it.
    assert is_due(datetime(2026, 10, 19, 2, 1, tzinfo=IST), datetime(2026, 10, 20, 15, 0, tzinfo=IST), IST) is True


# --- the job ------------------------------------------------------------------------------------


def test_the_job_files_only_the_rows_their_parent_settles(db: _Db) -> None:
    now = datetime(2026, 10, 20, 2, 30, tzinfo=IST)
    result = asyncio.run(maybe_run_nightly_auto_map(now, force_check=True))
    assert result is not None and result["applied"] == 1
    assert _workshop_of(db, "mediafile", "m-parent") == "w1"
    assert _workshop_of(db, "mediafile", "m-window") is None
    assert _workshop_of(db, "questionnaireinterview", "i-art") is None
    settings = db.appsetting.rows[0]
    assert settings.workshopAutoMapLastRunAt == now
    assert settings.workshopAutoMapLastResult.data["applied"] == 1


def test_a_second_ask_the_same_night_does_nothing(db: _Db) -> None:
    now = datetime(2026, 10, 20, 2, 30, tzinfo=IST)
    asyncio.run(maybe_run_nightly_auto_map(now, force_check=True))
    assert asyncio.run(maybe_run_nightly_auto_map(datetime(2026, 10, 20, 4, 0, tzinfo=IST), force_check=True)) is None


def test_switched_off_it_files_nothing(db: _Db) -> None:
    db.appsetting.rows[0].workshopAutoMapEnabled = False
    assert asyncio.run(maybe_run_nightly_auto_map(datetime(2026, 10, 20, 2, 30, tzinfo=IST), force_check=True)) is None
    assert _workshop_of(db, "mediafile", "m-parent") is None


def test_the_admin_button_still_files_every_resolved_row(db: _Db) -> None:
    payload = asyncio.run(workshop_inference.apply_workshop_mapping())
    assert payload["totals"]["applied"] == 3
    assert _workshop_of(db, "mediafile", "m-window") == "w1"


# --- the card's payload -------------------------------------------------------------------------


def test_the_payload_leads_with_what_needs_a_person() -> None:
    payload = payload_for(_run())
    assert payload["totals"]["clearCut"] == 1
    assert payload["totals"]["needsReview"] == 3
    media = next(bucket for bucket in payload["buckets"] if bucket["bucket"] == "media")
    assert media["clearCut"] == 1 and media["needsReview"] == 2
    # Needs a person, then suggestions, then the clear-cut row the job files anyway.
    assert [row["id"] for row in media["rows"]] == ["m-stuck", "m-window", "m-parent"]
    assert [row["clearCut"] for row in media["rows"]] == [False, False, True]


_CURRENT: dict[str, Any] = {"user": None}
_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


def _call(method: str, path: str, body: Any = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://map.test") as client:
            return await client.request(method, f"/api{path}", json=body)

    return asyncio.run(run())


def _user(role: str) -> SimpleNamespace:
    return SimpleNamespace(id=f"u-{role}", email="x@example.test", name=role, role=role, canManageWorkshops=False,
                           canManageCrafts=False, canManageQuestionnaire=False, canDownloadDataset=False,
                           canReview=False, canViewProvenance=False)


def test_the_card_reads_the_jobs_state_and_an_admin_can_switch_it(db: _Db) -> None:
    _CURRENT["user"] = _user("ADMIN")
    body = _call("GET", "/workshops/unmapped").json()
    assert body["autoMap"]["enabled"] is True and body["autoMap"]["runsAt"] == "02:00"
    switched = _call("PUT", "/workshops/unmapped/auto", {"enabled": False})
    assert switched.status_code == 200 and switched.json()["enabled"] is False
    assert db.appsetting.rows[0].workshopAutoMapEnabled is False


def test_only_an_admin_may_switch_it(db: _Db) -> None:
    _CURRENT["user"] = _user("PROFESSOR")
    assert _call("PUT", "/workshops/unmapped/auto", {"enabled": False}).status_code == 403
    assert db.appsetting.rows[0].workshopAutoMapEnabled is True


# --- the privacy scrub rides its own gate --------------------------------------------------------


def test_switching_the_filing_off_never_switches_off_the_snapshot_scrub(db: _Db) -> None:
    from prisma import Json

    db.appsetting.rows[0].workshopAutoMapEnabled = False
    long_ago = datetime(2026, 8, 1, tzinfo=UTC)
    db.artisanmerge.rows.append(
        SimpleNamespace(
            id="mg1",
            status="DONE",
            createdAt=long_ago,
            mergedSnapshot=Json({"aadhaarNumber": "234123412346", "pehchanCardNumber": "PC12345678"}),
            survivorBefore=Json({}),
            survivorAfter=Json({}),
        )
    )
    now = datetime(2026, 10, 20, 2, 30, tzinfo=IST)
    assert asyncio.run(maybe_run_nightly_auto_map(now, force_check=True)) is None
    snap = db.artisanmerge.rows[0].mergedSnapshot
    snap = getattr(snap, "data", snap)
    assert snap["aadhaarNumber"] == "XXXX XXXX 2346" and snap["pehchanCardNumber"] == "XXXX5678"
    # Once a night: a second ask the same night does not scrub again.
    assert workshop_auto_map._last_scrubbed["at"] == now
