import { GrayF, Px } from './buffers';
import { gaussianBlur } from './convolve';

export function clahe(src: GrayF, tilesX = 8, tilesY = 8, clipLimit = 2): GrayF {
  const w = src.width;
  const h = src.height;
  const tx = Px.clampInt(tilesX, 1, w);
  const ty = Px.clampInt(tilesY, 1, h);
  const d = src.data;

  const xb = new Int32Array(tx + 1);
  const yb = new Int32Array(ty + 1);
  for (let i = 0; i <= tx; i++) xb[i] = Math.floor((i * w) / tx);
  for (let i = 0; i <= ty; i++) yb[i] = Math.floor((i * h) / ty);

  const luts = new Int32Array(tx * ty * 256);
  const hist = new Int32Array(256);
  for (let tyi = 0; tyi < ty; tyi++) {
    for (let txi = 0; txi < tx; txi++) {
      hist.fill(0);
      const x0 = xb[txi];
      const x1 = xb[txi + 1];
      const y0 = yb[tyi];
      const y1 = yb[tyi + 1];
      let count = 0;
      let occupied = 0;
      for (let y = y0; y < y1; y++) {
        const row = y * w;
        for (let x = x0; x < x1; x++) {
          const bin = Px.toByte255(d[row + x]);
          if (hist[bin] === 0) occupied++;
          hist[bin]++;
          count++;
        }
      }
      buildClaheLut(hist, count, occupied, clipLimit, luts, (tyi * tx + txi) * 256);
    }
  }

  const cx = new Float32Array(tx);
  const cy = new Float32Array(ty);
  for (let i = 0; i < tx; i++) cx[i] = (xb[i] + xb[i + 1] - 1) * 0.5;
  for (let i = 0; i < ty; i++) cy[i] = (yb[i] + yb[i + 1] - 1) * 0.5;

  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    let ty0 = 0;
    while (ty0 < ty - 2 && y > cy[ty0 + 1]) ty0++;
    const ty1 = Math.min(ty - 1, ty0 + 1);
    const spanY = cy[ty1] - cy[ty0];
    const fy = spanY > 0 ? Px.clamp((y - cy[ty0]) / spanY, 0, 1) : 0;
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let tx0 = 0;
      while (tx0 < tx - 2 && x > cx[tx0 + 1]) tx0++;
      const tx1 = Math.min(tx - 1, tx0 + 1);
      const spanX = cx[tx1] - cx[tx0];
      const fx = spanX > 0 ? Px.clamp((x - cx[tx0]) / spanX, 0, 1) : 0;
      const bin = Px.toByte255(d[row + x]);
      const v00 = luts[(ty0 * tx + tx0) * 256 + bin];
      const v10 = luts[(ty0 * tx + tx1) * 256 + bin];
      const v01 = luts[(ty1 * tx + tx0) * 256 + bin];
      const v11 = luts[(ty1 * tx + tx1) * 256 + bin];
      const a = v00 + (v10 - v00) * fx;
      const b = v01 + (v11 - v01) * fx;
      out[row + x] = (a + (b - a) * fy) / 255;
    }
  }
  return new GrayF(w, h, out);
}

function buildClaheLut(
  hist: Int32Array,
  count: number,
  occupied: number,
  clipLimit: number,
  dest: Int32Array,
  destOffset: number,
): void {
  if (occupied <= 1) {
    for (let i = 0; i < 256; i++) dest[destOffset + i] = i;
    return;
  }
  if (clipLimit > 0) {
    const limit = Math.max(1, Math.floor((clipLimit * count) / 256));
    let excess = 0;
    for (let i = 0; i < 256; i++) {
      if (hist[i] > limit) {
        excess += hist[i] - limit;
        hist[i] = limit;
      }
    }
    const share = (excess / 256) | 0;
    const rest = excess - share * 256;
    for (let i = 0; i < 256; i++) hist[i] += share + (i < rest ? 1 : 0);
  }
  let cum = 0;
  const denom = 2 * count;
  for (let i = 0; i < 256; i++) {
    cum += hist[i];
    dest[destOffset + i] = Math.floor((cum * 510 + count) / denom);
  }
}

export function equalize(src: GrayF): GrayF {
  const d = src.data;
  const n = d.length;
  const hist = new Int32Array(256);
  for (let i = 0; i < n; i++) hist[Px.toByte255(d[i])]++;
  let cum = 0;
  let cumMin = -1;
  const cdf = new Float32Array(256);
  for (let i = 0; i < 256; i++) {
    cum += hist[i];
    if (cumMin < 0 && hist[i] > 0) cumMin = cum;
    cdf[i] = cum;
  }
  const lo = cumMin < 0 ? 0 : cumMin;
  const span = cum - lo;
  if (span <= 0) return src.copy();
  const inv = 1 / span;
  const out = new Float32Array(n);
  for (let i = 0; i < 256; i++) cdf[i] = Px.clamp01((cdf[i] - lo) * inv);
  for (let i = 0; i < n; i++) out[i] = cdf[Px.toByte255(d[i])];
  return new GrayF(src.width, src.height, out);
}

export function gamma(src: GrayF, gammaValue: number): GrayF {
  if (!(gammaValue > 0)) return src.copy();
  if (gammaValue === 1) return src.copy();
  const inv = 1 / gammaValue;
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  for (let i = 0; i < n; i++) out[i] = Math.pow(Px.clamp01(d[i]), inv);
  return new GrayF(src.width, src.height, out);
}

export function levels(src: GrayF, black: number, white: number, gammaValue: number): GrayF {
  const span = white - black;
  if (!(Math.abs(span) > 1e-6) || !(gammaValue > 0)) return src.copy();
  const inv = 1 / span;
  const invG = 1 / gammaValue;
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  const identityGamma = gammaValue === 1;
  for (let i = 0; i < n; i++) {
    const t = Px.clamp01((Px.clamp(d[i], Math.min(black, white), Math.max(black, white)) - black) * inv);
    out[i] = identityGamma ? t : Math.pow(t, invG);
  }
  return new GrayF(src.width, src.height, out);
}

export function brightnessContrast(src: GrayF, brightness: number, contrast: number): GrayF {
  const b = Px.clamp(brightness, -1, 1);
  const c = Px.clamp(contrast, -0.999, 0.999);
  const slope = (1 + c) / (1 - c);
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  for (let i = 0; i < n; i++) out[i] = Px.clamp01((d[i] - 0.5) * slope + 0.5 + b);
  return new GrayF(src.width, src.height, out);
}

export function unsharpMask(src: GrayF, sigma: number, amount: number, threshold = 0): GrayF {
  if (amount === 0) return src.copy();
  const blurred = gaussianBlur(src, sigma);
  const n = src.data.length;
  const out = new Float32Array(n);
  const a = src.data;
  const b = blurred.data;
  const t = Math.abs(threshold);
  for (let i = 0; i < n; i++) {
    const diff = a[i] - b[i];
    out[i] = Math.abs(diff) > t ? a[i] + amount * diff : a[i];
  }
  return new GrayF(src.width, src.height, out);
}

export function stretch(src: GrayF): GrayF {
  const { lo, hi } = src.range();
  const span = hi - lo;
  if (!(span > 1e-6)) return src.copy();
  const inv = 1 / span;
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  for (let i = 0; i < n; i++) out[i] = (d[i] - lo) * inv;
  return new GrayF(src.width, src.height, out);
}

const PERCENTILE_BINS = 256;

export function percentileStretch(src: GrayF, percentile = 1): GrayF {
  const p = Px.clamp(percentile, 0, 49);
  if (p === 0) return stretch(src);
  const { lo, hi } = src.range();
  const span = hi - lo;
  if (!(span > 1e-6)) return src.copy();
  const d = src.data;
  const n = d.length;
  const hist = new Int32Array(PERCENTILE_BINS);
  const invSpan = 1 / span;
  for (let i = 0; i < n; i++) {
    let bin = ((d[i] - lo) * invSpan * PERCENTILE_BINS) | 0;
    if (bin < 0) bin = 0;
    else if (bin >= PERCENTILE_BINS) bin = PERCENTILE_BINS - 1;
    hist[bin]++;
  }
  const loV = lo + percentileAt(hist, n, (p / 100) * n) * span;
  const hiV = lo + percentileAt(hist, n, ((100 - p) / 100) * n) * span;
  const outSpan = hiV - loV;
  if (!(outSpan > 1e-6)) return stretch(src);
  const inv = 1 / outSpan;
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) out[i] = Px.clamp01((d[i] - loV) * inv);
  return new GrayF(src.width, src.height, out);
}

function percentileAt(hist: Int32Array, n: number, rank: number): number {
  const bins = hist.length;
  if (bins === 0 || n <= 0) return 0;
  const target = Px.clamp(rank, 0, n);
  let cum = 0;
  for (let b = 0; b < bins; b++) {
    const count = hist[b];
    if (count === 0) continue;
    const next = cum + count;
    if (next >= target) {
      const fraction = Px.clamp((target - cum) / count, 0, 1);
      return (b + fraction) / bins;
    }
    cum = next;
  }
  return 1;
}

export function invert(src: GrayF): GrayF {
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  for (let i = 0; i < n; i++) out[i] = 1 - d[i];
  return new GrayF(src.width, src.height, out);
}

export function histogram(src: GrayF, bins = 256): Int32Array {
  const nb = Px.clampInt(bins, 1, 65536);
  const out = new Int32Array(nb);
  const d = src.data;
  if (nb === 1) {
    out[0] = d.length;
    return out;
  }
  const scale = nb - 1;
  for (let i = 0; i < d.length; i++) {
    let b = Math.round(Px.clamp01(d[i]) * scale);
    if (b < 0) b = 0;
    else if (b >= nb) b = nb - 1;
    out[b]++;
  }
  return out;
}
