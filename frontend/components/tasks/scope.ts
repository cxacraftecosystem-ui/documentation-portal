import type { TaskArtisanRef, TaskSectionRef, TaskStatus } from "@/components/tasks/types";

export const RECORD_TYPE_ORDER = ["artisan", "product", "process", "tool", "questionnaire", "media"] as const;

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
  SUBMITTED: "Under review",
  DONE: "Done",
  CANCELLED: "Cancelled"
};

export function orderRecordTypes(values: string[]): string[] {
  const wanted = new Set(values.map((value) => value.trim().toLowerCase()));
  return RECORD_TYPE_ORDER.filter((kind) => wanted.has(kind));
}

export function recordTypeLabel(kind: string, plural = true): string {
  return RECORD_TYPE_LABELS[kind]?.[plural ? 1 : 0] ?? kind;
}

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

function artisanPhrase(artisans: Pick<TaskArtisanRef, "name">[], workshopTitle?: string | null): string {
  if (artisans.length === 1) return `for ${artisans[0].name}`;
  if (artisans.length === 2) return `for ${artisans[0].name} and ${artisans[1].name}`;
  if (artisans.length) return `for ${artisans.length} named artisans`;
  return workshopTitle ? `for every artisan at ${workshopTitle}` : "for every artisan in scope";
}

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

export function assignmentPreview(assigneeCount: number, scope: ScopeInput): string {
  const who = plural(assigneeCount, "person", "people");
  const rows = plural(assigneeCount, "task", "tasks");
  return `${who} × ${workPhrase(scope)} = ${rows}`;
}

export type ProgressGap = {
  tone: "unknown" | "idle" | "match" | "ahead" | "behind";
  delta: number;
  label: string;
};

export function progressGap(reported: number, derived: number | null | undefined): ProgressGap {
  if (derived === null || derived === undefined) {
    return { tone: "unknown", delta: 0, label: "Count unavailable" };
  }
  if (reported === 0 && derived === 0) {
    return { tone: "idle", delta: 0, label: "Nothing reported or recorded yet" };
  }
  const delta = reported - derived;
  if (delta > 0) {
    return { tone: "behind", delta, label: `${delta} more reported than recorded` };
  }
  if (delta < 0) {
    return { tone: "ahead", delta, label: `${-delta} more recorded than reported` };
  }
  return { tone: "match", delta: 0, label: "Reported matches recorded" };
}
