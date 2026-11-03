import { unsharpMask } from "./engine/contrast";
import { GrayF } from "./engine/buffers";

export interface EditablePixels {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
}

export interface CropRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export const CROP_MIN_EDGE_PX = 16;

export function wholeFrame(width: number, height: number): CropRect {
  return { x: 0, y: 0, width: Math.max(1, Math.round(width)), height: Math.max(1, Math.round(height)) };
}

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

export function isWholeFrame(rect: CropRect, width: number, height: number): boolean {
  const whole = wholeFrame(width, height);
  return rect.x === 0 && rect.y === 0 && rect.width === whole.width && rect.height === whole.height;
}

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

export interface SharpenSettings {
  readonly amount: number;
  readonly radius: number;
  readonly threshold: number;
}

export const NO_SHARPEN: SharpenSettings = { amount: 0, radius: 1.5, threshold: 0 };

export const SHARPEN_AMOUNT_MIN = 0;
export const SHARPEN_AMOUNT_MAX = 5;
export const SHARPEN_RADIUS_MIN = 0.3;
export const SHARPEN_RADIUS_MAX = 8;
export const SHARPEN_THRESHOLD_MAX = 0.2;

export const SHARPEN_MAX_PIXELS = 8_000_000;

export function isSharpenOff(settings: SharpenSettings): boolean {
  return !(settings.amount > 0);
}

export function sharpenKernelTaps(radius: number): number {
  if (!(radius > 0.05)) return 3;
  return 2 * Math.max(1, Math.ceil(3 * radius)) + 1;
}

export interface SharpenPlan {
  readonly pixels: number;
  readonly megapixels: number;
  readonly taps: number;
  readonly multiplyAdds: number;
  readonly peakBytes: number;
  readonly refusal: string | null;
}

export function planSharpen(pixels: number, radius: number): SharpenPlan {
  const n = Math.max(0, Math.floor(pixels));
  const taps = sharpenKernelTaps(radius);
  const megapixels = n / 1_000_000;
  const refusal =
    n > SHARPEN_MAX_PIXELS
      ? `Too large to sharpen (${megapixels.toFixed(1)} of ${(SHARPEN_MAX_PIXELS / 1_000_000).toFixed(1)} MP max). Crop it first, or use “Sharpen amount”.`
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

export function sharpenPixels(src: EditablePixels, settings: SharpenSettings): EditablePixels {
  const n = src.width * src.height;
  if (isSharpenOff(settings) || n === 0) {
    return { data: src.data.slice(), width: src.width, height: src.height };
  }
  const grey = lumaPlane(src);
  const sharp = unsharpMask(grey, settings.radius, settings.amount, settings.threshold);
  const out = new Uint8ClampedArray(n * 4);
  const from = src.data;
  const a = grey.data;
  const b = sharp.data;
  for (let i = 0, j = 0; i < n; i += 1, j += 4) {
    const delta = (b[i] - a[i]) * 255;
    out[j] = from[j] + delta;
    out[j + 1] = from[j + 1] + delta;
    out[j + 2] = from[j + 2] + delta;
    out[j + 3] = from[j + 3];
  }
  return { data: out, width: src.width, height: src.height };
}

export interface EditRequest {
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
}

export function applyEdit(src: EditablePixels, request: EditRequest): EditablePixels {
  const cropped = cropPixels(src, request.crop);
  if (isSharpenOff(request.sharpen)) return cropped;
  return sharpenPixels(cropped, request.sharpen);
}

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
