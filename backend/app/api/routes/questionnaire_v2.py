"""Questionnaire v2 routes: the frozen contract of BUILD_PLAN section 1.

``/api/questionnaire/...`` (sessions, coverage, recordings, answers, inbox, corpus, config) and
``/api/artisans/{id}/consent|record``. The media routes (presign, multipart, uploads, complete,
download) live in ``routes/media.py`` and call ``services/questionnaire_v2/recordings.py``.

EVERY v2 ROUTE ANSWERS 404 WHILE ``QUESTIONNAIRE_V2`` IS FALSE, except ``GET /config`` (which says
so) and the consent routes (consent gates outside sends whatever screens a client draws). Bodies are
parsed here rather than by FastAPI so that every refusal has the contract's shape,
``{"detail": {"code", "message", ...}}``, and a ``title`` gets ``TITLE_IS_GENERATED``.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query, Request, status
from fastapi.responses import JSONResponse, RedirectResponse, Response
from pydantic import BaseModel, ValidationError

from app.services import transcription_enqueue as transcription
from app.core.config import get_settings
from app.core.deps import get_current_user
from app.schemas import questionnaire_v2 as S
from app.services import consent_gate
from app.services.questionnaire_v2 import answers, corpus, coverage, inbox, record, recordings, sessions
from app.services.questionnaire_v2.common import fail, iso
from app.services.questionnaire_v2.recordings import MULTIPART_THRESHOLD, PART_SIZE

CONTRACT_VERSION = 1

router = APIRouter(prefix="/questionnaire", tags=["questionnaire-v2"])
artisans_router = APIRouter(prefix="/artisans", tags=["questionnaire-v2"])


def v2_enabled() -> bool:
    return bool(get_settings().questionnaire_v2)


async def require_v2() -> None:
    if not v2_enabled():
        fail("NOT_FOUND")


async def parse(request: Request, model: type[BaseModel], *, forbid_title: bool = False) -> Any:
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 - a body that is not JSON is a validation failure
        fail("VALIDATION_FAILED", "The body is not JSON.")
    if forbid_title and isinstance(body, dict) and "title" in body:
        fail("TITLE_IS_GENERATED")
    try:
        return model.model_validate(body)
    except ValidationError as exc:
        errors = [{"loc": [str(p) for p in e.get("loc", ())], "msg": e.get("msg")} for e in exc.errors()]
        fail("VALIDATION_FAILED", errors[0]["msg"] if errors else None, errors=errors)


# ── config ─────────────────────────────────────────────────────────────────────────────────────
@router.get("/config")
async def questionnaire_config(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    settings = get_settings()
    return {
        "contractVersion": CONTRACT_VERSION,
        "v2Enabled": v2_enabled(),
        "transcriptionPaused": await transcription.is_paused(),
        "noteMaxChars": S.NOTE_MAX,
        "multipartThresholdBytes": MULTIPART_THRESHOLD,
        "partSizeBytes": PART_SIZE,
        "snapshotPollSeconds": 60,
        "joinCodeLength": 6,
        # The version NAME was reset to 0.0.1 (21 Oct 2026), so a name minimum can no longer be
        # compared (audit C7): always null; clients compare their build number with the two below.
        "minAppVersionForCreate": None,
        "minAndroidVersionCodeForCreate": settings.questionnaire_min_android_version_code_for_create,
        "minWebVersionCodeForCreate": settings.questionnaire_min_web_version_code_for_create,
    }


# ── sessions ───────────────────────────────────────────────────────────────────────────────────
V2 = [Depends(require_v2)]


@router.get("/sessions/open-match", dependencies=V2)
async def sessions_open_match(
    questionnaireId: str, artisanIds: list[str] = Query(default=[]), workshopId: str | None = None, date: str | None = None,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    ids = [a for value in artisanIds for a in value.split(",") if a]
    return await sessions.open_match(questionnaire_id=questionnaireId, workshop_id=workshopId, artisan_ids=ids, day=date)


@router.post("/sessions", dependencies=V2)
async def sessions_create(request: Request, user: Any = Depends(get_current_user)) -> JSONResponse:
    payload = await parse(request, S.SessionCreate, forbid_title=True)
    session, created = await sessions.create_session(payload, user)
    return JSONResponse(session, status_code=status.HTTP_201_CREATED if created else status.HTTP_200_OK)


@router.get("/sessions", dependencies=V2)
async def sessions_list(
    workshopId: str | None = None, questionnaireId: str | None = None, artisanId: str | None = None,
    researcherId: str | None = None, dateFrom: str | None = None, dateTo: str | None = None, search: str | None = None,
    cursor: str | None = None, limit: int = Query(20, ge=1, le=100), _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await sessions.list_sessions(
        workshopId=workshopId, questionnaireId=questionnaireId, artisanId=artisanId, researcherId=researcherId,
        dateFrom=dateFrom, dateTo=dateTo, search=search, cursor=cursor, limit=limit,
    )


@router.post("/sessions/join", dependencies=V2)
async def sessions_join(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.JoinRequest)
    return await sessions.join(payload.joinCode, user)


@router.post("/sessions/join-by-key", dependencies=V2)
async def sessions_join_by_key(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.JoinByKeyRequest)
    return await sessions.join_by_key(payload.clientKey, user)


@router.post("/sessions/continue", dependencies=V2)
async def sessions_continue(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ContinueRequest)
    return await sessions.continue_sessions(payload, user)


@router.get("/sessions/{session_id}", dependencies=V2)
async def sessions_get(session_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.core.db import db

    session = await sessions.session_payload(db, session_id)
    rows = await recordings.recording_rows(db, session_id=session_id)
    recs = await recordings.serialize_recordings(db, rows, user)
    participant_ids = [p["artisanId"] for p in session["participants"]]
    grid = await coverage.grid(session["questionnaire"]["id"], artisan_ids=participant_ids, client=db) if participant_ids else None
    session_answers = await answers.answers_for(db, session_id=session_id)
    return {
        **session,
        "recordings": recs,
        "answers": [
            {"artisanId": str(a["artisanId"]), "questionLineageId": str(a["questionLineageId"]), "status": a.get("status"),
             **(answers.answer_out(a) or {})}
            for a in session_answers
        ],
        "grid": grid,
    }


@router.patch("/sessions/{session_id}", dependencies=V2)
async def sessions_patch(session_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.SessionPatch, forbid_title=True)
    return await sessions.patch_session(session_id, payload, user)


@router.delete("/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT, dependencies=V2)
async def sessions_delete(session_id: str, user: Any = Depends(get_current_user)) -> Response:
    await sessions.delete_session(session_id, user)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/sessions/{session_id}/participants", dependencies=V2)
async def sessions_add_participant(session_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ParticipantAdd)
    return await sessions.add_participant(session_id, payload.artisanId, payload.joinedAt, user)


@router.patch("/sessions/{session_id}/participants/{artisan_id}", dependencies=V2)
async def sessions_patch_participant(session_id: str, artisan_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ParticipantPatch)
    return await sessions.patch_participant(session_id, artisan_id, payload, user)


@router.delete("/sessions/{session_id}/participants/{artisan_id}", dependencies=V2)
async def sessions_remove_participant(
    session_id: str, artisan_id: str, detach: bool = False, user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await sessions.remove_participant(session_id, artisan_id, detach, user)


@router.post("/sessions/{session_id}/researchers", dependencies=V2)
async def sessions_add_researcher(session_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ResearcherAdd)
    return await sessions.add_researcher_route(session_id, payload.userId, payload.role, user)


@router.post("/sessions/{session_id}/finish", dependencies=V2)
async def sessions_finish(session_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await sessions.finish(session_id, user)


@router.post("/sessions/{session_id}/merge-into/{target_id}", dependencies=V2)
async def sessions_merge(session_id: str, target_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await sessions.merge_into(session_id, target_id, user)


@router.get("/sessions/{session_id}/insights", dependencies=V2)
async def sessions_insights(session_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The summaries of the session's live takes, one item per take that has a transcript (audit
    M27): ``{"recordingId", "mediaId", "state", "stale", "canEdit", "canRegenerate", "error",
    "insight"}``, the per-clip view of ``GET /api/media/{id}/insights`` without the translation
    text. ``{"items": []}`` while the insights switch is off."""
    from app.api.routes.transcript_insights import may_edit
    from app.core.db import db
    from app.services import transcript_insights

    await sessions.require_session(db, session_id)
    if not transcript_insights.enabled():
        return {"items": []}
    rows = await recordings.recording_rows(db, session_id=session_id)
    by_media = {str(r["mediaFileId"]): str(r["id"]) for r in rows}
    if not by_media:
        return {"items": []}
    media_rows = await db.mediafile.find_many(where={"id": {"in": list(by_media)}})
    current = await transcript_insights.current_insights(list(by_media))
    jobs = await transcript_insights.latest_jobs(list(by_media))
    items = []
    for media in media_rows:
        view = transcript_insights.build_view(
            media, current.get(str(media.id)), jobs.get(str(media.id)), may_edit=may_edit(user, media), include_translation=False,
        )
        if view["state"] == "NO_TRANSCRIPT":
            continue
        items.append({"recordingId": by_media[str(media.id)], **view})
    order = {rid: i for i, rid in enumerate(str(r["id"]) for r in rows)}
    items.sort(key=lambda item: order.get(item["recordingId"], 0))
    return {"items": items}


# ── coverage ───────────────────────────────────────────────────────────────────────────────────
@router.post("/coverage/check", dependencies=V2)
async def coverage_check(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.CoverageCheckRequest)
    return await coverage.check(payload, user)


@router.get("/coverage", dependencies=V2)
async def coverage_grid(
    questionnaireId: str, workshopId: str | None = None, artisanIds: list[str] = Query(default=[]), _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    ids = [a for value in artisanIds for a in value.split(",") if a]
    return await coverage.grid(questionnaireId, artisan_ids=ids or None, workshop_id=workshopId)


@router.get("/coverage/snapshot", dependencies=V2)
async def coverage_snapshot(
    questionnaireId: str, workshopId: str | None = None, sinceSeq: int | None = None, _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await coverage.snapshot(questionnaireId, workshopId, sinceSeq)


# ── recordings ─────────────────────────────────────────────────────────────────────────────────
@router.patch("/recordings/{recording_id}", dependencies=V2)
async def recordings_patch(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.RecordingPatch)
    return await recordings.patch(recording_id, payload, user)


@router.delete("/recordings/{recording_id}", dependencies=V2)
async def recordings_remove(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await recordings.remove(recording_id, user)


@router.post("/recordings/{recording_id}/restore", dependencies=V2)
async def recordings_restore(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await recordings.restore(recording_id, user)


@router.post("/recordings/{recording_id}/decide", dependencies=V2)
async def recordings_decide(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.DecideRequest)
    return await recordings.decide(recording_id, payload, user)


@router.get("/recordings/removed", dependencies=V2)
async def recordings_removed(sessionId: str | None = None, artisanId: str | None = None,
                             user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Removed takes of one session or one artisan, newest first ("Show removed"; audit M12)."""
    return await recordings.removed_takes(user, session_id=sessionId, artisan_id=artisanId)


@router.post("/recordings/{recording_id}/file", dependencies=V2)
async def recordings_file(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """"File it" for a take waiting for a place (audit H14): targets, optionally subjects and session."""
    payload = await parse(request, S.FileRecordingRequest)
    return await recordings.file_recording(recording_id, payload, user)


@router.post("/media/{media_id}/file", dependencies=V2)
async def media_file(media_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """"File it" for an inbox "To file" clip with no recording yet (audit H14)."""
    payload = await parse(request, S.FileMediaRequest)
    return await recordings.file_media(media_id, payload, user)


@router.post("/recordings/{recording_id}/make-primary", dependencies=V2)
async def recordings_make_primary(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.MakePrimaryRequest)
    return await recordings.make_primary(recording_id, payload, user)


@router.post("/recordings/{recording_id}/answer-proposals", dependencies=V2)
async def recordings_proposals(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await answers.proposals(recording_id, user)


@router.post("/recordings/{recording_id}/transcribe", status_code=status.HTTP_202_ACCEPTED, dependencies=V2)
async def recordings_transcribe(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Take-level Retry. While paused: 202 ``{"status": "QUEUED", "paused": true}``, never an error."""
    return await recordings.transcribe(recording_id, user)


@router.get("/recordings/{recording_id}/download", dependencies=V2)
async def recordings_download(recording_id: str, user: Any = Depends(get_current_user)) -> RedirectResponse:
    return RedirectResponse(await recordings.download_url(recording_id, user), status_code=status.HTTP_302_FOUND)


# ── answers, inbox, corpus ─────────────────────────────────────────────────────────────────────
@router.put("/answers", dependencies=V2)
async def answers_put(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.AnswersPut)
    return await answers.put_answers(payload, user)


@router.get("/inbox", dependencies=V2)
async def inbox_get(user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await inbox.inbox(user)


async def _corpus(questionnaire_id: str, status_filter: str | None, kind: str, fmt: str) -> Response:
    from app.services.naming import content_disposition

    data = await corpus.corpus(questionnaire_id, status=status_filter or None)
    names = corpus.file_names(data["title"], fmt)
    if kind == "meta":
        return JSONResponse({"schemaVersion": corpus.SCHEMA_VERSION, "files": [names["answers"], names["recordings"]]})
    rows, fields = (data["recordings"], corpus.RECORDING_FIELDS) if kind == "recordings" else (data["answers"], corpus.ANSWER_FIELDS)
    media_type = "application/x-ndjson" if fmt == "jsonl" else "text/csv"
    return Response(
        corpus.render(rows, fields, fmt), media_type=f"{media_type}; charset=utf-8",
        headers={"Content-Disposition": content_disposition(names[kind if kind == "recordings" else "answers"])},
    )


@router.get("/corpus.jsonl", dependencies=V2)
async def corpus_jsonl(
    questionnaireId: str, status_filter: str | None = Query("APPROVED", alias="status"),
    kind: str = Query("answers", pattern="^(answers|recordings|meta)$"), _: Any = Depends(get_current_user),
) -> Response:
    return await _corpus(questionnaireId, status_filter, kind, "jsonl")


@router.get("/corpus.csv", dependencies=V2)
async def corpus_csv(
    questionnaireId: str, status_filter: str | None = Query("APPROVED", alias="status"),
    kind: str = Query("answers", pattern="^(answers|recordings|meta)$"), _: Any = Depends(get_current_user),
) -> Response:
    return await _corpus(questionnaireId, status_filter, kind, "csv")


# ── artisans: consent (always on) and the record (v2) ──────────────────────────────────────────
@artisans_router.get("/{artisan_id}/consent")
async def artisan_consent_get(
    artisan_id: str, purpose: str = "EXTERNAL_PROCESSING", _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    from app.core.db import db

    if not await db.artisan.find_unique(where={"id": artisan_id}):
        fail("NOT_FOUND", "No such artisan.")
    if purpose not in S.V.ConsentPurpose:
        fail("VALIDATION_FAILED", "Unknown purpose.")
    row = (await consent_gate.current_states("ARTISAN", [artisan_id], purpose=purpose, client=db)).get(artisan_id)
    by = None
    if row and row.get("actorId"):
        actor = await db.user.find_unique(where={"id": str(row["actorId"])})
        by = {"id": str(row["actorId"]), "name": getattr(actor, "name", "") if actor else ""}
    return {
        "artisanId": artisan_id, "purpose": purpose, "state": (row or {}).get("state") or "NOT_RECORDED",
        "basis": (row or {}).get("basis"), "source": (row or {}).get("source"),
        "recordedAt": iso((row or {}).get("recordedAt")), "recordedBy": by,
    }


@artisans_router.post("/{artisan_id}/consent", status_code=status.HTTP_201_CREATED)
async def artisan_consent_post(artisan_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Append one consent row (append-only: a change is a new row). Never blocks recording; it only
    decides whether a recording of this artisan may be SENT OUTSIDE. Consent is assumed by default
    (19 Oct 2026), so a refusal is what stops a send; recording GRANTED after one re-enqueues their
    held recordings (still subject to the pause, and to anyone else's refusal)."""
    from app.core.db import db

    payload = await parse(request, S.ConsentPost)
    if not await db.artisan.find_unique(where={"id": artisan_id}):
        fail("NOT_FOUND", "No such artisan.")
    await consent_gate.record_artisan_consent(
        artisan_id, state=payload.state, basis=payload.basis, actor_id=user.id, purpose=payload.purpose,
        note=payload.note, evidence_media_id=payload.evidenceMediaId, client=db,
    )
    if payload.state == "GRANTED" and payload.purpose == "EXTERNAL_PROCESSING":
        # Every recording of theirs that consent held back, by every link the gate reads (audit M7).
        await transcription.requeue_held(artisan_id=artisan_id, requested_by_id=user.id, client=db)
    return await artisan_consent_get(artisan_id, payload.purpose, user)


@artisans_router.get("/{artisan_id}/record", dependencies=V2)
async def artisan_record_get(artisan_id: str, questionnaireId: str | None = None, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await record.artisan_record(artisan_id, user, questionnaire_id=questionnaireId)


@artisans_router.get("/{artisan_id}/record.csv", dependencies=V2)
async def artisan_record_csv(artisan_id: str, questionnaireId: str | None = None, user: Any = Depends(get_current_user)) -> Response:
    from app.services.naming import content_disposition

    filename, text = await record.artisan_record_csv(artisan_id, user, questionnaire_id=questionnaireId)
    return Response(text, media_type="text/csv; charset=utf-8", headers={"Content-Disposition": content_disposition(filename)})
