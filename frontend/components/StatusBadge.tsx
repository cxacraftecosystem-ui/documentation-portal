import type { RecordStatus } from "@/lib/types";

const tone: Record<string, string> = {
  DRAFT: "border-line-200 bg-surface-50 text-ink-500",
  PENDING: "border-amber-500/30 bg-amber-100 text-amber-800",
  APPROVED: "border-success-600/25 bg-success-100 text-success-600",
  REJECTED: "border-error-600/25 bg-error-100 text-red-800",
  NEEDS_REVISION: "border-purple-300 bg-purple-50 text-purple-700"
};

const label: Record<string, string> = {
  DRAFT: "Draft",
  PENDING: "Pending",
  APPROVED: "Approved",
  REJECTED: "Rejected",
  NEEDS_REVISION: "Needs revision"
};

/** Fallback for statuses without a curated label: SOME_STATUS -> "Some status". */
function humanize(status: string): string {
  const words = status.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * The reader's word for a status — the badge's own label, so a Status dropdown cannot print
 * `NEEDS_REVISION` while every badge beside it says "Needs revision" (UX audit DC-16).
 */
export function statusLabel(status: RecordStatus | string): string {
  return label[status] ?? humanize(String(status));
}

export function StatusBadge({ status }: { status: RecordStatus | string }) {
  const className = tone[status] ?? tone.DRAFT;
  return (
    <span className={`rounded-full border px-2.5 py-1 text-xs font-medium ${className}`}>
      {statusLabel(status)}
    </span>
  );
}
