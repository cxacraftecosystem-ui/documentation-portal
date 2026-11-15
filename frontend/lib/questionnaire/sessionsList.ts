import type { Session, SessionDetail, SessionListState, SessionTotals } from "@/lib/questionnaire/api";
import { languageLabel } from "@/lib/questionnaire/languages";
import { qs } from "@/lib/questionnaire/qs";

export type SessionStatus = "ALL" | "OPEN" | "FINISHED";

export const SESSION_STATUSES: readonly SessionStatus[] = ["ALL", "OPEN", "FINISHED"];

export const STATUS_KEY = { ALL: "sessionsAll", OPEN: "sessionsOpen", FINISHED: "sessionsFinished" } as const;

export const STATUS_PARAM: Record<SessionStatus, SessionListState> = { ALL: "all", OPEN: "open", FINISHED: "finished" };

export const LIST_SHOWN = 20;

type Listed = Pick<Session, "state">;

export function isFinished(session: Listed): boolean {
  return session.state === "CLOSED";
}

export function statusCounts(items: Listed[]): Record<SessionStatus, number> {
  const finished = items.filter(isFinished).length;
  return { ALL: items.length, OPEN: items.length - finished, FINISHED: finished };
}

export function totalsCounts(totals: SessionTotals): Record<SessionStatus, number> {
  return { ALL: totals.all, OPEN: totals.open, FINISHED: totals.finished };
}

export function byStatus<T extends Listed>(items: T[], status: SessionStatus): T[] {
  if (status === "ALL") return items;
  return items.filter((s) => (status === "FINISHED" ? isFinished(s) : !isFinished(s)));
}

export function questionProgress(session: Pick<Session, "counts" | "progress">): { answered: number; total: number } | null {
  const answered = session.progress?.answeredQuestions ?? session.counts.answeredQuestions;
  const total = session.progress?.totalQuestions ?? session.counts.totalQuestions;
  if (typeof answered !== "number" || typeof total !== "number" || total <= 0) return null;
  return { answered: Math.min(answered, total), total };
}

export function percentOf(progress: { answered: number; total: number } | null): number {
  if (!progress || progress.total <= 0) return 0;
  return Math.round((progress.answered / progress.total) * 100);
}

export function namesLine(session: Pick<Session, "participants" | "respondents">): string {
  const names = [
    ...session.participants.map((p) => p.name),
    ...(session.respondents ?? []).filter((r) => !r.artisanId).map((r) => (r.kind === "SELF" ? r.name || qs("myself") : r.name))
  ]
    .map((n) => (n ?? "").trim())
    .filter(Boolean);
  if (names.length === 0) return "";
  if (names.length === 1) return names[0];
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return qs("andOthers", { name: names[0], count: names.length - 1 });
}

export function workshopName(session: Pick<Session, "workshop" | "designWorkshopId">, names: Record<string, string>): string | null {
  if (session.workshop?.name) return session.workshop.name;
  const id = session.workshop?.id ?? session.designWorkshopId;
  return id ? names[id] ?? null : null;
}

export function subLine(
  session: Pick<Session, "crafts" | "workshop" | "designWorkshopId" | "languageCode" | "languageOther">,
  names: Record<string, string>,
  withLanguage = false
): string {
  const parts = [(session.crafts ?? []).join(", "), workshopName(session, names) ?? "", withLanguage ? languageLabel(session.languageCode, session.languageOther) : ""];
  return parts.map((p) => p.trim()).filter(Boolean).join(" · ");
}

export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export function openToday<T extends Pick<Session, "state" | "sessionDate" | "startedAt" | "researchers">>(items: T[], today: string, viewerId: string | null): T | null {
  const open = items.filter((s) => !isFinished(s) && (s.sessionDate ?? "").slice(0, 10) === today);
  if (open.length === 0) return null;
  const mine = viewerId ? open.filter((s) => s.researchers.some((r) => r.userId === viewerId)) : [];
  const pool = mine.length ? mine : open;
  return [...pool].sort((a, b) => (b.startedAt ?? "").localeCompare(a.startedAt ?? ""))[0];
}

export type ContinuePoint = { sectionId: string; code: string };

export function continuePoint(detail: Pick<SessionDetail, "recordings" | "grid" | "progress">): ContinuePoint | null {
  const sections = detail.grid?.sections ?? [];
  if (sections.length === 0) return null;
  const sectionOf = (sectionId: string | null, code: string | null) => {
    if (sectionId) {
      const hit = sections.find((s) => s.sectionId === sectionId);
      if (hit) return hit;
    }
    if (!code) return null;
    return [...sections].sort((a, b) => b.code.length - a.code.length).find((s) => code.startsWith(s.code)) ?? null;
  };
  const takes = [...(detail.recordings ?? [])].filter((r) => !r.removedAt).sort((a, b) => (b.capturedAt ?? "").localeCompare(a.capturedAt ?? ""));
  for (const take of takes) {
    for (const target of take.targets) {
      const section = sectionOf(target.sectionId, target.displayCode);
      if (section) return { sectionId: section.sectionId, code: target.displayCode || section.code };
    }
  }
  const done = new Set((detail.progress?.sections ?? []).filter((s) => s.totalQuestions > 0 && s.answeredQuestions >= s.totalQuestions).map((s) => s.sectionId));
  const next = sections.find((s) => !done.has(s.sectionId)) ?? sections[0];
  return { sectionId: next.sectionId, code: next.code };
}

export function sectionsDone(detail: Pick<SessionDetail, "grid" | "progress">): { done: number; total: number } | null {
  const total = detail.grid?.sections?.length ?? detail.progress?.sections.length ?? 0;
  if (!total || !detail.progress) return null;
  const done = detail.progress.sections.filter((s) => s.totalQuestions > 0 && s.answeredQuestions >= s.totalQuestions).length;
  return { done, total };
}

export function sessionHref(session: Pick<Session, "id" | "state">, section?: string | null): string {
  const base = `/questionnaire/sessions/${encodeURIComponent(session.id)}`;
  if (isFinished(session)) return `${base}/view`;
  return section ? `${base}?section=${encodeURIComponent(section)}` : base;
}
