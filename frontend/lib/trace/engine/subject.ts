import { GrayF, Px, RgbaImage } from './buffers';
import { subjectMatte } from './matte';
import { cropGray, cropRgba } from './resample';

/**
 * Locating and cropping to the thing the user actually photographed — the TypeScript mirror of
 * `android/core-pipeline/.../Subject.kt`.
 *
 * This is the half of "extract only the relevant object" that comes after matting: `matte.ts`
 * decides which pixels are subject, and this decides which *rectangle* is worth keeping, or refuses.
 *
 * **Every refusal returns the full frame.** An alpha that kept almost everything separated nothing,
 * and an alpha that kept almost nothing is a failed matte; cropping to either destroys the picture,
 * so both come back as the whole frame with `confident: false` and a sentence saying so. That
 * asymmetry is deliberate and it is the reason this is a separate module rather than four lines
 * inside the pipeline: the refusals are the feature.
 *
 * The aspect ratio is never forced. A tall pot crops to a tall box, and a caller that wants a square
 * can expand this one — expanding a box cannot lose subject, and cropping to a forced square can.
 */

/** `Math.fround`; see the parity note in `matte.ts` for why every `Float` threshold goes through it. */
const f32 = Math.fround;

/** Alpha at or above which a pixel counts as subject. Matches the matte's own decision level. */
export const DEFAULT_THRESHOLD = 0.5;

/**
 * Margin added on every side, as a fraction of the tight box's **longer** side.
 *
 * Of the longer side, not of each axis independently: a 3 px wide vertical stroke would get a margin
 * of 0 px horizontally under a per-axis rule, and clip on exactly the axis where clipping is most
 * visible. One margin, taken from the dimension that has something to measure.
 */
export const DEFAULT_MARGIN_FRACTION = f32(0.04);

/**
 * Coverage at or below which the box is refused: the matte found a subject smaller than half a
 * percent of the frame, which is far more often a failed separation than a small subject.
 */
export const MIN_COVERAGE = f32(0.004);

/**
 * Coverage at or above which the box is refused: nothing was separated out, so there is no
 * background to crop away.
 */
export const MAX_COVERAGE = f32(0.97);

/**
 * Absolute floor on the number of subject pixels, independent of frame size.
 *
 * {@link MIN_COVERAGE} alone is not enough. On a 16x16 thumbnail a single lit pixel is 0.4% of the
 * frame — above the fraction — and cropping a photograph to one pixel plus a margin is the worst
 * output this function could produce. A subject is at least a small blob, whatever the frame.
 */
export const MIN_SUBJECT_PIXELS = 16;

/**
 * Where the subject is, as a rectangle the caller may crop to — or a refusal.
 *
 * `x`, `y`, `w`, `h` are always a **legal** sub-rectangle of the frame the box was measured in, so
 * `crop(img, box)` is safe without any further checking. When `confident` is `false` the rectangle
 * is the whole frame, which makes the crop a no-op rather than a decision: a caller that forgets to
 * test `confident` loses nothing, which is the only acceptable failure mode for an operation that
 * can otherwise remove part of somebody's artwork permanently.
 */
export interface SubjectBox {
  readonly x: number;
  readonly y: number;
  readonly w: number;
  readonly h: number;
  /**
   * Fraction of the frame the alpha kept above the threshold, 0..1. This is the measurement the
   * refusals are made on, and it is reported even when the box is refused so a UI can say *why*
   * without recomputing anything.
   */
  readonly coverage: number;
  /** `true` only when the box is a genuine, trustworthy crop to a subject. */
  readonly confident: boolean;
  /** One sentence, in the user's language, for what happened. Written to be shown. */
  readonly reason: string;
}

/**
 * A finished subject search: the matte, the box, and one verdict covering both.
 *
 * `confident` is `true` only when **both** the matte and the box are trustworthy. Cropping to a box
 * measured from a matte nobody believes is the exact failure this whole module exists to make
 * impossible, so the two verdicts are ANDed here rather than at each call site.
 */
export interface SubjectFind {
  /** The matte the box was measured from, the size of the source. */
  readonly alpha: GrayF;
  readonly box: SubjectBox;
  readonly confidence: number;
  readonly confident: boolean;
  readonly reason: string;
}

/**
 * The tight bounds of `alpha >= threshold`, expanded by a margin, or the full frame if cropping to it
 * would be wrong.
 *
 * One pass over the alpha accumulating a count and four extremes — O(n), no allocation, and the same
 * answer every time for the same input.
 *
 * The margin exists because the boundary of a matte is not the boundary of the drawing: a stroke
 * that ends exactly on the alpha's edge is traced right up to it, and a tight crop then slices the
 * stroke's outer half off. It is a fraction of the box rather than a fixed pixel count so it behaves
 * the same on a 400 px thumbnail and a 6000 px scan.
 *
 * @param threshold      clamped to 0..1
 * @param marginFraction negative is treated as zero; any positive value gives at least 1 px, so
 *                       asking for a margin never silently produces none
 * @returns a {@link SubjectBox} that is always a legal sub-rectangle of `alpha`'s frame.
 */
export function boundingBox(
  alpha: GrayF,
  threshold = DEFAULT_THRESHOLD,
  marginFraction = DEFAULT_MARGIN_FRACTION,
): SubjectBox {
  const w = alpha.width;
  const h = alpha.height;
  const n = w * h;
  const t = f32(Px.clamp(threshold, 0, 1));
  const d = alpha.data;

  let minX = w;
  let minY = h;
  let maxX = -1;
  let maxY = -1;
  let count = 0;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    let rowHit = false;
    for (let x = 0; x < w; x++) {
      if (d[row + x] >= t) {
        count++;
        rowHit = true;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
      }
    }
    if (rowHit) {
      if (y < minY) minY = y;
      maxY = y;
    }
  }

  const coverage = f32(count / n);
  if (count === 0) {
    return full(
      w,
      h,
      0,
      'The matte kept no pixels at all, so there is nothing to crop to and the whole frame was kept.',
    );
  }
  if (count < MIN_SUBJECT_PIXELS) {
    return full(
      w,
      h,
      coverage,
      `The matte kept ${count} ${count === 1 ? 'pixel' : 'pixels'}, which is too little to be a ` +
        'subject, so the whole frame was kept.',
    );
  }
  if (coverage <= MIN_COVERAGE) {
    return full(
      w,
      h,
      coverage,
      `The matte kept ${percent(coverage)}% of the frame, which reads as a failed separation ` +
        'rather than a small subject, so the whole frame was kept.',
    );
  }
  if (coverage >= MAX_COVERAGE) {
    return full(
      w,
      h,
      coverage,
      `The matte kept ${percent(coverage)}% of the frame, so there is no background to crop away ` +
        'and the whole frame was kept.',
    );
  }

  const tightW = maxX - minX + 1;
  const tightH = maxY - minY + 1;
  const margin =
    marginFraction <= 0 ? 0 : Math.max(1, Math.round(f32(marginFraction * Math.max(tightW, tightH))));
  const x0 = Math.max(0, minX - margin);
  const y0 = Math.max(0, minY - margin);
  const x1 = Math.min(w - 1, maxX + margin);
  const y1 = Math.min(h - 1, maxY + margin);
  const bw = x1 - x0 + 1;
  const bh = y1 - y0 + 1;

  const reason =
    bw === w && bh === h
      ? 'The subject reaches every edge of the frame, so nothing was cropped away.'
      : `Cropped to the subject: ${bw}x${bh} of ${w}x${h} (${percent(coverage)}% of the frame is ` +
        `subject), with a ${margin} px margin so nothing at the edge of it is clipped.`;
  return { x: x0, y: y0, w: bw, h: bh, coverage, confident: true, reason };
}

/**
 * {@link subjectMatte} followed by {@link boundingBox}, with the two verdicts combined.
 *
 * This is the one call the pipeline needs. Doing it in two steps at the call site is fine too, but
 * then the `&&` between the two `confident` flags lives at the call site, and that `&&` is the thing
 * that must not be forgotten.
 *
 * @param tolerance dE76 tolerance for the matte's border flood, 0..1
 * @returns a {@link SubjectFind} whose alpha is all-opaque and whose box is the full frame if either
 *          half refused.
 */
export function locate(
  src: RgbaImage,
  tolerance = 0.18,
  threshold = DEFAULT_THRESHOLD,
  marginFraction = DEFAULT_MARGIN_FRACTION,
): SubjectFind {
  const matte = subjectMatte(src, tolerance);
  const box = boundingBox(matte.alpha, threshold, marginFraction);
  return {
    alpha: matte.alpha,
    box,
    confidence: matte.confidence,
    confident: matte.confident && box.confident,
    reason: `${matte.reason} ${box.reason}`,
  };
}

/**
 * `src` cropped to `box`.
 *
 * Safe against a box measured from a differently-sized alpha (a proxy, say): the underlying crop
 * clamps the rectangle into the image rather than throwing. A caller cropping the full-resolution
 * image with a box measured on a proxy still has to scale the box itself — clamping keeps that
 * mistake from crashing, it does not make it correct.
 */
export function crop(src: RgbaImage, box: SubjectBox): RgbaImage;
export function crop(src: GrayF, box: SubjectBox): GrayF;
export function crop(src: RgbaImage | GrayF, box: SubjectBox): RgbaImage | GrayF {
  if (src instanceof GrayF) return cropGray(src, box.x, box.y, box.w, box.h);
  return cropRgba(src, box.x, box.y, box.w, box.h);
}

/** Every refusal is the same shape: the whole frame, not confident, and a sentence saying why. */
function full(w: number, h: number, coverage: number, reason: string): SubjectBox {
  return { x: 0, y: 0, w, h, coverage, confident: false, reason };
}

/**
 * 0..1 as a whole percent. Integer arithmetic and not a formatter, because a locale-aware one would
 * put a comma in the decimal separator for half of Europe.
 */
function percent(v: number): number {
  return Math.round(Px.clamp01(v) * 100);
}
