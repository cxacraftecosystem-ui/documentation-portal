import { CubicSeg, VecPath, VecPoint, VecSeg } from './path';
import { detectCorners } from './simplify';

const DUP_EPS = 1e-7;

const NEWTON_ITERATIONS = 4;

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

interface Poly {
  readonly xs: Float64Array;
  readonly ys: Float64Array;
  readonly n: number;
}

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

    let previousErr = maxErr;
    for (let i = 0; i < NEWTON_ITERATIONS; i++) {
      reparameterize(xs, ys, first, last, u, bez);
      bez = generateBezier(xs, ys, first, last, u, task.t1x, task.t1y, task.t2x, task.t2y);
      maxErr = computeMaxError(xs, ys, first, last, bez, u, splitOut);
      if (maxErr < err) break;
      if (maxErr >= previousErr) break;
      previousErr = maxErr;
    }
    if (maxErr < err) {
      emit(out, bez[2], bez[3], bez[4], bez[5], bez[6], bez[7]);
      continue;
    }

    if (task.depth >= cap) {
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

    stack.push({
      first: split, last, t1x: -cx, t1y: -cy, t2x: task.t2x, t2y: task.t2y, depth: task.depth + 1,
    });
    stack.push({
      first, last: split, t1x: task.t1x, t1y: task.t1y, t2x: cx, t2y: cy, depth: task.depth + 1,
    });
  }
  return out;
}

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
