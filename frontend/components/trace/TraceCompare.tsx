"use client";

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

export interface TraceCompareSolo extends TraceCompareImage {
  label?: string;
}

export interface TraceCompareProps {
  beforeImage: TraceCompareImage;
  afterImage: TraceCompareImage;
  beforeLabel?: string;
  afterLabel?: string;
  orientation?: "horizontal" | "vertical";
  initialPosition?: number;
  position?: number;
  onPosition?: (next: number) => void;
  maxZoom?: number;
  peekHoldMs?: number;
  soloImage?: TraceCompareSolo | null;
  aspectRatio?: number;
  showLabels?: boolean;
  dividerWidth?: number;
  className?: string;
  ariaLabel?: string;
}

const STEP = 2;

const ZOOM_STEP = 1.25;

const GRIP_HALF_PX = 20;

const clamp = (value: number) => Math.max(0, Math.min(100, value));

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
  const [frameSize, setFrameSize] = useState({ width: 0, height: 0 });
  const containerRef = useRef<HTMLDivElement>(null);

  const isHorizontal = orientation === "horizontal";
  const solo = soloImage ?? null;
  const zoomable = maxZoom > 1;

  const pointersRef = useRef(new Map<number, { x: number; y: number }>());
  const pinchRef = useRef<{ distance: number; centreX: number; centreY: number } | null>(null);
  const gestureRef = useRef<Gesture | null>(null);
  const peekTimerRef = useRef<number | null>(null);

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

  const frameRect = useCallback((): DOMRect | null => {
    const node = containerRef.current;
    if (node === null) return null;
    const rect = node.getBoundingClientRect();
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

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      event.preventDefault();
      const node = containerRef.current;
      const pointers = pointersRef.current;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      try {
        node?.setPointerCapture(event.pointerId);
      } catch {
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
      }
      cancelPeekTimer();

      const gesture = gestureRef.current;
      if (gesture !== null && gesture.kind === "press" && solo === null && transform.zoom <= 1) {
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

  useEffect(() => cancelPeekTimer, [cancelPeekTimer]);

  useEffect(() => {
    const node = containerRef.current;
    if (node === null || !zoomable) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      const rect = node.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) return;
      const factor = Math.exp(-event.deltaY / 180);
      setTransform((current) =>
        zoomAbout(current, factor, event.clientX - rect.left, event.clientY - rect.top, rect.width, rect.height, maxZoom)
      );
    };
    node.addEventListener("wheel", onWheel, { passive: false });
    return () => node.removeEventListener("wheel", onWheel);
  }, [maxZoom, zoomable]);

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

      if (solo !== null) return;

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

  const wrapperSeam = wrapperPercent(
    shownPosition,
    transform.zoom,
    isHorizontal ? transform.panX : transform.panY,
    isHorizontal ? frameSize.width : frameSize.height
  );
  const clipPath = isHorizontal
    ? `inset(0 ${100 - wrapperSeam}% 0 0)`
    : `inset(0 0 ${100 - wrapperSeam}% 0)`;

  const badgeClip = isHorizontal
    ? `inset(0 ${100 - shownPosition}% 0 0)`
    : `inset(0 0 ${100 - shownPosition}% 0)`;

  const afterBadgeClip = isHorizontal
    ? `inset(0 0 0 ${shownPosition}%)`
    : `inset(${shownPosition}% 0 0 0)`;

  const gripOffset = `clamp(${GRIP_HALF_PX}px, ${shownPosition}%, calc(100% - ${GRIP_HALF_PX}px))`;

  const shown = Math.round(shownPosition);

  const framed = typeof aspectRatio === "number" && Number.isFinite(aspectRatio) && aspectRatio > 0;

  const magnified = !isAtFit(transform);

  return (
    <section className={cn("w-full", className)}>
      <div className="grid gap-4">
        <div
          ref={containerRef}
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
            "focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2",
            solo !== null ? "cursor-default" : isHorizontal ? "cursor-ew-resize" : "cursor-ns-resize",
            magnified && "cursor-grab"
          )}
        >
          <div
            className="absolute inset-0"
            style={{
              transform: `translate(${transform.panX}px, ${transform.panY}px) scale(${transform.zoom})`,
              transformOrigin: "center"
            }}
          >
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

          {showLabels && solo !== null && solo.label ? (
            <span className="pointer-events-none absolute left-3 top-3 z-20 rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-xs">
              {solo.label}
            </span>
          ) : null}
          {showLabels && solo === null ? (
            <>
              <div className="pointer-events-none absolute inset-0 z-20" style={{ clipPath: badgeClip }}>
                <span className="absolute left-3 top-3 rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-xs">
                  {beforeLabel}
                </span>
              </div>
              <div className="pointer-events-none absolute inset-0 z-20" style={{ clipPath: afterBadgeClip }}>
                <span
                  className={cn(
                    "absolute rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-xs",
                    isHorizontal ? "right-3 top-3" : "bottom-3 left-3"
                  )}
                >
                  {afterLabel}
                </span>
              </div>
            </>
          ) : null}
        </div>

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
