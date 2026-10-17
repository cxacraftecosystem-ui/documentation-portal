"use client";

/**
 * `Toggle` — the ONE on/off control. Every setting that is on or off uses it, on every surface:
 * never a bare "On"/"Off" sentence, never a checkbox standing in for a setting (owner, 2026-10-17).
 * A checkbox is still right for PICKING rows (bulk select in a table) — that is a selection, not a
 * setting.
 *
 * Two shapes, one control:
 *
 * - **Row** (the default when `label` is shown): a full-width `<button role="switch">` holding the
 *   label, an optional description and the track. The whole row toggles, so the target is the row,
 *   not a 44×24 pill. `aria-labelledby` names it with the label alone and `aria-describedby` adds the
 *   description, so a screen reader hears "Two-step verification, switch, off, <description>".
 * - **Bare** (`hideLabel`): the track alone, named by `aria-label`, for a place whose visible label
 *   already sits beside it. Its hit area is still 44px on a touch screen.
 *
 * Space and Enter both toggle (native `<button>` behaviour). The focus ring is the global outline.
 * Off is an OUTLINED track with an ink knob and on is a filled purple track with a white knob, so the
 * state reads in greyscale and in forced-colours mode, not only by hue; both meet 3:1 non-text
 * contrast against `card` in light and dark. The knob slides with a CSS transition, which the global
 * reduced-motion rules already zero.
 *
 * `busy` keeps the switch where it is and marks it `aria-busy` — for a change that is waiting on the
 * server (two-step verification shows Off with "Setting up…" until the code is verified).
 *
 * Android's twin is `ui/AppToggle.kt` (`AppToggle`).
 */

import { useId, useState } from "react";

import { cn } from "@/lib/utils";

export type ToggleProps = {
  /** Controlled state. Omit (with `defaultChecked`) for an uncontrolled toggle inside a `<form>`. */
  checked?: boolean;
  defaultChecked?: boolean;
  onChange?: (next: boolean) => void;
  /** What the setting is. Always required: it is the switch's accessible name. */
  label: string;
  /** One plain sentence under the label (row shape only). */
  description?: React.ReactNode;
  /** Draw the track alone; `label` becomes its `aria-label`. */
  hideLabel?: boolean;
  disabled?: boolean;
  busy?: boolean;
  id?: string;
  /** Submit with a form: a hidden input carrying "on" while the toggle is on, like a checkbox. */
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
      {/* bg-white rather than bg-card on the ON knob: it sits on a filled purple track in both themes. */}
      <span
        className={cn(
          "absolute top-1/2 grid -translate-y-1/2 place-items-center rounded-full transition-all",
          on ? "left-[22px] h-5 w-5 bg-white shadow-sm" : "left-[3px] h-4 w-4 bg-ink-500"
        )}
      />
    </span>
  );
}
