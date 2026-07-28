"use client";

/**
 * Collapsible panel with a rotating chevron. Collapsed by default; `onOpenChange` lets a panel
 * lazy-load its contents the first time it is opened.
 *
 * LIFTED OUT OF THE QUESTIONNAIRE PAGE, where it was module-private and had one caller. It has three
 * now — the completion matrix, and the two workshop-scoped panels beside it — and a second hand-rolled
 * copy would have been a second set of chevron directions, header paddings and open-state semantics.
 *
 * IT UNMOUNTS ITS CHILDREN WHEN CLOSED (`{open ? … : null}`), which is load-bearing for two reasons and
 * a trap for a third:
 *   * a closed panel costs nothing — no effects, no timers, no fetch;
 *   * `onOpenChange` fires on every toggle, so a lazy loader must guard with a ref rather than relying
 *     on a mount effect, or it re-fetches on every open;
 *   * anything with in-progress local state inside will LOSE it on collapse. Hold that state in the
 *     parent.
 */

import { useState } from "react";
import { ChevronDown } from "lucide-react";

export function Accordion({
  title,
  subtitle,
  defaultOpen = false,
  onOpenChange,
  headerRight,
  children
}: {
  title: string;
  subtitle?: string;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  /** Sits outside the toggle button, so a control here does not also collapse the panel. */
  headerRight?: React.ReactNode;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);

  function toggle() {
    const next = !open;
    setOpen(next);
    onOpenChange?.(next);
  }

  return (
    <section className="panel mb-5">
      <div className="flex items-center gap-3 p-4">
        <button
          type="button"
          onClick={toggle}
          aria-expanded={open}
          className="flex min-w-0 flex-1 items-start gap-3 text-left"
        >
          <ChevronDown
            className={`mt-0.5 h-5 w-5 shrink-0 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`}
            aria-hidden
          />
          <span className="min-w-0">
            <span className="block font-display text-lg font-bold text-ink">{title}</span>
            {subtitle ? <span className="mt-1 block text-sm text-ink-muted">{subtitle}</span> : null}
          </span>
        </button>
        {headerRight}
      </div>
      {open ? <div className="border-t border-line-200 p-4">{children}</div> : null}
    </section>
  );
}
