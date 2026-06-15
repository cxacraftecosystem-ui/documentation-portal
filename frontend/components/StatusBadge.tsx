import type { RecordStatus } from "@/lib/types";

const tone: Record<RecordStatus, string> = {
  DRAFT: "border-[#e6dfd8] bg-field-100 text-ink-muted",
  PENDING: "border-amber-200 bg-amber-50 text-amber-800",
  APPROVED: "border-emerald-200 bg-emerald-50 text-emerald-800",
  REJECTED: "border-red-200 bg-red-50 text-red-700"
};

export function StatusBadge({ status }: { status: RecordStatus | string }) {
  const className = tone[status as RecordStatus] ?? tone.DRAFT;
  return <span className={`rounded-full border px-2.5 py-1 text-xs font-medium ${className}`}>{status}</span>;
}
