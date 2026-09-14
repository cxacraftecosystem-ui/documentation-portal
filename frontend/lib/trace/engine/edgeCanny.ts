import { GrayF, Mask } from './buffers';
import { GradientOp, Gradients, gaussianBlur, gradients } from './convolve';
import { autoCannyThresholds, hysteresis } from './threshold';

/**
 * Canny edge detection. See ALGORITHMS.md §7.1.
 *
 * The one place this differs from the usual textbook implementation is non-maximum suppression, which
 * **interpolates** the two comparison samples instead of quantising the gradient direction to
 * 0/45/90/135. Quantising is the usual shortcut and it produces the characteristic 1 px staircase,
 * which then becomes a staircase in the vector output — the exact artefact this whole engine exists
 * to avoid.
 */

/**
 * Interpolated non-maximum suppression.
 *
 * For gradient `(gx, gy)` the two comparison points are at `+-(gx, gy) / max(|gx|, |gy|)`, i.e. one
 * pixel away along the dominant axis, and the magnitude is sampled there bilinearly. The centre pixel
 * survives only if it is `>=` both.
 *
 * This answers *which* pixel the ridge is in, and nothing more; {@link subpixelRidge} answers where in
 * that pixel it is, which is worth about a factor of eight in edge position and is what a vector needs
 * to sit on the edge rather than beside it.
 *
 * @returns a new GrayF holding the magnitude where the pixel is a ridge and 0 elsewhere.
 */
export function nonMaximumSuppression(g: Gradients): GrayF {
  const mag = g.magnitude();
  const w = mag.width;
  const h = mag.height;
  const out = new Float32Array(w * h);
  const gx = g.gx.data;
  const gy = g.gy.data;
  const m = mag.data;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const c = m[i];
      if (c <= 0) continue;
      const ax = gx[i];
      const ay = gy[i];
      const denom = Math.max(Math.abs(ax), Math.abs(ay));
      if (denom <= 0) continue;
      const dx = ax / denom;
      const dy = ay / denom;
      const a = mag.sampleBilinear(x + dx, y + dy);
      const b = mag.sampleBilinear(x - dx, y - dy);
      if (c >= a && c >= b) out[i] = c;
    }
  }
  return new GrayF(w, h, out);
}

/**
 * The gradient ridge located to **sub-pixel** precision. See ALGORITHMS.md §7.1.
 *
 * `offsetX` / `offsetY` are the displacement from a pixel's centre to the ridge maximum, along the
 * gradient; `magnitude` is the parabola's peak value — the magnitude the edge would have had if it had
 * been sampled where it actually is.
 */
export class Ridge {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly magnitude: Float32Array,
    readonly offsetX: Float32Array,
    readonly offsetY: Float32Array,
  ) {}

  /** @returns the x displacement at `(x, y)`, or 0 outside the image. */
  offsetXAt(x: number, y: number): number {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return 0;
    return this.offsetX[y * this.width + x];
  }

  /** @returns the y displacement at `(x, y)`, or 0 outside the image. */
  offsetYAt(x: number, y: number): number {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return 0;
    return this.offsetY[y * this.width + x];
  }

  /**
   * Moves each of the first `n` points of `xs`/`ys` onto the ridge, **in place**.
   *
   * The offset read is the one stored at the point's *nearest* pixel, so this is meaningful for a
   * polyline whose vertices came off the same lattice the ridge was computed on — a traced edge mask,
   * not an arbitrary curve. Points outside the image are left where they are. The displacement is
   * bounded by half the sampling step, so a vertex can never be moved onto a neighbouring feature; see
   * {@link subpixelRidge}.
   */
  snap(xs: Float32Array, ys: Float32Array, n = Math.min(xs.length, ys.length)): void {
    const count = Math.max(0, Math.min(n, Math.min(xs.length, ys.length)));
    for (let i = 0; i < count; i++) {
      const x = Math.round(xs[i]);
      const y = Math.round(ys[i]);
      if (x < 0 || y < 0 || x >= this.width || y >= this.height) continue;
      const j = y * this.width + x;
      xs[i] += this.offsetX[j];
      ys[i] += this.offsetY[j];
    }
  }
}

/**
 * Sub-pixel refinement of the gradient ridge: a parabola through the three magnitudes along the
 * gradient direction, evaluated at every pixel.
 *
 * {@link nonMaximumSuppression} answers *which* pixel holds the ridge; it cannot answer *where in that
 * pixel* the edge is, so a mask built from it — and every vector traced from that mask — carries the
 * half-pixel quantisation of the grid. Measured on an anti-aliased disc of radius 45 whose centre is
 * deliberately off-lattice, the surviving pixels sit an RMS 0.341 px from the true circle (max 0.66) at
 * sigma = 1.2; on an axis-aligned edge the whole line is offset by a *systematic* 0.370 px, because
 * every pixel of it rounds the same way. The parabola vertex removes almost all of it: the same disc
 * measures 0.043 px RMS (max 0.12) and the axis-aligned edge 0.005 px.
 *
 * The three samples are **the same points NMS compares against** — the pixel itself and
 * `+-(gx, gy) / max(|gx|, |gy|)`, read bilinearly — so the refinement is consistent with the survivor
 * test by construction rather than by coincidence. With `a` and `c` the neighbours and `m` the centre,
 * the vertex of the parabola through `(-1, a), (0, m), (1, c)` is at `t = (a - c) / (2 (a - 2m + c))`.
 *
 * **A pixel that survived NMS always has |t| <= 1/2.** Writing `a = m - p`, `c = m - q` with
 * `p, q >= 0` (which is what `m >= a` and `m >= c` say), `t = (p - q) / (2 (p + q))` and
 * `|p - q| <= p + q`. The refinement can therefore only move a point within its own half-cell along the
 * gradient, never onto a neighbouring ridge, which is what makes {@link Ridge.snap} safe to apply
 * blindly to a traced polyline. The clamp below never binds on a ridge pixel; it exists for the pixels
 * either side of one, where the vertex genuinely lies elsewhere and an unbounded `t` is meaningless.
 *
 * A denominator `>= 0` means the three samples are flat or concave-up — no maximum to refine towards —
 * and leaves the offset at zero, as does a zero gradient, which has no direction.
 *
 * This is an **addition**: `detect`, `detectAuto` and `nonMaximumSuppression` are unchanged, so no
 * existing output moves.
 */
export function subpixelRidge(g: Gradients): Ridge {
  const mag = g.magnitude();
  const w = mag.width;
  const h = mag.height;
  const m = mag.data;
  const gx = g.gx.data;
  const gy = g.gy.data;
  const peak = new Float32Array(w * h);
  const offsetX = new Float32Array(w * h);
  const offsetY = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const c0 = m[i];
      peak[i] = c0;
      if (c0 <= 0) continue;
      const denomAxis = Math.max(Math.abs(gx[i]), Math.abs(gy[i]));
      if (denomAxis <= 0) continue;
      const ux = gx[i] / denomAxis;
      const uy = gy[i] / denomAxis;
      const a = mag.sampleBilinear(x - ux, y - uy);
      const c = mag.sampleBilinear(x + ux, y + uy);
      const denom = a - 2 * c0 + c;
      if (denom >= 0) continue;
      let t = (0.5 * (a - c)) / denom;
      if (!(t > -0.5)) t = -0.5; // also catches NaN
      if (!(t < 0.5)) t = 0.5;
      // Storing into a Float32Array already narrows; no explicit fround is needed or wanted here.
      offsetX[i] = t * ux;
      offsetY[i] = t * uy;
      // The parabola evaluated at t, not a closed form for its vertex: the two agree only when the
      // clamp above did not bind, and a peak that disagrees with its own offset would be worse than
      // not reporting one.
      peak[i] = c0 + 0.5 * t * (c - a) + 0.5 * t * t * denom;
    }
  }
  return new Ridge(w, h, peak, offsetX, offsetY);
}

/**
 * Full Canny: blur, gradients, interpolated NMS, double threshold with hysteresis.
 *
 * @param blurSigma Gaussian sigma; <= 0.05 skips the blur
 * @param low  lower hysteresis threshold on the gradient magnitude (0..1 for 0..1 input)
 * @param high upper hysteresis threshold; the two are ordered internally so swapping them is harmless
 * @returns a Mask whose `true` pixels are edges.
 */
export function detect(
  src: GrayF,
  blurSigma: number,
  low: number,
  high: number,
  op: GradientOp = GradientOp.SCHARR,
): Mask {
  const blurred = gaussianBlur(src, blurSigma);
  const nms = nonMaximumSuppression(gradients(blurred, op));
  return hysteresis(nms, low, high);
}

/**
 * Canny with thresholds derived from the median gradient magnitude (ALGORITHMS.md §6).
 *
 * The median is taken on the **pre-suppression** magnitude, which is the distribution the published
 * `(1 +- s) * median` rule was calibrated against; taking it after NMS makes the median almost zero
 * because NMS zeroes ~90% of the image and the thresholds collapse.
 *
 * @param sensitivity the `s` of `(1 +- s) * median`; 0.33 is the reference value
 */
export function detectAuto(src: GrayF, blurSigma: number, sensitivity = 0.33): Mask {
  const blurred = gaussianBlur(src, blurSigma);
  const g = gradients(blurred, GradientOp.SCHARR);
  const mag = g.magnitude();
  const t = autoCannyThresholds(mag, sensitivity);
  const nms = nonMaximumSuppression(g);
  return hysteresis(nms, t[0], t[1]);
}
