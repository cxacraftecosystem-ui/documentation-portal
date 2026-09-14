"use client";

/**
 * "Measure this dimension off the photograph" — the DETERMINISTIC, on-device route to a product's or
 * a tool's length, breadth and height, and the primary one on both record forms.
 *
 * ── WHY THIS EXISTS AT ALL ────────────────────────────────────────────────────────────────────
 * Until now the only measuring aid on `ProductForm` and `ToolForm` was `GridMeasurement`, which
 * posts the photograph to `POST /media/analyze-measurement` and asks a vision model to ESTIMATE the
 * inches — the endpoint's own prompt uses that verb. That route costs money on every capture, needs
 * a connection it has no queue or retry behind, and cannot say how it arrived at a number. It is now
 * the FALLBACK. It is not deleted and it is not broken: it stays on both forms, below this panel,
 * labelled as an estimate that needs a connection, because a researcher whose object will not sit
 * flat on a sheet still has it.
 *
 * ── THE INSIGHT THAT MADE THIS CHEAP, AND IT IS ONE SENTENCE ──────────────────────────────────
 * A 1-INCH GRID SHEET IS ITSELF A PERFECT DETERMINISTIC REFERENCE. The grid route already asks the
 * researcher to lay the object on ruled paper and photograph it; that photograph is a reference
 * photograph, and it does not need a model to be read. Marking across N squares states a reference
 * of N inches exactly, `lib/photoMeasure.ts` returns the answer WITH its error bar, and the whole
 * computation is a ratio of two pixel distances running on this device. No model, no network, no
 * per-call cost, and a number a later reader can re-derive from the same photograph.
 *
 * ── IT NEVER WRITES A DIMENSION ───────────────────────────────────────────────────────────────
 * Every path here ends at a button the researcher presses. A machine-produced value is a PROPOSAL,
 * and the reason is `records.merge_field_provenance`: it stamps every changed field with the
 * `{by, byName, at}` of whoever pressed Save, so a number that filled itself in is stored asserting
 * that a named human measured it. Proposing is what makes that assertion true again. The only calls
 * out of this file are `onPropose` from a button's `onClick` and `onPhotoChange` reporting the
 * photograph up for the media batch.
 *
 * ── THE ASSUMPTION IS ON THE SCREEN, NOT ONLY IN THIS COMMENT ─────────────────────────────────
 * A ratio of pixel distances is the true length only when the reference and the object lie in one
 * flat plane square to the sensor. A grid sheet photographed from an angle is not that, and the
 * error is silent, plausible and proportional to the tilt. The amber card below says so every time
 * the two-mark method is selected, and the four-corner method — which corrects the tilt of that
 * plane exactly, using a block of the same grid squares as its known rectangle — sits beside it.
 *
 * ── WHY ZOOM IS PART OF THE MEASUREMENT ───────────────────────────────────────────────────────
 * Marks are stored in NATURAL IMAGE PIXELS, so zoom cannot move an answer. What zoom moves is how
 * precisely a fingertip can be aimed: a 4000 px photograph shown 400 px wide is displayed at 0.1, so
 * one screen pixel IS ten image pixels and the most careful mark is worth ±15 image px. Each mark
 * remembers the zoom it was placed at, the measurement takes the worst of them, and the error bar
 * visibly narrows as the researcher zooms in — which is true, and is the only honest way to reward
 * the care. Without it this panel would report a wider error bar than the vision model claims
 * confidence, and would deserve to lose to it.
 *
 * ── NO NETWORK, NO CANVAS READBACK, NO RE-ENCODING ────────────────────────────────────────────
 * Nothing here reads a pixel. It reads the geometry of where a person pointed. That is why it works
 * on a photograph taken thirty seconds ago in a courtyard with no signal — which is the whole reason
 * for preferring it — and why this repository's refusal to touch an original is not even in tension
 * with it.
 *
 * ── WHAT LIVES OUTSIDE THIS FILE, AND WHY ─────────────────────────────────────────────────────
 * Everything this panel DECIDES is in `components/media/recordMeasure.ts` (squares into a reference,
 * a measured value into a number a `Decimal(10, 2)` column will take) and `lib/photoMeasure.ts` (the
 * geometry). There is no React renderer in this repository's devDependencies, so a judgement written
 * inside JSX is only ever exercised by somebody looking at a screen — and every judgement here has a
 * broken state that looks exactly like its working one. What is left in this file is layout, pointer
 * arithmetic and the one button.
 */

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

/**
 * A dimension column this panel may propose into.
 *
 * `unit` is the unit the COLUMN is in, not the unit the reference was stated in — the conversion
 * between them is `proposalFor`'s job. `note` is for a column whose unit is not in its own name; the
 * tool form's unit-less `height` was the case that made this field exist, and it is no longer a
 * destination (see that form's `MEASURE_COLUMNS`), so nothing sets `note` today. It stays on the type
 * for the next column that needs it.
 */
export type MeasureColumn = { key: string; label: string; unit: LengthUnit; note?: string };

type MarkId = "refA" | "refB" | "c0" | "c1" | "c2" | "c3" | "tgtA" | "tgtB";

type Mark = {
  point: Point;
  /** The per-mark uncertainty in image pixels, from the zoom this mark was last positioned at. */
  sigma: number;
  /** False while the mark is still sitting where it was seeded — see {@link SEEDS}. */
  placed: boolean;
};

type Mode = "SCALE" | "RECTIFY";
type ReferenceKind = "GRID" | "KNOWN";

const SCALE_MARKS: MarkId[] = ["refA", "refB", "tgtA", "tgtB"];
const RECTIFY_MARKS: MarkId[] = ["c0", "c1", "c2", "c3", "tgtA", "tgtB"];

/**
 * The badge on each handle and the sentence a screen reader gets.
 *
 * EVERY HANDLE CARRIES ITS OWN NAME, so which mark is which never depends on where it happens to be
 * or on the colour it is drawn in.
 */
const MARK_LABELS: Record<MarkId, { badge: string; name: string }> = {
  refA: { badge: "R1", name: "Reference, first end" },
  refB: { badge: "R2", name: "Reference, second end" },
  c0: { badge: "1", name: "Rectangle corner 1" },
  c1: { badge: "2", name: "Rectangle corner 2, along the width edge from corner 1" },
  c2: { badge: "3", name: "Rectangle corner 3, diagonally opposite corner 1" },
  c3: { badge: "4", name: "Rectangle corner 4" },
  tgtA: { badge: "A", name: "The dimension, first end" },
  tgtB: { badge: "B", name: "The dimension, second end" }
};

/**
 * Where each mark starts, as a fraction of the photograph.
 *
 * SEEDED RATHER THAN EMPTY, because an empty viewport with an instruction to tap four times is a
 * state a researcher can get wrong and cannot see the shape of. Seeded marks show what is being asked
 * for immediately. They are also flagged unplaced, and NO MEASUREMENT IS SHOWN until every mark has
 * been moved or tapped into position — a reading taken off the default layout would be a confident
 * number about nothing at all.
 */
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

/**
 * Things a researcher has to hand when the photograph has no grid in it, with the sizes they are.
 *
 * Nothing is preselected: a reference the researcher did not choose is a reference nobody checked was
 * in the photograph.
 */
const LENGTH_PRESETS: { label: string; length: number; unit: LengthUnit }[] = [
  { label: "Scale card, 100 mm", length: 100, unit: "mm" },
  { label: "Steel rule, 300 mm", length: 300, unit: "mm" },
  { label: "A4 short edge, 210 mm", length: 210, unit: "mm" },
  { label: "₹5 coin, 23 mm", length: 23, unit: "mm" }
];

/** Known rectangles for the four-corner method when there is no grid to count squares of. */
const RECT_PRESETS: { label: string; width: number; height: number; unit: LengthUnit }[] = [
  { label: "A4 sheet", width: 210, height: 297, unit: "mm" },
  { label: "A5 sheet", width: 148, height: 210, unit: "mm" },
  { label: "Bank/ID card", width: 85.6, height: 54, unit: "mm" }
];

const UNIT_OPTIONS = (Object.keys(LENGTH_UNITS) as LengthUnit[]).map((unit) => ({ value: unit, label: unit }));

const PITCH_OPTIONS = GRID_PITCHES.map((pitch) => ({ value: pitch.id, label: pitch.label }));

const MIN_ZOOM = 1;
const MAX_ZOOM = 40;

/** A drag shorter than this is a tap — it places the active mark rather than panning the photograph. */
const TAP_SLOP_PX = 4;

/**
 * Claim a pointer, and survive a browser that will not give it up.
 *
 * `setPointerCapture` THROWS — `NotFoundError` — when the pointer id is no longer active, which
 * happens for real when a second finger lands in the same frame as the first one lifts. Unguarded,
 * that exception escapes a React event handler and unmounts the tree: a researcher mid-pinch would
 * lose the whole record form, unsaved. Capture is an optimisation here anyway — it keeps a drag
 * alive when the finger leaves the element — so failing to get it costs a slightly worse drag.
 */
function capturePointer(element: Element, pointerId: number): void {
  try {
    (element as HTMLElement).setPointerCapture(pointerId);
  } catch {
    /* see above — a drag without capture still works while the pointer stays over the element */
  }
}

export function RecordPhotoMeasure({
  columns,
  values,
  onPropose,
  onPhotoChange,
  disabled
}: {
  /** The dimension columns this form stores, in the order they appear on it. */
  columns: MeasureColumn[];
  /** What each of those boxes holds right now, so "this replaces it" can be said before it does. */
  values: Record<string, string>;
  /**
   * Write one dimension box. Called ONLY from a button the researcher pressed.
   *
   * THE THIRD ARGUMENT SAYS HOW THE NUMBER WAS ARRIVED AT, and it is not optional, because this panel
   * always knows: everything it can propose is `PHOTO_GEOMETRY`, by one of two techniques. It is
   * `lib/photoMeasure.ts`'s own {@link MeasurementMarker}, composed by `methodMarker` there rather
   * than assembled here, so the two record forms cannot spell `PHOTO_GEOMETRY` differently in a
   * string a database keeps for the life of the record.
   *
   * The caller collects it into the save body's `measurementMethods` (see
   * `components/forms/measurementMethods.ts`), where the marker is dropped again the moment the box
   * stops holding the number this panel put in it — a `PHOTO_GEOMETRY` claim on a number somebody
   * typed over is worse than no claim at all.
   */
  onPropose: (key: string, text: string, method: MeasurementMarker) => void;
  /**
   * The measurement photograph, so the parent can put it in the save's media batch.
   *
   * `isGrid` tells the parent which kind of evidence it is holding — a ruled sheet or an object with
   * a ruler beside it — so the caption it files the upload under says which.
   */
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

  // The object URL is created and revoked in ONE effect, which is what keeps the pair together: a
  // URL revoked anywhere else is a URL somebody has to remember to revoke. Nothing reads the bytes,
  // so this is only ever handed to an <img src>.
  useEffect(() => {
    if (!file) {
      setUrl(null);
      return;
    }
    const next = URL.createObjectURL(file);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [file]);

  /**
   * Report the photograph up, THROUGH A REF, whenever it or the reference kind changes.
   *
   * The callback is an inline arrow at both call sites, so listing it as a dependency would re-run
   * this on every render of the form — which on a form with a rich-text editor in it is every
   * keystroke.
   *
   * It fires once on mount with `null`, which is deliberate and harmless: the parent sets `null`
   * over `null` (React bails out) and, critically, does NOT mark the form dirty, because a blank
   * form announcing unsaved work before anybody has typed is what trains people to click through the
   * guard.
   */
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

  /** Screen pixels per image pixel at zoom 1 — the "fit the whole photograph in the box" scale. */
  const fit = useMemo(() => {
    if (!natural || !box.width || !box.height) return 0;
    return Math.min(box.width / natural.width, box.height / natural.height);
  }, [natural, box]);

  /** Screen pixels per image pixel right now. This is what decides how precise a mark can be. */
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

  // Re-centre whenever the photograph, the box or the fit changes. Without this the marks and the
  // image disagree the first time the panel is opened on a phone in landscape.
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

  /** Seed any mark this mode needs that does not exist yet, once the photograph's size is known. */
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
    // `displayScale` is deliberately absent: it changes on every pinch frame, and listing it would
    // re-seed marks mid-gesture. A seeded mark's sigma is replaced the instant it is actually placed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [natural, mode]);

  const setMark = useCallback(
    (id: MarkId, point: Point) => {
      if (!natural) return;
      // Clamped to the photograph: a mark dragged off the edge is a coordinate outside the image, and
      // a homography solved through one measures a plane that was never photographed.
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
    // A mark is a position on ONE photograph. Carrying it to another would put the reference
    // somewhere nobody chose, on an image of a different size.
    setMarks({});
    setActive(needed[0]);
  }

  /* ── Gestures on the photograph ───────────────────────────────────────── */

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
    // A tap on the photograph places the mark the researcher is currently working on and moves the
    // cursor to the next — the four-corner case is six marks, and reaching for a chip between each
    // would make it eleven actions instead of six.
    if (wasSingle && !gestureMoved.current && !dragging.current && natural) {
      setMark(active, viewToImage(localPoint(event)));
      const index = needed.indexOf(active);
      if (index >= 0 && index < needed.length - 1) setActive(needed[index + 1]);
    }
    gestureMoved.current = false;
  }

  /* ── One handle ───────────────────────────────────────────────────────── */

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
        aria-label={`${MARK_LABELS[id].name}${mark.placed ? "" : " — not placed yet"}. Drag it, or use the arrow keys to nudge it.`}
        aria-pressed={isActive}
        className={[
          "pointer-events-auto absolute grid h-8 w-8 -translate-x-1/2 -translate-y-1/2 place-items-center",
          "rounded-full text-[0.625rem] font-bold leading-none shadow-md transition-shadow",
          isTarget
            ? "border-2 border-purple-700 bg-card text-purple-800"
            : "border-2 border-card bg-purple-700 text-white",
          isActive ? "ring-4 ring-purple-600/30" : "",
          mark.placed ? "" : "opacity-70"
        ].join(" ")}
        style={{ left: `${view.x}px`, top: `${view.y}px` }}
        onPointerDown={(event) => {
          if (disabled) return;
          // The viewport would otherwise read this as the start of a pan, and the photograph would
          // slide out from under the mark being dragged.
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
          // THE KEYBOARD ROUTE, and it is not decoration: dragging is pointer-only, so without this a
          // researcher working from a laptop trackpad or an assistive device could see the marks and
          // never move one. A step is one IMAGE pixel — finer than a pointer can manage — and Shift
          // is ten, so crossing a 4000 px frame is possible without holding an arrow down.
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

  /* ── The measurement ──────────────────────────────────────────────────── */

  const allPlaced = needed.every((id) => marks[id]?.placed);

  /** The worst of the marks this measurement rests on — an error bar is only as good as its weakest. */
  const markSigmaPx = useMemo(() => {
    const sigmas = needed.map((id) => marks[id]?.sigma).filter((value): value is number => typeof value === "number");
    return sigmas.length ? Math.max(...sigmas) : undefined;
  }, [needed, marks]);

  /**
   * The reference for the two-mark method, and the reference for the four-corner one.
   *
   * TWO MEMOS AND NOT ONE UNION, deliberately. A single `reference` covering both methods would be a
   * union of a length-shaped answer and a rectangle-shaped one, and every read of it downstream
   * would need a cast the compiler could not check — which is precisely how a rectangle's `width`
   * ends up passed as a scale bar's `length`, producing a confident measurement of the wrong thing.
   * Two values, each with one shape, cost a few lines and make that unrepresentable.
   *
   * Kept out of {@link result} so a refusal ("Say how many grid squares…") can be printed even while
   * the marks are unfinished — the two things a researcher can be waiting on are different things and
   * are said differently.
   */
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

  /** Whichever of the two the selected method is reading — for the sentences, not for the maths. */
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
        // `referenceLengthSigma` is deliberately left at its zero default. A printed grid sheet and a
        // steel rule are exact to far better than a mark can be placed, and every preset offered here
        // is a manufactured object. The moment this panel offers "about the width of a brick", that
        // argument stops holding and the term has to be passed — `SameScaleInput`'s own comment says
        // so, and it is the largest term the error bar would then be missing.
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
          Photograph the object on the 1-inch grid sheet, mark across however many squares you like — that is the
          reference — then mark the two ends of the dimension. The answer is worked out on this device from where you
          pointed, with an error bar, and offered for you to accept into{" "}
          {columns.map((column) => column.label).join(", ")}. No connection needed, and no model involved. A ruler, a
          scale card or a sheet of A4 in the frame works just as well when there is no grid.
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

      {/* The photograph. Its own capture rather than a picker over the media attached lower down the
          form, because the first thing a researcher does on a blank record is measure the object in
          their hands — a control that first asked them to attach something would be a control they
          could not use yet. It is reported up and stored with the record either way, so the evidence
          the number was read off is kept. */}
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
            {file.name} — saved with this record when you save the form, so the measurement can be checked against the
            photograph it was taken from.
          </p>
        ) : (
          <p className="text-xs leading-5 text-ink-500">
            Take the photograph square-on to the sheet, with the whole object and enough of the grid in the frame.
          </p>
        )}
      </div>

      {!file ? null : (
        <>
          {/* Method. Two real buttons rather than a dropdown, because the choice IS the explanation
              and a researcher has to read both to make it. */}
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

          {/* THE ASSUMPTION, ON SCREEN. See the file header for why this is not a comment. */}
          {mode === "SCALE" ? (
            <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2.5 py-2 text-xs leading-5 text-amber-800">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                This is only true when the reference and the thing you are measuring lie in the{" "}
                <strong>same flat plane, square to the camera</strong>. A grid sheet on a table and a pot standing on
                that table are not in one plane, and a dimension measured across a tilted object comes out wrong — by
                however much the angle happens to be, with nothing later able to tell. If the photograph is at an
                angle, use the four-corner method instead.
              </span>
            </p>
          ) : (
            <p className="flex items-start gap-2 rounded-md border border-line-200 bg-card px-2.5 py-2 text-xs leading-5 text-ink-500">
              <Crosshair className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                Mark the four corners of the rectangle <strong>in order around it</strong> — 1, 2, 3, 4 walking round
                the edge, not in reading order. The tilt of that surface is then corrected for exactly. The object
                still has to be lying <strong>on</strong> that surface: what is corrected is the angle of the plane,
                not the height of something standing up off it.
              </span>
            </p>
          )}

          {/* Which mark the next tap moves. */}
          <div className="grid gap-1">
            <span className="field-label">Marks — tap the photograph to place, drag or use arrow keys to adjust</span>
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
            // Pointer events only arrive for pinch and pan if the browser is not already using the
            // gesture to scroll the page; on a handset that is exactly what it would do otherwise.
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

                {/* The lines between the marks, so a researcher can see what is being measured rather
                    than inferring it from six discs. Purely decorative — every mark is named on its
                    own handle. */}
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

            {/*
              POINTER EVENTS STOP HERE, and without it these two buttons do nothing at all.

              The viewport calls `setPointerCapture` on pointerdown so a pan or a pinch survives the
              finger leaving the box. Capture also RETARGETS the click that follows — Chromium fires
              it at the capturing element rather than at the button under the finger — so pressing
              Zoom in silently placed a mark on the photograph instead of zooming. It looked like a
              dead control. Stopping the event before the viewport sees it is the same guard each
              mark handle already uses.
            */}
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

          {/* The zoom readout is part of the measurement, not chrome — see the file header.
              `role=status` rather than a bare paragraph so the narrowing error bar is available to a
              reader who cannot see the marks move. */}
          <p role="status" className="text-xs leading-5 text-ink-500">
            Zoom {zoom.toFixed(1)}×.{" "}
            {displayScale > 0
              ? `At this zoom a mark can be placed to about ±${markSigmaForDisplayScale(displayScale).toFixed(1)} image pixels, which is what the error bar below is built from. Zooming in genuinely narrows it.`
              : "Loading the photograph…"}
          </p>

          {/* THE REFERENCE. The grid is first, and it is first because it is the one the programme's
              own sheet gives away for free — see the file header. */}
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
                    {/* A <span>, not a <label htmlFor>: `Dropdown` renders a <button>, and HTML-AAM
                        computes a button's name from its own contents, so a label could only point
                        at a control it cannot name. The question arrives through `ariaLabel` below
                        instead. */}
                    <span className="field-label">The sheet</span>
                    <div className="w-44">
                      <Dropdown
                        value={pitchId}
                        onChange={(value) => setPitchId(value as GridPitchId)}
                        options={PITCH_OPTIONS}
                        disabled={disabled}
                        ariaLabel="How big one grid square is"
                        // This dropdown changes the reading on the panel it sits in rather than
                        // filling in a form field, so focus must stay where the researcher is
                        // adjusting.
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
                {/* THE ARITHMETIC, READ BACK. The one mistake this path can make that nothing
                    downstream could catch is miscounting the squares or picking the wrong sheet —
                    both are visible the instant the multiplication is printed beside the paper. */}
                <p role="status" className="text-xs leading-5 text-ink-500">
                  {reference.ok
                    ? `Reference: ${reference.sentence}. Mark across as many squares as the photograph allows — a longer reference is a narrower error bar, in direct proportion.`
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
            // SAID ONCE, BESIDE THE BOX THAT FIXES IT. The grid branch already prints its own
            // refusal next to the squares input — which is where a researcher can act on it, and
            // where it is visible before the marks are finished. Passing it here as well would
            // print the same sentence twice on one panel, and a sentence a reader has learned to
            // see duplicated is a sentence they stop reading.
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

/* ────────────────────────────────────────────────────────────────────────────
 * The answer, its error bar, and the only place anything is written
 * ──────────────────────────────────────────────────────────────────────────── */

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
  /** See the same prop on {@link RecordPhotoMeasure} — this is the component that actually calls it. */
  onPropose: (key: string, text: string, method: MeasurementMarker) => void;
}) {
  if (!allPlaced) {
    const remaining = needed.filter((id) => !marks[id]?.placed);
    return (
      <p className="text-xs leading-5 text-ink-500">
        {remaining.length} of {needed.length} marks still to place ({remaining.map((id) => MARK_LABELS[id].badge).join(", ")}
        ). Nothing is measured until every mark is where it belongs — a reading off the marks as they were laid out
        would be a confident number about nothing.
      </p>
    );
  }

  if (referenceReason) {
    return <p className="text-xs leading-5 text-ink-500">{referenceReason}</p>;
  }

  if (!result) return null;

  if (!result.ok) {
    // A refusal, with its reason. It is not styled as an error because nothing has gone wrong with
    // the researcher's work — the marks simply do not support a number, and the sentence says which.
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
        That is ±{(result.relativeUncertainty * 100).toFixed(1)}%, from a reference {Math.round(result.referencePixels)}{" "}
        pixels long and an object {Math.round(result.targetPixels)} pixels long in this photograph. The error bar is how
        far the answer moves when each mark is nudged by the amount a mark can be placed to at this zoom — it is not a
        guess about the camera, and it does not include the grid being the wrong sheet.
      </p>

      {result.method === "RECTIFIED" && typeof result.tiltCorrection === "number" ? (
        <p className="text-xs leading-5 text-ink-500">
          Correcting for the tilt of that surface changed this by {(result.tiltCorrection * 100).toFixed(1)}%
          {typeof result.uncorrectedValue === "number"
            ? ` — two marks alone would have read ${result.uncorrectedValue.toFixed(1)} ${result.unit}`
            : ""}
          .{" "}
          {result.tiltCorrection < 0.01
            ? "That is small enough that the two-mark method would have done here."
            : "That is why the four corners were worth marking."}
        </p>
      ) : null}

      <div className="grid gap-1.5">
        <span className="field-label">Accept this into</span>
        <div className="flex flex-wrap gap-2">
          {columns.map((column) => {
            const proposal = proposalFor(result.value, result.uncertainty, result.unit, column.unit);
            // A column this cannot reach must not become a destination. It is a refusal rather than a
            // silent omission so nobody wonders where the button went.
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
                  // THE MARKER IS COMPOSED FROM THE SAME `result` THE BUTTON'S FIGURE CAME OUT OF,
                  // in the same expression, so the technique it names can never belong to a
                  // different measurement than the number beside it. `methodMarker` takes a
                  // `Measurement` and not a `MeasureResult` — a refusal has no method to describe —
                  // and `result` is narrowed to one by the `!result.ok` branch above.
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
                  <span className="text-xs leading-5 text-amber-800">Currently “{current}”. This replaces it.</span>
                ) : null}
              </div>
            );
          })}
        </div>
        <p className="text-xs leading-5 text-ink-500">
          The figure is rounded to the precision its own error bar reaches, because once it is in the box the error bar
          is gone — the record has a column for the dimension and none for the doubt, so the number of digits is the
          only thing left saying how well it was measured.
        </p>
      </div>
    </div>
  );
}
