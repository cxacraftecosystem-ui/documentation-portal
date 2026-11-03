import { shortName } from "@/lib/questionnaire/coverageCache";
import type { Recording } from "@/lib/questionnaire/api";
import type { RecordingChips } from "@/lib/questionnaire/chips";
import { shortDate, durationLabel } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";

export type InboxToFile = { mediaId: string; name: string | null; date: string | null; sessionId: string | null; candidate?: unknown; durationMs?: number | null; chips?: RecordingChips | null };
export type InboxDecision = {
  recordingId: string;
  sessionId: string | null;
  decisionKind: string | null;
  targets: string[];
  artisanNames: string | null;
  capturedAt: string | null;
  otherRecordingId?: string | null;
  chips?: RecordingChips | null;
};
export type LegacyCandidate = { questionLineageId: string; prompt: string | null; score?: number };
export type InboxLegacy = { recordingId: string; sessionId: string | null; candidates: LegacyCandidate[]; capturedAt: string | null; code?: string | null; section?: string | null; chips?: RecordingChips | null };
export type InboxSetDates = { workshopId: string | null; workshop: string | null; sessionIds: string[]; from: string | null; to: string | null; count: number };
export type InboxNoParticipants = { sessionId: string; title: string | null; date: string | null };
export type InboxEmptySession = { sessionId: string; title: string | null; approvedAt: string | null };
export type InboxPrimaryChanged = { eventId: string; recordingId: string; change: unknown; at: string | null; name?: string | null; target?: string | null };

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
      return r.code && r.section
        ? qs("legacyChanged", { code: r.code, date: shortDate(r.capturedAt), section: r.section })
        : qs("inboxLegacyRow", { date: shortDate(r.capturedAt) });
    }
    case "setDates": {
      const r = row as InboxSetDates;
      const span = range(r.from, r.to);
      const line = span ? qs("legacySessionsRange", { count: r.count, range: span }) : qs("legacySessionsRange", { count: r.count, range: "" }).replace(/,\s*$/, "");
      return [line, r.workshop ?? ""].filter(Boolean).join(" · ");
    }
    case "noParticipants": {
      const r = row as InboxNoParticipants;
      return qs("sessionHasNoArtisans", { date: shortDate(r.date) || r.title || "" });
    }
    case "emptySessions": {
      const r = row as InboxEmptySession;
      return qs("emptyApproved", { date: shortDate(r.approvedAt) });
    }
    case "primaryChanged": {
      const r = row as InboxPrimaryChanged;
      return r.name && r.target ? qs("mainTakeChanged", { name: shortName(r.name), target: r.target }) : qs("inboxPrimaryRow", { date: shortDate(r.at) });
    }
  }
}

function targetCode(recording: Recording): string {
  const t = recording.targets[0];
  if (!t) return qs("wholeSession");
  return t.displayCode ?? t.label.replace(/^Section\s+/, "");
}

export function decisionPartner(row: Pick<InboxDecision, "recordingId" | "otherRecordingId">, recordings: Recording[]): Recording | null {
  const live = recordings.filter((r) => !r.removedAt);
  if (row.otherRecordingId) return live.find((r) => r.id === row.otherRecordingId) ?? null;
  const self = live.find((r) => r.id === row.recordingId);
  if (!self) return null;
  const targets = new Set(self.targets.map((t) => t.targetKey));
  const answered = new Set(self.subjects.filter((s) => s.role === "ANSWERED").map((s) => s.artisanId));
  const at = Date.parse(self.capturedAt);
  const candidates = live.filter(
    (r) =>
      r.id !== self.id &&
      r.targets.some((t) => targets.has(t.targetKey)) &&
      r.subjects.some((s) => s.role === "ANSWERED" && answered.has(s.artisanId))
  );
  candidates.sort((a, b) => Math.abs(Date.parse(a.capturedAt) - at) - Math.abs(Date.parse(b.capturedAt) - at));
  return candidates[0] ?? null;
}

export function decisionLabel(row: InboxDecision, self: Recording, other: Recording): string {
  const [first, second] = [self, other].sort((a, b) => a.capturedAt.localeCompare(b.capturedAt));
  const names = row.artisanNames
    ? row.artisanNames
        .split(",")
        .map((n) => shortName(n))
        .filter(Boolean)
        .join(", ")
    : qs("inboxNoOneNamed");
  return qs("twoTakesOf", { target: targetCode(self), name: names, date: shortDate(first.capturedAt), first: time(first.capturedAt), second: time(second.capturedAt) });
}

export function fileItDefaults(row: InboxToFile): { sessionId: string; targetKey: string; subjectArtisanIds: string[] } {
  const c = row.candidate && typeof row.candidate === "object" ? (row.candidate as Record<string, unknown>) : {};
  const sessionId = typeof c.sessionId === "string" ? c.sessionId : row.sessionId ?? "";
  const lineage = typeof c.questionLineageId === "string" ? c.questionLineageId : null;
  const section = typeof c.sectionId === "string" ? c.sectionId : null;
  const subjects = Array.isArray(c.subjectArtisanIds) ? c.subjectArtisanIds.filter((x): x is string => typeof x === "string") : [];
  return { sessionId, targetKey: lineage ? `Q:${lineage}` : section ? `S:${section}` : "", subjectArtisanIds: subjects };
}

export function fileItBody(input: { sessionId: string; targetKey: string; subjectArtisanIds: string[] }) {
  const ref = input.targetKey.slice(2);
  const targets = !input.targetKey ? [] : input.targetKey.startsWith("S:") ? [{ sectionId: ref }] : [{ questionLineageId: ref }];
  return { sessionId: input.sessionId, targets, subjects: input.subjectArtisanIds.map((artisanId) => ({ artisanId, role: "ANSWERED" })) };
}

export function normaliseInbox(raw: Partial<Inbox> | null | undefined): Inbox {
  const get = <K extends InboxSectionKey>(k: K) => (Array.isArray(raw?.[k]) ? (raw![k] as Inbox[K]) : ([] as unknown as Inbox[K]));
  const sections = Object.fromEntries(INBOX_ORDER.map((k) => [k, get(k)])) as Omit<Inbox, "counts">;
  const total = INBOX_ORDER.reduce((n, k) => n + sections[k].length, 0);
  return { ...sections, counts: { total: raw?.counts?.total ?? total, olderThan24h: raw?.counts?.olderThan24h ?? 0 } };
}

export function visibleInbox(inbox: Inbox, dismissed: ReadonlySet<string>): Inbox {
  const sections = Object.fromEntries(
    INBOX_ORDER.map((k) => [k, (inbox[k] as Record<string, unknown>[]).filter((row) => !dismissed.has(inboxRowKey(k, row)))])
  ) as unknown as Omit<Inbox, "counts">;
  const total = INBOX_ORDER.reduce((n, k) => n + sections[k].length, 0);
  return { ...sections, counts: { total, olderThan24h: Math.min(inbox.counts.olderThan24h, total) } };
}

const DISMISSED_KEY = "field_repo_questionnaire_inbox_dismissed";

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
  }
}

export function reviewRequest(action: "approve" | "revise", sessionId: string, note: string): { path: string; body: { notes: string | null } } | null {
  const notes = note.trim();
  if (action === "revise" && !notes) return null;
  return { path: `/review/questionnaire/${encodeURIComponent(sessionId)}/${action}`, body: { notes: notes || null } };
}

export type ArtisanListRead = "idle" | "reading" | "read" | "failed";

export function artisanReadDue(needed: boolean, state: ArtisanListRead): boolean {
  return needed && state === "idle";
}

export function artisanReadOnInboxReload(state: ArtisanListRead): ArtisanListRead {
  return state === "failed" ? "idle" : state;
}
