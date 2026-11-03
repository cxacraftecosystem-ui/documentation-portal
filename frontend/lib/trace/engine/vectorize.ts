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
  readonly fillRegions: boolean;
  readonly chainStrokes: boolean;
  readonly chainMaxTurnDegrees: number;
  readonly chainTangentSpan: number;
  readonly chainPruneBranch: number;
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

export function vectorizeParams(over: Partial<VectorizeParams> = {}): VectorizeParams {
  return { ...DEFAULT_VECTORIZE_PARAMS, ...over };
}

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

export function run(
  mask: Mask,
  params: VectorizeParams,
  distanceTransform: GrayF | null = null,
): VecShape[] {
  return params.mode === VectorMode.OUTLINE
    ? runOutline(mask, params)
    : runCenterline(mask, params, distanceTransform);
}

export interface RunStats {
  readonly emitted: number;
  readonly dropped: number;
}

let lastStats: RunStats = { emitted: 0, dropped: 0 };

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
  for (let i = 0; i < n; i++) if (taken[i] === 0) out.push(contours[i]);
  return out;
}

function runOutline(mask: Mask, params: VectorizeParams): VecShape[] {
  const contours = orderByRegion(params.fillRegions ? traceOuterOnly(mask) : traceContours(mask));
  const out: VecShape[] = [];
  let dropped = 0;
  const painted = vecStyle({
    stroke: null,
    fill: 0xff000000,
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
