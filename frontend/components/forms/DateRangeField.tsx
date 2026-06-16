"use client";

import { useState } from "react";
import type { DateRange } from "react-day-picker";

import { Calendar } from "@/components/ui/calendar";
import { Field } from "@/components/FormControls";

function startOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0)).toISOString();
}

function endOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999)).toISOString();
}

function parseDate(value?: string | null) {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

export function DateRangeField({
  start,
  end
}: {
  start?: string | null;
  end?: string | null;
}) {
  const today = new Date();
  const [range, setRange] = useState<DateRange | undefined>({
    from: parseDate(start) ?? today,
    to: parseDate(end) ?? parseDate(start) ?? today
  });
  const from = range?.from ?? today;
  const to = range?.to ?? from;

  return (
    <Field label="Workshop duration">
      <input type="hidden" name="startDate" value={startOfUtcDay(from)} />
      <input type="hidden" name="endDate" value={endOfUtcDay(to)} />
      <input type="hidden" name="date" value={startOfUtcDay(from)} />
      <div className="rounded-lg border border-[#e6dfd8] bg-field-50 p-3">
        <Calendar mode="range" selected={range} onSelect={setRange} />
        <div className="mt-2 rounded-md bg-field-100 px-3 py-2 text-sm text-ink-muted">
          {from.toLocaleDateString("en-IN")} to {to.toLocaleDateString("en-IN")}
        </div>
      </div>
    </Field>
  );
}
