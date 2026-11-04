"use client";

import { useState } from "react";

import { DateRangePicker, formatDisplayDate } from "@/components/forms/DateTimeField";

function startOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0)).toISOString();
}

function endOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999)).toISOString();
}

function parseDate(value?: string | null) {
  if (!value) return undefined;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return undefined;
  return new Date(parsed.getUTCFullYear(), parsed.getUTCMonth(), parsed.getUTCDate());
}

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
      <DateRangePicker from={range.from} to={range.to} onChange={setRange} startFieldKey="startDate" endFieldKey="endDate" />
      <p className="text-xs text-ink-500">
        {formatDisplayDate(from)} to {formatDisplayDate(to)}
      </p>
    </div>
  );
}
