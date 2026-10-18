/**
 * The inbox (DESIGN §4.7) and the review decision, as data the screens and the unit specs share.
 *
 * The row shapes are what `GET /questionnaire/inbox` sends (backend `services/questionnaire_v2/
 * inbox.py`); each section has its own fields, so a row's label is built here from them.
 */

import { shortDate, durationLabel } from "@/lib/questionnairePrompts";
import { qs } from "@/lib/strings/qs";

export type InboxToFile = { mediaId: string; name: string | null; date: string | null; sessionId: string | null; candidate?: unknown; durationMs?: number | null };
export type InboxDecision = { recordingId: string; sessionId: string | null; decisionKind: string | null; targets: string[]; artisanNames: string | null; capturedAt: string | null };
export type LegacyCandidate = { questionLineageId: string; prompt: string | null; score?: number };
export type InboxLegacy = { recordingId: string; sessionId: string | null; candidates: LegacyCandidate[]; capturedAt: string | null };
export type InboxSetDates = { workshopId: string | null; workshop: string | null; sessionIds: string[]; from: string | null; to: string | null; count: number };
export type InboxNoParticipants = { sessionId: string; title: string | null; date: string | null };
export type InboxEmptySession = { sessionId: string; title: string | null; approvedAt: string | null };
export type InboxPrimaryChanged = { eventId: string; recordingId: string; change: unknown; at: string | null };

export type Inbox = {
  toFile: InboxToFile[];
  needsDecision: InboxDecision[];
  legacy: InboxLegacy[];
  setDates: InboxSetDates[];
  noParticipants: InboxNoParticipants[];
  emptySessions: InboxEmptySession[];
  primaryChanged: InboxPrimaryChanged[];
  counts: { total: number; olderThan24h: number };
};

export type InboxSectionKey = Exclude<keyof Inbox, "counts">;
export const INBOX_ORDER: InboxSectionKey[] = ["toFile", "needsDecision", "legacy", "setDates", "noParticipants", "emptySessions", "primaryChanged"];

/** The key a row is dismissed and rendered under. */
export function inboxRowKey(section: InboxSectionKey, row: Record<string, unknown>): string {
  const id = row.mediaId ?? row.recordingId ?? row.eventId ?? row.sessionId ?? row.workshopId ?? "none";
  return `${section}:${String(id)}`;
}

function time(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Kolkata" });
}

function range(from: string | null, to: string | null): string {
  if (!from && !to) return "";
  if (!to || from === to) return shortDate(from ?? to);
  if (!from) return shortDate(to);
  const [a, b] = [shortDate(from), shortDate(to)];
  const [ad, am] = a.split(" ");
  const [bd, bm] = b.split(" ");
  return am === bm ? `${ad}–${bd} ${bm}` : `${a}–${b}`;
}

/** The one-line label of a row, in the DESIGN §4.7 row style. */
export function inboxLabel(section: InboxSectionKey, row: unknown): string {
  switch (section) {
    case "toFile": {
      const r = row as InboxToFile;
      return [r.name ?? qs("inboxUnnamedClip"), durationLabel(r.durationMs ?? null), shortDate(r.date)].filter(Boolean).join(" · ");
    }
    case "needsDecision": {
      const r = row as InboxDecision;
      const when = [shortDate(r.capturedAt), time(r.capturedAt)].filter(Boolean).join(" ");
      return qs("inboxDecisionRow", { names: r.artisanNames || qs("inboxNoOneNamed"), when });
    }
    case "legacy": {
      const r = row as InboxLegacy;
      return qs("inboxLegacyRow", { date: shortDate(r.capturedAt) });
    }
    case "setDates": {
      const r = row as InboxSetDates;
      return [qs("inboxSetDatesRow", { count: r.count }), r.workshop ?? "", range(r.from, r.to)].filter(Boolean).join(" · ");
    }
    case "noParticipants": {
      const r = row as InboxNoParticipants;
      return qs("inboxNoArtisansRow", { date: shortDate(r.date) || r.title || "" });
    }
    case "emptySessions": {
      const r = row as InboxEmptySession;
      return qs("inboxEmptyRow", { date: shortDate(r.approvedAt) });
    }
    case "primaryChanged": {
      const r = row as InboxPrimaryChanged;
      return qs("inboxPrimaryRow", { date: shortDate(r.at) });
    }
  }
}

/** A blank-safe read of the inbox: a section the server leaves out reads as empty. */
export function normaliseInbox(raw: Partial<Inbox> | null | undefined): Inbox {
  const get = <K extends InboxSectionKey>(k: K) => (Array.isArray(raw?.[k]) ? (raw![k] as Inbox[K]) : ([] as unknown as Inbox[K]));
  const sections = Object.fromEntries(INBOX_ORDER.map((k) => [k, get(k)])) as Omit<Inbox, "counts">;
  const total = INBOX_ORDER.reduce((n, k) => n + sections[k].length, 0);
  return { ...sections, counts: { total: raw?.counts?.total ?? total, olderThan24h: raw?.counts?.olderThan24h ?? 0 } };
}

/** What the inbox shows once locally dismissed rows ("Keep", "OK") are taken out. */
export function visibleInbox(inbox: Inbox, dismissed: ReadonlySet<string>): Inbox {
  const sections = Object.fromEntries(
    INBOX_ORDER.map((k) => [k, (inbox[k] as Record<string, unknown>[]).filter((row) => !dismissed.has(inboxRowKey(k, row)))])
  ) as unknown as Omit<Inbox, "counts">;
  const total = INBOX_ORDER.reduce((n, k) => n + sections[k].length, 0);
  return { ...sections, counts: { total, olderThan24h: Math.min(inbox.counts.olderThan24h, total) } };
}

const DISMISSED_KEY = "field_repo_questionnaire_inbox_dismissed";

/** Rows the viewer has acknowledged on this device; a per-viewer convenience, never shared state. */
export function readDismissed(): Set<string> {
  try {
    const raw = window.localStorage.getItem(DISMISSED_KEY);
    const list = raw ? (JSON.parse(raw) as unknown) : [];
    return new Set(Array.isArray(list) ? list.filter((x): x is string => typeof x === "string") : []);
  } catch {
    return new Set();
  }
}

export function writeDismissed(keys: Set<string>): void {
  try {
    window.localStorage.setItem(DISMISSED_KEY, JSON.stringify([...keys].slice(-500)));
  } catch {
    // Private window or blocked storage: the row simply comes back next time.
  }
}

/**
 * Approve or Return on a v2 session. The review routes take the record type `questionnaire` (the
 * session is the interview row; `routes/review.py` `_DELEGATE_ATTRS`). Return needs the reviewer's
 * words: `/revise` refuses an empty note with 422.
 */
export function reviewRequest(action: "approve" | "revise", sessionId: string, note: string): { path: string; body: { notes: string | null } } | null {
  const notes = note.trim();
  if (action === "revise" && !notes) return null;
  return { path: `/review/questionnaire/${encodeURIComponent(sessionId)}/${action}`, body: { notes: notes || null } };
}
