from __future__ import annotations

import asyncio
import hashlib
import hmac
import io
import json
import os
import socket
from collections.abc import Awaitable, Callable
from types import SimpleNamespace
from typing import Any
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

import pytest

_ON_ACTIONS = os.environ.get("GITHUB_ACTIONS") == "true"
_DSN = os.environ.get("DATABASE_URL", "") if _ON_ACTIONS else ""
_PARTS = urlsplit(_DSN) if _DSN else None
_LOCAL = bool(_PARTS and _PARTS.hostname in {"localhost", "127.0.0.1", "::1"})


def _listening() -> bool:
    if not _ON_ACTIONS or not _LOCAL or _PARTS is None:
        return False
    try:
        with socket.create_connection((_PARTS.hostname, _PARTS.port or 5432), timeout=1.0):
            return True
    except OSError:
        return False


pytestmark = pytest.mark.skipif(
    not _listening(),
    reason="runs only on GitHub Actions, with an exported DATABASE_URL naming a running Postgres on this machine (never backend/.env)",
)

TOKEN = "f" * 40
NONCE = "c" * 32
REQUESTED_BY = "kalavigyan-field-voices"
API = "http://portal.test/api"
PEER = "/cross-portal/peer/field-voices"
NEVER_IN_ANY_ANSWER = ("9876501234", "ismail.private@example.net", "370001", "House 12", "PEH-")


def _settings(**over: Any) -> SimpleNamespace:
    base = {
        "cross_portal_field_voices_enabled": True,
        "cross_portal_token": TOKEN,
        "field_voices_rights_holder": "IIT Kharagpur",
        "cross_portal_identity_enabled": False,
        "cross_portal_search_enabled": False,
        "cross_portal_craft_feed_enabled": False,
        "aadhaar_fingerprint_key": None,
        "field_repository_api_url": None,
        "designer_portal_api_url": None,
    }
    base.update(over)
    return SimpleNamespace(**base)


def _run(body: Callable[[], Awaitable[Any]]) -> Any:
    from app.core.db import db

    async def wrapper() -> Any:
        await db.connect()
        try:
            return await body()
        finally:
            await db.disconnect()

    return asyncio.run(wrapper())


def _jpeg(colour: tuple[int, int, int]) -> bytes:
    from PIL import Image

    buffer = io.BytesIO()
    image = Image.new("RGB", (64, 32), colour)
    exif = Image.Exif()
    exif[0x010F] = "Phone Maker"
    image.save(buffer, format="JPEG", exif=exif)
    return buffer.getvalue()


class Store:
    def __init__(self) -> None:
        self.sources: dict[str, bytes] = {}
        self.stored: dict[str, bytes] = {}
        self.deleted: list[str] = []
        self.refuse_deletes = False

    def fetch(self, key: str) -> bytes:
        return self.sources[key]

    def store(self, key: str, data: bytes, mime_type: str) -> None:
        self.stored[key] = data

    def delete(self, key: str) -> None:
        if self.refuse_deletes:
            raise RuntimeError("AccessDenied")
        self.deleted.append(key)
        self.stored.pop(key, None)


async def _x(sql: str, *args: Any) -> None:
    from app.core.db import db

    await db.execute_raw(sql, *args)


async def _user(world: SimpleNamespace, key: str, name: str, role: str = "RESEARCHER") -> str:
    uid = f"u{key}{world.tag}"
    await _x(
        'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, $4::"UserRole", now())',
        uid, f"{uid}@example.test", name, role,
    )
    world.users.append(uid)
    return uid


async def _location(world: SimpleNamespace, key: str, **fields: Any) -> str:
    lid = f"l{key}{world.tag}"
    await _x(
        'INSERT INTO "Location" ("id", "latitude", "longitude", "state", "district", "village", "placeName", "address", '
        '"pincode", "updatedAt") VALUES ($1, 23.25, 69.66, $2, $3, $4, $5, $6, $7, now())',
        lid, fields.get("state"), fields.get("district"), fields.get("village"), fields.get("placeName"),
        fields.get("address"), fields.get("pincode"),
    )
    world.locations.append(lid)
    return lid


async def _artisan(world: SimpleNamespace, key: str, name: str, *, born: str = "1970-01-01", location: str | None = None) -> str:
    aid = f"a{key}{world.tag}"
    await _x(
        'INSERT INTO "Artisan" ("id", "name", "place", "createdById", "phone", "phoneCountryCode", "email", '
        '"pehchanCardNumber", "dateOfBirth", "craftId", "locationId", "craftStartDate", "updatedAt") '
        "VALUES ($1, $2, 'Dhamadka', $3, '9876501234', '+91', 'ismail.private@example.net', $4, $5::timestamp, $6, $7, "
        "'1995-03-01'::timestamp, now())",
        aid, name, world.uploader, f"PEH-{key}-{world.tag}", born, world.craft, location,
    )
    world.artisans.append(aid)
    return aid


async def _media(world: SimpleNamespace, key: str, media_type: str, **fields: Any) -> str:
    mid = f"m{key}{world.tag}"
    await _x(
        'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
        '"uploadedById", "questionnaireInterviewId", "productId", "toolId", "craftId", "artisanId", "linkedRecordType", '
        '"linkedRecordId", "transcriptText", "caption", "extraMetadata", "contentSha256", "shaVerifiedAt", "createdAt", '
        '"recordedAt", "updatedAt") VALUES ($1, $2, $3::"MediaType", $4, 1000, \'ci\', $5, $6, $7, $8, $9, $10, $11, $12, '
        "$13, $14, $15, $16::jsonb, $17, $18::timestamp, $19::timestamp, $20::timestamp, now())",
        mid, f"Dhamadka_{key}.bin", media_type, "image/jpeg" if media_type == "IMAGE" else "audio/mp4",
        f"media/{world.uploader}/{key}{world.tag}/Dhamadka_{key}.bin", world.uploader, fields.get("session"),
        fields.get("product"), fields.get("tool"), fields.get("craft"), fields.get("artisan"), fields.get("linkedType"),
        fields.get("linkedId"), fields.get("text"), fields.get("caption"),
        json.dumps(fields["meta"]) if fields.get("meta") is not None else None, fields.get("sha"),
        "2026-01-01T00:00:00" if fields.get("sha") else None, fields.get("created", "2026-03-14T04:52:00"),
        fields.get("recorded", "2026-03-14T04:52:00"),
    )
    world.media.append(mid)
    if media_type == "IMAGE":
        world.store.sources[f"media/{world.uploader}/{key}{world.tag}/Dhamadka_{key}.bin"] = fields.get(
            "bytes", _jpeg((200, 30, 30))
        )
    return mid


async def _take(world: SimpleNamespace, key: str, *, subjects: list[str], section: str | None = None,
                question: str | None = None, replaces: str | None = None) -> tuple[str, str]:
    mid = await _media(world, key, "AUDIO", session=world.session)
    rid = f"r{key}{world.tag}"
    await _x(
        'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", '
        '"durationMs", "rosterCount", "recordedById", "provenance", "replacesRecordingId", "updatedAt") VALUES ($1, $2, $3, '
        "'2026-03-14T04:52:00'::timestamp, 'DEVICE'::\"CapturedAtSource\", 40000, $4, $5, 'NATIVE'::\"RecordingProvenance\", $6, now())",
        rid, mid, f"k{key}{world.tag}", len(subjects), world.uploader, replaces,
    )
    if section or question:
        await _x(
            'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "askedQuestionId") '
            "VALUES ($1, $2, $3, $4)",
            rid, f"T:{question or section}", section, question,
        )
    for artisan in subjects:
        await _x(
            'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named") '
            "VALUES ($1, $2, 'ANSWERED'::\"SubjectRole\", false)",
            rid, artisan,
        )
    await _turns(world, mid, artisan=subjects[0] if subjects else None)
    world.recordings.append(rid)
    return mid, rid


async def _turns(world: SimpleNamespace, mid: str, *, artisan: str | None) -> None:
    vid = f"v{mid}"
    await _x(
        'INSERT INTO "TranscriptVersion" ("id", "mediaFileId", "version", "languages", "detectedLanguage", "durationMs", '
        "\"updatedAt\") VALUES ($1, $2, 2, ARRAY['gu', 'hin']::text[], 'guj', 40000, now())",
        vid, mid,
    )
    rows = (
        (0, "speaker_1", 0, 6100, "hi", "How do you prepare the mordant?", None, "ALREADY_ENGLISH"),
        (1, "speaker_0", 6400, 33000, "gu", "We grind alum. Call me on 9876501234 near Dhamadka.",
         "We grind alum. Call me on 9876501234 near Dhamadka.", "TRANSLATED"),
    )
    for position, speaker, start, end, language, text, english, status in rows:
        await _x(
            'INSERT INTO "TranscriptTurn" ("id", "versionId", "mediaFileId", "position", "speakerKey", "startMs", "endMs", '
            '"language", "machineText", "machineEnglish", "translationStatus", "updatedAt") '
            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, now())",
            f"t{position}{mid}", vid, mid, position, speaker, start, end, language, text, english, status,
        )
    await _x(
        'INSERT INTO "TranscriptSpeaker" ("mediaFileId", "speakerKey", "name", "role", "personId", "updatedAt") '
        "VALUES ($1, 'speaker_1', 'Nandini Rao', 'RESEARCHER', $2, now())",
        mid, world.researcher,
    )
    if artisan:
        await _x(
            'INSERT INTO "TranscriptSpeaker" ("mediaFileId", "speakerKey", "name", "role", "personId", "updatedAt") '
            "VALUES ($1, 'speaker_0', 'Ismail', 'ARTISAN', $2, now())",
            mid, artisan,
        )


async def _seed(store: Store) -> SimpleNamespace:
    world = SimpleNamespace(tag=uuid4().hex[:10], store=store, users=[], locations=[], artisans=[], media=[],
                            recordings=[], crafts=[], extra=[])
    world.uploader = await _user(world, "up", "Uploader Person")
    world.researcher = await _user(world, "rs", "Nandini Rao")
    world.craft = f"c{world.tag}"
    world.old_craft = f"co{world.tag}"
    await _x(
        'INSERT INTO "Craft" ("id", "name", "slug", "status", "updatedAt") VALUES ($1, $2, $3, \'ACTIVE\'::"CraftStatus", now())',
        world.craft, f"Ajrakh Print of Kutch {world.tag}", f"Kutch-Ajrakh-{world.tag}",
    )
    await _x(
        'INSERT INTO "Craft" ("id", "name", "slug", "status", "mergedIntoId", "updatedAt") '
        "VALUES ($1, $2, $3, 'MERGED'::\"CraftStatus\", $4, now())",
        world.old_craft, f"Ajrak {world.tag}", f"Ajrak-{world.tag}", world.craft,
    )
    world.crafts += [world.old_craft, world.craft]
    home = await _location(world, "h", state="Gujarat", district="Kachchh", village="Dhamadka",
                           placeName="Dhamadka, Kutch", address="House 12, Lane 3", pincode="370001")
    world.artisan = await _artisan(world, "is", f"Ismail Khatri {world.tag}", location=home)
    world.minor = await _artisan(world, "mi", f"Young Artisan {world.tag}", born="2012-05-01")
    world.refuser = await _artisan(world, "rf", f"Refusing Artisan {world.tag}")
    world.workshop = f"w{world.tag}"
    await _x(
        'INSERT INTO "Workshop" ("id", "title", "date", "place", "createdById", "locationId", "updatedAt") '
        "VALUES ($1, 'Ajrakh documentation visit', '2026-03-12'::timestamp, 'Dhamadka', $2, $3, now())",
        world.workshop, world.uploader, home,
    )
    await _x('INSERT INTO "WorkshopCraft" ("workshopId", "craftId") VALUES ($1, $2)', world.workshop, world.craft)
    world.form = f"q{world.tag}"
    await _x(
        'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "sortOrder", "updatedAt") '
        "VALUES ($1, 'Craft documentation questionnaire', $2, 1, now())",
        world.form, world.uploader,
    )
    world.process_section, world.identity_section = f"sp{world.tag}", f"si{world.tag}"
    for sid, code, title, order in ((world.process_section, "C", "Process", 1), (world.identity_section, "A", "Identity", 2)):
        await _x(
            'INSERT INTO "QuestionnaireSection" ("id", "questionnaireId", "code", "title", "sortOrder", "updatedAt") '
            "VALUES ($1, $2, $3, $4, $5, now())",
            sid, world.form, code, title, order,
        )
    world.question, world.phone_question = f"qp{world.tag}", f"qi{world.tag}"
    for qid, sid, code, title, prompt, field, order in (
        (world.question, world.process_section, "C", "Process", "How do you prepare the mordant paste?", None, 1),
        (world.phone_question, world.identity_section, "A", "Identity", "Your phone number", "phone", 2),
    ):
        await _x(
            'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", '
            '"sortOrder", "sectionId", "artisanField", "displayCode", "updatedAt") VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, now())',
            qid, world.form, code, title, prompt, order, sid, field, f"{code}-Q0{order}",
        )
    world.session = f"i{world.tag}"
    await _x(
        'INSERT INTO "QuestionnaireInterview" ("id", "title", "createdById", "questionnaireId", "workshopId", "locationId", '
        "\"sessionDate\", \"updatedAt\") VALUES ($1, 'Interview_2026-03-14_Dhamadka-Kachchh_Ajrakh', $2, $3, $4, $5, "
        "'2026-03-14'::date, now())",
        world.session, world.uploader, world.form, world.workshop, home,
    )
    world.product = f"p{world.tag}"
    await _x(
        'INSERT INTO "ProductDocumentation" ("id", "craftName", "place", "artisanName", "productName", "createdById", '
        "\"craftId\", \"artisanId\", \"updatedAt\") VALUES ($1, 'Ajrakh', 'Dhamadka', 'Ismail', 'Ajrakh stole', $2, $3, $4, now())",
        world.product, world.uploader, world.old_craft, world.artisan,
    )
    world.tool = f"tl{world.tag}"
    await _x(
        'INSERT INTO "ToolDocumentation" ("id", "craftName", "place", "artisanName", "toolkitName", "createdById", '
        "\"craftId\", \"artisanId\", \"updatedAt\") VALUES ($1, 'Ajrakh', 'Dhamadka', 'Ismail', 'Printing block', $2, $3, $4, now())",
        world.tool, world.uploader, world.craft, world.artisan,
    )
    world.take, world.take_recording = await _take(world, "ok", subjects=[world.artisan], section=world.process_section,
                                                   question=world.question)
    world.identity_take, _ = await _take(world, "id", subjects=[world.artisan], section=world.identity_section)
    world.minor_take, _ = await _take(world, "mn", subjects=[world.minor], section=world.process_section,
                                      question=world.question)
    world.refused_take, _ = await _take(world, "rf", subjects=[world.refuser], section=world.process_section,
                                        question=world.question)
    world.replaced_take, world.replaced_recording = await _take(world, "rp", subjects=[world.artisan],
                                                                section=world.process_section, question=world.question)
    world.deleted_take, _ = await _take(world, "dl", subjects=[world.artisan], section=world.process_section,
                                        question=world.question)
    world.text_audio = await _media(world, "tx", "AUDIO", product=world.product,
                                    text="**Interviewer:** How long?\n\n**Ismail:** Thirty years, mail ismail.private@example.net")
    world.dictation = await _media(world, "dc", "AUDIO", craft=world.craft, linkedType="dictation", linkedId="x",
                                   text="**A:** dictated words")
    world.evidence = await _media(world, "ev", "AUDIO", craft=world.craft, text="**A:** I agree")
    world.craftless = await _media(world, "nc", "AUDIO", text="**A:** no craft here")
    world.product_photo = await _media(world, "pp", "IMAGE", product=world.product, artisan=world.artisan,
                                       caption="Ajrakh stole front", sha="d" * 64, created="2026-03-14T04:00:00")
    world.duplicate_photo = await _media(world, "dp", "IMAGE", tool=world.tool, sha="d" * 64,
                                         created="2026-03-15T04:00:00")
    world.portrait = await _media(world, "pt", "IMAGE", artisan=world.artisan, linkedType="artisan",
                                  linkedId=world.artisan, craft=world.craft)
    world.identity_photo = await _media(world, "ip", "IMAGE", product=world.product,
                                        meta={"identityPhotograph": {"decision": "STORE"}})
    world.card_photo = await _media(world, "cp", "IMAGE", product=world.product, caption="Aadhaar card")
    world.craft_photo = await _media(world, "cr", "IMAGE", craft=world.craft, bytes=_jpeg((10, 120, 40)))
    world.broken_photo = await _media(world, "bk", "IMAGE", tool=world.tool, bytes=b"not an image")
    from app.transcription import ledger as consent

    await consent.append(
        _db(), subject_type=consent.RECORDING, subject_id=world.dictation, state=consent.GRANTED,
        basis=consent.VERBAL_RECORDED, actor_id=world.uploader, evidence_media_id=world.evidence,
    )
    return world


def _db() -> Any:
    from app.core.db import db

    return db


async def _cleanup(world: SimpleNamespace) -> None:
    ids = list(world.media)
    subjects = ids + world.artisans + [world.workshop] + world.users + world.extra
    await _x('DELETE FROM "Consent" WHERE "subjectId" = ANY($1::text[]) OR "actorId" = ANY($2::text[])', subjects, world.users)
    await _x('DELETE FROM "FieldVoiceItem" WHERE "itemId" = ANY($1::text[]) OR "stream" LIKE $2', ids, f"%{world.tag}")
    await _x('DELETE FROM "FieldVoiceCopyRelease" WHERE "copyKey" = ANY($1::text[])', sorted(world.store.stored) + world.store.deleted)
    await _x('DELETE FROM "CrossPortalAccessLog" WHERE "remoteUserId" = ANY($1::text[])', [REQUESTED_BY, f"sweep-{world.tag}"])
    await _x('DELETE FROM "TranscriptTurn" WHERE "mediaFileId" = ANY($1::text[])', ids)
    await _x('DELETE FROM "TranscriptVersion" WHERE "mediaFileId" = ANY($1::text[])', ids)
    await _x('DELETE FROM "TranscriptSpeaker" WHERE "mediaFileId" = ANY($1::text[])', ids)
    await _x('DELETE FROM "QuestionnaireRecordingTarget" WHERE "recordingId" = ANY($1::text[])', world.recordings)
    await _x('DELETE FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = ANY($1::text[])', world.recordings)
    await _x('DELETE FROM "QuestionnaireRecording" WHERE "id" = ANY($1::text[])', world.recordings)
    for statement in getattr(world, "designer_cleanup", []):
        await _x(statement, world.tag)
    await _x('DELETE FROM "MediaFile" WHERE "id" = ANY($1::text[])', ids)
    for statement in getattr(world, "late_cleanup", []):
        await _x(statement, world.tag)
    await _x('DELETE FROM "QuestionnaireInterview" WHERE "id" = $1', world.session)
    await _x('DELETE FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1', world.form)
    await _x('DELETE FROM "QuestionnaireSection" WHERE "questionnaireId" = $1', world.form)
    await _x('DELETE FROM "Questionnaire" WHERE "id" = $1', world.form)
    await _x('DELETE FROM "ProductDocumentation" WHERE "id" = $1', world.product)
    await _x('DELETE FROM "ToolDocumentation" WHERE "id" = $1', world.tool)
    await _x('DELETE FROM "WorkshopCraft" WHERE "workshopId" = $1', world.workshop)
    await _x('DELETE FROM "Workshop" WHERE "id" = $1', world.workshop)
    await _x('DELETE FROM "Artisan" WHERE "id" = ANY($1::text[])', world.artisans)
    await _x('DELETE FROM "Location" WHERE "id" = ANY($1::text[])', world.locations)
    await _x('DELETE FROM "Craft" WHERE "id" = ANY($1::text[])', world.crafts)
    await _x('DELETE FROM "User" WHERE "id" = ANY($1::text[])', world.users)


async def _refresh(store: Store, settings: Any) -> dict[str, Any]:
    from app.services import field_voices_portal
    from app.services.field_voices.refresh import Refresher

    return await Refresher(
        _db(), field_voices_portal, settings, fetch=store.fetch, store=store.store, delete=store.delete
    ).run()


async def _get(monkeypatch: pytest.MonkeyPatch, path: str, *, settings: Any = None, token: str | None = TOKEN,
               nonce: str | None = NONCE, params: dict[str, str] | None = None, requested_by: str = REQUESTED_BY) -> Any:
    import httpx

    from app.main import app as application
    from app.services import cross_portal, cross_portal_peer

    chosen = settings or _settings()
    monkeypatch.setattr(cross_portal_peer, "get_settings", lambda: chosen)
    headers = {cross_portal.REQUESTED_BY_HEADER: requested_by}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if nonce is not None:
        headers[cross_portal.NONCE_HEADER] = nonce
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport) as client:
        return await client.get(f"{API}{path}", headers=headers, params=params)


def _expected_signature(path: str, body: bytes, nonce: str = NONCE) -> str:
    head = "\n".join(("GET", path, nonce, "")).encode("utf-8")
    return hmac.new(TOKEN.encode("utf-8"), head + body, hashlib.sha256).hexdigest()


async def _read_all(monkeypatch: pytest.MonkeyPatch, stream: str, view: str = "all") -> tuple[list[dict[str, Any]], list[Any]]:
    changes: list[dict[str, Any]] = []
    answers: list[Any] = []
    cursor: str | None = None
    while True:
        params = {"view": view} | ({"cursor": cursor} if cursor else {})
        response = await _get(monkeypatch, f"{PEER}/{stream}", params=params)
        assert response.status_code == 200, response.text
        answers.append(response)
        page = json.loads(response.content)
        assert (page["stream"], page["view"], page["cursor"]) == (stream, view, cursor)
        changes += page["changes"]
        if not page["hasMore"]:
            return changes, answers
        cursor = page["nextCursor"]


def _mine(changes: list[dict[str, Any]], world: SimpleNamespace) -> dict[str, dict[str, Any]]:
    return {c["id"]: c for c in changes if c["id"] in set(world.media)}


def _scenario(monkeypatch: pytest.MonkeyPatch, body: Callable[[SimpleNamespace], Awaitable[None]]) -> None:
    store = Store()

    async def run() -> None:
        world = await _seed(store)
        try:
            await _refresh(store, _settings())
            await body(world)
        finally:
            await _cleanup(world)

    _run(run)


def test_off_is_404_and_a_wrong_bearer_or_nonce_is_401(monkeypatch: pytest.MonkeyPatch) -> None:
    async def body() -> None:
        for path in (PEER, f"{PEER}/transcripts", f"{PEER}/images"):
            off = await _get(monkeypatch, path, settings=_settings(cross_portal_field_voices_enabled=False))
            assert (off.status_code, off.json()) == (404, {"detail": "Not Found"})
            short = await _get(monkeypatch, path, settings=_settings(cross_portal_token="t" * 31), token="t" * 31)
            assert short.status_code == 404
            for token, nonce in ((None, NONCE), ("x" * 40, NONCE), (TOKEN, None), (TOKEN, "short")):
                refused = await _get(monkeypatch, path, token=token, nonce=nonce)
                assert (refused.status_code, refused.json()) == (401, {"detail": "Not authorised."})
        unheld = await _get(monkeypatch, f"{PEER}/images", settings=_settings(field_voices_rights_holder=None))
        assert unheld.status_code == 404
        state = json.loads((await _get(monkeypatch, PEER, settings=_settings(field_voices_rights_holder=None))).content)
        assert state["streams"]["images"]["enabled"] is False

    _run(body)


def test_every_recording_with_a_transcript_and_a_craft_is_listed_with_only_the_legal_floor_scrubbed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.services import field_voices_portal
    from app.services.field_voices import contract, ledger

    async def body(world: SimpleNamespace) -> None:
        changes, answers = await _read_all(monkeypatch, "transcripts")
        mine = _mine(changes, world)
        listed = {i for i, c in mine.items() if c["state"] == "LIVE"}
        assert listed == {
            world.take, world.refused_take, world.replaced_take, world.deleted_take, world.text_audio,
            world.identity_take, world.minor_take, world.dictation, world.evidence,
        }
        assert world.craftless not in mine
        for answer in answers:
            assert answer.headers["Cache-Control"] == "no-store"
            assert answer.headers["X-Cross-Portal-Signature"] == _expected_signature(f"{PEER}/transcripts", answer.content)
            for needle in NEVER_IN_ANY_ANSWER:
                assert needle not in answer.content.decode("utf-8")
            assert contract.forbidden_keys(json.loads(answer.content)) == []
        item = mine[world.take]
        assert ledger.revision(item) == item["revision"]
        assert item["recordedAt"] == "2026-03-14T10:22:00+05:30"
        assert item["crafts"] == [{"id": world.craft, "slug": f"Kutch-Ajrakh-{world.tag}", "code": None, "name": f"Ajrakh Print of Kutch {world.tag}"}]
        assert item["workshop"]["kind"] == "WORKSHOP" and item["workshop"]["title"] == "Ajrakh documentation visit"
        assert item["workshop"]["startsOn"] == "2026-03-12"
        assert item["session"]["title"] == "Interview_2026-03-14_Dhamadka-Kachchh_Ajrakh"
        assert item["session"]["date"] == "2026-03-14"
        assert item["session"]["questionnaire"] == {"id": world.form, "title": "Craft documentation questionnaire"}
        assert item["topics"] == [{"sectionId": world.process_section, "sectionCode": "C", "sectionTitle": "Process",
                                   "questionId": world.question, "questionCode": "C-Q01",
                                   "question": "How do you prepare the mordant paste?"}]
        assert item["area"]["state"] == "Gujarat" and set(item["area"]) == {"state", "district", "stateLgd", "districtLgd"}
        assert [(p["id"], p["kind"]) for p in item["people"]] == [(world.artisan, "ARTISAN"), (world.researcher, "RESEARCHER")]
        assert item["people"][0]["name"] == f"Ismail Khatri {world.tag}" and item["people"][0]["yearsInCraft"] == 31
        assert item["people"][0]["craft"] == item["crafts"][0] and item["people"][1]["craft"] is None
        assert item["speakers"] == [
            {"key": "speaker_1", "label": "Nandini Rao", "role": "RESEARCHER", "personId": world.researcher},
            {"key": "speaker_0", "label": "Ismail", "role": "ARTISAN", "personId": world.artisan},
        ]
        transcript = item["transcript"]
        assert (transcript["source"], transcript["version"], transcript["languages"], transcript["detectedLanguage"]) == (
            "TURNS", 2, ["gu", "hi"], "gu")
        second = transcript["segments"][1]
        assert second["text"] == "We grind alum. Call me on [number] near Dhamadka."
        assert second["english"] == second["text"] and second["englishSource"] == "MACHINE"
        assert second["scrubbed"] == ["NUMBER"] and (second["startMs"], second["endMs"]) == (6400, 33000)
        assert transcript["segments"][0]["englishSource"] == "ORIGINAL"
        assert item["scrubbing"] == {
            "version": 2, "replaced": [{"kind": "EMAIL", "count": 0}, {"kind": "NUMBER", "count": 2}], "nulled": [],
        }
        identity = mine[world.identity_take]
        assert identity["topics"][0]["sectionTitle"] == "Identity"
        assert "9876501234" not in json.dumps(identity)
        assert mine[world.minor_take]["people"][0]["id"] == world.minor
        text = mine[world.text_audio]
        assert text["transcript"]["source"] == "TEXT" and text["record"]["kind"] == "PRODUCT"
        assert text["crafts"][0]["id"] == world.craft
        assert [s["key"] for s in text["speakers"]] == ["L1", "L2"]
        assert "[email]" in text["transcript"]["segments"][1]["text"]
        assert text["people"] == []
        assert field_voices_portal.PORTAL in contract.PORTALS

    _scenario(monkeypatch, body)


def test_tombstones_land_at_the_next_state_read(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import field_voices_portal
    from app.services.field_voices import withdrawals

    async def body(world: SimpleNamespace) -> None:
        await withdrawals.record(_db(), field_voices_portal, "ARTISAN", world.refuser, shown=False,
                                 basis="VERBAL_RECORDED", note="asked to withdraw", actor_id=world.uploader)
        status = await withdrawals.status(_db(), field_voices_portal, "ARTISAN", world.refuser)
        assert (status["shown"], status["state"], status["basis"]) == (False, "REFUSED", "VERBAL_RECORDED")
        successor = f"mrn{world.tag}"
        world.media.append(successor)
        await _x(
            'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
            "\"uploadedById\", \"updatedAt\") VALUES ($1, 'n.m4a', 'AUDIO'::\"MediaType\", 'audio/mp4', 1, 'ci', $2, $3, now())",
            successor, f"media/{world.uploader}/rn{world.tag}/n.m4a", world.uploader,
        )
        world.recordings.append(f"rrn{world.tag}")
        await _x(
            'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", '
            '"rosterCount", "recordedById", "provenance", "replacesRecordingId", "updatedAt") VALUES ($1, $2, $3, now(), '
            "'DEVICE'::\"CapturedAtSource\", 1, $4, 'NATIVE'::\"RecordingProvenance\", $5, now())",
            f"rrn{world.tag}", successor, f"krn{world.tag}", world.uploader, world.replaced_recording,
        )
        await _x('UPDATE "QuestionnaireRecording" SET "removedAt" = now() WHERE "id" = $1', world.replaced_recording)
        await _x('UPDATE "MediaFile" SET "removedAt" = now() WHERE "id" = $1', world.replaced_take)
        await _x('DELETE FROM "TranscriptTurn" WHERE "mediaFileId" = $1', world.deleted_take)
        await _x('DELETE FROM "TranscriptVersion" WHERE "mediaFileId" = $1', world.deleted_take)
        await _x('DELETE FROM "TranscriptSpeaker" WHERE "mediaFileId" = $1', world.deleted_take)
        await _x('DELETE FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1', f"rdl{world.tag}")
        await _x('DELETE FROM "QuestionnaireRecordingTarget" WHERE "recordingId" = $1', f"rdl{world.tag}")
        await _x('DELETE FROM "QuestionnaireRecording" WHERE "id" = $1', f"rdl{world.tag}")
        await _x('DELETE FROM "MediaFile" WHERE "id" = $1', world.deleted_take)
        state = await _get(monkeypatch, PEER)
        assert state.status_code == 200, state.text
        assert state.headers["X-Cross-Portal-Signature"] == _expected_signature(PEER, state.content)
        gone, _answers = await _read_all(monkeypatch, "transcripts", view="gone")
        mine = _mine(gone, world)
        assert all(c["state"] == "GONE" for c in gone)
        assert mine[world.refused_take]["reason"] == "WITHDRAWN"
        assert (mine[world.replaced_take]["reason"], mine[world.replaced_take]["successorId"]) == ("REPLACED", successor)
        assert mine[world.deleted_take]["reason"] == "DELETED"
        assert set(mine[world.deleted_take]) == {"state", "id", "updatedAt", "reason", "successorId"}
        assert world.take not in mine
        rows = await _db().query_raw(
            'SELECT "payload", "revision", "copyKey" FROM "FieldVoiceItem" WHERE "itemId" = $1', world.deleted_take
        )
        assert rows == [{"payload": None, "revision": None, "copyKey": None}]
        await withdrawals.record(_db(), field_voices_portal, "ARTISAN", world.refuser, shown=True,
                                 basis="SIGNED_FORM", note=None, actor_id=world.uploader)
        await _refresh(world.store, _settings())
        again = _mine((await _read_all(monkeypatch, "transcripts"))[0], world)
        assert again[world.refused_take]["state"] == "LIVE"

    _scenario(monkeypatch, body)


def test_paging_is_stable_in_both_views_and_the_digest_matches(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.api.routes import cross_portal_field_voices as routes
    from app.services.field_voices import ledger

    async def body(world: SimpleNamespace) -> None:
        assert (await _get(monkeypatch, PEER)).status_code == 200
        changes, answers = await _read_all(monkeypatch, "transcripts")
        keys = [(c["updatedAt"], c["id"]) for c in changes]
        assert keys == sorted(keys, key=lambda k: (k[0], k[1].encode("utf-8"))) and len(set(keys)) == len(keys)
        last = json.loads(answers[-1].content)
        state = json.loads((await _get(monkeypatch, PEER)).content)
        stream = state["streams"]["transcripts"]
        assert (state["schemaVersion"], state["refreshedAt"] is not None) == (2, True)
        assert stream["latestCursor"] == last["nextCursor"]
        live = [(c["id"], c["revision"]) for c in changes if c["state"] == "LIVE"]
        assert stream["digest"] == ledger.digest(live) and stream["live"] == len(live)
        assert stream["gone"] == sum(1 for c in changes if c["state"] == "GONE")
        empty = json.loads((await _get(monkeypatch, f"{PEER}/transcripts", params={"cursor": last["nextCursor"]})).content)
        assert (empty["changes"], empty["nextCursor"], empty["hasMore"]) == ([], last["nextCursor"], False)
        for params, detail in (
            ({"cursor": "nope"}, "Unknown cursor."),
            ({"cursor": last["nextCursor"], "view": "gone"}, "Unknown cursor."),
            ({"view": "live"}, "Unknown view."),
        ):
            refused = await _get(monkeypatch, f"{PEER}/transcripts", params=params)
            assert (refused.status_code, refused.json()) == (400, {"detail": detail})
            assert refused.headers["X-Cross-Portal-Signature"] == _expected_signature(f"{PEER}/transcripts", refused.content)
        other = await _get(monkeypatch, f"{PEER}/images", params={"cursor": last["nextCursor"]})
        assert other.status_code == 400
        rows = await _db().query_raw(
            'SELECT "action", "outcome", "entityType", "entityId", "resultCount" FROM "CrossPortalAccessLog" '
            'WHERE "remoteUserId" = $1 ORDER BY "createdAt"', REQUESTED_BY,
        )
        outcomes = {(r["action"], r["outcome"]) for r in rows}
        assert {("FIELD_VOICES_TRANSCRIPTS", "OK"), ("FIELD_VOICES_STATE", "OK"), ("FIELD_VOICES_TRANSCRIPTS", "BAD_CURSOR"),
                ("FIELD_VOICES_TRANSCRIPTS", "BAD_VIEW"), ("FIELD_VOICES_IMAGES", "BAD_CURSOR")} <= outcomes
        assert all(r["entityType"] == "STREAM" for r in rows if r["action"] != "FIELD_VOICES_STATE")
        monkeypatch.setattr(routes, "WINDOW", routes.pages.RateWindow(calls=1))
        assert (await _get(monkeypatch, PEER, requested_by=f"sweep-{world.tag}")).status_code == 200
        limited = await _get(monkeypatch, PEER, requested_by=f"sweep-{world.tag}")
        assert limited.status_code == 429 and int(limited.headers["Retry-After"]) >= 1
        assert limited.json() == {"detail": "Too many requests."}

    _scenario(monkeypatch, body)


def test_images_are_neutral_copies_with_the_contracts_placements(monkeypatch: pytest.MonkeyPatch) -> None:
    from PIL import Image

    from app.services import naming
    from app.services.field_voices import contract, ledger

    async def body(world: SimpleNamespace) -> None:
        changes, answers = await _read_all(monkeypatch, "images")
        mine = _mine(changes, world)
        live = {i for i, c in mine.items() if c["state"] == "LIVE"}
        assert live == {world.product_photo, world.craft_photo, world.portrait}
        for answer in answers:
            for needle in (*NEVER_IN_ANY_ANSWER, "media/"):
                assert needle not in answer.content.decode("utf-8")
            assert contract.forbidden_keys(json.loads(answer.content)) == []
        photo = mine[world.product_photo]
        assert ledger.revision(photo) == photo["revision"]
        kinds = [b["kind"] for b in photo["belongsTo"]]
        assert kinds == ["PRODUCT", "TOOL"]
        assert photo["caption"] == "Ajrakh stole front"
        assert photo["crafts"][0]["id"] == world.craft
        assert photo["credits"] == [{"personId": world.artisan, "as": "ARTISAN"}]
        assert photo["permission"] == {"statement": contract.PERMISSION_STATEMENT, "rightsHolder": "IIT Kharagpur", "licence": None}
        image = photo["image"]
        key = naming.field_voice_copy_key(image["sha256"], "jpg")
        assert hashlib.sha256(world.store.stored[key]).hexdigest() == image["sha256"]
        with Image.open(io.BytesIO(world.store.stored[key])) as copy:
            assert not copy.getexif() and "icc_profile" not in copy.info
        parts = urlsplit(image["url"])
        assert parts.scheme == "https" and parts.path == f"/{key}"
        disposition = parse_qs(parts.query)["response-content-disposition"][0]
        assert disposition.startswith("inline") and f"{image['sha256']}.jpg" in disposition
        assert world.uploader not in image["url"] and "Dhamadka" not in image["url"]
        assert mine[world.craft_photo]["belongsTo"] == [{"kind": "CRAFT"}]
        portrait = mine[world.portrait]
        assert portrait["belongsTo"] == [
            {"kind": "ARTISAN", "id": world.artisan, "name": f"Ismail Khatri {world.tag}", "localName": None}
        ]
        assert portrait["credits"] == [{"personId": world.artisan, "as": "ARTISAN"}]
        assert (portrait["artisanLinked"], photo["artisanLinked"], mine[world.craft_photo]["artisanLinked"]) == (
            True, True, False)
        for withheld in (world.identity_photo, world.card_photo, world.broken_photo):
            assert mine.get(withheld, {}).get("state") in (None, "GONE")
        assert mine.get(world.duplicate_photo, {}).get("state") in (None, "GONE")
        rows = await _db().query_raw(
            'SELECT "copyOf" FROM "FieldVoiceItem" WHERE "stream" = \'images\' AND "itemId" = $1', world.product_photo
        )
        assert rows[0]["copyOf"] == f"media/{world.uploader}/pp{world.tag}/Dhamadka_pp.bin {'d' * 64}"
        before = len(world.store.stored)
        await _refresh(world.store, _settings())
        assert len(world.store.stored) == before

    _scenario(monkeypatch, body)


def test_concurrent_writers_stamp_strictly_increasing_times() -> None:
    from app.services.field_voices import items, ledger

    async def body() -> None:
        tag = uuid4().hex[:10]
        stream = f"test-{tag}"
        try:
            batches = [
                [items.Outcome(item_id=f"{w}-{n}-{tag}", payload={"id": f"{w}-{n}-{tag}", "n": n}) for n in range(30)]
                for w in range(4)
            ]
            written = await asyncio.gather(*(ledger.write_outcomes(_db(), stream, b) for b in batches))
            assert written == [30, 30, 30, 30]
            rows = await _db().query_raw(
                f'SELECT "itemId", to_char("updatedAt", \'{ledger.STAMP_FORMAT}\') AS "stamp" FROM "FieldVoiceItem" '
                'WHERE "stream" = $1 ORDER BY "updatedAt", "itemId" COLLATE "C"', stream,
            )
            stamps = [r["stamp"] for r in rows]
            assert len(stamps) == 120 and len(set(stamps)) == 120
            assert stamps == sorted(stamps)
            assert await ledger.write_outcomes(_db(), stream, batches[0]) == 0
        finally:
            await _x('DELETE FROM "FieldVoiceItem" WHERE "stream" = $1', stream)

    _run(body)


def test_a_held_ledger_lock_answers_busy(monkeypatch: pytest.MonkeyPatch) -> None:
    from datetime import timedelta

    from app.services.field_voices import contract

    monkeypatch.setattr(contract, "LOCK_TIMEOUT_SECONDS", 1)

    async def body() -> None:
        from prisma import Prisma

        holder = Prisma(datasource={"url": _DSN})
        await holder.connect()
        release = asyncio.Event()
        taken = asyncio.Event()

        async def hold() -> None:
            async with holder.tx(max_wait=timedelta(seconds=5), timeout=timedelta(seconds=60)) as tx:
                await tx.query_raw("SELECT count(*) AS n FROM (SELECT pg_advisory_xact_lock(hashtext('field-voices'))) AS l")
                taken.set()
                await release.wait()

        task = asyncio.create_task(hold())
        try:
            await asyncio.wait_for(taken.wait(), timeout=10)
            busy = await _get(monkeypatch, PEER)
            assert (busy.status_code, busy.json()) == (503, {"detail": "Busy, try again."})
            assert busy.headers["Retry-After"] == "30"
        finally:
            release.set()
            await task
            await holder.disconnect()

    _run(body)


async def _row(stream: str, item_id: str) -> dict[str, Any]:
    from app.services.field_voices.sources import plain_json

    rows = await _db().query_raw(
        'SELECT "state", "reason", "copyKey", "subjects" FROM "FieldVoiceItem" WHERE "stream" = $1 AND "itemId" = $2',
        stream, item_id,
    )
    assert len(rows) == 1, f"{item_id} has no {stream} ledger row"
    return {**rows[0], "subjects": plain_json(rows[0]["subjects"])}


async def _product(world: SimpleNamespace, key: str, name: str) -> str:
    pid = f"p{key}{world.tag}"
    await _x(
        'INSERT INTO "ProductDocumentation" ("id", "craftName", "place", "artisanName", "productName", "createdById", '
        "\"craftId\", \"artisanId\", \"updatedAt\") VALUES ($1, 'Ajrakh', 'Dhamadka', 'Ismail', $2, $3, $4, $5, now())",
        pid, name, world.uploader, world.craft, world.artisan,
    )
    world.late_cleanup = [*getattr(world, "late_cleanup", []), f"DELETE FROM \"ProductDocumentation\" WHERE \"id\" = 'p{key}' || $1"]
    return pid


def test_the_respondent_sections_questions_are_listed_with_their_numbers_scrubbed(monkeypatch: pytest.MonkeyPatch) -> None:
    async def body(world: SimpleNamespace) -> None:
        section = f"sr{world.tag}"
        await _x(
            'INSERT INTO "QuestionnaireSection" ("id", "questionnaireId", "code", "title", "sortOrder", "updatedAt") '
            "VALUES ($1, $2, 'RESP', 'Respondent Information', 3, now())",
            section, world.form,
        )
        asked: dict[str, str] = {}
        for key, prompt, order in (("ag", "Age", 6), ("cn", "Contact Number", 8), ("dv", "District/Village", 4)):
            asked[key] = f"q{key}{world.tag}"
            await _x(
                'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", '
                '"sortOrder", "sectionId", "questionCode", "displayCode", "updatedAt") '
                "VALUES ($1, $2, 'RESP', 'Respondent Information', $3, $4, $5, $6, $7, now())",
                asked[key], world.form, prompt, order, section, f"CTW2.RESP.0{order}", f"RESP-Q0{order}",
            )
        age, _ = await _take(world, "ag", subjects=[world.artisan], question=asked["ag"])
        contact, _ = await _take(world, "cn", subjects=[world.artisan], question=asked["cn"])
        whole, _ = await _take(world, "rs", subjects=[world.artisan], section=section)
        await _refresh(world.store, _settings())
        changes, answers = await _read_all(monkeypatch, "transcripts")
        mine = _mine(changes, world)
        assert all(mine[i]["state"] == "LIVE" for i in (age, contact, whole, world.take))
        assert mine[contact]["topics"][0]["question"] == "Contact Number"
        assert mine[contact]["transcript"]["segments"][1]["text"] == "We grind alum. Call me on [number] near Dhamadka."
        for answer in answers:
            assert all(needle not in answer.content.decode("utf-8") for needle in NEVER_IN_ANY_ANSWER)

    _scenario(monkeypatch, body)


def test_a_take_scrubs_its_session_participants_numbers_and_keeps_every_place_name(monkeypatch: pytest.MonkeyPatch) -> None:
    async def body(world: SimpleNamespace) -> None:
        far = await _location(world, "pf", state="Gujarat", district="Kachchh", village="Khavda")
        participant = await _artisan(world, "pc", f"Participant {world.tag}", location=far)
        card = "PEH-pc-" + "".join(chr(ord("a") + int(ch, 16)) for ch in world.tag)
        await _x('UPDATE "Artisan" SET "pehchanCardNumber" = $1 WHERE "id" = $2', card, participant)
        await _x('INSERT INTO "QuestionnaireInterviewArtisan" ("interviewId", "artisanId") VALUES ($1, $2)',
                 world.session, participant)
        shoot = await _location(world, "sh", state="Gujarat", district="Kachchh", village="Ludiya", pincode="370510")
        await _x('UPDATE "MediaFile" SET "locationId" = $1 WHERE "id" = $2', shoot, world.take)
        spoken = f"My cousin from Khavda, card {card}. We dye at Ludiya, 370510."
        await _x('UPDATE "TranscriptTurn" SET "machineText" = $1, "machineEnglish" = $1 WHERE "id" = $2',
                 spoken, f"t1{world.take}")
        await _refresh(world.store, _settings())
        changes, answers = await _read_all(monkeypatch, "transcripts")
        item = _mine(changes, world)[world.take]
        expected = "My cousin from Khavda, card [number]. We dye at Ludiya, 370510."
        assert item["transcript"]["segments"][1]["text"] == expected
        assert item["transcript"]["segments"][1]["english"] == expected
        assert participant not in [p["id"] for p in item["people"]]
        for answer in answers:
            assert "PEH-" not in answer.content.decode("utf-8")

    _scenario(monkeypatch, body)


def test_a_withdrawn_session_participant_leaves_at_the_next_state_read(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import field_voices_portal
    from app.services.field_voices import withdrawals

    async def body(world: SimpleNamespace) -> None:
        participant = await _artisan(world, "wp", f"Withdrawing participant {world.tag}")
        await _x('INSERT INTO "QuestionnaireInterviewArtisan" ("interviewId", "artisanId") VALUES ($1, $2)',
                 world.session, participant)
        await _refresh(world.store, _settings())
        row = await _row("transcripts", world.take)
        assert row["state"] == "LIVE" and participant in row["subjects"]["artisans"]
        assert world.workshop in row["subjects"]["workshops"]
        await withdrawals.record(_db(), field_voices_portal, "ARTISAN", participant, shown=False,
                                 basis="VERBAL_RECORDED", note=None, actor_id=world.uploader)
        assert (await _get(monkeypatch, PEER)).status_code == 200
        gone = _mine((await _read_all(monkeypatch, "transcripts", view="gone"))[0], world)
        assert gone[world.take]["reason"] == "WITHDRAWN"
        assert (await _row("transcripts", world.take))["subjects"] is None

    _scenario(monkeypatch, body)


def test_a_photo_leaves_at_the_next_state_read_once_its_record_is_deleted(monkeypatch: pytest.MonkeyPatch) -> None:
    async def body(world: SimpleNamespace) -> None:
        product = await _product(world, "dl", "Ajrakh dupatta")
        photo = await _media(world, "pd", "IMAGE", product=product, bytes=_jpeg((90, 90, 200)))
        await _refresh(world.store, _settings())
        assert (await _row("images", photo))["state"] == "LIVE"
        await _x('DELETE FROM "ProductDocumentation" WHERE "id" = $1', product)
        assert (await _get(monkeypatch, PEER)).status_code == 200
        gone = _mine((await _read_all(monkeypatch, "images", view="gone"))[0], world)
        assert gone[photo]["reason"] == "DELETED"

    _scenario(monkeypatch, body)


def test_a_failed_copy_delete_is_retried_at_the_next_refresh(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import field_voices_portal
    from app.services.field_voices import withdrawals

    async def body(world: SimpleNamespace) -> None:
        product = await _product(world, "rt", "Ajrakh bedcover")
        photo = await _media(world, "pr", "IMAGE", product=product, bytes=_jpeg((12, 200, 90)))
        await _refresh(world.store, _settings())
        key = str((await _row("images", photo))["copyKey"])
        assert key in world.store.stored
        await withdrawals.record(_db(), field_voices_portal, "RECORDING", photo, shown=False,
                                 basis="SIGNED_FORM", note=None, actor_id=world.uploader)
        world.store.refuse_deletes = True
        await _refresh(world.store, _settings())
        row = await _row("images", photo)
        assert (row["state"], row["reason"], row["copyKey"]) == ("GONE", "WITHDRAWN", None)
        pending = await _db().query_raw('SELECT "attempts" FROM "FieldVoiceCopyRelease" WHERE "copyKey" = $1', key)
        assert len(pending) == 1 and pending[0]["attempts"] >= 1
        assert key in world.store.stored
        world.store.refuse_deletes = False
        await _refresh(world.store, _settings())
        assert key in world.store.deleted and key not in world.store.stored
        assert await _db().query_raw('SELECT 1 AS "n" FROM "FieldVoiceCopyRelease" WHERE "copyKey" = $1', key) == []

    _scenario(monkeypatch, body)


def test_a_tombstone_written_while_a_batch_was_evaluated_stays_gone() -> None:
    from app.services.field_voices import items, ledger

    async def body() -> None:
        tag = uuid4().hex[:10]
        stream = f"test-{tag}"
        item_id = f"race-{tag}"
        try:
            assert await ledger.write_outcomes(_db(), stream, [items.Outcome(item_id=item_id, payload={"id": item_id, "n": 1})]) == 1
            since = await ledger.clock(_db())
            assert await ledger.write_outcomes(_db(), stream, [items.Outcome(item_id=item_id, reason="WITHDRAWN")]) == 1
            stale = items.Outcome(item_id=item_id, payload={"id": item_id, "n": 2})
            assert await ledger.write_outcomes(_db(), stream, [stale], since=since) == 0
            assert (await _row(stream, item_id))["state"] == "GONE"
            assert await ledger.write_outcomes(_db(), stream, [stale], since=await ledger.clock(_db())) == 1
            assert (await _row(stream, item_id))["state"] == "LIVE"
        finally:
            await _x('DELETE FROM "FieldVoiceItem" WHERE "stream" = $1', stream)

    _run(body)


def _portal() -> str:
    from app.services import field_voices_portal

    return field_voices_portal.PORTAL


DESIGNER_ONLY = pytest.mark.skipif(_portal() != "DESIGNER", reason="design workshops exist only in the designer portal")


@DESIGNER_ONLY
def test_design_stage_media_come_across_except_the_legal_floor(monkeypatch: pytest.MonkeyPatch) -> None:
    store = Store()

    async def run() -> None:
        world = await _seed(store)
        dw = f"dw{world.tag}"
        world.extra.append(dw)
        world.designer_cleanup = [
            'DELETE FROM "DwStageEntry" WHERE "designWorkshopId" = \'dw\' || $1',
            'DELETE FROM "DesignWorkshop" WHERE "id" = \'dw\' || $1',
        ]
        try:
            designer = await _user(world, "dz", "Design Er", role="DESIGNER")
            await _x(
                'INSERT INTO "DesignWorkshop" ("id", "title", "createdById", "craftId", "state", "district", "venue", '
                "\"startDate\", \"updatedAt\") VALUES ($1, 'Hand block printing design workshop', $2, $3, 'Gujarat', 'Kachchh', "
                "'Dhamadka hall', '2026-06-18'::timestamp, now())",
                dw, designer, world.craft,
            )
            prototype = await _media(world, "pr", "IMAGE", bytes=_jpeg((1, 2, 3)))
            signature = await _media(world, "sg", "IMAGE", product=world.product, bytes=_jpeg((4, 5, 6)))
            feedback = await _media(world, "fb", "AUDIO")
            survey = await _media(world, "sv", "AUDIO", text="**Buyer:** I would pay")
            event = await _media(world, "eo", "IMAGE", bytes=_jpeg((7, 8, 9)))
            rows = (
                ("e1", "PROTOTYPE_DEVELOPMENT", "prototype", {"name": "Indigo dabu runner", "prototypePhotos": [prototype]}),
                ("e2", "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "workshopPlan",
                 {"designerSignature": signature, "eventPhotos": [event]}),
                ("e3", "SKETCH_REVIEW", "sketchReview", {"voiceFeedback": feedback, "voiceFeedbackTranscript": "Keep the border thin."}),
                ("e4", "MARKET_SURVEY_CAPTURE", "surveyResponse", {"respondentName": "Buyer", "voiceNote": survey}),
            )
            for key, stage, entity, data in rows:
                await _x(
                    'INSERT INTO "DwStageEntry" ("id", "designWorkshopId", "stageKey", "entityKey", "data", "updatedAt") '
                    "VALUES ($1, $2, $3, $4, $5::jsonb, now())",
                    f"{key}{world.tag}", dw, stage, entity, json.dumps(data),
                )
            await _refresh(store, _settings())
            images = _mine((await _read_all(monkeypatch, "images"))[0], world)
            transcripts = _mine((await _read_all(monkeypatch, "transcripts"))[0], world)
            staged = images[prototype]
            assert staged["state"] == "LIVE"
            assert staged["belongsTo"][0]["kind"] == "DESIGN_STAGE"
            assert staged["belongsTo"][0]["prototypeId"] == f"e1{world.tag}"
            assert staged["workshop"]["kind"] == "DESIGN_WORKSHOP" and staged["workshop"]["title"] == "Hand block printing design workshop"
            assert {"personId": designer, "as": "DESIGNER"} in staged["credits"]
            assert [p["kind"] for p in staged["people"] if p["id"] == designer] == ["DESIGNER"]
            assert signature not in images or images[signature]["state"] == "GONE"
            opening = images[event]
            assert opening["state"] == "LIVE"
            assert opening["belongsTo"][0]["field"] == {"key": "eventPhotos", "label": "Event photographs"}
            typed = transcripts[feedback]
            assert typed["transcript"]["source"] == "TYPED" and typed["speakers"] == []
            assert typed["stage"]["key"] == "SKETCH_REVIEW" and typed["stage"]["field"] == "voiceFeedback"
            voice = transcripts[survey]
            assert voice["state"] == "LIVE" and voice["stage"]["field"] == "voiceNote"
            assert voice["transcript"]["source"] == "TEXT"
        finally:
            await _cleanup(world)

    _run(run)
