import { GrayF, Mask, Px } from './buffers';
import { fitPath } from './bezierFit';
import { Contour, trace as traceContours, traceOuterOnly } from './contourTrace';
import {
  FillRule,
  LineCap,
  LineJoin,
  VecPath,
  VecPoint,
  VecShape,
  vecShape,
  vecStyle,
} from './path';
import { douglasPeucker, removeCollinear } from './simplify';
import {
  DEFAULT_CHAIN_PARAMS,
  chain as chainSkeleton,
  trace as traceSkeleton,
} from './skeletonTrace';
import { chaikin, movingAverage } from './smooth';
import { variableWidthOutline } from './strokeStyle';

/**
 * Mask to vector shapes. See ALGORITHMS.md §10.
 *
 * Choosing between the two modes is the most consequential decision in the app:
 *  - CENTERLINE traces the skeleton into **open polylines**; one stroke of the original becomes one path.
 *    This is what CNC, embroidery, pen plotters and single-stroke styles need.
 *  - OUTLINE traces region boundaries into **closed paths**; every filled region becomes a filled shape.
 *    This is what stencils, silhouettes, laser cutting, vinyl and colouring pages need.
 */

export enum VectorMode {
  CENTERLINE = 'CENTERLINE',
  OUTLINE = 'OUTLINE',
}

export interface VectorizeParams {
  readonly mode: VectorMode;
  readonly simplifyEpsilon: number;
  readonly fitError: number;
  readonly cornerThresholdDegrees: number;
  readonly smoothIterations: number;
  readonly minPathLength: number;
  readonly strokeWidth: number;
  readonly modulateWidth: boolean;
  readonly widthScale: number;
  readonly minWidth: number;
  readonly maxWidth: number;
  /**
   * **The distinction between a colouring page and a silhouette.** In {@link VectorMode.OUTLINE} a
   * traced region can be presented two ways, and confusing them produces the two worst outputs this
   * stage is capable of:
   *
   *  - `false` — *an outline you fill in yourself.* Every boundary is traced, holes included, and
   *    every one is emitted as a **stroked** closed path with no fill. This is a colouring page, a
   *    cut file and a transfer pattern: the interior stays paper, and the bands and motifs inside a
   *    shape survive as their own closed paths for a child to colour or a blade to follow.
   *  - `true` — *the region is the mark.* Only **outer** boundaries are traced and each is
   *    **painted**. This is a silhouette, a woodcut mass, a comic black.
   *
   * Holes are deliberately not traced in the painted case. A {@link VecPath} is one subpath, so a
   * hole cannot be expressed as part of the shape that contains it; emitting it as a separate
   * painted path fills the hole with ink, which is how a colouring book came out as a solid black
   * silhouette with every interior detail gone. Not tracing a hole loses the white; painting it
   * loses the white *and* costs a path. Between "a solid region has no interior" and "a solid region
   * has an interior painted the same colour", only the first is a defensible reading.
   */
  readonly fillRegions: boolean;
  /**
   * Link polylines that continue through a junction, so one contour comes back as one path rather
   * than one path per graph edge. See {@link module:skeletonTrace.chain}; only meaningful in
   * {@link VectorMode.CENTERLINE}.
   */
  readonly chainStrokes: boolean;
  /** See `ChainParams.maxTurnDegrees`. */
  readonly chainMaxTurnDegrees: number;
  /** See `ChainParams.tangentSpan`. */
  readonly chainTangentSpan: number;
  /** See `ChainParams.minBranchLength`. */
  readonly chainPruneBranch: number;
  /** See `ChainParams.maxNodeDegree`. */
  readonly chainMaxNodeDegree: number;
}

export const DEFAULT_VECTORIZE_PARAMS: VectorizeParams = {
  mode: VectorMode.CENTERLINE,
  simplifyEpsilon: 1.0,
  fitError: 1.6,
  cornerThresholdDegrees: 100,
  smoothIterations: 1,
  minPathLength: 3,
  strokeWidth: 1.6,
  modulateWidth: false,
  widthScale: 1,
  minWidth: 0.4,
  maxWidth: 12,
  fillRegions: false,
  chainStrokes: true,
  chainMaxTurnDegrees: 55,
  chainTangentSpan: 6,
  chainPruneBranch: 0,
  chainMaxNodeDegree: 4,
};

/** @returns {@link DEFAULT_VECTORIZE_PARAMS} with `over` applied. */
export function vectorizeParams(over: Partial<VectorizeParams> = {}): VectorizeParams {
  return { ...DEFAULT_VECTORIZE_PARAMS, ...over };
}

/** Moving-average window for width modulation; the reference value from ALGORITHMS.md §10.7. */
const WIDTH_SMOOTH_WINDOW = 5;

function polylineLength(points: readonly VecPoint[], closed: boolean): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    total += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
  }
  if (closed && points.length > 1) {
    const a = points[points.length - 1];
    const b = points[0];
    total += Math.hypot(b.x - a.x, b.y - a.y);
  }
  return total;
}

/**
 * Chaikin, then the collinear merge, then Douglas-Peucker.
 *
 * The order is load-bearing. Chaikin runs **before** DP because smoothing after simplification just
 * re-adds the points DP removed; the collinear merge runs before DP because it costs one pass and roughly
 * halves DP's input on axis-aligned artwork.
 */
function prepare(
  points: readonly VecPoint[],
  closed: boolean,
  params: VectorizeParams,
): VecPoint[] {
  let pts: VecPoint[] = points.slice();
  if (params.smoothIterations > 0 && pts.length >= 3) {
    pts = chaikin(pts, params.smoothIterations, closed);
  }
  pts = removeCollinear(pts, 0.05);
  if (params.simplifyEpsilon > 0) pts = douglasPeucker(pts, params.simplifyEpsilon);
  return pts;
}

/**
 * Vectorise a binary mask.
 *
 * @param mask              the cleaned-up ink mask; in CENTERLINE mode it is expected to be a 1 px skeleton
 * @param distanceTransform required when `params.modulateWidth` is true; must be the DT of the
 *                          **pre-skeleton** mask, because the distance from a skeleton pixel to the
 *                          nearest background pixel is the stroke's half-width. Passing null with
 *                          `modulateWidth` on silently falls back to a uniform width rather than throwing.
 * @returns shapes in the mask's own pixel coordinates. Paths whose length is below
 *          `params.minPathLength` are dropped; the caller is expected to report the count.
 */
export function run(
  mask: Mask,
  params: VectorizeParams,
  distanceTransform: GrayF | null = null,
): VecShape[] {
  return params.mode === VectorMode.OUTLINE
    ? runOutline(mask, params)
    : runCenterline(mask, params, distanceTransform);
}

/**
 * How many paths the last {@link run} discarded for being shorter than `minPathLength`.
 *
 * Returned out of band rather than thrown away: a trace that silently dropped 4 000 short paths and one
 * that found nothing look identical on screen, and that ambiguity is the bug class this project takes
 * most seriously. {@link module:pipeline} turns this into a sentence in `TraceResult.notes`.
 */
export interface RunStats {
  readonly emitted: number;
  readonly dropped: number;
}

let lastStats: RunStats = { emitted: 0, dropped: 0 };

/** @returns statistics for the most recent {@link run} on this thread. */
export function lastRunStats(): RunStats {
  return lastStats;
}

function runCenterline(
  mask: Mask,
  params: VectorizeParams,
  dt: GrayF | null,
): VecShape[] {
  const traced = traceSkeleton(mask);
  const polylines = params.chainStrokes
    ? chainSkeleton(traced, {
        ...DEFAULT_CHAIN_PARAMS,
        maxTurnDegrees: params.chainMaxTurnDegrees,
        tangentSpan: params.chainTangentSpan,
        minBranchLength: params.chainPruneBranch,
        maxNodeDegree: params.chainMaxNodeDegree,
      })
    : traced;
  const out: VecShape[] = [];
  let dropped = 0;
  const modulate = params.modulateWidth && dt !== null;
  const baseStyle = vecStyle({
    stroke: 0xff000000,
    strokeWidth: params.strokeWidth,
    fill: null,
    cap: LineCap.ROUND,
    join: LineJoin.ROUND,
  });
  const outlineStyle = vecStyle({
    stroke: null,
    fill: 0xff000000,
    fillRule: FillRule.NONZERO,
  });

  for (let i = 0; i < polylines.length; i++) {
    const pl = polylines[i];
    if (pl.points.length < 2) {
      dropped++;
      continue;
    }
    if (polylineLength(pl.points, pl.closed) < params.minPathLength) {
      dropped++;
      continue;
    }
    const pts = prepare(pl.points, pl.closed, params);
    if (pts.length < 2) {
      dropped++;
      continue;
    }
    const path = fitPath(pts, params.fitError, pl.closed, params.cornerThresholdDegrees);
    if (path.isEmpty()) {
      dropped++;
      continue;
    }
    if (!modulate || dt === null) {
      out.push(vecShape(path, baseStyle));
      continue;
    }
    const anchors = path.points();
    const raw = new Float32Array(anchors.length);
    for (let j = 0; j < anchors.length; j++) {
      const w = 2 * dt.clamped(Math.round(anchors[j].x), Math.round(anchors[j].y));
      raw[j] = Px.clamp(w * params.widthScale, params.minWidth, params.maxWidth);
    }
    const widths = movingAverage(raw, WIDTH_SMOOTH_WINDOW);
    // SVG has no variable-width stroke, so a modulated stroke has to be emitted as a filled outline.
    const ribbon = variableWidthOutline(
      new VecPath(path.start, path.segments, path.closed, path.id, widths),
      widths,
      LineCap.ROUND,
    );
    if (ribbon.isEmpty()) {
      out.push(vecShape(path, baseStyle));
      continue;
    }
    out.push(vecShape(ribbon, outlineStyle));
  }
  lastStats = { emitted: out.length, dropped };
  return out;
}

/**
 * Reorders contours so every region's outer boundary is immediately followed by its own holes.
 *
 * `contourTrace` returns all outer boundaries and then all holes, which is the right order for a
 * tracer and the wrong one for a painter: a renderer that walks the list in order must meet the
 * region before the holes inside it. Linear, and stable within each group, so the output order is
 * still a function of the mask alone.
 */
function orderByRegion(contours: readonly Contour[]): Contour[] {
  const n = contours.length;
  if (n < 2) return contours.slice();
  const holesByLabel = new Map<number, number[]>();
  for (let i = 0; i < n; i++) {
    const c = contours[i];
    if (!c.isHole) continue;
    const list = holesByLabel.get(c.label);
    if (list === undefined) holesByLabel.set(c.label, [i]);
    else list.push(i);
  }
  const out: Contour[] = [];
  const taken = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    const c = contours[i];
    if (c.isHole) continue;
    out.push(c);
    taken[i] = 1;
    const holes = holesByLabel.get(c.label);
    if (holes === undefined) continue;
    for (const j of holes) {
      if (taken[j] !== 0) continue;
      out.push(contours[j]);
      taken[j] = 1;
    }
  }
  // A hole whose region was filtered away upstream is still real ink; it ships in trace order rather
  // than being silently dropped by the reordering.
  for (let i = 0; i < n; i++) if (taken[i] === 0) out.push(contours[i]);
  return out;
}

function runOutline(mask: Mask, params: VectorizeParams): VecShape[] {
  // See VectorizeParams.fillRegions. Painted regions trace outer boundaries only, because a hole
  // painted in ink is not a hole; stroked outlines trace everything, because the interior detail is
  // the whole product.
  const contours = orderByRegion(params.fillRegions ? traceOuterOnly(mask) : traceContours(mask));
  const out: VecShape[] = [];
  let dropped = 0;
  const painted = vecStyle({
    stroke: null,
    fill: 0xff000000,
    // NONZERO rather than EVENODD. Only outer boundaries are painted, so there is no hole for the
    // two rules to disagree about — but a traced boundary can touch itself at a one-pixel isthmus,
    // and NONZERO is the rule that still renders that solid instead of punching a hole through it.
    fillRule: FillRule.NONZERO,
  });
  const stroked = vecStyle({
    stroke: 0xff000000,
    strokeWidth: params.strokeWidth,
    fill: null,
    fillRule: FillRule.EVENODD,
    cap: LineCap.ROUND,
    join: LineJoin.ROUND,
  });
  const style = params.fillRegions ? painted : stroked;
  let holeIndex = 0;
  let lastLabel = Number.NaN;
  for (let i = 0; i < contours.length; i++) {
    const c = contours[i];
    if (c.label !== lastLabel) {
      lastLabel = c.label;
      holeIndex = 0;
    }
    // The id records the hole relationship a single-subpath VecPath cannot carry in its geometry:
    // `r7` is a region's outer boundary and `r7h0` is the first hole in it. Combined with the
    // emission order — every region immediately followed by its own holes — a consumer that later
    // gains compound paths can reassemble them exactly.
    const id = c.isHole ? `r${c.label}h${holeIndex++}` : `r${c.label}`;
    if (c.points.length < 3) {
      dropped++;
      continue;
    }
    if (polylineLength(c.points, true) < params.minPathLength) {
      dropped++;
      continue;
    }
    const pts = prepare(c.points, true, params);
    if (pts.length < 3) {
      dropped++;
      continue;
    }
    const fitted = fitPath(pts, params.fitError, true, params.cornerThresholdDegrees);
    if (fitted.isEmpty()) {
      dropped++;
      continue;
    }
    const path = new VecPath(fitted.start, fitted.segments, fitted.closed, id, fitted.strokeWidths);
    out.push(vecShape(path, style));
  }
  lastStats = { emitted: out.length, dropped };
  return out;
}
