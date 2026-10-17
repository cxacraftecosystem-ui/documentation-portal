import { apiFetch, buildQuery } from "@/lib/api";

/** "1 record", "3 records". */
export function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

/**
 * The review feedback loop on the web — what the server says about a record's last review, the
 * uploader's own "Sent back to you" list, the reasons a reviewer picks from, and the review queue's
 * filters. The reason wording is the SERVER's (`GET /review/reasons`), so the web and the handset
 * cannot word one decision two ways; what lives here is the pure logic the unit tests pin.
 */

export type ReviewReason = { code: string; label: string; fixable: boolean; needsNote: boolean };

export type LatestReview = {
  status: string;
  notes?: string | null;
  reason?: string | null;
  reasonLabel?: string | null;
  reviewerName?: string | null;
  reviewedAt?: string | null;
};

export type LatestReviewAnswer = {
  status: string;
  canReview: boolean;
  needsAdminApproval: boolean;
  review: LatestReview | null;
};

export type ReturnedRecord = {
  recordType: string;
  id: string;
  label: string;
  place?: string | null;
  status: "NEEDS_REVISION" | "REJECTED";
  reviewNotes?: string | null;
  reason?: string | null;
  reasonLabel?: string | null;
  reviewerName?: string | null;
  reviewedAt?: string | null;
};

export type ReturnedList = {
  items: ReturnedRecord[];
  total: number;
  counts: { NEEDS_REVISION: number; REJECTED: number };
};

export type PreviousReview = {
  notes?: string | null;
  reviewerName?: string | null;
  reviewedAt?: string | null;
  status?: string | null;
  reason?: string | null;
  reasonLabel?: string | null;
};

/** The statuses whose last review the record's creator needs to read before editing. */
export const RETURNED_STATUSES = ["NEEDS_REVISION", "REJECTED"] as const;

export function isReturned(status: string | null | undefined): boolean {
  return status === "NEEDS_REVISION" || status === "REJECTED";
}

/** The banner's heading over a returned record. */
export function returnedHeading(status: string, reviewerName?: string | null): string {
  const who = reviewerName?.trim() || "A reviewer";
  return status === "REJECTED" ? `Not accepted by ${who}` : `Sent back by ${who}`;
}

/** The one line under the note: what saving does to this record now. */
export function returnedNextStep(status: string): string {
  return status === "REJECTED" ? "Fix it and save to reopen it for review." : "Fix it and save to send it back for review.";
}

/** "Sent back to you (3)" — the dashboard tile's title. */
export function sentBackTitle(count: number): string {
  return `Sent back to you (${count})`;
}

/** The tile's one-line note: how many are sent back and how many were not accepted. */
export function sentBackNote(sentBack: number, rejected: number): string {
  const parts: string[] = [];
  if (sentBack > 0) parts.push(`${plural(sentBack, "record")} to fix`);
  if (rejected > 0) parts.push(`${rejected} not accepted`);
  return parts.join(" · ");
}

/**
 * What the reviewer's "Reject" button really does with the picked reason: a FIXABLE reason sends
 * the record back for revision instead (C19). No reason picked means a plain rejection.
 */
export function refusalOutcome(reason: ReviewReason | null | undefined): "REJECTED" | "NEEDS_REVISION" {
  return reason?.fixable ? "NEEDS_REVISION" : "REJECTED";
}

/** The confirm button's label for the picked reason. */
export function refusalButtonLabel(reason: ReviewReason | null | undefined): string {
  return refusalOutcome(reason) === "NEEDS_REVISION" ? "Send for revision" : "Reject record";
}

/** The reasons offered on "Send for revision": only the fixable ones, and picking one is optional. */
export function reviseReasons(reasons: ReviewReason[]): ReviewReason[] {
  return reasons.filter((reason) => reason.fixable);
}

/** The body a decision posts. A reason travels only when one was picked; older servers ignore it. */
export function decisionBody(notes: string, reason: ReviewReason | null | undefined): { notes: string | null; reason?: string } {
  const trimmed = notes.trim();
  return reason ? { notes: trimmed || null, reason: reason.code } : { notes: trimmed || null };
}

/** Whether the reject panel may be submitted: a reason is picked, and "Other" carries words. */
export function refusalReady(reason: ReviewReason | null | undefined, note: string): boolean {
  if (!reason) return false;
  return !reason.needsNote || note.trim().length > 0;
}

/** The review queue's filters, as the URL carries them (Tasks links here with createdById). */
export type QueueFilters = {
  workshopId?: string;
  createdById?: string;
  recordType?: string;
  /** A task's artisans, comma-separated (B12). */
  artisanId?: string;
  order?: "oldest" | "newest";
};

export function queueFiltersFrom(params: URLSearchParams | null | undefined): QueueFilters {
  const read = (key: string) => {
    const value = params?.get(key)?.trim();
    return value ? value : undefined;
  };
  const order = read("order");
  return {
    workshopId: read("workshopId"),
    createdById: read("createdById"),
    recordType: read("recordType"),
    artisanId: read("artisanId"),
    order: order === "newest" ? "newest" : undefined
  };
}

export function queueQuery(page: number, pageSize: number, filters: QueueFilters): string {
  return buildQuery({
    page,
    pageSize,
    workshopId: filters.workshopId,
    createdById: filters.createdById,
    recordType: filters.recordType,
    artisanId: filters.artisanId,
    order: filters.order === "newest" ? "newest" : undefined
  });
}

/** The review queue link for a task's pending records (B12). */
export function reviewQueueHref(query: Record<string, string | undefined>): string {
  return `/review${buildQuery(query)}`;
}

export async function fetchLatestReview(recordType: string, recordId: string): Promise<LatestReviewAnswer> {
  return apiFetch<LatestReviewAnswer>(`/review/${encodeURIComponent(recordType)}/${encodeURIComponent(recordId)}/latest`);
}

export async function fetchReturnedRecords(): Promise<ReturnedList> {
  return apiFetch<ReturnedList>("/review/mine");
}

let reasonsCache: Promise<ReviewReason[]> | null = null;

export function fetchReviewReasons(): Promise<ReviewReason[]> {
  if (!reasonsCache) {
    reasonsCache = apiFetch<ReviewReason[]>("/review/reasons").catch((err) => {
      reasonsCache = null;
      throw err;
    });
  }
  return reasonsCache;
}

export type BulkResult = {
  results: { recordType: string; id: string; outcome: "APPROVED" | "FAILED"; detail?: unknown }[];
  approved: number;
  failed: number;
};

export async function bulkApprove(items: { recordType: string; id: string }[]): Promise<BulkResult> {
  return apiFetch<BulkResult>("/review/bulk-approve", {
    method: "POST",
    body: JSON.stringify({ items, notes: null })
  });
}

/** The receipt after a bulk approval. */
export function bulkReceipt(approved: number, failed: number): string {
  if (failed === 0) return `${plural(approved, "record")} approved.`;
  return `${approved} approved, ${failed} failed (still ticked).`;
}

/** The Data page's review-status choices (C21). "Approved" is what an export means. */
export const DEFAULT_DATA_STATUS = "APPROVED";

export const DATA_STATUS_OPTIONS = [
  { value: "APPROVED", label: "Approved" },
  { value: "PENDING", label: "Pending review" },
  { value: "ALL", label: "All statuses" }
];

/** The `status` query parameter for a choice; every status needs none. */
export function statusQuery(choice: string): string | undefined {
  return choice && choice !== "ALL" ? choice : undefined;
}

export function dataStatusLabel(choice: string): string {
  return DATA_STATUS_OPTIONS.find((option) => option.value === choice)?.label ?? "All statuses";
}
