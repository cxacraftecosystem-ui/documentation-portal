import { GrayF, Px } from './buffers';
import { gaussianBlur, gaussianBlurDouble } from './convolve';

/**
 * Difference of Gaussians and XDoG. See ALGORITHMS.md §7.2.
 *
 * XDoG is the workhorse for artwork because the soft threshold is what makes the output look *drawn*
 * rather than *detected*: large phi gives hard technical lines, small phi gives soft pencil ones.
 */

/** tau is a ratio; at 1 the difference is identically zero and 1/(1-tau) is a division by zero. */
const MAX_TAU = 0.999;

/**
 * `D = G(sigma) - tau * G(k * sigma)`.
 *
 * The raw difference, unnormalised and signed. For a flat region of intensity `I` it evaluates to
 * `(1 - tau) * I`, which is why {@link xdog} divides by `1 - tau` before thresholding.
 *
 * @param k   sigma ratio of the wide lobe; 1.6 approximates the Laplacian of a Gaussian
 * @param tau subtraction weight, clamped to at most 0.999
 * @returns a new GrayF; signed and **not** clamped.
 */
export function dog(src: GrayF, sigma: number, k = 1.6, tau = 0.98): GrayF {
  // The same clamps {@link xdog} applies, for the same reason: both take these from one slider, and the
  // pair has to degenerate identically in both engines or a negative tau (or a non-positive k, which
  // would otherwise blur the wide lobe by nothing at all and return a plane of zeros) diverges silently.
  const t = Px.clamp(tau, 0, MAX_TAU);
  const s1 = sigma < 0 ? 0 : sigma;
  const s2 = k > 0 ? s1 * k : s1;
  const narrow = gaussianBlur(src, s1);
  const wide = gaussianBlur(src, s2);
  const n = src.data.length;
  const out = new Float32Array(n);
  const a = narrow.data;
  const b = wide.data;
  for (let i = 0; i < n; i++) out[i] = a[i] - t * b[i];
  return new GrayF(src.width, src.height, out);
}

/**
 * Winnemoller's soft threshold `T(u)`.
 *
 * ```
 * T(u) = 1                        if u >= epsilon
 *      = 1 + tanh(phi * (u - epsilon))   otherwise
 * ```
 * @returns a value in 0..1: 1 is paper, 0 is saturated ink. A non-finite `u` maps to 1 (paper), so a NaN
 *          pixel shows as blank rather than as a black speck the user cannot explain — `u >= epsilon` is
 *          false for NaN, and without the guard the formula propagates the NaN straight to the output.
 */
export function softThreshold(u: number, epsilon: number, phi: number): number {
  if (Number.isNaN(u)) return 1;
  if (u >= epsilon) return 1;
  // Clamped for the same reason Kotlin clamps: `1 + tanh` only stays inside 0..1 while phi is positive,
  // and phi arrives from a slider.
  const v = 1 + Math.tanh(phi * (u - epsilon));
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/**
 * XDoG ink density.
 *
 * The DoG response is divided by `1 - tau` before the soft threshold. That normalisation is what makes
 * `epsilon` an *intensity* level in the same 0..1 space as the input: `D` for a flat region of
 * intensity `I` is `(1 - tau) * I`, so `D / (1 - tau)` is `I` and the default `epsilon = 0.5` means
 * "mid grey". Thresholding the raw `D` instead compares an intensity level against a number two orders
 * of magnitude smaller and turns the entire image into ink — the single most recognisable way to get
 * XDoG wrong.
 *
 * @param epsilon ink level in 0..1
 * @param phi     sharpness of the transition, typically 10..200
 * @returns a new GrayF of ink density in 0..1 where **1 is paper**; invert it for ink coverage.
 */
export function xdog(
  src: GrayF,
  sigma: number,
  k = 1.6,
  tau = 0.98,
  epsilon = 0.5,
  phi = 20,
): GrayF {
  // Every knob is quantised to float32 because Kotlin's parameters are `Float`: 0.98f is
  // 0.9800000190734863 and 1.6f is 1.6000000238418579, and the `1/(1-tau)` amplification below turns
  // an unquantised knob into visible disagreement. ALGORITHMS.md §7.3 states the rule; FDoG obeys it
  // for the same reason.
  const t = Math.fround(Px.clamp(tau, 0, MAX_TAU));
  const eps = Math.fround(epsilon);
  const sharpness = Math.fround(phi);
  const s1 = Math.fround(sigma < 0 ? 0 : sigma);
  const kf = Math.fround(k);
  const s2 = kf > 0 ? Math.fround(s1 * kf) : s1;

  // **Everything from the source pixels to `u` is float64 — kernel taps included** — and only the
  // thresholded result is float32. §7.2 records the argument; it is the same decision FDoG makes.
  //
  // G(sigma) and G(k*sigma) are both ~I in any flat region, so `a - tau*b` is a catastrophic
  // cancellation leaving (1-tau)*I — a fiftieth of the operands at the default tau — and multiplying
  // by 1/(1-tau) amplifies whatever rounding survived. The soft threshold's slope then multiplies it
  // by up to phi again, so a *round-off* in the blur reaches the output magnified by ~1000: one ulp of
  // float32 (6e-8) is worth 6e-5 here, most of the §14 tolerance. That is why every float32 rounding
  // on this path had to go and not just the obvious one.
  //
  // Measured on `gradient-blob` against Kotlin computing the identical formula: float32 kernel taps
  // cost 1.18e-4 (the engines round `gaussianKernel` differently in the last bit), a float32
  // convolution accumulator 6.9e-5, and storing the two blur planes as float32 2.5e-5. Widening only
  // the subtraction — which is what this function used to do — left 1.17e-4, over tolerance. Widening
  // the whole path leaves 5.8e-6.
  //
  // `dog` is deliberately left alone: it returns the raw signed response with no rescale, so it is
  // not amplified and float32 is the honest result of what it computes.
  const a = gaussianBlurDouble(src, s1);
  const b = gaussianBlurDouble(src, s2);
  const scale = 1 / (1 - t);
  const n = a.length;
  const out = new Float32Array(n);
  // `Math.fround(u)`: Kotlin narrows the rescaled response to `Float` before thresholding it, exactly
  // as FDoG's step 2 does, and the threshold's slope is steep enough for the last float32 bit of `u`
  // to matter.
  for (let i = 0; i < n; i++) {
    out[i] = softThreshold(Math.fround((a[i] - t * b[i]) * scale), eps, sharpness);
  }
  return new GrayF(src.width, src.height, out);
}
