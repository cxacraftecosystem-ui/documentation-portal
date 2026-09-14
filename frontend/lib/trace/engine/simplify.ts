import { VecPoint } from './path';

/**
 * Polyline simplification, corner detection and resampling. See ALGORITHMS.md §10.3 and §10.4.
 */

/**
 * Shortest chord {@link detectCorners} will measure an angle against, squared, in working pixels.
 *
 * One pixel, because that is the lattice every stage upstream samples on: a chord shorter than one
 * pixel is below the resolution of the data and the direction it reports is quantisation, not shape.
 */
const MIN_ARM_SQ = 1.0;

/** Floor on {@link resample}'s spacing, so a zero or negative argument cannot make it unbounded. */
const MIN_SPACING = 0.01;

/**
 * Douglas-Peucker simplification, implemented **iteratively with an explicit stack**. The recursive
 * form blows the stack on a 200 000-point contour from a noisy photograph, and the crash surfaces
 * somewhere unrelated.
 *
 * @param epsilon maximum perpendicular deviation in document units; <= 0 returns the input unchanged
 * @returns a new list that always keeps the first and last point; inputs of 2 points or fewer are
 *          returned as-is.
 */
export function douglasPeucker(points: readonly VecPoint[], epsilon: number): VecPoint[] {
  const n = points.length;
  if (n <= 2 || !(epsilon > 0)) return points.slice();
  const keep = new Uint8Array(n);
  keep[0] = 1;
  keep[n - 1] = 1;
  const stack = new Int32Array(2 * n);
  let sp = 0;
  stack[sp++] = 0;
  stack[sp++] = n - 1;
  const eps2 = epsilon * epsilon;
  while (sp > 0) {
    const last = stack[--sp];
    const first = stack[--sp];
    if (last <= first + 1) continue;
    const ax = points[first].x;
    const ay = points[first].y;
    const bx = points[last].x;
    const by = points[last].y;
    const dx = bx - ax;
    const dy = by - ay;
    const len2 = dx * dx + dy * dy;
    let worst = -1;
    let worstIdx = -1;
    for (let i = first + 1; i < last; i++) {
      const px = points[i].x - ax;
      const py = points[i].y - ay;
      let d2: number;
      if (len2 > 0) {
        // Squared perpendicular distance via the cross product; no sqrt in the inner loop.
        const cross = px * dy - py * dx;
        d2 = (cross * cross) / len2;
      } else {
        d2 = px * px + py * py;
      }
      if (d2 > worst) {
        worst = d2;
        worstIdx = i;
      }
    }
    if (worst > eps2 && worstIdx > first) {
      keep[worstIdx] = 1;
      stack[sp++] = first;
      stack[sp++] = worstIdx;
      stack[sp++] = worstIdx;
      stack[sp++] = last;
    }
  }
  const out: VecPoint[] = [];
  for (let i = 0; i < n; i++) if (keep[i] !== 0) out.push(points[i]);
  return out;
}

/**
 * Drop points whose perpendicular deviation from their neighbours is under `tolerance`.
 *
 * Run **before** Douglas-Peucker: it costs one pass and roughly halves DP's input on axis-aligned
 * artwork, which is pure win because DP is the expensive one.
 *
 * @param tolerance in document units; 0.05 px is the default and is well below anything visible
 * @returns a new list keeping the first and last point.
 */
export function removeCollinear(points: readonly VecPoint[], tolerance = 0.05): VecPoint[] {
  const n = points.length;
  // The `tolerance <= 0` half of this guard mirrors Simplify.kt. Without it the two engines answer
  // differently for a zero tolerance: Kotlin returns the input untouched, while the loop below would
  // still drop every exactly-collinear point (`d2 > 0` is false for them). Nothing in the pipeline
  // passes zero today, which is exactly why the divergence would have sat here undetected — no fixture
  // visits a degenerate argument.
  if (n <= 2 || !(tolerance > 0)) return points.slice();
  const out: VecPoint[] = [points[0]];
  const tol2 = tolerance * tolerance;
  let anchor = points[0];
  for (let i = 1; i < n - 1; i++) {
    const cur = points[i];
    const next = points[i + 1];
    const dx = next.x - anchor.x;
    const dy = next.y - anchor.y;
    const len2 = dx * dx + dy * dy;
    let d2: number;
    if (len2 > 0) {
      const px = cur.x - anchor.x;
      const py = cur.y - anchor.y;
      const cross = px * dy - py * dx;
      d2 = (cross * cross) / len2;
    } else {
      d2 = (cur.x - anchor.x) * (cur.x - anchor.x) + (cur.y - anchor.y) * (cur.y - anchor.y);
    }
    if (d2 > tol2) {
      out.push(cur);
      anchor = cur;
    }
  }
  out.push(points[n - 1]);
  return out;
}

/**
 * Corner detection over a +-`window`-point chord pair.
 *
 * A point is a corner when the angle between the incoming and outgoing chords is **sharper** than
 * `thresholdDegrees`, i.e. `dot(normalize(p[i-k] - p[i]), normalize(p[i+k] - p[i])) > cos(threshold)`.
 * A straight run scores -1 (180 degrees) and a needle scores +1.
 *
 * The `+-k` window measures the shape rather than the lattice the points were sampled on — and because
 * that is a statement about *length*, an arm shorter than one working pixel is walked further out (see
 * `arm` below), which keeps the measuring scale intact when a smoother or a simplifier has left the
 * points closer together than the lattice they came from.
 *
 * Candidates strictly **inside** each other's window are non-maximum suppressed, keeping the sharpest,
 * because a single physical corner otherwise yields three neighbouring indices and the fitter is handed
 * two two-point runs it cannot do anything useful with. The radius is `window - 1` rather than `window`
 * for the reason written out at the suppression loop itself.
 *
 * @param closed when true the window wraps, so a corner at index 0 is found
 * @returns strictly increasing indices into `points`; empty for a run shorter than `2 * window + 1`.
 */
export function detectCorners(
  points: readonly VecPoint[],
  thresholdDegrees = 100,
  window = 3,
  closed = false,
): Int32Array {
  const n = points.length;
  if (n < 3) return new Int32Array(0);

  let k = Math.max(1, window | 0);
  if (closed) {
    // Each arm of the window may span at most a THIRD of the loop, and there is no early return for
    // a short run. Mirrors Simplify.kt, where the reasoning is written out at length.
    //
    // Two bugs lived here, both of which silently destroyed small closed polygons — which is to say
    // every stencil, silhouette, vinyl cut and laser path that OUTLINE mode exists to produce:
    //
    //  - `if (n < 2k + 1) return []` reported a 5-point traced square as having NO corners at all
    //    (2*3+1 = 7 > 5), so the fitter ran one smooth loop through all four of them.
    //  - the obvious "arms must not overlap" clamp, `k <= (n-1)/2`, is not enough either: on a
    //    5-cycle it permits k = 2, and i-2 and i+2 differ by 4 = -1, so the two arms land on points
    //    ADJACENT TO EACH OTHER on the far side and the measured angle has nothing to do with the
    //    corner at i.
    //
    // A third keeps a clear gap of unclaimed points between the arms at every size: n = 3..5 gives
    // k = 1 (immediate neighbours, exact for a polygon), n = 6..8 gives 2, n >= 9 the requested 3.
    const maxK = Math.floor(n / 3);
    if (k > maxK) k = maxK < 1 ? 1 : maxK;
  }

  const cosT = Math.cos((thresholdDegrees * Math.PI) / 180);
  const score = new Float64Array(n);
  const isCorner = new Uint8Array(n);
  // An open run evaluates every interior point with its arms CLAMPED to the ends, rather than
  // skipping the first and last k points — otherwise a corner within k of either end is invisible.
  const lo = closed ? 0 : 1;
  const hi = closed ? n - 1 : n - 2;
  const at = (i: number, d: number): number =>
    closed ? (i + d + n) % n : Math.max(0, Math.min(n - 1, i + d));
  /**
   * Walks the arm out from `i` in direction `d` until it is at least one working pixel long (see
   * {@link MIN_ARM_SQ}), up to `k` points past the nominal window and never onto `stop` or `i` itself.
   *
   * `window` is a point count but its *purpose* is a length — it exists so the angle describes the
   * shape rather than the lattice the points were sampled on. Those two coincide only while the
   * points are about a pixel apart, which is true of a raw traced contour and false of anything a
   * smoother has been over: Chaikin doubles the point count per iteration, so a +-3-point window that
   * spans 3 px on the contour spans 1.5 px after one pass and 0.75 px after two, and the detector goes
   * back to measuring quantisation. Worse, Douglas-Peucker keeps the first and last point of what is
   * really a ring, so a closed contour whose seam falls on a corner arrives here with two points a
   * third of a pixel apart: both measure 135 degrees against each other, neither is reported, and the
   * fitter runs one curve through a right angle. That cost a traced rectangle 3.5% of its area.
   *
   * A chord under one working pixel is below the resolution of every stage upstream, so it cannot
   * describe a direction the raster was able to represent. Extending past it restores the intended
   * measuring scale and is a no-op on a raw contour, where every step is already 1 px or 1.41 px.
   */
  const arm = (i: number, d: number, stop: number): number => {
    let j = at(i, d * k);
    const c0 = points[i];
    for (let e = 1; e <= k; e++) {
      const dx = points[j].x - c0.x;
      const dy = points[j].y - c0.y;
      if (dx * dx + dy * dy >= MIN_ARM_SQ) break;
      const next = at(i, d * (k + e));
      if (next === stop || next === i || next === j) break;
      j = next;
    }
    return j;
  };
  for (let i = lo; i <= hi; i++) {
    const ib = arm(i, 1, at(i, -k));
    const ia = arm(i, -1, ib);
    // The two arms can only land on the same point once extension is in play, and only on a loop of
    // exactly `3k` points every one of which is under a pixel from `i` — a ring smaller than one
    // pixel. Both chords would then be identical, the dot product exactly 1, and every point on the
    // ring would report as a needle-sharp corner. There is no angle at `i` to measure; skip it.
    if (ia === ib) continue;
    const c = points[i];
    let ax = points[ia].x - c.x;
    let ay = points[ia].y - c.y;
    let bx = points[ib].x - c.x;
    let by = points[ib].y - c.y;
    const la = Math.hypot(ax, ay);
    const lb = Math.hypot(bx, by);
    // 1e-9 rather than an exact zero test, matching Kotlin: two points a nanometre apart are a
    // duplicate, not a corner, and dividing by that chord length puts a NaN into every comparison
    // below — where NaN answers false to all of them and the point is silently kept.
    if (la < 1e-9 || lb < 1e-9) continue;
    ax /= la;
    ay /= la;
    bx /= lb;
    by /= lb;
    const dot = ax * bx + ay * by;
    score[i] = dot;
    if (dot > cosT) isCorner[i] = 1;
  }
  const outIdx: number[] = [];
  // Suppression reaches `k - 1` points, NOT `k`. One physical corner at index c makes exactly the
  // indices |i - c| < k report a bend, because those are the points whose two arms straddle it; the
  // point at exactly c +- k has one arm endpoint sitting ON the corner and both arms lying along
  // straight runs, so it measures 180 degrees and is never a candidate. The cluster a single corner
  // produces is therefore 2k-1 wide, and a radius of k reaches one point past its own edge — into a
  // DIFFERENT corner.
  //
  // That over-reach is not theoretical. `fitPath` runs this on points a simplifier has already
  // reduced to the shape's vertices, where consecutive indices are the whole side of a polygon apart
  // and every one of them is a real corner. A traced 100x70 rectangle arrives here as five points, so
  // the closed clamp above sets k = 1 and the old radius let each corner suppress both its
  // neighbours: three of the four corners died, the fitter ran one smooth loop through them, and the
  // rectangle came back with an area 48% too large and its corners rounded by 0.74 px. With the
  // radius at k-1 the same rectangle keeps all four corners, its corners land exactly (0.000 px) and
  // its area is exact.
  //
  // Nothing changes on a dense pixel-sampled contour, which is the input the window was sized for:
  // there the cluster is 2k-1 wide and a radius of k-1 still collapses it onto its peak.
  for (let i = 0; i < n; i++) {
    if (isCorner[i] === 0) continue;
    let best = true;
    for (let j = -(k - 1); j <= k - 1; j++) {
      if (j === 0) continue;
      const m = closed ? (i + j + n) % n : i + j;
      if (m < 0 || m >= n || isCorner[m] === 0) continue;
      // STRICTLY greater only. A tie must never suppress.
      //
      // "On equal scores the lower index wins" looks reproducible and is a catastrophe on a polygon,
      // because it cascades: suppression is judged against the raw score array, so on a traced square
      // — all four vertices corners, all adjacent in index space, all scoring cos = 0 exactly —
      // vertex 1 is suppressed by 0, vertex 2 by 1, vertex 3 by 2. One corner survives out of four,
      // the fitter smooths through the rest, and the square comes out a blob 55% too large.
      //
      // Keeping ties costs at worst one redundant anchor where a smooth curve happens to score two
      // neighbours equally: an extra run, identical geometry. Dropping a real corner changes the
      // shape. This is deliberately the weakest suppression that still collapses a genuine cluster.
      if (score[m] > score[i]) {
        best = false;
        break;
      }
    }
    if (best) outIdx.push(i);
  }
  return Int32Array.from(outIdx);
}

/**
 * Split a run at the given corner indices.
 *
 * Each corner belongs to **both** adjacent runs, so the pieces share endpoints and the refitted path has
 * no gap at the corner. Out-of-range and unsorted indices are ignored rather than throwing.
 *
 * @returns one list per piece; a single-element result containing the whole input when there are no
 *          usable corners.
 */
export function splitAtCorners(
  points: readonly VecPoint[],
  corners: Int32Array | readonly number[],
): VecPoint[][] {
  const n = points.length;
  if (n < 2) return [points.slice()];
  // Sorted first, matching Simplify.kt. Reading `corners` in the order given and skipping anything not
  // greater than the previous cut silently drops every index that arrives out of order, so the same
  // unsorted argument produced different runs in the two engines. `detectCorners` returns ascending
  // indices so nothing in the pipeline notices — which is the whole problem with leaving it.
  const sorted = Array.from(corners as ArrayLike<number>).sort((a, b) => a - b);
  const cuts: number[] = [];
  let prev = 0;
  for (let i = 0; i < sorted.length; i++) {
    const c = sorted[i] | 0;
    if (c <= prev || c >= n - 1) continue;
    cuts.push(c);
    prev = c;
  }
  if (cuts.length === 0) return [points.slice()];
  const out: VecPoint[][] = [];
  let start = 0;
  for (let i = 0; i < cuts.length; i++) {
    out.push(points.slice(start, cuts[i] + 1));
    start = cuts[i];
  }
  out.push(points.slice(start));
  return out;
}

/**
 * Resample to (approximately) uniform arc-length spacing.
 *
 * @param spacing target distance between output points in document units, **coerced to at least
 *                {@link MIN_SPACING}** so a zero or negative argument cannot make the output unbounded
 * @returns a new list beginning at the first input point and ending at the last, so the geometry's
 *          extent is preserved exactly even when the total length is not a multiple of `spacing`.
 */
export function resample(points: readonly VecPoint[], spacing: number): VecPoint[] {
  const n = points.length;
  if (n < 2) return points.slice();
  // Coerced rather than "return the input for a non-positive spacing", which is what this used to do
  // and what Simplify.kt does not: the same call answered with 2 points here and ~100 there.
  const step = spacing > MIN_SPACING ? spacing : MIN_SPACING;
  const out: VecPoint[] = [points[0]];
  let carry = 0;
  for (let i = 1; i < n; i++) {
    const a = points[i - 1];
    const b = points[i];
    const segLen = Math.hypot(b.x - a.x, b.y - a.y);
    if (segLen === 0) continue;
    let t = step - carry;
    while (t <= segLen) {
      const u = t / segLen;
      out.push({ x: a.x + (b.x - a.x) * u, y: a.y + (b.y - a.y) * u });
      t += step;
    }
    carry = segLen - (t - step);
  }
  const last = points[n - 1];
  const tail = out[out.length - 1];
  if (tail.x !== last.x || tail.y !== last.y) out.push(last);
  return out;
}
