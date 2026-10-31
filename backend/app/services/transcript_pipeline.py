"""The transcript pipeline: Scribe v2 turns, Gemini 3.8 Flash English, and everything built on them.

IDENTICAL IN BOTH PORTALS (listed in ``shared/transcription.sha256``). Portal differences live in
``services/transcript_portal.py``. Design: docs/transcription/DESIGN_2026-10-31_Scribe-Gemini-Pipeline.md.

THE QUEUE STEP. ``transcribe -> translate -> insights`` is two jobs on the existing queue:

1. TRANSCRIPTION (unchanged job): the shared ``transcription_jobs.transcribe_recording`` asks Scribe
   with this recording's key terms (:func:`keyterms_for`) and, on success, stores the answer as a new
   :class:`TranscriptVersion` of turns (:func:`store_transcription`).
2. TRANSCRIPT_INSIGHTS (the existing job, behind AI_FEATURES_ENABLED + AI_TRANSCRIPT_INSIGHTS_ENABLED):
   :func:`generate` sends the turns to Gemini once and writes the English per turn, the summary and
   the quotes (the existing ``TranscriptInsight`` row, so every existing reader keeps working) and the
   craft terms heard (the glossary). A recording with no turns falls back to the old
   ``transcript_insights.generate``.

The queue re-reads consent immediately before each of the two sends; every on-demand send here
(re-translate a turn, Ask, auto-draft) re-reads it too (:func:`_send_gate`). Transcription stays
paused while ``AppSetting.transcriptionPaused`` is set, and a used-up monthly budget sets it
(:func:`check_budget`) and holds translation as well (:func:`holds_translation`).

Logs carry ids, counts and exception classes only: never transcript text or a key.
"""

from __future__ import annotations

import asyncio
import inspect
import json
import logging
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services import transcript_insights, transcript_portal, transcript_store
from app.transcription import schedule
from app.transcription.contract import ApiKey
from app.transcription.transcript import (
    PROMPT_VERSION,
    citations,
    gemini,
    insight,
    keyterms,
    render,
    translate,
)
from app.transcription.transcript.errors import PipelineRefused
from app.transcription.transcript.turns import (
    Turn,
    language_label,
    short_language,
    speaker_order,
    turns_from_scribe,
    turns_from_segments,
    turns_from_text,
)
from prisma import Json

logger = logging.getLogger(__name__)

STT_LABELS = {"scribe_v2": "Scribe v2", "scribe_v1": "Scribe v1", "nova-3": "Nova-3", "whisper-1": "Whisper"}
PROVIDER_LABELS = {"elevenlabs": "ElevenLabs", "deepgram": "Deepgram", "whisper": "Whisper", "muse": "Muse"}

KIND_TRANSLATE = "TRANSLATE"
KIND_RETRANSLATE = "RETRANSLATE"
KIND_ASK = "ASK"
KIND_AUTODRAFT = "AUTODRAFT"

BUDGET_NOTICE_KIND = "TRANSCRIPT_BUDGET"


def enabled() -> bool:
    """The existing switch: AI_FEATURES_ENABLED and AI_TRANSCRIPT_INSIGHTS_ENABLED."""
    return bool(transcript_insights.enabled())


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


async def gemini_keys() -> list[ApiKey]:
    """The deployment's Gemini key list (GEMINI_API_KEY, then GEMINI_API_KEYS), UI overrides first."""
    from app.services import managed_secrets

    try:
        await managed_secrets.refresh_if_stale()
    except Exception as exc:  # noqa: BLE001 - the env keys still work
        logger.warning("managed secrets could not be refreshed (%s)", type(exc).__name__)
    return [ApiKey(f"GEMINI_API_KEY#{i + 1}", value) for i, value in enumerate(managed_secrets.gemini_key_pool())]


# --- 1. Key terms and turn storage ------------------------------------------------------------


async def context_for(media: Any) -> dict[str, Any]:
    context = await transcript_store.recording_context(media)
    await transcript_portal.extra_context(media, context)
    return context


async def keyterms_for(media: Any) -> list[str]:
    """At most 100 Scribe key terms for this recording, most specific first (``keyterms.assemble``)."""
    try:
        from app.services import ai, craft_master

        context = await context_for(media)
        craft_ids = [c["id"] for c in context["crafts"] if c.get("id")]
        glossary = await transcript_store.glossary_for(craft_ids, limit=60)
        master = craft_master.load_master()
        states = {s.casefold() for s in context["states"]}
        regional = [m.name for m in master if states and any(p.casefold() in states for p in m.state_of_practice)]
        return keyterms.assemble(
            artisan_names=[a["name"] for a in context["artisans"]],
            craft_names=[c["name"] for c in context["crafts"]],
            glossary_terms=[g["term"] for g in glossary],
            regional_crafts=regional,
            vocabulary=ai.craft_keyterms(),
            master_crafts=[m.name for m in master if not m.is_catch_all],
        )
    except Exception as exc:  # noqa: BLE001 - no key terms is the old behaviour, never a failure
        logger.warning("key terms for media %s could not be built (%s)", _value(media, "id"), type(exc).__name__)
        return []


def accepts_keyterms(transcribe: Any) -> bool:
    try:
        parameters = inspect.signature(transcribe).parameters
    except TypeError, ValueError:
        return False
    return "keyterms" in parameters or any(p.kind is inspect.Parameter.VAR_KEYWORD for p in parameters.values())


def turns_from_result(result: dict[str, Any], *, duration_ms: int | None = None) -> list[Turn]:
    detected = result.get("languageCode")
    words = result.get("words")
    if isinstance(words, list) and words:
        return turns_from_scribe({"words": words, "language_code": detected})
    segments = result.get("segments")
    if isinstance(segments, list) and segments:
        return turns_from_segments(segments, detected=detected)
    return turns_from_text(str(result.get("text") or ""), detected=detected, duration_ms=duration_ms)


async def store_transcription(media: Any, result: dict[str, Any], *, keyterm_count: int = 0) -> Any | None:
    """A completed transcription as a new version of turns. Never raises: the transcript itself is
    already written by the queue, and a turn store that fails costs only the new viewer."""
    media_id = str(_value(media, "id"))
    if result.get("status") != "COMPLETED":
        return None
    try:
        turns = turns_from_result(result, duration_ms=result.get("audioMs"))
        if not turns:
            return None
        duration = result.get("audioMs") or None
        version = await transcript_store.create_version(
            media_id,
            turns,
            stt_provider=result.get("provider") if result.get("model") is None else (
                "cache" if result.get("cacheHit") else result.get("provider")
            ),
            stt_model=result.get("model"),
            detected=short_language(result.get("languageCode")),
            duration_ms=int(duration) if duration else None,
            keyterm_count=keyterm_count,
            stt_cost_usd=0.0 if result.get("cacheHit") else await transcript_store.stt_cost_for(media_id),
        )
        logger.info("transcript version %s stored for media %s (%d turns)", version.version, media_id, len(turns))
        return version
    except Exception as exc:  # noqa: BLE001 - see the docstring
        logger.warning("could not store the turns of media %s (%s)", media_id, type(exc).__name__)
        return None


async def ensure_version(media: Any) -> Any | None:
    """The current version, made from the stored transcript when a recording was transcribed before
    the pipeline existed (its segments, else the raw original, else the transcript text). Local
    only: nothing is sent anywhere. None when there is no transcript."""
    media_id = str(_value(media, "id"))
    version = await transcript_store.current_version(media_id)
    if version is not None:
        return version
    text = str(_value(media, "transcriptText") or "").strip()
    if not text:
        return None
    raw = str(_value(media, "transcriptSummary") or "").strip()
    original = raw if raw and raw != text and _value(media, "transcriptEditedAt") is None else text
    segments = _value(media, "transcriptSegments")
    result = {
        "status": "COMPLETED",
        "text": original,
        "segments": segments if isinstance(segments, list) and _value(media, "transcriptEditedAt") is None else None,
        "provider": _value(media, "transcriptProvider"),
        "model": None,
        "languageCode": _value(media, "transcriptLanguage"),
        "cacheHit": True,  # no new speech-to-text cost: the transcript was paid for long ago
    }
    return await store_transcription(media, result)


# --- 2. Translation and insights (the TRANSCRIPT_INSIGHTS job) --------------------------------


def holds_translation(row: Any) -> bool:
    """True while the monthly budget has paused transcription: translation waits with it."""
    return bool(
        schedule.is_paused(row)
        and _value(row, "transcriptBudgetPausedMonth")
        and _value(row, "transcriptBudgetPausedMonth") == insight.month_key()
    )


def _current_text(row: Any) -> str:
    return (row.editedText if row.editedText is not None else row.machineText) or ""


def _current_english(row: Any) -> str | None:
    if row.editedEnglish is not None:
        return row.editedEnglish
    return row.machineEnglish


def _speaker_labels(turn_rows: list[Any], speakers: dict[str, Any]) -> dict[str, str]:
    labels: dict[str, str] = {}
    for number, key in enumerate(speaker_order([{"speakerKey": r.speakerKey} for r in turn_rows]), start=1):
        mapped = speakers.get(key)
        if mapped is not None and mapped.name:
            labels[key] = f"{mapped.name} (researcher)" if mapped.role == "RESEARCHER" else mapped.name
        else:
            labels[key] = f"Speaker {number}"
    return labels


def _label(labels: dict[str, str], key: str | None) -> str:
    return labels.get(key or "", "Speaker") if key else "Speaker"


def _turns_in(turn_rows: list[Any], labels: dict[str, str]) -> list[translate.TurnIn]:
    return [
        translate.TurnIn(r.id, _label(labels, r.speakerKey), r.language, _current_text(r))
        for r in turn_rows
    ]


async def _write_insight(media: Any, result: translate.PassResult, turn_rows: list[Any], labels: dict[str, str], user_id: str | None) -> Any:
    media_id = str(_value(media, "id"))
    english = {r.id: _current_english(r) for r in turn_rows}
    for tid, out in result.translations.items():
        if out.english:
            english[tid] = out.english
    statuses = [o.status for o in result.translations.values()]
    if statuses and all(s == translate.ALREADY_ENGLISH for s in statuses):
        status, text, note = transcript_insights.NOT_NEEDED, None, transcript_insights.ALREADY_ENGLISH_NOTE
    else:
        failed = sum(1 for s in statuses if s == translate.FAILED)
        status = (
            transcript_insights.FAILED
            if failed and failed == len(statuses)
            else transcript_insights.PARTIAL
            if failed
            else transcript_insights.COMPLETED
        )
        text = "\n\n".join(
            f"**{_label(labels, r.speakerKey)}:** {english.get(r.id) or _current_text(r)}" for r in turn_rows
        )
        note = transcript_insights.TRANSLATION_FAILED_NOTE if failed else None
    speaker_of = {r.id: _label(labels, r.speakerKey) for r in turn_rows}
    quotes = [
        {"original": q["original"], "english": q["english"], "speaker": speaker_of.get(q["turnId"]), "turnId": q["turnId"]}
        for q in result.quotes
    ]
    now = datetime.now(UTC)
    previous = await db.transcriptinsight.find_first(where={"mediaFileId": media_id}, order={"version": "desc"})
    number = (previous.version if previous else 0) + 1
    row = await db.transcriptinsight.create(
        data={
            "mediaFileId": media_id,
            "version": number,
            "sourceHash": transcript_insights.media_source_hash(media),
            "sourceLanguage": result.primary_language,
            "summaryLines": result.summary,
            "quotes": Json(quotes),
            "translationStatus": status,
            "translationText": text,
            "translationNote": note,
            "provider": "gemini",
            "modelId": result.model or gemini.MODEL,
            "modelVersion": None,
            "promptVersion": PROMPT_VERSION,
            "generated": Json({"summaryLines": result.summary, "quotes": quotes, "translationText": text}),
            "generatedAt": now,
            "requestedById": user_id,
        }
    )
    await db.transcriptinsight.update_many(
        where={"mediaFileId": media_id, "supersededAt": None, "version": {"lt": number}},
        data={"supersededAt": now},
    )
    return row


async def generate(media: Any, *, user_id: str | None) -> dict[str, Any]:
    """The insights job's work. Called by the queue AFTER its consent check.

    With turns: one Gemini pass (English per turn, summary, quotes, craft terms). Without: the old
    ``transcript_insights.generate``. Raises ``transcript_insights.InsightsUnavailable`` /
    ``InsightsFailed`` exactly as the old path does, so the queue's handling is unchanged.
    """
    media_id = str(_value(media, "id"))
    try:
        version = await ensure_version(media)
    except Exception as exc:  # noqa: BLE001 - no readable turns: the old path, exactly as before
        logger.warning("turns of media %s unreadable (%s); summarising the text", media_id, type(exc).__name__)
        version = None
    if version is None:
        return await transcript_insights.generate(media, user_id=user_id)
    turn_rows = await transcript_store.turns_of(version.id)
    if not turn_rows:
        return await transcript_insights.generate(media, user_id=user_id)
    keys = await gemini_keys()
    if not keys:
        raise transcript_insights.InsightsUnavailable(gemini.MESSAGES[gemini.UNAVAILABLE])
    labels = _speaker_labels(turn_rows, await transcript_store.speakers_of(media_id))
    has_insight = await transcript_insights.current_insight(media_id) is not None
    wanted = [r for r in turn_rows if r.translationStatus in {"PENDING", "FAILED", "STALE"}]
    translations_only = has_insight and bool(wanted) and len(wanted) < len(turn_rows)
    pass_turns = _turns_in(wanted if translations_only else turn_rows, labels)
    context = await context_for(media)
    craft_ids = [c["id"] for c in context["crafts"] if c.get("id")]
    glossary = await transcript_store.glossary_for(craft_ids, limit=120)
    try:
        result = await asyncio.to_thread(
            translate.run_pass, pass_turns, keys=keys, glossary=glossary, translations_only=translations_only
        )
    except gemini.GeminiError as exc:
        await transcript_store.record_spend(
            media_id=media_id, kind=KIND_TRANSLATE, model=None, key_ref=None, input_tokens=0, output_tokens=0,
            cost_usd=0.0, outcome=exc.kind.upper(),
        )
        raise transcript_insights.InsightsFailed(str(exc), retry=exc.retry) from None
    await transcript_store.record_spend(
        media_id=media_id, kind=KIND_TRANSLATE, model=result.model, key_ref=None,
        input_tokens=result.input_tokens, output_tokens=result.output_tokens, cost_usd=result.cost_usd,
    )
    outcomes = [(tid, out.status, out.english) for tid, out in result.translations.items()]
    await transcript_store.write_translations(version.id, outcomes, result.model)
    failed = len(result.failed_ids)
    statuses = [o.status for o in result.translations.values()]
    version_status = (
        "NOT_NEEDED"
        if statuses and all(s == translate.ALREADY_ENGLISH for s in statuses)
        else "FAILED"
        if failed and failed == len([s for s in statuses if s != translate.ALREADY_ENGLISH])
        else "PARTIAL"
        if failed
        else "COMPLETED"
    )
    await transcript_store.finish_translation(
        version.id, status=version_status, model=result.model, provider="gemini",
        cost_usd=result.cost_usd, prompt_version=PROMPT_VERSION,
    )
    insight_row = None
    if not translations_only:
        insight_row = await _write_insight(media, result, turn_rows, labels, user_id)
        await _remember_terms(media_id, context, result.terms, await transcript_store.turns_of(version.id))
    await _index(media_id)
    await check_budget()
    return {
        "status": "COMPLETED",
        "versionId": version.id,
        "translated": len(outcomes) - failed,
        "failed": failed,
        "insightId": getattr(insight_row, "id", None),
        "costUsd": result.cost_usd,
    }


class _GlossTurn:
    def __init__(self, row: Any) -> None:
        self.id = row.id
        self.start_ms = row.startMs
        self.text = _current_text(row)
        self.english = _current_english(row) or ""


async def _remember_terms(media_id: str, context: dict[str, Any], terms: list[dict[str, str]], turn_rows: list[Any]) -> None:
    if not terms:
        return
    try:
        found = insight.sightings(terms, [_GlossTurn(r) for r in turn_rows])
        craft_id = next((c["id"] for c in context["crafts"] if c.get("id")), None)
        await transcript_store.add_sightings(media_id, craft_id, found, await transcript_store.master_crafts())
    except Exception as exc:  # noqa: BLE001 - the glossary is a by-product, never worth the translation
        logger.warning("glossary terms of media %s not stored (%s)", media_id, type(exc).__name__)


async def _index(media_id: str) -> None:
    """Ask search to rebuild this transcript's document (the indexer applies the consent rules)."""
    try:
        from app.services import search_indexer

        await search_indexer.enqueue("transcripts", media_id)
    except Exception as exc:  # noqa: BLE001 - search catches up on its next sweep
        logger.warning("search enqueue for media %s failed (%s)", media_id, type(exc).__name__)


async def search_english(media_id: str) -> str:
    """The English of the current version, for the search document (empty when none)."""
    version = await transcript_store.current_version(media_id)
    if version is None:
        return ""
    rows = await transcript_store.turns_of(version.id)
    return "\n".join(
        e for r in rows if r.translationStatus != "ALREADY_ENGLISH" and (e := _current_english(r))
    )


# --- 3. The budget meter ----------------------------------------------------------------------


async def _settings_row() -> Any | None:
    from app.services.app_settings import load_app_settings

    return await load_app_settings()


async def meter(row: Any | None = None) -> insight.Meter:
    row = row if row is not None else await _settings_row()
    stt, gem = await transcript_store.month_spend()
    cap = _value(row, "transcriptMonthlyBudgetUsd") if row is not None else 100.0
    return insight.Meter(insight.month_key(), float(cap) if cap is not None else None, stt, gem)


async def budget_view() -> dict[str, Any]:
    row = await _settings_row()
    current = await meter(row)
    return current.as_json(paused_by_budget=holds_translation(row))


async def set_budget(monthly_usd: float, by: str | None) -> dict[str, Any]:
    await db.execute_raw(
        'UPDATE "AppSetting" SET "transcriptMonthlyBudgetUsd" = $1, "updatedById" = $2, "updatedAt" = now() '
        "WHERE \"id\" = 'singleton'",
        float(monthly_usd),
        by,
    )
    return await budget_view()


async def _admins() -> list[Any]:
    return await db.user.find_many(where={"role": {"in": ["MASTER_ADMIN", "ADMIN"]}})


async def check_budget() -> insight.Meter | None:
    """Alert the admins once a month at 80 %; pause transcription once a month at 100 %. Never raises."""
    try:
        row = await _settings_row()
        current = await meter(row)
        actions = insight.budget_actions(
            current,
            alerted_month=_value(row, "transcriptBudgetAlertedMonth"),
            paused_month=_value(row, "transcriptBudgetPausedMonth"),
        )
        if not (actions.alert or actions.pause):
            return current
        from app.services import notifications

        spent = f"USD {current.spent_usd:.2f} of {current.monthly_usd:.0f}"
        if actions.pause:
            claimed = await db.query_raw(
                'UPDATE "AppSetting" SET "transcriptionPaused" = true, "transcriptBudgetPausedMonth" = $1, '
                '"transcriptBudgetAlertedMonth" = $1, "updatedAt" = now() WHERE "id" = \'singleton\' '
                'AND "transcriptBudgetPausedMonth" IS DISTINCT FROM $1 RETURNING "id"',
                current.month,
            )
            if claimed:
                logger.warning("transcript budget used up for %s: transcription paused", current.month)
                for admin in await _admins():
                    await notifications.notify(
                        admin, BUDGET_NOTICE_KIND, "Transcription paused: this month's budget is used up",
                        f"{spent}. Raise the budget or un-pause in Settings.", "/settings",
                    )
            return current
        claimed = await db.query_raw(
            'UPDATE "AppSetting" SET "transcriptBudgetAlertedMonth" = $1, "updatedAt" = now() '
            'WHERE "id" = \'singleton\' AND "transcriptBudgetAlertedMonth" IS DISTINCT FROM $1 RETURNING "id"',
            current.month,
        )
        if claimed:
            for admin in await _admins():
                await notifications.notify(
                    admin, BUDGET_NOTICE_KIND, "80% of this month's transcription budget is used",
                    f"{spent}. Transcription pauses at 100%.", "/settings",
                )
        return current
    except Exception as exc:  # noqa: BLE001 - the meter must never fail the work it measures
        logger.warning("transcript budget check failed (%s)", type(exc).__name__)
        return None


# --- 4. The view ------------------------------------------------------------------------------


async def _latest_job(media_id: str, job_type: str) -> Any | None:
    return await db.mediaprocessingjob.find_first(
        where={"mediaFileId": media_id, "jobType": job_type}, order={"createdAt": "desc"}
    )


def _badges(version: Any) -> list[dict[str, Any]]:
    out = []
    if version is None:
        return out
    if version.sttModel or version.sttProvider:
        out.append({
            "kind": "stt",
            "provider": version.sttProvider,
            "model": version.sttModel,
            "label": STT_LABELS.get(version.sttModel or "", PROVIDER_LABELS.get(version.sttProvider or "", version.sttModel or "")),
        })
    if version.translationModel:
        out.append({
            "kind": "translation",
            "provider": version.translationProvider or "gemini",
            "model": version.translationModel,
            "label": gemini.display_name(version.translationModel),
        })
    return out


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")
    return str(value)


def turn_json(row: Any, labels: dict[str, str]) -> dict[str, Any]:
    edited = row.editedText is not None
    english = _current_english(row)
    return {
        "id": row.id,
        "index": row.position,
        "speakerKey": row.speakerKey,
        "speakerName": _label(labels, row.speakerKey),
        "startMs": row.startMs,
        "endMs": row.endMs,
        "language": row.language,
        "text": _current_text(row),
        "textSource": "EDITED" if edited else "MACHINE",
        "lowConfidence": [] if edited else list(row.lowConfidence or []),
        "words": [] if edited else list(row.words or []),
        "translation": {
            "text": english or (None if row.translationStatus != "ALREADY_ENGLISH" else _current_text(row)),
            "status": row.translationStatus,
            "source": "EDITED" if row.editedEnglish is not None else "MACHINE",
            "model": row.translationModel,
        },
        "revision": row.revision,
    }


async def title_for(media: Any, context: dict[str, Any]) -> str:
    kind = transcript_portal.kind_label(media, context)
    subject = context["artisans"][0]["name"] if context["artisans"] else (
        _value(media, "caption") or _value(media, "originalFilename") or ""
    )
    moment = _value(media, "recordedAt") or _value(media, "createdAt")
    parts = [kind, " ".join(str(subject).split()), insight.card_date(moment)]
    return " · ".join(p for p in parts if p)


async def _status_of(media: Any, version: Any, turn_rows: list[Any], settings_row: Any) -> insight.Status:
    media_id = str(_value(media, "id"))
    stt_job = await _latest_job(media_id, "TRANSCRIPTION")
    tr_job = await _latest_job(media_id, transcript_insights.JOB_TYPE)
    has_text = (
        bool(str(_value(media, "transcriptText") or "").strip())
        or bool(turn_rows)
        or bool(version is not None and version.turnCount)
    )
    refused = False
    if not has_text:
        may, _ = await transcript_portal.may_send(media)
        refused = not may
    pending = sum(1 for r in turn_rows if r.translationStatus == "PENDING")
    return insight.pipeline_status(
        transcript_status=_value(media, "transcriptStatus"),
        transcript_error=_value(media, "transcriptError"),
        has_text=has_text,
        stt_job=getattr(stt_job, "status", None) and str(stt_job.status).split(".")[-1],
        translate_job=getattr(tr_job, "status", None) and str(tr_job.status).split(".")[-1],
        translate_error=getattr(tr_job, "error", None),
        version_ready=version is not None,
        pending_turns=pending if (tr_job is not None and str(tr_job.status).split(".")[-1] in {"QUEUED", "PROCESSING"}) else 0,
        paused=schedule.is_paused(settings_row),
        budget_paused=holds_translation(settings_row),
        in_window=schedule.within_window(settings_row),
        consent_refused=refused,
    )


async def build_view(media: Any, user: Any, *, version_number: int | None = None) -> dict[str, Any]:
    media_id = str(_value(media, "id"))
    version = (
        await transcript_store.version_number(media_id, version_number)
        if version_number is not None
        else await ensure_version(media)
    )
    turn_rows = await transcript_store.turns_of(version.id) if version is not None else []
    speakers = await transcript_store.speakers_of(media_id)
    labels = _speaker_labels(turn_rows, speakers)
    context = await context_for(media)
    settings_row = await _settings_row()
    state = await _status_of(media, version, turn_rows, settings_row)
    row = await transcript_insights.current_insight(media_id)
    quotes = []
    for q in list(getattr(row, "quotes", None) or []):
        if isinstance(q, dict) and isinstance(q.get("original"), str):
            quotes.append({
                "turnId": q.get("turnId") if isinstance(q.get("turnId"), str) else None,
                "original": q["original"],
                "english": q.get("english") or "",
                "speaker": q.get("speaker"),
            })
    admin = transcript_portal.is_admin(user)
    # The write rule itself (``guard_write``'s), so a control the view offers is one the server takes;
    # ``readOnly`` names why it is not offered, in the words the portal already uses.
    read_only = await transcript_portal.read_only(user, media)
    edit = read_only is None
    cost = None
    if admin:
        stt, gem = await transcript_store.media_cost(media_id)
        cost = {"sttUsd": round(stt, 4), "translationUsd": round(gem, 4), "totalUsd": round(stt + gem, 4)}
    languages = list(version.languages or []) if version is not None else []
    return {
        "mediaId": media_id,
        "title": await title_for(media, context),
        "status": state.status,
        "queueReason": state.queue_reason,
        "statusMessage": state.message,
        "version": version.version if version is not None else None,
        "languages": [{"code": c, "label": language_label(c)} for c in languages],
        "durationMs": version.durationMs if version is not None else None,
        "speakerCount": version.speakerCount if version is not None else 0,
        "badges": _badges(version),
        "speakers": [
            {
                "speakerKey": key,
                "label": labels[key],
                "name": getattr(speakers.get(key), "name", None),
                "role": getattr(speakers.get(key), "role", None),
                "personId": getattr(speakers.get(key), "personId", None),
                "suggested": False,
            }
            for key in labels
        ],
        "turns": [turn_json(r, labels) for r in turn_rows],
        "summary": list(getattr(row, "summaryLines", None) or []),
        "quotes": quotes,
        "cost": cost,
        "audio": {"url": transcript_portal.audio_url(media), "durationMs": version.durationMs if version else None},
        "permissions": {
            "edit": edit,
            "retry": edit,
            "transcribeNow": admin,
            "ask": version is not None,
            "autodraft": edit and bool(context.get("recordingId")) and version is not None,
            "quoteCard": version is not None,
            "mapSpeakers": edit and version is not None,
        },
        "readOnly": read_only,
        "revision": version.revision if version is not None else 0,
        "updatedAt": _iso(getattr(version, "updatedAt", None) or _value(media, "updatedAt")),
    }


async def summary_items(media_rows: list[Any]) -> list[dict[str, Any]]:
    ids = [str(m.id) for m in media_rows]
    versions_by = await transcript_store.current_versions(ids)
    settings_row = await _settings_row()
    out = []
    for media in media_rows:
        version = versions_by.get(str(media.id))
        state = await _status_of(media, version, [], settings_row)
        out.append({
            "mediaId": str(media.id),
            "status": state.status,
            "languages": [{"code": c, "label": language_label(c)} for c in (version.languages if version else [])],
            "durationMs": version.durationMs if version else None,
            "speakerCount": version.speakerCount if version else 0,
            "badges": _badges(version),
        })
    return out


async def render_doc(media: Any) -> tuple[render.RenderDoc, dict[str, Any]]:
    media_id = str(_value(media, "id"))
    version = await ensure_version(media)
    if version is None:
        raise PipelineRefused("NOT_READY", "This recording has no transcript yet.")
    rows = await transcript_store.turns_of(version.id)
    labels = _speaker_labels(rows, await transcript_store.speakers_of(media_id))
    context = await context_for(media)
    doc = render.RenderDoc(
        title=await title_for(media, context),
        languages=[language_label(c) for c in (version.languages or [])],
        duration_ms=version.durationMs,
        speaker_count=version.speakerCount,
        turns=[
            render.RenderTurn(
                r.id, _label(labels, r.speakerKey), r.startMs, r.endMs, _current_text(r),
                _current_english(r), r.translationStatus,
            )
            for r in rows
        ],
    )
    return doc, context


def download_name(media: Any, context: dict[str, Any], fmt: str, track: str) -> str:
    from app.services import naming

    what = {
        ("md", "original"): "Transcript", ("md", "english"): "Transcript-English", ("md", "both"): "Transcript-Bilingual",
        ("docx", "original"): "Transcript", ("docx", "english"): "Transcript-English", ("docx", "both"): "Transcript-Bilingual",
        ("srt", "original"): "Subtitles-With-Speakers", ("srt", "english"): "Subtitles-With-Speakers-English",
        ("vtt", "original"): "Subtitles-With-Speakers", ("vtt", "english"): "Subtitles-With-Speakers-English",
    }[(fmt, track)]
    return naming.file_name(
        entity=transcript_portal.entity_for(media, context),
        what=what,
        ext=fmt,
        date=_value(media, "recordedAt") or _value(media, "createdAt"),
        place=context.get("district") or (context["states"][0] if context.get("states") else None),
        craft=context["crafts"][0]["name"] if context.get("crafts") else None,
        subject=context["artisans"][0]["name"] if context.get("artisans") else None,
    )


async def requeue_transcription(media: Any) -> None:
    await transcript_portal.requeue_transcription(media)


async def transcribe_now(media: Any) -> dict[str, Any]:
    return await transcript_portal.transcribe_now(media)


# --- 5. Speakers, edits, re-translate ---------------------------------------------------------


def _check_revision(version: Any, base: int) -> None:
    if version.revision != base:
        raise PipelineRefused("STALE_REVISION", "Changed on another device.")


async def map_speakers(media: Any, user: Any, base_revision: int, mappings: list[Any]) -> None:
    media_id = str(_value(media, "id"))
    version = await transcript_store.current_version(media_id)
    if version is None:
        raise PipelineRefused("NOT_READY", "This recording has no transcript yet.")
    _check_revision(version, base_revision)
    for m in mappings:
        await transcript_store.set_speaker(media_id, m.speakerKey, m.name, m.role, m.personId, getattr(user, "id", None))
    await transcript_store.bump_revision(version.id)
    await _index(media_id)


async def speaker_suggestions(media: Any) -> list[dict[str, Any]]:
    media_id = str(_value(media, "id"))
    version = await transcript_store.current_version(media_id)
    if version is None:
        return []
    rows = await transcript_store.turns_of(version.id)
    context = await context_for(media)
    candidates: list[dict[str, Any]] = []
    for person in context["researchers"]:
        candidates.append({"name": person["name"], "role": "RESEARCHER", "personId": person["id"], "reason": "researcher"})
    uploader = context.get("uploader")
    if uploader and all(c["personId"] != uploader["id"] for c in candidates):
        candidates.append({"name": uploader["name"], "role": "RESEARCHER", "personId": uploader["id"], "reason": "uploader"})
    for person in context["artisans"]:
        candidates.append({"name": person["name"], "role": "ARTISAN", "personId": person["id"], "reason": "roster"})
    speech: dict[str, int] = {}
    questions: dict[str, int] = {}
    for r in rows:
        if r.speakerKey is None:
            continue
        speech[r.speakerKey] = speech.get(r.speakerKey, 0) + len(_current_text(r))
        questions[r.speakerKey] = questions.get(r.speakerKey, 0) + _current_text(r).count("?")
    most = max(speech, key=lambda k: speech[k]) if speech else None
    asker = max(questions, key=lambda k: questions[k]) if questions and max(questions.values()) else None
    out = []
    for key in speaker_order([{"speakerKey": r.speakerKey} for r in rows]):
        ranked = list(candidates)
        if key == most and key != asker:
            ranked.sort(key=lambda c: c["role"] != "ARTISAN")
            ranked = [{**c, "reason": "most_speech"} if c["role"] == "ARTISAN" and i == 0 else c for i, c in enumerate(ranked)]
        elif key == asker:
            ranked.sort(key=lambda c: c["role"] != "RESEARCHER")
        out.append({"speakerKey": key, "candidates": ranked})
    return out


async def edit_turn(media: Any, user: Any, turn_id: str, *, base_revision: int, text: str | None, english: str | None) -> dict[str, Any]:
    row = await transcript_store.turn(turn_id)
    if row is None or row.mediaFileId != str(_value(media, "id")):
        raise PipelineRefused("TURN_UNKNOWN", "That turn isn't in this transcript.", 404)
    if row.revision != base_revision:
        raise PipelineRefused("STALE_REVISION", "Changed on another device.")
    actor = getattr(user, "id", None)
    data: dict[str, Any] = {"revision": {"increment": 1}, "editedAt": datetime.now(UTC), "editedById": actor}
    if text is not None and text.strip() != _current_text(row).strip():
        if row.editedText is None:
            await transcript_store.add_edit(row.id, "ORIGINAL", "MACHINE", row.machineText, None)
        data["editedText"] = text.strip()
        await transcript_store.add_edit(row.id, "ORIGINAL", "EDITED", text.strip(), actor)
        if english is None and row.translationStatus in {"TRANSLATED", "ALREADY_ENGLISH"}:
            data["translationStatus"] = "STALE"
        if english is None and row.translationStatus == "PENDING":
            data["translationStatus"] = "PENDING"
    if english is not None and english.strip() != (_current_english(row) or "").strip():
        if row.editedEnglish is None and row.machineEnglish:
            await transcript_store.add_edit(row.id, "ENGLISH", "MACHINE", row.machineEnglish, None)
        data["editedEnglish"] = english.strip()
        data["translationStatus"] = "TRANSLATED"
        await transcript_store.add_edit(row.id, "ENGLISH", "EDITED", english.strip(), actor)
    if len(data) == 3:
        return turn_json(row, _speaker_labels([row], await transcript_store.speakers_of(row.mediaFileId)))
    updated = await db.transcriptturn.update(where={"id": row.id}, data=data)
    await transcript_store.bump_revision(row.versionId)
    await _index(row.mediaFileId)
    rows = await transcript_store.turns_of(row.versionId)
    return turn_json(updated, _speaker_labels(rows, await transcript_store.speakers_of(row.mediaFileId)))


async def turn_history(media: Any, turn_id: str) -> list[dict[str, Any]]:
    row = await transcript_store.turn(turn_id)
    if row is None or row.mediaFileId != str(_value(media, "id")):
        raise PipelineRefused("TURN_UNKNOWN", "That turn isn't in this transcript.", 404)
    entries = await transcript_store.history(turn_id)
    names: dict[str, str] = {}
    actor_ids = list({e.actorId for e in entries if e.actorId})
    if actor_ids:
        for u in await db.user.find_many(where={"id": {"in": actor_ids}}):
            names[u.id] = u.name
    out = [{"at": _iso(row.createdAt), "field": "ORIGINAL", "source": "MACHINE", "text": row.machineText, "byName": None}]
    for e in entries:
        if e.source == "MACHINE" and e.field == "ORIGINAL":
            continue
        out.append({"at": _iso(e.createdAt), "field": e.field, "source": e.source, "text": e.text, "byName": names.get(e.actorId or "")})
    return out


async def _send_gate(media: Any) -> list[ApiKey]:
    """Before any on-demand send: the switch, consent (re-read now), the budget, a key."""
    if not enabled():
        raise PipelineRefused("NOT_FOUND", "Not found", 404)
    may, refusal = await transcript_portal.may_send(media)
    if not may:
        raise PipelineRefused("CONSENT_REFUSED", refusal or stt_contract_refusal())
    row = await _settings_row()
    if holds_translation(row) or (await meter(row)).state == insight.PAUSED:
        raise PipelineRefused("BUDGET_PAUSED", "This month's transcription budget is used up.")
    keys = await gemini_keys()
    if not keys:
        raise PipelineRefused("AI_UNAVAILABLE", gemini.MESSAGES[gemini.UNAVAILABLE], 503)
    return keys


def stt_contract_refusal() -> str:
    from app.transcription.consent import CONSENT_REFUSED_MESSAGE

    return CONSENT_REFUSED_MESSAGE


async def retranslate_turn(media: Any, user: Any, turn_id: str) -> dict[str, Any]:
    row = await transcript_store.turn(turn_id)
    if row is None or row.mediaFileId != str(_value(media, "id")):
        raise PipelineRefused("TURN_UNKNOWN", "That turn isn't in this transcript.", 404)
    keys = await _send_gate(media)
    rows = await transcript_store.turns_of(row.versionId)
    labels = _speaker_labels(rows, await transcript_store.speakers_of(row.mediaFileId))
    glossed: list[str] = []
    for other in rows:
        english = _current_english(other)
        if other.id != row.id and english and other.position < row.position:
            glossed.extend(translate._gloss_terms(english))
    text = _current_text(row)
    language = row.language
    if language == "en":
        language = "und"  # asked explicitly: let the model say whether it is English
    try:
        result = await asyncio.to_thread(
            translate.run_pass,
            [translate.TurnIn(row.id, _label(labels, row.speakerKey), language, text)],
            keys=keys,
            already_glossed=glossed,
            translations_only=True,
        )
    except gemini.GeminiError as exc:
        raise PipelineRefused("AI_UNAVAILABLE", str(exc), 503) from None
    await transcript_store.record_spend(
        media_id=row.mediaFileId, kind=KIND_RETRANSLATE, model=result.model, key_ref=None,
        input_tokens=result.input_tokens, output_tokens=result.output_tokens, cost_usd=result.cost_usd,
    )
    out = result.translations.get(row.id)
    status = out.status if out else translate.FAILED
    data: dict[str, Any] = {"translationStatus": status, "translationModel": result.model, "revision": {"increment": 1}}
    if out and out.english:
        data["machineEnglish"] = out.english
        data["editedEnglish"] = None
    updated = await db.transcriptturn.update(where={"id": row.id}, data=data)
    if out and out.english:
        await transcript_store.add_edit(row.id, "RETRANSLATE", "MACHINE", out.english, getattr(user, "id", None))
    await transcript_store.bump_revision(row.versionId)
    await _index(row.mediaFileId)
    await check_budget()
    return turn_json(updated, labels)


# --- 6. Ask, auto-draft, quote cards, glossary ------------------------------------------------


async def _cite_turns(media_id: str) -> tuple[Any, list[citations.CiteTurn], dict[str, str]]:
    version = await transcript_store.current_version(media_id)
    if version is None:
        raise PipelineRefused("NOT_READY", "This recording has no transcript yet.")
    rows = await transcript_store.turns_of(version.id)
    labels = _speaker_labels(rows, await transcript_store.speakers_of(media_id))
    return version, [
        citations.CiteTurn(r.id, r.startMs, _label(labels, r.speakerKey), _current_text(r), _current_english(r))
        for r in rows
    ], labels


async def ask(media: Any, question: str) -> dict[str, Any]:
    media_id = str(_value(media, "id"))
    _version, turns, _ = await _cite_turns(media_id)
    keys = await _send_gate(media)
    try:
        result = await asyncio.to_thread(citations.ask, question, turns, keys=keys)
    except gemini.GeminiError as exc:
        raise PipelineRefused("AI_UNAVAILABLE", str(exc), 503) from None
    await transcript_store.record_spend(
        media_id=media_id, kind=KIND_ASK, model=result.model, key_ref=None, input_tokens=0, output_tokens=0,
        cost_usd=result.cost_usd,
    )
    await check_budget()
    return {
        "covered": result.covered,
        "answer": result.answer,
        "citations": [c.as_json() for c in result.citations],
        "model": result.model or gemini.MODEL,
    }


async def autodraft(media: Any, user: Any) -> dict[str, Any]:
    """Drafts for a questionnaire take's questions, through the existing take-drafts flow."""
    from uuid import uuid4

    from app.questionnaire import answer_lock, answers as q_answers, recordings

    media_id = str(_value(media, "id"))
    context = await context_for(media)
    recording_id = context.get("recordingId")
    if not recording_id:
        raise PipelineRefused("NOT_A_QUESTIONNAIRE_TAKE", "Only a questionnaire take can fill a form.")
    row = await recordings.require_recording(db, recording_id)
    await recordings.assert_can_change(db, user, row, reviewers=True)
    rec = (await recordings.serialize_recordings(db, [row], user))[0]
    _version, turns, _ = await _cite_turns(media_id)
    lineages: list[str] = []
    for target in rec.get("targets") or []:
        if target.get("questionLineageId"):
            lineages.append(str(target["questionLineageId"]))
        elif target.get("sectionId"):
            for q in await db.query_raw(
                'SELECT "lineageId" FROM "QuestionnaireQuestion" WHERE "sectionId" = $1 AND "isActive" '
                'AND "retiredAt" IS NULL AND "supersededById" IS NULL ORDER BY "sortOrder"',
                target["sectionId"],
            ):
                lineages.append(str(q["lineageId"]))
    lineages = list(dict.fromkeys(lineages))
    if not lineages:
        return {"drafted": [], "skipped": [], "draftsUrl": f"/questionnaire/recordings/{recording_id}"}
    prompts: dict[str, str] = {}
    for q in await db.query_raw(
        'SELECT DISTINCT ON ("lineageId") "lineageId", "prompt" FROM "QuestionnaireQuestion" '
        'WHERE "lineageId" = ANY($1::text[]) AND "supersededById" IS NULL ORDER BY "lineageId", "createdAt" DESC',
        lineages,
    ):
        prompts[str(q["lineageId"])] = str(q["prompt"])
    answered = [s for s in rec.get("subjects") or [] if s.get("role") == "ANSWERED"]
    owners = [s["artisanId"] for s in answered] if answered and (len(answered) == 1 or all(s.get("named") for s in answered)) else []
    skipped: list[dict[str, str]] = []
    locked: set[str] = set()
    if owners:
        for cur in await db.query_raw(
            f'SELECT {q_answers._CURRENT_COLUMNS}, a."questionLineageId" AS "_lineageId" FROM "QuestionnaireAnswer" a '
            'LEFT JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
            'WHERE a."artisanId" = ANY($1::text[]) AND a."questionLineageId" = ANY($2::text[])',
            owners, lineages,
        ):
            if answer_lock.locked_for(dict(cur), user):
                locked.add(str(cur["_lineageId"]))
    for lineage in lineages:
        if lineage in locked:
            skipped.append({"questionId": lineage, "reason": "LOCKED"})
    asked = [lin for lin in lineages if lin not in locked and lin in prompts]
    if not asked:
        return {"drafted": [], "skipped": skipped, "draftsUrl": f"/questionnaire/recordings/{recording_id}"}
    keys = await _send_gate(media)
    numbered = [(i + 1, prompts[lin]) for i, lin in enumerate(asked)]
    try:
        drafts, reply = await asyncio.to_thread(citations.draft, numbered, turns, keys=keys)
    except gemini.GeminiError as exc:
        raise PipelineRefused("AI_UNAVAILABLE", str(exc), 503) from None
    if reply is not None:
        await transcript_store.record_spend(
            media_id=media_id, kind=KIND_AUTODRAFT, model=reply.model, key_ref=reply.key_ref,
            input_tokens=reply.input_tokens, output_tokens=reply.output_tokens, cost_usd=reply.cost_usd,
        )
    by_n = {d.n: d for d in drafts}
    drafted: list[dict[str, Any]] = []
    rows_to_write: list[tuple[str, str, str]] = []
    for i, lineage in enumerate(asked):
        d = by_n.get(i + 1)
        if d is None:
            skipped.append({"questionId": lineage, "reason": "NOT_COVERED"})
            continue
        for artisan_key in owners or [""]:
            rows_to_write.append((lineage, artisan_key, d.original))
        await db.execute_raw(
            'INSERT INTO "TranscriptDraftCitation" ("recordingId", "questionLineageId", "mediaFileId", "english", "citations", "model") '
            "VALUES ($1, $2, $3, $4, $5::jsonb, $6) ON CONFLICT (\"recordingId\", \"questionLineageId\") DO UPDATE SET "
            '"english" = EXCLUDED."english", "citations" = EXCLUDED."citations", "model" = EXCLUDED."model", "createdAt" = now()',
            recording_id, lineage, media_id, d.english,
            json.dumps([c.as_json() for c in d.citations]), getattr(reply, "model", None),
        )
        drafted.append({
            "questionId": lineage,
            "answerOriginal": d.original,
            "answerEnglish": d.english,
            "citations": [c.as_json() for c in d.citations],
        })
    if rows_to_write:
        await db.execute_raw(
            'INSERT INTO "QuestionnaireTakeDraft" ("id", "recordingId", "questionLineageId", "artisanId", "text", "origin") '
            'SELECT x.id, $1, x.lineage, x.artisan, x.body, \'MACHINE_SUGGESTED\'::"AnswerOrigin" '
            "FROM unnest($2::text[], $3::text[], $4::text[], $5::text[]) AS x(id, lineage, artisan, body) "
            'ON CONFLICT ("recordingId", "questionLineageId", "artisanId") '
            'DO UPDATE SET "text" = EXCLUDED."text", "origin" = EXCLUDED."origin"',
            recording_id,
            [uuid4().hex for _ in rows_to_write],
            [r[0] for r in rows_to_write],
            [r[1] for r in rows_to_write],
            [r[2] for r in rows_to_write],
        )
    await check_budget()
    return {"drafted": drafted, "skipped": skipped, "draftsUrl": f"/questionnaire/recordings/{recording_id}"}


async def quote_card(media: Any, turn_ids: list[str]) -> dict[str, Any]:
    media_id = str(_value(media, "id"))
    may, _refusal = await transcript_portal.may_send(media)
    if not may:
        raise PipelineRefused("CONSENT_REFUSED", "This artisan withdrew consent, so they can't be quoted.")
    doc, context = await render_doc(media)
    wanted = set(turn_ids)
    chosen = [t for t in doc.turns if t.id in wanted]
    if len(chosen) != len(wanted):
        raise PipelineRefused("TURN_UNKNOWN", "That turn isn't in this transcript.", 404)
    version = await transcript_store.current_version(media_id)
    start, end = insight.clip_range(chosen, version.durationMs if version else None)
    rows = {r.id: r for r in await transcript_store.turns_of(version.id)}
    language = rows[chosen[0].id].language if chosen[0].id in rows else "und"
    speaker = chosen[0].speaker_name
    craft = context["crafts"][0]["name"] if context.get("crafts") else None
    artisan = context["artisans"][0]["name"] if context.get("artisans") else None
    date = insight.card_date(_value(media, "recordedAt") or _value(media, "createdAt"))
    attribution = " · ".join(p for p in (artisan or speaker, craft, date) if p)
    markdown = render.copy_selected(doc, [t.id for t in chosen], render.BOTH) + f"\n\n— {attribution}"
    return {
        "turnIds": [t.id for t in chosen],
        "original": "\n".join(t.text.strip() for t in chosen),
        "english": "\n".join(render.english_of(t).strip() for t in chosen),
        "speakerName": speaker,
        "artisanName": artisan,
        "craft": craft,
        "date": date,
        "language": {"code": language, "label": language_label(language)},
        "clip": {"startMs": start, "endMs": end, "url": transcript_portal.audio_url(media)},
        "markdown": markdown,
    }


async def glossary(craft_id: str | None, q: str | None, limit: int, cursor: str | None) -> dict[str, Any]:
    where: dict[str, Any] = {}
    if craft_id:
        where["OR"] = [{"craftId": craft_id}, {"linkedCraftId": craft_id}]
    prefix = insight.term_key(q or "").replace("_", " ").strip()
    if prefix:
        # A prefix of the term's key: term_key keeps letters, digits and spaces only, so nothing
        # here can reach LIKE as a wildcard or a control byte.
        where["termKey"] = {"startswith": prefix}
    args: dict[str, Any] = {"where": where, "order": [{"occurrences": "desc"}, {"id": "asc"}], "take": limit + 1,
                            "include": {"sightings": {"take": 5, "order_by": {"createdAt": "desc"}}}}
    if cursor:
        args["cursor"] = {"id": cursor}
        args["skip"] = 1
    rows = await db.transcriptglossaryterm.find_many(**args)
    craft_ids = list({r.craftId for r in rows if r.craftId} | {r.linkedCraftId for r in rows if r.linkedCraftId})
    names: dict[str, str] = {}
    if craft_ids:
        for c in await db.query_raw('SELECT "id", "name" FROM "Craft" WHERE "id" = ANY($1::text[])', craft_ids):
            names[str(c["id"])] = str(c["name"])
    items = []
    for r in rows[:limit]:
        craft = r.linkedCraftId or r.craftId
        items.append({
            "id": r.id,
            "term": r.term,
            "gloss": r.gloss,
            "craftId": craft,
            "craftName": names.get(craft or ""),
            "occurrences": r.occurrences,
            "sightings": [
                {"mediaId": s.mediaFileId, "turnId": s.turnId, "startMs": s.startMs} for s in (r.sightings or [])
            ],
        })
    return {"items": items, "nextCursor": rows[limit].id if len(rows) > limit else None}
