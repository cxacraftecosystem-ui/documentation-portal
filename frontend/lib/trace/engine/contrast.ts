import { GrayF, Px } from './buffers';
import { gaussianBlur } from './convolve';

/**
 * Contrast and tone. See ALGORITHMS.md §5.
 *
 * Local contrast (CLAHE) is what makes a faint pencil line survive the same threshold as a bold ink
 * line, which is why it is on by default in every preset that expects a photograph.
 */

/**
 * Contrast-limited adaptive histogram equalisation.
 *
 * The four nearest tile LUTs are **bilinearly interpolated** using tile *centres* as the lattice, and
 * pixels outside that lattice (the border half-tile) clamp to the edge tiles. Skipping the
 * interpolation is what produces visible tile seams and is the most common way CLAHE is implemented
 * wrong.
 *
 * A tile holding a **single grey level** is mapped by the identity instead of by its CDF. The literal
 * algorithm sends that level to 255 — its CDF is 1.0 at the one occupied bin — so a constant tile, a
 * 1x1 image, or any image small enough that a tile is one pixel would come back pure white. "Enhance
 * local contrast" has no meaning where there is one level to enhance, and white is not an answer a
 * user would accept for a mid-grey input.
 *
 * @param tilesX tiles across; clamped to `1..width`
 * @param tilesY tiles down; clamped to `1..height`
 * @param clipLimit multiple of the mean bin count above which a bin is clipped; <= 0 disables
 *                  clipping and degenerates to tiled equalisation
 * @returns a new GrayF with values in 0..1.
 */
export function clahe(src: GrayF, tilesX = 8, tilesY = 8, clipLimit = 2): GrayF {
  const w = src.width;
  const h = src.height;
  const tx = Px.clampInt(tilesX, 1, w);
  const ty = Px.clampInt(tilesY, 1, h);
  const d = src.data;

  // Tile boundaries by exact partition rather than a fixed tile size, so no tile is ever empty and
  // the last tile is not silently 1/8th the width of the others.
  const xb = new Int32Array(tx + 1);
  const yb = new Int32Array(ty + 1);
  for (let i = 0; i <= tx; i++) xb[i] = Math.floor((i * w) / tx);
  for (let i = 0; i <= ty; i++) yb[i] = Math.floor((i * h) / ty);

  // Integer 0..255 LUTs, per ALGORITHMS.md §5 step 4, divided down after the interpolation.
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

  // Tile centres, in pixel coordinates, are the interpolation lattice.
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

/**
 * Clip, redistribute and integrate one tile histogram into a 0..255 mapping LUT.
 *
 * @param occupied how many bins hold at least one pixel, counted before clipping
 */
function buildClaheLut(
  hist: Int32Array,
  count: number,
  occupied: number,
  clipLimit: number,
  dest: Int32Array,
  destOffset: number,
): void {
  if (occupied <= 1) {
    // One level, or (impossibly, since tx <= width) none at all: pass the tile through. See the note
    // in the clahe doc comment — equalising a single level yields white — and note that the empty
    // case would divide by zero below and poison a quarter of the interpolation.
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
    // One redistribution pass, uniform across all bins. The standard accepts that redistribution can
    // push bins back over the limit; iterating to a fixed point measurably flattens the result for no
    // visible benefit. The remainder goes to the lowest bins so the split is deterministic.
    const share = (excess / 256) | 0;
    const rest = excess - share * 256;
    for (let i = 0; i < 256; i++) hist[i] += share + (i < rest ? 1 : 0);
  }
  // Redistribution moves counts between bins and never changes their sum, so the CDF still ends at
  // `count`. `round(cum * 255 / count)` is evaluated in exact integer arithmetic: as a float product
  // the result lands either side of a .5 tie depending on the working precision — Double here, Float
  // in Kotlin — and one LUT step is 1/255, forty times the parity tolerance.
  let cum = 0;
  const denom = 2 * count;
  for (let i = 0; i < 256; i++) {
    cum += hist[i];
    dest[destOffset + i] = Math.floor((cum * 510 + count) / denom);
  }
}

/**
 * Global histogram equalisation over 256 bins.
 * @returns a new GrayF with values in 0..1; `src.copy()` when the image is a single level.
 */
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

/**
 * Power-law tone curve `out = clamp01(in) ^ (1 / gamma)`.
 * @param gamma > 1 lightens, < 1 darkens; <= 0 returns `src.copy()`.
 */
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

/**
 * Black/white point remap with a gamma: `out = ((clamp(in, black, white) - black) / (white - black)) ^ (1/gamma)`.
 * @returns a new GrayF with values in 0..1; `src.copy()` when `white - black` is degenerate.
 */
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

/**
 * Linear brightness and contrast, both in -1..1.
 *
 * Contrast is a slope about mid-grey, `slope = (1 + c) / (1 - c)`, so 0 is identity, +1 would be
 * infinite and is therefore clamped just short of it. Brightness is a plain offset applied after.
 *
 * @returns a new GrayF with values in 0..1.
 */
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

/**
 * Unsharp mask: `out = in + amount * (in - blur(in, sigma))`, skipping differences at or below
 * `threshold` so flat areas do not gain noise.
 * @returns a new GrayF; values are **not** clamped, matching the engine's "never clamp between
 *          stages" rule.
 */
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

/**
 * Map the actual `[lo, hi]` range to `[0, 1]`.
 * @returns a new GrayF; `src.copy()` when the range is degenerate (a flat image has nothing to
 *          stretch and scaling it by 1/0 would produce NaN everywhere).
 */
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

/**
 * 256, matching `Contrast.kt`. The bin count is part of the answer, not an implementation detail: a
 * percentile read from a histogram is only as precise as its bins, so two engines that bin differently
 * return different percentiles for the same image and §14 parity fails.
 */
const PERCENTILE_BINS = 256;

/**
 * Stretch using the `p`-th and `(100 - p)`-th percentiles instead of the extremes, which is what you
 * want on any real photograph: a single specular highlight otherwise owns the whole top of the range.
 *
 * Percentiles come from a 256-bin histogram over the actual range rather than a full sort —
 * deterministic, O(n), and {@link percentileAt} interpolates inside the crossing bin so the answer is
 * not quantised to a bin edge.
 *
 * @param percentile 0..49; values outside are clamped. 0 degenerates to {@link stretch} — which is
 *   also what the general path computes there, since bin 0 always holds `lo` and the last bin `hi`.
 * @returns a new GrayF with values in 0..1.
 */
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

/**
 * Locates the value at cumulative `rank` in `hist` and returns it as a **fraction of the histogram's
 * range** (0..1), interpolating linearly inside the bin the rank falls in.
 *
 * The interpolation is the whole point, and returning the bin *edge* — the obvious implementation — is
 * a real error rather than a rounding one. A bin is only narrow when the data fills the range, which is
 * exactly the case {@link percentileStretch} does not exist for. In the case it does exist for, one
 * outlier stretches the range so far that all the real data crowds into a handful of bins:
 *
 *   100 samples, 99 spread evenly over 0.20..0.396 and one at 8.0. The range is 7.8, so a bin spans
 *   0.0305 and the real data occupies bins 0..6. The 95th percentile crosses in bin 6, which holds 7
 *   samples starting at cumulative 92. That bin's top edge is 0.4133 and the true percentile is 0.390;
 *   interpolating gives 0.2 + (6 + 3/7)/256 * 7.8 = 0.3959, an error of 0.006 instead of 0.023 — the
 *   difference between the artwork reaching the top of the output range and being squashed into the
 *   bottom 84% of it.
 *
 * Empty bins are skipped so the `count` divisor is never zero. Mirrors `Contrast.percentileAt`.
 *
 * @param rank cumulative sample index to locate, 0..`n`; clamped into range.
 * @returns the position in 0..1 across the histogram's span.
 */
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

/** @returns a new GrayF holding `1 - in`, unclamped, so signed inputs stay signed. */
export function invert(src: GrayF): GrayF {
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  for (let i = 0; i < n; i++) out[i] = 1 - d[i];
  return new GrayF(src.width, src.height, out);
}

/**
 * Histogram over the 0..1 interval; values outside are clamped into the end bins.
 * @param bins clamped to `1..65536`
 * @returns counts, length `bins`, summing to `src.size`.
 */
export function histogram(src: GrayF, bins = 256): Int32Array {
  const nb = Px.clampInt(bins, 1, 65536);
  const out = new Int32Array(nb);
  const d = src.data;
  if (nb === 1) {
    out[0] = d.length;
    return out;
  }
  // Quantisation to `bins` *levels*, `round(v * (bins - 1))`, not `bins` equal-width buckets. That
  // makes `bins = 256` produce exactly `Px.toByte255`, so a histogram bin and the LUT it indexes
  // cannot disagree — with floor binning they differ by one bin for most values and every equalised
  // image comes out a level dark.
  const scale = nb - 1;
  for (let i = 0; i < d.length; i++) {
    let b = Math.round(Px.clamp01(d[i]) * scale);
    if (b < 0) b = 0;
    else if (b >= nb) b = nb - 1;
    out[b]++;
  }
  return out;
}
