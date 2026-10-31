/**
 * Recording chips (owner, 31 Oct 2026): what a take answers, on which form, who answered, when, where.
 *
 * The server sends `chips` on every recording, session and inbox row (`backend/app/questionnaire/
 * chips.py`); `displayChips` turns it into the pills every screen draws, with exactly the labels,
 * order and filters of `shared/questionnaire-chips.json` — the vectors Android's twin
 * (`questionnaire/data/RecordingChips.kt`) is held to as well.
 */

import { languageLabel } from "@/lib/questionnaire/languages";
import { qs } from "@/lib/questionnaire/qs";

export type ChipPerson = { id: string; name: string; role: "ANSWERED" | "PRESENT" | string };

export type ChipTarget =
  | { kind: "SECTION"; sectionId: string | null; sectionCode: string; sectionTitle: string }
  | {
      kind: "QUESTION";
      questionLineageId: string;
      displayCode: string;
      prompt: string;
      sectionId: string | null;
      sectionCode: string;
      sectionTitle: string;
    };

export type RecordingChips = {
  session: { id: string; title: string } | null;
  questionnaire: { id: string; title: string; kind: string | null } | null;
  targets: ChipTarget[];
  artisans: ChipPerson[];
  respondents: ChipPerson[];
  sessionDate: string | null;
  sessionDateEstimated: boolean;
  capturedOn: string | null;
  captureEstimated: boolean;
  workshop: { id: string; name: string; type: "FIELD" | "DESIGN" | string } | null;
  languageCode: string | null;
  durationMs: number | null;
  researcher: { id: string; name: string } | null;
  source: string;
};

/** The filter a chip applies: a filter key, or "date" (sets dateFrom and dateTo to that day). */
export type ChipFilter = { key: string; value: string };

export type ChipKind =
  | "target"
  | "moreTargets"
  | "questionnaire"
  | "artisan"
  | "guest"
  | "morePeople"
  | "date"
  | "captured"
  | "workshop"
  | "language"
  | "duration";

export type DisplayChip = { kind: ChipKind; label: string; filter: ChipFilter | null; title?: string };

export const MAX_TARGETS = 2;
export const MAX_PEOPLE = 2;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** `2026-07-07` → `7 Jul 2026` (read as a calendar day, never through a time zone). */
export function chipDay(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split("-").map((part) => Number(part));
  if (!y || !m || !d) return iso;
  return `${d} ${MONTHS[m - 1]} ${y}`;
}

/** `m:ss` under an hour, `h:mm:ss` from an hour. */
export function chipDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const two = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${m}:${two(s)}`;
}

function targetChip(t: ChipTarget): DisplayChip {
  if (t.kind === "QUESTION") {
    const label = t.sectionTitle ? `${t.displayCode} · ${t.sectionTitle}` : t.displayCode;
    return { kind: "target", label, filter: { key: "questionLineageId", value: t.questionLineageId } };
  }
  const label = t.sectionTitle ? `${t.sectionCode} · ${t.sectionTitle}` : qs("chipSection", { code: t.sectionCode });
  return { kind: "target", label, filter: t.sectionId ? { key: "sectionId", value: t.sectionId } : null };
}

function languageName(code: string): string {
  const label = languageLabel(code);
  return label && label !== code ? label : code.toUpperCase();
}

/** The pills for one recording (or session), in the shared order. */
export function displayChips(
  chips: RecordingChips | null | undefined,
  options: { maxTargets?: number; maxPeople?: number; targets?: boolean; people?: boolean } = {}
): DisplayChip[] {
  if (!chips) return [];
  const maxTargets = options.maxTargets ?? MAX_TARGETS;
  const maxPeople = options.maxPeople ?? MAX_PEOPLE;
  const out: DisplayChip[] = [];

  const targets = (chips.targets ?? []).map(targetChip);
  if (options.targets === false) {
    // A session's chips: nothing about a take.
  } else if (targets.length === 0) {
    out.push({ kind: "target", label: qs("chipWholeSession"), filter: null });
  } else {
    out.push(...targets.slice(0, maxTargets));
    if (targets.length > maxTargets) {
      const rest = targets.slice(maxTargets);
      out.push({ kind: "moreTargets", label: qs("chipMore", { count: rest.length }), filter: null, title: rest.map((c) => c.label).join(", ") });
    }
  }

  if (chips.questionnaire) {
    out.push({ kind: "questionnaire", label: chips.questionnaire.title, filter: { key: "questionnaireId", value: chips.questionnaire.id } });
  }

  const people: DisplayChip[] = [
    ...(chips.artisans ?? []).map((a): DisplayChip => ({
      kind: "artisan",
      label: a.role === "PRESENT" ? qs("chipPresent", { name: a.name }) : a.name,
      filter: { key: "artisanId", value: a.id }
    })),
    ...(chips.respondents ?? []).map((g): DisplayChip => ({ kind: "guest", label: qs("chipGuest", { name: g.name }), filter: null }))
  ];
  if (options.people === false) people.length = 0;
  out.push(...people.slice(0, maxPeople));
  if (people.length > maxPeople) {
    const rest = people.slice(maxPeople);
    out.push({ kind: "morePeople", label: qs("chipMore", { count: rest.length }), filter: null, title: rest.map((c) => c.label).join(", ") });
  }

  if (chips.sessionDate) {
    const day = chipDay(chips.sessionDate);
    const estimated = chips.sessionDateEstimated || chips.captureEstimated;
    out.push({ kind: "date", label: estimated ? qs("chipEstimated", { date: day }) : day, filter: { key: "date", value: chips.sessionDate } });
  }
  if (chips.capturedOn) {
    out.push({ kind: "captured", label: qs("chipRecordedOn", { date: chipDay(chips.capturedOn) }), filter: { key: "date", value: chips.capturedOn } });
  }
  if (chips.workshop) {
    out.push({
      kind: "workshop",
      label: chips.workshop.name,
      filter: { key: chips.workshop.type === "DESIGN" ? "designWorkshopId" : "workshopId", value: chips.workshop.id }
    });
  }
  if (chips.languageCode) {
    out.push({ kind: "language", label: languageName(chips.languageCode), filter: { key: "languageCode", value: chips.languageCode } });
  }
  if (options.targets !== false && chips.durationMs !== null && chips.durationMs !== undefined) {
    out.push({ kind: "duration", label: chipDuration(chips.durationMs), filter: null });
  }
  return out;
}

/** A session's chips: the form, the people, the day, the workshop and the language. */
export function sessionChips(chips: RecordingChips | null | undefined, options: { maxPeople?: number; people?: boolean } = {}): DisplayChip[] {
  return displayChips(chips, { ...options, targets: false });
}
