"use client";

import { X } from "lucide-react";
import { useId, useState } from "react";

import { DateField, type DateFieldProps } from "@/components/forms/DateTimeField";
import { RequiredMark } from "@/components/ui/RequiredMark";
import { cn } from "@/lib/utils";

export type CalendarFieldProps = DateFieldProps & {
  clearable?: boolean;
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
