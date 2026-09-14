"use client";

/**
 * `TraceCompare` — a before/after slider. Two images stacked, one clipped, and a handle deciding
 * where the clip falls.
 *
 * ── WHY THIS LIVES IN `components/trace/` AND NOT IN `components/ui/` ──────────────────────────
 *
 * It is a general-purpose primitive by shape and a single-purpose one by fact: its only caller is
 * `TracePanel`, every default it carries is that panel's (open on the after layer, peek the before
 * layer, magnify to six), and `components/ui/` is a directory other workstreams are editing. A new
 * file here collides with nobody. If a second caller ever appears, move it up a directory — that is a
 * rename and an import change, and nothing in this file assumes where it lives.
 *
 * ── ON `initialPosition`, BECAUSE THE OBVIOUS DEFAULT IS BACKWARDS FOR THIS USE ────────────────
 *
 * `position` clips the BEFORE layer: at 0 the before-image is fully clipped and the after-image is
 * what you see. So to open showing the TRACED result with the divider hard against the leading edge,
 * pass the trace as `afterImage` and `initialPosition={0}`. Dragging then reveals the original
 * underneath. Passing them the other way round is the mistake that makes the panel look as though the
 * trace never ran.
 *
 * ── ON BEING CONTROLLABLE ─────────────────────────────────────────────────────────────────────
 *
 * Three things need the seam to be the caller's: a mode control that writes it to an end (the
 * Drawing / Wipe / Photograph chips), a press-and-hold peek that must be able to leave the stored
 * seam alone, and a caller that wants to remember where the researcher left it across a re-trace.
 * {@link TraceCompareProps.position} and {@link TraceCompareProps.onPosition} are the controlled pair;
 * passing neither leaves the component holding its own seam, which is a legitimate simpler mount.
 *
 * ── ON `aspectRatio` ──────────────────────────────────────────────────────────────────────────
 *
 * Without one the frame is `aspect-video` and both layers are `object-cover`, which centre-crops most
 * of a photographed A4 sheet off the screen. The crop is identical on both layers, so the comparison
 * stays aligned — it just stops showing the drawing.
 *
 * It is a NUMBER applied as an inline style rather than a class, and that is not laziness. `cn()` in
 * `lib/utils.ts` is `filter(Boolean).join(" ")` and NOT `tailwind-merge`, so later classes do not win
 * and CSS source order decides — appending `aspect-[3/4]` to a container that already has
 * `aspect-video` is a coin toss settled by the order Tailwind happened to emit two utilities that set
 * the same property. An inline style has no such argument with anything. When it is given,
 * `aspect-video` is not emitted at all — and with the frame matching the source, `object-cover` crops
 * nothing.
 *
 * ── ON THE MAGNIFIER, AND THE ONE RULE IT MUST NOT BREAK ──────────────────────────────────────
 *
 * **ONE TRANSFORM, ONE WRAPPER, BOTH LAYERS INSIDE IT.** The two `<img>` elements carry no transform
 * of their own and never may: a comparison whose layers are scaled or panned independently does not
 * fail loudly, it shows a drawing that appears to have drifted off its own photograph, and the
 * researcher attributes the drift to the trace.
 *
 * That forces one piece of arithmetic, and it is the part worth reading before editing this file. The
 * SEAM lives in frame space — it must not pan away with the picture, or at 2x it would leave the frame
 * entirely with no way back — while the layer it clips lives inside the transform. So the clip is
 * converted once, by `compareTransform.wrapperPercent`, and the seam, the grip and the two corner
 * badges are drawn OUTSIDE the wrapper at the raw percentage. At fit the conversion is the identity.
 *
 * Why a magnifier at all: `comparisonPlates.ts` caps both plates at 1024 px and the panel renders them
 * into a card a few hundred CSS pixels wide, so a pencil line is sub-pixel — which means the failure
 * this comparator exists to catch is invisible at fit-to-screen.
 *
 * ── ON THE PRESS-AND-HOLD PEEK ────────────────────────────────────────────────────────────────
 *
 * A hold anywhere in the frame shows the before layer whole and letting go comes back. **The stored
 * seam is deliberately not rewritten** — that is the whole point of the gesture: the researcher keeps
 * their place. So the component holds the seam the caller gave it and a separate DISPLAYED value, and
 * only the second one moves.
 *
 * It also forces the press to stop writing the seam on contact: the seam is written on RELEASE for a
 * tap and continuously for a drag past `REVEAL_DRAG_SLOP_PX`. Writing it on contact would move the
 * seam under the finger that was about to peek, and releasing would restore a place the researcher
 * never chose.
 *
 * ── ON THERE BEING NO ANIMATION, WHICH IS A DECISION ──────────────────────────────────────────
 *
 * The same number is written by a pointer drag, four kinds of key press, a mode button and a hold —
 * and a transition that is right for the button lags the drag and stutters key repeat. Nothing is
 * lost by leaving it out: every signal in this comparator is static (the badges, the seam, the spoken
 * value), so there is nothing for either reduced-motion switch to have to turn off.
 *
 * ── ON THE TWO BADGES, WHICH ARE CLIPPED WITH THE LAYERS THEY NAME ────────────────────────────
 *
 * Pinned to the two top corners unconditionally, the before badge would sit over the after layer
 * calling it by the before layer's name at `position: 0` — which is exactly where this panel opens.
 * So each badge is inside a layer clipped exactly as its own image is: the before badge shares the
 * before layer's `clipPath`, the after badge gets the complement. A badge is therefore visible only
 * over the picture it names. Nothing decides this from a threshold — the two clips are the one number
 * `position`.
 *
 * ── ON THE GRIP, WHICH MUST NOT BE HALF-CLIPPED AT THE EDGES ──────────────────────────────────
 *
 * The frame is `overflow-hidden` and the grip is a 40px circle centred on the divider, so at
 * `position: 0` exactly half of it would be outside the frame and clipped away, leaving a 20px
 * half-disc at the extreme edge as the only pointer target. The DIVIDER still sits exactly on the clip
 * boundary, because moving it would make the seam disagree with the picture; the GRIP is positioned
 * separately and kept a grip's radius inside the frame with a CSS `clamp()`. Within 20px of either end
 * the grip and the seam are visibly apart by up to that much, which is the honest trade: a handle you
 * can grab everywhere, and a seam that is never drawn in the wrong place.
 */

import { GripHorizontal, GripVertical } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { cn } from "@/lib/utils";
import {
  REVEAL_AT_FIT,
  REVEAL_DRAG_SLOP_PX,
  clampPan,
  isAtFit,
  panBy,
  wrapperPercent,
  zoomAbout,
  zoomLabel,
  type RevealTransform
} from "./compareTransform";

export interface TraceCompareImage {
  src: string;
  alt: string;
}

/**
 * The one picture that replaces both layers, and the word that names it.
 *
 * ITS OWN TYPE RATHER THAN A `label` ON {@link TraceCompareImage}, because the two layers are named by
 * `beforeLabel` and `afterLabel` — props that exist because those two badges are clipped to the halves
 * of the frame their layers occupy, and a per-image label would sit beside them meaning something
 * subtly different. This one is not clipped by anything: there is one picture and it fills the frame.
 *
 * The label is OPTIONAL and a solo picture without one draws no badge.
 */
export interface TraceCompareSolo extends TraceCompareImage {
  /** Drawn at the leading corner while this picture is showing. See {@link TraceCompareSolo}. */
  label?: string;
}

export interface TraceCompareProps {
  beforeImage: TraceCompareImage;
  afterImage: TraceCompareImage;
  beforeLabel?: string;
  afterLabel?: string;
  orientation?: "horizontal" | "vertical";
  /** 0 shows `afterImage` in full with the divider at the leading edge. See the file note. */
  initialPosition?: number;
  /**
   * The seam, when the caller wants to own it. Percent, 0–100.
   *
   * CONTROLLED WHEN THIS IS A NUMBER, and then the component holds no seam of its own — the only
   * arrangement in which a mode button and a drag cannot end up disagreeing about where the join is.
   * Pair it with {@link onPosition}; supplying `position` without `onPosition` deliberately produces a
   * seam nothing can move, which is a legitimate read-only comparison and not a bug.
   */
  position?: number;
  /** Every seam change, from a drag, a key, or a tap. Never fired by the peek — see the file note. */
  onPosition?: (next: number) => void;
  /**
   * How far a researcher may magnify. 1 (the default) offers no magnifier at all.
   *
   * The cap belongs to the caller because this component has no idea how big its caller's pictures
   * are. The trace panel passes 6: beyond it a 1024 px plate is showing its own pixels.
   */
  maxZoom?: number;
  /** Milliseconds a still pointer must be held before the before layer is shown whole. 0 disables. */
  peekHoldMs?: number;
  /**
   * One picture instead of two, drawn through the SAME transform.
   *
   * For a derived plate that is neither layer — the trace panel's difference view. The seam, the grip
   * and the two LAYER badges are withdrawn while it is set, because there is nothing for them to
   * divide, and the peek is withdrawn with them: it is defined as "show the before layer", which is
   * not on screen. In their place this picture may carry one badge of its own — see
   * {@link TraceCompareSolo} — which is not clipped, because it names the whole frame. The zoom and
   * the pan survive the switch, so flipping to the difference and back keeps the researcher's
   * magnification and their place in the drawing.
   */
  soloImage?: TraceCompareSolo | null;
  aspectRatio?: number;
  showLabels?: boolean;
  dividerWidth?: number;
  className?: string;
  /** Accessible name. Without one it is announced as just "slider". */
  ariaLabel?: string;
}

/** How far one arrow press moves the divider, in percent. The Page keys move ten times as far. */
const STEP = 2;

/** What one press of the zoom keys, or one notch of a wheel, multiplies the magnification by. */
const ZOOM_STEP = 1.25;

/**
 * Half the grip's own size, in CSS pixels — `h-10 w-10` is 40px, so 20.
 *
 * The grip is never centred closer than this to an edge, so the whole circle stays inside an
 * `overflow-hidden` frame. Kept beside the class it is derived from: changing `h-10 w-10` without
 * changing this puts a sliver of the handle back outside the frame.
 */
const GRIP_HALF_PX = 20;

const clamp = (value: number) => Math.max(0, Math.min(100, value));

/** What a pointer gesture turned out to be. Decided on the first movement past the slop, never before. */
type Gesture = {
  kind: "press" | "seam" | "pan" | "peek";
  startX: number;
  startY: number;
  lastX: number;
  lastY: number;
};

export function TraceCompare({
  beforeImage,
  afterImage,
  beforeLabel = "Before",
  afterLabel = "After",
  orientation = "horizontal",
  initialPosition = 50,
  position,
  onPosition,
  maxZoom = 1,
  peekHoldMs = 0,
  soloImage = null,
  showLabels = true,
  dividerWidth = 4,
  className,
  ariaLabel,
  aspectRatio
}: TraceCompareProps) {
  const controlled = typeof position === "number" && Number.isFinite(position);
  const [held, setHeld] = useState(clamp(initialPosition));
  const seam = controlled ? clamp(position as number) : held;

  const [dragging, setDragging] = useState(false);
  const [peeking, setPeeking] = useState(false);
  const [transform, setTransform] = useState<RevealTransform>(REVEAL_AT_FIT);
  /**
   * The frame's own box, in CSS pixels, kept in state by the observer below.
   *
   * IN STATE AND NOT READ OFF THE NODE DURING RENDER, which is the obvious shortcut and is a layout
   * read in the middle of a render — the thing React's concurrent renderer is allowed to throw away
   * and redo. It is only ever CONSUMED while magnified: at fit `wrapperPercent` is the identity
   * whatever it is handed, so a zero here before the first measurement cannot draw a wrong seam.
   */
  const [frameSize, setFrameSize] = useState({ width: 0, height: 0 });
  const containerRef = useRef<HTMLDivElement>(null);

  const isHorizontal = orientation === "horizontal";
  const solo = soloImage ?? null;
  const zoomable = maxZoom > 1;

  /**
   * Every pointer currently down on the frame, so a second finger can be recognised as a pinch.
   *
   * A ref rather than state: it is read inside the handlers that write it, where a piece of state
   * would be the value from the render that installed them — which for a gesture that starts and ends
   * between two renders is always the empty map.
   */
  const pointersRef = useRef(new Map<number, { x: number; y: number }>());
  const pinchRef = useRef<{ distance: number; centreX: number; centreY: number } | null>(null);
  const gestureRef = useRef<Gesture | null>(null);
  const peekTimerRef = useRef<number | null>(null);

  /** What is actually on screen. Only the peek makes this differ from the stored seam. */
  const shownPosition = peeking ? 100 : seam;

  const commit = useCallback(
    (next: number) => {
      const value = clamp(next);
      if (!controlled) setHeld(value);
      onPosition?.(value);
    },
    [controlled, onPosition]
  );

  const cancelPeekTimer = useCallback(() => {
    if (peekTimerRef.current !== null) {
      window.clearTimeout(peekTimerRef.current);
      peekTimerRef.current = null;
    }
  }, []);

  /** The frame's box, or null before it has been laid out. Every gesture measures through this. */
  const frameRect = useCallback((): DOMRect | null => {
    const node = containerRef.current;
    if (node === null) return null;
    const rect = node.getBoundingClientRect();
    // Guard the divide. A container that has not been laid out yet has zero width, and 0/0 is NaN,
    // which would set the position to NaN and blank both layers with nothing on screen to explain it.
    if (rect.width === 0 || rect.height === 0) return null;
    return rect;
  }, []);

  const percentAt = useCallback(
    (clientX: number, clientY: number): number | null => {
      const rect = frameRect();
      if (rect === null) return null;
      return isHorizontal
        ? ((clientX - rect.left) / rect.width) * 100
        : ((clientY - rect.top) / rect.height) * 100;
    },
    [frameRect, isHorizontal]
  );

  const resetZoom = useCallback(() => setTransform(REVEAL_AT_FIT), []);

  /* ──────────────────────────────────────────────────────────────────────────
   * Pointer gestures
   * ────────────────────────────────────────────────────────────────────────── */

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      // ONE pointer handler, not a mouse one and a touch one. Registering both means a pen or a touch
      // that the browser also reports as a mouse runs the move twice for one gesture.
      event.preventDefault();
      const node = containerRef.current;
      const pointers = pointersRef.current;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      // POINTER CAPTURE RATHER THAN WINDOW LISTENERS. A pinch needs both pointers followed
      // individually, and `pointercancel` — an incoming call, a system gesture taking over — is
      // delivered to the capturing element, so the handle can never be left welded to a pointer that
      // no longer exists.
      try {
        node?.setPointerCapture(event.pointerId);
      } catch {
        // A pointer the browser has already released. Nothing to capture and nothing to report.
      }

      if (pointers.size === 2 && zoomable) {
        cancelPeekTimer();
        setPeeking(false);
        setDragging(false);
        gestureRef.current = null;
        const [a, b] = Array.from(pointers.values());
        pinchRef.current = {
          distance: Math.hypot(a.x - b.x, a.y - b.y),
          centreX: (a.x + b.x) / 2,
          centreY: (a.y + b.y) / 2
        };
        return;
      }
      if (pointers.size !== 1) return;

      gestureRef.current = {
        kind: "press",
        startX: event.clientX,
        startY: event.clientY,
        lastX: event.clientX,
        lastY: event.clientY
      };
      // The peek is armed on contact and only BECOMES a peek if nothing moved — see the file note on
      // why the seam is not written here.
      if (peekHoldMs > 0 && solo === null) {
        peekTimerRef.current = window.setTimeout(() => {
          peekTimerRef.current = null;
          const gesture = gestureRef.current;
          if (gesture === null || gesture.kind !== "press") return;
          gesture.kind = "peek";
          setPeeking(true);
        }, peekHoldMs);
      }
    },
    [cancelPeekTimer, peekHoldMs, solo, zoomable]
  );

  const onPointerMove = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const pointers = pointersRef.current;
      if (!pointers.has(event.pointerId)) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

      const rect = frameRect();
      if (rect === null) return;

      const pinch = pinchRef.current;
      if (pinch !== null && pointers.size >= 2) {
        const [a, b] = Array.from(pointers.values());
        const distance = Math.hypot(a.x - b.x, a.y - b.y);
        const centreX = (a.x + b.x) / 2;
        const centreY = (a.y + b.y) / 2;
        if (distance > 0 && pinch.distance > 0) {
          setTransform((current) => {
            // ZOOM FIRST, ABOUT THE MIDPOINT, THEN PAN BY HOW FAR THE MIDPOINT ITSELF TRAVELLED. Doing
            // it the other way round makes a two-finger drag that also spreads slightly overshoot,
            // because the pan would be applied at the old magnification and then scaled.
            const zoomed = zoomAbout(
              current,
              distance / pinch.distance,
              centreX - rect.left,
              centreY - rect.top,
              rect.width,
              rect.height,
              maxZoom
            );
            return panBy(zoomed, centreX - pinch.centreX, centreY - pinch.centreY, rect.width, rect.height);
          });
        }
        pinchRef.current = { distance, centreX, centreY };
        return;
      }

      const gesture = gestureRef.current;
      if (gesture === null || gesture.kind === "peek") return;

      if (gesture.kind === "press") {
        const travelled = Math.hypot(event.clientX - gesture.startX, event.clientY - gesture.startY);
        if (travelled < REVEAL_DRAG_SLOP_PX) return;
        cancelPeekTimer();
        // MAGNIFIED, THE FRAME IS A PAN SURFACE; AT FIT IT IS THE SEAM'S HANDLE. There is nothing to
        // pan at fit, and once a researcher has magnified into a corner of the drawing, dragging to
        // look around it is the gesture they mean — the seam still answers to the grip, the keys and
        // the chip row above.
        gesture.kind = solo !== null || transform.zoom > 1 ? "pan" : "seam";
        if (gesture.kind === "seam") setDragging(true);
      }

      if (gesture.kind === "seam") {
        const next = percentAt(event.clientX, event.clientY);
        if (next !== null) commit(next);
      } else if (gesture.kind === "pan") {
        const dx = event.clientX - gesture.lastX;
        const dy = event.clientY - gesture.lastY;
        setTransform((current) => panBy(current, dx, dy, rect.width, rect.height));
      }
      gesture.lastX = event.clientX;
      gesture.lastY = event.clientY;
    },
    [cancelPeekTimer, commit, frameRect, maxZoom, percentAt, solo, transform.zoom]
  );

  const endPointer = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const pointers = pointersRef.current;
      if (!pointers.has(event.pointerId)) return;
      pointers.delete(event.pointerId);
      try {
        containerRef.current?.releasePointerCapture(event.pointerId);
      } catch {
        // Already released — the browser does this itself on `pointercancel`.
      }
      cancelPeekTimer();

      const gesture = gestureRef.current;
      if (gesture !== null && gesture.kind === "press" && solo === null && transform.zoom <= 1) {
        // A TAP JUMPS THE SEAM. A researcher who wants "mostly photograph" should not have to drag
        // there from wherever the seam happens to be.
        const next = percentAt(event.clientX, event.clientY);
        if (next !== null) commit(next);
      }
      if (gesture !== null && gesture.kind === "peek") setPeeking(false);

      if (pointers.size < 2) pinchRef.current = null;
      if (pointers.size === 0) {
        gestureRef.current = null;
        setDragging(false);
      }
    },
    [cancelPeekTimer, commit, percentAt, solo, transform.zoom]
  );

  /** The timer outlives a fast unmount otherwise, and fires `setPeeking` into a dead component. */
  useEffect(() => cancelPeekTimer, [cancelPeekTimer]);

  /**
   * The wheel, as a NON-PASSIVE native listener rather than React's `onWheel`.
   *
   * React registers `wheel` on its root container as PASSIVE, so `preventDefault()` inside an
   * `onWheel` prop does nothing but log a console warning — the page scrolls anyway, and a trackpad
   * pinch zooms the whole browser instead of the drawing. This is the one listener in the component
   * that has to be attached by hand, and it is attached with `{ passive: false }` for exactly that.
   *
   * ONLY WITH A MODIFIER. A bare wheel over this frame must still scroll the page: a comparator that
   * swallowed the wheel would trap a reader halfway down a long panel. `ctrlKey` is what a trackpad
   * pinch reports on every platform, and `metaKey` covers the keyboard-plus-wheel habit on macOS.
   */
  useEffect(() => {
    const node = containerRef.current;
    if (node === null || !zoomable) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      const rect = node.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) return;
      // Exponential in the wheel's own units, so one notch of a mouse and one small trackpad gesture
      // both land somewhere sensible instead of a mouse jumping the whole range in one detent.
      const factor = Math.exp(-event.deltaY / 180);
      setTransform((current) =>
        zoomAbout(current, factor, event.clientX - rect.left, event.clientY - rect.top, rect.width, rect.height, maxZoom)
      );
    };
    node.addEventListener("wheel", onWheel, { passive: false });
    return () => node.removeEventListener("wheel", onWheel);
  }, [maxZoom, zoomable]);

  /**
   * The frame's box, measured — and the pan pulled back inside the overhang whenever it changes.
   *
   * A frame that shrinks under a magnified picture would otherwise leave the translation outside the
   * new overhang, which is a plate hanging half off its own window with nothing to drag it back by.
   */
  useEffect(() => {
    const node = containerRef.current;
    if (node === null || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const box = entries[0]?.contentRect;
      if (!box || box.width === 0 || box.height === 0) return;
      setFrameSize({ width: box.width, height: box.height });
      setTransform((current) => clampPan(current, box.width, box.height));
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      const key = event.key;

      // THE ZOOM KEYS ARE THE ONLY ROUTE IN FOR A KEYBOARD, and a magnifier reachable by pinch alone
      // is a magnifier a switch device and a keyboard user do not have. About the CENTRE, because
      // there is no pointer to be about.
      if (zoomable && (key === "+" || key === "=" || key === "-" || key === "_" || key === "0")) {
        event.preventDefault();
        if (key === "0") {
          resetZoom();
          return;
        }
        const rect = frameRect();
        const factor = key === "-" || key === "_" ? 1 / ZOOM_STEP : ZOOM_STEP;
        setTransform((current) =>
          zoomAbout(
            current,
            factor,
            (rect?.width ?? 0) / 2,
            (rect?.height ?? 0) / 2,
            rect?.width ?? 0,
            rect?.height ?? 0,
            maxZoom
          )
        );
        return;
      }

      // Nothing below this line applies while one derived picture is filling the frame: there is no
      // seam to move, and a key that silently did nothing would be worse than one that is not offered.
      if (solo !== null) return;

      // Both axes answer to both arrow pairs. A vertical slider driven only by up and down would be
      // technically correct and unhelpful: people reach for left and right on anything slider-shaped.
      let next: number | null = null;
      if (key === "ArrowLeft" || key === "ArrowUp") next = seam - STEP;
      else if (key === "ArrowRight" || key === "ArrowDown") next = seam + STEP;
      else if (key === "PageUp") next = seam - STEP * 10;
      else if (key === "PageDown") next = seam + STEP * 10;
      else if (key === "Home") next = 0;
      else if (key === "End") next = 100;
      if (next === null) return;
      event.preventDefault();
      commit(next);
    },
    [commit, frameRect, maxZoom, resetZoom, seam, solo, zoomable]
  );

  /* ──────────────────────────────────────────────────────────────────────────
   * Geometry
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * The before layer's clip, in WRAPPER space — the one place the two coordinate systems meet.
   *
   * `wrapperPercent` is the identity at fit, so an unmagnified comparator emits exactly the string an
   * un-zoomable one would. See the file note for why the seam itself stays in frame space.
   */
  const wrapperSeam = wrapperPercent(
    shownPosition,
    transform.zoom,
    isHorizontal ? transform.panX : transform.panY,
    isHorizontal ? frameSize.width : frameSize.height
  );
  const clipPath = isHorizontal
    ? `inset(0 ${100 - wrapperSeam}% 0 0)`
    : `inset(0 0 ${100 - wrapperSeam}% 0)`;

  /** The frame-space clip, which is what the badges are cut by — they are pinned to the frame's corners. */
  const badgeClip = isHorizontal
    ? `inset(0 ${100 - shownPosition}% 0 0)`
    : `inset(0 0 ${100 - shownPosition}% 0)`;

  /** The complement of {@link badgeClip}: everything the before layer is NOT covering. */
  const afterBadgeClip = isHorizontal
    ? `inset(0 0 0 ${shownPosition}%)`
    : `inset(${shownPosition}% 0 0 0)`;

  /**
   * Where the grip's centre goes — the divider's position, held a grip's radius inside the frame.
   *
   * `clamp()` with a percentage and two pixel bounds is doing arithmetic this component cannot do in
   * JS: the frame's width in pixels is not known at this line, and measuring it would mean an
   * observer and a re-render per resize for a purely visual inset.
   */
  const gripOffset = `clamp(${GRIP_HALF_PX}px, ${shownPosition}%, calc(100% - ${GRIP_HALF_PX}px))`;

  const shown = Math.round(shownPosition);

  // A non-finite or non-positive ratio would produce `aspect-ratio: NaN`, which collapses the frame to
  // nothing and takes both layers with it. Falling back to the class default keeps a bad number from
  // costing the whole comparison.
  const framed = typeof aspectRatio === "number" && Number.isFinite(aspectRatio) && aspectRatio > 0;

  const magnified = !isAtFit(transform);

  return (
    <section className={cn("w-full", className)}>
      <div className="grid gap-4">
        <div
          ref={containerRef}
          /*
            THE ROLE FOLLOWS WHAT IS IN THE FRAME. Two layers and a seam is a slider; one derived
            picture is not, and a frame that kept `role="slider"` with nothing to slide would be
            advertising a role it does not honour. `group` because the frame still owns keys (the zoom
            keys) and still contains an image with its own description.

            THE NAME DOES NOT CHANGE WITH IT. It is the same frame showing the same comparison from a
            different angle, and a control that renamed itself under a screen reader every time a chip
            was pressed would read as four different controls appearing and disappearing. What is IN
            it is named by the image's own `alt`.
          */
          role={solo === null ? "slider" : "group"}
          aria-label={ariaLabel ?? `${beforeLabel} and ${afterLabel} comparison`}
          aria-valuemin={solo === null ? 0 : undefined}
          aria-valuemax={solo === null ? 100 : undefined}
          aria-valuenow={solo === null ? shown : undefined}
          aria-valuetext={solo === null ? `${shown}% ${beforeLabel}, ${100 - shown}% ${afterLabel}` : undefined}
          aria-orientation={solo === null ? (isHorizontal ? "horizontal" : "vertical") : undefined}
          tabIndex={0}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endPointer}
          onPointerCancel={endPointer}
          style={
            framed
              ? { aspectRatio, touchAction: zoomable ? "none" : undefined }
              : { touchAction: zoomable ? "none" : undefined }
          }
          onKeyDown={onKeyDown}
          className={cn(
            "panel relative w-full select-none overflow-hidden rounded-lg",
            framed ? null : "aspect-video",
            "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2",
            solo !== null ? "cursor-default" : isHorizontal ? "cursor-ew-resize" : "cursor-ns-resize",
            magnified && "cursor-grab"
          )}
        >
          {/*
            THE ONE TRANSFORMED WRAPPER. Both layers are inside it and neither carries a transform of
            its own, so they cannot be scaled or panned apart — see the file note. At fit the string is
            an identity and the browser composites it away.
          */}
          <div
            className="absolute inset-0"
            style={{
              transform: `translate(${transform.panX}px, ${transform.panY}px) scale(${transform.zoom})`,
              transformOrigin: "center"
            }}
          >
            {/*
              RAW `<img>` AND NOT `next/image`, on purpose. Both sources here are object URLs produced
              on the device — a freshly traced canvas and the photograph it came from — so there is no
              remote host for `next.config.ts`'s `remotePatterns` to allow and nothing for the
              optimiser to fetch. The rule is right for remote media and inapplicable to a blob.
            */}
            {solo !== null ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img className="absolute inset-0 size-full object-cover" src={solo.src} alt={solo.alt} />
            ) : (
              <>
                <div className="absolute inset-0">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img className="absolute inset-0 size-full object-cover" src={afterImage.src} alt={afterImage.alt} />
                </div>

                <div className="absolute inset-0" style={{ clipPath }}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img className="absolute inset-0 size-full object-cover" src={beforeImage.src} alt={beforeImage.alt} />
                </div>
              </>
            )}
          </div>

          {solo === null ? (
            <>
              {/* The seam, exactly on the clip boundary — see the file note on the grip. */}
              <div
                aria-hidden
                className={cn(
                  "absolute z-10 bg-white shadow-lg",
                  isHorizontal ? "bottom-0 top-0 -translate-x-1/2" : "left-0 right-0 -translate-y-1/2"
                )}
                style={{
                  [isHorizontal ? "left" : "top"]: `${shownPosition}%`,
                  [isHorizontal ? "width" : "height"]: `${dividerWidth}px`
                }}
              />

              {/* The grip, a sibling of the seam rather than its child, so it can be held inside the
                  frame without dragging the seam off the boundary with it. */}
              <div
                aria-hidden
                className={cn(
                  "absolute z-10 flex h-10 w-10 -translate-x-1/2 -translate-y-1/2",
                  "items-center justify-center rounded-full border-2 border-white bg-primary",
                  "shadow-lg transition-transform",
                  dragging && "scale-110"
                )}
                style={isHorizontal ? { left: gripOffset, top: "50%" } : { top: gripOffset, left: "50%" }}
              >
                {isHorizontal ? (
                  <GripVertical className="h-5 w-5 text-white" />
                ) : (
                  <GripHorizontal className="h-5 w-5 text-white" />
                )}
              </div>
            </>
          ) : null}

          {/*
            THE SOLO BADGE, WHICH IS NOT CLIPPED BY ANYTHING.

            One picture fills the frame, so there is no half to cut it to and no wrong thing for it to
            caption — the whole reason the two badges below carry a `clipPath` does not arise. Same
            corner as `beforeLabel`, because it replaces both of them rather than joining them.

            WHY A DERIVED PLATE IS WORTH A WORD ON IT AT ALL: the panel's difference plate is very
            nearly black when the trace is GOOD, and a nearly black frame with nothing written on it is
            indistinguishable from a picture that failed to load. The badge is what tells those two
            apart without leaving the frame.
          */}
          {showLabels && solo !== null && solo.label ? (
            <span className="pointer-events-none absolute left-3 top-3 z-20 rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-sm">
              {solo.label}
            </span>
          ) : null}
          {/*
            EACH BADGE IS CLIPPED WITH THE LAYER IT NAMES, IN FRAME SPACE and not the wrapper's: a
            badge is pinned to the frame's corner and does not pan with the picture, so cutting it by
            the wrapper's seam would leave it captioning the wrong half the moment anything was
            magnified.

            `pointer-events-none` on the wrappers, because a badge that swallowed `pointerdown` would
            make the one corner of the frame it covers refuse to move the divider.
          */}
          {showLabels && solo === null ? (
            <>
              <div className="pointer-events-none absolute inset-0 z-20" style={{ clipPath: badgeClip }}>
                <span className="absolute left-3 top-3 rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-sm">
                  {beforeLabel}
                </span>
              </div>
              <div className="pointer-events-none absolute inset-0 z-20" style={{ clipPath: afterBadgeClip }}>
                <span
                  className={cn(
                    "absolute rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-sm",
                    isHorizontal ? "right-3 top-3" : "bottom-3 left-3"
                  )}
                >
                  {afterLabel}
                </span>
              </div>
            </>
          ) : null}
        </div>

        {/*
          THE MAGNIFICATION READOUT IS A BUTTON, AND IT IS OUTSIDE THE FRAME.

          Outside, because the frame carries `role="slider"` and a focusable control inside a slider is
          a control a keyboard user reaches by tabbing INTO a widget that owns its own keys — and
          because a badge over the drawing covers the drawing, which is the whole complaint against
          putting anything on the picture.

          A button rather than a read-only "pinch out to fit" badge: a phone can pinch back to fit and
          a mouse cannot, so the affordance that is honest on a handset is a dead end here. Same class
          of divergence as "Save" against "Download" in the export row.
        */}
        {magnified ? (
          <div className="flex items-center gap-2">
            <button
              type="button"
              className="rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300"
              onClick={resetZoom}
            >
              {zoomLabel(transform.zoom)} — reset to fit
            </button>
            <span className="text-xs text-ink-500">Drag the picture to move around it.</span>
          </div>
        ) : null}
      </div>
    </section>
  );
}
