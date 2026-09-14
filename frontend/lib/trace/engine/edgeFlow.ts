import { GrayF, Px } from './buffers';
import { GradientOp, gaussianBlur, gradients } from './convolve';
import { softThreshold } from './edgeDog';

/**
 * Flow-based DoG with Edge Tangent Flow. See ALGORITHMS.md §7.3.
 *
 * This is the quality tier: DoG *across* the local edge tangent and a smoothing pass *along* it
 * produce long, coherent strokes instead of the per-pixel-independent response of Canny or plain DoG.
 * Incoherent texture — fabric weave, foliage, stone grain — is suppressed by construction rather than
 * by a threshold that also removes the drawing.
 */

/** Unit tangent field plus the normalised gradient magnitude that weights the ETF refinement. */
export class FlowField {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly tx: Float32Array,
    readonly ty: Float32Array,
    readonly magnitude: Float32Array,
  ) {}
}

/** Kang et al.'s eta; controls how strongly a stronger-gradient neighbour dominates the average. */
const ETF_ETA = 1;

/**
 * Structure-tensor edge tangent flow.
 *
 * The **minor** eigenvector of the Gaussian-smoothed structure tensor is the local edge tangent. The
 * 2x2 symmetric eigenproblem is solved in closed form; the `Jxy ~ 0` branch falls back to the axis
 * that carries the smaller eigenvalue, because the general formula `(Jxy, lambdaMin - Jxx)` degenerates
 * to the zero vector exactly there.
 *
 * @param sigma tensor smoothing sigma; ~2 px is the reference value
 * @returns a FlowField whose `magnitude` is the gradient magnitude normalised to 0..1 (the `gHat` of
 *          the ETF weights). Isotropic pixels get the tangent `(1, 0)` and magnitude 0, so they carry
 *          no weight anywhere downstream but still have a defined direction to walk along.
 */
export function structureTensorFlow(src: GrayF, sigma = 2): FlowField {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const g = gradients(src, GradientOp.SCHARR);
  const gx = g.gx.data;
  const gy = g.gy.data;

  const exx = new Float32Array(n);
  const exy = new Float32Array(n);
  const eyy = new Float32Array(n);
  const mag = new Float32Array(n);
  let maxMag = 0;
  for (let i = 0; i < n; i++) {
    const a = gx[i];
    const b = gy[i];
    exx[i] = a * a;
    exy[i] = a * b;
    eyy[i] = b * b;
    const m = Math.sqrt(a * a + b * b);
    mag[i] = m;
    if (m > maxMag) maxMag = m;
  }
  const jxx = gaussianBlur(new GrayF(w, h, exx), sigma).data;
  const jxy = gaussianBlur(new GrayF(w, h, exy), sigma).data;
  const jyy = gaussianBlur(new GrayF(w, h, eyy), sigma).data;

  const invMax = maxMag > 0 ? 1 / maxMag : 0;
  for (let i = 0; i < n; i++) mag[i] *= invMax;

  const tx = new Float32Array(n);
  const ty = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const a = jxx[i];
    const b = jxy[i];
    const c = jyy[i];
    const diff = a - c;
    const root = Math.sqrt(diff * diff + 4 * b * b);
    const lmin = (a + c - root) * 0.5;
    let vx: number;
    let vy: number;
    if (Math.abs(b) > 1e-12) {
      vx = b;
      vy = lmin - a;
    } else if (a <= c) {
      vx = 1;
      vy = 0;
    } else {
      vx = 0;
      vy = 1;
    }
    const len = Math.sqrt(vx * vx + vy * vy);
    if (len > 1e-20) {
      // Canonical half-plane: ty > 0, or ty === 0 and tx >= 0. Mirrors EdgeFlow.kt exactly.
      //
      // A tangent is a DIRECTOR — t and -t describe the same line — and which one an eigen-solver
      // returns is an accident of the branch it took. Leaving that in the output makes the field
      // unreproducible across implementations: this engine and the Kotlin one returned (0, +1) and
      // (0, -1) for the same vertical edge, an error of 2.0 against a 1e-4 parity tolerance, for two
      // answers that are geometrically the same line.
      //
      // Nothing downstream loses information, because every consumer already treats the sign as
      // meaningless and re-derives it: refineEtf carries the explicit sign(t(x)·t(y)) term that
      // exists for this, and the FDoG streamline walk flips the tangent when it opposes travel.
      let nx = vx / len;
      let ny = vy / len;
      if (ny < 0 || (ny === 0 && nx < 0)) {
        nx = -nx;
        ny = -ny;
      }
      tx[i] = nx;
      ty[i] = ny;
    } else {
      // No dominant orientation. (1, 0) is already canonical.
      tx[i] = 1;
      ty[i] = 0;
    }
  }
  return new FlowField(w, h, tx, ty, mag);
}

/**
 * ETF refinement (Kang et al.), `iterations` passes over a disc of radius `radius`.
 *
 * ```
 * t'(x) = normalize( sum_y phi(x,y) * t(y) * ws * wm * wd )
 *   phi = sign(t(x) . t(y))      ws = 1 if |x-y| < r      wm = (1 + tanh(eta*(gHat(y)-gHat(x))))/2
 *   wd  = |t(x) . t(y)|
 * ```
 * `phi` is what resolves the +-t ambiguity. Without it neighbouring tangents that point in opposite
 * directions cancel, the sum collapses to zero, and the whole field turns to noise on the first pass.
 *
 * @returns a new FlowField; the input is not modified. `iterations <= 0` returns a copy.
 */
export function refineEtf(field: FlowField, iterations = 3, radius = 5): FlowField {
  const w = field.width;
  const h = field.height;
  const n = w * h;
  let tx = field.tx.slice();
  let ty = field.ty.slice();
  let nx = new Float32Array(n);
  let ny = new Float32Array(n);
  const mag = field.magnitude;
  const r = Math.max(1, radius | 0);
  const iters = Math.max(0, iterations | 0);
  const r2 = r * r;

  for (let it = 0; it < iters; it++) {
    for (let y = 0; y < h; y++) {
      const row = y * w;
      for (let x = 0; x < w; x++) {
        const i = row + x;
        const cx = tx[i];
        const cy = ty[i];
        const cg = mag[i];
        let sx = 0;
        let sy = 0;
        for (let dy = -r; dy <= r; dy++) {
          const yy = y + dy;
          const cyy = yy < 0 ? 0 : yy >= h ? h - 1 : yy;
          const srow = cyy * w;
          const dy2 = dy * dy;
          for (let dx = -r; dx <= r; dx++) {
            if (dx * dx + dy2 >= r2) continue;
            const xx = x + dx;
            const j = srow + (xx < 0 ? 0 : xx >= w ? w - 1 : xx);
            const ox = tx[j];
            const oy = ty[j];
            const dot = cx * ox + cy * oy;
            const wd = dot < 0 ? -dot : dot;
            if (wd === 0) continue;
            const wm = (1 + Math.tanh(ETF_ETA * (mag[j] - cg))) * 0.5;
            const weight = (dot >= 0 ? 1 : -1) * wm * wd;
            sx += ox * weight;
            sy += oy * weight;
          }
        }
        const len = Math.sqrt(sx * sx + sy * sy);
        if (len > 1e-20) {
          nx[i] = sx / len;
          ny[i] = sy / len;
        } else {
          nx[i] = cx;
          ny[i] = cy;
        }
      }
    }
    const sxa = tx;
    const sya = ty;
    tx = nx;
    ty = ny;
    nx = sxa;
    ny = sya;
  }
  return new FlowField(w, h, tx, ty, mag);
}

/** Every knob of {@link coherentLineDrawing} in one record, matching the Kotlin data class. */
export interface FlowParams {
  readonly tensorSigma: number;
  readonly etfIterations: number;
  readonly etfRadius: number;
  readonly sigmaC: number;
  readonly sigmaM: number;
  readonly tau: number;
  readonly fdogIterations: number;
  readonly epsilon: number;
  readonly phi: number;
}

/** @returns the reference FlowParams from ALGORITHMS.md §7.3, overridden by anything in `over`. */
export function flowParams(over: Partial<FlowParams> = {}): FlowParams {
  return {
    tensorSigma: 2,
    etfIterations: 3,
    etfRadius: 5,
    sigmaC: 1,
    sigmaM: 3,
    tau: 0.99,
    fdogIterations: 3,
    epsilon: 0.5,
    phi: 20,
    ...over,
  };
}

/** tau is a ratio; 1 makes the difference identically zero and 1/(1-tau) a division by zero. */
const MAX_TAU = 0.999;

/** Kotlin spells this `1.6f`, which is 1.6000000238418579; float32 here so both engines agree. */
const SIGMA_RATIO = Math.fround(1.6);

/**
 * Flow-based DoG.
 *
 * Per iteration: a 1-D DoG **across** the flow (a straight walk along the gradient direction,
 * `±ceil(3 * 1.6 * sigmaC)` bilinearly interpolated samples), a Gaussian integration **along** the flow
 * (a unit-step Euler walk down the streamline, `±ceil(3 * sigmaM)` samples, re-reading the tangent at
 * every step and flipping it whenever it opposes the direction of travel), then XDoG's soft threshold.
 * All but the last iteration feed the thresholded result back as `min(image, ink)` — Kang's
 * superimposition of the detected black lines onto the input, ALGORITHMS.md §7.3.
 *
 * **The feedback rule is `min`, and the plausible alternative is a bug rather than a variant.** Blacking
 * out where the *raw* DoG response is negative paints the dark flank of every edge — the response is
 * negative on the dark side by construction — the next pass reads that flank as a fresh edge and answers
 * with a bright halo beside it, and a large uniform dark region comes back as *paper*. This engine did
 * exactly that: on the step-edge fixture the flat left half (intensity 0.167) returned 1.0 where Kotlin
 * returned 0.0, a total disagreement about ink on the largest region of the image. Superimposing the
 * *thresholded ink* can only ever darken the working image, so the iteration converges instead of
 * oscillating, and a flat region keeps the XDoG tone response it had on the first pass.
 *
 * Step 3's soft threshold runs on `H / (1 - tau)`. The division is what makes `epsilon` an intensity
 * level in the same 0..1 space as the input — see {@link softThreshold}'s note; thresholding raw `H`
 * turns the whole frame to ink.
 *
 * The along-flow walk **stops at the image border** instead of clamping. A clamped walk would sit on the
 * same edge pixel for the rest of its samples and smear a band around the whole frame; the accumulated
 * weights are renormalised so a truncated walk stays unbiased.
 *
 * @param iterations feedback rounds, clamped to 1..16.
 * @returns a new GrayF of ink density in 0..1 where **1 is paper**.
 */
export function fdog(
  src: GrayF,
  field: FlowField,
  sigmaC = 1,
  sigmaM = 3,
  tau = 0.99,
  iterations = 3,
  epsilon = 0.5,
  phi = 20,
): GrayF {
  if (field.width !== src.width || field.height !== src.height) {
    throw new Error('fdog(): flow field size does not match the source');
  }
  // The knobs are quantised to float32 on entry because the Kotlin engine's are Float parameters, so
  // `tau = 0.99` is 0.98999996 there. Everywhere else in the engine a 4e-9 difference in a knob is
  // invisible; here `1/(1 - tau)` turns it into 4e-6 of the value epsilon is compared against, and the
  // inter-pass feedback multiplies that by another 100 per pass. Starting from bit-identical numbers is
  // free; relying on the §14 tolerance to absorb the drift is not.
  const t = Math.fround(Px.clamp(tau, 0, MAX_TAU));
  const iters = Px.clampInt(iterations, 1, 16);
  const sc = Math.fround(clampSigma(sigmaC, 16));
  const sm = Math.fround(clampSigma(sigmaM, 32));
  const ss = Math.fround(SIGMA_RATIO * sc);
  const eps = Math.fround(epsilon);
  const sharpness = Math.fround(phi);

  // Both Gaussians of the DoG share one truncated support and are each normalised over it, so a
  // constant region of intensity I answers exactly (1 - tau) * I and the 1 / (1 - tau) rescale below is
  // exact rather than approximately right — which matters, because epsilon is compared against it.
  //
  // The taps are double, not float32: the DoG at tau = 0.99 is the difference of two kernels that each
  // sum to 1 and whose difference sums to 0.01, so rounding the taps to float32 puts ~2e-6 of relative
  // noise into the rescaled response — a hundred times worse than the arithmetic that produced them.
  const rc = radiusFor(ss);
  const kernAcross = new Float64Array(2 * rc + 1);
  {
    const gc = new Float64Array(2 * rc + 1);
    const gs = new Float64Array(2 * rc + 1);
    const dc = 2 * sc * sc;
    const ds = 2 * ss * ss;
    let sumC = 0;
    let sumS = 0;
    for (let i = -rc; i <= rc; i++) {
      const a = Math.exp(-(i * i) / dc);
      const b = Math.exp(-(i * i) / ds);
      gc[i + rc] = a;
      gs[i + rc] = b;
      sumC += a;
      sumS += b;
    }
    for (let i = 0; i < kernAcross.length; i++) kernAcross[i] = gc[i] / sumC - t * (gs[i] / sumS);
  }
  const scale = 1 / (1 - t);

  const rm = radiusFor(sm);
  const kernAlong = new Float64Array(rm + 1);
  {
    const dm = 2 * sm * sm;
    for (let i = 0; i <= rm; i++) kernAlong[i] = Math.exp(-(i * i) / dm);
  }

  const work = src.copy();
  const wd = work.data;
  const ink = new Float32Array(wd.length);
  for (let pass = 1; ; pass++) {
    const along = smoothAlongFlow(
      dogAcrossFlow(work, field, kernAcross, rc, scale),
      field,
      kernAlong,
      rm,
    );
    const ad = along.data;
    for (let i = 0; i < ink.length; i++) ink[i] = softThreshold(ad[i], eps, sharpness);
    if (pass >= iters) break;
    for (let i = 0; i < wd.length; i++) if (ink[i] < wd[i]) wd[i] = ink[i];
  }
  return new GrayF(src.width, src.height, ink);
}

/** Mirrors Kotlin's `EdgeFlow.clampSigma`: NaN and anything below 0.05 become 0.05. */
function clampSigma(v: number, hi: number): number {
  return Number.isNaN(v) || v < 0.05 ? 0.05 : v > hi ? hi : v;
}

/** Kernel half-width, `ceil(3 * sigma)` — the same convention as every other Gaussian in the engine. */
function radiusFor(sigma: number): number {
  const r = Math.ceil(3 * sigma);
  return r < 1 ? 1 : r;
}

/** Step 1: 1-D DoG along the gradient direction, i.e. perpendicular to the tangent. */
function dogAcrossFlow(
  src: GrayF,
  field: FlowField,
  kern: Float64Array,
  radius: number,
  scale: number,
): GrayF {
  const w = src.width;
  const h = src.height;
  const out = new Float32Array(w * h);
  const d = src.data;
  const tx = field.tx;
  const ty = field.ty;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      // Perpendicular of the tangent; the walk is symmetric so which perpendicular does not matter,
      // only that it is unit length.
      const gx = ty[i];
      const gy = -tx[i];
      let acc = kern[radius] * d[i];
      for (let s = 1; s <= radius; s++) {
        const ox = gx * s;
        const oy = gy * s;
        acc += kern[radius + s] * src.sampleBilinear(x + ox, y + oy);
        acc += kern[radius - s] * src.sampleBilinear(x - ox, y - oy);
      }
      out[i] = acc * scale;
    }
  }
  return new GrayF(w, h, out);
}

/** Step 2: Gaussian integration along the streamline of the flow, both ways from each pixel. */
function smoothAlongFlow(src: GrayF, field: FlowField, kern: Float64Array, radius: number): GrayF {
  const w = src.width;
  const h = src.height;
  const out = new Float32Array(w * h);
  const d = src.data;
  const tx = field.tx;
  const ty = field.ty;
  const maxX = w - 1;
  const maxY = h - 1;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      let acc = kern[0] * d[i];
      let wsum = kern[0];
      // Forward, then backward. Each walk carries its own direction and flips the tangent whenever it
      // disagrees with the previous step, without which the walk turns around at the first sign flip
      // and integrates the same three pixels forever.
      for (let side = 0; side < 2; side++) {
        let dx = side === 0 ? tx[i] : -tx[i];
        let dy = side === 0 ? ty[i] : -ty[i];
        let cx = x;
        let cy = y;
        for (let s = 1; s <= radius; s++) {
          cx += dx;
          cy += dy;
          if (cx < 0 || cy < 0 || cx > maxX || cy > maxY) break;
          const kw = kern[s];
          acc += kw * src.sampleBilinear(cx, cy);
          wsum += kw;
          // Nearest neighbour for the tangent, not bilinear: a director field cannot be interpolated
          // component-wise without first aligning signs, and an averaged (t, -t) pair is the zero
          // vector, which would stall the walk.
          const j = Px.clampInt(Math.round(cy), 0, maxY) * w + Px.clampInt(Math.round(cx), 0, maxX);
          let ntx = tx[j];
          let nty = ty[j];
          if (ntx * dx + nty * dy < 0) {
            ntx = -ntx;
            nty = -nty;
          }
          if (ntx * ntx + nty * nty < 1e-12) break;
          dx = ntx;
          dy = nty;
        }
      }
      out[i] = wsum > 0 ? acc / wsum : d[i];
    }
  }
  return new GrayF(w, h, out);
}

/** Convenience: structure tensor, ETF refinement, then FDoG, with one params record. */
export function coherentLineDrawing(src: GrayF, params: FlowParams): GrayF {
  const field = refineEtf(
    structureTensorFlow(src, params.tensorSigma),
    params.etfIterations,
    params.etfRadius,
  );
  return fdog(
    src,
    field,
    params.sigmaC,
    params.sigmaM,
    params.tau,
    params.fdogIterations,
    params.epsilon,
    params.phi,
  );
}
