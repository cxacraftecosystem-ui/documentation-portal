"use client";

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
import { ButtonRow } from "@/components/ui/ButtonRow";

export type DialogTone = "neutral" | "danger" | "warning";

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

const dialogStack: string[] = [];

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
  onClose: () => void;
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  role?: "dialog" | "alertdialog";
  tone?: DialogTone;
  icon?: ReactNode;
  dismissible?: boolean;
  dismissOnBackdrop?: boolean;
  busy?: boolean;
  initialFocusRef?: RefObject<HTMLElement | null>;
  className?: string;
  surfaceClassName?: string;
  showClose?: boolean;
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

  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  useEffect(() => {
    installFocusTracker();
    return releaseFocusTracker;
  }, []);

  const canDismiss = dismissible && !busy;
  const backdropCloses = dismissOnBackdrop ?? (role !== "alertdialog" && tone !== "danger");

  const focusableNodes = useCallback(() => {
    const panel = panelRef.current;
    if (!panel) return [] as HTMLElement[];
    return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
      (node) => node.offsetParent !== null || node === document.activeElement
    );
  }, []);

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
      const target = restoreFocusRef.current;
      restoreFocusRef.current = null;
      if (target && document.contains(target)) {
        requestAnimationFrame(() => target.focus({ preventScroll: true }));
      }
    };
  }, [open, instanceId]);

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

  useEffect(() => {
    if (!open) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      if (dialogStack[dialogStack.length - 1] !== instanceId) return;
      if (!canDismiss) {
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
              "relative w-full max-w-md rounded-xl border p-5 shadow-lg outline-hidden",
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
              {(showClose ?? true) && dismissible ? (
                <button
                  type="button"
                  onClick={onClose}
                  disabled={busy}
                  aria-label="Close dialog"
                  className="-m-1 shrink-0 rounded-md p-1 text-ink-500 transition hover:text-ink-700 disabled:opacity-40"
                >
                  <X className="h-4 w-4" aria-hidden />
                </button>
              ) : null}
            </div>
            {footer ? <ButtonRow align="end" className="mt-5">{footer}</ButtonRow> : null}
          </motion.div>
        </motion.div>
      ) : null}
    </AnimatePresence>,
    document.body
  );
}

export const DANGER_BUTTON_CLASS =
  "inline-flex min-h-10 items-center justify-center gap-2 rounded-md bg-error-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-red-700 disabled:cursor-not-allowed disabled:bg-line-200 disabled:text-ink-500";
