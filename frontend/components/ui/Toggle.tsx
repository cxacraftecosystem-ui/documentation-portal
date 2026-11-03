"use client";

import { useId, useState } from "react";

import { cn } from "@/lib/utils";

export type ToggleProps = {
  checked?: boolean;
  defaultChecked?: boolean;
  onChange?: (next: boolean) => void;
  label: string;
  description?: React.ReactNode;
  hideLabel?: boolean;
  disabled?: boolean;
  busy?: boolean;
  id?: string;
  name?: string;
  className?: string;
};

export function Toggle({
  checked,
  defaultChecked = false,
  onChange,
  label,
  description,
  hideLabel = false,
  disabled = false,
  busy = false,
  id,
  name,
  className
}: ToggleProps) {
  const baseId = useId();
  const labelId = `${baseId}-label`;
  const descriptionId = `${baseId}-description`;
  const [inner, setInner] = useState(defaultChecked);
  const on = checked ?? inner;

  function flip() {
    if (disabled || busy) return;
    const next = !on;
    if (checked === undefined) setInner(next);
    onChange?.(next);
  }

  const mirror = name && on ? <input type="hidden" name={name} value="on" /> : null;

  if (hideLabel) {
    return (
      <>
        {mirror}
        <button
          id={id}
          type="button"
          role="switch"
          aria-checked={on}
          aria-label={label}
          aria-busy={busy || undefined}
          disabled={disabled}
          onClick={flip}
          data-toggle=""
          className={cn(
            "inline-grid h-8 w-12 shrink-0 place-items-center rounded-full disabled:cursor-not-allowed disabled:opacity-60 pointer-coarse:h-11 pointer-coarse:w-14",
            className
          )}
        >
          <Track on={on} busy={busy} />
        </button>
      </>
    );
  }

  return (
    <>
      {mirror}
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={on}
        aria-labelledby={labelId}
        aria-describedby={description ? descriptionId : undefined}
        aria-busy={busy || undefined}
        disabled={disabled}
        onClick={flip}
        data-toggle=""
        className={cn(
          "flex min-h-12 w-full items-center justify-between gap-4 rounded-md px-1 py-2 text-left transition hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-transparent pointer-coarse:min-h-14",
          className
        )}
      >
        <span className="min-w-0">
          <span id={labelId} className="block text-sm font-medium text-ink-900">
            {label}
          </span>
          {description ? (
            <span id={descriptionId} className="mt-0.5 block text-xs leading-5 text-ink-500">
              {description}
            </span>
          ) : null}
        </span>
        <Track on={on} busy={busy} />
      </button>
    </>
  );
}

function Track({ on, busy }: { on: boolean; busy: boolean }) {
  return (
    <span
      aria-hidden
      className={cn(
        "relative inline-block h-7 w-12 shrink-0 rounded-full border-2 transition-colors",
        on ? "border-purple-700 bg-purple-700" : "border-ink-500 bg-card",
        busy && "opacity-70"
      )}
    >
      <span
        className={cn(
          "absolute top-1/2 grid -translate-y-1/2 place-items-center rounded-full transition-all",
          on ? "left-[22px] h-5 w-5 bg-white shadow-sm" : "left-[3px] h-4 w-4 bg-ink-500"
        )}
      />
    </span>
  );
}
