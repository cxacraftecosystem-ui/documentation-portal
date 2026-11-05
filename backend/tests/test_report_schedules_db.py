from __future__ import annotations

import asyncio
import contextlib
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from qv2_fixtures import connect, needs_db, patch_db

from app.core.config import get_settings
from app.core.security import create_access_token
from app.services import email_outbox, mailer, s3
from app.services import report_schedules as rs

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]
WORKBOOK = b"PK\x03\x04" + b"x" * 64


class _Db:
    def __getattr__(self, name: str) -> Any:
        import app.core.db as core_db

        return getattr(core_db.db, name)


db = _Db()


def _private(monkeypatch: Any, work: Any) -> Any:
    from app.api.routes import data_browser, data_explorer, data_report_schedule

    assert data_browser and data_explorer and data_report_schedule

    async def connected() -> Any:
        client = await connect()
        patch_db(monkeypatch, client)
        try:
            return await work()
        finally:
            await client.disconnect()

    return asyncio.run(connected())


def asgi_client() -> httpx.AsyncClient:
    from app.main import app

    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://schedules.test")


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


def _mail(**extra: Any) -> Any:
    return get_settings().model_copy(
        update={"mail_from_address": "reports@example.org", "report_schedules_enabled": True, **extra}
    )


@pytest.fixture
def storage(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    kept: dict[str, Any] = {"put": {}, "deleted": [], "built": []}

    def put(key: str, data: bytes, *, mime_type: str) -> None:
        kept["put"][key] = data

    def delete(key: str) -> None:
        kept["deleted"].append(key)

    async def build(owner: Any, path: str, options: dict[str, Any], q: str | None) -> tuple[bytes, str]:
        kept["built"].append({"owner": owner.id, "path": path, "options": options, "q": q})
        return kept.get("blob", WORKBOOK), "DesignWorkshop_Dataset_2026-11-09_Report.xlsx"

    monkeypatch.setattr(s3, "put_object_bytes", put)
    monkeypatch.setattr(s3, "delete_object", delete)
    monkeypatch.setattr(s3, "get_object_bytes", lambda key, **_: kept["put"][key])
    monkeypatch.setattr(rs, "build_workbook", build)
    return kept


class Seed:
    def __init__(self) -> None:
        self.users: list[str] = []

    async def user(self, label: str, role: str = "MASTER_ADMIN", **extra: Any) -> Any:
        row = await db.user.create(
            data={
                "email": f"rs-{label}-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
                "name": f"Scheduler {label}",
                "role": role,
                "canDownloadDataset": True,
                **extra,
            }
        )
        self.users.append(row.id)
        return row

    async def view(self, owner: Any, *, path: str = "", shared: bool = False, q: str | None = None) -> Any:
        from prisma import Json

        return await db.datasavedview.create(
            data={
                "ownerId": owner.id,
                "name": f"Weekly {STAMP}",
                "path": path,
                "state": Json({"view": "tables", "sheet": None, "q": q, "filters": {}}),
                "shared": shared,
            }
        )

    async def schedule(self, owner: Any, view: Any, *, last_slot: datetime | None = None, **extra: Any) -> Any:
        schedule_id = rs.new_id()
        return await db.datareportschedule.create(
            data={
                "id": schedule_id,
                "ownerId": owner.id,
                "savedViewId": view.id,
                "weekday": 0,
                "hourLocal": 8,
                "timezone": rs.DEFAULT_TIMEZONE,
                "options": rs.options_json(rs.options_of({})),
                "lastSlotAt": last_slot,
                "unsubscribeTokenHash": rs.token_digest(rs.unsubscribe_token(schedule_id)),
                **extra,
            }
        )

    async def cleanup(self) -> None:
        for step in (
            db.emailmessage.delete_many(where={"recipientId": {"in": self.users}}),
            db.datareportschedule.delete_many(where={"ownerId": {"in": self.users}}),
            db.datasavedview.delete_many(where={"ownerId": {"in": self.users}}),
            db.user.delete_many(where={"id": {"in": self.users}}),
        ):
            with contextlib.suppress(Exception):
                await step


def _monday(now: datetime | None = None) -> datetime:
    moment = now or datetime.now(UTC)
    return rs.latest_slot(moment, 0, 8, rs.DEFAULT_TIMEZONE) + timedelta(minutes=20)


async def _emails(owner_id: str) -> list[Any]:
    return await db.emailmessage.find_many(
        where={"recipientId": owner_id, "kind": mailer.REPORT_SCHEDULE}, order={"createdAt": "asc"}
    )


def test_owners_create_list_pause_and_delete_their_schedules(storage, monkeypatch):
    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("owner")
            stranger = await seed.user("stranger")
            view = await seed.view(owner)
            private = await seed.view(stranger)
            async with asgi_client() as client:
                made = await client.post(
                    "/api/data/report/schedules",
                    json={"savedViewId": view.id, "weekday": 2, "hourLocal": 9,
                          "options": {"answers": "english", "extras": {"quotes": True}}},
                    headers=_auth(owner),
                )
                assert made.status_code == 200, made.text
                body = made.json()
                assert body["viewName"] == view.name
                assert body["label"] == "every Wednesday at 09:00 IST"
                assert body["paused"] is False
                assert body["options"]["extras"] == {"quotes": True}
                assert datetime.fromisoformat(body["nextRunAt"]) > datetime.now(UTC)

                refused = await client.post(
                    "/api/data/report/schedules", json={"savedViewId": private.id}, headers=_auth(owner)
                )
                assert refused.status_code == 404
                bad = await client.post(
                    "/api/data/report/schedules",
                    json={"savedViewId": view.id, "weekday": 9},
                    headers=_auth(owner),
                )
                assert bad.status_code == 422

                listed = (await client.get("/api/data/report/schedules", headers=_auth(owner))).json()
                assert [item["id"] for item in listed["items"]] == [body["id"]]
                assert listed["maxPerOwner"] == rs.MAX_PER_OWNER
                theirs = (await client.get("/api/data/report/schedules", headers=_auth(stranger))).json()
                assert theirs["items"] == []

                path = f"/api/data/report/schedules/{body['id']}"
                assert (await client.patch(path, json={"paused": True}, headers=_auth(stranger))).status_code == 404
                paused = (await client.patch(path, json={"paused": True}, headers=_auth(owner))).json()
                assert paused["paused"] is True and paused["nextRunAt"] is None
                resumed = (
                    await client.patch(path, json={"paused": False, "weekday": 0}, headers=_auth(owner))
                ).json()
                assert resumed["paused"] is False and resumed["weekday"] == 0
                assert datetime.fromisoformat(resumed["nextRunAt"]) > datetime.now(UTC)

                assert (await client.delete(path, headers=_auth(stranger))).status_code == 404
                assert (await client.delete(path, headers=_auth(owner))).status_code == 204
                assert await db.datareportschedule.find_unique(where={"id": body["id"]}) is None
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_a_new_schedule_does_not_send_until_its_next_occurrence(storage, monkeypatch):
    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("fresh")
            view = await seed.view(owner)
            async with asgi_client() as client:
                made = (
                    await client.post(
                        "/api/data/report/schedules",
                        json={"savedViewId": view.id, "weekday": 0, "hourLocal": 8},
                        headers=_auth(owner),
                    )
                ).json()
            row = await db.datareportschedule.find_unique(where={"id": made["id"]})
            assert rs.due_slot(row, datetime.now(UTC)) is None
            assert await rs.run_once(row, datetime.now(UTC), _mail()) == "NOT_DUE"
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_a_due_schedule_sends_once_per_week_even_when_two_passes_race(storage, monkeypatch):
    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("race")
            view = await seed.view(owner, q="indigo")
            now = _monday()
            row = await seed.schedule(owner, view, last_slot=rs.latest_slot(now, 0, 8, rs.DEFAULT_TIMEZONE) - timedelta(days=7))
            first, second = await asyncio.gather(
                rs.run_once(row, now, _mail()), rs.run_once(row, now, _mail())
            )
            assert sorted([first, second]) == ["EXISTS", "SENT"]
            again = await db.datareportschedule.find_unique(where={"id": row.id})
            assert await rs.run_once(again, now + timedelta(hours=3), _mail()) == "NOT_DUE"
            emails = await _emails(row.ownerId)
            assert len(emails) == 1
            assert emails[0].kind == mailer.REPORT_SCHEDULE
            assert emails[0].toAddress == owner.email
            assert emails[0].params["attachmentKey"].startswith(f"report-schedules/{row.id}/")
            assert storage["built"] == [
                {"owner": owner.id, "path": view.path, "options": rs.options_of({}), "q": "indigo"}
            ]
            assert again.lastSentAt is not None and again.lastError is None
            assert again.lastAttached is True and again.lastFileBytes == len(WORKBOOK)
            next_week = now + timedelta(days=7)
            assert await rs.run_once(again, next_week, _mail()) == "SENT"
            assert len(await _emails(row.ownerId)) == 2
            assert storage["deleted"] == [again.lastFileKey]
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_the_runner_pass_is_idempotent(storage, monkeypatch):
    monkeypatch.setattr(rs, "SENDS_PER_PASS", 10_000)

    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("pass")
            view = await seed.view(owner)
            now = _monday()
            row = await seed.schedule(owner, view, last_slot=None)
            await asyncio.gather(
                rs.run_report_schedules(now, force=True, settings=_mail()),
                rs.run_report_schedules(now, force=True, settings=_mail()),
            )
            await rs.run_report_schedules(now, force=True, settings=_mail())
            assert len(await _emails(row.ownerId)) == 1
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_the_owner_scope_is_read_at_send_time(storage, monkeypatch):
    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("lost", role="RESEARCHER")
            view = await seed.view(owner)
            now = _monday()
            row = await seed.schedule(owner, view)
            await db.user.update(where={"id": owner.id}, data={"canDownloadDataset": False})
            assert await rs.run_once(row, now, _mail()) == "SKIPPED"
            after = await db.datareportschedule.find_unique(where={"id": row.id})
            assert after.lastError == rs.LOST_DOWNLOAD
            assert await _emails(row.ownerId) == []
            assert storage["built"] == []
            assert rs.due_slot(after, now) is None
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_a_view_that_stops_being_shared_is_not_sent(storage, monkeypatch):
    async def work() -> None:
        seed = Seed()
        try:
            sharer = await seed.user("sharer")
            reader = await seed.user("reader")
            view = await seed.view(sharer, shared=True)
            async with asgi_client() as client:
                made = await client.post(
                    "/api/data/report/schedules", json={"savedViewId": view.id}, headers=_auth(reader)
                )
            assert made.status_code == 200, made.text
            await db.datasavedview.update(where={"id": view.id}, data={"shared": False})
            row = await db.datareportschedule.update(
                where={"id": made.json()["id"]}, data={"lastSlotAt": None}
            )
            assert await rs.run_once(row, _monday(), _mail()) == "SKIPPED"
            after = await db.datareportschedule.find_unique(where={"id": row.id})
            assert after.lastError == rs.LOST_VIEW
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_a_folder_the_owner_cannot_see_now_is_not_sent(storage, monkeypatch):
    async def hidden(_scope: Any, _path: str) -> bool:
        return False

    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("folder")
            view = await seed.view(owner, path="by-workshop/gone")
            row = await seed.schedule(owner, view)
            monkeypatch.setattr(rs, "_visible", hidden)
            assert await rs.run_once(row, _monday(), _mail()) == "SKIPPED"
            after = await db.datareportschedule.find_unique(where={"id": row.id})
            assert after.lastError == rs.LOST_FOLDER
            assert storage["built"] == []
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_a_large_workbook_is_linked_and_downloads_for_its_owner_only(storage, monkeypatch):
    monkeypatch.setattr(s3, "presign_get_url", lambda key, **kwargs: f"https://signed.example.org/{key}")

    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("large")
            stranger = await seed.user("peek")
            view = await seed.view(owner)
            row = await seed.schedule(owner, view)
            assert await rs.run_once(row, _monday(), _mail(report_schedule_attach_max_bytes=10)) == "SENT"
            emails = await _emails(row.ownerId)
            assert "attachmentKey" not in emails[0].params
            after = await db.datareportschedule.find_unique(where={"id": row.id})
            assert after.lastAttached is False
            async with asgi_client() as client:
                mine = await client.get(f"/api/data/report/schedules/{row.id}/file", headers=_auth(owner))
                theirs = await client.get(f"/api/data/report/schedules/{row.id}/file", headers=_auth(stranger))
            assert mine.status_code == 200, mine.text
            assert mine.json()["url"].endswith(after.lastFileKey)
            assert mine.json()["fileName"] == after.lastFileName
            assert theirs.status_code == 404
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_a_failed_build_releases_the_week_and_waits(storage, monkeypatch):
    async def broken(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("builder fell over")

    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("retry")
            view = await seed.view(owner)
            now = _monday()
            row = await seed.schedule(owner, view)
            monkeypatch.setattr(rs, "build_workbook", broken)
            assert await rs.run_once(row, now, _mail()) == "FAILED"
            after = await db.datareportschedule.find_unique(where={"id": row.id})
            assert after.lastSlotAt is None
            assert after.lastError == rs.NOT_BUILT
            assert rs.due_slot(after, now) is None
            assert rs.due_slot(after, now + rs.RETRY_AFTER + timedelta(seconds=1)) is not None
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_the_queued_mail_carries_the_workbook_and_unsubscribe_header(storage, monkeypatch):
    sent: list[dict[str, Any]] = []

    def capture(**kwargs: Any) -> str:
        sent.append(kwargs)
        return "ses-1"

    monkeypatch.setattr(mailer, "send", capture)

    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("mail", email=f"rs-mail-{STAMP}@weekly-reports.in")
            view = await seed.view(owner)
            row = await seed.schedule(owner, view)
            assert await rs.run_once(row, _monday(), _mail()) == "SENT"
            message = (await _emails(row.ownerId))[0]
            assert await email_outbox._send_one(message, _mail()) == email_outbox.SENT
            assert len(sent) == 1
            attachment = sent[0]["attachments"][0]
            assert attachment.data == WORKBOOK
            assert attachment.file_name.endswith(".xlsx")
            assert attachment.mime_type == rs.XLSX_MIME
            header = dict(sent[0]["headers"])["List-Unsubscribe"]
            assert rs.unsubscribe_token(row.id) in header
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)


def test_the_unsubscribe_link_pauses_without_signing_in(storage, monkeypatch):
    async def work() -> None:
        seed = Seed()
        try:
            owner = await seed.user("leave")
            view = await seed.view(owner)
            row = await seed.schedule(owner, view)
            token = rs.unsubscribe_token(row.id)
            async with asgi_client() as client:
                looked = await client.post("/api/report-schedule/unsubscribe/lookup", json={"token": token})
                assert looked.status_code == 200
                assert looked.json() == {
                    "viewName": view.name, "label": "every Monday at 08:00 IST", "paused": False
                }
                assert (await db.datareportschedule.find_unique(where={"id": row.id})).pausedAt is None
                left = await client.post("/api/report-schedule/unsubscribe", json={"token": token})
                assert left.status_code == 200 and left.json()["paused"] is True
                twice = await client.post("/api/report-schedule/unsubscribe", json={"token": token})
                assert twice.status_code == 200 and twice.json()["paused"] is True
                unknown = await client.post("/api/report-schedule/unsubscribe", json={"token": "nope"})
                assert unknown.status_code == 404
                empty = await client.post("/api/report-schedule/unsubscribe/lookup", json={})
                assert empty.status_code == 404
            after = await db.datareportschedule.find_unique(where={"id": row.id})
            assert after.pausedAt is not None
            assert rs.due_slot(after, _monday()) is None
        finally:
            await seed.cleanup()

    _private(monkeypatch, work)
