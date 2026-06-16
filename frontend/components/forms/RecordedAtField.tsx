"use client";

import { formatDateTime } from "@/lib/format";

/**
 * Read-only record-time panel. The timestamp is captured automatically at save time and cannot be
 * edited: new records inherit the database `now()` default (no `recordedAt` field is submitted),
 * and existing records keep their original timestamp. Only the interpretation timezone is retained
 * as a hidden value.
 */
export function RecordedAtField({
  value,
  timezone = "Asia/Kolkata"
}: {
  value?: string | Date | null;
  timezone?: string | null;
}) {
  const existing = value ? formatDateTime(typeof value === "string" ? value : value.toISOString()) : null;

  return (
    <section className="grid gap-2 rounded-md border border-[#e6dfd8] bg-field-100 p-3">
      <h3 className="font-serif text-lg text-ink">Record time</h3>
      <p className="text-sm text-ink-muted">
        Captured automatically and stored in UTC (interpreted in IST). This field is not editable.
      </p>
      <p className="text-sm font-medium text-ink">
        {existing ? `Recorded at ${existing}` : "Will be set to the moment you save this record."}
      </p>
      <input type="hidden" name="recordedTimezone" value={timezone ?? "Asia/Kolkata"} />
    </section>
  );
}
