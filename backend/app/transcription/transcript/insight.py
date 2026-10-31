"""The small rules beside the pipeline: glossary terms, the budget meter, the status, quote cards.

All pure. The store and the routes feed them rows and write back what they decide.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

from app.transcription.transcript.translate import normalise

IST = timezone(timedelta(hours=5, minutes=30), name="IST")

# --- The craft glossary -----------------------------------------------------------------------

TERM_MAX_CHARS = 60
GLOSS_MAX_CHARS = 120


def term_key(term: str) -> str:
    """The identity of a term: accents off, casefolded, letters and digits only."""
    decomposed = unicodedata.normalize("NFKD", str(term or ""))
    plain = "".join(ch for ch in decomposed if not unicodedata.combining(ch)).casefold()
    return re.sub(r"[^\w]+", " ", plain).strip()


@dataclass
class Sighting:
    term: str
    gloss: str
    turn_id: str | None
    start_ms: int | None


def sightings(terms: list[dict[str, str]], turns: list[Any]) -> list[Sighting]:
    """Each term the model reported, located in the first turn whose original or English says it.

    A term that appears in no turn is dropped: the glossary records what was HEARD.
    ``turns`` are objects with ``id``, ``start_ms``, ``text`` and ``english``.
    """
    out: list[Sighting] = []
    seen: set[str] = set()
    for entry in terms:
        term = str(entry.get("term") or "").strip()[:TERM_MAX_CHARS]
        key = term_key(term)
        if not key or key in seen or len(key) < 2:
            continue
        needle = normalise(term)
        home = None
        for turn in turns:
            if needle in normalise(getattr(turn, "text", "") or "") or needle in normalise(
                getattr(turn, "english", "") or ""
            ):
                home = turn
                break
        if home is None:
            continue
        seen.add(key)
        out.append(
            Sighting(
                term,
                str(entry.get("gloss") or "").strip()[:GLOSS_MAX_CHARS],
                str(getattr(home, "id", "")) or None,
                getattr(home, "start_ms", None),
            )
        )
    return out


def match_craft(term: str, crafts: list[tuple[str, str]]) -> str | None:
    """The id of the master craft whose name is this term, contains it as a word, or is contained
    in it (``("id", "name")`` pairs). None when nothing matches plainly."""
    key = term_key(term)
    if not key:
        return None
    padded = f" {key} "
    for craft_id, name in crafts:
        name_key = term_key(name)
        if name_key and (key == name_key or f" {name_key} " in padded):
            return craft_id
    for craft_id, name in crafts:
        name_key = term_key(name)
        if name_key and len(key) >= 4 and padded in f" {name_key} ":
            return craft_id
    return None


# --- The budget meter -------------------------------------------------------------------------

ALERT_AT = 0.8
PAUSE_AT = 1.0
OK = "OK"
ALERT = "ALERT"
PAUSED = "PAUSED"
UNSET = "UNSET"


def month_key(now: datetime | None = None) -> str:
    return (now or datetime.now(UTC)).astimezone(IST).strftime("%Y-%m")


def month_start(now: datetime | None = None) -> datetime:
    local = (now or datetime.now(UTC)).astimezone(IST)
    return local.replace(day=1, hour=0, minute=0, second=0, microsecond=0).astimezone(UTC)


@dataclass
class Meter:
    month: str
    monthly_usd: float | None
    stt_usd: float
    translation_usd: float

    @property
    def spent_usd(self) -> float:
        return round(self.stt_usd + self.translation_usd, 4)

    @property
    def fraction(self) -> float | None:
        if not self.monthly_usd or self.monthly_usd <= 0:
            return None
        return self.spent_usd / self.monthly_usd

    @property
    def state(self) -> str:
        fraction = self.fraction
        if fraction is None:
            return UNSET
        if fraction >= PAUSE_AT:
            return PAUSED
        if fraction >= ALERT_AT:
            return ALERT
        return OK

    def as_json(self, *, paused_by_budget: bool) -> dict[str, Any]:
        fraction = self.fraction
        return {
            "month": self.month,
            "monthlyUsd": self.monthly_usd,
            "spentUsd": round(self.spent_usd, 2),
            "sttUsd": round(self.stt_usd, 2),
            "translationUsd": round(self.translation_usd, 2),
            "percent": None if fraction is None else round(fraction * 100, 1),
            "state": self.state,
            "alertAt": ALERT_AT,
            "pauseAt": PAUSE_AT,
            "pausedByBudget": paused_by_budget,
        }


@dataclass(frozen=True)
class BudgetActions:
    alert: bool
    pause: bool


def budget_actions(meter: Meter, *, alerted_month: str | None, paused_month: str | None) -> BudgetActions:
    """What to do now: alert once a month at 80 %, pause once a month at 100 %."""
    state = meter.state
    return BudgetActions(
        alert=state in {ALERT, PAUSED} and alerted_month != meter.month,
        pause=state == PAUSED and paused_month != meter.month,
    )


# --- The pipeline status ----------------------------------------------------------------------

WAITING_FOR_CONSENT = "WAITING_FOR_CONSENT"
QUEUED = "QUEUED"
TRANSCRIBING = "TRANSCRIBING"
TRANSLATING = "TRANSLATING"
READY = "READY"
NEEDS_ATTENTION = "NEEDS_ATTENTION"
STATUSES = (WAITING_FOR_CONSENT, QUEUED, TRANSCRIBING, TRANSLATING, READY, NEEDS_ATTENTION)

_REFUSED_TRANSCRIPT_STATUSES = {"REFUSED_CONSENT", "AWAITING_CONSENT", "CONSENT_REFUSED"}


@dataclass(frozen=True)
class Status:
    status: str
    queue_reason: str | None = None
    message: str | None = None


def pipeline_status(
    *,
    transcript_status: str | None,
    transcript_error: str | None,
    has_text: bool,
    stt_job: str | None,
    translate_job: str | None,
    translate_error: str | None,
    version_ready: bool,
    pending_turns: int,
    paused: bool,
    budget_paused: bool,
    in_window: bool,
    consent_refused: bool,
) -> Status:
    """One of the six states the clients draw, from what the database says.

    ``stt_job`` / ``translate_job`` are the latest TRANSCRIPTION / TRANSCRIPT_INSIGHTS job statuses
    (QUEUED, PROCESSING, COMPLETED, FAILED) or None.
    """
    if consent_refused or (transcript_status or "").upper() in _REFUSED_TRANSCRIPT_STATUSES:
        return Status(WAITING_FOR_CONSENT, None, "Consent was refused, so this recording stays on this server.")
    if stt_job == "PROCESSING":
        return Status(TRANSCRIBING)
    if stt_job == "QUEUED" or (not has_text and (transcript_status or "").upper() in {"QUEUED", "", "PENDING"}):
        if budget_paused:
            reason = "BUDGET_PAUSED"
        elif paused:
            reason = "PAUSED"
        elif not in_window:
            reason = "WINDOW"
        else:
            reason = "WAITING"
        return Status(QUEUED, reason)
    if not has_text:
        return Status(NEEDS_ATTENTION, None, transcript_error or "The transcription didn't finish.")
    if translate_job in {"QUEUED", "PROCESSING"} or (version_ready and pending_turns):
        return Status(TRANSLATING)
    if translate_job == "FAILED" and not version_ready:
        return Status(NEEDS_ATTENTION, None, translate_error or "The translation didn't finish.")
    return Status(READY)


# --- Quote cards ------------------------------------------------------------------------------

CLIP_PAD_MS = 500


def clip_range(turns: list[Any], duration_ms: int | None) -> tuple[int, int]:
    start = min(int(getattr(t, "start_ms", 0) or 0) for t in turns)
    end = max(int(getattr(t, "end_ms", 0) or 0) for t in turns)
    start = max(0, start - CLIP_PAD_MS)
    end = end + CLIP_PAD_MS
    if duration_ms:
        end = min(end, int(duration_ms))
    return start, max(start, end)


def card_date(moment: datetime | None) -> str:
    """``24 Jun 2026`` in India time."""
    if moment is None:
        return ""
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    local = moment.astimezone(IST)
    return f"{local.day} {local.strftime('%b')} {local.year}"
