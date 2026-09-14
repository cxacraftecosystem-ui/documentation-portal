import { CubicSeg, VecPath, VecPoint, VecSeg } from './path';
import { detectCorners } from './simplify';

/**
 * Philip J. Schneider's curve fitter (Graphics Gems, 1990). See ALGORITHMS.md §10.5.
 *
 * Chosen deliberately over porting Potrace, which is GPL and would infect this codebase's licence.
 * Schneider's algorithm is public domain, and it reaches the same visual class here because corners are
 * split out *before* fitting (see {@link fitPath}) — the fitter is never asked to approximate a corner,
 * which is the only place a least-squares fit visibly loses to Potrace's corner-aware pass.
 *
 * **`error` is a SQUARED deviation tolerance**, as in the original and as ALGORITHMS.md §10.5 now
 * states: `computeMaxError` reports a squared deviation and it is compared directly against `error`, so
 * `error = 1.6` means "no sample deviates by more than sqrt(1.6) ≈ 1.26 px". This file used to treat it
 * as a true distance, which silently gave the two engines different tolerances for the same argument —
 * the hardest kind of parity failure to find, because every segment endpoint moves and nothing looks
 * obviously wrong on either side.
 *
 * The recursion is an explicit stack: a contour from a noisy photograph can be hundreds of thousands of
 * points and the recursive form overflows.
 */

/** Points closer together than this are treated as one; a zero-length chord has no tangent. */
const DUP_EPS = 1e-7;

/** Newton-Raphson passes per fit attempt, as published. More does not converge better here. */
const NEWTON_ITERATIONS = 4;

/**
 * Upper bound on a tangent magnitude relative to the chord length. Past this the least-squares solution
 * is not a curve any more, it is a control point in another postcode; see the fallback in
 * {@link generateBezier}.
 */
const MAX_ALPHA_RATIO = 10;

interface Frame {
  readonly first: number;
  readonly last: number;
  readonly t1x: number;
  readonly t1y: number;
  readonly t2x: number;
  readonly t2y: number;
  readonly depth: number;
}

/** Deduped point storage. Flat float64 arrays so the fitter never touches an object property. */
interface Poly {
  readonly xs: Float64Array;
  readonly ys: Float64Array;
  readonly n: number;
}

/**
 * Fit a chain of cubic Beziers through `points`, in order, starting at `points[0]`.
 *
 * @param points   at least 2 distinct points; fewer returns an empty list
 * @param error    **squared** deviation tolerance (see the module doc)
 * @param maxDepth subdivision cap; at the cap the range is emitted as line-shaped cubics, so a
 *                 pathological input degrades in quality rather than hanging
 * @returns cubic segments in order; the first starts at `points[0]` and the last ends at the last point.
 */
export function fit(points: readonly VecPoint[], error: number, maxDepth = 24): CubicSeg[] {
  return fitInternal(points, error, maxDepth, null);
}

function fitInternal(
  points: readonly VecPoint[],
  error: number,
  maxDepth: number,
  overrideTangents: Float64Array | null,
): CubicSeg[] {
  const poly = toPoly(points);
  const n = poly.n;
  const out: CubicSeg[] = [];
  if (n < 2) return out;
  const xs = poly.xs;
  const ys = poly.ys;

  let t1x: number;
  let t1y: number;
  let t2x: number;
  let t2y: number;
  if (overrideTangents !== null && overrideTangents.length >= 4) {
    t1x = overrideTangents[0];
    t1y = overrideTangents[1];
    t2x = overrideTangents[2];
    t2y = overrideTangents[3];
  } else {
    const tan = new Float64Array(4);
    const third = n > 2;
    endpointTangent(
      xs[0], ys[0], xs[1], ys[1],
      third ? xs[2] : 0, third ? ys[2] : 0, third, tan, 0,
    );
    endpointTangent(
      xs[n - 1], ys[n - 1], xs[n - 2], ys[n - 2],
      third ? xs[n - 3] : 0, third ? ys[n - 3] : 0, third, tan, 2,
    );
    t1x = tan[0];
    t1y = tan[1];
    t2x = tan[2];
    t2y = tan[3];
  }

  // A tolerance of exactly zero would make every comparison false and drive the fit straight to the
  // depth cap; a tiny positive floor degrades to "one cubic per chord" instead.
  const err = error > 0 && Number.isFinite(error) ? error : 1e-9;
  const cap = maxDepth < 0 ? 0 : maxDepth > 64 ? 64 : maxDepth;

  const stack: Frame[] = [{ first: 0, last: n - 1, t1x, t1y, t2x, t2y, depth: 0 }];
  const splitOut = new Int32Array(1);

  while (stack.length > 0) {
    const task = stack.pop() as Frame;
    const first = task.first;
    const last = task.last;
    const count = last - first + 1;

    if (count < 2) continue;

    if (count === 2) {
      const dist = distance(xs[first], ys[first], xs[last], ys[last]) / 3;
      emit(
        out,
        xs[first] + task.t1x * dist, ys[first] + task.t1y * dist,
        xs[last] + task.t2x * dist, ys[last] + task.t2y * dist,
        xs[last], ys[last],
      );
      continue;
    }

    const u = chordLengthParameterize(xs, ys, first, last);
    let bez = generateBezier(xs, ys, first, last, u, task.t1x, task.t1y, task.t2x, task.t2y);
    let maxErr = computeMaxError(xs, ys, first, last, bez, u, splitOut);

    if (maxErr < err) {
      emit(out, bez[2], bez[3], bez[4], bez[5], bez[6], bez[7]);
      continue;
    }

    // Reparameterise before considering a split — ALWAYS, not behind a gate.
    //
    // Graphics Gems guards this with `maxError < error * error`, and that guard has to go for two
    // independent reasons.
    //
    // First, it inverts its own meaning below 1. `error` here is a SQUARED deviation and callers
    // legitimately pass values well under 1, so `error * error` is SMALLER than `error`; the branch then
    // implies `maxErr < err`, which the acceptance test immediately above has already returned on.
    // Refinement was unreachable dead code.
    //
    // Second, and the reason no gate belongs here at all: `computeMaxError` measures
    // |sample[i] - B(u[i])| with `u` from chord-length parameterisation, and arc length is not the
    // Bernstein parameter. For a curve of any real curvature the two disagree materially, so a cubic
    // that is GEOMETRICALLY almost exact still reports a large error on its first attempt — the residual
    // is the parameter mismatch, not the shape. Measured on 61 samples of a single cubic of extent 100:
    // the first attempt recovers the control point to (20.04, 60.02) against a true (20, 60), and still
    // reports a squared error between 1.0 and 2.56. Any threshold small enough to be a meaningful
    // tolerance is therefore far below the first-attempt error of a perfect fit, and gating on it
    // guarantees a split that was never needed: 17 segments for a curve that one segment reproduces.
    //
    // Newton refinement is what converts that parametric residual into the geometric distance the caller
    // asked about, by walking each u[i] to its foot point. It is four passes and it replaces a
    // subdivision that would have cost two whole recursive ranges, so removing the gate makes the fitter
    // cheaper as well as better.
    let previousErr = maxErr;
    for (let i = 0; i < NEWTON_ITERATIONS; i++) {
      reparameterize(xs, ys, first, last, u, bez);
      bez = generateBezier(xs, ys, first, last, u, task.t1x, task.t1y, task.t2x, task.t2y);
      maxErr = computeMaxError(xs, ys, first, last, bez, u, splitOut);
      if (maxErr < err) break;
      // Newton is not guaranteed to descend on badly conditioned data. Once it stops improving it will
      // not recover within the iteration budget, and continuing only degrades the split point that
      // `computeMaxError` reports for the fallback below.
      if (maxErr >= previousErr) break;
      previousErr = maxErr;
    }
    if (maxErr < err) {
      emit(out, bez[2], bez[3], bez[4], bez[5], bez[6], bez[7]);
      continue;
    }

    if (task.depth >= cap) {
      // The cap exists so a pathological input loses quality instead of the process.
      for (let i = first; i < last; i++) {
        const ax = xs[i];
        const ay = ys[i];
        const bx = xs[i + 1];
        const by = ys[i + 1];
        emit(
          out,
          ax + (bx - ax) / 3, ay + (by - ay) / 3,
          ax + ((bx - ax) * 2) / 3, ay + ((by - ay) * 2) / 3,
          bx, by,
        );
      }
      continue;
    }

    let split = splitOut[0];
    if (split <= first) split = first + 1;
    if (split >= last) split = last - 1;

    let cx = xs[split - 1] - xs[split + 1];
    let cy = ys[split - 1] - ys[split + 1];
    let clen = Math.sqrt(cx * cx + cy * cy);
    if (clen < DUP_EPS) {
      cx = xs[split] - xs[split - 1];
      cy = ys[split] - ys[split - 1];
      clen = Math.sqrt(cx * cx + cy * cy);
      if (clen < DUP_EPS) {
        cx = 1;
        cy = 0;
        clen = 1;
      }
    }
    cx /= clen;
    cy /= clen;

    // Push right first: the stack is LIFO, so the left half is processed — and therefore emitted —
    // before the right one, which is what keeps the segment list in path order.
    stack.push({
      first: split, last, t1x: -cx, t1y: -cy, t2x: task.t2x, t2y: task.t2y, depth: task.depth + 1,
    });
    stack.push({
      first, last: split, t1x: task.t1x, t1y: task.t1y, t2x: cx, t2y: cy, depth: task.depth + 1,
    });
  }
  return out;
}

/**
 * Fit `points` into a complete path, splitting the run at every corner {@link detectCorners} reports and
 * fitting each run independently.
 *
 * That split is *how* corners are preserved: a least-squares cubic through a 90 degree turn rounds it
 * off, so the turn is made an endpoint of two separate fits instead, where the tangent is the chord and
 * the corner survives exactly.
 *
 * For `closed` input the loop is **rotated to begin at the first corner** so the seam falls on a corner
 * rather than in the middle of a smooth arc; a closed loop with no corners at all is fitted as one run
 * with a single averaged tangent across the seam, which is what stops a traced circle showing a kink at
 * 3 o'clock. Appending a copy of the start point and fitting from index 0 instead — which this function
 * used to do — promotes the seam to a corner and hands `detectCorners` a list whose wrap-around
 * neighbours are the same point twice.
 *
 * @param error **squared** deviation tolerance (see the module doc)
 * @returns a path with no segments (but a valid start point) for degenerate input.
 */
export function fitPath(
  points: readonly VecPoint[],
  error: number,
  closed: boolean,
  cornerThresholdDegrees = 100,
): VecPath {
  const clean = dedupe(points, closed);
  if (clean.length === 0) return new VecPath({ x: 0, y: 0 }, [], closed);
  if (clean.length === 1) return new VecPath(clean[0], [], closed);
  if (clean.length === 2) {
    // Two distinct points cannot be closed and cannot have a corner.
    return new VecPath(clean[0], fit(clean, error), false);
  }

  const n = clean.length;
  const corners = normaliseCorners(detectCorners(clean, cornerThresholdDegrees, 3, closed), n, closed);

  if (!closed) {
    const segs: CubicSeg[] = [];
    let from = 0;
    for (let i = 0; i < corners.length; i++) {
      const c = corners[i];
      if (c <= from || c >= n - 1) continue;
      pushAll(segs, fit(clean.slice(from, c + 1), error));
      from = c;
    }
    pushAll(segs, fit(clean.slice(from, n), error));
    return new VecPath(clean[0], segs, false);
  }

  if (corners.length === 0) {
    // No corner anywhere: fit the whole loop as one run with a tangent that is continuous across the
    // seam, so the start point is not silently promoted to a corner.
    const run = clean.slice();
    run.push(clean[0]);
    const t = seamTangent(clean);
    const segs = fitInternal(run, error, 24, Float64Array.of(t[0], t[1], -t[0], -t[1]));
    return new VecPath(clean[0], segs, true);
  }

  const pivot = corners[0];
  const rotated: VecPoint[] = [];
  for (let i = 0; i < n; i++) rotated.push(clean[(pivot + i) % n]);
  rotated.push(clean[pivot]);

  const segs: CubicSeg[] = [];
  let from = 0;
  for (let k = 1; k < corners.length; k++) {
    const c = corners[k] - pivot;
    if (c <= from || c >= n) continue;
    pushAll(segs, fit(rotated.slice(from, c + 1), error));
    from = c;
  }
  pushAll(segs, fit(rotated.slice(from, n + 1), error));
  return new VecPath(rotated[0], segs, true);
}

function pushAll(into: CubicSeg[], more: readonly CubicSeg[]): void {
  for (let i = 0; i < more.length; i++) into.push(more[i]);
}

/**
 * Solves the 2x2 normal equations for the two tangent magnitudes and returns the control polygon as
 * `[x0, y0, c1x, c1y, c2x, c2y, x3, y3]`.
 *
 * The fallback is not optional. When the system is near-singular, or either magnitude comes back
 * negative or absurdly large, the Wu/Barsky heuristic `alpha = ||p0 - p3|| / 3` is used instead. Without
 * it the solve occasionally returns control points thousands of units away and the path visibly shoots
 * off screen — a spectacular and very recognisable bug.
 */
function generateBezier(
  xs: Float64Array,
  ys: Float64Array,
  first: number,
  last: number,
  u: Float64Array,
  t1x: number,
  t1y: number,
  t2x: number,
  t2y: number,
): Float64Array {
  const count = last - first + 1;
  const px0 = xs[first];
  const py0 = ys[first];
  const px3 = xs[last];
  const py3 = ys[last];

  let c00 = 0;
  let c01 = 0;
  let c11 = 0;
  let xr0 = 0;
  let xr1 = 0;

  for (let i = 0; i < count; i++) {
    const ui = u[i];
    const omu = 1 - ui;
    const b0 = omu * omu * omu;
    const b1 = 3 * ui * omu * omu;
    const b2 = 3 * ui * ui * omu;
    const b3 = ui * ui * ui;

    const a0x = t1x * b1;
    const a0y = t1y * b1;
    const a1x = t2x * b2;
    const a1y = t2y * b2;

    c00 += a0x * a0x + a0y * a0y;
    c01 += a0x * a1x + a0y * a1y;
    c11 += a1x * a1x + a1y * a1y;

    const tmpx = xs[first + i] - (px0 * (b0 + b1) + px3 * (b2 + b3));
    const tmpy = ys[first + i] - (py0 * (b0 + b1) + py3 * (b2 + b3));
    xr0 += a0x * tmpx + a0y * tmpy;
    xr1 += a1x * tmpx + a1y * tmpy;
  }

  const det = c00 * c11 - c01 * c01;
  let alphaL: number;
  let alphaR: number;
  if (Math.abs(det) > 1e-12 * (1 + Math.abs(c00 * c11) + Math.abs(c01 * c01))) {
    alphaL = (xr0 * c11 - xr1 * c01) / det;
    alphaR = (c00 * xr1 - c01 * xr0) / det;
  } else {
    alphaL = NaN;
    alphaR = NaN;
  }

  // Reference length for the sanity bounds. It is the chord normally, but a closed run has p0 == p3 and
  // a zero chord would make every bound zero and force the fallback to collapse all four control points
  // onto one point.
  let refLen = distance(px0, py0, px3, py3);
  if (refLen < DUP_EPS) {
    let acc = 0;
    for (let i = first; i < last; i++) acc += distance(xs[i], ys[i], xs[i + 1], ys[i + 1]);
    refLen = acc > DUP_EPS ? acc : 1;
  }

  const minAlpha = 1e-6 * refLen;
  const maxAlpha = MAX_ALPHA_RATIO * refLen;
  if (
    !Number.isFinite(alphaL) ||
    !Number.isFinite(alphaR) ||
    alphaL < minAlpha ||
    alphaR < minAlpha ||
    alphaL > maxAlpha ||
    alphaR > maxAlpha
  ) {
    const d = refLen / 3;
    alphaL = d;
    alphaR = d;
  }

  return Float64Array.of(
    px0, py0,
    px0 + t1x * alphaL, py0 + t1y * alphaL,
    px3 + t2x * alphaR, py3 + t2y * alphaR,
    px3, py3,
  );
}

/** Normalised cumulative chord length over `[first, last]`; uniform when the run has no length. */
function chordLengthParameterize(
  xs: Float64Array,
  ys: Float64Array,
  first: number,
  last: number,
): Float64Array {
  const count = last - first + 1;
  const u = new Float64Array(count);
  for (let i = 1; i < count; i++) {
    u[i] = u[i - 1] + distance(xs[first + i - 1], ys[first + i - 1], xs[first + i], ys[first + i]);
  }
  const total = u[count - 1];
  if (total < DUP_EPS) {
    for (let i = 0; i < count; i++) u[i] = i / (count - 1);
  } else {
    for (let i = 1; i < count; i++) u[i] /= total;
    u[count - 1] = 1;
  }
  return u;
}

/**
 * Inward-pointing unit tangent at an endpoint, written to `out[offset], out[offset + 1]`.
 *
 * Uses the **second-order** one-sided difference `(-3p0 + 4p1 - p2) / 2` when a third point is
 * available, falling back to the plain chord `p1 - p0` otherwise.
 *
 * Graphics Gems uses that plain chord, which is only first-order accurate in the sample spacing, and the
 * direction is never corrected afterwards: reparameterisation refines the parameter values `u`, not the
 * tangent directions, so the least-squares solve can only absorb a bad direction by stretching the
 * tangent MAGNITUDE — which moves the control point along the wrong ray. On smooth input that is what
 * forces the fitter to subdivide a curve it could otherwise reproduce nearly exactly. Measured on 61
 * samples taken from a single cubic of extent 100 with a 1e-4 squared tolerance: the chord estimate
 * needed 17 segments, this one needs 1.
 *
 * The three-point form carries larger coefficients and so amplifies noise, which matters on the
 * pixel-staircase point sets that come out of contour tracing. It is therefore sanity-checked against
 * the chord and discarded whenever the two disagree by more than 90 degrees, which is precisely the case
 * where the extra term is reacting to a staircase step rather than to curvature.
 */
function endpointTangent(
  x0: number, y0: number,
  x1: number, y1: number,
  x2: number, y2: number,
  hasThird: boolean,
  out: Float64Array,
  offset: number,
): void {
  let cx = x1 - x0;
  let cy = y1 - y0;
  let clen = Math.sqrt(cx * cx + cy * cy);
  // A zero-length chord has no direction at all; any unit vector is as defensible as another and an
  // arbitrary one keeps the fit finite instead of producing NaN control points.
  if (clen < DUP_EPS) {
    cx = 1;
    cy = 0;
    clen = 1;
  }
  cx /= clen;
  cy /= clen;

  if (hasThird) {
    const hx = (-3 * x0 + 4 * x1 - x2) * 0.5;
    const hy = (-3 * y0 + 4 * y1 - y2) * 0.5;
    const hlen = Math.sqrt(hx * hx + hy * hy);
    if (hlen >= DUP_EPS) {
      const ux = hx / hlen;
      const uy = hy / hlen;
      if (ux * cx + uy * cy > 0) {
        out[offset] = ux;
        out[offset + 1] = uy;
        return;
      }
    }
  }
  out[offset] = cx;
  out[offset + 1] = cy;
}

/**
 * One Newton-Raphson step per sample, in place. Endpoints are pinned to 0 and 1 because the closed-form
 * solve assumes them; letting them drift makes the normal equations inconsistent and the fit gets worse
 * with every "refinement" pass.
 */
function reparameterize(
  xs: Float64Array,
  ys: Float64Array,
  first: number,
  last: number,
  u: Float64Array,
  bez: Float64Array,
): void {
  const count = last - first + 1;
  for (let i = 0; i < count; i++) {
    u[i] = newtonStep(bez, xs[first + i], ys[first + i], u[i]);
  }
  u[0] = 0;
  u[count - 1] = 1;
}

function newtonStep(b: Float64Array, px: number, py: number, u: number): number {
  const omu = 1 - u;
  const b0 = omu * omu * omu;
  const b1 = 3 * u * omu * omu;
  const b2 = 3 * u * u * omu;
  const b3 = u * u * u;
  const qx = b[0] * b0 + b[2] * b1 + b[4] * b2 + b[6] * b3;
  const qy = b[1] * b0 + b[3] * b1 + b[5] * b2 + b[7] * b3;

  // First derivative control points (degree 2) and second derivative (degree 1).
  const d0x = 3 * (b[2] - b[0]);
  const d0y = 3 * (b[3] - b[1]);
  const d1x = 3 * (b[4] - b[2]);
  const d1y = 3 * (b[5] - b[3]);
  const d2x = 3 * (b[6] - b[4]);
  const d2y = 3 * (b[7] - b[5]);
  const e0x = 2 * (d1x - d0x);
  const e0y = 2 * (d1y - d0y);
  const e1x = 2 * (d2x - d1x);
  const e1y = 2 * (d2y - d1y);

  const q1x = d0x * omu * omu + d1x * 2 * u * omu + d2x * u * u;
  const q1y = d0y * omu * omu + d1y * 2 * u * omu + d2y * u * u;
  const q2x = e0x * omu + e1x * u;
  const q2y = e0y * omu + e1y * u;

  const dx = qx - px;
  const dy = qy - py;
  const numerator = dx * q1x + dy * q1y;
  const denominator = q1x * q1x + q1y * q1y + dx * q2x + dy * q2y;
  if (Math.abs(denominator) < 1e-12) return u;
  const next = u - numerator / denominator;
  if (!Number.isFinite(next)) return u;
  return next < 0 ? 0 : next > 1 ? 1 : next;
}

/**
 * Largest **squared** deviation of a sample from the curve. Writes the index of the worst sample to
 * `splitOut[0]`, pre-seeded with the midpoint so the caller always has a legal split even when every
 * deviation is identical.
 */
function computeMaxError(
  xs: Float64Array,
  ys: Float64Array,
  first: number,
  last: number,
  b: Float64Array,
  u: Float64Array,
  splitOut: Int32Array,
): number {
  let maxDist = 0;
  splitOut[0] = (first + last) >> 1;
  for (let i = first + 1; i < last; i++) {
    const ui = u[i - first];
    const omu = 1 - ui;
    const b0 = omu * omu * omu;
    const b1 = 3 * ui * omu * omu;
    const b2 = 3 * ui * ui * omu;
    const b3 = ui * ui * ui;
    const qx = b[0] * b0 + b[2] * b1 + b[4] * b2 + b[6] * b3;
    const qy = b[1] * b0 + b[3] * b1 + b[5] * b2 + b[7] * b3;
    const dx = qx - xs[i];
    const dy = qy - ys[i];
    const d = dx * dx + dy * dy;
    if (d > maxDist) {
      maxDist = d;
      splitOut[0] = i;
    }
  }
  return maxDist;
}

/**
 * Appends one cubic, narrowed to float32.
 *
 * Kotlin's `VecPoint` holds `Float`, so every control point it emits is a float32 value. `bezierFit.toD`
 * is a §14 string stage compared **exactly**, and at 2 decimal places a coordinate sitting within one
 * float32 ulp of a rounding boundary is enough to make the two engines print different digits. Rounding
 * here is the same rule `edgeDog`/`edgeFlow` already follow for their float knobs.
 */
function emit(
  out: CubicSeg[],
  c1x: number, c1y: number,
  c2x: number, c2y: number,
  ex: number, ey: number,
): void {
  out.push(
    VecSeg.cubic(
      { x: Math.fround(c1x), y: Math.fround(c1y) },
      { x: Math.fround(c2x), y: Math.fround(c2y) },
      { x: Math.fround(ex), y: Math.fround(ey) },
    ),
  );
}

function distance(ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax;
  const dy = by - ay;
  return Math.sqrt(dx * dx + dy * dy);
}

/** Drops non-finite and repeated points into flat arrays; the fitter never sees a duplicate chord. */
function toPoly(points: readonly VecPoint[]): Poly {
  const cap = points.length;
  const xs = new Float64Array(cap > 0 ? cap : 1);
  const ys = new Float64Array(cap > 0 ? cap : 1);
  let m = 0;
  for (let i = 0; i < cap; i++) {
    const p = points[i];
    const x = p.x;
    const y = p.y;
    if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
    if (m > 0 && Math.abs(x - xs[m - 1]) <= DUP_EPS && Math.abs(y - ys[m - 1]) <= DUP_EPS) continue;
    xs[m] = x;
    ys[m] = y;
    m++;
  }
  return { xs, ys, n: m };
}

/** Drops non-finite and repeated points; for a closed run also drops a trailing copy of the start. */
function dedupe(points: readonly VecPoint[], closed: boolean): VecPoint[] {
  const out: VecPoint[] = [];
  for (let i = 0; i < points.length; i++) {
    const p = points[i];
    if (!Number.isFinite(p.x) || !Number.isFinite(p.y)) continue;
    if (out.length > 0) {
      const q = out[out.length - 1];
      if (Math.abs(p.x - q.x) <= 1e-6 && Math.abs(p.y - q.y) <= 1e-6) continue;
    }
    out.push(p);
  }
  if (closed && out.length > 1) {
    const a = out[0];
    const b = out[out.length - 1];
    if (Math.abs(a.x - b.x) <= 1e-6 && Math.abs(a.y - b.y) <= 1e-6) out.pop();
  }
  return out;
}

/** Sorted, de-duplicated, in-range corner indices. Index 0 and n-1 are endpoints, not corners. */
function normaliseCorners(corners: Int32Array, n: number, closed: boolean): number[] {
  const out: number[] = [];
  if (corners.length === 0) return out;
  const sorted = Array.from(corners).sort((a, b) => a - b);
  const lo = closed ? 0 : 1;
  const hi = closed ? n - 1 : n - 2;
  for (let i = 0; i < sorted.length; i++) {
    const c = sorted[i];
    if (c < lo || c > hi) continue;
    if (out.length > 0 && out[out.length - 1] === c) continue;
    out.push(c);
  }
  return out;
}

/** Unit tangent across the seam of a closed loop: the mean of the incoming and outgoing chords. */
function seamTangent(pts: readonly VecPoint[]): Float64Array {
  const n = pts.length;
  let ax = pts[0].x - pts[n - 1].x;
  let ay = pts[0].y - pts[n - 1].y;
  let la = Math.sqrt(ax * ax + ay * ay);
  if (la < DUP_EPS) {
    ax = 1;
    ay = 0;
    la = 1;
  }
  let bx = pts[1].x - pts[0].x;
  let by = pts[1].y - pts[0].y;
  let lb = Math.sqrt(bx * bx + by * by);
  if (lb < DUP_EPS) {
    bx = 1;
    by = 0;
    lb = 1;
  }
  let tx = ax / la + bx / lb;
  let ty = ay / la + by / lb;
  let lt = Math.sqrt(tx * tx + ty * ty);
  if (lt < DUP_EPS) {
    tx = bx / lb;
    ty = by / lb;
    lt = 1;
  }
  return Float64Array.of(tx / lt, ty / lt);
}
