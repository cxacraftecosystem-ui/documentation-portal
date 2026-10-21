"""Shared fixtures for the questionnaire v2 suites that run against a real, migrated Postgres.

THE DATABASE IS A THROWAWAY ONE, NEVER ``backend/.env`` (that is production). Point
``QV2_TEST_DATABASE_URL`` at a database migrated through ``20261025090000_questionnaire_v2``;
without it every DB-backed test skips and says why. Rows are seeded under unique suffixes and left
behind (the database is thrown away), so the suites can share one database.

No test here calls a speech-to-text provider or S3: ``no_outside_calls`` replaces every provider
entry point with one that fails the test, and the S3 helpers with local stand-ins.
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import sys
import uuid
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

URL = os.environ.get("QV2_TEST_DATABASE_URL", "").strip()
needs_db = pytest.mark.skipif(not URL, reason="QV2_TEST_DATABASE_URL is not set (a migrated throwaway database)")


def run(coro: Any) -> Any:
    return asyncio.run(coro)


async def connect() -> Any:
    from prisma import Prisma

    client = Prisma(datasource={"url": URL})
    await client.connect()
    return client


def patch_db(monkeypatch: pytest.MonkeyPatch, client: Any) -> None:
    """Point every loaded ``app`` module's ``db`` at ``client``."""
    # Every module holding a Prisma client as ``db``, not only the ones holding the original: a module
    # first imported while an earlier test had patched ``app.core.db`` keeps THAT test's client.
    import app.core.db as core_db
    from prisma import Prisma

    monkeypatch.setattr(core_db, "db", client)
    for name, module in list(sys.modules.items()):
        if (name.startswith("app.") or name.startswith("scripts.")) and isinstance(getattr(module, "db", None), Prisma):
            monkeypatch.setattr(module, "db", client)


def settings_v2(monkeypatch: pytest.MonkeyPatch, enabled: bool = True) -> None:
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "questionnaire_v2", enabled, raising=False)


class ProviderCalled(AssertionError):
    pass


def no_outside_calls(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """Fail the test on any provider call; replace S3 with local stand-ins."""
    from app.services import ai, media_queue, s3

    def refuse(*args: Any, **kwargs: Any) -> Any:
        raise ProviderCalled("a speech-to-text or AI provider was called")

    async def refuse_async(*args: Any, **kwargs: Any) -> Any:
        raise ProviderCalled("a speech-to-text or AI provider was called")

    for module in (ai, media_queue):
        for name in ("transcribe_audio_bytes", "refine_transcript_text"):
            if hasattr(module, name):
                monkeypatch.setattr(module, name, refuse_async)
    stored: dict[str, bytes] = {}
    calls: dict[str, list] = {"presign": [], "multipart": [], "deleted": [], "aborted": []}
    missing: set[str] = set()

    def presign(key: str, mime: str, checksum: str | None, expires_in: int = 900) -> str:
        calls["presign"].append((key, checksum))
        return f"https://s3.test/{key}?sig={uuid.uuid4().hex[:8]}"

    def create_multipart(key: str, mime: str) -> str:
        calls["multipart"].append(key)
        return f"upl-{uuid.uuid4().hex[:10]}"

    monkeypatch.setattr(s3, "presign_put_url_checksum", presign)
    monkeypatch.setattr(s3, "create_multipart_upload", create_multipart)
    monkeypatch.setattr(s3, "list_uploaded_parts", lambda key, upload_id: [{"partNumber": 1, "etag": '"e1"'}])
    monkeypatch.setattr(s3, "public_url_for_key", lambda key: f"https://cdn.test/{key}")
    monkeypatch.setattr(s3, "presign_get_url", lambda key, filename, mime_type, expires_in=900: f"https://s3.test/{key}?dl={filename}")
    monkeypatch.setattr(s3, "stream_sha256", lambda key: (hashlib.sha256(stored.get(key, key.encode())).hexdigest(), len(stored.get(key, b""))))
    monkeypatch.setattr(s3, "get_object_bytes", lambda key: refuse())
    monkeypatch.setattr(s3, "delete_object", lambda key: calls["deleted"].append(key))
    # /complete checks the object is there (audit Low); a test lists the keys that are NOT.
    monkeypatch.setattr(s3, "object_exists", lambda key: key not in missing)
    monkeypatch.setattr(s3, "abort_multipart_upload", lambda key, upload_id: calls["aborted"].append((key, upload_id)))
    return {"stored": stored, "calls": calls, "missing": missing}


def user(role: str, uid: str, name: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        id=uid, email=f"{uid}@example.test", name=name or uid, role=role, canReview=role in ("PROFESSOR", "ADMIN"),
        canDownloadDataset=False, canViewProvenance=False, canManageQuestionnaire=False,
    )


async def seed(client: Any, *, sections: dict[str, int] | None = None, artisans: int = 3, workshop: bool = True,
               paused: bool = True) -> SimpleNamespace:
    """Users, an instrument with sections and questions, a workshop at Bagru, Jaipur with one craft,
    and ``artisans`` artisans. Returns their ids."""
    t = uuid.uuid4().hex[:10]
    ids = SimpleNamespace(tag=t)
    people = {"lead": "RESEARCHER", "other": "RESEARCHER", "prof": "PROFESSOR", "admin": "ADMIN", "vol": "CROWDSOURCE_VOLUNTEER"}
    for key, role in people.items():
        uid = f"u{key}{t}"
        await client.execute_raw(
            'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, $4::"UserRole", now())',
            uid, f"{uid}@example.test", key.title(), role,
        )
        setattr(ids, key, user(role, uid, key.title()))
    await client.execute_raw(
        'INSERT INTO "AppSetting" ("id", "updatedAt", "transcriptionPaused") VALUES (\'singleton\', now(), $1) '
        'ON CONFLICT ("id") DO UPDATE SET "transcriptionPaused" = EXCLUDED."transcriptionPaused"',
        paused,
    )
    ids.q = f"q{t}"
    await client.execute_raw(
        'INSERT INTO "Questionnaire" ("id", "title", "sortOrder", "updatedAt") VALUES ($1, $2, 1, now())', ids.q, f"Craft Survey {t}"
    )
    ids.sections, ids.questions = {}, {}
    for order, (code, count) in enumerate((sections or {"B": 2, "D": 4, "F": 7}).items(), start=1):
        sid = f"s{code}{t}"
        await client.execute_raw(
            'INSERT INTO "QuestionnaireSection" ("id", "questionnaireId", "code", "title", "sortOrder", "updatedAt") VALUES ($1, $2, $3, $4, $5, now())',
            sid, ids.q, code, f"Section {code}", order,
        )
        ids.sections[code] = sid
        for n in range(1, count + 1):
            qid = f"q{code}{n}{t}"
            await client.execute_raw(
                'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", "sortOrder", '
                '"sectionId", "updatedAt") VALUES ($1, $2, $3, $4, $5, $6, $7, now())',
                qid, ids.q, code, f"Section {code}", f"Question {code}{n} about tools and materials number {n}", n, sid,
            )
            ids.questions[f"{code}{n}"] = qid
    ids.craft = f"c{t}"
    await client.execute_raw('INSERT INTO "Craft" ("id", "name", "createdById", "updatedAt") VALUES ($1, $2, $3, now())',
                             ids.craft, f"Hand Block Printing {t}", ids.admin.id)
    ids.workshop = None
    if workshop:
        ids.location = f"l{t}"
        await client.execute_raw(
            'INSERT INTO "Location" ("id", "latitude", "longitude", "village", "district", "state", "updatedAt") '
            "VALUES ($1, 26.8, 75.5, 'Bagru', 'Jaipur', 'Rajasthan', now())", ids.location,
        )
        ids.workshop = f"w{t}"
        await client.execute_raw(
            'INSERT INTO "Workshop" ("id", "title", "date", "startDate", "endDate", "place", "createdById", "locationId", "status", "updatedAt") '
            "VALUES ($1, 'Bagru workshop', $2::timestamp, $2::timestamp, $3::timestamp, 'Bagru', $4, $5, 'APPROVED', now())",
            ids.workshop, "2026-06-19T00:00:00", "2026-06-24T00:00:00", ids.admin.id, ids.location,
        )
        await client.execute_raw('INSERT INTO "WorkshopCraft" ("workshopId", "craftId") VALUES ($1, $2)', ids.workshop, ids.craft)
    ids.artisans = []
    names = ["Giriraj Prasad Chhipa", "Meena Pal", "Ravi Karmakar", "Sabita Dutta", "Bina Doley", "Ranu Das"]
    for i in range(artisans):
        aid = f"a{i}{t}"
        await client.execute_raw(
            'INSERT INTO "Artisan" ("id", "name", "place", "phone", "status", "createdById", "updatedAt", "submittedAt", "craftId") '
            "VALUES ($1, $2, 'Bagru', $3, 'PENDING', $4, now(), now(), $5)",
            aid, names[i % len(names)], "9876543210" if i == 0 else None, ids.admin.id, ids.craft,
        )
        ids.artisans.append(aid)
    lineage = await client.query_raw('SELECT "id", "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1', ids.q)
    ids.codes = {str(r["id"]): r["displayCode"] for r in lineage}
    return ids


def session_create(ids: SimpleNamespace, artisans: list[str], *, key: str | None = None, day: str = "2026-06-20", **extra: Any) -> Any:
    from app.schemas.questionnaire_v2 import SessionCreate

    return SessionCreate.model_validate({
        "clientKey": key or f"c-{uuid.uuid4().hex[:12]}", "questionnaireId": ids.q, "workshopId": ids.workshop,
        "sessionDate": day, "startedAt": f"{day}T05:00:00Z", "purpose": "FIRST_SITTING",
        "participants": [{"artisanId": a} for a in artisans], **extra,
    })


def complete_request(object_key: str, session_id: str, *, clip: str | None = None, targets: list[dict] | None = None,
                     subjects: list[dict] | None = None, at: datetime | None = None, offset: int | None = 0, **rec: Any) -> Any:
    from app.schemas.questionnaire_v2 import CompleteRequest

    at = at or datetime(2026, 6, 20, 6, 0, tzinfo=UTC)
    return CompleteRequest.model_validate({
        "objectKey": object_key, "clientClipKey": clip or f"k-{uuid.uuid4().hex[:10]}", "linkedRecordType": "questionnaire_session",
        "linkedRecordId": session_id,
        "recording": {
            "targets": targets or [], "subjects": subjects or [], "deviceCapturedAt": at.isoformat(), "clockOffsetMs": offset,
            "capturedAtSource": "DEVICE", "durationMs": 60000, **rec,
        },
    })


def key_for(u: Any, leaf: str = "clip.m4a") -> str:
    return f"media/{u.id}/{uuid.uuid4().hex}/{leaf}"


async def record_take(client: Any, ids: SimpleNamespace, session_id: str, u: Any, *, targets: list[dict], at: datetime,
                      subjects: list[dict] | None = None, **rec: Any) -> dict[str, Any]:
    from app.services.questionnaire_v2 import recordings

    recording, _created = await recordings.complete(
        complete_request(key_for(u), session_id, targets=targets, subjects=subjects, at=at, **rec), u, client=client
    )
    return recording


def t(hours: float = 0, *, base: datetime | None = None) -> datetime:
    return (base or datetime(2026, 6, 20, 6, 0, tzinfo=UTC)) + timedelta(hours=hours)
