import type { TaskArtisanRef, TaskSectionRef, TaskStatus } from "@/components/tasks/types";

/**
 * Plain English for a task scope.
 *
 * An assignment is five orthogonal dimensions — workshop x record types x artisans x questionnaire
 * sections x target count — and an admin cannot sanity-check a combination of five dropdowns by
 * looking at the dropdowns. So the builder says back, in a sentence, exactly what is about to be
 * created; `scopeTitle` below is a faithful port of the backend's `scope_title()` so the title
 * previewed here is the title the server will actually store when none is typed.
 */

/** The canonical order titles read in — matches `RECORD_TYPE_ORDER` on the backend. */
export const RECORD_TYPE_ORDER = ["artisan", "product", "process", "tool", "questionnaire", "media"] as const;

/** kind -> [singular, plural], matching `RECORD_TYPE_LABELS` on the backend. */
export const RECORD_TYPE_LABELS: Record<string, [string, string]> = {
  artisan: ["artisan", "artisans"],
  product: ["product", "products"],
  process: ["process", "processes"],
  tool: ["tool", "tools"],
  questionnaire: ["questionnaire interview", "questionnaire interviews"],
  media: ["media file", "media files"]
};

export const STATUS_LABEL: Record<TaskStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  DONE: "Done",
  CANCELLED: "Cancelled"
};

/** Sort a set of record-type values into the canonical order, dropping anything unknown. */
export function orderRecordTypes(values: string[]): string[] {
  const wanted = new Set(values.map((value) => value.trim().toLowerCase()));
  return RECORD_TYPE_ORDER.filter((kind) => wanted.has(kind));
}

export function recordTypeLabel(kind: string, plural = true): string {
  return RECORD_TYPE_LABELS[kind]?.[plural ? 1 : 0] ?? kind;
}

/** "a", "a and b", "a, b and c" — the backend's `_and_list`. */
export function andList(items: string[]): string {
  if (items.length === 0) return "";
  if (items.length === 1) return items[0];
  return `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
}

export type ScopeInput = {
  recordTypes: string[];
  sections: Pick<TaskSectionRef, "code">[];
  artisans: Pick<TaskArtisanRef, "name">[];
  targetCount?: number | null;
  workshopTitle?: string | null;
};

/**
 * The default title the server derives when the admin does not type one — ported line for line from
 * `scope_title()` so the preview never promises a title the backend then writes differently.
 */
export function scopeTitle({ recordTypes, sections, artisans, targetCount, workshopTitle }: ScopeInput): string {
  const parts: string[] = [];
  const ordered = orderRecordTypes(recordTypes);
  if (ordered.length) {
    const plural = targetCount !== 1;
    const labels = ordered.map((kind) => recordTypeLabel(kind, plural));
    const count = targetCount ? `${targetCount} ` : "";
    parts.push(`Record ${count}${andList(labels)}`);
  }
  if (sections.length) {
    const codes = sections.map((section) => section.code).join(", ");
    const noun = sections.length === 1 ? "section" : "sections";
    // Lower-cased when it trails a record-type half, so the whole reads as one instruction.
    const head = parts.length ? "questionnaire" : "Questionnaire";
    parts.push(`${head} ${noun} ${codes}`);
  }

  let title = parts.join(" + ") || "Field task";
  const names = artisans.map((artisan) => artisan.name);
  if (names.length === 1) title += ` for ${names[0]}`;
  else if (names.length === 2) title += ` for ${names[0]} and ${names[1]}`;
  else if (names.length) title += ` for ${names.length} artisans`;
  if (workshopTitle && title.length + workshopTitle.length + 3 <= 300) title += ` (${workshopTitle})`;
  return title.slice(0, 300);
}

/**
 * The denominator `derivedCount` is read against — the backend's `_derived_target()`. Null means the
 * scope has no honest denominator (record types with no target count = "as many as apply").
 */
export function derivedTargetFor({
  recordTypes,
  sectionCount,
  artisanCount,
  targetCount
}: {
  recordTypes: string[];
  sectionCount: number;
  artisanCount: number;
  targetCount?: number | null;
}): number | null {
  let total = 0;
  if (recordTypes.length) {
    if (!targetCount) return null;
    total += targetCount;
  }
  if (sectionCount) total += sectionCount * Math.max(1, artisanCount);
  return total || null;
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** "for Gitaben Patel", "for 5 named artisans", "for every artisan at Test WS". */
function artisanPhrase(artisans: Pick<TaskArtisanRef, "name">[], workshopTitle?: string | null): string {
  if (artisans.length === 1) return `for ${artisans[0].name}`;
  if (artisans.length === 2) return `for ${artisans[0].name} and ${artisans[1].name}`;
  if (artisans.length) return `for ${artisans.length} named artisans`;
  return workshopTitle ? `for every artisan at ${workshopTitle}` : "for every artisan in scope";
}

/** "record products and tools", "answer questionnaire sections C and D", or both joined. */
export function workPhrase({ recordTypes, sections, artisans, targetCount, workshopTitle }: ScopeInput): string {
  const halves: string[] = [];
  const ordered = orderRecordTypes(recordTypes);
  if (ordered.length) {
    const labels = ordered.map((kind) => recordTypeLabel(kind, targetCount !== 1));
    halves.push(`record ${targetCount ? `${targetCount} ` : ""}${andList(labels)}`);
  }
  if (sections.length) {
    const codes = andList(sections.map((section) => section.code));
    halves.push(`answer questionnaire ${sections.length === 1 ? "section" : "sections"} ${codes}`);
  }
  if (!halves.length) return "do nothing yet";
  return `${halves.join(" and ")} ${artisanPhrase(artisans, workshopTitle)}`;
}

/**
 * The headline the builder shows before anything is written:
 * "3 people x record products and tools for 5 artisans = 3 tasks".
 */
export function assignmentPreview(assigneeCount: number, scope: ScopeInput): string {
  const who = plural(assigneeCount, "person", "people");
  const rows = plural(assigneeCount, "task", "tasks");
  return `${who} × ${workPhrase(scope)} = ${rows}`;
}

/**
 * How the assignee's own number compares with what the repository can see. This gap is the entire
 * point of the accountability view, so it is computed once here and rendered identically everywhere.
 */
export type ProgressGap = {
  tone: "unknown" | "idle" | "match" | "ahead" | "behind";
  /** reported - derived, when both are known. */
  delta: number;
  label: string;
};

export function progressGap(reported: number, derived: number | null | undefined): ProgressGap {
  if (derived === null || derived === undefined) {
    return { tone: "unknown", delta: 0, label: "Repository count unavailable" };
  }
  // Two zeroes agree, but agreeing about nothing is not an achievement: a green "matches" tick on an
  // untouched task would read as reassurance on exactly the row that deserves a chase.
  if (reported === 0 && derived === 0) {
    return { tone: "idle", delta: 0, label: "Nothing reported or recorded yet" };
  }
  const delta = reported - derived;
  if (delta > 0) {
    return { tone: "behind", delta, label: `${delta} more reported than the repository can find` };
  }
  if (delta < 0) {
    return { tone: "ahead", delta, label: `${-delta} more in the repository than reported` };
  }
  return { tone: "match", delta: 0, label: "Reported figure matches the repository" };
}
