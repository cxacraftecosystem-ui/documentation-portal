/**
 * Pure view rules for the questionnaire v2 screens, kept out of JSX so the unit specs can hold them:
 * the transcript state of a take (§3.14), the sessions-list cells (§4.8), the artisan timeline
 * (§4.6) and the review gate (§3.10).
 */

import { qs } from "@/lib/strings/qs";
import { isEstimatedSource } from "@/lib/naming";
import type { Recording, RecordSession, Session } from "@/lib/questionnaireApi";

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
    return { label: qs("consentRefusedStatus"), tone: "neutral", retryEnabled: false, retryReason: null, offerConsent: false };
  if (verdict === "AWAITING" || status === "AWAITING_CONSENT")
    return { label: qs("waitingForConsent"), tone: "neutral", retryEnabled: false, retryReason: null, offerConsent: true };
  if (paused || recording.transcript?.paused)
    return { label: qs("transcriptionPaused"), tone: "neutral", retryEnabled: false, retryReason: qs("transcriptionPausedReason"), offerConsent: false };
  if (status === "PROCESSING" || status === "TRANSCRIBING")
    return { label: qs("transcribing"), tone: "progress", retryEnabled: false, retryReason: null, offerConsent: false };
  if (status === "FAILED")
    return { label: qs("transcriptionFailed"), tone: "error", retryEnabled: true, retryReason: null, offerConsent: false };
  return { label: qs("transcriptQueued"), tone: "neutral", retryEnabled: false, retryReason: null, offerConsent: false };
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
