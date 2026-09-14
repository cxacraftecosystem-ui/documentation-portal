import { GrayF, Mask } from './buffers';
import { VecPoint } from './path';

/**
 * Skeleton graph tracing. See ALGORITHMS.md §10.2.
 *
 * The junction handling is what separates this from a naive "trace all 8-connected runs": without it a
 * five-way star becomes five paths that all stop one pixel short of each other, and the SVG has a
 * visible hole at every junction.
 */

/** A traced run of skeleton pixels. `closed` marks a loop with no junction on it. */
export interface Polyline {
  readonly points: readonly VecPoint[];
  readonly closed: boolean;
}

/** Polylines plus a per-vertex width array for each, as returned by {@link traceWithWidths}. */
export interface PolylinesWithWidths {
  readonly polylines: readonly Polyline[];
  readonly widths: readonly Float32Array[];
}

// Clockwise from east; the order fixes which branch of a junction is walked first, and therefore the
// output order, which both engines must agree on.
const DX = [1, 1, 0, -1, -1, -1, 0, 1];
const DY = [0, 1, 1, 1, 0, -1, -1, -1];

function opposite(d: number): number {
  return (d + 4) & 7;
}

/**
 * Walk the skeleton as a graph.
 *
 * Every edge between two nodes (endpoints, degree 1, and junctions, degree >= 3) is emitted exactly
 * once, then any remaining degree-2 pixels are emitted as closed loops. Isolated pixels come out as
 * one-point polylines rather than being dropped, so nothing disappears without the caller counting it.
 *
 * @returns polylines in raster order of their first node; pixel coordinates.
 */
export function trace(skeleton: Mask): Polyline[] {
  const w = skeleton.width;
  const h = skeleton.height;
  const n = w * h;
  const d = skeleton.data;
  const out: Polyline[] = [];

  const deg = new Uint8Array(n);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      if (d[row + x] === 0) continue;
      let c = 0;
      for (let k = 0; k < 8; k++) {
        const nx = x + DX[k];
        const ny = y + DY[k];
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
        if (d[ny * w + nx] !== 0) c++;
      }
      deg[row + x] = c;
    }
  }

  // One byte per pixel holding an 8-bit "this direction has been walked" set. A full
  // pixel-times-direction table would be 8 bytes per pixel — 96 MB for a 12 MP skeleton.
  const usedDir = new Uint8Array(n);
  const visited = new Uint8Array(n);

  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const start = row + x;
      if (d[start] === 0) continue;
      const dg = deg[start];
      if (dg === 2) continue;
      if (dg === 0) {
        visited[start] = 1;
        out.push({ points: [{ x, y }], closed: false });
        continue;
      }
      for (let k = 0; k < 8; k++) {
        if ((usedDir[start] & (1 << k)) !== 0) continue;
        const fx = x + DX[k];
        const fy = y + DY[k];
        if (fx < 0 || fy < 0 || fx >= w || fy >= h) continue;
        const firstIdx = fy * w + fx;
        if (d[firstIdx] === 0) continue;
        usedDir[start] |= 1 << k;
        usedDir[firstIdx] |= 1 << opposite(k);
        const pts: VecPoint[] = [
          { x, y },
          { x: fx, y: fy },
        ];
        let prev = start;
        let cur = firstIdx;
        let cx = fx;
        let cy = fy;
        if (deg[cur] === 2) visited[cur] = 1;
        // Interior pixels have exactly two neighbours, so the walk is forced; it stops at the first
        // pixel whose degree is not 2, which is by definition the far node.
        let guard = 0;
        while (deg[cur] === 2 && guard++ <= n) {
          let nextIdx = -1;
          let nk = -1;
          for (let j = 0; j < 8; j++) {
            const tx = cx + DX[j];
            const ty = cy + DY[j];
            if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue;
            const ti = ty * w + tx;
            if (d[ti] === 0 || ti === prev) continue;
            nextIdx = ti;
            nk = j;
            break;
          }
          if (nextIdx < 0) break;
          usedDir[cur] |= 1 << nk;
          usedDir[nextIdx] |= 1 << opposite(nk);
          prev = cur;
          cur = nextIdx;
          cy = (cur / w) | 0;
          cx = cur - cy * w;
          pts.push({ x: cx, y: cy });
          if (deg[cur] === 2) visited[cur] = 1;
        }
        out.push({ points: pts, closed: pts.length > 2 && cur === start });
      }
    }
  }

  // Anything left is a loop of degree-2 pixels with no node on it at all.
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const start = row + x;
      if (d[start] === 0 || visited[start] !== 0 || deg[start] !== 2) continue;
      const pts: VecPoint[] = [{ x, y }];
      visited[start] = 1;
      let prev = -1;
      let cur = start;
      let cx = x;
      let cy = y;
      let guard = 0;
      for (;;) {
        let nextIdx = -1;
        for (let j = 0; j < 8; j++) {
          const tx = cx + DX[j];
          const ty = cy + DY[j];
          if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue;
          const ti = ty * w + tx;
          if (d[ti] === 0 || ti === prev) continue;
          nextIdx = ti;
          break;
        }
        if (nextIdx < 0 || nextIdx === start || guard++ > n) break;
        visited[nextIdx] = 1;
        prev = cur;
        cur = nextIdx;
        cy = (cur / w) | 0;
        cx = cur - cy * w;
        pts.push({ x: cx, y: cy });
      }
      out.push({ points: pts, closed: true });
    }
  }
  return out;
}

/**
 * Tuning for {@link chain}.
 *
 * Mirrors `SkeletonTrace.ChainParams`, where each field's reasoning is written out at length.
 */
export interface ChainParams {
  /**
   * How far a stroke may turn at a node and still count as *the same* stroke continuing.
   *
   * **The usable window is (45, 80) degrees, and both ends of it are forced rather than chosen.**
   *
   * The floor is the lattice. A skeleton is 8-connected, so the edges immediately around a junction
   * are one to three pixels long and the only directions that exist between neighbouring pixels are
   * multiples of 45 degrees. A threshold at or below 45 therefore refuses to walk through a single
   * diagonal step — which is not a corner, it is rasterisation — and the measurement is
   * unambiguous: sweeping the threshold over a 900x1200 shaded subject, every value from 30 to 44
   * gives 4552 paths at a 8.1 px median and every value from 46 to 75 gives ~3540 paths at a 11.7 px
   * median. The step is exactly at 45 and there is nothing else in the sweep.
   *
   * The ceiling is {@link MAX_TURN_DEGREES} = 80, which is exactly where `Simplify.detectCorners`'
   * default begins calling a bend a corner (it measures the arm angle, so its 100 degree default is
   * an 80 degree turn). Chaining past that point would fuse two strokes through a vertex the corner
   * detector exists to protect, and invent geometry that was never traced.
   *
   * The default sits in the middle of that window with margin on both sides. Above 46 the result is
   * flat to within 1.5%, so the exact value is not load-bearing — which is the point: it is chosen
   * to be far from both cliffs rather than tuned to a subject.
   */
  readonly maxTurnDegrees: number;
  /**
   * Chord length in pixels used to measure the direction a stroke arrives at a node with. It has to
   * span several pixels: consecutive skeleton pixels are one pixel apart, so the only directions
   * that exist between neighbours are multiples of 45 degrees, and a turn threshold measured
   * against that quantisation means nothing.
   */
  readonly tangentSpan: number;
  /**
   * Leaf branches shorter than this are dropped **before** chaining. Thinning a noisy region grows
   * hair, every hair is a spurious junction, and a spurious junction is what breaks a real contour
   * into pieces — so pruning it ought to be cheaper than chaining around it.
   *
   * **It is off by default, because it was measured and it does not pay.** Sweeping 0 to 14 px on
   * the shaded subject moved the path count by 3% (3540 to 3435) and the flat-graphic subject not at
   * all: on a real skeleton the tone response arrives as closed contours and connected runs rather
   * than as leaves, so there is very little that fits the definition of a spur. Three percent does
   * not justify deleting ink, and where hair genuinely dominates, `Thinning.pruneSpurs` removes it at
   * the pixel level before the graph is built (ALGORITHMS.md §9), which is both a documented preset
   * knob and the earlier, cheaper place to do it. The parameter stays because it is the right lever
   * for a caller who has already measured that it helps on their source.
   */
  readonly minBranchLength: number;
  /** Rounds of leaf removal; removing one leaf can expose the next. Bounded by {@link MAX_PRUNE_ROUNDS}. */
  readonly pruneRounds: number;
  /**
   * A node where more than this many strokes meet is a blob, not a crossing. Two strokes crossing
   * gives degree 4 and a T-junction 3; degree 8 is an unthinned region, where every pair of arms is
   * equally plausible and picking one is guessing.
   *
   * Measured: 3 is clearly wrong — refusing to chain at a 4-way crossing loses a fifth of the total
   * path length on the shaded subject (52 087 px against 64 734) because the crossings stay
   * fragmented and the fragments fall under the caller's minimum length. 5 and above buy under 1%
   * over 4 and give up the property that makes an unthinned mask degrade safely: with the cap at 4 a
   * solid region, every pixel of which is a degree-8 node, chains nothing at all and comes back as
   * the runt paths it really is rather than as invented strokes across a blob.
   */
  readonly maxNodeDegree: number;
}

export const DEFAULT_CHAIN_PARAMS: ChainParams = {
  maxTurnDegrees: 55,
  tangentSpan: 6,
  minBranchLength: 0,
  pruneRounds: 3,
  maxNodeDegree: 4,
};

/** @returns {@link DEFAULT_CHAIN_PARAMS} with `over` applied. */
export function chainParams(over: Partial<ChainParams> = {}): ChainParams {
  return { ...DEFAULT_CHAIN_PARAMS, ...over };
}

/** See {@link ChainParams.maxTurnDegrees}. */
const MAX_TURN_DEGREES = 80;

/** See {@link ChainParams.pruneRounds}; the same bound `Thinning.pruneSpurs` uses. */
const MAX_PRUNE_ROUNDS = 10;

/** Wider than any image this engine will process, and small enough that the product is exact. */
const NODE_KEY_STRIDE = 4194304;

/**
 * A node's identity is its **rounded pixel coordinate**, packed into one number.
 *
 * Rounding is exact here — every point {@link trace} emits is a pixel centre — and packing rather
 * than hashing a pair means the lookup cannot collide, so two strokes are judged to meet when they
 * meet on the same pixel and never otherwise.
 */
function nodeKey(p: VecPoint): number {
  return Math.round(p.y) * NODE_KEY_STRIDE + Math.round(p.x);
}

/** Flattened length of a polyline, including the closing edge when it is closed. */
function polylineLength(pl: Polyline): number {
  const pts = pl.points;
  let total = 0;
  for (let i = 1; i < pts.length; i++) {
    const dx = pts[i].x - pts[i - 1].x;
    const dy = pts[i].y - pts[i - 1].y;
    total += Math.sqrt(dx * dx + dy * dy);
  }
  if (pl.closed && pts.length > 1) {
    const dx = pts[0].x - pts[pts.length - 1].x;
    const dy = pts[0].y - pts[pts.length - 1].y;
    total += Math.sqrt(dx * dx + dy * dy);
  }
  return total;
}

/**
 * Unit vector pointing from one end of `points` **into** the polyline, over a chord of at least
 * `span` pixels (or the whole polyline, whichever is shorter).
 *
 * `Math.sqrt` and not `Math.hypot`: `sqrt` is required by IEEE 754 to be correctly rounded and
 * therefore agrees bit for bit with Kotlin's, while `hypot` is a library routine the two engines
 * implement differently.
 *
 * @returns false when the chord is degenerate, which leaves that end unchainable rather than
 *          producing a direction from a zero-length vector.
 */
function tangentAt(
  points: readonly VecPoint[],
  fromStart: boolean,
  span: number,
  out: Float64Array,
): boolean {
  const n = points.length;
  if (n < 2) return false;
  const step = fromStart ? 1 : -1;
  let idx = fromStart ? 0 : n - 1;
  const end = points[idx];
  let far = end;
  let acc = 0;
  let count = 0;
  while (count < n - 1) {
    const next = points[idx + step];
    const dx = next.x - points[idx].x;
    const dy = next.y - points[idx].y;
    acc += Math.sqrt(dx * dx + dy * dy);
    idx += step;
    far = next;
    count++;
    if (acc >= span) break;
  }
  const vx = far.x - end.x;
  const vy = far.y - end.y;
  const len = Math.sqrt(vx * vx + vy * vy);
  if (len < 1e-9) return false;
  out[0] = vx / len;
  out[1] = vy / len;
  return true;
}

/** Appends `src` to `dst`, reversed if asked, skipping its first point when it duplicates a node. */
function appendRun(
  dst: VecPoint[],
  src: readonly VecPoint[],
  reversed: boolean,
  skipFirst: boolean,
): void {
  const n = src.length;
  const from = skipFirst ? 1 : 0;
  if (reversed) {
    for (let i = n - 1 - from; i >= 0; i--) dst.push(src[i]);
  } else {
    for (let i = from; i < n; i++) dst.push(src[i]);
  }
}

function nodeDegrees(
  nodeOf: Int32Array,
  alive: Uint8Array,
  nodeCount: number,
  polylineCount: number,
): Int32Array {
  const degree = new Int32Array(nodeCount);
  for (let i = 0; i < polylineCount; i++) {
    if (alive[i] === 0) continue;
    degree[nodeOf[2 * i]]++;
    degree[nodeOf[2 * i + 1]]++;
  }
  return degree;
}

/**
 * Link polylines that meet at a node and continue through it, so one contour comes back as one path.
 *
 * {@link trace} emits one polyline per *graph edge*, which is the only decomposition that is a
 * function of the skeleton alone — but it means a long contour crossed by two other strokes comes
 * back as three paths, and a skeleton with spurious junctions (which is any skeleton of a
 * photograph) comes back shattered. Measured on a 900x1200 shaded subject: 88% of the kept paths
 * were under 20 px and the median was 7 px, for a subject whose smallest real feature is 150 px long.
 *
 * The rule, in one sentence: **at a node, the incoming stroke continues into whichever other arm
 * turns least, provided that turn is under `maxTurnDegrees`.**
 *
 * Properties this is required to have, and how each is obtained:
 *
 *  - **Deterministic.** No RNG and no reliance on `Map` iteration order. Nodes are numbered in the
 *    order the polylines are visited; candidate pairs are ordered by a *total* key — straightest
 *    first, then node index, then the two end ids — so the greedy pass cannot depend on anything but
 *    the geometry.
 *  - **Each skeleton edge used at most once.** A polyline has exactly two ends and the greedy pass
 *    links an end only when both ends of the pair are still free, so an edge can join at most one
 *    predecessor and one successor: the links form disjoint paths and cycles, never a branch.
 *  - **Greedy, and bounded.** It is a greedy pass rather than a global matching because the geometry
 *    does not deserve better: the candidate that is straightest at a node is the continuation, and no
 *    rearrangement of the rest changes that. Cost is `O(maxNodeDegree^2 * nodes)` candidates plus one
 *    sort — the degree cap is what bounds it.
 *  - **Closed loops stay closed.** A ring already arrives from {@link trace} as a closed polyline and
 *    is passed through untouched; a chain that walks back to its own start is emitted closed, with
 *    the duplicated seam vertex dropped, rather than opened at an arbitrary point.
 *  - **Corners survive.** See {@link ChainParams.maxTurnDegrees}.
 *
 * @returns polylines in the order of the lowest-indexed member of each chain. Pruned spurs are
 *          *absent*, so the caller's own count of what it received is the honest one.
 */
export function chain(
  polylines: readonly Polyline[],
  params: ChainParams = DEFAULT_CHAIN_PARAMS,
): Polyline[] {
  const n = polylines.length;
  if (n === 0) return polylines.slice();

  // Only an open polyline with two distinct ends can be chained or pruned. A closed ring has no
  // ends; a one-point polyline (an isolated pixel) has no direction.
  const chainable = new Uint8Array(n);
  const nodeOf = new Int32Array(2 * n).fill(-1);
  const keyToNode = new Map<number, number>();
  let nodeCount = 0;
  for (let i = 0; i < n; i++) {
    const pl = polylines[i];
    if (pl.closed || pl.points.length < 2) continue;
    chainable[i] = 1;
    for (let port = 0; port < 2; port++) {
      const p = port === 0 ? pl.points[0] : pl.points[pl.points.length - 1];
      const k = nodeKey(p);
      const existing = keyToNode.get(k);
      if (existing !== undefined) {
        nodeOf[2 * i + port] = existing;
      } else {
        nodeOf[2 * i + port] = nodeCount;
        keyToNode.set(k, nodeCount);
        nodeCount++;
      }
    }
  }
  if (nodeCount === 0) return polylines.slice();

  const alive = chainable.slice();

  // --- prune leaves ------------------------------------------------------------------------------
  // A spur is a short branch whose far end is a junction (ALGORITHMS.md §9). A short branch whose
  // *both* ends are free is not a spur, it is an isolated fragment, and dropping it here would
  // pre-empt the caller's own minimum-length decision.
  if (params.minBranchLength > 0 && params.pruneRounds > 0) {
    const rounds = Math.min(params.pruneRounds, MAX_PRUNE_ROUNDS);
    for (let round = 0; round < rounds; round++) {
      const degree = nodeDegrees(nodeOf, alive, nodeCount, n);
      const doomed = new Uint8Array(n);
      let removed = 0;
      for (let i = 0; i < n; i++) {
        if (alive[i] === 0) continue;
        const na = nodeOf[2 * i];
        const nb = nodeOf[2 * i + 1];
        if (na === nb) continue;
        if (polylineLength(polylines[i]) >= params.minBranchLength) continue;
        if ((degree[na] === 1 && degree[nb] >= 3) || (degree[nb] === 1 && degree[na] >= 3)) {
          doomed[i] = 1;
          removed++;
        }
      }
      if (removed === 0) break;
      // Applied after the whole pass, never in place: an in-place removal makes the result depend on
      // iteration order, which is how the two engines drift apart.
      for (let i = 0; i < n; i++) if (doomed[i] !== 0) alive[i] = 0;
    }
  }

  // --- arrival directions ------------------------------------------------------------------------
  const span = params.tangentSpan > 0 ? params.tangentSpan : 1;
  const tanX = new Float64Array(2 * n);
  const tanY = new Float64Array(2 * n);
  const hasTangent = new Uint8Array(2 * n);
  const scratch = new Float64Array(2);
  for (let i = 0; i < n; i++) {
    if (alive[i] === 0) continue;
    for (let port = 0; port < 2; port++) {
      if (!tangentAt(polylines[i].points, port === 0, span, scratch)) continue;
      tanX[2 * i + port] = scratch[0];
      tanY[2 * i + port] = scratch[1];
      hasTangent[2 * i + port] = 1;
    }
  }

  // --- candidate pairs ---------------------------------------------------------------------------
  const degree = nodeDegrees(nodeOf, alive, nodeCount, n);
  const offset = new Int32Array(nodeCount + 1);
  for (let v = 0; v < nodeCount; v++) offset[v + 1] = offset[v] + degree[v];
  const cursor = offset.slice();
  const endsAt = new Int32Array(offset[nodeCount]);
  for (let i = 0; i < n; i++) {
    if (alive[i] === 0) continue;
    endsAt[cursor[nodeOf[2 * i]]++] = 2 * i;
    endsAt[cursor[nodeOf[2 * i + 1]]++] = 2 * i + 1;
  }

  let turn = Math.fround(params.maxTurnDegrees);
  if (!Number.isFinite(turn) || turn < 0) turn = 0;
  if (turn > MAX_TURN_DEGREES) turn = MAX_TURN_DEGREES;
  const cosLimit = Math.cos((turn * Math.PI) / 180);
  const maxDegree = params.maxNodeDegree < 2 ? 2 : params.maxNodeDegree;

  const candDot: number[] = [];
  const candNode: number[] = [];
  const candA: number[] = [];
  const candB: number[] = [];
  for (let v = 0; v < nodeCount; v++) {
    const d = degree[v];
    if (d < 2 || d > maxDegree) continue;
    for (let a = offset[v]; a < offset[v + 1]; a++) {
      const ea = endsAt[a];
      if (hasTangent[ea] === 0) continue;
      for (let b = a + 1; b < offset[v + 1]; b++) {
        const eb = endsAt[b];
        if (hasTangent[eb] === 0) continue;
        // Both ends of one polyline at one node is a loop, not a crossing; trace() already emits
        // those closed, and joining a polyline to itself here would make the link graph inconsistent.
        if (ea >> 1 === eb >> 1) continue;
        // Both tangents point *away* from the node along their own polyline, so a stroke that runs
        // straight through has them exactly opposed: the continuation score is the negated dot
        // product, +1 for straight through and -1 for a hairpin.
        const dot = -(tanX[ea] * tanX[eb] + tanY[ea] * tanY[eb]);
        if (dot < cosLimit) continue;
        candDot.push(dot);
        candNode.push(v);
        candA.push(ea);
        candB.push(eb);
      }
    }
  }

  // --- greedy linkage ----------------------------------------------------------------------------
  // `(node, endA, endB)` is unique per candidate, so this is a total order and the sort cannot depend
  // on stability. Straightest first: at a crossing, the pair that continues wins over the pair that
  // turns, whichever order they were generated in.
  const order: number[] = new Array<number>(candDot.length);
  for (let i = 0; i < order.length; i++) order[i] = i;
  order.sort((p, q) => {
    if (candDot[p] !== candDot[q]) return candDot[q] - candDot[p];
    if (candNode[p] !== candNode[q]) return candNode[p] - candNode[q];
    if (candA[p] !== candA[q]) return candA[p] - candA[q];
    return candB[p] - candB[q];
  });
  const link = new Int32Array(2 * n).fill(-1);
  for (let c = 0; c < order.length; c++) {
    const ea = candA[order[c]];
    const eb = candB[order[c]];
    if (link[ea] >= 0 || link[eb] >= 0) continue;
    link[ea] = eb;
    link[eb] = ea;
  }

  // --- walk the chains ---------------------------------------------------------------------------
  const out: Polyline[] = [];
  const visited = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    if (chainable[i] === 0) {
      out.push(polylines[i]);
      continue;
    }
    if (alive[i] === 0 || visited[i] !== 0) continue;

    // Walk backwards to the chain's head. A walk that arrives back at the seed means the links close
    // a cycle, and the chain is a ring.
    // Annotated rather than inferred: `headRev` is assigned from a value read at an index computed
    // from `headRev`, and TypeScript reports that self-reference as a circular initializer.
    let headPoly: number = i;
    let headRev: boolean = false;
    let cyclic = false;
    for (let steps = 0; steps <= n; steps++) {
      const q: number = link[2 * headPoly + (headRev ? 1 : 0)];
      if (q < 0) break;
      const prevPoly = q >> 1;
      if (prevPoly === i) {
        cyclic = true;
        break;
      }
      // We leave the previous polyline through the port the link names. Leaving through port 0 means
      // it is traversed from its last point to its first, i.e. reversed.
      headRev = (q & 1) === 0;
      headPoly = prevPoly;
    }
    if (cyclic) {
      headPoly = i;
      headRev = false;
    }

    const pts: VecPoint[] = [];
    let curPoly: number = headPoly;
    let curRev: boolean = headRev;
    for (let used = 0; used <= n; used++) {
      visited[curPoly] = 1;
      appendRun(pts, polylines[curPoly].points, curRev, pts.length > 0);
      const q: number = link[2 * curPoly + (curRev ? 0 : 1)];
      if (q < 0) break;
      const nextPoly = q >> 1;
      if (visited[nextPoly] !== 0) break;
      curRev = (q & 1) === 1;
      curPoly = nextPoly;
    }

    if (cyclic && pts.length >= 4) {
      // The last polyline ends on the node the first one started from, so the closing vertex is
      // already in the list twice. Dropping it is what keeps a ring one closed path instead of a path
      // with a zero-length final segment.
      pts.pop();
      out.push({ points: pts, closed: true });
    } else {
      out.push({ points: pts, closed: false });
    }
  }
  return out;
}

/**
 * As {@link trace}, but also samples a distance transform at every vertex.
 *
 * @param dt a distance transform of the **pre-skeleton** mask — the distance from a skeleton pixel to
 *           the nearest background pixel is the stroke's half-width, and sampling a DT of the skeleton
 *           itself would return zero everywhere
 * @returns matching arrays: `widths[i][j]` is `2 * dt` at `polylines[i].points[j]`, i.e. the full stroke
 *          width in pixels.
 * @throws if `dt` is not the same size as `skeleton`.
 */
export function traceWithWidths(skeleton: Mask, dt: GrayF): PolylinesWithWidths {
  if (dt.width !== skeleton.width || dt.height !== skeleton.height) {
    throw new Error('traceWithWidths(): distance transform size does not match the skeleton');
  }
  const polylines = trace(skeleton);
  const widths: Float32Array[] = new Array<Float32Array>(polylines.length);
  for (let i = 0; i < polylines.length; i++) {
    const pts = polylines[i].points;
    const arr = new Float32Array(pts.length);
    for (let j = 0; j < pts.length; j++) arr[j] = 2 * dt.clamped(pts[j].x | 0, pts[j].y | 0);
    widths[i] = arr;
  }
  return { polylines, widths };
}
