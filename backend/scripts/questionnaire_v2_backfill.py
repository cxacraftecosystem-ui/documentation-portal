"""Reconstruct the legacy interviews as v2 sessions, honestly (DESIGN 7.3, decision D12).

MAPPING (one session per legacy row, SAME id, never split):

* the row becomes ``origin = LEGACY_RECONSTRUCTED``; participants, status and reviewer fields stay;
  ``legacyTitle`` = the old title and ``title`` is generated; researchers = the creator (LEAD) and
  every uploader (CONTRIBUTOR); language free text -> a ``shared/languages.json`` code (unmapped ->
  ``other`` + ``languageOther``, listed in the report);
* ``sessionDate`` = the earliest clip time, clamped to the workshop window: inside -> UPLOAD_TIME,
  outside (the 30 Jun batch) -> the window's last day, UPLOAD_TIME_OUT_OF_WINDOW; always "Estimated";
* each clip -> a recording: ``capturedAt`` = the filename stamp or ``recordedAt`` (same clamping),
  targets from the ONE legacy resolver, subjects = the participants, ANSWERED and unnamed,
  ``rosterCount`` = the participant count, ``clientClipKey = legacy:<mediaFileId>``;
* byte-identical copies in ONE interview: the earliest is kept (one with a COMPLETED transcript
  first); the others are soft-removed (purged 30 days later only once their sha is verified);
* byte-identical copies across interviews: ONE new LEGACY group session holding all their
  participants, dated with the earliest copy; the kept recording moves there and the other copies
  are soft-removed. No recording ever carries a subject who is not a participant;
* the same target twice in one session with different bytes: both kept, NEEDS_DECISION, flag LEGACY
  (a Part 2 candidate when within 10 minutes); the same (artisan, target) across sessions: a repeat,
  flag LEGACY, no decision;
* artisan-less interviews and empty approved ones are left for the inbox ("Who was there",
  "Empty session"); NEEDS_DECISION takes stay PENDING; nothing is transcribed (correction (d)).

COMMANDS (all refuse a host not named in --allow-host):

    python scripts/questionnaire_v2_backfill.py --allow-host H                     # dry run (default)
    python scripts/questionnaire_v2_backfill.py --allow-host H --sha-only          # hash the stored objects
    python scripts/questionnaire_v2_backfill.py --allow-host H --apply --run-id R --approved-report P
    python scripts/questionnaire_v2_backfill.py --allow-host H --undo --run-id R

The dry run runs in a verified READ ONLY transaction and writes
``docs/ops/FieldRepository_Questionnaire_<date>_Backfill-Dry-Run-Report.md`` and ``.csv``. The expected
totals come from the assessment (647 clips -> about 409 recordings, about 238 duplicate rows, 3
artisan-less, 3 empty, 8 cross-session repeats) but NOTHING IS HARD-CODED: the acceptance values are
whatever the owner signs off from the dry run. Apply is one transaction per legacy row and
idempotent; a second apply changes nothing. It has NOT been run against production.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import hashlib
import io
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from pathlib import Path
from typing import Any
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import qv2_guard  # noqa: E402

from app import shared_vocab  # noqa: E402
from app.services import naming  # noqa: E402
from app.services.questionnaire_v2 import legacy_resolver  # noqa: E402
from app.services.questionnaire_v2.common import IST, aware  # noqa: E402

PART2_WINDOW = timedelta(minutes=10)
_STAMP = re.compile(r"_(\d{2})(\d{2})(\d{4})(\d{2})(\d{2})(\d{2})?(?=\.[^.]*$|$)")


# ── loading ────────────────────────────────────────────────────────────────────────────────────
async def load(client: Any) -> dict[str, Any]:
    interviews = await client.query_raw(
        'SELECT i."id", i."title", i."questionnaireId", i."workshopId", i."createdById", i."createdAt", i."interviewDate", '
        'i."language", i."status"::text AS status, i."sessionDate", w."startDate" AS wstart, w."endDate" AS wend, w."date" AS wdate '
        'FROM "QuestionnaireInterview" i LEFT JOIN "Workshop" w ON w."id" = i."workshopId" '
        "WHERE i.\"origin\" = 'NATIVE' AND i.\"clientKey\" IS NULL AND i.\"mergedIntoId\" IS NULL "
        "AND COALESCE(i.\"extraMetadata\"->'questionnaireV2'->>'shim', 'false') <> 'true' ORDER BY i.\"createdAt\", i.\"id\""
    )
    ids = [str(i["id"]) for i in interviews]
    participants = await client.query_raw(
        'SELECT "interviewId", "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = ANY($1::text[]) '
        'ORDER BY "interviewId", "sortOrder", "createdAt", "artisanId"', ids,
    )
    media = await client.query_raw(
        'SELECT m."id", m."questionnaireInterviewId" AS "interviewId", m."originalFilename", m."caption", m."extraMetadata", '
        'm."recordedAt", m."createdAt", m."uploadedById", m."contentSha256", m."shaVerifiedAt", m."transcriptStatus", '
        'm."transcriptText", m."status"::text AS status, m."mediaType"::text AS "mediaType" FROM "MediaFile" m '
        "WHERE m.\"questionnaireInterviewId\" = ANY($1::text[]) AND m.\"mediaType\" IN ('AUDIO', 'VIDEO') AND m.\"removedAt\" IS NULL "
        'AND NOT EXISTS (SELECT 1 FROM "QuestionnaireRecording" r WHERE r."mediaFileId" = m."id") ORDER BY m."createdAt", m."id"',
        ids,
    )
    instruments: dict[str, tuple[list, list]] = {}
    for qid in sorted({str(i["questionnaireId"]) for i in interviews}):
        instruments[qid] = await legacy_resolver.load_instrument(client, qid)
    return {"interviews": interviews, "participants": participants, "media": media, "instruments": instruments}


# ── planning (pure) ────────────────────────────────────────────────────────────────────────────
@dataclass
class ClipPlan:
    media_id: str
    interview_id: str
    session_id: str
    kind: str  # RECORDING | DUPLICATE_SAME | DUPLICATE_CROSS
    captured_at: datetime | None = None
    source: str = "UPLOAD_TIME"
    targets: list[dict[str, str]] = field(default_factory=list)
    target_keys: list[str] = field(default_factory=list)
    step: str = ""
    provenance: str = "LEGACY_META"
    sha: str | None = None
    kept_media_id: str | None = None
    filing: str = "FILED"
    decision: str | None = None
    flag: str | None = None
    candidates: list[dict[str, Any]] = field(default_factory=list)
    uploader_id: str = ""
    verified: bool = False


@dataclass
class SessionPlan:
    session_id: str
    legacy_ids: list[str]
    group: bool
    questionnaire_id: str
    workshop_id: str | None
    session_date: date | None
    date_source: str
    participants: list[str]
    researchers: dict[str, str]
    language_code: str | None
    language_other: str | None
    unmapped_language: str | None
    legacy_title: str | None
    artisanless: bool
    empty_approved: bool


@dataclass
class Plan:
    sessions: list[SessionPlan] = field(default_factory=list)
    clips: list[ClipPlan] = field(default_factory=list)

    def totals(self) -> dict[str, int]:
        kinds = Counter(c.kind for c in self.clips)
        return {
            "legacySessions": sum(1 for s in self.sessions if not s.group),
            "groupSessions": sum(1 for s in self.sessions if s.group),
            "clips": len(self.clips),
            "recordings": kinds["RECORDING"],
            "duplicatesSameInterview": kinds["DUPLICATE_SAME"],
            "duplicatesCrossInterview": kinds["DUPLICATE_CROSS"],
            "needsDecision": sum(1 for c in self.clips if c.kind == "RECORDING" and c.filing == "NEEDS_DECISION"),
            "unresolved": sum(1 for c in self.clips if c.kind == "RECORDING" and c.decision == "UNRESOLVED_LEGACY"),
            "crossSessionRepeats": sum(1 for c in self.clips if c.kind == "RECORDING" and c.flag == "LEGACY" and c.filing == "FILED"),
            "artisanless": sum(1 for s in self.sessions if s.artisanless),
            "emptyApproved": sum(1 for s in self.sessions if s.empty_approved),
            "datesOutOfWindow": sum(1 for s in self.sessions if s.date_source == "UPLOAD_TIME_OUT_OF_WINDOW"),
            "unmappedLanguages": sum(1 for s in self.sessions if s.unmapped_language),
        }


def filename_stamp(name: str | None) -> datetime | None:
    m = _STAMP.search(name or "")
    if not m:
        return None
    d, mo, y, hh, mm = (int(m.group(i)) for i in range(1, 6))
    try:
        return datetime(y, mo, d, hh, mm, tzinfo=IST).astimezone(UTC)
    except ValueError:
        return None


def window(row: dict[str, Any]) -> tuple[date, date] | None:
    start = aware(row.get("wstart")) or aware(row.get("wdate"))
    end = aware(row.get("wend")) or start
    if not start:
        return None
    return start.astimezone(IST).date(), end.astimezone(IST).date()


def clamp(moment: datetime | None, win: tuple[date, date] | None) -> tuple[datetime | None, str]:
    if moment is None:
        return None, "UPLOAD_TIME"
    if win is None:
        return moment, "UPLOAD_TIME"
    day = moment.astimezone(IST).date()
    if win[0] <= day <= win[1]:
        return moment, "UPLOAD_TIME"
    return datetime.combine(win[1], time(12, 0), tzinfo=IST).astimezone(UTC), "UPLOAD_TIME_OUT_OF_WINDOW"


def language(value: str | None) -> tuple[str | None, str | None, str | None]:
    text = (value or "").strip()
    if not text:
        return None, None, None
    alias = shared_vocab.LANGUAGE_ALIASES.get(text) or shared_vocab.LANGUAGE_ALIASES.get(text.lower())
    if alias:
        return alias, None, None
    for code, label in shared_vocab.LANGUAGE_LABELS.items():
        if text.casefold() in (code, label.casefold()):
            return code, None, None
    return "other", text[:40], text


def _keep_first(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return sorted(rows, key=lambda m: (0 if str(m.get("transcriptStatus") or "") == "COMPLETED" else 1, aware(m["createdAt"]), str(m["id"])))[0]


def group_session_id(sha: str) -> str:
    return f"legacy-group-{sha[:24]}"


def build_plan(data: dict[str, Any]) -> Plan:
    plan = Plan()
    interviews = {str(i["id"]): i for i in data["interviews"]}
    parts: dict[str, list[str]] = defaultdict(list)
    for p in data["participants"]:
        parts[str(p["interviewId"])].append(str(p["artisanId"]))
    clips_by_interview: dict[str, list] = defaultdict(list)
    for m in data["media"]:
        clips_by_interview[str(m["interviewId"])].append(m)
    by_sha: dict[str, list] = defaultdict(list)
    for m in data["media"]:
        if m.get("contentSha256"):
            by_sha[str(m["contentSha256"]).lower()].append(m)
    cross = {sha: rows for sha, rows in by_sha.items() if len({str(r["interviewId"]) for r in rows}) > 1}
    cross_media = {str(r["id"]): sha for sha, rows in cross.items() for r in rows}

    def clip_time(m: dict[str, Any]) -> datetime | None:
        return filename_stamp(m.get("originalFilename")) or aware(m.get("recordedAt")) or aware(m.get("createdAt"))

    def resolve(m: dict[str, Any], qid: str) -> legacy_resolver.Resolution:
        questions, sections = data["instruments"][qid]
        return legacy_resolver.resolve(m, questions, sections)

    def target_keys(targets: list[dict[str, str]]) -> list[str]:
        return [f"S:{t['sectionId']}" if t.get("sectionId") else f"Q:{t['questionLineageId']}" for t in targets]

    # legacy rows, one session each
    for iid, row in interviews.items():
        win = window(row)
        clips = clips_by_interview.get(iid, [])
        own = [m for m in clips if str(m["id"]) not in cross_media]
        times = [t for t in (clip_time(m) for m in own) if t]
        earliest = min(times) if times else (aware(row.get("interviewDate")) or aware(row.get("createdAt")))
        moment, source = clamp(earliest, win)
        code, other, unmapped = language(row.get("language"))
        researchers = {str(row["createdById"]): "LEAD"}
        for m in clips:
            researchers.setdefault(str(m["uploadedById"]), "CONTRIBUTOR")
        plan.sessions.append(SessionPlan(
            session_id=iid, legacy_ids=[iid], group=False, questionnaire_id=str(row["questionnaireId"]),
            workshop_id=row.get("workshopId"), session_date=moment.astimezone(IST).date() if moment else None,
            date_source=source, participants=parts.get(iid, []), researchers=researchers, language_code=code,
            language_other=other, unmapped_language=unmapped, legacy_title=row.get("title"),
            artisanless=not parts.get(iid), empty_approved=row.get("status") == "APPROVED" and not clips,
        ))
        # same-interview copies
        seen: dict[str, dict[str, Any]] = {}
        groups: dict[str, list] = defaultdict(list)
        for m in own:
            sha = (m.get("contentSha256") or "").lower()
            groups[sha or f"nosha:{m['id']}"].append(m)
        for key, rows in groups.items():
            keep = _keep_first(rows)
            seen[key] = keep
            for m in rows:
                captured, csource = clamp(clip_time(m), win)
                if m is keep:
                    res = resolve(m, str(row["questionnaireId"]))
                    plan.clips.append(ClipPlan(
                        media_id=str(m["id"]), interview_id=iid, session_id=iid, kind="RECORDING", captured_at=captured,
                        source=csource, targets=res.targets, target_keys=target_keys(res.targets), step=res.step,
                        provenance=res.provenance, sha=key if not key.startswith("nosha:") else None,
                        filing="NEEDS_DECISION" if res.needs_decision else "FILED",
                        decision="UNRESOLVED_LEGACY" if res.needs_decision else None, candidates=res.candidates,
                        uploader_id=str(m["uploadedById"]), verified=bool(m.get("shaVerifiedAt")),
                    ))
                else:
                    plan.clips.append(ClipPlan(
                        media_id=str(m["id"]), interview_id=iid, session_id=iid, kind="DUPLICATE_SAME", captured_at=captured,
                        source=csource, sha=key, kept_media_id=str(keep["id"]), uploader_id=str(m["uploadedById"]),
                        verified=bool(m.get("shaVerifiedAt")),
                    ))
    # cross-interview copies: one LEGACY group session per file
    for sha, rows in sorted(cross.items()):
        keep = _keep_first(rows)
        legacy_ids = sorted({str(r["interviewId"]) for r in rows})
        first = interviews[str(keep["interviewId"])]
        union: list[str] = []
        for lid in legacy_ids:
            for a in parts.get(lid, []):
                if a not in union:
                    union.append(a)
        win = window(first)
        moment, source = clamp(min(t for t in (clip_time(r) for r in rows) if t) if any(clip_time(r) for r in rows) else None, win)
        researchers = {str(first["createdById"]): "LEAD"}
        for r in rows:
            researchers.setdefault(str(r["uploadedById"]), "CONTRIBUTOR")
        gid = group_session_id(sha)
        plan.sessions.append(SessionPlan(
            session_id=gid, legacy_ids=legacy_ids, group=True, questionnaire_id=str(first["questionnaireId"]),
            workshop_id=first.get("workshopId"), session_date=moment.astimezone(IST).date() if moment else None,
            date_source=source, participants=union, researchers=researchers, language_code=None, language_other=None,
            unmapped_language=None, legacy_title=None, artisanless=not union, empty_approved=False,
        ))
        for r in rows:
            captured, csource = clamp(clip_time(r), win)
            if r is keep:
                res = resolve(r, str(first["questionnaireId"]))
                plan.clips.append(ClipPlan(
                    media_id=str(r["id"]), interview_id=str(r["interviewId"]), session_id=gid, kind="RECORDING",
                    captured_at=captured, source=csource, targets=res.targets, target_keys=target_keys(res.targets),
                    step=res.step, provenance=res.provenance, sha=sha, filing="NEEDS_DECISION" if res.needs_decision else "FILED",
                    decision="UNRESOLVED_LEGACY" if res.needs_decision else None, candidates=res.candidates,
                    uploader_id=str(r["uploadedById"]), verified=bool(r.get("shaVerifiedAt")),
                ))
            else:
                plan.clips.append(ClipPlan(
                    media_id=str(r["id"]), interview_id=str(r["interviewId"]), session_id=gid, kind="DUPLICATE_CROSS",
                    captured_at=captured, source=csource, sha=sha, kept_media_id=str(keep["id"]),
                    uploader_id=str(r["uploadedById"]), verified=bool(r.get("shaVerifiedAt")),
                ))
    # repeats: same target twice in one session (decide), or across sessions (flag only)
    participants_of = {s.session_id: s.participants for s in plan.sessions}
    recs = [c for c in plan.clips if c.kind == "RECORDING" and c.target_keys]
    by_session_target: dict[tuple[str, str], list[ClipPlan]] = defaultdict(list)
    for c in recs:
        for k in c.target_keys:
            by_session_target[(c.session_id, k)].append(c)
    for (_sid, _key), rows in by_session_target.items():
        if len(rows) < 2:
            continue
        rows.sort(key=lambda c: (c.captured_at or datetime.min.replace(tzinfo=UTC), c.media_id))
        for c in rows:
            c.flag, c.filing = "LEGACY", "NEEDS_DECISION"
            c.decision = c.decision or "CONCURRENT"
        for a, b in zip(rows, rows[1:]):
            if a.captured_at and b.captured_at and b.captured_at - a.captured_at <= PART2_WINDOW:
                b.candidates = [*b.candidates, {"part2Of": a.media_id}]
    seen_artisan_target: dict[tuple[str, str], str] = {}
    for c in sorted(recs, key=lambda c: (c.captured_at or datetime.min.replace(tzinfo=UTC), c.media_id)):
        for a in participants_of.get(c.session_id, []):
            for k in c.target_keys:
                first_session = seen_artisan_target.setdefault((a, k), c.session_id)
                if first_session != c.session_id and c.flag is None:
                    c.flag = "LEGACY"
    return plan


# ── reports ────────────────────────────────────────────────────────────────────────────────────
def report_csv(plan: Plan) -> str:
    buffer = io.StringIO()
    w = csv.writer(buffer, lineterminator="\n")
    w.writerow(["kind", "sessionId", "legacyInterviewId", "mediaFileId", "capturedAt", "capturedAtSource", "targets",
                "resolverStep", "shaGroup", "keptMediaFileId", "filing", "decisionKind", "flag"])
    for s in plan.sessions:
        w.writerow(["SESSION" if not s.group else "GROUP_SESSION", s.session_id, " ".join(s.legacy_ids), "",
                    s.session_date.isoformat() if s.session_date else "", s.date_source, "", "", "", "", "", "", ""])
    for c in sorted(plan.clips, key=lambda c: (c.session_id, c.media_id)):
        w.writerow([c.kind, c.session_id, c.interview_id, c.media_id, c.captured_at.isoformat() if c.captured_at else "",
                    c.source, " ".join(c.target_keys), c.step, (c.sha or "")[:16], c.kept_media_id or "", c.filing,
                    c.decision or "", c.flag or ""])
    return buffer.getvalue()


def report_md(plan: Plan, csv_text: str) -> str:
    totals = plan.totals()
    steps = Counter(c.step for c in plan.clips if c.kind == "RECORDING")
    unmapped = sorted({s.unmapped_language for s in plan.sessions if s.unmapped_language})
    lines = [
        "# Questionnaire v2 backfill: dry-run report",
        "",
        "Nothing has been written. The owner approves this report (add a line",
        "`Approved-by: <owner email> <YYYY-MM-DD>`) before `--apply`; apply refuses if the data has",
        "changed since. No transcription is queued or retried (correction (d)).",
        "",
        "## Totals",
        "",
        "| What | Count |",
        "|---|---|",
        *[f"| {k} | {v} |" for k, v in totals.items()],
        "",
        "Assessment expectation, for comparison only (nothing is hard-coded): 647 clips -> about 409",
        "recordings; about 238 duplicate rows; 3 artisan-less; 3 empty; 8 cross-session repeats.",
        "",
        "## Resolver steps",
        "",
        "| Step | Recordings |",
        "|---|---|",
        *[f"| {k or '(none)'} | {v} |" for k, v in sorted(steps.items())],
        "",
        "## Languages that map to `other`",
        "",
        *([f"- {u}" for u in unmapped] or ["- none"]),
        "",
        f"Rows: see the CSV beside this file (sha256 {hashlib.sha256(csv_text.encode()).hexdigest()}).",
        "",
    ]
    return "\n".join(lines)


# ── apply and undo ─────────────────────────────────────────────────────────────────────────────
async def _apply_session(tx: Any, s: SessionPlan, clips: list[ClipPlan], run_id: str) -> int:
    from app.services.questionnaire_v2 import sessions as v2_sessions
    from app.services.questionnaire_v2 import titles

    changes = 0
    if s.group:
        exists = await tx.query_raw('SELECT 1 FROM "QuestionnaireInterview" WHERE "id" = $1', s.session_id)
        if not exists:
            first = await tx.query_raw('SELECT "createdById", "status"::text AS status FROM "QuestionnaireInterview" WHERE "id" = $1', s.legacy_ids[0])
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireInterview" ("id", "title", "questionnaireId", "workshopId", "createdById", "status", '
                '"sessionDate", "sessionDateEstimated", "state", "origin", "extraMetadata", "updatedAt") VALUES ($1, \'Interview\', $2, $3, $4, '
                "$5::\"RecordStatus\", $6::date, true, 'CLOSED', 'LEGACY_RECONSTRUCTED', $7::jsonb, now())",
                s.session_id, s.questionnaire_id, s.workshop_id, str(first[0]["createdById"]), first[0]["status"],
                s.session_date.isoformat() if s.session_date else None,
                json.dumps({"questionnaireV2": {"backfill": {"runId": run_id, "group": True, "legacyInterviewIds": s.legacy_ids}}}),
            )
            changes += 1
    else:
        row = await tx.query_raw(
            'SELECT "origin"::text AS origin, "title", "sessionDate", "languageCode", "extraMetadata" FROM "QuestionnaireInterview" WHERE "id" = $1',
            s.session_id,
        )
        if not row or row[0]["origin"] == "LEGACY_RECONSTRUCTED":
            return 0
        before = {"title": row[0]["title"], "sessionDate": str(row[0]["sessionDate"]) if row[0].get("sessionDate") else None}
        await tx.execute_raw(
            'UPDATE "QuestionnaireInterview" SET "origin" = \'LEGACY_RECONSTRUCTED\', "legacyTitle" = COALESCE("legacyTitle", "title"), '
            '"sessionDate" = $2::date, "sessionDateEstimated" = true, "languageCode" = COALESCE("languageCode", $3), '
            '"languageOther" = COALESCE("languageOther", $4), "state" = \'CLOSED\', "closedAt" = COALESCE("closedAt", now()), '
            '"extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $5::jsonb, "updatedAt" = now() WHERE "id" = $1',
            s.session_id, s.session_date.isoformat() if s.session_date else None, s.language_code, s.language_other,
            json.dumps({"questionnaireV2": {"backfill": {"runId": run_id, "before": before}}}),
        )
        changes += 1
    if s.group:
        await v2_sessions.add_participants(tx, s.session_id, s.participants)
    for user_id, role in s.researchers.items():
        await v2_sessions.add_researcher(tx, s.session_id, user_id, role)
    for c in clips:
        if c.kind == "RECORDING":
            done = await tx.query_raw('SELECT 1 FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1', c.media_id)
            if done:
                continue
            if c.session_id != c.interview_id:
                await tx.execute_raw(
                    'UPDATE "MediaFile" SET "questionnaireInterviewId" = $2, "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $3::jsonb WHERE "id" = $1',
                    c.media_id, c.session_id, json.dumps({"questionnaireV2": {"movedFrom": c.interview_id, "backfillRunId": run_id}}),
                )
            rid = uuid4().hex
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", "rosterCount", '
                '"recordedById", "filing", "decisionKind", "provenance", "updatedAt") VALUES ($1, $2, $3, $4::timestamp, $5::"CapturedAtSource", '
                '$6, $7, $8::"RecordingFiling", $9, $10::"RecordingProvenance", now()) ON CONFLICT DO NOTHING',
                rid, c.media_id, f"legacy:{c.media_id}", (c.captured_at or datetime.now(UTC)).astimezone(UTC).replace(tzinfo=None).isoformat(),
                c.source, len(s.participants), c.uploader_id, c.filing, c.decision, c.provenance,
            )
            for t, key in zip(c.targets, c.target_keys):
                lineage = t.get("questionLineageId")
                await tx.execute_raw(
                    'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "questionLineageId", "askedQuestionId") '
                    "VALUES ($1, $2, $3, $4, $4) ON CONFLICT DO NOTHING",
                    rid, key, t.get("sectionId"), lineage,
                )
            for a in s.participants:
                await tx.execute_raw(
                    'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named", "flag") '
                    "VALUES ($1, $2, 'ANSWERED', false, $3::\"TakeFlag\") ON CONFLICT DO NOTHING",
                    rid, a, c.flag,
                )
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireRecordingEvent" ("id", "recordingId", "kind", "after", "actorId") VALUES ($1, $2, \'BACKFILL\', $3::jsonb, $4)',
                uuid4().hex, rid, json.dumps({"runId": run_id, "step": c.step, "candidates": c.candidates}), c.uploader_id,
            )
            if c.candidates:
                await tx.execute_raw(
                    'UPDATE "MediaFile" SET "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $2::jsonb WHERE "id" = $1',
                    c.media_id, json.dumps({"questionnaireV2": {"candidates": c.candidates}}),
                )
            changes += 1
        else:
            removed = await tx.execute_raw(
                'UPDATE "MediaFile" SET "removedAt" = now(), "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $2::jsonb '
                'WHERE "id" = $1 AND "removedAt" IS NULL',
                c.media_id, json.dumps({"removedAs": "VERIFIED_DUPLICATE" if c.verified else "DUPLICATE_UNVERIFIED",
                                        "keptMediaId": c.kept_media_id, "questionnaireV2": {"removedByRun": run_id}}),
            )
            changes += int(removed or 0)
    await titles.regenerate_title(tx, s.session_id)
    return changes


async def apply_plan(client: Any, plan: Plan, run_id: str) -> int:
    clips_by_session: dict[str, list[ClipPlan]] = defaultdict(list)
    for c in plan.clips:
        clips_by_session[c.session_id].append(c)
    changed = 0
    for s in sorted(plan.sessions, key=lambda s: (s.group, s.session_id)):
        async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=120)) as tx:
            changed += await _apply_session(tx, s, clips_by_session.get(s.session_id, []), run_id)
    return changed


async def undo(client: Any, run_id: str) -> int:
    undone = 0
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=300)) as tx:
        recs = await tx.query_raw(
            "SELECT DISTINCT e.\"recordingId\" FROM \"QuestionnaireRecordingEvent\" e WHERE e.\"kind\" = 'BACKFILL' AND e.\"after\"->>'runId' = $1", run_id
        )
        for r in recs:
            undone += int(await tx.execute_raw('DELETE FROM "QuestionnaireRecording" WHERE "id" = $1', r["recordingId"]) or 0)
        undone += int(await tx.execute_raw(
            'UPDATE "MediaFile" SET "questionnaireInterviewId" = "extraMetadata"->\'questionnaireV2\'->>\'movedFrom\', '
            '"extraMetadata" = "extraMetadata" #- \'{questionnaireV2,movedFrom}\' #- \'{questionnaireV2,backfillRunId}\' '
            "WHERE \"extraMetadata\"->'questionnaireV2'->>'backfillRunId' = $1", run_id,
        ) or 0)
        undone += int(await tx.execute_raw(
            'UPDATE "MediaFile" SET "removedAt" = NULL, "extraMetadata" = "extraMetadata" - \'removedAs\' - \'keptMediaId\' #- \'{questionnaireV2,removedByRun}\' '
            "WHERE \"extraMetadata\"->'questionnaireV2'->>'removedByRun' = $1", run_id,
        ) or 0)
        groups = await tx.query_raw(
            "SELECT \"id\" FROM \"QuestionnaireInterview\" WHERE \"extraMetadata\"->'questionnaireV2'->'backfill'->>'runId' = $1 "
            "AND \"extraMetadata\"->'questionnaireV2'->'backfill'->>'group' = 'true'", run_id,
        )
        for g in groups:
            await tx.execute_raw('DELETE FROM "QuestionnaireInterview" WHERE "id" = $1', g["id"])
            undone += 1
        rows = await tx.query_raw(
            "SELECT \"id\", \"extraMetadata\"->'questionnaireV2'->'backfill'->'before' AS before FROM \"QuestionnaireInterview\" "
            "WHERE \"extraMetadata\"->'questionnaireV2'->'backfill'->>'runId' = $1", run_id,
        )
        for r in rows:
            before = r.get("before") or {}
            if isinstance(before, str):
                before = json.loads(before)
            await tx.execute_raw(
                'UPDATE "QuestionnaireInterview" SET "origin" = \'NATIVE\', "title" = $2, "legacyTitle" = NULL, "sessionDate" = $3::date, '
                '"sessionDateEstimated" = false, "state" = \'OPEN\', "closedAt" = NULL, '
                '"extraMetadata" = "extraMetadata" #- \'{questionnaireV2,backfill}\' WHERE "id" = $1',
                r["id"], before.get("title") or "Interview", before.get("sessionDate"),
            )
            await tx.execute_raw('DELETE FROM "QuestionnaireInterviewResearcher" WHERE "interviewId" = $1', r["id"])
            undone += 1
    return undone


async def sha_only(client: Any, *, hasher: Any = None) -> int:
    from app.services import s3

    rows = await client.query_raw(
        'SELECT "id", "objectKey" FROM "MediaFile" WHERE "shaVerifiedAt" IS NULL AND "mediaType" IN (\'AUDIO\', \'VIDEO\') '
        'AND "questionnaireInterviewId" IS NOT NULL ORDER BY "createdAt"'
    )
    done = 0
    for r in rows:
        sha, _size = await asyncio.to_thread(hasher or s3.stream_sha256, str(r["objectKey"]))
        await client.execute_raw('UPDATE "MediaFile" SET "contentSha256" = $2, "shaVerifiedAt" = now() WHERE "id" = $1', r["id"], sha)
        done += 1
    return done


async def dry_plan(client: Any) -> Plan:
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=300)) as tx:
        await qv2_guard.assert_read_only(tx)
        return build_plan(await load(tx))


def report_names() -> tuple[str, str]:
    md = naming.export_filename(entity="Questionnaire", what="Backfill-Dry-Run-Report", ext="md")
    return md, md[:-3] + ".csv"


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    try:
        if args.undo:
            if not args.run_id:
                raise qv2_guard.Refused("--undo needs --run-id")
            print(f"UNDONE: {await undo(client, args.run_id)} changes reverted for run {args.run_id}.")
            return 0
        if args.sha_only:
            print(f"SHA: {await sha_only(client)} objects hashed.")
            return 0
        plan = await dry_plan(client)
        csv_text = report_csv(plan)
        md_text = report_md(plan, csv_text)
        md_name, csv_name = report_names()
        if not args.apply:
            qv2_guard.write_report(args.out, md_name, md_text)
            qv2_guard.write_report(args.out, csv_name, csv_text)
            print(json.dumps(plan.totals(), indent=2))
            print("DRY RUN: nothing was written.")
            return 0
        if not args.run_id:
            raise qv2_guard.Refused("--apply needs --run-id")
        approver, day = qv2_guard.check_approval(args.approved_report, md_text)
        changed = await apply_plan(client, plan, args.run_id)
        print(f"APPLIED run {args.run_id} (approved by {approver} on {day}): {changed} changes.")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    qv2_guard.add_common_arguments(parser)
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--undo", action="store_true")
    parser.add_argument("--sha-only", action="store_true")
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
