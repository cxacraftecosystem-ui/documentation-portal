import { GrayF, Mask, Px } from './buffers';
import { gaussianBlur, rectSum, summedAreaTable } from './convolve';

const OTSU_BINS = 256;

function otsuHistogram(src: GrayF): Int32Array {
  const hist = new Int32Array(OTSU_BINS);
  const d = src.data;
  for (let i = 0; i < d.length; i++) hist[Px.toByte255(d[i])]++;
  return hist;
}

interface OtsuSolution {
  readonly bin: number;
  readonly betweenVariance: number;
  readonly totalVariance: number;
}

function solveOtsu(hist: Int32Array): OtsuSolution {
  let total = 0;
  let sum = 0;
  for (let i = 0; i < OTSU_BINS; i++) {
    total += hist[i];
    sum += i * hist[i];
  }
  if (total === 0) return { bin: 0, betweenVariance: 0, totalVariance: 0 };
  const mean = sum / total;
  let totalVar = 0;
  for (let i = 0; i < OTSU_BINS; i++) {
    const d = i - mean;
    totalVar += hist[i] * d * d;
  }
  totalVar /= total;

  let w0 = 0;
  let s0 = 0;
  let best = 0;
  let bestBin = 0;
  for (let t = 0; t < OTSU_BINS - 1; t++) {
    w0 += hist[t];
    s0 += t * hist[t];
    if (w0 === 0) continue;
    const w1 = total - w0;
    if (w1 === 0) break;
    const m0 = s0 / w0;
    const m1 = (sum - s0) / w1;
    const dm = m0 - m1;
    const between = ((w0 / total) * (w1 / total)) * dm * dm;
    if (between > best) {
      best = between;
      bestBin = t;
    }
  }
  return { bin: bestBin, betweenVariance: best, totalVariance: totalVar };
}

export function otsu(src: GrayF): number {
  const sol = solveOtsu(otsuHistogram(src));
  return (sol.bin + 0.5) / 255;
}

export function otsuSeparability(src: GrayF): number {
  const sol = solveOtsu(otsuHistogram(src));
  if (!(sol.totalVariance > 0)) return 0;
  return Px.clamp01(sol.betweenVariance / sol.totalVariance);
}

export function fixed(src: GrayF, t: number, invert = false): Mask {
  const n = src.data.length;
  const out = new Uint8Array(n);
  const d = src.data;
  if (invert) {
    for (let i = 0; i < n; i++) out[i] = d[i] <= t ? 1 : 0;
  } else {
    for (let i = 0; i < n; i++) out[i] = d[i] > t ? 1 : 0;
  }
  return new Mask(src.width, src.height, out);
}

export function adaptiveMean(src: GrayF, radius: number, c: number, invert = false): Mask {
  const w = src.width;
  const h = src.height;
  const r = Math.max(0, radius | 0);
  const sat = summedAreaTable(src);
  const out = new Uint8Array(w * h);
  const d = src.data;
  for (let y = 0; y < h; y++) {
    const y0 = y - r;
    const y1 = y + r;
    const cy0 = y0 < 0 ? 0 : y0;
    const cy1 = y1 > h - 1 ? h - 1 : y1;
    const rows = cy1 - cy0 + 1;
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const x0 = x - r;
      const x1 = x + r;
      const cx0 = x0 < 0 ? 0 : x0;
      const cx1 = x1 > w - 1 ? w - 1 : x1;
      const count = rows * (cx1 - cx0 + 1);
      const mean = rectSum(sat, w, h, x0, y0, x1, y1) / count;
      const v = d[row + x];
      out[row + x] = invert ? (v <= mean - c ? 1 : 0) : v > mean - c ? 1 : 0;
    }
  }
  return new Mask(w, h, out);
}

export function adaptiveGaussian(src: GrayF, sigma: number, c: number, invert = false): Mask {
  const local = gaussianBlur(src, sigma);
  const n = src.data.length;
  const out = new Uint8Array(n);
  const d = src.data;
  const m = local.data;
  for (let i = 0; i < n; i++) {
    const t = m[i] - c;
    out[i] = invert ? (d[i] <= t ? 1 : 0) : d[i] > t ? 1 : 0;
  }
  return new Mask(src.width, src.height, out);
}

const SAUVOLA_R = 0.5;

export function sauvola(src: GrayF, radius: number, k = 0.2, invert = false): Mask {
  const w = src.width;
  const h = src.height;
  const r = Math.max(0, radius | 0);
  const sat = summedAreaTable(src);
  const sq = new GrayF(w, h, squared(src.data));
  const satSq = summedAreaTable(sq);
  const out = new Uint8Array(w * h);
  const d = src.data;
  for (let y = 0; y < h; y++) {
    const y0 = y - r;
    const y1 = y + r;
    const cy0 = y0 < 0 ? 0 : y0;
    const cy1 = y1 > h - 1 ? h - 1 : y1;
    const rows = cy1 - cy0 + 1;
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const x0 = x - r;
      const x1 = x + r;
      const cx0 = x0 < 0 ? 0 : x0;
      const cx1 = x1 > w - 1 ? w - 1 : x1;
      const count = rows * (cx1 - cx0 + 1);
      const inv = 1 / count;
      const mean = rectSum(sat, w, h, x0, y0, x1, y1) * inv;
      const meanSq = rectSum(satSq, w, h, x0, y0, x1, y1) * inv;
      const variance = meanSq - mean * mean;
      const std = variance > 0 ? Math.sqrt(variance) : 0;
      const t = mean * (1 + k * (std / SAUVOLA_R - 1));
      const v = d[row + x];
      out[row + x] = invert ? (v <= t ? 1 : 0) : v > t ? 1 : 0;
    }
  }
  return new Mask(w, h, out);
}

function squared(d: Float32Array): Float32Array {
  const out = new Float32Array(d.length);
  for (let i = 0; i < d.length; i++) out[i] = d[i] * d[i];
  return out;
}

export function hysteresis(src: GrayF, low: number, high: number): Mask {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const d = src.data;
  const lo = Math.min(low, high);
  const hi = Math.max(low, high);
  const out = new Uint8Array(n);
  const stack = new Int32Array(n);
  let sp = 0;
  for (let i = 0; i < n; i++) {
    if (d[i] > hi) {
      out[i] = 1;
      stack[sp++] = i;
    }
  }
  while (sp > 0) {
    const idx = stack[--sp];
    const y = (idx / w) | 0;
    const x = idx - y * w;
    const yStart = y > 0 ? y - 1 : 0;
    const yEnd = y < h - 1 ? y + 1 : h - 1;
    const xStart = x > 0 ? x - 1 : 0;
    const xEnd = x < w - 1 ? x + 1 : w - 1;
    for (let ny = yStart; ny <= yEnd; ny++) {
      const row = ny * w;
      for (let nx = xStart; nx <= xEnd; nx++) {
        const ni = row + nx;
        if (out[ni] === 0 && d[ni] > lo) {
          out[ni] = 1;
          stack[sp++] = ni;
        }
      }
    }
  }
  return new Mask(w, h, out);
}

export function autoCannyThresholds(magnitude: GrayF, sigma = 0.33): Float32Array {
  const m = median(magnitude);
  const s = Math.abs(sigma);
  const out = new Float32Array(2);
  out[0] = Math.max(0, (1 - s) * m);
  out[1] = Math.min(1, (1 + s) * m);
  if (out[1] < out[0]) out[1] = out[0];
  return out;
}

const MEDIAN_BINS = 256;

export function median(src: GrayF): number {
  const { lo, hi } = src.range();
  const span = hi - lo;
  if (!(span > 1e-12)) return lo;
  const d = src.data;
  const n = d.length;
  const hist = new Int32Array(MEDIAN_BINS);
  const inv = 1 / span;
  for (let i = 0; i < n; i++) {
    let b = ((d[i] - lo) * inv * MEDIAN_BINS) | 0;
    if (b < 0) b = 0;
    else if (b > MEDIAN_BINS - 1) b = MEDIAN_BINS - 1;
    hist[b]++;
  }
  const half = (n / 2) | 0;
  let cum = 0;
  for (let b = 0; b < MEDIAN_BINS; b++) {
    cum += hist[b];
    if (cum > half) return lo + ((b + 0.5) / MEDIAN_BINS) * span;
  }
  return hi;
}
