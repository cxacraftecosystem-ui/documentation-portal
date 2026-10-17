/**
 * The pure half of the "Records not filed under a workshop" card (`WorkshopMappingPanel.tsx`).
 *
 * Since 2026-10-20 the server files the CLEAR-CUT rows itself, once a night — a record whose parent
 * record already names a workshop (`backend/app/services/workshop_auto_map.py`). So the card leads with
 * what is left for a person: the rows the artisans or the dates only suggest, and the rows nothing
 * settles. Every count here falls back to the older payload's fields, so the card still reads right
 * against a server that has not shipped the nightly job.
 */

export const CLEAR_CUT_RUNG = "PARENT";

export type MappingRow = {
  id: string;
  title: string;
  workshopId: string | null;
  workshopTitle: string | null;
  rung: string | null;
  rungCopy: string | null;
  reason: string | null;
  reasonCopy: string | null;
  candidateTitles: string[];
  clearCut?: boolean;
};

export type MappingBucket = {
  bucket: string;
  singular: string;
  plural: string;
  unassigned: number;
  resolved: number;
  unresolved: number;
  clearCut?: number;
  needsReview?: number;
  byRung: Array<{ rung: string; copy: string; count: number }>;
  byReason: Array<{ reason: string; copy: string; count: number }>;
  byWorkshop: Array<{ workshopId: string; title: string; count: number }>;
  rows: MappingRow[];
  rowsTruncated: boolean;
  applied: number | null;
};

export type AutoMapStatus = {
  enabled: boolean;
  runsAt: string;
  timezone: string;
  lastRunAt: string | null;
  lastFiled: number;
};

export type WorkshopMappingPlan = {
  workshops: Array<{ id: string; title: string; start: string; end: string }>;
  buckets: MappingBucket[];
  totals: {
    unassigned: number;
    resolved: number;
    unresolved: number;
    clearCut?: number;
    needsReview?: number;
    applied: number | null;
  };
  autoMap?: AutoMapStatus;
};

/** Rows the nightly job files on its own, for one bucket. */
export function clearCutCount(bucket: MappingBucket): number {
  if (typeof bucket.clearCut === "number") return bucket.clearCut;
  return bucket.byRung.find((entry) => entry.rung === CLEAR_CUT_RUNG)?.count ?? 0;
}

/** What still needs a look in one bucket: suggestions plus rows nothing settles. */
export function needsReviewCount(bucket: MappingBucket): number {
  return typeof bucket.needsReview === "number" ? bucket.needsReview : bucket.unassigned - clearCutCount(bucket);
}

/** Resolved rows an admin confirms — the artisans or the dates suggest them. */
export function suggestedCount(bucket: MappingBucket): number {
  return Math.max(0, bucket.resolved - clearCutCount(bucket));
}

export function totalNeedsReview(plan: WorkshopMappingPlan): number {
  if (typeof plan.totals.needsReview === "number") return plan.totals.needsReview;
  return plan.buckets.reduce((sum, bucket) => sum + needsReviewCount(bucket), 0);
}

export function totalClearCut(plan: WorkshopMappingPlan): number {
  if (typeof plan.totals.clearCut === "number") return plan.totals.clearCut;
  return plan.buckets.reduce((sum, bucket) => sum + clearCutCount(bucket), 0);
}

/** The buckets worth a line on the card: only those with something left for a person. */
export function bucketsNeedingReview(plan: WorkshopMappingPlan): MappingBucket[] {
  return plan.buckets.filter((bucket) => needsReviewCount(bucket) > 0);
}

/** The "why" lines a bucket prints — the clear-cut rung is the job's, not the admin's. */
export function reviewRungs(bucket: MappingBucket): MappingBucket["byRung"] {
  return bucket.byRung.filter((entry) => entry.rung !== CLEAR_CUT_RUNG);
}

export function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : pluralForm}`;
}

/** One line under the nightly switch. */
export function autoMapLine(status: AutoMapStatus | undefined, pending: number): string {
  if (!status) return "";
  if (!status.enabled) return pending ? `${plural(pending, "record")} waiting to be filed.` : "Off.";
  const tonight = pending ? `${plural(pending, "record")} tonight.` : "";
  const last = status.lastRunAt ? `Last run filed ${status.lastFiled}.` : "";
  return [`Every night at ${status.runsAt}.`, tonight, last].filter(Boolean).join(" ");
}
