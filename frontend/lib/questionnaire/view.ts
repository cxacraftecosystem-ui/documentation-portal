/**
 * Pure view rules for the questionnaire v2 screens, kept out of JSX so the unit specs can hold them:
 * the transcript state of a take (§3.14), the sessions-list cells (§4.8), the artisan timeline
 * (§4.6) and the review gate (§3.10).
 */

import { qs } from "@/lib/questionnaire/qs";
import { isEstimatedSource } from "@/lib/naming";
import type { CompleteRecording, Recording, RecordSession, RepeatReason, Session, SessionParticipant } from "@/lib/questionnaire/api";

export type TranscriptView = {
  label: string;
  tone: "neutral" | "progress" | "done" | "error";
  /** Retry / Transcribe now: never enabled while transcription is paused. */
  retryEnabled: boolean;
  retryReason: string | null;
  /** Offer "Record consent" inline (never as an error). */
  offerConsent: boolean;
};

export function transcriptView(recording: Pick<Recording, "transcript" | "consent">, paused: boolean): TranscriptView {
  const status = recording.transcript?.status ?? "QUEUED";
  const verdict = recording.consent?.verdict ?? "ALLOW";
  if (status === "COMPLETED" || status === "DONE")
    return { label: qs("transcribed"), tone: "done", retryEnabled: false, retryReason: null, offerConsent: false };
  if (verdict === "REFUSED" || status === "REFUSED_CONSENT")
    return { label: qs("consentRefusedStatus"), tone: "neutral", retryEnabled: false, retryReason: null, offerConsent: true };
  // Consent is assumed by default (19 Oct 2026); AWAITING_CONSENT survives only on older takes until released.
  if (verdict === "AWAITING" || status === "AWAITING_CONSENT")
    return { label: qs("waitingForConsent"), tone: "neutral", retryEnabled: false, retryReason: null, offerConsent: false };
  if (paused || recording.transcript?.paused)
    return { label: qs("transcriptionPaused"), tone: "neutral", retryEnabled: false, retryReason: qs("transcriptionPausedReason"), offerConsent: false };
  if (status === "PROCESSING" || status === "TRANSCRIBING")
    return { label: qs("transcribing"), tone: "progress", retryEnabled: false, retryReason: null, offerConsent: false };
  if (status === "FAILED")
    return { label: qs("transcriptionFailed"), tone: "error", retryEnabled: true, retryReason: null, offerConsent: false };
  return { label: qs("transcriptQueued"), tone: "neutral", retryEnabled: false, retryReason: null, offerConsent: false };
}

/** A take was imported from the old interviews: provenance LEGACY, LEGACY_META, LEGACY_… (audit, Legacy badge). */
export function isLegacyTake(recording: Pick<Recording, "provenance">): boolean {
  return (recording.provenance ?? "").toUpperCase().startsWith("LEGACY");
}

/**
 * The artisan whose consent a take's "Change consent" is about (audit M22): the subject who refused,
 * never simply the first one.
 */
export function consentSubject(recording: Pick<Recording, "subjects" | "consent">, participants: Pick<SessionParticipant, "artisanId" | "consent">[]): string | null {
  const onTake = new Set(recording.subjects.map((s) => s.artisanId));
  const refused = participants.find((p) => onTake.has(p.artisanId) && p.consent?.state === "REFUSED");
  if (refused) return refused.artisanId;
  const source = recording.consent?.source ?? null;
  if (source && onTake.has(source)) return source;
  return recording.subjects.find((s) => s.role === "ANSWERED")?.artisanId ?? recording.subjects[0]?.artisanId ?? null;
}

/**
 * The subjects of a take: every artisan in the roster, ANSWERED when ticked under "Answered by", else
 * PRESENT (audit M22, DESIGN §3.4) — unticking never drops an artisan from the take.
 */
/**
 * A take's subjects from the roster at the press. A roster entry is an artisan's id, or `R:<id>` for
 * a guest respondent (owner decision 1), who becomes a `respondentId` subject.
 */
export function takeSubjects(roster: string[], answered: ReadonlySet<string>, reasons: Record<string, RepeatReason>): CompleteRecording["subjects"] {
  return roster.map((entry): CompleteRecording["subjects"][number] => {
    const answers = answered.has(entry);
    const who = entry.startsWith("R:") ? { respondentId: entry.slice(2) } : { artisanId: entry };
    return { ...who, role: answers ? "ANSWERED" : "PRESENT", named: roster.length > 1, repeatReason: answers ? reasons[entry] ?? null : null };
  });
}

/** The artisans of a roster (guests left out): what presign and the coverage check are asked about. */
export function rosterArtisans(roster: string[]): string[] {
  return roster.filter((entry) => !entry.startsWith("R:"));
}

const EXTENSION_BY_TYPE: Record<string, string> = {
  "audio/mpeg": "mp3",
  "audio/mp3": "mp3",
  "audio/mp4": "m4a",
  "audio/x-m4a": "m4a",
  "audio/m4a": "m4a",
  "audio/aac": "aac",
  "audio/webm": "webm",
  "audio/ogg": "ogg",
  "audio/opus": "opus",
  "audio/wav": "wav",
  "audio/x-wav": "wav",
  "audio/wave": "wav",
  "audio/flac": "flac",
  "audio/amr": "amr",
  "audio/3gpp": "3gp",
  "video/mp4": "mp4",
  "video/webm": "webm",
  "video/quicktime": "mov",
  "video/3gpp": "3gp"
};

/**
 * What a picked file is sent as (audit, file picks): its extension from the name when it has a usable
 * one, else from its type (a name with no extension used to fail for good); and its time. A file's
 * `lastModified` is when it was last copied or saved, not when it was recorded, so a pick is dated
 * the session day at 12:00 IST (`SESSION_DATE`, shown as estimated) — never the copy time.
 */
export function pickedFileMeta(file: { name: string; type: string }, sessionDate: string | null, now: Date = new Date()): {
  extension: string;
  contentType: string;
  deviceCapturedAt: string;
  capturedAtSource: "SESSION_DATE" | "UPLOAD_TIME";
} {
  const type = (file.type || "").split(";")[0].trim().toLowerCase();
  const dot = file.name.lastIndexOf(".");
  const fromName = dot > 0 ? file.name.slice(dot + 1).toLowerCase() : "";
  const extension = /^[a-z0-9]{1,12}$/.test(fromName) ? fromName : EXTENSION_BY_TYPE[type] ?? (type.startsWith("video/") ? "mp4" : "m4a");
  const contentType = type || (extension === "mp4" || extension === "mov" ? "video/mp4" : extension === "webm" ? "audio/webm" : "audio/mp4");
  if (sessionDate && /^\d{4}-\d{2}-\d{2}$/.test(sessionDate.slice(0, 10))) {
    return { extension, contentType, deviceCapturedAt: `${sessionDate.slice(0, 10)}T06:30:00.000Z`, capturedAtSource: "SESSION_DATE" };
  }
  return { extension, contentType, deviceCapturedAt: now.toISOString(), capturedAtSource: "UPLOAD_TIME" };
}

export function modeLabel(session: Pick<Session, "participantCount">): string {
  return session.participantCount > 1 ? qs("modeGroupOf", { count: session.participantCount }) : qs("modeIndividual");
}

/** "6 sections · 9 takes" — never "0 answers". */
export function coverageCell(session: Pick<Session, "counts">): string {
  return qs("listCoverage", { sections: session.counts.sections, takes: session.counts.takes });
}

/** Search over title, note and legacyTitle (the list shows only the title and note). */
export function sessionMatches(session: Pick<Session, "title" | "note" | "legacyTitle">, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [session.title, session.note, session.legacyTitle].some((v) => (v ?? "").toLowerCase().includes(q));
}

export type TimelineEntry = {
  session: Session;
  dated: Recording[];
  uncertain: Recording[];
};

function isUncertain(recording: Recording): boolean {
  return recording.estimated || isEstimatedSource(recording.capturedAtSource);
}

/**
 * The artisan timeline: sessions newest first; inside each, takes in `capturedAt` order, including
 * PRESENT and whole-session ones; estimated items in an "Order uncertain" band at the end.
 */
export function artisanTimeline(sessions: RecordSession[]): TimelineEntry[] {
  const dateOf = (s: Session) => s.sessionDate ?? s.startedAt ?? "";
  return [...sessions]
    .sort((a, b) => dateOf(b.session).localeCompare(dateOf(a.session)))
    .map(({ session, recordings, uncertain }) => {
      const live = [...recordings, ...uncertain].filter((r) => !r.removedAt);
      const seen = new Set<string>();
      const unique = live.filter((r) => (seen.has(r.id) ? false : (seen.add(r.id), true)));
      return {
        session,
        dated: unique.filter((r) => !isUncertain(r)).sort((a, b) => a.capturedAt.localeCompare(b.capturedAt)),
        uncertain: unique.filter(isUncertain).sort((a, b) => a.capturedAt.localeCompare(b.capturedAt))
      };
    });
}

/** How this artisan figures in a take: answered, or present without answering. */
export function subjectRoleFor(recording: Recording, artisanId: string): "ANSWERED" | "PRESENT" | null {
  return recording.subjects.find((s) => s.artisanId === artisanId)?.role ?? null;
}

export function targetLabel(recording: Recording): string {
  if (recording.targets.length === 0) return qs("wholeSession");
  if (recording.targets.length === 1) return recording.targets[0].label;
  const codes = recording.targets.map((t) => t.displayCode).filter(Boolean);
  return codes.length > 1 ? `${codes[0]}–${codes[codes.length - 1]}` : recording.targets[0].label;
}

/** Approve is disabled, with the reason, for 0 participants, no content, or a take needing a decision. */
export function approveBlock(input: { participants: number; liveRecordings: number; answers: number; needsDecision: number }): string | null {
  if (input.needsDecision > 0) return qs("approveBlockedDecide", { count: input.needsDecision });
  if (input.participants === 0) return qs("approveBlockedNoParticipants");
  if (input.liveRecordings === 0 && input.answers === 0) return qs("approveBlockedEmpty");
  return null;
}

/** The review flags line: "2 repeats · 1 needs a decision · dates estimated". */
export function reviewFlags(recordings: Recording[]): string {
  const live = recordings.filter((r) => !r.removedAt);
  const repeats = live.filter((r) => r.subjects.some((s) => s.repeatReason)).length;
  const decisions = live.filter((r) => r.filing === "NEEDS_DECISION").length;
  const estimated = live.some(isUncertain);
  return estimated ? qs("flagsLine", { repeats, decisions }) : qs("flagsNoEstimate", { repeats, decisions });
}

/** P10's count: the live takes this artisan answered or was present for in the session. */
export function takesFor(recordings: Recording[], artisanId: string): number {
  return recordings.filter((r) => !r.removedAt && r.subjects.some((s) => s.artisanId === artisanId)).length;
}

/**
 * P8 applies when a save would overwrite an answer that came from a take: the artisans whose
 * current answer has a source recording and different text.
 */
export function answersFromTakes(
  existing: { artisanId?: string; text: string; sourceRecordingId: string | null }[],
  artisanIds: string[],
  text: string
): { artisanId: string; sourceRecordingId: string }[] {
  return existing
    .filter((a): a is { artisanId: string; text: string; sourceRecordingId: string } => Boolean(a.artisanId && a.sourceRecordingId))
    .filter((a) => artisanIds.includes(a.artisanId) && a.text.trim() !== text.trim())
    .map((a) => ({ artisanId: a.artisanId, sourceRecordingId: a.sourceRecordingId }));
}
