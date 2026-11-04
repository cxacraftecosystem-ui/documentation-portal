import { qs } from "@/lib/questionnaire/qs";
import { isEstimatedSource } from "@/lib/naming";
import type { CompleteRecording, Recording, RecordSession, RepeatReason, Session, SessionParticipant } from "@/lib/questionnaire/api";

export type TranscriptView = {
  label: string;
  tone: "neutral" | "progress" | "done" | "error";
  retryEnabled: boolean;
  retryReason: string | null;
  offerConsent: boolean;
};

export function transcriptView(recording: Pick<Recording, "transcript" | "consent">, paused: boolean): TranscriptView {
  const status = recording.transcript?.status ?? "QUEUED";
  const verdict = recording.consent?.verdict ?? "ALLOW";
  if (status === "COMPLETED" || status === "DONE")
    return { label: qs("transcribed"), tone: "done", retryEnabled: false, retryReason: null, offerConsent: false };
  if (verdict === "REFUSED" || status === "REFUSED_CONSENT")
    return { label: qs("consentRefusedStatus"), tone: "neutral", retryEnabled: false, retryReason: null, offerConsent: true };
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

export function isLegacyTake(recording: Pick<Recording, "provenance">): boolean {
  return (recording.provenance ?? "").toUpperCase().startsWith("LEGACY");
}

export function consentSubject(recording: Pick<Recording, "subjects" | "consent">, participants: Pick<SessionParticipant, "artisanId" | "consent">[]): string | null {
  const onTake = new Set(recording.subjects.map((s) => s.artisanId));
  const refused = participants.find((p) => onTake.has(p.artisanId) && p.consent?.state === "REFUSED");
  if (refused) return refused.artisanId;
  const source = recording.consent?.source ?? null;
  if (source && onTake.has(source)) return source;
  return recording.subjects.find((s) => s.role === "ANSWERED")?.artisanId ?? recording.subjects[0]?.artisanId ?? null;
}

export function takeSubjects(roster: string[], answered: ReadonlySet<string>, reasons: Record<string, RepeatReason>): CompleteRecording["subjects"] {
  return roster.map((entry): CompleteRecording["subjects"][number] => {
    const answers = answered.has(entry);
    const who = entry.startsWith("R:") ? { respondentId: entry.slice(2) } : { artisanId: entry };
    return { ...who, role: answers ? "ANSWERED" : "PRESENT", named: roster.length > 1, repeatReason: answers ? reasons[entry] ?? null : null };
  });
}

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

export function coverageCell(session: Pick<Session, "counts">): string {
  return qs("listCoverage", { sections: session.counts.sections, takes: session.counts.takes });
}

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

export function subjectRoleFor(recording: Recording, artisanId: string): "ANSWERED" | "PRESENT" | null {
  return recording.subjects.find((s) => s.artisanId === artisanId)?.role ?? null;
}

export function targetLabel(recording: Recording): string {
  if (recording.targets.length === 0) return qs("wholeSession");
  if (recording.targets.length === 1) return recording.targets[0].label;
  const codes = recording.targets.map((t) => t.displayCode).filter(Boolean);
  return codes.length > 1 ? `${codes[0]}–${codes[codes.length - 1]}` : recording.targets[0].label;
}

export function approveBlock(input: { participants: number; liveRecordings: number; answers: number; needsDecision: number }): string | null {
  if (input.needsDecision > 0) return qs("approveBlockedDecide", { count: input.needsDecision });
  if (input.participants === 0) return qs("approveBlockedNoParticipants");
  if (input.liveRecordings === 0 && input.answers === 0) return qs("approveBlockedEmpty");
  return null;
}

export function reviewFlags(recordings: Recording[]): string {
  const live = recordings.filter((r) => !r.removedAt);
  const repeats = live.filter((r) => r.subjects.some((s) => s.repeatReason)).length;
  const decisions = live.filter((r) => r.filing === "NEEDS_DECISION").length;
  const estimated = live.some(isUncertain);
  return estimated ? qs("flagsLine", { repeats, decisions }) : qs("flagsNoEstimate", { repeats, decisions });
}

export function takesFor(recordings: Recording[], artisanId: string): number {
  return recordings.filter((r) => !r.removedAt && r.subjects.some((s) => s.artisanId === artisanId)).length;
}

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
