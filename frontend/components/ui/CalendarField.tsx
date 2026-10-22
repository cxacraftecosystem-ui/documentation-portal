"use client";

/**
 * `CalendarField` — the ONE date field: a typed `dd/mm/yyyy` box that opens a month calendar, with
 * `min`/`max`, and a Clear button. Never `<input type="date">` (owner, 2026-10-17): the native
 * picker looks different on every browser, cannot be themed for dark mode, and on Android Chrome
 * hides the typed path entirely.
 *
 * It is `DateField` (components/forms/DateTimeField.tsx) — the typing contract, the anchored
 * calendar, the keyboard model — plus a clear control. The value is a `yyyy-mm-dd` LOCAL date both
 * ways (`toIsoDate` / `fromIsoDate` build from local parts, the `parseDateInput` rule): a date is a
 * day on the wall calendar, never a UTC instant, so it cannot slide a day west of Greenwich.
 *
 * Label it as a SIBLING (`<label htmlFor={id}>` + `<CalendarField id={id}>`), never by wrapping —
 * see the DateTimeField header for why. Android's twin is `ui/CalendarField.kt`.
 */

import { X } from "lucide-react";
import { useId, useState } from "react";

import { DateField, type DateFieldProps } from "@/components/forms/DateTimeField";
import { RequiredMark } from "@/components/ui/RequiredMark";
import { cn } from "@/lib/utils";

export type CalendarFieldProps = DateFieldProps & {
  /** Offer a Clear button while a date is set (default on; off for a required field). */
  clearable?: boolean;
  /** A visible label drawn above the field as a sibling `<label htmlFor>` (designer-portal's shape). */
  label?: string;
};

export function CalendarField({ label, ...props }: CalendarFieldProps) {
  const generated = useId();
  if (!label) return <CalendarControl {...props} />;
  const id = props.id ?? `calendar-${generated}`;
  return (
    <div className="grid min-w-0 gap-1.5">
      <label htmlFor={id} className="field-label">
        {label}
        <RequiredMark when={props.required} />
      </label>
      <CalendarControl {...props} id={id} />
    </div>
  );
}

function CalendarControl({ clearable, value, defaultValue, onChange, className, ...rest }: Omit<CalendarFieldProps, "label">) {
  const controlled = value !== undefined;
  const [inner, setInner] = useState(defaultValue ?? "");
  const iso = controlled ? value : inner;
  const canClear = (clearable ?? !rest.required) && Boolean(iso) && !rest.disabled;

  function change(next: string) {
    if (!controlled) setInner(next);
    onChange?.(next);
  }

  return (
    <div className={cn("flex min-w-0 items-start gap-1.5", className)}>
      <div className="min-w-0 flex-1">
        <DateField {...rest} value={iso} onChange={change} />
      </div>
      {canClear ? (
        <button
          type="button"
          onClick={() => change("")}
          aria-label={rest.ariaLabel ? `Clear ${rest.ariaLabel}` : "Clear date"}
          title="Clear date"
          className="grid h-[42px] w-10 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-500 transition hover:border-purple-300 hover:text-ink-900 pointer-coarse:h-11 pointer-coarse:w-11"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      ) : null}
    </div>
  );
}
