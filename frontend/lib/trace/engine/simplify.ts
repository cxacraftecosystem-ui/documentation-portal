import { VecPoint } from './path';

const MIN_ARM_SQ = 1.0;

const MIN_SPACING = 0.01;

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

export function removeCollinear(points: readonly VecPoint[], tolerance = 0.05): VecPoint[] {
  const n = points.length;
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
    const maxK = Math.floor(n / 3);
    if (k > maxK) k = maxK < 1 ? 1 : maxK;
  }

  const cosT = Math.cos((thresholdDegrees * Math.PI) / 180);
  const score = new Float64Array(n);
  const isCorner = new Uint8Array(n);
  const lo = closed ? 0 : 1;
  const hi = closed ? n - 1 : n - 2;
  const at = (i: number, d: number): number =>
    closed ? (i + d + n) % n : Math.max(0, Math.min(n - 1, i + d));
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
    if (ia === ib) continue;
    const c = points[i];
    let ax = points[ia].x - c.x;
    let ay = points[ia].y - c.y;
    let bx = points[ib].x - c.x;
    let by = points[ib].y - c.y;
    const la = Math.hypot(ax, ay);
    const lb = Math.hypot(bx, by);
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
  for (let i = 0; i < n; i++) {
    if (isCorner[i] === 0) continue;
    let best = true;
    for (let j = -(k - 1); j <= k - 1; j++) {
      if (j === 0) continue;
      const m = closed ? (i + j + n) % n : i + j;
      if (m < 0 || m >= n || isCorner[m] === 0) continue;
      if (score[m] > score[i]) {
        best = false;
        break;
      }
    }
    if (best) outIdx.push(i);
  }
  return Int32Array.from(outIdx);
}

export function splitAtCorners(
  points: readonly VecPoint[],
  corners: Int32Array | readonly number[],
): VecPoint[][] {
  const n = points.length;
  if (n < 2) return [points.slice()];
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

export function resample(points: readonly VecPoint[], spacing: number): VecPoint[] {
  const n = points.length;
  if (n < 2) return points.slice();
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
