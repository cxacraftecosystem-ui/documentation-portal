"use client";

import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
  type RefObject
} from "react";
import { createPortal } from "react-dom";

import { cn } from "@/lib/utils";

const GUTTER = 8;

const MIN_PANEL_HEIGHT = 220;

const FLOATING_Z = 70;

const popoverStack: string[] = [];

type Side = "top" | "bottom";

type Placement = { top: number; left: number; maxHeight: number; side: Side };

const CLOSE_ON_SCROLL_GRACE_MS = 600;

const useIsomorphicLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

function useLessMotion() {
  const system = useReducedMotion() ?? false;
  const [preference, setPreference] = useState(false);

  useEffect(() => {
    const root = document.documentElement;
    const read = () => setPreference(root.dataset.reducedMotion === "true");
    read();
    const observer = new MutationObserver(read);
    observer.observe(root, { attributes: true, attributeFilter: ["data-reduced-motion"] });
    return () => observer.disconnect();
  }, []);

  return system || preference;
}

function samePlacement(a: Placement | null, b: Placement) {
  return (
    a !== null &&
    Math.abs(a.top - b.top) < 0.5 &&
    Math.abs(a.left - b.left) < 0.5 &&
    Math.abs(a.maxHeight - b.maxHeight) < 0.5 &&
    a.side === b.side
  );
}

function computePlacement(anchor: DOMRect, width: number, height: number, offset: number): Placement {
  const viewportWidth = document.documentElement.clientWidth;
  const viewportHeight = document.documentElement.clientHeight;

  const roomBelow = viewportHeight - anchor.bottom - offset - GUTTER;
  const roomAbove = anchor.top - offset - GUTTER;
  const flip = height > roomBelow && roomAbove > roomBelow;

  const room = Math.max(flip ? roomAbove : roomBelow, MIN_PANEL_HEIGHT);
  const maxHeight = Math.min(room, viewportHeight - 2 * GUTTER);
  const settledHeight = Math.min(height, maxHeight);
  const top = flip
    ? Math.max(GUTTER, anchor.top - offset - settledHeight)
    : Math.min(anchor.bottom + offset, viewportHeight - GUTTER - settledHeight);

  let left = anchor.left;
  const rightLimit = viewportWidth - GUTTER - width;
  if (left > rightLimit) left = rightLimit;
  if (left < GUTTER) left = GUTTER;

  return { top, left, maxHeight, side: flip ? "top" : "bottom" };
}

export type AnchoredPopoverProps = {
  open: boolean;
  onClose: () => void;
  anchorRef: RefObject<HTMLElement | null>;
  label: string;
  children: ReactNode;
  offset?: number;
  matchAnchorWidth?: boolean;
  maxWidth?: number;
  className?: string;
  restoreFocusRef?: RefObject<HTMLElement | null>;
};

export function AnchoredPopover({
  open,
  onClose,
  anchorRef,
  label,
  children,
  offset = 6,
  matchAnchorWidth = false,
  maxWidth,
  className,
  restoreFocusRef
}: AnchoredPopoverProps) {
  const instanceId = useId();
  const panelRef = useRef<HTMLDivElement | null>(null);
  const openedAt = useRef(0);
  const reduce = useLessMotion();

  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const [host, setHost] = useState<HTMLElement | null>(null);
  const [absolute, setAbsolute] = useState(false);
  const [placement, setPlacement] = useState<Placement | null>(null);
  const [minWidth, setMinWidth] = useState<number | undefined>(undefined);

  useEffect(() => {
    if (!open) return;
    const anchor = anchorRef.current;
    if (!anchor) return;
    const dialogPanel = anchor.closest<HTMLElement>("[data-field-dialog]");
    setHost(dialogPanel ?? document.body);
    setAbsolute(Boolean(dialogPanel));
  }, [open, anchorRef]);

  const reposition = useCallback((mayClose = false) => {
    const anchor = anchorRef.current;
    const panel = panelRef.current;
    if (!anchor || !panel || !host) return;

    const rect = anchor.getBoundingClientRect();
    const viewportHeight = document.documentElement.clientHeight;
    if (mayClose && Date.now() - openedAt.current > CLOSE_ON_SCROLL_GRACE_MS) {
      if (rect.bottom < 0 || rect.top > viewportHeight) {
        onClose();
        return;
      }
    }

    const next = computePlacement(rect, panel.offsetWidth, panel.scrollHeight, offset);

    if (absolute) {
      const hostRect = host.getBoundingClientRect();
      const style = getComputedStyle(host);
      next.top -= hostRect.top + parseFloat(style.borderTopWidth || "0") - host.scrollTop;
      next.left -= hostRect.left + parseFloat(style.borderLeftWidth || "0") - host.scrollLeft;
    }

    setPlacement((current) => (samePlacement(current, next) ? current : next));
    setMinWidth(matchAnchorWidth ? rect.width : undefined);
  }, [absolute, anchorRef, host, matchAnchorWidth, offset, onClose]);

  useIsomorphicLayoutEffect(() => {
    if (!open || !host) return;
    openedAt.current = Date.now();
    reposition();
  }, [open, host, reposition]);

  useEffect(() => {
    if (!open || !host) return;
    let frame = 0;
    let mayClose = false;
    const schedule = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        reposition(mayClose);
        mayClose = false;
      });
    };
    const scheduleUserMove = () => {
      mayClose = true;
      schedule();
    };
    window.addEventListener("scroll", scheduleUserMove, true);
    window.addEventListener("resize", scheduleUserMove);
    const observer = new ResizeObserver(schedule);
    if (panelRef.current) observer.observe(panelRef.current);
    if (anchorRef.current) observer.observe(anchorRef.current);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      window.removeEventListener("scroll", scheduleUserMove, true);
      window.removeEventListener("resize", scheduleUserMove);
      observer.disconnect();
    };
  }, [open, host, reposition, anchorRef]);

  useEffect(() => {
    if (!open) return;
    popoverStack.push(instanceId);
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      if (popoverStack[popoverStack.length - 1] !== instanceId) return;
      event.preventDefault();
      event.stopPropagation();
      onClose();
      const target = restoreFocusRef?.current ?? anchorRef.current;
      target?.focus({ preventScroll: true });
    }
    window.addEventListener("keydown", onKeyDown, true);
    return () => {
      const index = popoverStack.lastIndexOf(instanceId);
      if (index >= 0) popoverStack.splice(index, 1);
      window.removeEventListener("keydown", onKeyDown, true);
    };
  }, [open, instanceId, onClose, anchorRef, restoreFocusRef]);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent) {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (panelRef.current?.contains(target)) return;
      if (anchorRef.current?.contains(target)) return;
      onClose();
    }
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  }, [open, onClose, anchorRef]);

  useEffect(() => {
    if (!open) return;
    function onFocusIn(event: FocusEvent) {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (panelRef.current?.contains(target)) return;
      if (anchorRef.current?.contains(target)) return;
      onClose();
    }
    document.addEventListener("focusin", onFocusIn);
    return () => document.removeEventListener("focusin", onFocusIn);
  }, [open, onClose, anchorRef]);

  if (!mounted || !host) return null;

  const side = placement?.side ?? "bottom";

  return createPortal(
    <AnimatePresence>
      {open ? (
        <motion.div
          key="anchored-popover"
          ref={panelRef}
          role="dialog"
          aria-label={label}
          data-anchored-popover=""
          data-side={side}
          data-strategy={absolute ? "absolute" : "fixed"}
          style={{
            position: absolute ? "absolute" : "fixed",
            top: placement?.top ?? 0,
            left: placement?.left ?? 0,
            maxHeight: placement?.maxHeight,
            minWidth,
            maxWidth: maxWidth
              ? `min(${maxWidth}px, calc(100vw - ${2 * GUTTER}px))`
              : `calc(100vw - ${2 * GUTTER}px)`,
            zIndex: absolute ? 50 : FLOATING_Z
          }}
          initial={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.98, y: side === "top" ? 4 : -4 }}
          animate={reduce ? { opacity: 1 } : { opacity: 1, scale: 1, y: 0 }}
          exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.98, y: side === "top" ? 2 : -2 }}
          transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 520, damping: 38, mass: 0.6 }}
          className={cn(
            "overflow-y-auto overscroll-contain rounded-lg border border-line-200 bg-card p-3 shadow-lg",
            className
          )}
        >
          {children}
        </motion.div>
      ) : null}
    </AnimatePresence>,
    host
  );
}
