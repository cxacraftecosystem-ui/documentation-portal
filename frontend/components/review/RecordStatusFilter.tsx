"use client";

import { SearchableSelect } from "@/components/ui/SearchableSelect";

export const RECORD_STATUS_FILTER_OPTIONS = [
  { value: "ALL", label: "All statuses" },
  { value: "PENDING", label: "Pending" },
  { value: "NEEDS_REVISION", label: "Needs revision" },
  { value: "REJECTED", label: "Rejected" },
  { value: "APPROVED", label: "Approved" }
];

export function statusFilterParam(choice: string): string | undefined {
  return choice && choice !== "ALL" ? choice : undefined;
}

export function RecordStatusFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <div className="grid max-w-xs gap-1">
      <span className="field-label">Status</span>
      <SearchableSelect
        ariaLabel="Status"
        value={value}
        onChange={onChange}
        options={RECORD_STATUS_FILTER_OPTIONS}
        advanceOnSelect={false}
      />
    </div>
  );
}
