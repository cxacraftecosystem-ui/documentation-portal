"use client";

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
