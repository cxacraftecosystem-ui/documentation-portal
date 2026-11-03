import { VecPoint } from './path';

const MAX_CHAIKIN_ITERATIONS = 8;

export function chaikin(
  points: readonly VecPoint[],
  iterations: number,
  closed: boolean,
): VecPoint[] {
  const iters = Math.min(MAX_CHAIKIN_ITERATIONS, Math.max(0, iterations | 0));
  let cur: VecPoint[] = points.slice();
  if (iters === 0 || cur.length < 3) return cur;
  for (let it = 0; it < iters; it++) {
    const n = cur.length;
    const next: VecPoint[] = [];
    if (!closed) next.push(cur[0]);
    const limit = closed ? n : n - 1;
    for (let i = 0; i < limit; i++) {
      const a = cur[i];
      const b = cur[(i + 1) % n];
      next.push({ x: a.x * 0.75 + b.x * 0.25, y: a.y * 0.75 + b.y * 0.25 });
      next.push({ x: a.x * 0.25 + b.x * 0.75, y: a.y * 0.25 + b.y * 0.75 });
    }
    if (!closed) next.push(cur[n - 1]);
    cur = next;
  }
  return cur;
}

export function gaussian(
  points: readonly VecPoint[],
  sigma: number,
  closed: boolean,
): VecPoint[] {
  const n = points.length;
  if (n < 3 || !(sigma > 0)) return points.slice();
  const r = Math.max(1, Math.ceil(3 * sigma));
  const k = new Float64Array(2 * r + 1);
  const inv = 1 / (2 * sigma * sigma);
  let sum = 0;
  for (let i = -r; i <= r; i++) {
    const v = Math.exp(-(i * i) * inv);
    k[i + r] = v;
    sum += v;
  }
  for (let i = 0; i < k.length; i++) k[i] /= sum;
  const out: VecPoint[] = new Array<VecPoint>(n);
  for (let i = 0; i < n; i++) {
    if (!closed && (i === 0 || i === n - 1)) {
      out[i] = points[i];
      continue;
    }
    let sx = 0;
    let sy = 0;
    for (let j = -r; j <= r; j++) {
      let m = i + j;
      if (closed) m = ((m % n) + n) % n;
      else m = m < 0 ? 0 : m >= n ? n - 1 : m;
      const w = k[j + r];
      sx += points[m].x * w;
      sy += points[m].y * w;
    }
    out[i] = { x: sx, y: sy };
  }
  return out;
}

export function curvatureAware(
  points: readonly VecPoint[],
  sigma: number,
  corners: Int32Array | readonly number[],
  closed: boolean,
): VecPoint[] {
  const n = points.length;
  if (n < 3 || !(sigma > 0)) return points.slice();
  if (corners.length === 0) return gaussian(points, sigma, closed);

  const dist = new Int32Array(n).fill(n);
  for (let c = 0; c < corners.length; c++) {
    const ci = corners[c] | 0;
    if (ci < 0 || ci >= n) continue;
    for (let i = 0; i < n; i++) {
      let d = Math.abs(i - ci);
      if (closed) d = Math.min(d, n - d);
      if (d < dist[i]) dist[i] = d;
    }
  }
  const reach = Math.max(1, Math.ceil(6 * sigma));
  const out: VecPoint[] = new Array<VecPoint>(n);
  for (let i = 0; i < n; i++) {
    const localSigma = sigma * Math.min(1, dist[i] / reach);
    if (localSigma <= 0.01 || (!closed && (i === 0 || i === n - 1))) {
      out[i] = points[i];
      continue;
    }
    const r = Math.max(1, Math.ceil(3 * localSigma));
    const inv = 1 / (2 * localSigma * localSigma);
    let sx = 0;
    let sy = 0;
    let wsum = 0;
    for (let j = -r; j <= r; j++) {
      let m = i + j;
      if (closed) m = ((m % n) + n) % n;
      else m = m < 0 ? 0 : m >= n ? n - 1 : m;
      const w = Math.exp(-(j * j) * inv);
      sx += points[m].x * w;
      sy += points[m].y * w;
      wsum += w;
    }
    out[i] = { x: sx / wsum, y: sy / wsum };
  }
  return out;
}

export function movingAverage(values: Float32Array, window: number): Float32Array {
  const n = values.length;
  const out = new Float32Array(n);
  let win = Math.max(1, window | 0);
  if ((win & 1) === 0) win++;
  if (win === 1 || n === 0) {
    out.set(values);
    return out;
  }
  const r = (win - 1) >> 1;
  for (let i = 0; i < n; i++) {
    let sum = 0;
    for (let j = -r; j <= r; j++) {
      const m = i + j;
      sum += values[m < 0 ? 0 : m >= n ? n - 1 : m];
    }
    out[i] = sum / win;
  }
  return out;
}
