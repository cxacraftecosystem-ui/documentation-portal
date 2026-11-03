import { workingSizeFor } from "./decodeToPixels";
import type { DecodedPixels } from "./decodeToPixels";
import { canvasToBlob, createCanvas, paintGeometry } from "./traceExport";
import type { SvgInput } from "./geometryToSvg";

export const COMPARISON_LONG_EDGE_PX = 1024;

export interface ComparisonPlates {
  readonly trace: Blob;
  readonly original: Blob;
  readonly width: number;
  readonly height: number;
  readonly reduced: boolean;
}

export interface ComparisonRefusal {
  readonly reason: string;
}

export type ComparisonOutcome = ComparisonPlates | ComparisonRefusal;

export function isComparable(outcome: ComparisonOutcome): outcome is ComparisonPlates {
  return "trace" in outcome;
}

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

export const BAND_SOURCE_PIXELS = 1_000_000;

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

  const perRow = Math.max(1, Math.round((sourceWidth * sourceHeight) / plan.height));
  const rowsPerBand = Math.max(1, Math.floor(BAND_SOURCE_PIXELS / perRow));

  for (let y = 0; y < plan.height; y += rowsPerBand) {
    if (shouldStop?.()) return null;
    fillBand(plan, y, Math.min(plan.height, y + rowsPerBand));
    if (y + rowsPerBand < plan.height) await new Promise((resolve) => setTimeout(resolve, 0));
  }
  return plan.out;
}

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

function fillBand(plan: Extract<ResamplePlan, { done: false }>, from: number, to: number): void {
  const { out, source, sourceWidth, sourceHeight, width, height } = plan;
  for (let y = from; y < to; y += 1) {
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

export async function buildComparisonPlates(
  pixels: DecodedPixels,
  input: SvgInput,
  options: {
    longEdge?: number;
    shouldStop?: () => boolean;
  } = {}
): Promise<ComparisonOutcome> {
  const longEdge = options.longEdge ?? COMPARISON_LONG_EDGE_PX;
  const traceWidth = Number.isFinite(input.width) && input.width > 0 ? Math.round(input.width) : 0;
  const traceHeight = Number.isFinite(input.height) && input.height > 0 ? Math.round(input.height) : 0;
  if (traceWidth < 1 || traceHeight < 1 || pixels.width < 1 || pixels.height < 1) {
    return { reason: "There is nothing to compare yet." };
  }

  if (Math.abs(traceWidth - pixels.width) > 1 || Math.abs(traceHeight - pixels.height) > 1) {
    return {
      reason:
        `Sizes differ (${traceWidth}x${traceHeight} vs ${pixels.width}x${pixels.height}), so they can’t be compared.`
    };
  }

  const box = workingSizeFor(traceWidth, traceHeight, Math.max(1, Math.round(longEdge)));
  const reduced = box.width < traceWidth || box.height < traceHeight;

  const traceCanvas = createCanvas(box.width, box.height);
  const originalCanvas = createCanvas(box.width, box.height);
  if (traceCanvas === null || originalCanvas === null) {
    return { reason: "This browser can’t draw the comparison." };
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
    return { reason: "This browser can’t draw the comparison." };
  }

  traceContext.fillStyle = "#ffffff";
  traceContext.fillRect(0, 0, box.width, box.height);
  paintGeometry(traceContext, input, box.width / traceWidth);

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
  if (small === null) return { reason: "The comparison was replaced by a newer one." };
  const plate = originalContext.createImageData(plateWidth, plateHeight);
  plate.data.set(small);
  originalContext.putImageData(plate, 0, 0);

  const [trace, original] = await Promise.all([canvasToBlob(traceCanvas), canvasToBlob(originalCanvas)]);
  if (trace === null || original === null) {
    return {
      reason:
        "Couldn’t draw the comparison — the image may be too large."
    };
  }

  return { trace, original, width: box.width, height: box.height, reduced };
}

export interface ComparisonStatusInput {
  readonly haveCompare: boolean;
  readonly compareProblem: string | null;
  readonly tracing: boolean;
  readonly progress: string | null;
  readonly traceProblem: string | null;
  readonly haveResult: boolean;
}

export function comparisonStatusFor(input: ComparisonStatusInput): string {
  if (input.haveCompare) return input.tracing ? "Updating…" : "";
  if (input.compareProblem !== null) return input.compareProblem;
  if (input.tracing) return input.progress ?? "Tracing…";
  if (input.traceProblem !== null) {
    return "The trace didn’t finish — see the reason above.";
  }
  if (!input.haveResult) return "The comparison appears as soon as the first trace finishes.";
  return "Preparing the comparison…";
}

export const COMPARISON_DIFFERENCE_NOTE =
  "Black where they match, bright where they differ.";

export const COMPARISON_DIFFERENCE_REFUSAL =
  "This browser could not make room for the difference picture. The wipe and the two whole pictures " +
  "still work, and the drawing is unaffected.";

export const COMPARISON_DIFFERENCE_PENDING = "Working out the difference picture…";

export const COMPARISON_DIFFERENCE_ALT =
  "The traced drawing and the photograph subtracted from each other. Dark where they agree, bright " +
  "where they differ.";

export const COMPARISON_DIFFERENCE_BADGE = "Difference";

export type DifferenceOutcome = { readonly plate: Blob } | ComparisonRefusal;

export function isDifference(outcome: DifferenceOutcome): outcome is { readonly plate: Blob } {
  return "plate" in outcome;
}

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
    bitmap.close();
  }
  try {
    return context.getImageData(0, 0, width, height).data;
  } catch {
    return null;
  }
}

export async function buildDifferencePlate(
  photograph: Blob,
  trace: Blob,
  width: number,
  height: number
): Promise<DifferenceOutcome> {
  const w = Math.max(1, Math.round(width));
  const h = Math.max(1, Math.round(height));
  const photographPlane = await planeOf(photograph, w, h);
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
  const image = context.createImageData(w, h);
  image.data.set(plane.subarray(0, Math.min(plane.length, image.data.length)));
  context.putImageData(image, 0, 0);

  const plate = await canvasToBlob(canvas);
  if (plate === null) return { reason: COMPARISON_DIFFERENCE_REFUSAL };
  return { plate };
}
