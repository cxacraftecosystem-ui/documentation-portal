"use client";

import { useState } from "react";

import { DateRangePicker, formatDisplayDate } from "@/components/forms/DateTimeField";

function startOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0)).toISOString();
}

function endOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999)).toISOString();
}

/**
 * A stored instant -> the local-midnight Date standing for the SAME CALENDAR DAY it was stored as.
 *
 * Everything else in this file (`startOfUtcDay`, `endOfUtcDay`) and in the shared picker reads and
 * writes LOCAL calendar components, while the API stores a UTC instant — start of day for
 * `startDate`, 23:59:59.999 for `endDate`. Handing `new Date(value)` straight back mixed the two: in
 * any timezone east of UTC an `endDate` of `...T23:59:59.999Z` lands on the NEXT local day, so simply
 * opening a workshop and pressing Update — without touching the dates — re-saved it a day later, and
 * each further save drifted it again (observed: 12 Jul -> 13 Jul -> 14 Jul). West of UTC the same
 * mismatch walks `startDate` backwards. Reading the UTC components keeps the round trip stable.
 */
function parseDate(value?: string | null) {
  if (!value) return undefined;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return undefined;
  return new Date(parsed.getUTCFullYear(), parsed.getUTCMonth(), parsed.getUTCDate());
}

/**
 * Workshop duration: Start date / End date, backed by the shared floating range picker.
 *
 * This component is now only the workshop-specific part — the UTC round trip above and the three
 * hidden inputs the endpoint expects. Everything visible (the typed `dd/mm/yyyy` inputs, the themed
 * calendar, and the panel that FLOATS over the form instead of pushing it down) is
 * `components/forms/DateTimeField`, which every other date field in the app shares.
 *
 * Submits the SAME hidden fields as before: `startDate` (start of start day, UTC), `endDate` (end of
 * end day, UTC), `date` (= startDate).
 */
export function DateRangeField({
  start,
  end
}: {
  start?: string | null;
  end?: string | null;
}) {
  const [today] = useState(() => new Date());
  const [range, setRange] = useState<{ from?: Date; to?: Date }>(() => ({
    from: parseDate(start) ?? today,
    to: parseDate(end) ?? parseDate(start) ?? today
  }));

  const from = range.from ?? today;
  const to = range.to ?? from;

  return (
    <div className="grid gap-2">
      <span className="field-label">Workshop duration</span>
      <input type="hidden" name="startDate" value={startOfUtcDay(from)} />
      <input type="hidden" name="endDate" value={endOfUtcDay(to)} />
      <input type="hidden" name="date" value={startOfUtcDay(from)} />
      <DateRangePicker from={range.from} to={range.to} onChange={setRange} />
      {/* Not a restatement of the two inputs: between the first and second click of a range the End
          field is legitimately empty, and this is the line that shows the end date the form would
          actually submit for it — the start day, per the fallback above. */}
      <p className="text-xs text-ink-500">
        {formatDisplayDate(from)} to {formatDisplayDate(to)}
      </p>
    </div>
  );
}
