import {
  DEFAULT_FLATTEN_TOLERANCE,
  FillRule,
  VecPath,
  VecPoint,
  VecSeg,
} from './path';

/**
 * Polygon boolean operations on flattened outlines.
 *
 * The approach is edge splitting plus inside/outside classification, then reassembly — the same skeleton
 * as Greiner-Hormann without its doubly linked list. Every edge of A is cut at each crossing with B and
 * vice versa, each resulting fragment is classified by testing its midpoint against the other operand,
 * the operation selects a subset, and the selected fragments are chained back into closed loops.
 *
 * **Known limitation, stated rather than hidden:** exactly collinear overlapping edges are not special
 * cased. Two rectangles sharing an edge exactly may produce a seam. Every consumer in this app feeds
 * traced outlines, which never share an edge to the last bit, and the general solution (Vatti or
 * Martinez-Rueda with full degeneracy handling) is several times this much code for a case that does not
 * arise here.
 */

export enum BoolOp {
  UNION = 'UNION',
  INTERSECT = 'INTERSECT',
  DIFFERENCE = 'DIFFERENCE',
  XOR = 'XOR',
}

/** Chaining snaps endpoints to this grid; finer than any tolerance the flattener can produce. */
const SNAP = 1e-6;

/** Signed shoelace area. Positive is clockwise in y-down (image/document) coordinates. */
export function polygonArea(points: readonly VecPoint[]): number {
  const n = points.length;
  if (n < 3) return 0;
  let s = 0;
  for (let i = 0; i < n; i++) {
    const a = points[i];
    const b = points[(i + 1) % n];
    s += a.x * b.y - b.x * a.y;
  }
  return s * 0.5;
}

/**
 * Point-in-polygon.
 *
 * @param rule EVENODD uses a crossing count, NONZERO a winding number. They differ for
 *             self-intersecting outlines, which is exactly the case a stroke outline produces.
 * @returns true when `(x, y)` is inside. Points exactly on an edge are not guaranteed either way, which
 *          is why the classifier below tests segment **midpoints** offset off the edge.
 */
export function pointInPolygon(
  points: readonly VecPoint[],
  x: number,
  y: number,
  rule: FillRule,
): boolean {
  const n = points.length;
  if (n < 3) return false;
  if (rule === FillRule.EVENODD) {
    let inside = false;
    for (let i = 0, j = n - 1; i < n; j = i++) {
      const a = points[i];
      const b = points[j];
      if (a.y > y !== b.y > y) {
        const t = (y - a.y) / (b.y - a.y);
        if (x < a.x + t * (b.x - a.x)) inside = !inside;
      }
    }
    return inside;
  }
  let winding = 0;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const a = points[i];
    const b = points[j];
    if (a.y <= y) {
      if (b.y > y && (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y) > 0) winding++;
    } else if (b.y <= y && (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y) < 0) {
      winding--;
    }
  }
  return winding !== 0;
}

/** Multi-polygon containment with the non-zero rule: sum the winding across every ring. */
function insideSet(rings: readonly VecPoint[][], x: number, y: number): boolean {
  let winding = 0;
  for (let r = 0; r < rings.length; r++) {
    const points = rings[r];
    const n = points.length;
    for (let i = 0, j = n - 1; i < n; j = i++) {
      const a = points[i];
      const b = points[j];
      if (a.y <= y) {
        if (b.y > y && (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y) > 0) winding++;
      } else if (b.y <= y && (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y) < 0) {
        winding--;
      }
    }
  }
  return winding !== 0;
}

function toRings(paths: readonly VecPath[], flatten: number): VecPoint[][] {
  const rings: VecPoint[][] = [];
  for (const p of paths) {
    const pts = p.flatten(flatten);
    // Drop a repeated closing vertex; the ring is implicitly closed and a duplicate makes a zero-length
    // edge that the chainer cannot match.
    while (pts.length > 1) {
      const a = pts[0];
      const b = pts[pts.length - 1];
      if (Math.abs(a.x - b.x) < SNAP && Math.abs(a.y - b.y) < SNAP) pts.pop();
      else break;
    }
    if (pts.length >= 3) rings.push(pts);
  }
  return rings;
}

/** A directed fragment of an input edge, after cutting at every crossing. */
interface Fragment {
  ax: number;
  ay: number;
  bx: number;
  by: number;
}

function splitRings(rings: readonly VecPoint[][], others: readonly VecPoint[][]): Fragment[] {
  const out: Fragment[] = [];
  const ts: number[] = [];
  for (let r = 0; r < rings.length; r++) {
    const ring = rings[r];
    const n = ring.length;
    for (let i = 0; i < n; i++) {
      const a = ring[i];
      const b = ring[(i + 1) % n];
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      if (Math.abs(dx) < SNAP && Math.abs(dy) < SNAP) continue;
      ts.length = 0;
      for (let s = 0; s < others.length; s++) {
        const o = others[s];
        const m = o.length;
        for (let j = 0; j < m; j++) {
          const c = o[j];
          const d = o[(j + 1) % m];
          const ex = d.x - c.x;
          const ey = d.y - c.y;
          const denom = dx * ey - dy * ex;
          if (Math.abs(denom) < 1e-14) continue;
          const t = ((c.x - a.x) * ey - (c.y - a.y) * ex) / denom;
          const u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / denom;
          if (t > SNAP && t < 1 - SNAP && u >= 0 && u <= 1) ts.push(t);
        }
      }
      ts.sort((p, q) => p - q);
      let prev = 0;
      for (let k = 0; k <= ts.length; k++) {
        const cut = k === ts.length ? 1 : ts[k];
        if (cut - prev > SNAP) {
          out.push({
            ax: a.x + dx * prev,
            ay: a.y + dy * prev,
            bx: a.x + dx * cut,
            by: a.y + dy * cut,
          });
        }
        prev = cut;
      }
    }
  }
  return out;
}

function keyOf(x: number, y: number): string {
  return `${Math.round(x / SNAP)},${Math.round(y / SNAP)}`;
}

/** Chain directed fragments head-to-tail into closed rings, dropping anything that cannot close. */
function chain(fragments: readonly Fragment[]): VecPoint[][] {
  const buckets = new Map<string, number[]>();
  for (let i = 0; i < fragments.length; i++) {
    const k = keyOf(fragments[i].ax, fragments[i].ay);
    const list = buckets.get(k);
    if (list === undefined) buckets.set(k, [i]);
    else list.push(i);
  }
  const used = new Uint8Array(fragments.length);
  const rings: VecPoint[][] = [];
  for (let start = 0; start < fragments.length; start++) {
    if (used[start] !== 0) continue;
    const ring: VecPoint[] = [];
    let cur = start;
    const startKey = keyOf(fragments[start].ax, fragments[start].ay);
    let guard = 0;
    for (;;) {
      used[cur] = 1;
      ring.push({ x: fragments[cur].ax, y: fragments[cur].ay });
      const endKey = keyOf(fragments[cur].bx, fragments[cur].by);
      if (endKey === startKey) break;
      const candidates = buckets.get(endKey);
      let nextIdx = -1;
      if (candidates !== undefined) {
        for (let c = 0; c < candidates.length; c++) {
          if (used[candidates[c]] === 0) {
            nextIdx = candidates[c];
            break;
          }
        }
      }
      if (nextIdx < 0 || guard++ > fragments.length) break;
      cur = nextIdx;
    }
    if (ring.length >= 3) rings.push(ring);
  }
  return rings;
}

function ringToPath(points: readonly VecPoint[]): VecPath {
  const segs = [];
  for (let i = 1; i < points.length; i++) segs.push(VecSeg.line(points[i]));
  return new VecPath(points[0], segs, true);
}

/**
 * Boolean combination of two path sets.
 *
 * Curves are flattened first, so the result is a polygonal approximation of the true boolean — which is
 * what every consumer here wants, since the outputs go to a cutter, a rasteriser or another boolean.
 *
 * @param op DIFFERENCE is `a - b`
 * @param flatten flattening tolerance in document units
 * @returns closed polygonal paths; an empty list when the result is empty. Degenerate inputs (fewer than
 *          three points in every ring) return the other operand's contribution rather than throwing.
 */
export function apply(
  a: readonly VecPath[],
  b: readonly VecPath[],
  op: BoolOp,
  flatten = DEFAULT_FLATTEN_TOLERANCE,
): VecPath[] {
  const ra = toRings(a, flatten);
  const rb = toRings(b, flatten);
  if (ra.length === 0 && rb.length === 0) return [];
  if (rb.length === 0) {
    if (op === BoolOp.INTERSECT) return [];
    return ra.map(ringToPath);
  }
  if (ra.length === 0) {
    if (op === BoolOp.INTERSECT || op === BoolOp.DIFFERENCE) return [];
    return rb.map(ringToPath);
  }

  const fragA = splitRings(ra, rb);
  const fragB = splitRings(rb, ra);
  const selected: Fragment[] = [];

  // Classification uses the fragment midpoint. A fragment is cut at every crossing, so its interior
  // never straddles the other operand's boundary and one sample decides the whole fragment.
  for (let i = 0; i < fragA.length; i++) {
    const f = fragA[i];
    const inside = insideSet(rb, (f.ax + f.bx) / 2, (f.ay + f.by) / 2);
    const want = op === BoolOp.INTERSECT ? inside : !inside;
    if (want) selected.push(f);
    else if (op === BoolOp.XOR) selected.push({ ax: f.bx, ay: f.by, bx: f.ax, by: f.ay });
  }
  for (let i = 0; i < fragB.length; i++) {
    const f = fragB[i];
    const inside = insideSet(ra, (f.ax + f.bx) / 2, (f.ay + f.by) / 2);
    if (op === BoolOp.UNION) {
      if (!inside) selected.push(f);
    } else if (op === BoolOp.INTERSECT) {
      if (inside) selected.push(f);
    } else if (op === BoolOp.DIFFERENCE) {
      // B's inner fragments become the hole boundary, traversed backwards so the winding subtracts.
      if (inside) selected.push({ ax: f.bx, ay: f.by, bx: f.ax, by: f.ay });
    } else if (inside) {
      selected.push({ ax: f.bx, ay: f.by, bx: f.ax, by: f.ay });
    } else {
      selected.push(f);
    }
  }
  return chain(selected).map(ringToPath);
}
