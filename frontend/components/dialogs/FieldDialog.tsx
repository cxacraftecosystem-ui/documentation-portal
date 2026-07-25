"use client";

/**
 * `FieldDialog` — the one modal primitive every dialog in this app is built on.
 *
 * Before this existed each dialog re-implemented its own overlay, and the destructive paths did not
 * even get that far: they called `window.confirm()`, which is unthemed, unstyleable, blocks the whole
 * renderer, and on the Android app has no counterpart at all (there every delete opens a real
 * `AlertDialog`). This component is the web half of that parity.
 *
 * What it guarantees, because a modal that gets any of these wrong is worse than no modal:
 *
 * - **Focus is trapped.** Tab and Shift+Tab cycle inside the panel and cannot reach the page behind
 *   it. A stray `focusin` (browser chrome round-trip, a click on the backdrop) is pulled back in.
 * - **Focus is restored.** Whatever was focused when the dialog opened — nearly always the row button
 *   that triggered it — is focused again on close, so a keyboard user resumes where they were.
 * - **Escape closes**, unless the dialog is non-dismissable (`dismissible={false}`, used by the
 *   required-update prompt) or an action is in flight (`busy`).
 * - **Backdrop click closes only when it is safe to.** Destructive dialogs opt out: a misplaced click
 *   must never be the thing that answers "delete this permanently?". Defaults to false for
 *   `role="alertdialog"` and for the danger tone.
 * - **`aria-modal` + a labelled title**, `aria-describedby` on the body when one is supplied, and
 *   `role="alertdialog"` for anything demanding a decision.
 * - **Reduced motion is respected** — `useReducedMotion` covers both the OS setting and the app's own
 *   Settings toggle, and collapses the enter/exit to a plain fade with zero movement.
 * - **The page behind cannot scroll**, and nested dialogs unwind that lock in the right order.
 */

import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { X } from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
  type RefObject
} from "react";
import { createPortal } from "react-dom";

import { cn } from "@/lib/utils";

export type DialogTone = "neutral" | "danger" | "warning";

/** Everything the browser will let a user Tab to, in DOM order. */
const FOCUSABLE_SELECTOR = [
  "a[href]",
  "area[href]",
  "button:not([disabled])",
  "input:not([disabled]):not([type='hidden'])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "iframe",
  "audio[controls]",
  "video[controls]",
  "[contenteditable]:not([contenteditable='false'])",
  "[tabindex]:not([tabindex='-1'])"
].join(",");

/**
 * Open dialogs, innermost last. Only the topmost may act on Escape or reclaim stray focus, otherwise
 * a confirm opened on top of a form dialog would close both with one key press.
 */
const dialogStack: string[] = [];

/**
 * The last element outside any dialog that genuinely held focus.
 *
 * `document.activeElement` at open time is not good enough on its own. A trigger very often disables
 * itself in the same handler that opens the dialog — "Save artisan" flips to "Checking…" while the
 * duplicate lookup runs — and the browser answers a disabled element by dropping focus to `<body>`.
 * By the time the dialog mounts there is nothing left to remember, and closing it strands the
 * keyboard user at the top of the document.
 *
 * So one passive document listener, refcounted across every mounted `FieldDialog`, keeps the last
 * real target. `<body>` and anything inside a dialog are ignored, which is exactly what makes the
 * disabled-trigger case recover: the button is still the last thing we saw focused, and by the time
 * the dialog closes it has been re-enabled.
 */
let lastFocusedOutsideDialog: HTMLElement | null = null;
let focusTrackerRefs = 0;

function onDocumentFocusIn(event: FocusEvent) {
  const target = event.target;
  if (!(target instanceof HTMLElement)) return;
  if (target === document.body) return;
  if (target.closest("[data-field-dialog]")) return;
  lastFocusedOutsideDialog = target;
}

function installFocusTracker() {
  if (typeof document === "undefined") return;
  focusTrackerRefs += 1;
  if (focusTrackerRefs === 1) document.addEventListener("focusin", onDocumentFocusIn, true);
}

function releaseFocusTracker() {
  if (typeof document === "undefined") return;
  focusTrackerRefs = Math.max(0, focusTrackerRefs - 1);
  if (focusTrackerRefs === 0) {
    document.removeEventListener("focusin", onDocumentFocusIn, true);
    lastFocusedOutsideDialog = null;
  }
}

/** Body scroll is locked while any dialog is open, and only unlocked by the last one to close. */
let scrollLockCount = 0;
let previousBodyOverflow = "";

function lockScroll() {
  if (typeof document === "undefined") return;
  if (scrollLockCount === 0) {
    previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
  }
  scrollLockCount += 1;
}

function unlockScroll() {
  if (typeof document === "undefined") return;
  scrollLockCount = Math.max(0, scrollLockCount - 1);
  if (scrollLockCount === 0) document.body.style.overflow = previousBodyOverflow;
}

const TONE_RING: Record<DialogTone, string> = {
  neutral: "border-line-200",
  danger: "border-error-600/30",
  warning: "border-amber-500/40"
};

const TONE_CHIP: Record<DialogTone, string> = {
  neutral: "bg-purple-50 text-purple-700",
  danger: "bg-error-100 text-error-600",
  warning: "bg-amber-100 text-amber-800"
};

export type FieldDialogProps = {
  open: boolean;
  /** Called for Escape, the close button and a permitted backdrop click. */
  onClose: () => void;
  title: ReactNode;
  /** Sub-heading under the title; also becomes the accessible description. */
  description?: ReactNode;
  children?: ReactNode;
  /** Action row, right-aligned at the foot of the panel. */
  footer?: ReactNode;
  /** `alertdialog` for anything the user must answer before continuing. */
  role?: "dialog" | "alertdialog";
  tone?: DialogTone;
  /** Small icon rendered in a tinted tile beside the title. */
  icon?: ReactNode;
  /** False removes Escape, the close button and the backdrop escape hatch entirely. */
  dismissible?: boolean;
  /** Override the tone-derived default for "does clicking the backdrop close this?". */
  dismissOnBackdrop?: boolean;
  /** An action is running: every dismissal path is suspended until it finishes. */
  busy?: boolean;
  /** Focused when the dialog opens; falls back to the first focusable node in the panel. */
  initialFocusRef?: RefObject<HTMLElement | null>;
  /** Extra classes for the panel (width, padding overrides). */
  className?: string;
  /**
   * Replaces the panel's default background + tone border outright. For the one dialog whose colour
   * IS its message (the amber duplicate-artisan prompt); everything else should use `tone`.
   */
  surfaceClassName?: string;
  /**
   * Corner close button. Defaults to on wherever the dialog is dismissable, but a dialog offering
   * three different ways forward turns it off — an X is only unambiguous when there is one way out.
   */
  showClose?: boolean;
  /** Stacking order. 100 is the app's dialog layer; toasts sit at 110. */
  zIndex?: number;
};

export function FieldDialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  role = "dialog",
  tone = "neutral",
  icon,
  dismissible = true,
  dismissOnBackdrop,
  busy = false,
  initialFocusRef,
  className,
  surfaceClassName,
  showClose,
  zIndex = 100
}: FieldDialogProps) {
  const titleId = useId();
  const descriptionId = `${titleId}-desc`;
  const instanceId = useId();
  const panelRef = useRef<HTMLDivElement | null>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  const reduce = useReducedMotion() ?? false;

  // Portals need a DOM; the first client render is what makes one available.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // Runs whether or not the dialog is open: the element to restore has to be known BEFORE it opens.
  useEffect(() => {
    installFocusTracker();
    return releaseFocusTracker;
  }, []);

  // A dismissal is only honoured when the dialog allows it and nothing is in flight.
  const canDismiss = dismissible && !busy;
  // Destructive decisions never close on a stray backdrop click unless a caller insists.
  const backdropCloses = dismissOnBackdrop ?? (role !== "alertdialog" && tone !== "danger");

  const focusableNodes = useCallback(() => {
    const panel = panelRef.current;
    if (!panel) return [] as HTMLElement[];
    return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
      (node) => node.offsetParent !== null || node === document.activeElement
    );
  }, []);

  // Stack registration + scroll lock + focus save/restore, all tied to the same open/close edge.
  useEffect(() => {
    if (!open) return;
    const active = document.activeElement;
    restoreFocusRef.current =
      active instanceof HTMLElement && active !== document.body ? active : lastFocusedOutsideDialog;
    dialogStack.push(instanceId);
    lockScroll();

    return () => {
      const index = dialogStack.lastIndexOf(instanceId);
      if (index >= 0) dialogStack.splice(index, 1);
      unlockScroll();
      // Restore on the next frame: React has already torn the panel out, and focusing while the
      // browser is still settling the removal is what makes focus land on <body> instead.
      const target = restoreFocusRef.current;
      restoreFocusRef.current = null;
      if (target && document.contains(target)) {
        requestAnimationFrame(() => target.focus({ preventScroll: true }));
      }
    };
  }, [open, instanceId]);

  // Initial focus. Destructive dialogs pass their Cancel button, so the dangerous button is never
  // the one sitting under a reflex Enter press.
  useEffect(() => {
    if (!open) return;
    const frame = requestAnimationFrame(() => {
      const preferred = initialFocusRef?.current;
      if (preferred && document.contains(preferred)) {
        preferred.focus({ preventScroll: true });
        return;
      }
      const [first] = focusableNodes();
      (first ?? panelRef.current)?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [open, initialFocusRef, focusableNodes]);

  // Escape, at the document level so it works even if focus briefly slipped out of the panel.
  useEffect(() => {
    if (!open) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      if (dialogStack[dialogStack.length - 1] !== instanceId) return;
      if (!canDismiss) {
        // A non-dismissable dialog still swallows Escape, so it cannot reach a parent dialog.
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      onClose();
    }
    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, [open, canDismiss, onClose, instanceId]);

  // Focus that escapes the panel — by any route Tab handling does not see — is pulled straight back.
  useEffect(() => {
    if (!open) return;
    function onFocusIn(event: FocusEvent) {
      if (dialogStack[dialogStack.length - 1] !== instanceId) return;
      const panel = panelRef.current;
      if (!panel) return;
      const target = event.target;
      if (target instanceof Node && panel.contains(target)) return;
      const [first] = focusableNodes();
      (first ?? panel).focus({ preventScroll: true });
    }
    document.addEventListener("focusin", onFocusIn);
    return () => document.removeEventListener("focusin", onFocusIn);
  }, [open, instanceId, focusableNodes]);

  /** Tab / Shift+Tab wrap-around: the trap proper. */
  function onPanelKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key !== "Tab") return;
    const nodes = focusableNodes();
    if (nodes.length === 0) {
      event.preventDefault();
      panelRef.current?.focus({ preventScroll: true });
      return;
    }
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === panelRef.current)) {
      event.preventDefault();
      last.focus({ preventScroll: true });
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus({ preventScroll: true });
    }
  }

  if (!mounted) return null;

  return createPortal(
    <AnimatePresence>
      {open ? (
        <motion.div
          key="field-dialog-overlay"
          data-field-dialog-overlay=""
          className="fixed inset-0 grid place-items-center overflow-y-auto bg-ink-900/45 p-4 backdrop-blur-[2px]"
          style={{ zIndex }}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: reduce ? 0 : 0.14 }}
          onMouseDown={(event) => {
            if (event.target !== event.currentTarget) return;
            if (canDismiss && backdropCloses) onClose();
          }}
        >
          <motion.div
            ref={panelRef}
            role={role}
            aria-modal="true"
            aria-labelledby={titleId}
            aria-describedby={description ? descriptionId : undefined}
            tabIndex={-1}
            data-field-dialog=""
            data-tone={tone}
            onKeyDown={onPanelKeyDown}
            initial={reduce ? { opacity: 0 } : { opacity: 0, y: 10, scale: 0.98 }}
            animate={reduce ? { opacity: 1 } : { opacity: 1, y: 0, scale: 1 }}
            exit={reduce ? { opacity: 0 } : { opacity: 0, y: 6, scale: 0.98 }}
            transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 420, damping: 34, mass: 0.7 }}
            className={cn(
              "relative w-full max-w-md rounded-xl border p-5 shadow-lg outline-none",
              // One or the other, never both: two competing `bg-*` utilities resolve by stylesheet
              // order, not by the order they appear in this string.
              surfaceClassName ?? cn("bg-card", TONE_RING[tone]),
              className
            )}
          >
            <div className="flex items-start gap-3">
              {icon ? (
                <span aria-hidden className={cn("mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-md", TONE_CHIP[tone])}>
                  {icon}
                </span>
              ) : null}
              <div className="min-w-0 flex-1">
                <h2 id={titleId} className="display-title text-lg leading-snug">
                  {title}
                </h2>
                {description ? (
                  <div id={descriptionId} className="mt-2 text-sm leading-6 text-ink-700">
                    {description}
                  </div>
                ) : null}
                {children}
              </div>
              {/* No close affordance on a dialog that cannot be dismissed — offering one that does
                  nothing is worse than offering none. */}
              {(showClose ?? true) && dismissible ? (
                <button
                  type="button"
                  onClick={onClose}
                  disabled={busy}
                  aria-label="Close dialog"
                  className="-m-1 shrink-0 rounded-md p-1 text-ink-300 transition hover:text-ink-700 disabled:opacity-40"
                >
                  <X className="h-4 w-4" aria-hidden />
                </button>
              ) : null}
            </div>
            {footer ? <div className="mt-5 flex flex-wrap justify-end gap-2">{footer}</div> : null}
          </motion.div>
        </motion.div>
      ) : null}
    </AnimatePresence>,
    document.body
  );
}

/** Solid destructive button — the confirming action inside a danger dialog. */
export const DANGER_BUTTON_CLASS =
  "inline-flex min-h-10 items-center justify-center gap-2 rounded-md bg-error-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-red-700 disabled:cursor-not-allowed disabled:bg-line-200 disabled:text-ink-500";
