import { GrayF, Mask, Px } from './buffers';
import { gaussianBlur, rectSum, summedAreaTable } from './convolve';

/**
 * Thresholding. See ALGORITHMS.md §6.
 *
 * Every function here that returns a {@link Mask} takes the same `invert` flag with the same meaning:
 * without it the foreground is `value > threshold`, with it the foreground is `value <= threshold`.
 * Ink on paper is *dark*, so the ink-extraction paths pass `invert = true`; edge responses are
 * *bright*, so they do not. Keeping one flag with one meaning is why no caller has to think about it.
 */

const OTSU_BINS = 256;

function otsuHistogram(src: GrayF): Int32Array {
  const hist = new Int32Array(OTSU_BINS);
  const d = src.data;
  for (let i = 0; i < d.length; i++) hist[Px.toByte255(d[i])]++;
  return hist;
}

/** Between-class variance and the total variance of a 256-bin histogram, plus the best split index. */
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
    // omega0 * omega1 * (mu0 - mu1)^2, with the class weights left as fractions of the total.
    const between = ((w0 / total) * (w1 / total)) * dm * dm;
    if (between > best) {
      best = between;
      bestBin = t;
    }
  }
  return { bin: bestBin, betweenVariance: best, totalVariance: totalVar };
}

/**
 * Otsu's threshold by exhaustive search over the 256 histogram splits.
 *
 * The returned value sits **between** the two bin centres of the winning split (`(bin + 0.5) / 255`),
 * so comparing `value > threshold` reproduces exactly the partition the search scored. Returning
 * `bin / 255` instead puts the threshold on top of a populated level and flips those pixels.
 *
 * @returns the threshold in 0..1; 0 for an empty or single-level image.
 */
export function otsu(src: GrayF): number {
  const sol = solveOtsu(otsuHistogram(src));
  return (sol.bin + 0.5) / 255;
}

/**
 * Otsu's separability `sigma_b^2 / sigma_total^2` — how convincingly bimodal the image is.
 * @returns 0..1; 0 when the image is flat. Used by {@link module:classify} to decide that a source is
 *          already line art and must not be run through an edge detector.
 */
export function otsuSeparability(src: GrayF): number {
  const sol = solveOtsu(otsuHistogram(src));
  if (!(sol.totalVariance > 0)) return 0;
  return Px.clamp01(sol.betweenVariance / sol.totalVariance);
}

/**
 * Global threshold.
 * @param invert false → foreground is `value > t`; true → foreground is `value <= t`
 */
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

/**
 * Adaptive mean threshold: `out = in > localMean(radius) - c`. The local mean comes from a summed-area
 * table so cost is independent of `radius`, and windows that overhang the border average only the
 * pixels that exist.
 *
 * @param radius window half-width in pixels; <= 0 falls back to a global mean
 * @param c      bias subtracted from the local mean; larger values keep less ink
 */
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

/**
 * Adaptive threshold whose local mean is a Gaussian blur rather than a box mean — smoother, and
 * noticeably better on a background gradient that is not axis aligned.
 */
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

/** Dynamic range of 0..1 data, the `R` of Sauvola's formula. */
const SAUVOLA_R = 0.5;

/**
 * Sauvola's local threshold `T = m * (1 + k * (s / R - 1))` with `R = 0.5` for 0..1 data.
 *
 * The right default for photographed documents and faded artwork: where the background gradient is
 * strong, plain adaptive-mean smears, because the mean of a window that straddles a fold is not a
 * meaningful reference and the standard deviation is.
 *
 * @param radius window half-width in pixels
 * @param k      typically 0.2; larger keeps less ink
 */
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
      // Numerical guard: mean^2 can exceed meanSq by an epsilon on a flat window and sqrt(-eps) is NaN.
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

/**
 * Double-threshold hysteresis: seed from every pixel `> high`, then flood 8-connected through pixels
 * `> low`.
 *
 * Uses an explicit `Int32Array` stack, never recursion — a 12 MP image overflows the JS stack and the
 * crash presents as an unrelated "Maximum call stack size exceeded" from whatever ran next.
 */
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

/**
 * Canny's thresholds from the median `m` of the gradient magnitude:
 * `lo = max(0, (1 - s) * m)`, `hi = min(1, (1 + s) * m)`.
 *
 * @param sigma the `s` above; 0.33 is the usual choice and larger values widen the band
 * @returns `[low, high]`, always with `low <= high`.
 */
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

/**
 * Median value of the image, to within 1/512 of its actual `[lo, hi]` range.
 *
 * Read from a 256-bin histogram over the observed range rather than by sorting: an exact median of 12
 * million floats costs a full sort and a full copy, and the only consumer is an auto-threshold whose
 * own tolerance is far wider than the quantisation. A fixed 0..1 histogram would be wrong instead of
 * merely coarse — this is called on gradient magnitudes, which are not confined to 0..1.
 *
 * The bin count and the `(bin + 0.5) / bins` report are load-bearing rather than incidental: they set
 * Canny's automatic thresholds, so changing either moves which pixels end up in the mask and the two
 * engines stop agreeing.
 *
 * @returns a value inside `[lo, hi]`; `lo` when the image is flat.
 */
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
  // `> half` with `half = n / 2` truncated, which puts the median on the bin that contains the
  // (n/2 + 1)-th sample. `>=` picks the bin one below it for an even count and reads as a systematic
  // downward bias in every auto-threshold derived from it.
  const half = (n / 2) | 0;
  let cum = 0;
  for (let b = 0; b < MEDIAN_BINS; b++) {
    cum += hist[b];
    if (cum > half) return lo + ((b + 0.5) / MEDIAN_BINS) * span;
  }
  return hi;
}
