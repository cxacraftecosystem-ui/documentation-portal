"use client";

/**
 * `AnchoredPopover` — a panel that floats over the page next to the element that opened it, and
 * moves nothing.
 *
 * This exists because of a reported bug: opening the workshop date picker pushed the whole rest of
 * the form down the page. The calendar was rendered inline, in document flow, so it occupied real
 * layout space — no amount of z-index would have fixed it. A popover has to be taken OUT of flow and
 * out of its clipping ancestors, and that is all this component does. It knows nothing about dates;
 * `components/forms/DateTimeField` is its first caller and dropdowns could be its second.
 *
 * The four things a hand-rolled popover normally gets wrong, and what is done about each:
 *
 * - **Clipping.** Filter panels and dialogs are `overflow-hidden` / `overflow-y-auto`, which shears
 *   an absolutely positioned child. The panel is therefore portalled out.
 * - **Stacking.** Portalled to `document.body` it sits at `FLOATING_Z`, chosen to clear the floating
 *   island nav (50) and the skip link (60) while staying under the dialog layer (100) and toasts
 *   (110). Inside a dialog it is portalled into the dialog's own panel instead, so it is above that
 *   dialog's content and — structurally, not by a magic number — below any dialog stacked on top.
 * - **Flipping.** A picker on the last row of a long form has no room below it, so the panel opens
 *   upward; near the right edge it shifts left. Both are recomputed from the live viewport rather
 *   than guessed once.
 * - **Scroll.** The panel follows its anchor on every scroll of every ancestor, and closes once the
 *   USER has scrolled the anchor out of the viewport, rather than hovering somewhere it no longer
 *   belongs. Only scroll and resize may dismiss it, and not for the first moments after opening:
 *   the panel would otherwise close itself, because flipping upward and moving focus inside itself
 *   both scroll the page, which pushed a low-sitting anchor out of view and tripped this very rule.
 *
 * It is deliberately NOT a focus trap. The trigger is a text input the user must be able to keep
 * typing into while the calendar is open, so opening does not steal focus; Escape, a click outside
 * and focus leaving the pair all close it.
 */

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

/** Breathing room kept between the panel and the edge of the viewport. */
const GUTTER = 8;

/** A panel is never squeezed below this; past it, flipping is better than shrinking. */
const MIN_PANEL_HEIGHT = 220;

/**
 * Over the island nav (`z-50`) and the skip link (`z-60`), under the dialog layer (100) and the
 * toast layer (110). Only used when the popover is NOT inside a dialog — see the file header.
 */
const FLOATING_Z = 70;

/**
 * Open popovers, innermost last. Escape must close only the topmost, and — more importantly — must
 * be swallowed before it reaches `FieldDialog`, which would otherwise close the whole dialog the
 * picker is sitting in. See `onKeyDown` for why the listener goes on `window`.
 */
const popoverStack: string[] = [];

type Side = "top" | "bottom";

type Placement = { top: number; left: number; maxHeight: number; side: Side };

/**
 * How long after opening the panel refuses to close itself for being "scrolled away".
 *
 * Long enough to outlast the open animation and any focus the panel moves inside itself; short
 * enough that a user who opens a picker and immediately flicks the page still sees it dismiss.
 */
const CLOSE_ON_SCROLL_GRACE_MS = 600;

/** SSR renders nothing, but the hook still has to be called unconditionally. */
const useIsomorphicLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

/**
 * True when the OS asks for less motion, or when the app's own Settings toggle does.
 *
 * The toggle stamps `data-reduced-motion` on `<html>` and globals.css switches CSS transitions and
 * animations off from there. That is not enough on its own: framer-motion animates by writing inline
 * styles frame by frame, which no CSS rule can suppress. So the attribute is read here too, or a
 * reader who turned the setting on in Settings would still get a panel that slides.
 */
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

/**
 * Where the panel goes, in viewport coordinates.
 *
 * Flipping is conditional on BOTH tests: the panel has to actually not fit below, and there has to be
 * more room above. Flipping on the first test alone makes a picker in a short viewport jump upward
 * into even less space.
 */
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
  /** Escape, a click outside, focus leaving the pair, and the anchor scrolling away all call this. */
  onClose: () => void;
  /** The element the panel is positioned against — normally the field wrapper, not the input. */
  anchorRef: RefObject<HTMLElement | null>;
  /** Accessible name for the panel; it is announced when focus moves inside. */
  label: string;
  children: ReactNode;
  /** Gap between the anchor and the panel. */
  offset?: number;
  /** Give the panel at least the anchor's width — right for a list, wrong for a month grid. */
  matchAnchorWidth?: boolean;
  /** Ceiling in px, so `matchAnchorWidth` on a full-width field does not produce a full-width list. */
  maxWidth?: number;
  className?: string;
  /** Escape also fires this before restoring focus, so the caller can put focus somewhere better. */
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
  // When this opening began, so the panel cannot be dismissed by the layout its own arrival causes.
  const openedAt = useRef(0);
  const reduce = useLessMotion();

  // Portals need a DOM, and the first client render is what makes one available.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // Resolved once per open and then held, so the exit animation still has somewhere to play out.
  const [host, setHost] = useState<HTMLElement | null>(null);
  const [absolute, setAbsolute] = useState(false);
  const [placement, setPlacement] = useState<Placement | null>(null);
  const [minWidth, setMinWidth] = useState<number | undefined>(undefined);

  useEffect(() => {
    // The last placement is deliberately NOT cleared on close. AnimatePresence keeps the panel
    // mounted for its exit, and a null placement falls back to top/left 0 — so clearing it made the
    // panel jump to the top-left corner and fade out there instead of where it had been sitting.
    // Re-opening recomputes in a layout effect before the browser paints, so nothing stale is shown.
    if (!open) return;
    const anchor = anchorRef.current;
    if (!anchor) return;
    // Inside a dialog the panel is portalled into the dialog's own panel rather than to the body.
    // That keeps focus INSIDE the dialog, which matters: FieldDialog pulls back any focus that lands
    // outside its panel, and a body-portalled calendar would be yanked away the moment it was used.
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
    // Scrolled out of sight: close rather than leave the panel floating over unrelated content.
    //
    // Only the scroll and resize listeners may reach this — hence `mayClose`. It used to run on
    // EVERY reposition, including the ones the ResizeObserver fires as the panel settles, and that
    // made the popover dismiss itself a few hundred milliseconds after opening: a trigger low in
    // the viewport flips the panel upward, something inside it takes focus, the browser scrolls to
    // reveal it, and the anchor is pushed below the fold by the popover's own opening. Closing on
    // layout the popover itself caused is never right; closing when the USER scrolls away is.
    //
    // The grace period covers the rest of that sequence. The scroll a focus triggers is a real
    // scroll event, so gating on the event source alone is not enough — the panel has to survive
    // its own arrival before it starts listening for reasons to leave.
    if (mayClose && Date.now() - openedAt.current > CLOSE_ON_SCROLL_GRACE_MS) {
      if (rect.bottom < 0 || rect.top > viewportHeight) {
        onClose();
        return;
      }
    }

    // `offsetWidth` / `scrollHeight` are layout values, unaffected by the open animation's transform,
    // and `scrollHeight` still reports the full content height once a `maxHeight` has been applied —
    // so the flip decision does not get stuck on whatever height the last pass clamped it to.
    const next = computePlacement(rect, panel.offsetWidth, panel.scrollHeight, offset);

    if (absolute) {
      // Offsets for an absolutely positioned child are measured from the host's PADDING box, while
      // getBoundingClientRect reports its border box — hence the border correction. (The dialog panel
      // is scaled by framer-motion only while it is animating open, and the popover cannot be open
      // during that, so post-transform and layout coordinates agree here.)
      const hostRect = host.getBoundingClientRect();
      const style = getComputedStyle(host);
      next.top -= hostRect.top + parseFloat(style.borderTopWidth || "0") - host.scrollTop;
      next.left -= hostRect.left + parseFloat(style.borderLeftWidth || "0") - host.scrollLeft;
    }

    setPlacement((current) => (samePlacement(current, next) ? current : next));
    setMinWidth(matchAnchorWidth ? rect.width : undefined);
  }, [absolute, anchorRef, host, matchAnchorWidth, offset, onClose]);

  // First placement, before the browser paints — otherwise the panel is visible at 0,0 for a frame.
  useIsomorphicLayoutEffect(() => {
    if (!open || !host) return;
    openedAt.current = Date.now();
    reposition();
  }, [open, host, reposition]);

  // Follow the anchor. `capture` on the scroll listener is what catches ancestor scroll containers:
  // scroll does not bubble, so a listener on window alone would miss a picker inside a scrolling panel.
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
    // A user scroll or a resize may dismiss the panel; the ResizeObserver below may only move it.
    const scheduleUserMove = () => {
      mayClose = true;
      schedule();
    };
    window.addEventListener("scroll", scheduleUserMove, true);
    window.addEventListener("resize", scheduleUserMove);
    // The panel changes height when the month goes from five week-rows to six, which can change the
    // flip decision. The rAF throttle plus the equality check in `setPlacement` keep this from looping.
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

  // Escape, and the reason it is bound to `window` rather than `document`.
  //
  // FieldDialog listens for Escape with a document-level CAPTURE listener. Two capture listeners on
  // the same node fire in registration order, and the dialog's was registered first — so a picker
  // opened inside a dialog would have Escape close the dialog out from under it. `window` is the
  // first entry in the capture path, ahead of `document`, so this handler runs first and
  // `stopPropagation` keeps the key from ever reaching the dialog.
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

  // A click anywhere that is neither the panel nor the anchor. `pointerdown` rather than `click` so
  // the panel is gone before the click lands on whatever is underneath.
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

  // Tabbing out of the pair closes it — the keyboard equivalent of clicking away.
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
            // Inside a dialog the panel is a child of the dialog's own stacking context, so a small
            // local value is all it needs — and it cannot then out-rank a dialog stacked above.
            zIndex: absolute ? 50 : FLOATING_Z
            // Nothing hides the frame between mount and the first measurement, and nothing should:
            // the entrance animation already starts at `opacity: 0`, and the placement runs in a
            // layout effect, so the panel is positioned before the browser paints it. A
            // `visibility: hidden` guard here looked harmless and was not — an element that is
            // `visibility: hidden` cannot take focus, and react-day-picker moves focus onto a day in
            // a mount effect, so opening with the keyboard silently left focus behind on the input.
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
