"use client";

import Link from "next/link";
import { useEffect, useRef, type RefObject } from "react";
import { AnimatePresence, motion } from "framer-motion";
import type { LucideIcon } from "lucide-react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { ISLAND_MORE_LABEL, islandBadgeText, islandSlotName, type IslandSlot } from "@/lib/islandNav";
import { cn } from "@/lib/utils";

const FOCUSABLE = 'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The history entry the open sheet stands on, so the browser's Back closes it — Android's
 * `BackHandler`. Next's own state is copied in beside it, so the router reads the entry as the same page.
 */
export const ISLAND_MORE_HISTORY_KEY = "islandMoreSheet";

/**
 * The destinations that did not fit on the bar, in the bar's order — Android's `IslandMoreSheet`.
 *
 * A loose destination is one row; a group is its heading and then one row per entry, so nothing
 * hidden is more than one tap away. The row for the screen the reader is on is lit. A modal sheet:
 * focus moves in and is trapped, Escape, Back or the scrim closes it, and focus returns to "More".
 * Opening pushes one history entry; closing other than by Back takes it off again, and a row replaces
 * it with the page it opens, so the sheet never leaves an extra step behind.
 *
 * Shared byte-identical by the designer portal and the documentation portal.
 */
export function IslandMoreSheet({
  open,
  slots,
  currentLabel,
  groupIcon,
  returnFocusTo,
  onClose
}: {
  open: boolean;
  slots: IslandSlot<LucideIcon>[];
  currentLabel: string | null;
  groupIcon: (label: string) => LucideIcon;
  returnFocusTo?: RefObject<HTMLElement | null>;
  onClose: () => void;
}) {
  const reduce = useAppReducedMotion();
  const panelRef = useRef<HTMLDivElement | null>(null);
  const closeRef = useRef(onClose);
  useEffect(() => {
    closeRef.current = onClose;
  });
  // Set by a row just before the sheet closes: that row's link replaces the sheet's history entry.
  const leavingByLink = useRef(false);

  useEffect(() => {
    if (!open) return;
    leavingByLink.current = false;
    let popped = false;
    window.history.pushState({ ...(window.history.state ?? {}), [ISLAND_MORE_HISTORY_KEY]: true }, "");
    const onPopState = (event: PopStateEvent) => {
      // A pop that lands on a sheet entry (a re-run effect's) is not a Back out of this one.
      if ((event.state as Record<string, unknown> | null)?.[ISLAND_MORE_HISTORY_KEY]) return;
      popped = true;
      closeRef.current();
    };
    window.addEventListener("popstate", onPopState);
    return () => {
      window.removeEventListener("popstate", onPopState);
      const onSheetEntry = Boolean((window.history.state as Record<string, unknown> | null)?.[ISLAND_MORE_HISTORY_KEY]);
      if (!popped && !leavingByLink.current && onSheetEntry) window.history.back();
    };
  }, [open]);

  const navigateFromSheet = () => {
    leavingByLink.current = true;
    onClose();
  };

  useEffect(() => {
    if (!open) return;
    const returnTarget = returnFocusTo?.current ?? null;
    const frame = requestAnimationFrame(() => {
      panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE)[0]?.focus();
    });
    const onKeyDown = (event: KeyboardEvent) => {
      const panel = panelRef.current;
      if (event.key === "Escape") {
        event.preventDefault();
        closeRef.current();
        return;
      }
      if (event.key !== "Tab" || !panel) return;
      const stops = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (stops.length === 0) return;
      const first = stops[0];
      const last = stops[stops.length - 1];
      const focused = document.activeElement as HTMLElement | null;
      const outside = !focused || !panel.contains(focused);
      if (event.shiftKey && (outside || focused === first)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (outside || focused === last)) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("keydown", onKeyDown);
      if (returnTarget && document.contains(returnTarget)) returnTarget.focus();
    };
  }, [open, returnFocusTo]);

  return (
    <AnimatePresence>
      {open ? (
        <motion.div
          key="island-more"
          className="fixed inset-0 z-90 lg:hidden"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={reduce ? { duration: 0 } : { duration: 0.18 }}
        >
          <div aria-hidden onClick={onClose} style={{ touchAction: "none" }} className="absolute inset-0 bg-black/[0.32]" />
          <motion.div
            ref={panelRef}
            role="dialog"
            aria-modal="true"
            aria-label={ISLAND_MORE_LABEL}
            initial={reduce ? { y: 0 } : { y: "100%" }}
            animate={{ y: 0 }}
            exit={reduce ? { y: 0 } : { y: "100%" }}
            transition={reduce ? { duration: 0 } : { duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
            className="absolute inset-x-0 bottom-0 mx-auto max-h-[80dvh] w-full max-w-[560px] overflow-y-auto overscroll-contain rounded-t-[28px] border border-b-0 border-line-200 bg-card pb-[calc(1rem+env(safe-area-inset-bottom,0px))] pt-2.5 shadow-lg"
          >
            <span aria-hidden className="mx-auto block h-1 w-8 rounded-full bg-line-200" />
            <h2 className="px-6 pb-1 pt-3 font-display text-base font-semibold text-ink-900">{ISLAND_MORE_LABEL}</h2>
            {slots.map((slot) =>
              slot.kind === "root" ? (
                <SheetRow
                  key={slot.label}
                  href={slot.entry.href}
                  label={slot.label}
                  icon={slot.entry.icon}
                  badge={slot.entry.badge ?? 0}
                  selected={slot.label === currentLabel}
                  onNavigate={navigateFromSheet}
                />
              ) : (
                <section key={slot.label} aria-labelledby={`island-more-${slot.label}`}>
                  <h3
                    id={`island-more-${slot.label}`}
                    className="flex items-center gap-2 px-6 pb-1 pt-3.5 text-xs font-medium uppercase tracking-wide text-ink-500"
                  >
                    {(() => {
                      const Icon = groupIcon(slot.label);
                      return <Icon className="h-4 w-4" aria-hidden />;
                    })()}
                    {slot.label}
                  </h3>
                  {slot.group.entries.map((entry) => (
                    <SheetRow
                      key={entry.href}
                      href={entry.href}
                      label={entry.label}
                      icon={entry.icon}
                      badge={entry.badge ?? 0}
                      selected={entry.label === currentLabel}
                      onNavigate={navigateFromSheet}
                    />
                  ))}
                </section>
              )
            )}
          </motion.div>
        </motion.div>
      ) : null}
    </AnimatePresence>
  );
}

function SheetRow({
  href,
  label,
  icon: Icon,
  badge,
  selected,
  onNavigate
}: {
  href: string;
  label: string;
  icon: LucideIcon;
  badge: number;
  selected: boolean;
  onNavigate: () => void;
}) {
  const text = islandBadgeText(badge);
  return (
    <div className="px-3">
      <Link
        href={href}
        replace
        onClick={onNavigate}
        aria-current={selected ? "page" : undefined}
        aria-label={islandSlotName(label, badge)}
        className={cn(
          "flex min-h-12 items-center gap-3.5 rounded-[20px] px-3 text-sm font-medium transition",
          selected ? "bg-field-100 text-purple-700" : "text-ink-700 hover:bg-surface-50 hover:text-ink-900"
        )}
      >
        <Icon className="h-5 w-5 shrink-0" aria-hidden />
        <span className="truncate">{label}</span>
        {text ? (
          <span aria-hidden className="ml-auto rounded-full bg-purple-700 px-1.5 text-[10px] font-semibold leading-[1.125rem] text-white">
            {text}
          </span>
        ) : null}
      </Link>
    </div>
  );
}
