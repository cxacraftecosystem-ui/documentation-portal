"use client";

/**
 * "Something over there was picked — bring the matching row here and say which one it is."
 *
 * THE PROBLEM THIS SOLVES. Two views of one selection sit side by side: a graphic on the left (the map's
 * numbered dots) and a list of cards on the right. Clicking a dot changes the selection, and on the
 * graphic that is obvious — the pin lights up under the pointer that just hit it. In the list it is
 * invisible: the matching card is very often scrolled out of view, so the click appears to do nothing,
 * and even when the card IS in view a reader has no idea which of a dozen similar cards just changed.
 *
 * So this does two things, in this order:
 *
 *   1. SCROLL, but only as far as it has to. Up if the row is above the viewport, down if below, and
 *      NOT AT ALL if the row is already comfortably visible — a list that lurches on every click is
 *      worse than one that never moves, because the reader loses the row they were reading.
 *   2. FLASH ONCE. A single pulse plus a ring that lingers for a beat. The pulse says "this one, just
 *      now"; the ring is what remains for anyone who looked away during the pulse or who has motion
 *      turned off.
 *
 * IT WORKS IN BOTH LAYOUTS, and that is the part worth reading before changing anything. On a wide
 * screen the card column is its OWN scroll container (bounded height, `overflow-y-auto`) so the map and
 * the cards scroll independently. On a phone there is no such container: the page scrolls and the cards
 * are simply below the map. Those need different scrolling — a container's `scrollTop`, versus the
 * document's — so the container is measured at call time rather than assumed. `scrollIntoView` alone
 * cannot serve the first case well: it scrolls EVERY scrollable ancestor including the document, which
 * on a wide screen drags the page out from under a map that was deliberately pinned in place.
 *
 * REDUCED MOTION is honoured in both halves, from `useAppReducedMotion` (the OS query OR the app's own
 * Settings toggle — see that hook). The jump becomes instant rather than smooth, and the pulse is left
 * to the stylesheet, which already flattens every animation for those readers. The RING IS NOT AN
 * ANIMATION for exactly that reason: it is a static attribute-driven style, so the "which row" answer
 * survives a reader who has asked for no movement. A signal that only exists as motion is a signal
 * those readers never get.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * How long the flash attribute stays on the row.
 *
 * Long enough to be seen after a smooth scroll has finished (~300-400ms in a browser) and short enough
 * that it does not read as a persistent selected state — the row has its own, permanent, selected
 * styling, and two overlapping "this one" signals would be one too many.
 */
export const FLASH_MS = 1400;

/**
 * Clear space kept between the revealed row and the edge of its scroller, in pixels.
 *
 * A row flush against the top edge looks clipped and gives no hint that there is anything above it, so
 * "already visible" is judged with this much slack: a row peeking in by three pixels counts as out of
 * view and gets scrolled properly.
 */
const EDGE_PADDING = 24;

/** `visualViewport` where it exists, so an on-screen keyboard or a pinch-zoom is measured honestly. */
function viewportHeight(): number {
  return window.visualViewport?.height ?? window.innerHeight;
}

export type RevealRow = {
  /** Ref callback for each row: `ref={registerRow(key)}`. Passing null unregisters it. */
  registerRow: (key: string) => (node: HTMLElement | null) => void;
  /** Attach to the scroll container that holds the rows. Optional — falls back to the document. */
  containerRef: React.MutableRefObject<HTMLElement | null>;
  /** Scroll `key`'s row into view if needed and flash it. Safe to call for a key with no row. */
  reveal: (key: string | null | undefined) => void;
  /** The key currently flashing, for `data-flash={flashKey === key}`. */
  flashKey: string | null;
};

export function useRevealRow(): RevealRow {
  const reducedMotion = useAppReducedMotion();
  const rows = useRef(new Map<string, HTMLElement>());
  const containerRef = useRef<HTMLElement | null>(null);
  const [flashKey, setFlashKey] = useState<string | null>(null);
  const flashTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // The scroll is deferred by one frame (see `reveal`), and a component can unmount inside that frame.
  const frame = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (flashTimer.current) clearTimeout(flashTimer.current);
      if (frame.current !== null) cancelAnimationFrame(frame.current);
    },
    []
  );

  const registerRow = useCallback(
    (key: string) => (node: HTMLElement | null) => {
      if (node) rows.current.set(key, node);
      else rows.current.delete(key);
    },
    []
  );

  const reveal = useCallback(
    (key: string | null | undefined) => {
      if (!key) return;
      // Re-arm the flash even when the same key is revealed twice in a row: clearing it first means the
      // attribute goes false and true again, which is what restarts the CSS animation. Without this,
      // clicking the same dot twice pulses once.
      setFlashKey(null);
      if (flashTimer.current) clearTimeout(flashTimer.current);

      if (frame.current !== null) cancelAnimationFrame(frame.current);
      // ONE FRAME LATER, and it is load-bearing. `reveal` is called from an effect in the same commit
      // that may have just inserted a panel ABOVE these rows (selecting a pin opens its detail panel),
      // so measuring now would measure the layout the row is about to leave. A frame is also what lets
      // a freshly-expanded disclosure finish laying out before its parent row is measured.
      frame.current = requestAnimationFrame(() => {
        frame.current = null;
        const row = rows.current.get(key);
        if (row) scrollRowIntoView(row, containerRef.current, reducedMotion);
        setFlashKey(key);
        flashTimer.current = setTimeout(() => setFlashKey(null), FLASH_MS);
      });
    },
    [reducedMotion]
  );

  return { registerRow, containerRef, reveal, flashKey };
}

/**
 * Move `row` into view inside `container`, or inside the document when the container is not scrolling.
 *
 * The container test is `scrollHeight > clientHeight`, measured NOW rather than assumed from a
 * breakpoint: the same component renders as an independent scroller on a wide screen and as ordinary
 * page content on a phone, and a media query in JavaScript would be a second source of truth for the
 * layout that CSS already decides. A container that exists but is not overflowing takes the document
 * path, which is correct — nothing can scroll inside it.
 */
function scrollRowIntoView(
  row: HTMLElement,
  container: HTMLElement | null,
  reducedMotion: boolean
): void {
  const behavior: ScrollBehavior = reducedMotion ? "auto" : "smooth";

  if (container && container.scrollHeight > container.clientHeight + 1) {
    const box = container.getBoundingClientRect();
    /**
     * THE BAND THIS AIMS AT IS THE CONTAINER'S INTERSECTION WITH THE VIEWPORT, not the container itself.
     *
     * The pane is `sticky`, so until the reader has scrolled past the filters above it, its lower half
     * genuinely sits below the fold — on a short `lg` viewport that is most of it. Centring in the
     * container's own box would then place the row somewhere off screen and the reader would see the list
     * move and land on nothing. Clamping to the visible band keeps the whole calculation inside the
     * container's own scrollTop — no ancestor is ever scrolled, so the map beside it never moves.
     */
    const bandTop = Math.max(box.top, 0);
    const bandBottom = Math.min(box.bottom, viewportHeight());
    // No overlap at all — the pane is entirely off screen, so nothing it can do to itself will help. The
    // document path is the only honest answer, and `block: "nearest"` keeps that move minimal.
    if (bandBottom - bandTop < 1) {
      row.scrollIntoView({ behavior, block: "nearest", inline: "nearest" });
      return;
    }
    const rowBox = row.getBoundingClientRect();
    const above = rowBox.top < bandTop + EDGE_PADDING;
    const below = rowBox.bottom > bandBottom - EDGE_PADDING;
    // Already comfortably in view: flash it where it is. Scrolling anyway would move the reader's page
    // for no reason, which is the behaviour that makes this kind of feature feel broken.
    if (!above && !below) return;
    // Centre it in the visible band when it has to move at all. "Just barely into view" leaves the row
    // against an edge with its neighbours cut off, and the neighbours are the context that makes a count
    // mean anything.
    const bandCentre = (bandTop + bandBottom) / 2;
    const delta = rowBox.top + rowBox.height / 2 - bandCentre;
    const target = Math.max(
      0,
      Math.min(container.scrollTop + delta, container.scrollHeight - container.clientHeight)
    );
    container.scrollTo({ top: target, behavior });
    return;
  }

  // The document path. `block: "nearest"` is the whole reason to use the native call here: it scrolls
  // only if the row is outside the viewport, and only far enough — the same "up or down, as little as
  // possible" rule the container branch implements by hand.
  row.scrollIntoView({ behavior, block: "nearest", inline: "nearest" });
}
