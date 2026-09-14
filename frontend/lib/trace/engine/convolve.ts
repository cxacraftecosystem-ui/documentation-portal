import { GrayF } from './buffers';

/**
 * Convolution and derivatives. See ALGORITHMS.md §3.
 *
 * Every filter here is an *analytic* filter and therefore **clamps to the edge**, which is the
 * opposite of the binary stages' "out of bounds is background" rule. Both conventions are correct
 * for their own stage and mixing them up shows as a bright frame around every result.
 */

/** Which first-derivative operator {@link gradients} uses. */
export enum GradientOp {
  SOBEL = 'SOBEL',
  SCHARR = 'SCHARR',
}

/** Below this sigma a Gaussian is a 1-tap kernel, so blurring is a full pass that does nothing. */
const MIN_SIGMA = 0.05;

/**
 * `MIN_SIGMA` as float32, for the paths that quantise sigma before testing it. Kotlin's threshold is
 * the literal `0.05f` = 0.05000000074, so a sigma of exactly `0.05f` must fall on the *identity* side
 * of the comparison in both engines; testing the quantised sigma against the float64 `0.05` puts it on
 * opposite sides.
 */
const MIN_SIGMA_F32 = Math.fround(MIN_SIGMA);

/**
 * Normalised 1-D Gaussian taps.
 * @param sigma standard deviation in pixels; values <= 0 yield the 3-tap identity-ish kernel for
 *              radius 1, so callers still get an odd-length normalised kernel.
 * @returns an odd-length `Float32Array` of `2 * ceil(3 * sigma) + 1` taps summing to 1.
 */
export function gaussianKernel(sigma: number): Float32Array {
  // The degenerate case returns the exact 3-tap identity rather than a kernel whose taps underflow
  // to it, so a caller that builds a kernel unconditionally gets a well-formed odd kernel and the
  // two engines agree bit for bit at sigma = 0.
  if (!(sigma > MIN_SIGMA)) return new Float32Array([0, 1, 0]);
  const s = sigma;
  const r = Math.max(1, Math.ceil(3 * s));
  const n = 2 * r + 1;
  const k = new Float32Array(n);
  const inv2s2 = 1 / (2 * s * s);
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const d = i - r;
    const v = Math.exp(-(d * d) * inv2s2);
    k[i] = v;
    sum += v;
  }
  const invSum = 1 / sum;
  for (let i = 0; i < n; i++) k[i] *= invSum;
  return k;
}

/**
 * The same taps as {@link gaussianKernel}, in float64 and normalised in float64.
 *
 * `gaussianKernel` stores each `exp` into a `Float32Array` and then scales it, so its taps carry a
 * float32 rounding. Kotlin's equivalent rounds too, but *differently* — it scales by a `Float`
 * reciprocal where this rounds only at the store — and 4 of the 7 taps at sigma=1 come out exactly
 * 1 ulp (1.5e-8) apart. Invisible in a blur; decisive in {@link xdog}, where the `1/(1-tau)` rescale
 * and the soft threshold together magnify a tap error by ~1000. It was the largest single term in the
 * 1.17e-4 cross-engine disagreement on the `gradient-blob` fixture.
 *
 * Working in float64 removes the rounding instead of trying to mirror it, so both engines evaluate the
 * same expression in the same arithmetic and agree to the last bit.
 *
 * @param sigma quantised to float32 on entry, because Kotlin's parameter is a `Float`: `ceil(3*sigma)`
 *              and `exp(-d^2 / 2*sigma^2)` must be evaluated from the *same* sigma in both engines,
 *              and `1.6` is not `1.6f`. See ALGORITHMS.md §7.3 on quantising the knobs.
 * @returns an odd-length `Float64Array` of `2 * ceil(3 * sigma) + 1` taps summing to 1.
 */
export function gaussianKernelDouble(sigma: number): Float64Array {
  const s = Math.fround(sigma);
  if (!(s > MIN_SIGMA_F32)) return new Float64Array([0, 1, 0]);
  const r = Math.max(1, Math.ceil(3 * s));
  const n = 2 * r + 1;
  const k = new Float64Array(n);
  const inv = -1 / (2 * s * s);
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const d = i - r;
    const v = Math.exp(d * d * inv);
    k[i] = v;
    sum += v;
  }
  const norm = 1 / sum;
  for (let i = 0; i < n; i++) k[i] *= norm;
  return k;
}

/**
 * Separable Gaussian blur, edge-clamped.
 * @param sigma <= 0.05 returns `src.copy()` — a degenerate kernel otherwise costs two full passes to
 *              reproduce the input.
 */
export function gaussianBlur(src: GrayF, sigma: number): GrayF {
  if (sigma <= MIN_SIGMA) return src.copy();
  const k = gaussianKernel(sigma);
  return separable(src, k, k);
}

/**
 * {@link gaussianBlur} with {@link gaussianKernelDouble}'s taps and a float64 intermediate plane —
 * nothing on the path is rounded to float32.
 *
 * **This exists for one consumer shape: a filter that subtracts two nearly equal blurs and then
 * divides by the small remainder.** It is not a better `gaussianBlur` to reach for by default; it
 * costs two float64 planes instead of two float32 ones, and a well-conditioned consumer throws the
 * extra digits away at its own float32 store.
 *
 * Returning a raw `Float64Array` rather than a `GrayF` is the point — a `GrayF` would round the result
 * back to float32 and discard exactly the digits this function exists to keep. At tau = 0.98 that
 * store alone is worth 2.5e-5 of cross-engine disagreement in {@link xdog}: inside the §14 tolerance,
 * but four fifths of the budget for nothing. The caller combines the two planes in float64 and stores
 * once.
 *
 * There is no `sigma <= 0.05` short circuit as in `gaussianBlur`: the identity kernel `[0, 1, 0]`
 * reproduces the input exactly (`0*a + 1*b + 0*c` is `b` with no rounding), so the two passes are
 * wasted work but never a different answer, and one code path is one fewer place for the engines to
 * disagree about where the degenerate case begins.
 *
 * @returns `width * height` samples, row-major, in `GrayF.data` layout.
 */
export function gaussianBlurDouble(src: GrayF, sigma: number): Float64Array {
  const k = gaussianKernelDouble(sigma);
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const r = k.length >> 1;
  // One straightforward clamped loop per axis and no interior fast path. The two engines have to
  // accumulate the *same* taps in the *same* order for this to be reproducible bit for bit, and a
  // second code path is a second chance for them to drift apart.
  const mid = new Float64Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = 0; i < k.length; i++) {
        let sx = x - r + i;
        if (sx < 0) sx = 0;
        if (sx > w - 1) sx = w - 1;
        acc += k[i] * d[row + sx];
      }
      mid[row + x] = acc;
    }
  }
  const out = new Float64Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = 0; i < k.length; i++) {
        let sy = y - r + i;
        if (sy < 0) sy = 0;
        if (sy > h - 1) sy = h - 1;
        acc += k[i] * mid[sy * w + x];
      }
      out[row + x] = acc;
    }
  }
  return out;
}

/**
 * Box blur via running sums, edge-clamped, so cost is independent of `radius`.
 * @param radius <= 0 returns `src.copy()`.
 */
export function boxBlur(src: GrayF, radius: number): GrayF {
  const r = radius | 0;
  if (r <= 0) return src.copy();
  const w = src.width;
  const h = src.height;
  const tmp = new Float32Array(w * h);
  const out = new Float32Array(w * h);
  const d = src.data;
  const win = 2 * r + 1;
  const inv = 1 / win;

  for (let y = 0; y < h; y++) {
    const row = y * w;
    let sum = 0;
    // Prime the window over [-r, r] with out-of-range samples clamped to the first column.
    for (let i = -r; i <= r; i++) sum += d[row + (i < 0 ? 0 : i >= w ? w - 1 : i)];
    tmp[row] = sum * inv;
    for (let x = 1; x < w; x++) {
      const add = x + r;
      const sub = x - r - 1;
      sum += d[row + (add >= w ? w - 1 : add)] - d[row + (sub < 0 ? 0 : sub)];
      tmp[row + x] = sum * inv;
    }
  }

  for (let x = 0; x < w; x++) {
    let sum = 0;
    for (let i = -r; i <= r; i++) sum += tmp[(i < 0 ? 0 : i >= h ? h - 1 : i) * w + x];
    out[x] = sum * inv;
    for (let y = 1; y < h; y++) {
      const add = y + r;
      const sub = y - r - 1;
      sum += tmp[(add >= h ? h - 1 : add) * w + x] - tmp[(sub < 0 ? 0 : sub) * w + x];
      out[y * w + x] = sum * inv;
    }
  }
  return new GrayF(w, h, out);
}

/**
 * Horizontal pass with `kx` then vertical pass with `ky`, both edge-clamped.
 * @param kx odd-length kernel; its radius is `(kx.length - 1) / 2`
 * @param ky odd-length kernel
 * @throws if either kernel has even length.
 */
export function separable(src: GrayF, kx: Float32Array, ky: Float32Array): GrayF {
  const w = src.width;
  const h = src.height;
  const d = src.data;
  // An empty kernel skips its axis entirely, which is what lets a caller pass one derivative kernel
  // and leave the other direction untouched without building a `[0,1,0]` no-op.
  const tmp = kx.length === 0 ? d.slice() : new Float32Array(w * h);
  if (kx.length !== 0) {
    const rx = kx.length >> 1;
    for (let y = 0; y < h; y++) {
      const row = y * w;
      for (let x = 0; x < w; x++) {
        let acc = 0;
        for (let i = 0; i < kx.length; i++) {
          const sx = x - rx + i;
          acc += d[row + (sx < 0 ? 0 : sx >= w ? w - 1 : sx)] * kx[i];
        }
        tmp[row + x] = acc;
      }
    }
  }
  if (ky.length === 0) return new GrayF(w, h, tmp);
  const ry = ky.length >> 1;
  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = 0; i < ky.length; i++) {
        const sy = y - ry + i;
        acc += tmp[(sy < 0 ? 0 : sy >= h ? h - 1 : sy) * w + x] * ky[i];
      }
      out[row + x] = acc;
    }
  }
  return new GrayF(w, h, out);
}

/**
 * 3x3 correlation with a row-major kernel, edge-clamped.
 * @param k exactly 9 taps, `k[(dy + 1) * 3 + (dx + 1)]` weighting the sample at `(x + dx, y + dy)`.
 */
export function convolve3(src: GrayF, k: Float32Array | number[]): GrayF {
  if (k.length !== 9) throw new Error('convolve3(): kernel must have 9 taps');
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const out = new Float32Array(w * h);
  const k0 = k[0];
  const k1 = k[1];
  const k2 = k[2];
  const k3 = k[3];
  const k4 = k[4];
  const k5 = k[5];
  const k6 = k[6];
  const k7 = k[7];
  const k8 = k[8];
  for (let y = 0; y < h; y++) {
    const ym = (y > 0 ? y - 1 : 0) * w;
    const y0 = y * w;
    const yp = (y < h - 1 ? y + 1 : h - 1) * w;
    for (let x = 0; x < w; x++) {
      const xm = x > 0 ? x - 1 : 0;
      const xp = x < w - 1 ? x + 1 : w - 1;
      out[y0 + x] =
        d[ym + xm] * k0 +
        d[ym + x] * k1 +
        d[ym + xp] * k2 +
        d[y0 + xm] * k3 +
        d[y0 + x] * k4 +
        d[y0 + xp] * k5 +
        d[yp + xm] * k6 +
        d[yp + x] * k7 +
        d[yp + xp] * k8;
    }
  }
  return new GrayF(w, h, out);
}

/** A gradient pair with the derived magnitude/direction planes computed on demand. */
export class Gradients {
  constructor(
    readonly gx: GrayF,
    readonly gy: GrayF,
  ) {}

  /**
   * True `hypot(gx, gy)`, never the `|gx| + |gy|` approximation — L1 is up to 41% high on diagonals
   * and biases every auto-threshold derived from the magnitude median.
   */
  magnitude(): GrayF {
    const n = this.gx.data.length;
    const out = new Float32Array(n);
    const a = this.gx.data;
    const b = this.gy.data;
    for (let i = 0; i < n; i++) out[i] = Math.sqrt(a[i] * a[i] + b[i] * b[i]);
    return new GrayF(this.gx.width, this.gx.height, out);
  }

  /** `atan2(gy, gx)` in radians, measured with y down (image coordinates). */
  direction(): GrayF {
    const n = this.gx.data.length;
    const out = new Float32Array(n);
    const a = this.gx.data;
    const b = this.gy.data;
    for (let i = 0; i < n; i++) out[i] = Math.atan2(b[i], a[i]);
    return new GrayF(this.gx.width, this.gx.height, out);
  }
}

// Scharr is the default, not Sobel: the [3 10 3] weighting has markedly better rotational symmetry,
// and directional bias in the gradient shows up directly as staircasing in the traced vector.
const SCHARR_X = [3, 0, -3, 10, 0, -10, 3, 0, -3].map((v) => v / 32);
const SCHARR_Y = [3, 10, 3, 0, 0, 0, -3, -10, -3].map((v) => v / 32);
const SOBEL_X = [1, 0, -1, 2, 0, -2, 1, 0, -1].map((v) => v / 8);
const SOBEL_Y = [1, 2, 1, 0, 0, 0, -1, -2, -1].map((v) => v / 8);

/**
 * First derivatives, edge-clamped.
 *
 * Sign convention: `gx` is positive where intensity **decreases** to the right and `gy` is positive
 * where it decreases downward. That is the formulation Canny's non-maximum suppression assumes.
 *
 * @returns both derivative planes; `hypot` and `atan2` are available on the result.
 */
export function gradients(src: GrayF, op: GradientOp = GradientOp.SCHARR): Gradients {
  const kx = op === GradientOp.SOBEL ? SOBEL_X : SCHARR_X;
  const ky = op === GradientOp.SOBEL ? SOBEL_Y : SCHARR_Y;
  return new Gradients(convolve3(src, kx), convolve3(src, ky));
}

const LAPLACIAN_8 = [1, 1, 1, 1, -8, 1, 1, 1, 1];

/** 8-neighbour Laplacian, edge-clamped. The result is signed; zero crossings are the edges. */
export function laplacian(src: GrayF): GrayF {
  return convolve3(src, LAPLACIAN_8);
}

/**
 * Summed-area table so that any rectangle mean costs four reads regardless of radius.
 * @returns a `Float64Array` of `(width + 1) * (height + 1)`; `sat[0]` row and column are zero.
 *          Doubles, not floats: a 12 MP image of 1.0 sums to 1.2e7 and a float32 accumulator loses
 *          the low bits long before the last row.
 */
export function summedAreaTable(src: GrayF): Float64Array {
  const w = src.width;
  const h = src.height;
  const sw = w + 1;
  const sat = new Float64Array(sw * (h + 1));
  const d = src.data;
  for (let y = 0; y < h; y++) {
    let rowSum = 0;
    const srow = (y + 1) * sw;
    const prow = y * sw;
    const drow = y * w;
    for (let x = 0; x < w; x++) {
      rowSum += d[drow + x];
      sat[srow + x + 1] = sat[prow + x + 1] + rowSum;
    }
  }
  return sat;
}

/**
 * Inclusive rectangle sum from a {@link summedAreaTable}.
 * @param w source width, `h` source height — the table's own dimensions are `(w+1) x (h+1)`
 * @param x0 y0 x1 y1 inclusive corners; clamped into the image, so a window that overhangs the
 *           border sums only the pixels that exist (the caller divides by the same clamped count)
 * @returns the sum, or 0 when the clamped rectangle is empty.
 */
export function rectSum(
  sat: Float64Array,
  w: number,
  h: number,
  x0: number,
  y0: number,
  x1: number,
  y1: number,
): number {
  // Only the near corner clamps up and only the far corner clamps down. Clamping both ends of both
  // axes would turn a rectangle that lies entirely off the image into the single nearest pixel
  // instead of into the empty sum it is.
  const ax = x0 < 0 ? 0 : x0;
  const ay = y0 < 0 ? 0 : y0;
  const bx = x1 > w - 1 ? w - 1 : x1;
  const by = y1 > h - 1 ? h - 1 : y1;
  if (bx < ax || by < ay) return 0;
  const sw = w + 1;
  return (
    sat[(by + 1) * sw + (bx + 1)] -
    sat[ay * sw + (bx + 1)] -
    sat[(by + 1) * sw + ax] +
    sat[ay * sw + ax]
  );
}
