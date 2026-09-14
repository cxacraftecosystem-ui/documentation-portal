/**
 * The two pictures the before/after slider needs, made from what the panel already has.
 *
 * WHY THIS FILE EXISTS AT ALL. `TraceCompare.tsx` takes two `src` strings and nothing else. The
 * tracing panel holds neither: it holds `DecodedPixels` (the RGBA the engine was handed) and flat
 * path geometry (what the worker sent back). Neither is an image. This turns each into one PNG blob,
 * both at the SAME pixel size, so the comparator's two stacked layers line up exactly.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * THE FOUR DECISIONS, EACH OF WHICH IS A BUG IF TAKEN THE OTHER WAY
 * ────────────────────────────────────────────────────────────────────────────
 *
 * 1. **THE PHOTOGRAPH COMES FROM `DecodedPixels`, NOT FROM THE `File`.** The obvious route is
 *    `URL.createObjectURL(file)`, and it is wrong twice. `decodeToPixels`'s header records that a bare
 *    `createImageBitmap(file)` is the one EXIF-orientation opinion this app holds, and an `<img>`
 *    element applies its own — so a phone portrait can arrive rotated in one layer and upright in the
 *    other, which reads as "the trace is sideways". And the pixels are what the engine ACTUALLY traced,
 *    capped to `DECODE_MAX_EDGE_PX`, so comparing against them compares against the truth rather than
 *    against a file the trace only ever saw a resized copy of.
 *
 * 2. **THE TRACE PLATE IS PAINTED ON OPAQUE WHITE, ALWAYS.** `output.background` defaults to null —
 *    a transparent document (`engine/params.ts`, and the "White background" toggle in
 *    `traceParamTable.ts`) — and a transparent AFTER layer stacked over the photograph shows the
 *    photograph through both layers. The divider then moves and nothing changes, which is
 *    indistinguishable from a broken slider. The white is the COMPARISON's, not the export's: the file
 *    handed to the host and the file downloaded keep whatever transparency the researcher chose, and
 *    the panel says so in words beside the comparator.
 *
 * 3. **BOTH PLATES ARE THE SAME SIZE, DERIVED FROM THE TRACED FRAME.** `engine/pipeline.ts` builds its
 *    document at `sourceWidth x sourceHeight` — the frame of the pixels it was given — and its crop
 *    stage says so in its own note: "The exported coordinates are still in the original frame." So the
 *    two frames agree by construction, and this asserts it rather than assuming it: a mismatch beyond
 *    a rounding pixel comes back as a refusal, because a comparator whose layers disagree about the
 *    frame is a comparator that misattributes every line it draws.
 *
 * 4. **NEITHER PLATE IS EVER OFFERED TO THE RECORD.** These are display bitmaps for one slider, capped
 *    at {@link COMPARISON_LONG_EDGE_PX} and re-encoded. `docs/MEDIA_PIPELINE.md` — "the original file
 *    *is* the artifact … re-encoding through a canvas destroys full resolution and strips the EXIF" —
 *    forbids exactly this arriving anywhere near an upload, and names a "helpful" canvas re-encode as
 *    the thing that rule exists to stop. Nothing here returns a `File`, and that is deliberate:
 *    `traceExport.ts` is where a `File` is made, and it works from full-resolution geometry rather
 *    than from a display plate.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * AND A THIRD PLATE, BUILT ONLY WHEN IT IS ASKED FOR
 * ────────────────────────────────────────────────────────────────────────────
 *
 * {@link buildDifferencePlate} subtracts the two — see {@link differenceRgba} for the arithmetic and
 * for why it is the one definition both clients implement. It is deliberately NOT built by
 * {@link buildComparisonPlates}: it is the only one of the four views that costs a third plate, and
 * building it with the other two would add a third PNG encode to every settled trace — which is once
 * per slider drag, on the page thread.
 */

import { workingSizeFor } from "./decodeToPixels";
import type { DecodedPixels } from "./decodeToPixels";
import { canvasToBlob, createCanvas, paintGeometry } from "./traceExport";
import type { SvgInput } from "./geometryToSvg";

/**
 * The longest edge either comparison plate may have.
 *
 * 1024 rather than the 2048 an exported PNG is allowed, because these two exist only to be looked at
 * inside a panel that is at most a few hundred CSS pixels wide, and there are TWO of them held as
 * blob URLs for as long as the comparator is on screen. A 4096px pair is ~130 MB of decoded bitmap
 * pinned in the tab to show a 400px picture — and `decodeToPixels`'s header is explicit that three
 * copies of one big buffer is how a 2 GB handset kills the page. The panel states the reduction.
 */
export const COMPARISON_LONG_EDGE_PX = 1024;

/** The two plates, and everything the panel has to be able to say about them. */
export interface ComparisonPlates {
  /** The traced drawing, on white. Pass as the comparator's `afterImage`. */
  readonly trace: Blob;
  /** The photograph the engine traced. Pass as the comparator's `beforeImage`. */
  readonly original: Blob;
  readonly width: number;
  readonly height: number;
  /** True when {@link COMPARISON_LONG_EDGE_PX} shrank the plates. Say so on screen. */
  readonly reduced: boolean;
}

/** Why no comparison could be built, in a sentence written to be shown to a researcher. */
export interface ComparisonRefusal {
  readonly reason: string;
}

export type ComparisonOutcome = ComparisonPlates | ComparisonRefusal;

export function isComparable(outcome: ComparisonOutcome): outcome is ComparisonPlates {
  return "trace" in outcome;
}

/**
 * Box-filter downscale of an RGBA plane.
 *
 * WHY NOT `drawImage`. Scaling with the canvas means holding the FULL-SIZE plane on a second surface
 * first — `putImageData` cannot scale — so a 4096x3072 photograph costs a 50 MB canvas on the way to
 * a 1024px thumbnail, on the device least able to afford it. This reads the source array directly and
 * writes only the destination.
 *
 * WHY NOT NEAREST-NEIGHBOUR, which is four lines shorter. Dropping seven of every eight pixels of a
 * photograph of a pencil drawing drops the pencil: fine lines alias into a dotted mess, and a
 * researcher comparing a trace against an aliased photograph is being shown a fault that is not in
 * either.
 *
 * WHY NOT `engine/resample.ts`, which already has this. Because importing it here would put engine
 * modules in the page's own bundle, which `TracePanel.tsx`'s header forbids in as many words ("Do not
 * add a top-level import from `@/lib/trace/*` to this file") — the engine is reached only through
 * `traceRuntime.ts`'s dynamic import. Thirty lines of arithmetic is the cheaper side of that trade,
 * and it runs in Node, which the engine's copy behind a dynamic import does not.
 *
 * The average is per channel, alpha included and not premultiplied. Correct for a photograph, which
 * is opaque everywhere; a source with hard transparency would show a faint halo where it meets an
 * opaque edge, and no photograph this panel decodes has one.
 *
 * Never upscales — a caller asking for more pixels than there are gets a copy at the source size, so
 * the cap can only ever shrink. Same rule as `workingSizeFor`.
 *
 * SYNCHRONOUS, AND THEREFORE FOR SMALL PLANES AND FOR TESTS. Every source pixel is read exactly once,
 * so the cost is the SOURCE's size and not the plate's: a 4096x4096 decode is 16.7 million pixels and
 * 67 million array reads, which is a long task on the page thread however small the answer is. Both
 * callers in this repository run on a decode that big, so both use {@link resampleRgbaInBands}. This
 * one is still the definition of the arithmetic, and the two share every line of it.
 */
export function resampleRgba(
  source: Uint8ClampedArray,
  sourceWidth: number,
  sourceHeight: number,
  targetWidth: number,
  targetHeight: number
): Uint8ClampedArray {
  const plan = planResample(source, sourceWidth, sourceHeight, targetWidth, targetHeight);
  if (plan.done) return plan.out;
  fillBand(plan, 0, plan.height);
  return plan.out;
}

/**
 * How many source pixels one band may read before the page gets a turn.
 *
 * 1 million reads is single-digit milliseconds on a laptop and tens on a slow handset — comfortably
 * inside a frame's budget either way — and it bounds the number of yields as well as the length of the
 * tasks: 16.7 million source pixels is at most 17 bands, so the yielding costs at most a handful of
 * task hops rather than one per row. Bands are whole destination rows, because a box straddles rows
 * and splitting one would mean reading its source twice.
 *
 * EXPORTED ONLY SO A TEST CAN BUILD A PLANE BIG ENOUGH TO CROSS A BAND BOUNDARY. A case that hard-coded
 * a size would stop covering the boundaries the moment this number moved, silently.
 */
export const BAND_SOURCE_PIXELS = 1_000_000;

/**
 * The same box filter, in bands, giving the page thread a turn between them.
 *
 * WHY THIS EXISTS AND THE SYNCHRONOUS ONE IS NOT ENOUGH. This is display work — a plate for a slider —
 * and it runs on the page thread, per settled trace. A multi-hundred-millisecond synchronous pass is
 * not a slow tab, it is a frozen one, with no spinner able to animate. The work is unchanged and no
 * faster; it is merely interruptible, so nothing else on the page is held up by it.
 *
 * WHY NOT A WORKER. `buildComparisonPlates` needs a canvas either side of this call (`paintGeometry`,
 * then `putImageData` and `toBlob`), so a worker would mean shipping the plane out and back for one
 * of three steps — and one averaging pass per pixel is two orders of magnitude less arithmetic than
 * the pipeline stages that DO justify a worker's own cost (a chunk fetch, a module instantiation, a
 * 32 MB transfer).
 *
 * @param shouldStop asked between bands. When it returns true the work stops and `null` comes back —
 *   the caller has already lost interest, and finishing would be a whole plane of wasted arithmetic
 *   while a newer one is being built. Never called before the first band, so a caller that is already
 *   stale gets `null` without any work at all.
 */
export async function resampleRgbaInBands(
  source: Uint8ClampedArray,
  sourceWidth: number,
  sourceHeight: number,
  targetWidth: number,
  targetHeight: number,
  shouldStop?: () => boolean
): Promise<Uint8ClampedArray | null> {
  const plan = planResample(source, sourceWidth, sourceHeight, targetWidth, targetHeight);
  if (plan.done) return plan.out;

  // Whole destination rows, at least one, sized so a band reads about `BAND_SOURCE_PIXELS` of source.
  const perRow = Math.max(1, Math.round((sourceWidth * sourceHeight) / plan.height));
  const rowsPerBand = Math.max(1, Math.floor(BAND_SOURCE_PIXELS / perRow));

  for (let y = 0; y < plan.height; y += rowsPerBand) {
    if (shouldStop?.()) return null;
    fillBand(plan, y, Math.min(plan.height, y + rowsPerBand));
    // A MACROTASK, NOT A MICROTASK. `await Promise.resolve()` would yield to the microtask queue and
    // to nothing else — no rendering, no input handler, no timer — so the page would still be frozen
    // for the whole pass. `setTimeout(0)` is a task boundary, which is the thing being asked for.
    if (y + rowsPerBand < plan.height) await new Promise((resolve) => setTimeout(resolve, 0));
  }
  return plan.out;
}

/** The destination plane and the two scale ratios, or the finished copy when there is nothing to do. */
type ResamplePlan =
  | { readonly done: true; readonly out: Uint8ClampedArray }
  | {
      readonly done: false;
      readonly out: Uint8ClampedArray;
      readonly source: Uint8ClampedArray;
      readonly sourceWidth: number;
      readonly sourceHeight: number;
      readonly width: number;
      readonly height: number;
    };

function planResample(
  source: Uint8ClampedArray,
  sourceWidth: number,
  sourceHeight: number,
  targetWidth: number,
  targetHeight: number
): ResamplePlan {
  const width = Math.max(1, Math.min(Math.round(targetWidth), sourceWidth));
  const height = Math.max(1, Math.min(Math.round(targetHeight), sourceHeight));
  const out = new Uint8ClampedArray(width * height * 4);
  if (width === sourceWidth && height === sourceHeight) {
    out.set(source.subarray(0, Math.min(source.length, out.length)));
    return { done: true, out };
  }
  return { done: false, out, source, sourceWidth, sourceHeight, width, height };
}

/** Destination rows `[from, to)`. The one copy of the arithmetic; both entry points call it. */
function fillBand(plan: Extract<ResamplePlan, { done: false }>, from: number, to: number): void {
  const { out, source, sourceWidth, sourceHeight, width, height } = plan;
  for (let y = from; y < to; y += 1) {
    // The far edge is exclusive and floored from the NEXT destination row, so consecutive boxes tile
    // the source exactly: no source pixel is read twice and none is skipped. `Math.max(y0 + 1, …)`
    // covers the degenerate case of a destination larger than the source in one axis only.
    const y0 = Math.floor((y * sourceHeight) / height);
    const y1 = Math.max(y0 + 1, Math.floor(((y + 1) * sourceHeight) / height));
    for (let x = 0; x < width; x += 1) {
      const x0 = Math.floor((x * sourceWidth) / width);
      const x1 = Math.max(x0 + 1, Math.floor(((x + 1) * sourceWidth) / width));
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      let n = 0;
      for (let sy = y0; sy < y1; sy += 1) {
        let index = (sy * sourceWidth + x0) * 4;
        for (let sx = x0; sx < x1; sx += 1) {
          r += source[index];
          g += source[index + 1];
          b += source[index + 2];
          a += source[index + 3];
          index += 4;
          n += 1;
        }
      }
      const at = (y * width + x) * 4;
      out[at] = r / n;
      out[at + 1] = g / n;
      out[at + 2] = b / n;
      out[at + 3] = a / n;
    }
  }
}

/**
 * @returns one PNG of the photograph and one of the drawing, both {@link ComparisonPlates.width} by
 *   {@link ComparisonPlates.height} — or a sentence saying why not.
 *
 * The caller owns the blobs. Nothing here creates an object URL: a URL is a thing that must be
 * revoked, and the component that renders it is the only place that knows when it stopped being on
 * screen. Handing one back would put the leak in this file and the fix in another.
 */
export async function buildComparisonPlates(
  pixels: DecodedPixels,
  input: SvgInput,
  options: {
    longEdge?: number;
    /**
     * Asked between bands of the downscale. When it goes true the build stops and returns a refusal
     * nobody is meant to show — the caller that stopped caring is the caller that will discard it.
     * Passing it is what keeps a slider drag from paying for every superseded comparison in full.
     */
    shouldStop?: () => boolean;
  } = {}
): Promise<ComparisonOutcome> {
  const longEdge = options.longEdge ?? COMPARISON_LONG_EDGE_PX;
  const traceWidth = Number.isFinite(input.width) && input.width > 0 ? Math.round(input.width) : 0;
  const traceHeight = Number.isFinite(input.height) && input.height > 0 ? Math.round(input.height) : 0;
  if (traceWidth < 1 || traceHeight < 1 || pixels.width < 1 || pixels.height < 1) {
    return { reason: "There is nothing to compare yet." };
  }

  // Decision 3 in the header. A one-pixel disagreement is rounding; anything larger means the trace
  // and the photograph are not the same frame, and stacking them would put every line in the wrong
  // place while looking entirely plausible.
  if (Math.abs(traceWidth - pixels.width) > 1 || Math.abs(traceHeight - pixels.height) > 1) {
    return {
      reason:
        `The drawing was traced in a ${traceWidth}x${traceHeight} frame and the image was read at ` +
        `${pixels.width}x${pixels.height}, so the two cannot be laid over each other. The drawing above is ` +
        "unaffected."
    };
  }

  const box = workingSizeFor(traceWidth, traceHeight, Math.max(1, Math.round(longEdge)));
  const reduced = box.width < traceWidth || box.height < traceHeight;

  const traceCanvas = createCanvas(box.width, box.height);
  const originalCanvas = createCanvas(box.width, box.height);
  if (traceCanvas === null || originalCanvas === null) {
    return { reason: "This browser would not give the page a drawing surface, so the comparison cannot be drawn here." };
  }
  const traceContext = traceCanvas.getContext("2d") as
    | CanvasRenderingContext2D
    | OffscreenCanvasRenderingContext2D
    | null;
  const originalContext = originalCanvas.getContext("2d") as
    | CanvasRenderingContext2D
    | OffscreenCanvasRenderingContext2D
    | null;
  if (traceContext === null || originalContext === null) {
    return { reason: "This browser would not give the page a drawing surface, so the comparison cannot be drawn here." };
  }

  // Decision 2. White FIRST, then the geometry — and `paintGeometry` still paints the document's own
  // background over it when the researcher turned one on, so a chosen background is not overridden,
  // only backed.
  traceContext.fillStyle = "#ffffff";
  traceContext.fillRect(0, 0, box.width, box.height);
  paintGeometry(traceContext, input, box.width / traceWidth);

  // `resampleRgba` never upscales, so its answer is the box or the source — whichever is smaller in
  // each axis. With the frames asserted equal above the two agree, and taking the minimum rather than
  // assuming the box is what keeps a one-pixel rounding difference from throwing.
  const plateWidth = Math.min(box.width, pixels.width);
  const plateHeight = Math.min(box.height, pixels.height);
  const small = await resampleRgbaInBands(
    pixels.data,
    pixels.width,
    pixels.height,
    plateWidth,
    plateHeight,
    options.shouldStop
  );
  // Only reachable through `shouldStop` — see its note. The sentence exists because a refusal type
  // has to carry one, not because anybody is expected to read this.
  if (small === null) return { reason: "The comparison was replaced by a newer one." };
  // `context.createImageData` RATHER THAN `new ImageData(small, w, h)`: the constructor's typed-array
  // parameter is declared over `ArrayBuffer` and a `Uint8ClampedArray` is declared over
  // `ArrayBufferLike`, so the direct call needs a cast to compile — and a cast here would be a cast
  // over the one thing worth checking, which is that the array and the dimensions agree. This copies
  // into a buffer the context itself sized.
  const plate = originalContext.createImageData(plateWidth, plateHeight);
  plate.data.set(small);
  originalContext.putImageData(plate, 0, 0);

  const [trace, original] = await Promise.all([canvasToBlob(traceCanvas), canvasToBlob(originalCanvas)]);
  if (trace === null || original === null) {
    return {
      reason:
        "This browser could not turn the comparison into images — usually because the image is very " +
        "large. The drawing above is unaffected."
    };
  }

  return { trace, original, width: box.width, height: box.height, reduced };
}

/* ────────────────────────────────────────────────────────────────────────────
 * What the panel says about the comparison
 * ──────────────────────────────────────────────────────────────────────────── */

/** Everything the comparator's one status line has to distinguish between. */
export interface ComparisonStatusInput {
  /** True once both plates are built and on screen. */
  readonly haveCompare: boolean;
  /** A refusal from {@link buildComparisonPlates}, or null. */
  readonly compareProblem: string | null;
  /** True while a trace — preview or full — is running. */
  readonly tracing: boolean;
  /** The engine's own progress sentence, when there is one. */
  readonly progress: string | null;
  /** The red message already on screen about a trace that failed, or null. */
  readonly traceProblem: string | null;
  /** True once any trace has produced geometry. */
  readonly haveResult: boolean;
}

/**
 * The one sentence that says why the comparator is not showing a comparison, and the empty string
 * when it is.
 *
 * ── ALL SIX ABSENCES ANSWERED IN ONE PLACE ────────────────────────────────────────────────────
 *
 * They are genuinely different and a single "no comparison" placeholder would flatten them into one
 * shrug: nothing traced yet, a trace still running, a trace that failed, a comparison that could not
 * be built from a trace that succeeded, an update in flight over a comparison already on screen, and
 * the ordinary case where there is nothing to say at all. The fourth is the one that would otherwise
 * be invisible — {@link buildComparisonPlates} refuses when the browser gives it no drawing surface,
 * or when the traced frame and the decoded frame disagree, and both refusals are sentences written to
 * be read.
 *
 * ── THE ORDER IS THE CONTENT ──────────────────────────────────────────────────────────────────
 *
 * A comparison already on screen wins over everything, because a stale sentence under a live picture
 * is worse than no sentence; a comparison refusal wins over the trace's progress, because the trace
 * has already finished by then; and the failed-trace branch POINTS AT the red message rather than
 * restating it — two copies of one fault in one card is how a researcher ends up believing there are
 * two.
 *
 * ── AND WHY IT IS A FUNCTION IN THIS FILE RATHER THAN A TERNARY IN THE PANEL ───────────────────
 *
 * There is no React renderer in this repository's devDependencies, so anything inside JSX cannot be
 * exercised at all. This is six branches of copy that a researcher reads at the exact moment they are
 * unsure whether the tool is working, so it is lifted out to where `e2e/trace-compare-unit.spec.ts`
 * can stand in front of it.
 *
 * @returns the sentence, or the empty string when the comparator needs no explanation.
 */
export function comparisonStatusFor(input: ComparisonStatusInput): string {
  if (input.haveCompare) return input.tracing ? "Updating…" : "";
  if (input.compareProblem !== null) return input.compareProblem;
  if (input.tracing) return input.progress ?? "Tracing…";
  if (input.traceProblem !== null) {
    return "The trace did not finish, so there is nothing to compare. The reason is above.";
  }
  if (!input.haveResult) return "The comparison appears as soon as the first trace finishes.";
  return "Preparing the comparison…";
}

/* ────────────────────────────────────────────────────────────────────────────
 * The difference plate
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the panel says about the difference view, once, under the frame.
 *
 * ONE WORDING FOR BOTH CLIENTS. The handset's tracing panel shows the same picture and this is its
 * sentence; a researcher moving between the two apps mid-visit reads one description of one picture
 * rather than two.
 */
export const COMPARISON_DIFFERENCE_NOTE =
  "Difference subtracts the two pictures from each other: black where they agree, bright where they " +
  "do not. A line the trace missed and a line it invented both come out bright, and the paper’s own " +
  "tone shows as an even dim grey.";

/**
 * What the panel says when the third plate could not be made.
 *
 * The handset's sentence with one word changed — it says "This phone" and there is no phone here.
 * Same class of divergence as "Download" against "Save" in the export row: the sentence is shared,
 * the noun is the platform's. Everything after the first clause is identical, including the part that
 * matters most, which is that nothing else was lost.
 */
export const COMPARISON_DIFFERENCE_REFUSAL =
  "This browser could not make room for the difference picture. The wipe and the two whole pictures " +
  "still work, and the drawing is unaffected.";

/**
 * What the panel says while the third plate is being subtracted.
 *
 * IT SURVIVES THE PLATFORM SWAP UNCHANGED, unlike {@link COMPARISON_DIFFERENCE_REFUSAL} beside it,
 * because it names no device — a wait is a wait on both.
 */
export const COMPARISON_DIFFERENCE_PENDING = "Working out the difference picture…";

/**
 * How the difference picture is described to a screen reader.
 *
 * IT SAYS WHAT THE PICTURE MEANS, NOT WHAT IT IS. "The difference between the traced drawing and the
 * photograph" names the operation and leaves a reader who cannot see the plate with no way to
 * interpret it. Dark-is-agreement and bright-is-disagreement is the whole content of the view, and a
 * sighted researcher reads it off the picture in a second.
 *
 * It replaces the comparator's seam proportion while the difference is showing, for the reason the
 * handset gives for the same substitution: a proportion is meaningless when there is one picture
 * rather than two laid over each other.
 */
export const COMPARISON_DIFFERENCE_ALT =
  "The traced drawing and the photograph subtracted from each other. Dark where they agree, bright " +
  "where they differ.";

/**
 * The word on the picture while the difference is showing.
 *
 * ONE BADGE, WHERE THE WIPE HAS TWO. The other two name a layer each and are clipped to the half of
 * the frame that layer occupies; this one names the whole frame, so it is not clipped at all. The
 * reason a chip row does not already cover it: a difference plate of a good trace is very nearly
 * black, and a nearly black frame is indistinguishable from a plate that failed to draw. The badge is
 * what separates "this worked and they agree" from "nothing rendered".
 */
export const COMPARISON_DIFFERENCE_BADGE = "Difference";

/** The third plate, or a sentence saying why there is not one. */
export type DifferenceOutcome = { readonly plate: Blob } | ComparisonRefusal;

export function isDifference(outcome: DifferenceOutcome): outcome is { readonly plate: Blob } {
  return "plate" in outcome;
}

/**
 * The two layers subtracted from each other, one RGBA plane in and one out.
 *
 * ── THE DEFINITION, WRITTEN DOWN BECAUSE TWO CLIENTS HAVE TO SHARE IT ─────────────────────────
 *
 * **ABSOLUTE DIFFERENCE PER CHANNEL.** Red, green and blue are each subtracted independently and the
 * sign is dropped; alpha is forced opaque. It is NOT a luminance difference, and that is a decision
 * rather than a convenience: a luminance difference needs a set of weights, there are at least two
 * standard sets in common use, and the day the two clients picked different ones the difference plate
 * would disagree between a laptop and a handset with nothing on either screen to say which was right.
 * An absolute per-channel difference has exactly one definition, needs no colour-space opinion, and is
 * what every image editor's "difference" blend already means — so a researcher who has met one has
 * met this.
 *
 * The handset implements the identical arithmetic against this same paragraph, and the mode is named
 * "Difference" on both clients.
 *
 * ── WHY ALPHA IS FORCED RATHER THAN SUBTRACTED ────────────────────────────────────────────────
 *
 * Both plates are opaque by construction — the trace plate is painted on white (decision 2 above) and
 * the photograph is a decode. A subtracted alpha would therefore be zero everywhere, which is an
 * invisible picture rather than a black one, and the failure would look exactly like a plate that
 * never got built.
 *
 * ── WHAT IT SHOWS ─────────────────────────────────────────────────────────────────────────────
 *
 * A stroke the trace reproduced is dark in the photograph and black on the plate, so it comes out near
 * black: agreement reads as nothing. A stroke the trace MISSED is dark in the photograph and white on
 * the plate, and a stroke it INVENTED is the reverse; both come out bright. The paper's own tone
 * becomes an even dim grey, because paper is not quite white and the plate is.
 *
 * @returns a new plane the length of the SHORTER of the two, so a caller that hands over mismatched
 *   planes gets a short answer rather than reading off the end of one of them. The one caller here
 *   draws both plates to one agreed size first.
 */
export function differenceRgba(photograph: Uint8ClampedArray, trace: Uint8ClampedArray): Uint8ClampedArray {
  const length = Math.min(photograph.length, trace.length);
  const out = new Uint8ClampedArray(length);
  for (let i = 0; i + 3 < length; i += 4) {
    out[i] = Math.abs(photograph[i] - trace[i]);
    out[i + 1] = Math.abs(photograph[i + 1] - trace[i + 1]);
    out[i + 2] = Math.abs(photograph[i + 2] - trace[i + 2]);
    out[i + 3] = 255;
  }
  return out;
}

/**
 * Decode one plate blob back into pixels at a known size.
 *
 * WHY THE PLATES ARE RE-DECODED RATHER THAN THEIR PLANES KEPT. Holding both planes would pin two
 * whole RGBA planes of `COMPARISON_LONG_EDGE_PX` for as long as the comparator is on screen, per
 * trace, for a view most researchers never open — the same argument that made that cap 1024 rather
 * than 4096. Two decodes on one button press is the cheaper side of that trade, and it is paid once:
 * the caller keeps the blob that comes back.
 */
async function planeOf(blob: Blob, width: number, height: number): Promise<Uint8ClampedArray | null> {
  if (typeof createImageBitmap !== "function") return null;
  const canvas = createCanvas(width, height);
  if (canvas === null) return null;
  const context = canvas.getContext("2d") as
    | CanvasRenderingContext2D
    | OffscreenCanvasRenderingContext2D
    | null;
  if (context === null) return null;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(blob);
  } catch {
    return null;
  }
  try {
    context.drawImage(bitmap, 0, 0, width, height);
  } finally {
    // A decoded bitmap holds its pixels until it is closed or collected, and there are two of them per
    // press. `decodeToPixels`'s header records that three copies of one big buffer is how a 2 GB
    // handset kills the page.
    bitmap.close();
  }
  try {
    return context.getImageData(0, 0, width, height).data;
  } catch {
    // A surface that will not be read back. Not reachable from a blob this page made itself, and
    // answered rather than thrown because the caller's only sensible response is the refusal sentence.
    return null;
  }
}

/**
 * The third plate, from the two the comparator is already holding.
 *
 * BUILT ON THE PRESS AND NOT WITH THE OTHER TWO — see the file header. The caller owns the blob and,
 * as everywhere else in this file, no object URL is created here: a URL is a thing that has to be
 * revoked and only the component knows when it left the screen.
 *
 * @param width the plates' agreed width — {@link ComparisonPlates.width}. Both blobs are drawn to it,
 *   so a plate that somehow came back at another size is scaled to the frame rather than offset inside
 *   it, which would subtract a drawing from a shifted copy of itself and light up every edge in it.
 */
export async function buildDifferencePlate(
  photograph: Blob,
  trace: Blob,
  width: number,
  height: number
): Promise<DifferenceOutcome> {
  const w = Math.max(1, Math.round(width));
  const h = Math.max(1, Math.round(height));
  const photographPlane = await planeOf(photograph, w, h);
  // SEQUENTIAL, NOT `Promise.all`. Two decodes of a plate this size held at once is exactly the two
  // simultaneous big buffers the cap above exists to avoid, and the first one's refusal makes the
  // second one's work pointless anyway.
  const tracePlane = photographPlane === null ? null : await planeOf(trace, w, h);
  if (photographPlane === null || tracePlane === null) {
    return { reason: COMPARISON_DIFFERENCE_REFUSAL };
  }

  const canvas = createCanvas(w, h);
  const context =
    canvas === null
      ? null
      : (canvas.getContext("2d") as CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D | null);
  if (canvas === null || context === null) return { reason: COMPARISON_DIFFERENCE_REFUSAL };

  const plane = differenceRgba(photographPlane, tracePlane);
  // `context.createImageData` rather than `new ImageData(...)`, for the reason `buildComparisonPlates`
  // gives at its own `putImageData`: the constructor's typed-array parameter needs a cast, and the cast
  // would be over the one thing worth checking.
  const image = context.createImageData(w, h);
  image.data.set(plane.subarray(0, Math.min(plane.length, image.data.length)));
  context.putImageData(image, 0, 0);

  const plate = await canvasToBlob(canvas);
  if (plate === null) return { reason: COMPARISON_DIFFERENCE_REFUSAL };
  return { plate };
}
