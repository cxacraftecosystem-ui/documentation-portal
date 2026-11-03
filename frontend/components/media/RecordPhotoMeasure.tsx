"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Camera, Check, Crosshair, Grid3x3, Ruler, X, ZoomIn, ZoomOut } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";
import {
  DEFAULT_GRID_PITCH_ID,
  GRID_PITCHES,
  gridPitchById,
  gridRectangle,
  gridSpan,
  proposalFor,
  statedLength,
  type GridPitchId
} from "@/components/media/recordMeasure";
import {
  LENGTH_UNITS,
  markSigmaForDisplayScale,
  measureByRectification,
  measureBySameScale,
  methodMarker,
  roundToUncertainty,
  type LengthUnit,
  type MeasurementMarker,
  type MeasureResult,
  type Point
} from "@/lib/photoMeasure";

export type MeasureColumn = { key: string; label: string; unit: LengthUnit; note?: string };

type MarkId = "refA" | "refB" | "c0" | "c1" | "c2" | "c3" | "tgtA" | "tgtB";

type Mark = {
  point: Point;
  sigma: number;
  placed: boolean;
};

type Mode = "SCALE" | "RECTIFY";
type ReferenceKind = "GRID" | "KNOWN";

const SCALE_MARKS: MarkId[] = ["refA", "refB", "tgtA", "tgtB"];
const RECTIFY_MARKS: MarkId[] = ["c0", "c1", "c2", "c3", "tgtA", "tgtB"];

const MARK_LABELS: Record<MarkId, { badge: string; name: string }> = {
  refA: { badge: "R1", name: "Reference, first end" },
  refB: { badge: "R2", name: "Reference, second end" },
  c0: { badge: "1", name: "Rectangle corner 1" },
  c1: { badge: "2", name: "Rectangle corner 2 (width edge)" },
  c2: { badge: "3", name: "Rectangle corner 3 (opposite 1)" },
  c3: { badge: "4", name: "Rectangle corner 4" },
  tgtA: { badge: "A", name: "The dimension, first end" },
  tgtB: { badge: "B", name: "The dimension, second end" }
};

const SEEDS: Record<MarkId, Point> = {
  refA: { x: 0.14, y: 0.84 },
  refB: { x: 0.52, y: 0.84 },
  c0: { x: 0.2, y: 0.2 },
  c1: { x: 0.8, y: 0.22 },
  c2: { x: 0.82, y: 0.78 },
  c3: { x: 0.18, y: 0.76 },
  tgtA: { x: 0.3, y: 0.42 },
  tgtB: { x: 0.72, y: 0.44 }
};

const LENGTH_PRESETS: { label: string; length: number; unit: LengthUnit }[] = [
  { label: "Scale card, 100 mm", length: 100, unit: "mm" },
  { label: "Steel rule, 300 mm", length: 300, unit: "mm" },
  { label: "A4 short edge, 210 mm", length: 210, unit: "mm" },
  { label: "₹5 coin, 23 mm", length: 23, unit: "mm" }
];

const RECT_PRESETS: { label: string; width: number; height: number; unit: LengthUnit }[] = [
  { label: "A4 sheet", width: 210, height: 297, unit: "mm" },
  { label: "A5 sheet", width: 148, height: 210, unit: "mm" },
  { label: "Bank/ID card", width: 85.6, height: 54, unit: "mm" }
];

const UNIT_OPTIONS = (Object.keys(LENGTH_UNITS) as LengthUnit[]).map((unit) => ({ value: unit, label: unit }));

const PITCH_OPTIONS = GRID_PITCHES.map((pitch) => ({ value: pitch.id, label: pitch.label }));

const MIN_ZOOM = 1;
const MAX_ZOOM = 40;

const TAP_SLOP_PX = 4;

function capturePointer(element: Element, pointerId: number): void {
  try {
    (element as HTMLElement).setPointerCapture(pointerId);
  } catch {
  }
}

export function RecordPhotoMeasure({
  columns,
  values,
  onPropose,
  onPhotoChange,
  disabled
}: {
  columns: MeasureColumn[];
  values: Record<string, string>;
  onPropose: (key: string, text: string, method: MeasurementMarker) => void;
  onPhotoChange: (photo: { file: File; isGrid: boolean } | null) => void;
  disabled?: boolean;
}) {
  const panelId = useId();
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [mode, setMode] = useState<Mode>("SCALE");
  const [referenceKind, setReferenceKind] = useState<ReferenceKind>("GRID");
  const [marks, setMarks] = useState<Partial<Record<MarkId, Mark>>>({});
  const [active, setActive] = useState<MarkId>("refA");
  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null);
  const [box, setBox] = useState<{ width: number; height: number }>({ width: 0, height: 0 });
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState<Point>({ x: 0, y: 0 });
  const [pitchId, setPitchId] = useState<GridPitchId>(DEFAULT_GRID_PITCH_ID);
  const [squares, setSquares] = useState("");
  const [rectWidthSquares, setRectWidthSquares] = useState("");
  const [rectHeightSquares, setRectHeightSquares] = useState("");
  const [referenceLength, setReferenceLength] = useState("");
  const [referenceUnit, setReferenceUnit] = useState<LengthUnit>("mm");
  const [rectWidth, setRectWidth] = useState("");
  const [rectHeight, setRectHeight] = useState("");
  const [rectUnit, setRectUnit] = useState<LengthUnit>("mm");

  const viewportRef = useRef<HTMLDivElement | null>(null);
  const pointers = useRef(new Map<number, Point>());
  const pinchDistance = useRef<number | null>(null);
  const dragging = useRef<MarkId | null>(null);
  const gestureMoved = useRef(false);

  const pitch = gridPitchById(pitchId);
  const needed = mode === "SCALE" ? SCALE_MARKS : RECTIFY_MARKS;

  useEffect(() => {
    if (!file) {
      setUrl(null);
      return;
    }
    const next = URL.createObjectURL(file);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [file]);

  const reportPhoto = useRef(onPhotoChange);
  useEffect(() => {
    reportPhoto.current = onPhotoChange;
  });
  useEffect(() => {
    reportPhoto.current(file ? { file, isGrid: referenceKind === "GRID" } : null);
  }, [file, referenceKind]);

  useEffect(() => {
    const node = viewportRef.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect;
      if (rect) setBox({ width: rect.width, height: rect.height });
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [open, url]);

  const fit = useMemo(() => {
    if (!natural || !box.width || !box.height) return 0;
    return Math.min(box.width / natural.width, box.height / natural.height);
  }, [natural, box]);

  const displayScale = fit * zoom;

  const clampPan = useCallback(
    (next: Point, atZoom: number): Point => {
      if (!natural || !fit) return next;
      const width = natural.width * fit * atZoom;
      const height = natural.height * fit * atZoom;
      const clampAxis = (value: number, span: number, container: number) =>
        span <= container ? (container - span) / 2 : Math.min(0, Math.max(container - span, value));
      return { x: clampAxis(next.x, width, box.width), y: clampAxis(next.y, height, box.height) };
    },
    [natural, fit, box]
  );

  useEffect(() => {
    setZoom(1);
    setPan(clampPan({ x: 0, y: 0 }, 1));
  }, [clampPan]);

  const imageToView = useCallback(
    (point: Point): Point => ({ x: pan.x + point.x * displayScale, y: pan.y + point.y * displayScale }),
    [pan, displayScale]
  );

  const viewToImage = useCallback(
    (point: Point): Point => ({ x: (point.x - pan.x) / displayScale, y: (point.y - pan.y) / displayScale }),
    [pan, displayScale]
  );

  const localPoint = useCallback((event: { clientX: number; clientY: number }): Point => {
    const rect = viewportRef.current?.getBoundingClientRect();
    return { x: event.clientX - (rect?.left ?? 0), y: event.clientY - (rect?.top ?? 0) };
  }, []);

  useEffect(() => {
    if (!natural) return;
    setMarks((current) => {
      let changed = false;
      const next = { ...current };
      for (const id of needed) {
        if (next[id]) continue;
        next[id] = {
          point: { x: SEEDS[id].x * natural.width, y: SEEDS[id].y * natural.height },
          sigma: markSigmaForDisplayScale(displayScale),
          placed: false
        };
        changed = true;
      }
      return changed ? next : current;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [natural, mode]);

  const setMark = useCallback(
    (id: MarkId, point: Point) => {
      if (!natural) return;
      const clamped = {
        x: Math.min(natural.width, Math.max(0, point.x)),
        y: Math.min(natural.height, Math.max(0, point.y))
      };
      setMarks((current) => ({
        ...current,
        [id]: { point: clamped, sigma: markSigmaForDisplayScale(displayScale), placed: true }
      }));
    },
    [natural, displayScale]
  );

  const zoomBy = useCallback(
    (factor: number, centre?: Point) => {
      setZoom((currentZoom) => {
        const nextZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, currentZoom * factor));
        const anchor = centre ?? { x: box.width / 2, y: box.height / 2 };
        setPan((currentPan) =>
          clampPan(
            {
              x: anchor.x - ((anchor.x - currentPan.x) * nextZoom) / currentZoom,
              y: anchor.y - ((anchor.y - currentPan.y) * nextZoom) / currentZoom
            },
            nextZoom
          )
        );
        return nextZoom;
      });
    },
    [box, clampPan]
  );

  function choosePhoto(next: File | null) {
    setFile(next);
    setNatural(null);
    setMarks({});
    setActive(needed[0]);
  }

  function onViewportPointerDown(event: React.PointerEvent<HTMLDivElement>) {
    if (disabled) return;
    capturePointer(event.currentTarget, event.pointerId);
    pointers.current.set(event.pointerId, localPoint(event));
    gestureMoved.current = false;
    if (pointers.current.size === 2) {
      const [a, b] = Array.from(pointers.current.values());
      pinchDistance.current = Math.hypot(b.x - a.x, b.y - a.y);
    }
  }

  function onViewportPointerMove(event: React.PointerEvent<HTMLDivElement>) {
    if (!pointers.current.has(event.pointerId)) return;
    const previous = pointers.current.get(event.pointerId)!;
    const current = localPoint(event);
    pointers.current.set(event.pointerId, current);

    if (pointers.current.size >= 2) {
      const [a, b] = Array.from(pointers.current.values());
      const distance = Math.hypot(b.x - a.x, b.y - a.y);
      const previousDistance = pinchDistance.current;
      pinchDistance.current = distance;
      gestureMoved.current = true;
      if (previousDistance && previousDistance > 0 && distance > 0) {
        zoomBy(distance / previousDistance, { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
      }
      return;
    }

    const dx = current.x - previous.x;
    const dy = current.y - previous.y;
    if (Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX) gestureMoved.current = true;
    setPan((currentPan) => clampPan({ x: currentPan.x + dx, y: currentPan.y + dy }, zoom));
  }

  function onViewportPointerUp(event: React.PointerEvent<HTMLDivElement>) {
    const wasSingle = pointers.current.size === 1;
    pointers.current.delete(event.pointerId);
    if (pointers.current.size < 2) pinchDistance.current = null;
    if (wasSingle && !gestureMoved.current && !dragging.current && natural) {
      setMark(active, viewToImage(localPoint(event)));
      const index = needed.indexOf(active);
      if (index >= 0 && index < needed.length - 1) setActive(needed[index + 1]);
    }
    gestureMoved.current = false;
  }

  function handleFor(id: MarkId) {
    const mark = marks[id];
    if (!mark || !natural || !fit) return null;
    const view = imageToView(mark.point);
    const isTarget = id === "tgtA" || id === "tgtB";
    const isActive = active === id;
    return (
      <button
        key={id}
        type="button"
        disabled={disabled}
        aria-label={`${MARK_LABELS[id].name}${mark.placed ? "" : " — not placed"}. Drag or use arrow keys.`}
        aria-pressed={isActive}
        className={[
          "pointer-events-auto absolute grid h-8 w-8 -translate-x-1/2 -translate-y-1/2 place-items-center",
          "rounded-full text-[0.625rem] font-bold leading-none shadow-md transition-shadow",
          isTarget
            ? "border-2 border-purple-700 bg-card text-purple-800 dark:text-purple-300"
            : "border-2 border-card bg-purple-700 text-white",
          isActive ? "ring-4 ring-purple-600/30" : "",
          mark.placed ? "" : "opacity-70"
        ].join(" ")}
        style={{ left: `${view.x}px`, top: `${view.y}px` }}
        onPointerDown={(event) => {
          if (disabled) return;
          event.stopPropagation();
          capturePointer(event.currentTarget, event.pointerId);
          dragging.current = id;
          setActive(id);
        }}
        onPointerMove={(event) => {
          if (dragging.current !== id) return;
          event.stopPropagation();
          setMark(id, viewToImage(localPoint(event)));
        }}
        onPointerUp={(event) => {
          if (dragging.current !== id) return;
          event.stopPropagation();
          dragging.current = null;
        }}
        onPointerCancel={() => {
          if (dragging.current === id) dragging.current = null;
        }}
        onKeyDown={(event) => {
          const step = event.shiftKey ? 10 : 1;
          const delta: Record<string, Point> = {
            ArrowLeft: { x: -step, y: 0 },
            ArrowRight: { x: step, y: 0 },
            ArrowUp: { x: 0, y: -step },
            ArrowDown: { x: 0, y: step }
          };
          const move = delta[event.key];
          if (!move) return;
          event.preventDefault();
          setMark(id, { x: mark.point.x + move.x, y: mark.point.y + move.y });
        }}
      >
        <span aria-hidden>{MARK_LABELS[id].badge}</span>
      </button>
    );
  }

  const allPlaced = needed.every((id) => marks[id]?.placed);

  const markSigmaPx = useMemo(() => {
    const sigmas = needed.map((id) => marks[id]?.sigma).filter((value): value is number => typeof value === "number");
    return sigmas.length ? Math.max(...sigmas) : undefined;
  }, [needed, marks]);

  const scaleReference = useMemo(
    () => (referenceKind === "GRID" ? gridSpan(squares, pitch) : statedLength(referenceLength, referenceUnit)),
    [referenceKind, squares, pitch, referenceLength, referenceUnit]
  );

  const rectReference = useMemo(() => {
    if (referenceKind === "GRID") return gridRectangle(rectWidthSquares, rectHeightSquares, pitch);
    const width = statedLength(rectWidth, rectUnit);
    if (!width.ok) return { ok: false as const, reason: `Width: ${width.reason}` };
    const height = statedLength(rectHeight, rectUnit);
    if (!height.ok) return { ok: false as const, reason: `Height: ${height.reason}` };
    return {
      ok: true as const,
      width: width.length,
      height: height.length,
      unit: rectUnit,
      sentence: `${width.sentence} × ${height.sentence}`
    };
  }, [referenceKind, rectWidthSquares, rectHeightSquares, pitch, rectWidth, rectHeight, rectUnit]);

  const reference = mode === "SCALE" ? scaleReference : rectReference;
  const referenceReason = reference.ok ? null : reference.reason;

  const result: MeasureResult | null = useMemo(() => {
    if (!allPlaced || !markSigmaPx) return null;
    const target = { from: marks.tgtA!.point, to: marks.tgtB!.point };
    if (mode === "SCALE") {
      if (!scaleReference.ok) return null;
      return measureBySameScale({
        reference: {
          from: marks.refA!.point,
          to: marks.refB!.point,
          length: scaleReference.length,
          unit: scaleReference.unit
        },
        target,
        markSigmaPx
      });
    }
    if (!rectReference.ok) return null;
    return measureByRectification({
      corners: [marks.c0!.point, marks.c1!.point, marks.c2!.point, marks.c3!.point],
      rectangle: { width: rectReference.width, height: rectReference.height, unit: rectReference.unit },
      target,
      markSigmaPx
    });
  }, [allPlaced, markSigmaPx, marks, mode, scaleReference, rectReference]);

  if (!columns.length) return null;

  if (!open) {
    return (
      <section className="grid gap-2 rounded-lg border border-line-200 bg-surface-50 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Ruler className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <h3 className="font-display text-lg font-bold text-ink-900">Measure from a photograph</h3>
        </div>
        <p className="text-sm leading-6 text-ink-500">
          Photograph the object on the grid sheet (or beside a ruler), mark a reference, then the two ends. Works
          offline.
        </p>
        <div>
          <button
            type="button"
            className="field-button"
            disabled={disabled}
            aria-expanded={false}
            onClick={() => setOpen(true)}
          >
            <Ruler className="h-4 w-4" aria-hidden />
            Measure from a photograph
          </button>
        </div>
      </section>
    );
  }

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-surface-50 p-4" id={panelId}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-2">
          <Ruler className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <h3 className="font-display text-lg font-bold text-ink-900">Measure from a photograph</h3>
        </span>
        <button
          type="button"
          className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
          aria-expanded
          aria-controls={panelId}
          onClick={() => setOpen(false)}
        >
          <X className="h-3 w-3" aria-hidden />
          Close
        </button>
      </div>

      <div className="grid gap-1">
        <span className="field-label">Photograph</span>
        <input
          className="field-input"
          type="file"
          accept="image/*"
          capture="environment"
          aria-label="Photograph to measure from"
          disabled={disabled}
          onChange={(event) => choosePhoto(event.target.files?.[0] ?? null)}
        />
        {file ? (
          <p className="text-xs leading-5 text-ink-500">
            <Camera className="mr-1 inline h-3 w-3" aria-hidden />
            {file.name} — saved with this record.
          </p>
        ) : (
          <p className="text-xs leading-5 text-ink-500">
            Shoot square-on, with the object and grid in frame.
          </p>
        )}
      </div>

      {!file ? null : (
        <>
          <div className="grid gap-1">
            <span className="field-label">Method</span>
            <div className="flex flex-wrap gap-2">
              {(
                [
                  ["SCALE", "Same plane (2 marks)"],
                  ["RECTIFY", "Tilted — rectify a rectangle (4 corners)"]
                ] as [Mode, string][]
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={mode === value}
                  disabled={disabled}
                  className={
                    mode === value
                      ? "inline-flex min-h-10 items-center rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white"
                      : "inline-flex min-h-10 items-center rounded-md border border-line-200 bg-card px-3 py-2 text-sm font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                  }
                  onClick={() => {
                    setMode(value);
                    setActive(value === "SCALE" ? "refA" : "c0");
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          {mode === "SCALE" ? (
            <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2.5 py-2 text-xs leading-5 text-amber-800">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                Only accurate when the reference and object lie <strong>flat, square to the camera</strong>. If the
                photo is angled, use four corners.
              </span>
            </p>
          ) : (
            <p className="flex items-start gap-2 rounded-md border border-line-200 bg-card px-2.5 py-2 text-xs leading-5 text-ink-500">
              <Crosshair className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                Mark the corners <strong>in order around the edge</strong>. The object must lie flat on that surface.
              </span>
            </p>
          )}

          <div className="grid gap-1">
            <span className="field-label">Marks — tap to place, drag to adjust</span>
            <div className="flex flex-wrap gap-1.5">
              {needed.map((id) => {
                const mark = marks[id];
                return (
                  <button
                    key={id}
                    type="button"
                    aria-pressed={active === id}
                    disabled={disabled}
                    className={
                      active === id
                        ? "rounded-full bg-purple-700 px-3 py-1 text-xs font-medium text-white"
                        : "rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                    }
                    onClick={() => setActive(id)}
                  >
                    {MARK_LABELS[id].badge} · {mark?.placed ? "placed" : "not placed"}
                  </button>
                );
              })}
            </div>
          </div>

          <div
            ref={viewportRef}
            className="relative h-72 w-full overflow-hidden rounded-md border border-line-200 bg-field-100 sm:h-96"
            style={{ touchAction: "none" }}
            onPointerDown={onViewportPointerDown}
            onPointerMove={onViewportPointerMove}
            onPointerUp={onViewportPointerUp}
            onPointerCancel={onViewportPointerUp}
            onWheel={(event) => {
              if (disabled) return;
              event.preventDefault();
              zoomBy(event.deltaY < 0 ? 1.12 : 1 / 1.12, localPoint(event));
            }}
          >
            {url ? (
              <>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={url}
                  alt="Place marks on this photograph to measure a dimension"
                  draggable={false}
                  className="pointer-events-none absolute left-0 top-0 max-w-none select-none"
                  style={
                    natural && fit
                      ? {
                          width: `${natural.width * displayScale}px`,
                          height: `${natural.height * displayScale}px`,
                          transform: `translate(${pan.x}px, ${pan.y}px)`
                        }
                      : { visibility: "hidden" }
                  }
                  onLoad={(event) => {
                    const image = event.currentTarget;
                    if (image.naturalWidth && image.naturalHeight) {
                      setNatural({ width: image.naturalWidth, height: image.naturalHeight });
                    }
                  }}
                />

                {natural && fit ? (
                  <svg aria-hidden className="pointer-events-none absolute inset-0 h-full w-full" role="presentation">
                    {mode === "SCALE" && marks.refA && marks.refB ? (
                      <line
                        x1={imageToView(marks.refA.point).x}
                        y1={imageToView(marks.refA.point).y}
                        x2={imageToView(marks.refB.point).x}
                        y2={imageToView(marks.refB.point).y}
                        className="stroke-purple-700"
                        strokeWidth={2}
                        strokeDasharray="6 4"
                      />
                    ) : null}
                    {mode === "RECTIFY" && marks.c0 && marks.c1 && marks.c2 && marks.c3 ? (
                      <polygon
                        points={[marks.c0, marks.c1, marks.c2, marks.c3]
                          .map((mark) => {
                            const view = imageToView(mark.point);
                            return `${view.x},${view.y}`;
                          })
                          .join(" ")}
                        className="fill-purple-700/10 stroke-purple-700"
                        strokeWidth={2}
                        strokeDasharray="6 4"
                      />
                    ) : null}
                    {marks.tgtA && marks.tgtB ? (
                      <line
                        x1={imageToView(marks.tgtA.point).x}
                        y1={imageToView(marks.tgtA.point).y}
                        x2={imageToView(marks.tgtB.point).x}
                        y2={imageToView(marks.tgtB.point).y}
                        className="stroke-purple-700"
                        strokeWidth={3}
                      />
                    ) : null}
                  </svg>
                ) : null}

                <div className="pointer-events-none absolute inset-0">{needed.map((id) => handleFor(id))}</div>
              </>
            ) : null}

            <div className="absolute bottom-2 right-2 flex gap-1" onPointerDown={(event) => event.stopPropagation()}>
              <button
                type="button"
                aria-label="Zoom out"
                className="grid h-9 w-9 place-items-center rounded-md border border-line-200 bg-card text-ink-900 shadow-sm"
                onClick={() => zoomBy(1 / 1.5)}
              >
                <ZoomOut className="h-4 w-4" aria-hidden />
              </button>
              <button
                type="button"
                aria-label="Zoom in"
                className="grid h-9 w-9 place-items-center rounded-md border border-line-200 bg-card text-ink-900 shadow-sm"
                onClick={() => zoomBy(1.5)}
              >
                <ZoomIn className="h-4 w-4" aria-hidden />
              </button>
            </div>
          </div>

          <p role="status" className="text-xs leading-5 text-ink-500">
            Zoom {zoom.toFixed(1)}×.{" "}
            {displayScale > 0
              ? `Mark precision ±${markSigmaForDisplayScale(displayScale).toFixed(1)} px. Zoom in to improve it.`
              : "Loading the photograph…"}
          </p>

          <div className="grid gap-2">
            <span className="field-label">What is the reference?</span>
            <div className="flex flex-wrap gap-2">
              {(
                [
                  ["GRID", "Grid squares in the photograph"],
                  ["KNOWN", "Something of a known size"]
                ] as [ReferenceKind, string][]
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={referenceKind === value}
                  disabled={disabled}
                  className={
                    referenceKind === value
                      ? "inline-flex min-h-10 items-center gap-2 rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white"
                      : "inline-flex min-h-10 items-center gap-2 rounded-md border border-line-200 bg-card px-3 py-2 text-sm font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                  }
                  onClick={() => setReferenceKind(value)}
                >
                  {value === "GRID" ? <Grid3x3 className="h-4 w-4" aria-hidden /> : <Ruler className="h-4 w-4" aria-hidden />}
                  {label}
                </button>
              ))}
            </div>

            {referenceKind === "GRID" ? (
              <div className="grid gap-2">
                <div className="flex flex-wrap items-end gap-2">
                  <div className="grid gap-1">
                    <span className="field-label">The sheet</span>
                    <div className="w-44">
                      <Dropdown
                        value={pitchId}
                        onChange={(value) => setPitchId(value as GridPitchId)}
                        options={PITCH_OPTIONS}
                        disabled={disabled}
                        ariaLabel="How big one grid square is"
                        advanceOnSelect={false}
                      />
                    </div>
                  </div>
                  {mode === "SCALE" ? (
                    <div className="grid gap-1">
                      <span className="field-label">Squares between R1 and R2</span>
                      <input
                        className="field-input w-32"
                        type="number"
                        step="any"
                        min={0}
                        inputMode="decimal"
                        aria-label="How many grid squares the reference mark spans"
                        value={squares}
                        disabled={disabled}
                        onChange={(event) => setSquares(event.target.value)}
                      />
                    </div>
                  ) : (
                    <>
                      <div className="grid gap-1">
                        <span className="field-label">Squares across (1 → 2)</span>
                        <input
                          className="field-input w-28"
                          type="number"
                          step="any"
                          min={0}
                          inputMode="decimal"
                          aria-label="How many grid squares from corner 1 to corner 2"
                          value={rectWidthSquares}
                          disabled={disabled}
                          onChange={(event) => setRectWidthSquares(event.target.value)}
                        />
                      </div>
                      <div className="grid gap-1">
                        <span className="field-label">Squares down (2 → 3)</span>
                        <input
                          className="field-input w-28"
                          type="number"
                          step="any"
                          min={0}
                          inputMode="decimal"
                          aria-label="How many grid squares from corner 2 to corner 3"
                          value={rectHeightSquares}
                          disabled={disabled}
                          onChange={(event) => setRectHeightSquares(event.target.value)}
                        />
                      </div>
                    </>
                  )}
                </div>
                <p role="status" className="text-xs leading-5 text-ink-500">
                  {reference.ok
                    ? `Reference: ${reference.sentence}. Longer is more accurate.`
                    : reference.reason}
                </p>
              </div>
            ) : mode === "SCALE" ? (
              <div className="grid gap-2">
                <div className="flex flex-wrap gap-1.5">
                  {LENGTH_PRESETS.map((preset) => (
                    <button
                      key={preset.label}
                      type="button"
                      className="rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                      disabled={disabled}
                      onClick={() => {
                        setReferenceLength(String(preset.length));
                        setReferenceUnit(preset.unit);
                      }}
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
                <div className="flex flex-wrap items-end gap-2">
                  <input
                    className="field-input w-32"
                    type="number"
                    step="any"
                    min={0}
                    inputMode="decimal"
                    aria-label="Reference length"
                    value={referenceLength}
                    disabled={disabled}
                    onChange={(event) => setReferenceLength(event.target.value)}
                  />
                  <div className="w-24">
                    <Dropdown
                      value={referenceUnit}
                      onChange={(value) => setReferenceUnit(value as LengthUnit)}
                      options={UNIT_OPTIONS}
                      disabled={disabled}
                      ariaLabel="Reference unit"
                      advanceOnSelect={false}
                    />
                  </div>
                </div>
              </div>
            ) : (
              <div className="grid gap-2">
                <div className="flex flex-wrap gap-1.5">
                  {RECT_PRESETS.map((preset) => (
                    <button
                      key={preset.label}
                      type="button"
                      className="rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                      disabled={disabled}
                      onClick={() => {
                        setRectWidth(String(preset.width));
                        setRectHeight(String(preset.height));
                        setRectUnit(preset.unit);
                      }}
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
                <div className="flex flex-wrap items-end gap-2">
                  <input
                    className="field-input w-28"
                    type="number"
                    step="any"
                    min={0}
                    inputMode="decimal"
                    aria-label="Rectangle width, from corner 1 to corner 2"
                    value={rectWidth}
                    disabled={disabled}
                    onChange={(event) => setRectWidth(event.target.value)}
                  />
                  <span aria-hidden className="pb-2.5 text-sm text-ink-500">
                    ×
                  </span>
                  <input
                    className="field-input w-28"
                    type="number"
                    step="any"
                    min={0}
                    inputMode="decimal"
                    aria-label="Rectangle height, from corner 2 to corner 3"
                    value={rectHeight}
                    disabled={disabled}
                    onChange={(event) => setRectHeight(event.target.value)}
                  />
                  <div className="w-24">
                    <Dropdown
                      value={rectUnit}
                      onChange={(value) => setRectUnit(value as LengthUnit)}
                      options={UNIT_OPTIONS}
                      disabled={disabled}
                      ariaLabel="Rectangle unit"
                      advanceOnSelect={false}
                    />
                  </div>
                </div>
                <p className="text-xs leading-5 text-ink-500">
                  The width is the edge from corner 1 to corner 2; the height is from corner 2 to corner 3. Getting the
                  two the wrong way round measures a real rectangle that is not the one in the photograph.
                </p>
              </div>
            )}
          </div>

          <MeasurementReadout
            result={result}
            allPlaced={allPlaced}
            needed={needed}
            marks={marks}
            referenceReason={referenceKind === "GRID" ? null : referenceReason}
            columns={columns}
            values={values}
            disabled={disabled}
            onPropose={onPropose}
          />
        </>
      )}
    </section>
  );
}

function MeasurementReadout({
  result,
  allPlaced,
  needed,
  marks,
  referenceReason,
  columns,
  values,
  disabled,
  onPropose
}: {
  result: MeasureResult | null;
  allPlaced: boolean;
  needed: MarkId[];
  marks: Partial<Record<MarkId, Mark>>;
  referenceReason: string | null;
  columns: MeasureColumn[];
  values: Record<string, string>;
  disabled?: boolean;
  onPropose: (key: string, text: string, method: MeasurementMarker) => void;
}) {
  if (!allPlaced) {
    const remaining = needed.filter((id) => !marks[id]?.placed);
    return (
      <p className="text-xs leading-5 text-ink-500">
        Place {remaining.length} more ({remaining.map((id) => MARK_LABELS[id].badge).join(", ")}).
      </p>
    );
  }

  if (referenceReason) {
    return <p className="text-xs leading-5 text-ink-500">{referenceReason}</p>;
  }

  if (!result) return null;

  if (!result.ok) {
    return (
      <p
        role="status"
        className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2.5 py-2 text-xs leading-5 text-amber-800"
      >
        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>{result.reason}</span>
      </p>
    );
  }

  const shown = roundToUncertainty(result.value, result.uncertainty);
  const shownDoubt = roundToUncertainty(result.uncertainty, result.uncertainty);

  return (
    <div className="grid gap-2 rounded-md border border-purple-300 bg-card p-3">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500">Measured — not saved yet</p>
      <p className="font-display text-2xl font-bold text-ink-900">
        {shown.value.toFixed(shown.decimals)} ± {shownDoubt.value.toFixed(shownDoubt.decimals)} {result.unit}
      </p>
      <p className="text-xs leading-5 text-ink-500">
        ±{(result.relativeUncertainty * 100).toFixed(1)}% from mark precision.
      </p>

      {result.method === "RECTIFIED" && typeof result.tiltCorrection === "number" ? (
        <p className="text-xs leading-5 text-ink-500">
          Correcting for the tilt of that surface changed this by {(result.tiltCorrection * 100).toFixed(1)}%
          {typeof result.uncorrectedValue === "number"
            ? ` — two marks alone would have read ${result.uncorrectedValue.toFixed(1)} ${result.unit}`
            : ""}
          .{" "}
          {result.tiltCorrection < 0.01 ? "Two marks would have been enough." : null}
        </p>
      ) : null}

      <div className="grid gap-1.5">
        <span className="field-label">Accept this into</span>
        <div className="flex flex-wrap gap-2">
          {columns.map((column) => {
            const proposal = proposalFor(result.value, result.uncertainty, result.unit, column.unit);
            if (!proposal.ok) {
              return (
                <span key={column.key} className="max-w-md text-xs leading-5 text-ink-500">
                  {column.label}: {proposal.reason}
                </span>
              );
            }
            const current = values[column.key]?.trim();
            return (
              <div key={column.key} className="grid max-w-xs gap-1">
                <button
                  type="button"
                  className="field-button"
                  disabled={disabled}
                  onClick={() => onPropose(column.key, proposal.text, methodMarker(result))}
                >
                  <Check className="h-4 w-4" aria-hidden />
                  {column.label}: {proposal.text} {proposal.unit}
                </button>
                {proposal.clamped ? (
                  <span className="text-xs leading-5 text-ink-500">
                    Rounded to {proposal.decimals} decimal places, which is all this box stores — the measurement was
                    finer than that.
                  </span>
                ) : null}
                {column.note ? <span className="text-xs leading-5 text-ink-500">{column.note}</span> : null}
                {current ? (
                  <span className="text-xs leading-5 text-amber-800 dark:text-amber-200">Currently “{current}”. This replaces it.</span>
                ) : null}
              </div>
            );
          })}
        </div>
        <p className="text-xs leading-5 text-ink-500">
          The figure is rounded to the precision its own error bar reaches, because the record keeps the number but not
          its margin of error, so the number of digits is the only thing left saying how well it was measured.
        </p>
      </div>
    </div>
  );
}
