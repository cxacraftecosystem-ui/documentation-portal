/**
 * Cropping and sharpening a photograph on the device, as arithmetic and nothing else.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHAT THIS FILE IS FOR, AND THE ONE THING IT DELIBERATELY CANNOT DO
 * ────────────────────────────────────────────────────────────────────────────
 *
 * The owner asked for a crop tool and for "a way to sharpen images further", with all image
 * processing staying on the edge device. This module is the whole of the arithmetic for both. It
 * takes RGBA bytes in `ImageData` order and returns RGBA bytes in `ImageData` order.
 *
 * **IT PRODUCES NO `File`, TOUCHES NO CANVAS, AND KNOWS NOTHING ABOUT MEDIA.** That is not tidiness;
 * it is the safety property. `docs/MEDIA_PIPELINE.md` §5 is explicit that "the original file *is* the
 * artifact", that re-encoding a photograph through a canvas destroys resolution and strips the EXIF
 * the app preserves, and that if such a mode is ever wanted "it should be an explicit, off-by-default
 * … mode … never a silent default". A module that could mint a `File` is one call site away from
 * being that silent default. This one cannot: nothing here can displace a photograph, because
 * nothing here can produce something the upload door would take.
 *
 * What the crop and the sharpen therefore feed is **the trace**, and only the trace. See
 * `components/sketches/upload/FramePanel.tsx` for the surface and for the sentence the designer reads.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY THE ENGINE'S OWN UNSHARP MASK AND NOT A SECOND ONE
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `engine/contrast.ts:238` already has `unsharpMask(src, sigma, amount, threshold)` — a separable
 * Gaussian (`engine/convolve.ts:98`) subtracted from the original with a threshold gate — and
 * `engine/pipeline.ts:552` already runs it on the grey plane the edge stage sees. It is vendored,
 * cross-engine parity-tested against the Kotlin side, and it is the sharpening opinion this
 * repository already holds. A hand-rolled convolution here would be a second opinion about what
 * "sharpen" means, and the two would drift the first time either was tuned.
 *
 * So this file calls `Contrast.unsharpMask` and adds nothing of its own except the two conversions at
 * the ends: RGBA to a luminance plane and the sharpened difference back onto the colour channels.
 *
 * ── LUMINANCE, NOT THREE CHANNELS, AND THE WEIGHTS ARE NOT THIS FILE'S OPINION EITHER ────────────
 *
 * Sharpening R, G and B separately means three blurs — three times the cost — and produces coloured
 * fringes on every high-contrast edge, which is exactly what a photograph of a pencil line on paper
 * is made of. Sharpening the luminance and applying the same delta to all three channels is one blur,
 * has no chroma to fringe, and is what the trace actually consumes: `pipeline.ts:514` greys the image
 * with `Color.toGray` before any edge engine runs, so luminance is the plane the trace sees.
 *
 * {@link lumaPlane} therefore reproduces `engine/color.ts:31 toGray` **exactly** — the same Rec.601
 * weights, in the same multiplication order, with the same `1/255` scale, so the float32 stores are
 * bit-identical. It is repeated rather than called because `toGray` takes an `RgbaImage`, whose
 * backing store is a `Uint32Array` of packed ARGB: building one from `ImageData` bytes would cost a
 * full extra pass and another 4 bytes per pixel, and this file's whole memory budget (below) is spent
 * on the float planes. `e2e/sketch-frame-sharpen-unit.spec.ts` asserts the two agree pixel for pixel
 * against the real `Color.toGray`, so the duplication is held in place by a test rather than by this
 * paragraph.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * THE MEMORY BUDGET, WHICH IS WHY THERE IS A CAP AT ALL
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `decodeToPixels.ts`'s header already records the failure this is guarding against: "three copies of
 * a 12 MP buffer is how a 2 GB handset kills the tab", and `DECODE_MAX_EDGE_PX = 4096` means a
 * 4000x3000 photograph reaches this module uncapped.
 *
 * Counted honestly, for `n` pixels, the peak live bytes of one sharpen are:
 *
 *   RGBA in/out (written in place)      4n
 *   the luminance plane                 4n
 *   `separable`'s intermediate plane    4n   (transient, inside gaussianBlur)
 *   `gaussianBlur`'s output             4n
 *   `unsharpMask`'s output              4n
 *   ------------------------------------------
 *   peak                               20n bytes
 *
 * At 12.6 MP (4096x3072) that is 252 MB, which is well past what killed the tab before. At
 * {@link SHARPEN_MAX_PIXELS} it is 160 MB, inside a worker, once, on a button press.
 *
 * **The cap REFUSES rather than quietly reducing the frame.** Silently sharpening a downscaled copy
 * and handing it back at the smaller size would trace a coarser photograph than the one on screen
 * with nothing saying so — rule 10 of the frontend guide, and the single most repeated bug class in
 * this repository. The refusal names the number and points at the crop, which is the way through and
 * is in the same panel: cropping is what makes a 12 MP frame a 6 MP one.
 */

import { unsharpMask } from "./engine/contrast";
import { GrayF } from "./engine/buffers";

/* ────────────────────────────────────────────────────────────────────────────
 * The shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * RGBA bytes plus their size — structurally what `decodeToPixels.DecodedPixels` is, minus the fields
 * that describe where it came from.
 *
 * Deliberately NOT importing `DecodedPixels`: this module is in `lib/` and that type is in
 * `components/`, and the dependency would point the wrong way. The overlap is checked by the compiler
 * at every call site anyway, because a `DecodedPixels` satisfies this interface.
 */
export interface EditablePixels {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
}

/** A crop box in source pixels. `x`/`y` are the near edge; the far edge is exclusive. */
export interface CropRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/**
 * The smallest crop the panel allows.
 *
 * Not zero, and not one: a crop of a few pixels is never a thing a designer meant, and every stage of
 * the trace downstream reads global statistics off the frame — Otsu's threshold over sixteen pixels is
 * arithmetic on noise. 16 is small enough never to be in anybody's way and large enough that the
 * result is still an image.
 */
export const CROP_MIN_EDGE_PX = 16;

/** @returns the crop that is the whole frame — what "no crop" is, spelled as a rectangle. */
export function wholeFrame(width: number, height: number): CropRect {
  return { x: 0, y: 0, width: Math.max(1, Math.round(width)), height: Math.max(1, Math.round(height)) };
}

/**
 * Force a rectangle to be a usable crop of a `width` x `height` frame.
 *
 * INTEGER, INSIDE THE FRAME, AND AT LEAST {@link CROP_MIN_EDGE_PX} ON EACH EDGE. Every one of those
 * three is a real failure otherwise: a fractional `x` reaches `cropPixels` as a fractional row offset
 * and silently truncates, a box the drag pushed past the edge reads pixels that are not there, and a
 * zero-width box throws inside `GrayF`'s constructor rather than declining.
 *
 * The order matters, and it is TWO steps rather than the three this paragraph used to describe. The
 * size is clamped to the frame FIRST, then the origin is clamped so the box still fits. Clamping the
 * origin first would let a large box push itself back off the far edge. There is no third pass and
 * none is owed: `minW`/`minH` are `min(CROP_MIN_EDGE_PX, frame)`, so a frame smaller than the minimum
 * edge is already handled by the first clamp — a reader who went looking for the trim named here
 * found nothing and had no way to tell a missing step from a stale sentence. Checked in both
 * directions by `e2e/sketch-frame-geometry-unit.spec.ts`, whose first case includes a 9px frame.
 *
 * ⚠ **THE CLAMP IS TOTAL, AND THAT IS WHY IT MUST NOT BE HANDED A DRAG.** It always answers with a
 * legal rectangle, so a box that hangs off the edge comes back SLID back inside — which moves the
 * corner nobody was touching. `FramePanel` did exactly that and every outward corner drag silently
 * reset the crop to the whole photograph; the fix is
 * `components/sketches/upload/frameGeometry.moveCropCorner`, which clamps the two edges the dragged
 * corner owns and only then builds a rectangle. Use this for "make that legal", never for "follow
 * that finger".
 */
export function clampCrop(rect: CropRect, width: number, height: number): CropRect {
  const frameW = Math.max(1, Math.floor(width));
  const frameH = Math.max(1, Math.floor(height));
  const minW = Math.min(CROP_MIN_EDGE_PX, frameW);
  const minH = Math.min(CROP_MIN_EDGE_PX, frameH);

  let w = Math.round(Number.isFinite(rect.width) ? rect.width : frameW);
  let h = Math.round(Number.isFinite(rect.height) ? rect.height : frameH);
  w = Math.min(frameW, Math.max(minW, w));
  h = Math.min(frameH, Math.max(minH, h));

  let x = Math.round(Number.isFinite(rect.x) ? rect.x : 0);
  let y = Math.round(Number.isFinite(rect.y) ? rect.y : 0);
  x = Math.min(frameW - w, Math.max(0, x));
  y = Math.min(frameH - h, Math.max(0, y));

  return { x, y, width: w, height: h };
}

/** True when this rectangle is the entire frame, i.e. when there is nothing to say about a crop. */
export function isWholeFrame(rect: CropRect, width: number, height: number): boolean {
  const whole = wholeFrame(width, height);
  return rect.x === 0 && rect.y === 0 && rect.width === whole.width && rect.height === whole.height;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Crop
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Copy the pixels inside `rect` out of `src`.
 *
 * ROW BY ROW WITH `subarray` + `set`, WHICH IS A `memcpy` PER ROW rather than a loop per byte. Four
 * byte-writes per pixel over a 12 MP frame is fifty million bounds-checked stores; one typed-array
 * copy per row is 3072 of them. The clamp is applied here as well as at the call sites because this
 * is the function that would read out of bounds, and a `Uint8ClampedArray` read past its end returns
 * `undefined` — which `set` writes as 0, i.e. a black band nobody would trace back to a rounding error.
 *
 * @returns a new buffer. The source is never modified — a crop the designer widens again has to be
 *   able to come back, so the decoded photograph stays whole and every crop is taken from it afresh.
 */
export function cropPixels(src: EditablePixels, rect: CropRect): EditablePixels {
  const box = clampCrop(rect, src.width, src.height);
  if (isWholeFrame(box, src.width, src.height)) {
    return { data: src.data.slice(), width: src.width, height: src.height };
  }
  const out = new Uint8ClampedArray(box.width * box.height * 4);
  const rowBytes = box.width * 4;
  for (let row = 0; row < box.height; row += 1) {
    const from = ((box.y + row) * src.width + box.x) * 4;
    out.set(src.data.subarray(from, from + rowBytes), row * rowBytes);
  }
  return { data: out, width: box.width, height: box.height };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Sharpen
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sharpening the designer asked for, as two numbers.
 *
 * THE SAME TWO THE ENGINE ALREADY TAKES, AND THE SAME UNITS. `params.ts:462` declares
 * `preprocess.unsharpAmount` (0..5, default 0) and `preprocess.unsharpSigma` (0.05..32, default 1.5),
 * and `traceParamTable.ts:186-211` already draws them as "Sharpen amount" and "Sharpen radius" in the
 * Sharpening group. This panel's sliders are those same two names over those same units, applied one
 * stage earlier — see {@link sharpenPixels}. Two controls called "sharpen" that meant different
 * things by "amount" would be the worst of both.
 */
export interface SharpenSettings {
  /** How much of the difference is added back. 0 is off. */
  readonly amount: number;
  /** The Gaussian sigma in pixels — the size of detail being lifted. */
  readonly radius: number;
  /** Differences at or below this are left alone, so flat paper does not gain grain. 0..1. */
  readonly threshold: number;
}

/** Off. The default, exactly as the engine's own `unsharpAmount` defaults to 0. */
export const NO_SHARPEN: SharpenSettings = { amount: 0, radius: 1.5, threshold: 0 };

/** The slider bounds, matching `traceParamTable`'s two rows so the two controls cannot disagree. */
export const SHARPEN_AMOUNT_MIN = 0;
export const SHARPEN_AMOUNT_MAX = 5;
export const SHARPEN_RADIUS_MIN = 0.3;
export const SHARPEN_RADIUS_MAX = 8;
export const SHARPEN_THRESHOLD_MAX = 0.2;

/**
 * The largest frame this device is asked to sharpen. See the header's budget: 20 bytes per pixel of
 * peak live memory, so this is 160 MB.
 *
 * 8 MP is also, not coincidentally, larger than the 2048px working edge the trace runs at by default
 * — so the sharpen is genuinely happening before the engine's downscale for every frame it accepts,
 * which is the only thing that makes it different from the slider the engine already has.
 */
export const SHARPEN_MAX_PIXELS = 8_000_000;

/** True when `settings` would change nothing, so the caller can skip the work rather than prove it. */
export function isSharpenOff(settings: SharpenSettings): boolean {
  return !(settings.amount > 0);
}

/**
 * @returns the tap count `engine/convolve.gaussianKernel` will build for this radius.
 *
 * Reproduced rather than imported for one reason: this is used to write a sentence on screen before
 * any engine module has been fetched, and importing `convolve` to read a number would pull the engine
 * chunk onto the page graph — the thing `SketchTraceField`'s fourth rule forbids. It is
 * `2 * max(1, ceil(3σ)) + 1`, quoted from that function's own docblock, and the unit spec checks it
 * against the real kernel's length.
 */
export function sharpenKernelTaps(radius: number): number {
  if (!(radius > 0.05)) return 3;
  return 2 * Math.max(1, Math.ceil(3 * radius)) + 1;
}

/** What a sharpen of this size would cost, in the terms a sentence can be built from. */
export interface SharpenPlan {
  readonly pixels: number;
  readonly megapixels: number;
  readonly taps: number;
  /** Multiply-adds in the two separable passes — the only term that is not linear in the pixels. */
  readonly multiplyAdds: number;
  /** Peak live bytes, by the header's count. */
  readonly peakBytes: number;
  /** Non-null when this frame is over {@link SHARPEN_MAX_PIXELS}: the sentence to show instead. */
  readonly refusal: string | null;
}

/**
 * Cost and admissibility for a frame of this size, without doing any of the work.
 *
 * THE NUMBERS ARE ARITHMETIC, NOT A PREDICTION IN MILLISECONDS. A device's actual time depends on
 * things this module cannot see, and a figure quoted as "about 2 seconds" that turns out to be nine
 * on a five-year-old handset is worse than no figure — this repository's rule is that a measured
 * number is quoted only by whoever measured it. So the panel shows the size and the work, states that
 * a large photograph takes seconds on a phone, and then shows the MEASURED time of the run that
 * actually happened. See `FramePanel`.
 */
export function planSharpen(pixels: number, radius: number): SharpenPlan {
  const n = Math.max(0, Math.floor(pixels));
  const taps = sharpenKernelTaps(radius);
  const megapixels = n / 1_000_000;
  const refusal =
    n > SHARPEN_MAX_PIXELS
      ? `Sharpening runs on frames up to ${(SHARPEN_MAX_PIXELS / 1_000_000).toFixed(1)} megapixels on ` +
        `this device, and this one is ${megapixels.toFixed(1)}. Crop it first — the crop above is what ` +
        "makes a frame smaller — or trace it unsharpened and use the “Sharpen amount” control in " +
        "Sharpening, which works on the smaller plane the trace itself runs at."
      : null;
  return {
    pixels: n,
    megapixels,
    taps,
    multiplyAdds: n * taps * 2,
    peakBytes: n * 20,
    refusal
  };
}

/**
 * The luminance plane, byte-for-byte the same answer as `engine/color.toGray`.
 *
 * See the header for why it is repeated here instead of called. The multiplication order and the
 * `* INV255` at the end are copied deliberately: floating-point addition is not associative, so
 * `(0.299 * r + 0.587 * g + 0.114 * b) * (1 / 255)` and `0.299 * r / 255 + …` are different numbers,
 * and the parity spec compares these two functions exactly.
 */
export function lumaPlane(src: EditablePixels): GrayF {
  const n = src.width * src.height;
  const out = new Float32Array(n);
  const d = src.data;
  const inv255 = 1 / 255;
  for (let i = 0, j = 0; i < n; i += 1, j += 4) {
    out[i] = (0.299 * d[j] + 0.587 * d[j + 1] + 0.114 * d[j + 2]) * inv255;
  }
  return new GrayF(src.width, src.height, out);
}

/**
 * Unsharp-mask the photograph's luminance and put the difference back on all three colour channels.
 *
 * ── WHY THE DIFFERENCE AND NOT THE SHARPENED LUMINANCE ──────────────────────────────────────────
 *
 * Replacing each pixel's luminance outright means recovering its chroma and re-deriving R, G and B
 * from it — a colour-space round trip, with its own rounding, its own opinion about how to preserve
 * hue, and a visible desaturation wherever a channel clips. Adding the same signed delta to all three
 * channels leaves the colour differences between them untouched, which is what "sharpen" means to
 * anybody looking at it: the edges get crisper and nothing changes colour. It is also the only version
 * of this that needs no second colour opinion in the codebase.
 *
 * ── WHAT IS AND IS NOT CLAMPED ──────────────────────────────────────────────────────────────────
 *
 * `unsharpMask` returns unclamped floats on purpose — the engine's rule is "never clamp between
 * stages". But this function's output is 8-bit RGBA, so the clamp has to happen here, and it happens
 * where the engine would do it: `Uint8ClampedArray` clamps and rounds-to-nearest-even on store, which
 * is the same rule `Px.toByte255` states in words. An unsharp mask at a real amount produces overshoot
 * at every strong edge by design, so the clamp is the normal case rather than an error path — a
 * pencil line on white paper will have its halo clipped at 255, and that is what sharpening a
 * photograph of paper looks like.
 *
 * @param src RGBA in `ImageData` order. Not modified.
 * @returns a new buffer of the same size, or `src` copied when {@link isSharpenOff}.
 */
export function sharpenPixels(src: EditablePixels, settings: SharpenSettings): EditablePixels {
  const n = src.width * src.height;
  if (isSharpenOff(settings) || n === 0) {
    return { data: src.data.slice(), width: src.width, height: src.height };
  }
  const grey = lumaPlane(src);
  // THE VENDORED FUNCTION, WITH NOTHING ADDED. Amount, sigma and threshold go straight through; the
  // engine owns what each of them means. See the header on why there is not a second one of these.
  const sharp = unsharpMask(grey, settings.radius, settings.amount, settings.threshold);
  const out = new Uint8ClampedArray(n * 4);
  const from = src.data;
  const a = grey.data;
  const b = sharp.data;
  for (let i = 0, j = 0; i < n; i += 1, j += 4) {
    // The delta is in 0..1 luminance, so it scales back to bytes by 255. One multiply, three adds.
    const delta = (b[i] - a[i]) * 255;
    out[j] = from[j] + delta;
    out[j + 1] = from[j + 1] + delta;
    out[j + 2] = from[j + 2] + delta;
    // ALPHA IS COPIED, NEVER SHARPENED. A photograph is opaque, but a PNG of a sketch on a
    // transparent background is not, and adding an edge signal to its alpha would put a halo of
    // partial transparency around every stroke — visible only once something was composited behind it.
    out[j + 3] = from[j + 3];
  }
  return { data: out, width: src.width, height: src.height };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Both, and the provenance sentence
 * ──────────────────────────────────────────────────────────────────────────── */

/** One request: crop, then sharpen what the crop kept. */
export interface EditRequest {
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
}

/**
 * Crop first, then sharpen — and the order is a decision with a visible consequence.
 *
 * CROP FIRST BECAUSE IT IS FREE AND MAKES THE SHARPEN CHEAPER. A crop to a quarter of the frame is a
 * quarter of the blur, which on a phone is the difference between a second and four. The cost of the
 * order is at the crop's own boundary: `gaussianBlur` clamps at the edge of the plane it is given, so
 * the outermost few pixels of a crop are blurred against the crop's edge rather than against the
 * pixels just outside it, which the source still holds. That is a halo of at most `3σ` pixels — three
 * to five, at usable radii — on a boundary the designer chose and cropped away from. Sharpening the
 * whole frame first would remove it and cost the full frame every time.
 *
 * @returns the edited pixels. Never `src` itself, so a caller can hold both.
 */
export function applyEdit(src: EditablePixels, request: EditRequest): EditablePixels {
  const cropped = cropPixels(src, request.crop);
  if (isSharpenOff(request.sharpen)) return cropped;
  return sharpenPixels(cropped, request.sharpen);
}

/**
 * What was done to the pixels, as a clause for the provenance note written into the exported SVG.
 *
 * WHY THIS BELONGS HERE AND NOT IN THE COMPONENT. `traceExport.exportSvgFile` takes a
 * `provenanceNote` and `buildSvg` writes it into the file, which is the only channel in which a
 * derived drawing carries how it was made. A crop is destructive — everything outside it is gone from
 * the drawing — so a reviewer holding the SVG and the photograph needs to be able to tell why they do
 * not match. Building the sentence beside the arithmetic is what keeps it true when the arithmetic
 * changes.
 *
 * @returns an empty string when nothing was done, so a caller can concatenate unconditionally.
 */
export function describeEdit(
  request: EditRequest,
  sourceWidth: number,
  sourceHeight: number
): string {
  const parts: string[] = [];
  const box = clampCrop(request.crop, sourceWidth, sourceHeight);
  if (!isWholeFrame(box, sourceWidth, sourceHeight)) {
    parts.push(
      `Cropped on the device to ${box.width}x${box.height} at (${box.x}, ${box.y}) of ` +
        `${sourceWidth}x${sourceHeight}.`
    );
  }
  if (!isSharpenOff(request.sharpen)) {
    parts.push(
      `Sharpened on the device with an unsharp mask, amount ${round2(request.sharpen.amount)}, ` +
        `radius ${round2(request.sharpen.radius)}px` +
        (request.sharpen.threshold > 0 ? `, threshold ${round2(request.sharpen.threshold)}` : "") +
        ". The original photograph is unchanged."
    );
  }
  return parts.join(" ");
}

function round2(value: number): string {
  return (Math.round(value * 100) / 100).toString();
}
