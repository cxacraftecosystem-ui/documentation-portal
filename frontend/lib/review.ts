import { apiFetch, buildQuery } from "@/lib/api";

export function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

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

export const RETURNED_STATUSES = ["NEEDS_REVISION", "REJECTED"] as const;

export function isReturned(status: string | null | undefined): boolean {
  return status === "NEEDS_REVISION" || status === "REJECTED";
}

export function returnedHeading(status: string, reviewerName?: string | null): string {
  const who = reviewerName?.trim() || "A reviewer";
  return status === "REJECTED" ? `Not accepted by ${who}` : `Sent back by ${who}`;
}

export function returnedNextStep(status: string): string {
  return status === "REJECTED" ? "Fix it and save to reopen it for review." : "Fix it and save to send it back for review.";
}

export function sentBackTitle(count: number): string {
  return `Sent back to you (${count})`;
}

export function sentBackNote(sentBack: number, rejected: number): string {
  const parts: string[] = [];
  if (sentBack > 0) parts.push(`${plural(sentBack, "record")} to fix`);
  if (rejected > 0) parts.push(`${rejected} not accepted`);
  return parts.join(" · ");
}

export function refusalOutcome(reason: ReviewReason | null | undefined): "REJECTED" | "NEEDS_REVISION" {
  return reason?.fixable ? "NEEDS_REVISION" : "REJECTED";
}

export function refusalButtonLabel(reason: ReviewReason | null | undefined): string {
  return refusalOutcome(reason) === "NEEDS_REVISION" ? "Send for revision" : "Reject record";
}

export function fileRefusalLabel(reason: ReviewReason | null | undefined): string {
  return refusalOutcome(reason) === "NEEDS_REVISION" ? "Send for revision" : "Reject file";
}

export function reviseReasons(reasons: ReviewReason[]): ReviewReason[] {
  return reasons.filter((reason) => reason.fixable);
}

export function decisionBody(notes: string, reason: ReviewReason | null | undefined): { notes: string | null; reason?: string } {
  const trimmed = notes.trim();
  return reason ? { notes: trimmed || null, reason: reason.code } : { notes: trimmed || null };
}

export function refusalReady(reason: ReviewReason | null | undefined, note: string): boolean {
  if (!reason) return false;
  return !reason.needsNote || note.trim().length > 0;
}

export type QueueFilters = {
  workshopId?: string;
  createdById?: string;
  recordType?: string;
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
  mediaApproved?: number;
};

export async function bulkApprove(items: { recordType: string; id: string }[]): Promise<BulkResult> {
  return apiFetch<BulkResult>("/review/bulk-approve", {
    method: "POST",
    body: JSON.stringify({ items, notes: null })
  });
}

export function bulkReceipt(approved: number, failed: number, files = 0): string {
  const withFiles = files > 0 ? `, with ${plural(files, "file")}` : "";
  if (failed === 0) return `${plural(approved, "record")} approved${withFiles}.`;
  return `${approved} approved${withFiles.replace(",", "")}, ${failed} failed (still ticked).`;
}

export function alsoApprovesLine(files: number | null | undefined): string | null {
  return files && files > 0 ? `Also approves ${plural(files, "file")}.` : null;
}

export function approvedReceipt(files: number | null | undefined): string {
  return files && files > 0 ? `Approved, with ${plural(files, "file")}.` : "Approved.";
}

export type RecordMediaReview = {
  approvesWith: number;
  rejectable: string[];
};

export async function fetchRecordMediaReview(recordType: string, recordId: string): Promise<RecordMediaReview> {
  return apiFetch<RecordMediaReview>(`/review/${encodeURIComponent(recordType)}/${encodeURIComponent(recordId)}/media`);
}

export async function rejectMedia(mediaId: string, notes: string, reason: ReviewReason | null | undefined): Promise<{ status?: string }> {
  return apiFetch<{ status?: string }>(`/review/media/${encodeURIComponent(mediaId)}/reject`, {
    method: "POST",
    body: JSON.stringify(decisionBody(notes, reason))
  });
}

export const DEFAULT_DATA_STATUS = "APPROVED";

export const DATA_STATUS_OPTIONS = [
  { value: "APPROVED", label: "Approved" },
  { value: "PENDING", label: "Pending review" },
  { value: "ALL", label: "All statuses" }
];

export function statusQuery(choice: string): string | undefined {
  return choice && choice !== "ALL" ? choice : undefined;
}

export function dataStatusLabel(choice: string): string {
  return DATA_STATUS_OPTIONS.find((option) => option.value === choice)?.label ?? "All statuses";
}
