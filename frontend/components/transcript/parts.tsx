"use client";

import { Children, isValidElement, useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";

import { copyText } from "@/lib/transcript/clipboard";
import { ts } from "@/lib/transcript/strings";

/**
 * Small pieces the transcript viewer is built from. They live in the shared tree (rather than
 * reaching for each portal's `components/ui`) because the two portals' primitives do not carry the
 * same exports, and this tree may import only what both do (`shared/transcript-web-imports.json`).
 */

export const SECONDARY =
  "inline-flex min-h-10 items-center justify-center gap-2 rounded-md border border-line-200 bg-card px-3 py-2 text-sm font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-60 dark:hover:bg-purple-950/40";
export const PRIMARY =
  "inline-flex min-h-10 items-center justify-center gap-2 rounded-md bg-purple-700 px-4 py-2 text-sm font-medium text-white transition hover:bg-purple-800 disabled:cursor-not-allowed disabled:bg-line-200 disabled:text-ink-500";
export const ICON_BUTTON =
  "inline-grid h-9 w-9 shrink-0 place-items-center rounded-md text-ink-500 transition hover:bg-purple-50 hover:text-purple-700 max-sm:h-11 max-sm:w-11 dark:hover:bg-purple-950/40 dark:hover:text-purple-300";

/**
 * A row of buttons by the house rule (owner, 2026-10-17): one is centred, two share a line, three
 * or more stack on a phone and become pills from `sm` up.
 */
export function Actions({ children, align = "end", className = "" }: { children: ReactNode; align?: "start" | "center" | "end"; className?: string }) {
  const count = Children.toArray(children).filter((child) => isValidElement(child)).length;
  const justify = align === "start" ? "sm:justify-start" : align === "center" ? "sm:justify-center" : "sm:justify-end";
  if (count <= 1) return <div className={`flex justify-center ${className}`}>{children}</div>;
  if (count === 2)
    return <div className={`grid grid-cols-2 gap-2 sm:flex sm:flex-wrap ${justify} ${className}`}>{children}</div>;
  return (
    <div className={`grid gap-2 sm:flex sm:flex-wrap ${justify} sm:[&>*]:rounded-full ${className}`} data-pills>
      {children}
    </div>
  );
}

export type MenuItem = { id: string; label: string; onSelect: () => void; disabled?: boolean };

/**
 * A button that opens a short menu of actions. Keyboard: Enter/Space/ArrowDown open it with the
 * first item focused, arrows and Home/End move, Escape closes and returns focus to the button.
 */
export function MenuButton({
  label,
  ariaLabel,
  icon,
  items,
  className = SECONDARY,
  testId,
  align = "start"
}: {
  label?: ReactNode;
  ariaLabel: string;
  icon?: ReactNode;
  items: MenuItem[];
  className?: string;
  testId?: string;
  align?: "start" | "end";
}) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const menuId = useId();

  const close = useCallback((refocus: boolean) => {
    setOpen(false);
    if (refocus) buttonRef.current?.focus();
  }, []);

  useEffect(() => {
    if (!open) return;
    const frame = requestAnimationFrame(() => listRef.current?.querySelector<HTMLButtonElement>("button:not([disabled])")?.focus());
    function outside(event: PointerEvent) {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", outside);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("pointerdown", outside);
    };
  }, [open]);

  function onKey(event: React.KeyboardEvent<HTMLDivElement>) {
    const buttons = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>("button:not([disabled])") ?? []);
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement);
    let next = -1;
    if (event.key === "Tab") {
      setOpen(false);
      return;
    }
    if (event.key === "ArrowDown") next = (at + 1) % buttons.length;
    else if (event.key === "ArrowUp") next = (at - 1 + buttons.length) % buttons.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = buttons.length - 1;
    if (next < 0) return;
    event.preventDefault();
    buttons[next]?.focus();
  }

  return (
    <div
      ref={wrapRef}
      className="relative inline-flex"
      onKeyDown={(event) => {
        // Escape closes from anywhere inside, the trigger included (focus reaches the first item a
        // frame after opening, and a quick Escape must not be lost in between).
        if (open && event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          close(true);
        }
      }}
    >
      <button
        ref={buttonRef}
        type="button"
        className={className}
        aria-label={ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        data-testid={testId}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" && !open) {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        {icon}
        {label}
        {label ? <ChevronDown className="h-4 w-4 text-ink-500" aria-hidden /> : null}
      </button>
      {open ? (
        <div
          ref={listRef}
          id={menuId}
          role="menu"
          aria-label={ariaLabel}
          onKeyDown={onKey}
          className={`absolute top-full z-10 mt-1 grid min-w-48 gap-0.5 rounded-md border border-line-200 bg-card p-1 shadow-lg ${align === "end" ? "right-0" : "left-0"}`}
        >
          {items.map((item) => (
            <button
              key={item.id}
              type="button"
              role="menuitem"
              disabled={item.disabled}
              data-menu-item={item.id}
              className="flex min-h-10 items-center rounded-sm px-3 text-left text-sm text-ink-900 hover:bg-purple-50 focus-visible:bg-purple-50 disabled:cursor-not-allowed disabled:text-ink-300 max-sm:min-h-11 dark:hover:bg-purple-950/40 dark:focus-visible:bg-purple-950/40"
              onClick={() => {
                close(true);
                item.onSelect();
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

/**
 * Copy with a brief "Copied" (or a short failure) said in one polite live region per viewer.
 * The region is always mounted, so the first announcement is heard.
 */
export function useCopyFeedback() {
  const [message, setMessage] = useState<{ text: string; ok: boolean; nonce: number } | null>(null);
  const timer = useRef<number | null>(null);
  useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current);
  }, []);
  const copy = useCallback(async (text: string) => {
    const ok = await copyText(text);
    if (timer.current) window.clearTimeout(timer.current);
    setMessage({ text: ok ? ts("copied") : ts("copyFailed"), ok, nonce: Date.now() });
    timer.current = window.setTimeout(() => setMessage(null), ok ? 1800 : 5000);
    return ok;
  }, []);
  return { copy, message };
}

export function CopyStatus({ message }: { message: { text: string; ok: boolean } | null }) {
  return (
    <p role="status" aria-live="polite" data-copy-status={message ? (message.ok ? "ok" : "failed") : undefined} className="min-h-0">
      {message ? (
        <span
          className={
            message.ok
              ? "inline-flex items-center rounded-full bg-success-100 px-2.5 py-1 text-xs font-medium text-success-600"
              : "inline-flex items-center rounded-full bg-error-100 px-2.5 py-1 text-xs font-medium text-error-600"
          }
        >
          {message.text}
        </span>
      ) : null}
    </p>
  );
}

/** A clock that plays the audio from a moment: the timestamp on a turn, a citation chip. */
export function TimeChip({ ms, label, onSeek, variant = "plain" }: { ms: number; label: string; onSeek: (ms: number) => void; variant?: "plain" | "chip" }) {
  return (
    <button
      type="button"
      onClick={() => onSeek(ms)}
      aria-label={ts("playFrom", { time: label })}
      data-seek-ms={ms}
      className={
        variant === "chip"
          ? "inline-flex min-h-8 items-center rounded-full border border-purple-200 bg-purple-50 px-2.5 font-mono text-xs text-purple-800 transition hover:border-purple-400 max-sm:min-h-10 dark:border-purple-800 dark:bg-purple-950/50 dark:text-purple-200"
          : "inline-flex min-h-8 items-center rounded-sm px-1 font-mono text-xs text-purple-700 underline-offset-2 hover:underline max-sm:min-h-10 dark:text-purple-300"
      }
    >
      {label}
    </button>
  );
}

export function Badge({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "accent" | "warn" }) {
  const cls =
    tone === "accent"
      ? "border-purple-200 bg-purple-50 text-purple-800 dark:border-purple-800 dark:bg-purple-950/50 dark:text-purple-200"
      : tone === "warn"
        ? "border-amber-500/40 bg-amber-100 text-amber-800"
        : "border-line-200 bg-surface-50 text-ink-500";
  return <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium ${cls}`}>{children}</span>;
}
