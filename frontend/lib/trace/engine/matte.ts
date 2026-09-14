import { GrayF, Mask, Px, RgbaImage } from './buffers';
import { toGray, toLabPlanes } from './color';
import { fillHoles, removeSmallBlobs } from './components';
import { boxBlur, gaussianBlur } from './convolve';
import { nextPowerOfTwo, transform2d } from './fft';
import { resizeGray } from './resample';

/**
 * Model-free matting — the TypeScript mirror of `android/core-imaging/.../Matte.kt`. See
 * ALGORITHMS.md §8.
 *
 * Three cheap cues, each honest about its limits, plus one function that fuses them
 * ({@link subjectMatte}). No matte is ever applied without the user accepting it — a wrong matte
 * quietly deleting half of somebody's artwork is the worst failure this app can have — so the
 * single-cue functions return an alpha and nothing else, the fused one returns a {@link MatteResult}
 * carrying the evidence for its own trustworthiness, and every degenerate case deliberately returns
 * "keep everything" rather than "keep nothing".
 *
 * **Parity note.** Kotlin's `Float` arithmetic is mirrored here either by storing into a
 * `Float32Array` (which rounds for free) or, where a value has to survive in a local across
 * iterations, by an explicit `Math.fround`. The flood's acceptance test and the fusion's `>= 0.5`
 * are both decisions rather than measurements, so a 1e-7 difference between the engines is not
 * invisible here the way it is inside a filter — it moves a pixel from one side of the matte to the
 * other.
 */

/**
 * `Math.fround` under a short name.
 *
 * Every threshold below that Kotlin spells as a `Float` literal is rounded through this, and so is
 * every quantity compared against one. That is not decoration: `0.15f` is 0.15000000596 and the
 * `0.15` on this side is not, so a pixel whose alpha is exactly `Float(0.15)` counts as decided on
 * one engine and undecided on the other. Constants Kotlin declares as `Double` (the fusion weights,
 * the bin scales) are deliberately *not* rounded — rounding those would create the divergence
 * instead of removing it.
 */
const f32 = Math.fround;

/** Lab distances are ~0..100+; a 0..1 tolerance is scaled by this so the control reads as a fraction. */
const MAX_LAB_DISTANCE = 100;

/**
 * Luminance span below which the saliency proxy counts as flat and the spectral residual is declared
 * undefined rather than computed. Two orders of magnitude above float round-off and 400x below one
 * 8-bit code value, so it rejects only proxies whose entire dynamic range is round-off.
 */
const FLAT_LUMA_SPAN = f32(1e-5);

/** Lightness bins. 8 over 0..100 is 12.5 L units — a visible but not a large step. */
const BINS_L = 8;

/**
 * Chroma bins per axis over the full -128..128 Lab range, so one bin is 16 units — roughly the dE at
 * which two colours stop reading as the same paper. Finer bins make the histograms sparse enough
 * that a single pixel decides a bin; coarser ones merge a subject into its background.
 */
const BINS_AB = 16;

const BIN_COUNT = BINS_L * BINS_AB * BINS_AB;

/** Pixels a colour bin needs before {@link borderLikeness} will express an opinion about it. */
const MIN_BIN_EVIDENCE = 4;

// Fusion weights. They sum to exactly 1, so `score` is a 0..1 quantity that can be compared against
// 0.5 without a second normalisation. See `subjectMatte` for why they are in this order.
const W_CONNECT = 0.45;
const W_COLOUR = 0.35;
const W_SALIENCY = 0.2;

/** Smallest surviving blob, as an absolute floor and as a fraction (0.25%) of the frame. */
const MIN_SPECK_AREA = 16;
const SPECK_AREA_DIVISOR = 400;

/** Largest enclosed background region treated as a hole: 2% of the frame, floored at 64 px. */
const MIN_HOLE_AREA = 64;
const HOLE_AREA_DIVISOR = 50;

/**
 * Guided-filter window as a fraction of the short side, and its regularisation.
 *
 * `sqrt(1e-4) = 0.01` is 2.5 code values of an 8-bit luma: luminance steps below that are treated as
 * flat and averaged across, larger ones as edges and preserved. Below about 1e-5 the variance term
 * is competing with cancellation noise in `E[I^2] - E[I]^2` and the filter sharpens the noise.
 */
const GUIDE_RADIUS_FRACTION = f32(0.03);
const GUIDE_EPS = f32(1e-4);

/** Below 4x4 there is no border band, no interior and no neighbourhood — nothing to separate. */
const MIN_MATTE_PIXELS = 16;

/** Coverage below which the separation is declared failed and the whole frame is kept. */
const MIN_KEEP_FRACTION = f32(0.005);

// Coverage confidence ramps: full marks between 3% and 90% of the frame, falling to zero at the two
// ends. Outside that band the matte is not describing a subject on a background.
const HEALTHY_LOW = f32(0.03);
const HEALTHY_HIGH = f32(0.9);
const MAX_KEEP_FRACTION = f32(0.99);

// Two independent cues agreeing on half the frame is chance; 90% is a real signal.
const AGREE_LOW = 0.5;
const AGREE_HIGH = f32(0.9);

// An alpha pixel counts as decided outside this band, and the matte as decisive when at least 90% of
// it is (60% or less is a matte made of soft edges rather than one boundary).
const DECIDED_LOW = f32(0.15);
const DECIDED_HIGH = f32(0.85);
const DECISIVE_LOW = f32(0.6);
const DECISIVE_HIGH = f32(0.9);

/** {@link MatteResult.confidence} at or above which a matte may be applied without asking. */
export const MIN_CONFIDENCE = 0.5;

/**
 * A matte together with everything a caller needs to decide whether to believe it.
 *
 * The point of the structure is that `alpha` is never handed over bare. A caller that applies a
 * matte with `confidence` 0.1 and no `if` has quietly deleted somebody's artwork, and that failure
 * is not detectable from the alpha alone — an alpha keeping 3% of the frame looks exactly like an
 * alpha of a small subject.
 *
 * Kotlin's `Matte.MatteResult` computes `confident` as a getter over {@link MIN_CONFIDENCE}; here it
 * is a field set at construction from the same comparison.
 */
export interface MatteResult {
  /** The matte, the size of the source. `1` keeps the pixel, `0` removes it. */
  readonly alpha: GrayF;
  /** Mean alpha, i.e. the fraction of the frame the matte keeps, 0..1. */
  readonly coverage: number;
  /** 0..1; the weakest of the three checks in {@link subjectMatte}. */
  readonly confidence: number;
  /** `confidence >= MIN_CONFIDENCE`. Below it the caller must ask, or keep the whole frame. */
  readonly confident: boolean;
  /** One sentence, in the user's language, naming what the evidence did. Written to be shown. */
  readonly reason: string;
}

/**
 * Flood the background inward from all four borders and return the alpha of what survived: `0` where
 * the flood reached, `1` elsewhere, feathered by a Gaussian of `feather` pixels.
 *
 * A candidate joins the flood when its dE76 distance to the **running mean of the already-flooded
 * region** is below `tolerance * 100`. Comparing against the running mean rather than the seed
 * colour is the whole point: a photographed background is never one colour, and a fixed reference
 * stops dead halfway down a vignette.
 *
 * The border pixels are *seeds*, not members: each one is still tested against the running mean
 * before it joins. That distinction is what stops a subject that touches the frame from being
 * deleted for touching it.
 *
 * @param tolerance 0..1, scaled to a Lab dE76 distance of `tolerance * 100`
 * @param feather   Gaussian sigma applied to the resulting alpha; <= 0.05 leaves it hard-edged
 * @returns alpha in 0..1 where 1 is kept (subject) and 0 is removed (background); all-opaque if the
 *          flood consumed the entire image, because "delete everything" is never the answer wanted.
 */
export function borderFlood(src: RgbaImage, tolerance: number, feather = 1.5): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const alpha = new GrayF(w, h);
  const [lp, ap, bp] = toLabPlanes(src);

  const tol = f32(f32(Px.clamp(tolerance, 0, 1)) * MAX_LAB_DISTANCE);
  const tolSq = f32(tol * tol);

  const flooded = new Uint8Array(n);
  const queued = new Uint8Array(n);
  // Capacity n is exact: `queued` guarantees an index is on the stack at most once.
  const stack = new Int32Array(n);
  let sp = 0;

  // Seed the running mean with the per-channel median of the four corners. The median of four
  // survives one corner landing on the subject, which the mean does not, and it is still a fixed
  // deterministic starting point.
  const c0 = 0;
  const c1 = w - 1;
  const c2 = (h - 1) * w;
  const c3 = (h - 1) * w + (w - 1);
  let mL = median4(lp[c0], lp[c1], lp[c2], lp[c3]);
  let mA = median4(ap[c0], ap[c1], ap[c2], ap[c3]);
  let mB = median4(bp[c0], bp[c1], bp[c2], bp[c3]);

  for (let x = 0; x < w; x++) {
    const top = x;
    if (queued[top] === 0) {
      queued[top] = 1;
      stack[sp++] = top;
    }
    const bottom = (h - 1) * w + x;
    if (queued[bottom] === 0) {
      queued[bottom] = 1;
      stack[sp++] = bottom;
    }
  }
  for (let y = 0; y < h; y++) {
    const left = y * w;
    if (queued[left] === 0) {
      queued[left] = 1;
      stack[sp++] = left;
    }
    const right = y * w + (w - 1);
    if (queued[right] === 0) {
      queued[right] = 1;
      stack[sp++] = right;
    }
  }

  let sumL = 0;
  let sumA = 0;
  let sumB = 0;
  let cnt = 0;
  let pops = 0;
  // A pixel can be re-queued once per accepted neighbour (the running mean moves, so a rejection is
  // not final), which is bounded at 8 per pixel. The cap makes termination a property of the code
  // rather than of the data.
  const maxPops = 8 * n + 4 * (w + h) + 64;

  while (sp > 0 && pops < maxPops) {
    const idx = stack[--sp];
    queued[idx] = 0;
    pops++;
    if (flooded[idx] !== 0) continue;

    const dl = f32(lp[idx] - mL);
    const da = f32(ap[idx] - mA);
    const db = f32(bp[idx] - mB);
    // Squared compare: a sqrt per candidate is the hottest operation in this loop and the ordering
    // is identical. Rounded per operation because Kotlin's is `Float` arithmetic and this is the
    // comparison that decides which side of the matte a pixel lands on.
    const d2 = f32(f32(f32(dl * dl) + f32(da * da)) + f32(db * db));
    if (d2 > tolSq) continue;

    flooded[idx] = 1;
    sumL += lp[idx];
    sumA += ap[idx];
    sumB += bp[idx];
    cnt++;
    const inv = 1 / cnt;
    mL = f32(sumL * inv);
    mA = f32(sumA * inv);
    mB = f32(sumB * inv);

    const py = (idx / w) | 0;
    const px = idx - py * w;
    const yLo = py > 0 ? py - 1 : 0;
    const yHi = py < h - 1 ? py + 1 : h - 1;
    const xLo = px > 0 ? px - 1 : 0;
    const xHi = px < w - 1 ? px + 1 : w - 1;
    for (let ny = yLo; ny <= yHi; ny++) {
      const row = ny * w;
      for (let nx = xLo; nx <= xHi; nx++) {
        const ni = row + nx;
        if (flooded[ni] === 0 && queued[ni] === 0) {
          queued[ni] = 1;
          stack[sp++] = ni;
        }
      }
    }
  }

  if (cnt >= n) return alpha.fill(1);

  const out = alpha.data;
  for (let i = 0; i < n; i++) out[i] = flooded[i] !== 0 ? 0 : 1;
  if (feather <= 0.05) return alpha;
  const soft = gaussianBlur(alpha, feather);
  for (let i = 0; i < soft.data.length; i++) soft.data[i] = Px.clamp01(soft.data[i]);
  return soft;
}

/**
 * Spectral-residual saliency (Hou & Zhang), normalised to 0..1 and bilinearly upsampled back to the
 * source size.
 *
 * ```
 * A = |FFT(I)|, P = phase(FFT(I)), L = log A
 * R = L - boxBlur(L, 3)
 * S = Gauss(sigma=3, |IFFT(exp(R + iP))|^2)
 * ```
 * Computed on a `proxySize` square proxy because the technique is explicitly a coarse-scale one — at
 * full resolution the residual describes texture, not objects.
 *
 * A power-of-two `proxySize` (the default 64 is one) needs no FFT padding; other values are
 * zero-padded to the next power of two and cropped back, which costs a mild border artefact.
 *
 * @returns saliency in 0..1 at the **source** resolution; an all-zero map for an image that is flat
 *          at proxy scale, because a degenerate range means "nothing found" and never "everything
 *          found". A `[min,max]` stretch of a flat proxy's residual reports saliency exactly 1.0 in
 *          the corner of an image containing nothing, which is how a flat background becomes "the
 *          subject" and background removal eats the artwork.
 */
export function spectralSaliency(src: RgbaImage, proxySize = 64): GrayF {
  const p = Px.clampInt(proxySize, 4, 1024);
  const proxy = resizeGray(toGray(src), p, p);

  const proxyRange = proxy.range();
  if (proxyRange.hi - proxyRange.lo <= FLAT_LUMA_SPAN) return new GrayF(src.width, src.height);

  const fw = nextPowerOfTwo(p);
  const fh = fw;
  const fn = fw * fh;
  const re = new Float32Array(fn);
  const im = new Float32Array(fn);
  for (let y = 0; y < p; y++) {
    const srcRow = y * p;
    const dstRow = y * fw;
    for (let x = 0; x < p; x++) re[dstRow + x] = proxy.data[srcRow + x];
  }

  transform2d(re, im, fw, fh, false);

  const logMag = new Float32Array(fn);
  const cosP = new Float32Array(fn);
  const sinP = new Float32Array(fn);
  for (let i = 0; i < fn; i++) {
    const a = re[i];
    const b = im[i];
    const m = Math.sqrt(a * a + b * b);
    logMag[i] = Math.log(m + 1e-8);
    if (m > 1e-20) {
      // Phase kept as (cos, sin) rather than an angle: reconstruction needs exactly these two
      // numbers and this skips an atan2 plus a cos/sin per bin.
      cosP[i] = a / m;
      sinP[i] = b / m;
    } else {
      cosP[i] = 1;
      sinP[i] = 0;
    }
  }

  const smoothed = boxBlur(new GrayF(fw, fh, logMag), 1).data;
  for (let i = 0; i < fn; i++) {
    const r = Math.exp(logMag[i] - smoothed[i]);
    re[i] = r * cosP[i];
    im[i] = r * sinP[i];
  }

  transform2d(re, im, fw, fh, true);

  // Crop back before blurring so the padded region cannot bleed into the map.
  const sal = new GrayF(p, p);
  for (let y = 0; y < p; y++) {
    const srcRow = y * fw;
    const dstRow = y * p;
    for (let x = 0; x < p; x++) {
      const a = re[srcRow + x];
      const b = im[srcRow + x];
      sal.data[dstRow + x] = a * a + b * b;
    }
  }

  const blurred = gaussianBlur(sal, 3);
  const range = blurred.range();
  const span = range.hi - range.lo;
  // Second line of defence, not the first: this catches only an exactly-constant map, and the
  // flat-input guard above is what keeps a span that is pure round-off from being stretched to full
  // scale. A relative test cannot replace it either — an all-black frame produces a map of [0, 1]
  // whose relative span is a perfectly healthy 1.0 even though every bit of it is the fabricated
  // origin delta.
  if (span <= 1e-12) return new GrayF(src.width, src.height);
  const inv = 1 / span;
  const bd = blurred.data;
  for (let i = 0; i < bd.length; i++) bd[i] = Px.clamp01((bd[i] - range.lo) * inv);
  return resizeGray(blurred, src.width, src.height);
}

/**
 * Thresholds {@link spectralSaliency}, fills interior holes and feathers the result into an alpha
 * channel (1 = keep).
 *
 * If the thresholded region covers less than 0.5% of the frame the matte is abandoned and an
 * all-opaque alpha is returned. A saliency map that found nothing is not a licence to erase the
 * image. Note the polarity, the opposite of {@link spectralSaliency}'s and deliberately so: a
 * degenerate *map* is all-zero ("nothing is salient"), a degenerate *matte* is all-one ("keep every
 * pixel"). Both say the same thing, and neither is ever allowed to come out as "everything found".
 *
 * @param threshold saliency level at or above which a pixel is kept, 0..1
 * @param feather   Gaussian sigma of the edge softening; <= 0.05 leaves it hard-edged
 */
export function saliencyMatte(src: RgbaImage, threshold = 0.5, feather = 2): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const sal = spectralSaliency(src);
  const t = f32(Px.clamp(threshold, 0, 1));

  const mask = new Mask(w, h);
  let on = 0;
  for (let i = 0; i < n; i++) {
    if (sal.data[i] >= t) {
      mask.data[i] = 1;
      on++;
    }
  }
  if (on * 200 < n) return new GrayF(w, h).fill(1);

  // The interior of a subject frequently falls below the threshold even when its outline does not;
  // without the hole fill the matte punches holes through the middle of the subject.
  const solid = fillHoles(mask, Math.max(64, (n / 100) | 0));
  const alpha = solid.toGray();
  if (feather <= 0.05) return alpha;
  const soft = gaussianBlur(alpha, feather);
  for (let i = 0; i < soft.data.length; i++) soft.data[i] = Px.clamp01(soft.data[i]);
  return soft;
}

/**
 * Per-pixel probability that a pixel's colour belongs to the **border band** rather than to the
 * interior: `1` means "this colour is what the edge of the frame is made of", `0` means "this colour
 * only ever appears inside".
 *
 * Two quantised Lab histograms are built in one pass — one over a band `bandFraction` of the short
 * side wide around the frame, one over everything inside it — and each pixel is scored with the
 * posterior of its own bin under equal priors:
 * ```
 * fBand = countBand[bin] / pixelsInBand      fInner = countInner[bin] / pixelsInside
 * likeness = fBand / (fBand + fInner)
 * ```
 * **The densities, not the raw counts, are what make this usable.** The band is a few per cent of
 * the frame, so a ratio of counts would call almost every colour "interior" purely because the
 * interior is twenty times larger.
 *
 * This is the one cue with no connectivity assumption in it, which is exactly why
 * {@link subjectMatte} needs it: {@link borderFlood} fails outright when the subject touches an
 * edge, and this does not — a subject touching the edge contributes its colour to the band, but it
 * contributes it to the interior far more strongly, so the posterior still lands below 0.5.
 *
 * A bin holding fewer than {@link MIN_BIN_EVIDENCE} pixels scores exactly 0.5 — no opinion. One
 * stray pixel of a colour is not evidence about a region, and without that floor a single JPEG
 * artefact in the band would mark every pixel sharing its bin as background.
 *
 * @param bandFraction band width as a fraction of the short side, clamped to 0..0.49; always at
 *                     least 1 px and never wider than half the short side
 * @returns a 0..1 map the size of `src`; a flat 0.5 (no information) for any image with no interior
 *          left once the band is taken, which includes everything under 3 px.
 */
export function borderLikeness(src: RgbaImage, bandFraction = 0.06): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const out = new GrayF(w, h);
  const shortSide = w < h ? w : h;
  // Under 3 px there is no "inside" to contrast the border against, so there is nothing this
  // function can measure. 0.5 everywhere is the honest answer and it makes the fusion in
  // `subjectMatte` fall back on its other two cues instead of on a fabricated one.
  if (shortSide < 3) return out.fill(0.5);
  const frac = f32(Px.clamp(bandFraction, 0, 0.49));
  const band = Px.clampInt(Math.round(f32(frac * shortSide)), 1, (shortSide - 1) >> 1);

  const [lp, ap, bp] = toLabPlanes(src);
  const bandHist = new Int32Array(BIN_COUNT);
  const innerHist = new Int32Array(BIN_COUNT);
  let bandTotal = 0;
  let innerTotal = 0;
  // The bin per pixel is cached rather than recomputed on the second pass: the quantisation is three
  // clamps and three multiplies, and it would otherwise run twice over the whole image.
  const bin = new Int32Array(n);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    const edgeRow = y < band || y >= h - band;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const q = labBin(lp[i], ap[i], bp[i]);
      bin[i] = q;
      if (edgeRow || x < band || x >= w - band) {
        bandHist[q]++;
        bandTotal++;
      } else {
        innerHist[q]++;
        innerTotal++;
      }
    }
  }
  if (bandTotal === 0 || innerTotal === 0) return out.fill(0.5);

  // Once per bin, not once per pixel: 2048 divisions instead of one per pixel of a 12 MP image.
  const posterior = new Float32Array(BIN_COUNT);
  for (let q = 0; q < BIN_COUNT; q++) {
    const cb = bandHist[q];
    const ci = innerHist[q];
    if (cb + ci < MIN_BIN_EVIDENCE) {
      posterior[q] = 0.5;
    } else {
      const fb = cb / bandTotal;
      const fi = ci / innerTotal;
      posterior[q] = fb / (fb + fi);
    }
  }
  const od = out.data;
  for (let i = 0; i < n; i++) od[i] = posterior[bin[i]];
  return out;
}

/**
 * Guided filter (He, Sun & Tang), the model-free way to move a coarse alpha's edge onto the object's
 * edge.
 *
 * Inside every `(2r+1)^2` window the output is assumed to be a **linear function of the guide**,
 * `q = a*I + b`, with `a` and `b` the least-squares fit to `input` over that window; the per-pixel
 * result is the average of the fits of all windows containing it:
 * ```
 * a = cov(I, p) / (var(I) + eps)      b = mean(p) - a * mean(I)
 * q = boxMean(a) * I + boxMean(b)
 * ```
 * That linear assumption is the whole trick: `q` can only step where `I` steps, so an alpha whose own
 * boundary is a pixel or two out comes back with **its steepest transition on the luminance edge
 * underneath it**. Everything downstream traces that boundary, so a matte edge in the wrong place is
 * that many pixels of wrong drawing.
 *
 * Be precise about what that does and does not promise. It is a re-*shaping*, not a re-*locating*:
 * the transition is rebuilt at the guide's edge, and the level at which the result crosses 0.5
 * therefore moves toward it, but a boundary displaced further than about `radius` cannot be pulled
 * all the way back — the windows that would have to see both edges at once do not exist.
 *
 * Cost is six {@link boxBlur} passes and is independent of `radius`, which is why the radius can be
 * a useful fraction of the image instead of a token 2 px.
 *
 * @param eps    regularisation in the units of `guide^2`; larger means smoother
 * @param radius <= 0 returns a copy of `input` — a zero-radius window has no neighbourhood to fit to
 * @returns an unclamped field the size of `input`; a caller matting with it should clamp, because
 *          the linear fit legitimately overshoots slightly on either side of a hard edge
 * @throws if `guide` and `input` differ in size.
 */
export function guidedFilter(guide: GrayF, input: GrayF, radius: number, eps: number): GrayF {
  if (guide.width !== input.width || guide.height !== input.height) {
    throw new Error(
      `guidedFilter(): guide ${guide.width}x${guide.height} does not match ` +
        `input ${input.width}x${input.height}`,
    );
  }
  if (radius <= 0) return input.copy();
  const w = guide.width;
  const h = guide.height;
  const n = w * h;
  const g = guide.data;
  const p = input.data;

  const gg = new GrayF(w, h);
  const gp = new GrayF(w, h);
  for (let i = 0; i < n; i++) {
    const gi = g[i];
    gg.data[i] = gi * gi;
    gp.data[i] = gi * p[i];
  }
  const meanG = boxBlur(guide, radius).data;
  const meanP = boxBlur(input, radius).data;
  const meanGG = boxBlur(gg, radius).data;
  const meanGP = boxBlur(gp, radius).data;

  const a = new GrayF(w, h);
  const b = new GrayF(w, h);
  const e = eps < 0 ? 0 : eps;
  for (let i = 0; i < n; i++) {
    const mg = meanG[i];
    const mp = meanP[i];
    // `E[I^2] - E[I]^2` is a subtraction of two nearly equal numbers wherever the window is flat, so
    // it lands a hair below zero there; a negative variance would flip the sign of `a` and put a
    // dark halo around every flat region.
    let varG = meanGG[i] - mg * mg;
    if (varG < 0) varG = 0;
    const den = varG + e;
    const ai = den <= 0 ? 0 : (meanGP[i] - mg * mp) / den;
    a.data[i] = ai;
    b.data[i] = mp - ai * mg;
  }
  const ma = boxBlur(a, radius).data;
  const mb = boxBlur(b, radius).data;
  const out = new GrayF(w, h);
  for (let i = 0; i < n; i++) out.data[i] = ma[i] * g[i] + mb[i];
  return out;
}

/**
 * Foreground/background separation from all three cues at once, with a confidence figure.
 *
 * Neither existing matte is trustworthy on its own — {@link borderFlood} fails when the subject
 * touches an edge or the background is textured, {@link saliencyMatte} is a 64x64 map that misses
 * the flat interior of a large object — so this fuses them with {@link borderLikeness}, refines the
 * result against the image, and reports how well it went.
 *
 * **The fusion rule.** Each cue votes for *foreground* in 0..1: `c` = not reached by the border
 * flood, `b` = colour unlike the border band (`1 - borderLikeness`), `s` = spectrally salient.
 * ```
 * score = 0.45*c + 0.35*b + 0.20*s
 * ```
 * with two overrides for the cases where the two **structural** cues agree, because there the answer
 * is not a matter of degree:
 *  - flooded **and** border-coloured -> `0`. Both cues say background by different arguments;
 *    saliency, which leaks roughly one 64th of the frame past every object boundary, does not get to
 *    drag a halo of background back in.
 *  - not flooded **and** not border-coloured -> `1`. Likewise, a coarse map that found nothing
 *    salient in the flat middle of a white pot does not get to punch a hole in it.
 *
 * The weights say what each cue is worth where they *disagree*, which is the only place the
 * arithmetic matters. Connectivity is the largest because where it fires it is exact — an explicit
 * 8-connected path of background-coloured pixels to the frame edge — but it is one threshold away
 * from failing completely. The colour posterior is next because it is per-pixel and survives the
 * case connectivity cannot (a subject touching the edge), but it cannot tell apart a subject that
 * happens to share the background's colours. Saliency is smallest and is deliberately never decisive
 * alone: at 64x64 one proxy pixel covers 1/64 of the frame.
 *
 * The vote is then thresholded at 0.5 — it exists to make the *decision*, not to be the alpha — and
 * the resulting mask is cleaned in order: specks below 0.25% of the frame are dropped (**and the
 * prune is reverted wholesale if it would cost more than a tenth of the foreground** — a speck
 * filter may tidy, it may not amputate); enclosed holes up to 2% of the frame are filled, which is
 * what stops the pattern *inside* a subject reading as background. Only then does the hard mask go
 * through {@link guidedFilter} against the luma, which puts the steep part of the alpha's transition
 * on the object's own edge instead of wherever the vote's 0.5 happened to fall.
 *
 * **Confidence** is the minimum of three independent checks, so it is as strong as its weakest link
 * and {@link MatteResult.reason} can name which one that was:
 *  1. *coverage plausibility* — a matte keeping 0.5% or 99% of the frame separated nothing;
 *  2. *cue agreement* — the fraction of the frame on which connectivity and colour reached the same
 *     verdict. Two independent cues agreeing is the only evidence available here that the answer is
 *     about the image rather than about one cue's failure mode;
 *  3. *decisiveness* — the fraction of the refined alpha actually near 0 or near 1. An alpha that is
 *     grey everywhere has located no boundary at all.
 *
 * @param tolerance dE76 tolerance for the border flood, 0..1
 * @param feather   optional extra Gaussian softening of the finished alpha, in pixels; <= 0.05
 *                  leaves the guided filter's own edge alone, which is normally what you want
 * @returns a {@link MatteResult} whose alpha is **all-opaque** whenever the separation failed or the
 *          image is too small to separate — never an alpha that empties the frame.
 */
export function subjectMatte(src: RgbaImage, tolerance = 0.18, feather = 0): MatteResult {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  if (n < MIN_MATTE_PIXELS) {
    return {
      alpha: new GrayF(w, h).fill(1),
      coverage: 1,
      confidence: 0,
      confident: false,
      reason:
        `A ${w}x${h} image is too small to tell a subject from a background, so the whole ` +
        'frame was kept.',
    };
  }

  // Feather is applied once, at the end, to the fused alpha. Feathering the flood here would put a
  // soft ramp into a cue that the fusion then compares against 0.5, which turns a boundary decision
  // into a coin toss along the whole outline.
  const connect = borderFlood(src, tolerance, 0).data;
  const likeness = borderLikeness(src).data;
  const salience = spectralSaliency(src).data;

  const mask = new Mask(w, h);
  const md = mask.data;
  let on = 0;
  let agree = 0;
  for (let i = 0; i < n; i++) {
    const c = connect[i];
    const b = likeness[i];
    const fgByConnect = c >= 0.5;
    const fgByColour = b < 0.5;
    if (fgByConnect === fgByColour) agree++;
    let score: number;
    if (!fgByConnect && !fgByColour) score = 0;
    else if (fgByConnect && fgByColour) score = 1;
    else score = W_CONNECT * c + W_COLOUR * (1 - b) + W_SALIENCY * salience[i];
    if (score >= 0.5) {
      md[i] = 1;
      on++;
    }
  }

  let cleaned = mask;
  if (on > 0) {
    const pruned = removeSmallBlobs(mask, Math.max(MIN_SPECK_AREA, (n / SPECK_AREA_DIVISOR) | 0));
    // A speck filter is allowed to tidy and not to amputate.
    if (pruned.countTrue() * 10 >= on * 9) cleaned = pruned;
  }
  const filled = fillHoles(cleaned, Math.max(MIN_HOLE_AREA, (n / HOLE_AREA_DIVISOR) | 0));

  // **The vote decides, the guided filter shapes.** What goes into the refinement is a hard 0/1 mask
  // and not the graded score, and the difference is not cosmetic: a fused score is 0.625 across an
  // image where the cues merely disagree everywhere, and feeding that forward produces an alpha that
  // fades the whole picture by 37% instead of keeping it. Uncertainty belongs in `confidence`, where
  // a caller can act on it; smeared into the alpha it is just a wash nobody asked for.
  const pre = new GrayF(w, h);
  const pd = pre.data;
  const fd = filled.data;
  for (let i = 0; i < n; i++) pd[i] = fd[i] !== 0 ? 1 : 0;

  const shortSide = w < h ? w : h;
  const radius = Math.max(2, Math.round(f32(GUIDE_RADIUS_FRACTION * shortSide)));
  const refined = guidedFilter(toGray(src), pre, radius, GUIDE_EPS);
  let alpha = new GrayF(w, h);
  for (let i = 0; i < n; i++) alpha.data[i] = Px.clamp01(refined.data[i]);
  if (feather > 0.05) {
    const soft = gaussianBlur(alpha, feather);
    for (let i = 0; i < n; i++) soft.data[i] = Px.clamp01(soft.data[i]);
    alpha = soft;
  }

  let kept = 0;
  let decided = 0;
  const ad = alpha.data;
  for (let i = 0; i < n; i++) {
    const v = ad[i];
    kept += v;
    if (v <= DECIDED_LOW || v >= DECIDED_HIGH) decided++;
  }
  const coverage = f32(kept / n);
  if (coverage < MIN_KEEP_FRACTION) {
    // Same invariant as `saliencyMatte` and `borderFlood`: a separation that found nothing returns
    // "keep everything", never "keep nothing". The reported coverage is the returned alpha's, which
    // is 1 — the failed measurement is in the sentence.
    return {
      alpha: new GrayF(w, h).fill(1),
      coverage: 1,
      confidence: 0,
      confident: false,
      reason:
        `Background separation kept only ${percent(coverage)}% of the frame, which reads as ` +
        'a failed matte rather than a small subject, so the whole frame was kept.',
    };
  }

  const agreement = f32(agree / n);
  const decisiveness = f32(decided / n);
  const cCoverage = Math.min(
    ramp(coverage, MIN_KEEP_FRACTION, HEALTHY_LOW),
    1 - ramp(coverage, HEALTHY_HIGH, MAX_KEEP_FRACTION),
  );
  const cAgreement = ramp(agreement, AGREE_LOW, AGREE_HIGH);
  const cDecisive = ramp(decisiveness, DECISIVE_LOW, DECISIVE_HIGH);
  const confidence = Math.min(cCoverage, cAgreement, cDecisive);

  let reason: string;
  if (confidence >= MIN_CONFIDENCE) {
    reason =
      `The border flood and the border-colour model agree on ${percent(agreement)}% of the ` +
      `frame; the matte keeps ${percent(coverage)}% of it and ${percent(decisiveness)}% ` +
      'of that is a clear decision rather than a soft edge.';
  } else if (cCoverage <= cAgreement && cCoverage <= cDecisive) {
    reason =
      coverage >= HEALTHY_HIGH
        ? `The matte kept ${percent(coverage)}% of the frame, so almost nothing was ` +
          'separated out — treat this image as having no background to remove.'
        : `The matte kept only ${percent(coverage)}% of the frame, which is small enough ` +
          'that it is more likely a failed separation than a small subject.';
  } else if (cAgreement <= cDecisive) {
    reason =
      'The border flood and the border-colour model disagree about ' +
      `${percent(1 - agreement)}% of the frame, so where the subject ends is a guess ` +
      'rather than a measurement.';
  } else {
    reason =
      `${percent(1 - decisiveness)}% of the matte is neither clearly subject nor clearly ` +
      'background, so it has softened an edge rather than found one.';
  }
  return { alpha, coverage, confidence, confident: confidence >= MIN_CONFIDENCE, reason };
}

/**
 * Composite `src` over a flat colour using `alpha`, per channel and non-premultiplied.
 * @param background packed ARGB; its own alpha participates, so compositing over `0x00000000` yields a
 *                   transparent result rather than a black one
 * @throws if `alpha` is not the same size as `src`.
 */
export function applyMatteRgba(src: RgbaImage, alpha: GrayF, background: number): RgbaImage {
  if (src.width !== alpha.width || src.height !== alpha.height) {
    throw new Error('applyMatte(): size mismatch');
  }
  const n = src.pixels.length;
  const out = new Uint32Array(n);
  const px = src.pixels;
  const a = alpha.data;
  const bg = background >>> 0;
  const bgA = (bg >>> 24) & 0xff;
  const bgR = (bg >>> 16) & 0xff;
  const bgG = (bg >>> 8) & 0xff;
  const bgB = bg & 0xff;
  for (let i = 0; i < n; i++) {
    const t = Px.clamp01(a[i]);
    const v = px[i];
    const sa = (v >>> 24) & 0xff;
    const sr = (v >>> 16) & 0xff;
    const sg = (v >>> 8) & 0xff;
    const sb = v & 0xff;
    out[i] = RgbaImage.argb(
      Math.round(sa * t + bgA * (1 - t)),
      Math.round(sr * t + bgR * (1 - t)),
      Math.round(sg * t + bgG * (1 - t)),
      Math.round(sb * t + bgB * (1 - t)),
    );
  }
  return new RgbaImage(src.width, src.height, out);
}

/** Composite a grey plane over a flat level: `out = src * alpha + background * (1 - alpha)`. */
export function applyMatteGray(src: GrayF, alpha: GrayF, background: number): GrayF {
  if (src.width !== alpha.width || src.height !== alpha.height) {
    throw new Error('applyMatte(): size mismatch');
  }
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  const a = alpha.data;
  for (let i = 0; i < n; i++) {
    const t = Px.clamp01(a[i]);
    out[i] = d[i] * t + background * (1 - t);
  }
  return new GrayF(src.width, src.height, out);
}

/** Overloaded dispatcher mirroring Kotlin's two `Matte.applyMatte` overloads. */
export function applyMatte(src: RgbaImage, alpha: GrayF, background: number): RgbaImage;
export function applyMatte(src: GrayF, alpha: GrayF, background: number): GrayF;
export function applyMatte(
  src: RgbaImage | GrayF,
  alpha: GrayF,
  background: number,
): RgbaImage | GrayF {
  if (src instanceof GrayF) return applyMatteGray(src, alpha, background);
  return applyMatteRgba(src, alpha, background);
}

/** Median of four values (mean of the middle two), by explicit compare — no sorting, no boxing. */
function median4(a: number, b: number, c: number, d: number): number {
  let v0 = a;
  let v1 = b;
  let v2 = c;
  let v3 = d;
  let t: number;
  if (v0 > v1) {
    t = v0;
    v0 = v1;
    v1 = t;
  }
  if (v2 > v3) {
    t = v2;
    v2 = v3;
    v3 = t;
  }
  if (v0 > v2) {
    t = v0;
    v0 = v2;
    v2 = t;
  }
  if (v1 > v3) {
    t = v1;
    v1 = v3;
    v3 = t;
  }
  if (v1 > v2) {
    t = v1;
    v1 = v2;
    v2 = t;
  }
  return f32((v1 + v2) * 0.5);
}

/**
 * Quantises one Lab colour to a {@link BIN_COUNT} histogram index. `| 0` truncates toward zero,
 * which is what Kotlin's `Float.toInt()` does, so a colour sitting exactly on a bin edge lands in
 * the same bin on both engines.
 */
function labBin(l: number, a: number, b: number): number {
  const li = Px.clampInt(l * (BINS_L / 100), 0, BINS_L - 1);
  const ai = Px.clampInt((a + 128) * (BINS_AB / 256), 0, BINS_AB - 1);
  const bi = Px.clampInt((b + 128) * (BINS_AB / 256), 0, BINS_AB - 1);
  return (li * BINS_AB + ai) * BINS_AB + bi;
}

/** Linear 0..1 ramp between `lo` and `hi`; a degenerate range is a step, never a divide by zero. */
function ramp(v: number, lo: number, hi: number): number {
  if (hi <= lo) return v >= hi ? 1 : 0;
  return Px.clamp01(f32(f32(v - lo) / f32(hi - lo)));
}

/**
 * 0..1 as a whole percent for the sentences above. Deliberately integer arithmetic and not a
 * formatter: a locale-aware one would emit "0,5" in half of Europe.
 */
function percent(v: number): number {
  return Math.round(Px.clamp01(v) * 100);
}
