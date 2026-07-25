"use client";

import { useState } from "react";
import type { DateRange } from "react-day-picker";

import { Calendar } from "@/components/ui/calendar";

function startOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0)).toISOString();
}

function endOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999)).toISOString();
}

/**
 * A stored instant -> the local-midnight Date standing for the SAME CALENDAR DAY it was stored as.
 *
 * Everything else in this file (`formatDisplay`, `parseTyped`, `startOfUtcDay`, `endOfUtcDay`) reads
 * and writes LOCAL calendar components, while the API stores a UTC instant — start of day for
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

function formatDisplay(date: Date) {
  const dd = String(date.getDate()).padStart(2, "0");
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  return `${dd}/${mm}/${date.getFullYear()}`;
}

/** Accepts dd/mm/yyyy (also - or . separators) and yyyy-mm-dd; rejects impossible dates. */
function parseTyped(text: string): Date | undefined {
  const trimmed = text.trim();
  let year: number;
  let month: number;
  let day: number;
  let match = /^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$/.exec(trimmed);
  if (match) {
    day = Number(match[1]);
    month = Number(match[2]);
    year = Number(match[3]);
  } else {
    match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(trimmed);
    if (!match) return undefined;
    year = Number(match[1]);
    month = Number(match[2]);
    day = Number(match[3]);
  }
  const date = new Date(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : undefined;
}

/**
 * Start date / End date fields backed by an inline themed range calendar. Typing a date
 * (dd/mm/yyyy or yyyy-mm-dd) works too. Submits the SAME hidden fields as before:
 * `startDate` (start of start day, UTC), `endDate` (end of end day, UTC), `date` (= startDate).
 */
export function DateRangeField({
  start,
  end
}: {
  start?: string | null;
  end?: string | null;
}) {
  const today = new Date();
  const initialFrom = parseDate(start) ?? today;
  const initialTo = parseDate(end) ?? parseDate(start) ?? today;
  const [range, setRange] = useState<DateRange | undefined>({ from: initialFrom, to: initialTo });
  const from = range?.from ?? today;
  const to = range?.to ?? from;
  const [fromText, setFromText] = useState(() => formatDisplay(initialFrom));
  const [toText, setToText] = useState(() => formatDisplay(initialTo));
  const [open, setOpen] = useState(false);
  const [month, setMonth] = useState<Date>(initialFrom);

  function handleFromChange(text: string) {
    setFromText(text);
    const parsed = parseTyped(text);
    if (!parsed) return;
    const nextTo = range?.to && range.to.getTime() >= parsed.getTime() ? range.to : parsed;
    setRange({ from: parsed, to: nextTo });
    setToText(formatDisplay(nextTo));
    setMonth(parsed);
  }

  function handleToChange(text: string) {
    setToText(text);
    const parsed = parseTyped(text);
    if (!parsed) return;
    const nextFrom = range?.from && range.from.getTime() <= parsed.getTime() ? range.from : parsed;
    setRange({ from: nextFrom, to: parsed });
    setFromText(formatDisplay(nextFrom));
    setMonth(parsed);
  }

  function handleSelect(next: DateRange | undefined) {
    setRange(next);
    const nextFrom = next?.from;
    const nextTo = next?.to ?? next?.from;
    if (nextFrom) setFromText(formatDisplay(nextFrom));
    if (nextTo) setToText(formatDisplay(nextTo));
  }

  return (
    <div className="grid gap-2">
      <span className="field-label">Workshop duration</span>
      <input type="hidden" name="startDate" value={startOfUtcDay(from)} />
      <input type="hidden" name="endDate" value={endOfUtcDay(to)} />
      <input type="hidden" name="date" value={startOfUtcDay(from)} />
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="grid gap-1">
          <span className="field-label">Start date</span>
          <input
            type="text"
            inputMode="numeric"
            autoComplete="off"
            placeholder="dd/mm/yyyy"
            value={fromText}
            onFocus={() => {
              setOpen(true);
              setMonth(from);
            }}
            onClick={() => setOpen(true)}
            onChange={(event) => handleFromChange(event.target.value)}
            onBlur={() => setFromText(formatDisplay(from))}
            className="field-input"
          />
        </label>
        <label className="grid gap-1">
          <span className="field-label">End date</span>
          <input
            type="text"
            inputMode="numeric"
            autoComplete="off"
            placeholder="dd/mm/yyyy"
            value={toText}
            onFocus={() => {
              setOpen(true);
              setMonth(to);
            }}
            onClick={() => setOpen(true)}
            onChange={(event) => handleToChange(event.target.value)}
            onBlur={() => setToText(formatDisplay(to))}
            className="field-input"
          />
        </label>
      </div>
      {open ? (
        <div className="w-fit max-w-full rounded-lg border border-line-200 bg-card p-3 shadow-sm">
          <Calendar mode="range" month={month} onMonthChange={setMonth} selected={range} onSelect={handleSelect} />
          <div className="mt-2 flex items-center justify-between gap-3 rounded-md bg-purple-50 px-3 py-2 text-sm text-ink-500">
            <span>
              {formatDisplay(from)} to {formatDisplay(to)}
            </span>
            <button type="button" className="font-medium text-purple-700 hover:underline" onClick={() => setOpen(false)}>
              Done
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
