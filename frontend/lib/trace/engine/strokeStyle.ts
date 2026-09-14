import { Px } from './buffers';
import {
  DEFAULT_FLATTEN_TOLERANCE,
  LineCap,
  LineJoin,
  VecPath,
  VecPoint,
  VecSeg,
} from './path';
import { movingAverage } from './smooth';

/**
 * Stroke outlining. See ALGORITHMS.md §10.7 and §11.
 *
 * SVG has no variable-width stroke, so a width-modulated centreline has to become a **filled outline**.
 * The uniform case is outlined too, because that is what lets the rasteriser fill everything with one
 * non-zero scanline pass and makes a self-overlapping stroke render solid instead of showing seams.
 */

/** Radians per segment when approximating an arc; ~17 degrees is below the visible threshold at 64x zoom. */
const ARC_STEP = 0.3;

/** Points used for a round cap on a zero-length stroke, i.e. a dot. */
const DOT_SEGMENTS = 16;

function dedupe(pts: readonly VecPoint[]): VecPoint[] {
  const out: VecPoint[] = [];
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i];
    if (out.length === 0) {
      out.push(p);
      continue;
    }
    const q = out[out.length - 1];
    if (p.x !== q.x || p.y !== q.y) out.push(p);
  }
  return out;
}

function polygonToPath(pts: readonly VecPoint[], id: string): VecPath {
  if (pts.length === 0) return new VecPath({ x: 0, y: 0 }, [], true, id);
  const segs = [];
  for (let i = 1; i < pts.length; i++) segs.push(VecSeg.line(pts[i]));
  return new VecPath(pts[0], segs, true, id);
}

function dot(centre: VecPoint, hw: number, cap: LineCap, id: string): VecPath {
  if (cap === LineCap.BUTT) return new VecPath(centre, [], true, id);
  const pts: VecPoint[] = [];
  if (cap === LineCap.SQUARE) {
    pts.push({ x: centre.x - hw, y: centre.y - hw });
    pts.push({ x: centre.x + hw, y: centre.y - hw });
    pts.push({ x: centre.x + hw, y: centre.y + hw });
    pts.push({ x: centre.x - hw, y: centre.y + hw });
  } else {
    for (let i = 0; i < DOT_SEGMENTS; i++) {
      const a = (i / DOT_SEGMENTS) * Math.PI * 2;
      pts.push({ x: centre.x + hw * Math.cos(a), y: centre.y + hw * Math.sin(a) });
    }
  }
  return polygonToPath(pts, id);
}

/** Appends an arc of radius `r` about `c` from angle `a0`, sweeping `delta` radians. */
function pushArc(
  out: VecPoint[],
  c: VecPoint,
  r: number,
  a0: number,
  delta: number,
): void {
  const steps = Math.max(1, Math.ceil(Math.abs(delta) / ARC_STEP));
  for (let i = 1; i < steps; i++) {
    const a = a0 + (delta * i) / steps;
    out.push({ x: c.x + r * Math.cos(a), y: c.y + r * Math.sin(a) });
  }
}

/**
 * One side of a uniform-width offset polygon.
 * @param side +1 for the side the tangent's left normal points to, -1 for the other
 */
function offsetSide(
  pts: readonly VecPoint[],
  hw: number,
  join: LineJoin,
  miterLimit: number,
  side: number,
): VecPoint[] {
  const n = pts.length;
  const out: VecPoint[] = [];
  const tx = new Float64Array(n);
  const ty = new Float64Array(n);
  for (let i = 0; i < n - 1; i++) {
    const dx = pts[i + 1].x - pts[i].x;
    const dy = pts[i + 1].y - pts[i].y;
    const len = Math.hypot(dx, dy);
    tx[i] = len > 0 ? dx / len : 1;
    ty[i] = len > 0 ? dy / len : 0;
  }
  tx[n - 1] = tx[n - 2];
  ty[n - 1] = ty[n - 2];

  for (let i = 0; i < n; i++) {
    if (i === 0 || i === n - 1) {
      const k = i === 0 ? 0 : n - 2;
      out.push({ x: pts[i].x - side * ty[k] * hw, y: pts[i].y + side * tx[k] * hw });
      continue;
    }
    const t0x = tx[i - 1];
    const t0y = ty[i - 1];
    const t1x = tx[i];
    const t1y = ty[i];
    const ax = pts[i].x - side * t0y * hw;
    const ay = pts[i].y + side * t0x * hw;
    const bx = pts[i].x - side * t1y * hw;
    const by = pts[i].y + side * t1x * hw;
    const cross = t0x * t1y - t0y * t1x;
    // Outer corner for this side: the two offset points diverge and the gap between them has to be
    // filled by the join. On the inner corner both points are pushed and the resulting self-overlap is
    // resolved by the non-zero fill rule, which is exactly what SVG specifies.
    const outer = side * cross < 0;
    if (!outer || Math.abs(cross) < 1e-9) {
      out.push({ x: ax, y: ay });
      out.push({ x: bx, y: by });
      continue;
    }
    if (join === LineJoin.MITER) {
      const u = ((bx - ax) * t1y - (by - ay) * t1x) / cross;
      const mx = ax + t0x * u;
      const my = ay + t0y * u;
      const miterLen = Math.hypot(mx - pts[i].x, my - pts[i].y);
      if (miterLen <= miterLimit * hw) {
        out.push({ x: mx, y: my });
        continue;
      }
      out.push({ x: ax, y: ay });
      out.push({ x: bx, y: by });
    } else if (join === LineJoin.ROUND) {
      const a0 = Math.atan2(ay - pts[i].y, ax - pts[i].x);
      const a1 = Math.atan2(by - pts[i].y, bx - pts[i].x);
      let delta = a1 - a0;
      while (delta > Math.PI) delta -= 2 * Math.PI;
      while (delta < -Math.PI) delta += 2 * Math.PI;
      out.push({ x: ax, y: ay });
      pushArc(out, pts[i], hw, a0, delta);
      out.push({ x: bx, y: by });
    } else {
      out.push({ x: ax, y: ay });
      out.push({ x: bx, y: by });
    }
  }
  return out;
}

/**
 * Convert a stroked path into a fillable outline.
 *
 * A **closed** input is outlined as if it were an open path whose last point repeats its first, with a
 * butt cap at the seam. Both seam crossings then lie on the same radial line and cancel under the
 * non-zero rule, which yields an exact annulus from a single subpath — a genuinely separate inner ring
 * cannot be expressed in one `VecPath` and joining two rings with a bridge leaves a visible wedge.
 *
 * @param width   stroke width in document units; <= 0 yields an empty closed path
 * @param flatten curve flattening tolerance used before offsetting
 * @returns a closed VecPath of line segments, to be filled with {@link FillRule.NONZERO}.
 */
export function outlineStroke(
  path: VecPath,
  width: number,
  cap: LineCap,
  join: LineJoin,
  miterLimit = 4,
  flatten = DEFAULT_FLATTEN_TOLERANCE,
): VecPath {
  const hw = width / 2;
  if (!(hw > 0)) return new VecPath(path.start, [], true, path.id);
  let pts = dedupe(path.flatten(flatten));
  if (pts.length < 2) return dot(pts.length === 1 ? pts[0] : path.start, hw, cap, path.id);

  let effectiveCap = cap;
  if (path.closed) {
    const a = pts[0];
    const b = pts[pts.length - 1];
    if (a.x !== b.x || a.y !== b.y) pts = pts.concat([a]);
    effectiveCap = LineCap.BUTT;
  }
  if (pts.length < 2) return dot(pts[0], hw, cap, path.id);

  const left = offsetSide(pts, hw, join, miterLimit, 1);
  const right = offsetSide(pts, hw, join, miterLimit, -1);
  const out: VecPoint[] = [];
  for (let i = 0; i < left.length; i++) out.push(left[i]);
  appendCap(out, pts, hw, effectiveCap, false, left, right);
  for (let i = right.length - 1; i >= 0; i--) out.push(right[i]);
  appendCap(out, pts, hw, effectiveCap, true, left, right);
  return polygonToPath(out, path.id);
}

/**
 * Adds the end cap (`atStart = false`) or the start cap (`atStart = true`) between the two sides.
 * A butt cap contributes nothing: the straight join between the two offset points is already the cap.
 */
function appendCap(
  out: VecPoint[],
  pts: readonly VecPoint[],
  hw: number,
  cap: LineCap,
  atStart: boolean,
  left: readonly VecPoint[],
  right: readonly VecPoint[],
): void {
  if (cap === LineCap.BUTT) return;
  const n = pts.length;
  const i = atStart ? 0 : n - 1;
  const k = atStart ? 0 : n - 2;
  let dx = pts[k + 1].x - pts[k].x;
  let dy = pts[k + 1].y - pts[k].y;
  const len = Math.hypot(dx, dy);
  if (len === 0) return;
  dx /= len;
  dy /= len;
  // The outward direction is the travel direction at the end and its negation at the start.
  const ox = atStart ? -dx : dx;
  const oy = atStart ? -dy : dy;
  const from = atStart ? right[0] : left[left.length - 1];
  const to = atStart ? left[0] : right[right.length - 1];
  if (cap === LineCap.SQUARE) {
    out.push({ x: from.x + ox * hw, y: from.y + oy * hw });
    out.push({ x: to.x + ox * hw, y: to.y + oy * hw });
    return;
  }
  const a0 = Math.atan2(from.y - pts[i].y, from.x - pts[i].x);
  // Rotating the left normal by -90 degrees reaches the travel direction, so a -pi sweep from one side
  // to the other passes through the outward direction rather than back over the stroke.
  pushArc(out, pts[i], hw, a0, -Math.PI);
}

/**
 * Outline a centreline whose width varies per vertex.
 *
 * @param widths one width per **input anchor**; it is resampled by linear interpolation onto the
 *               flattened polyline, because flattening multiplies the vertex count and a stale index
 *               would apply the wrong width to the wrong part of the stroke
 * @param cap    applied at both ends; joins are implicitly round-ish, since a varying offset has no
 *               single miter to compute
 * @returns a closed VecPath of line segments, to be filled with {@link FillRule.NONZERO}.
 */
export function variableWidthOutline(
  path: VecPath,
  widths: Float32Array,
  cap: LineCap,
): VecPath {
  const pts = dedupe(path.flatten());
  if (pts.length < 2 || widths.length === 0) {
    const w0 = widths.length > 0 ? widths[0] : 0;
    return dot(pts.length > 0 ? pts[0] : path.start, w0 / 2, cap, path.id);
  }
  const n = pts.length;
  const hw = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const t = n === 1 ? 0 : (i / (n - 1)) * (widths.length - 1);
    const i0 = Math.min(widths.length - 1, Math.floor(t));
    const i1 = Math.min(widths.length - 1, i0 + 1);
    const f = t - i0;
    hw[i] = (widths[i0] + (widths[i1] - widths[i0]) * f) / 2;
  }

  const nx = new Float64Array(n);
  const ny = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    let ax = 0;
    let ay = 0;
    if (i > 0) {
      const dx = pts[i].x - pts[i - 1].x;
      const dy = pts[i].y - pts[i - 1].y;
      const l = Math.hypot(dx, dy);
      if (l > 0) {
        ax += -dy / l;
        ay += dx / l;
      }
    }
    if (i < n - 1) {
      const dx = pts[i + 1].x - pts[i].x;
      const dy = pts[i + 1].y - pts[i].y;
      const l = Math.hypot(dx, dy);
      if (l > 0) {
        ax += -dy / l;
        ay += dx / l;
      }
    }
    const l = Math.hypot(ax, ay);
    if (l > 1e-9) {
      nx[i] = ax / l;
      ny[i] = ay / l;
    } else {
      // A 180 degree reversal has no averaged normal; the previous segment's normal keeps the ribbon
      // continuous instead of collapsing it to a point.
      nx[i] = i > 0 ? nx[i - 1] : 1;
      ny[i] = i > 0 ? ny[i - 1] : 0;
    }
  }

  const left: VecPoint[] = new Array<VecPoint>(n);
  const right: VecPoint[] = new Array<VecPoint>(n);
  for (let i = 0; i < n; i++) {
    left[i] = { x: pts[i].x + nx[i] * hw[i], y: pts[i].y + ny[i] * hw[i] };
    right[i] = { x: pts[i].x - nx[i] * hw[i], y: pts[i].y - ny[i] * hw[i] };
  }
  const out: VecPoint[] = [];
  for (let i = 0; i < n; i++) out.push(left[i]);
  appendVariableCap(out, pts, hw, cap, false, left, right);
  for (let i = n - 1; i >= 0; i--) out.push(right[i]);
  appendVariableCap(out, pts, hw, cap, true, left, right);
  return polygonToPath(out, path.id);
}

function appendVariableCap(
  out: VecPoint[],
  pts: readonly VecPoint[],
  hw: Float64Array,
  cap: LineCap,
  atStart: boolean,
  left: readonly VecPoint[],
  right: readonly VecPoint[],
): void {
  if (cap === LineCap.BUTT) return;
  const n = pts.length;
  const i = atStart ? 0 : n - 1;
  const r = hw[i];
  if (!(r > 0)) return;
  const k = atStart ? 0 : n - 2;
  let dx = pts[k + 1].x - pts[k].x;
  let dy = pts[k + 1].y - pts[k].y;
  const len = Math.hypot(dx, dy);
  if (len === 0) return;
  dx /= len;
  dy /= len;
  const ox = atStart ? -dx : dx;
  const oy = atStart ? -dy : dy;
  const from = atStart ? right[0] : left[n - 1];
  const to = atStart ? left[0] : right[n - 1];
  if (cap === LineCap.SQUARE) {
    out.push({ x: from.x + ox * r, y: from.y + oy * r });
    out.push({ x: to.x + ox * r, y: to.y + oy * r });
    return;
  }
  const a0 = Math.atan2(from.y - pts[i].y, from.x - pts[i].x);
  pushArc(out, pts[i], r, a0, -Math.PI);
}

/**
 * Taper the ends of a width profile to zero, which is what makes a traced brush stroke read as drawn
 * rather than extruded.
 *
 * @param headFraction fraction of the profile ramped up from 0 at the start, clamped to 0..0.5
 * @param tailFraction fraction ramped down to 0 at the end, clamped to 0..0.5
 * @returns a new array of the same length; the input is not modified.
 */
export function taper(
  widths: Float32Array,
  headFraction: number,
  tailFraction: number,
): Float32Array {
  const n = widths.length;
  const out = new Float32Array(n);
  out.set(widths);
  if (n < 2) return out;
  const head = Px.clamp(headFraction, 0, 0.5);
  const tail = Px.clamp(tailFraction, 0, 0.5);
  const headCount = Math.floor(head * (n - 1));
  const tailCount = Math.floor(tail * (n - 1));
  for (let i = 0; i < headCount; i++) out[i] *= i / headCount;
  for (let i = 0; i < tailCount; i++) {
    const j = n - 1 - i;
    out[j] *= i / tailCount;
  }
  return out;
}

/**
 * Smooth a width profile with a centred moving average.
 *
 * Raw distance-transform samples are noisy at 1 px resolution and an unsmoothed width reads as a wobble
 * along the stroke, which looks like a mistake rather than like brushwork.
 *
 * @param window forced odd; 5 is the reference value
 */
export function smoothWidths(widths: Float32Array, window = 5): Float32Array {
  return movingAverage(widths, window);
}
