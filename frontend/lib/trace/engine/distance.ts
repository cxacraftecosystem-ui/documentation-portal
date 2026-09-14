import { GrayF, Mask } from './buffers';

/**
 * Exact Euclidean distance transform. See ALGORITHMS.md §9.
 *
 * Felzenszwalb & Huttenlocher's two-pass 1-D lower-envelope algorithm: O(n) per row and per column,
 * and **exact**, not the 3-4 chamfer approximation. Stroke width comes straight off this
 * (`width = 2 * distance` at the skeleton), and a chamfer's 6% directional error reads as a visible
 * wobble in a width-modulated stroke.
 */

// Large enough to stand in for infinity in the squared domain without overflowing a double's exact
// integer range, so the parabola intersections stay well-conditioned.
const INF = 1e20;

/**
 * @param src              the binary image
 * @param insideForeground true (default) measures each pixel's distance to the nearest **background**
 *                         pixel, so the values are non-zero inside blobs — this is what stroke width
 *                         needs. false measures the distance to the nearest foreground pixel.
 * @returns a new GrayF of Euclidean distances in pixels. When there is no source pixel at all (an
 *          all-foreground mask with `insideForeground`), every value is capped at the image diagonal
 *          rather than left at infinity, so downstream arithmetic stays finite.
 */
export function euclidean(src: Mask, insideForeground = true): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const d = src.data;
  const f = new Float64Array(n);
  let anySource = false;
  for (let i = 0; i < n; i++) {
    const isSource = insideForeground ? d[i] === 0 : d[i] !== 0;
    if (isSource) {
      f[i] = 0;
      anySource = true;
    } else {
      f[i] = INF;
    }
  }
  if (!anySource) {
    // Every distance would be infinite. Zeros are the honest answer and they keep downstream arithmetic
    // finite; an image-diagonal placeholder reads as real structure to the stroke-width sampler and
    // would put a uniform fat stroke on a mask that contains nothing.
    return new GrayF(w, h);
  }

  const maxDim = Math.max(w, h);
  const row = new Float64Array(maxDim);
  const dt = new Float64Array(maxDim);
  const v = new Int32Array(maxDim);
  const z = new Float64Array(maxDim + 1);

  // Columns first, then rows: the order is irrelevant to the answer but doing columns first keeps the
  // row pass reading contiguous memory, which is measurably faster at working resolution.
  for (let x = 0; x < w; x++) {
    for (let y = 0; y < h; y++) row[y] = f[y * w + x];
    lowerEnvelope(row, h, dt, v, z);
    for (let y = 0; y < h; y++) f[y * w + x] = dt[y];
  }
  for (let y = 0; y < h; y++) {
    const base = y * w;
    for (let x = 0; x < w; x++) row[x] = f[base + x];
    lowerEnvelope(row, w, dt, v, z);
    for (let x = 0; x < w; x++) f[base + x] = dt[x];
  }

  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) out[i] = Math.sqrt(f[i]);
  return new GrayF(w, h, out);
}

/** 1-D squared-distance transform of a sampled function, writing `n` values into `dt`. */
function lowerEnvelope(
  fRow: Float64Array,
  n: number,
  dt: Float64Array,
  v: Int32Array,
  z: Float64Array,
): void {
  let k = 0;
  v[0] = 0;
  z[0] = -INF;
  z[1] = INF;
  for (let q = 1; q < n; q++) {
    let top = k;
    let s = 0;
    for (;;) {
      const p = v[top];
      s = (fRow[q] + q * q - (fRow[p] + p * p)) / (2 * (q - p));
      // `top > 0` rather than testing z[0] = -INF: with 1e20 standing in for infinity the comparison
      // against z[0] is arithmetically true but not *guaranteed* true, and popping past the base of the
      // envelope reads v[-1].
      if (top > 0 && s <= z[top]) {
        top--;
        continue;
      }
      break;
    }
    k = top + 1;
    v[k] = q;
    z[k] = s;
    z[k + 1] = INF;
  }
  let k2 = 0;
  for (let q = 0; q < n; q++) {
    while (z[k2 + 1] < q) k2++;
    const p = v[k2];
    const diff = q - p;
    dt[q] = diff * diff + fRow[p];
  }
}

/**
 * Stroke width from a distance transform: twice the distance to the nearest background pixel.
 * @returns `2 * dt(x, y)`, with the coordinates edge-clamped so a caller sampling at a path vertex that
 *          rounds one pixel outside the image gets the border value instead of NaN.
 */
export function strokeWidthAt(dt: GrayF, x: number, y: number): number {
  return 2 * dt.clamped(x | 0, y | 0);
}
