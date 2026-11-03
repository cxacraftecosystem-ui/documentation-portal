import { toGreyPlane, type GreyPlane } from "@/lib/imageQuality";
import { decodeQrFromGrey, type QrDecodeRefusal, type QrDecodeResult, type QrRegion } from "@/lib/qrDecode";

export type QrImageRefusal =
  | QrDecodeRefusal
  | "FILE_UNREADABLE"
  | "NO_IMAGE_SUPPORT";

export type QrImageResult =
  | { ok: true; text: string; via: "native" | "bundled" }
  | { ok: false; reason: QrImageRefusal; message: string };

const DETECT_EDGE_PX = 1400;

const DEEP_EDGE_PX = 2800;

const RECUT_EDGE_PX = 1200;

const DEEP_PASS_MIN_SOURCE_PX = DETECT_EDGE_PX * 1.5;

function canvasFor(width: number, height: number): OffscreenCanvas | HTMLCanvasElement | null {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

function drawToPixels(
  source: CanvasImageSource,
  sx: number,
  sy: number,
  sw: number,
  sh: number,
  dw: number,
  dh: number
): Uint8ClampedArray | null {
  const canvas = canvasFor(dw, dh);
  if (!canvas) return null;
  const context = canvas.getContext("2d", { willReadFrequently: true }) as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D
    | null;
  if (!context) return null;
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = "high";
  try {
    context.drawImage(source, sx, sy, sw, sh, 0, 0, dw, dh);
    return context.getImageData(0, 0, dw, dh).data;
  } catch {
    return null;
  }
}

type Crop = { x: number; y: number; width: number; height: number };

async function planeFrom(source: ImageBitmap | HTMLVideoElement, crop: Crop | null, maxEdge: number): Promise<GreyPlane | null> {
  const sourceWidth = "videoWidth" in source ? source.videoWidth : source.width;
  const sourceHeight = "videoHeight" in source ? source.videoHeight : source.height;
  const sx = Math.max(0, Math.floor(crop?.x ?? 0));
  const sy = Math.max(0, Math.floor(crop?.y ?? 0));
  const sw = Math.max(1, Math.min(sourceWidth - sx, Math.ceil(crop?.width ?? sourceWidth)));
  const sh = Math.max(1, Math.min(sourceHeight - sy, Math.ceil(crop?.height ?? sourceHeight)));
  if (sw < 1 || sh < 1) return null;

  const scale = Math.min(1, maxEdge / Math.max(sw, sh));
  const width = Math.max(1, Math.round(sw * scale));
  const height = Math.max(1, Math.round(sh * scale));

  let resized: ImageBitmap | null = null;
  if (scale < 1 && typeof createImageBitmap === "function") {
    try {
      resized = await createImageBitmap(source, sx, sy, sw, sh, {
        resizeWidth: width,
        resizeHeight: height,
        resizeQuality: "high"
      });
    } catch {
      resized = null;
    }
  }

  try {
    const rgba = resized
      ? drawToPixels(resized, 0, 0, resized.width, resized.height, width, height)
      : drawToPixels(source, sx, sy, sw, sh, width, height);
    return rgba ? toGreyPlane(rgba, width, height) : null;
  } finally {
    resized?.close();
  }
}

type BarcodeDetectorLike = { detect(source: CanvasImageSource): Promise<{ rawValue: string }[]> };
type BarcodeDetectorConstructor = {
  new (options?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats?: () => Promise<string[]>;
};

let nativeProbe: Promise<BarcodeDetectorLike | null> | null = null;

export function nativeQrDetector(): Promise<BarcodeDetectorLike | null> {
  if (nativeProbe) return nativeProbe;
  nativeProbe = (async () => {
    const constructor = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector;
    if (!constructor) return null;
    try {
      const formats = await constructor.getSupportedFormats?.();
      if (formats && !formats.includes("qr_code")) return null;
      return new constructor({ formats: ["qr_code"] });
    } catch {
      return null;
    }
  })();
  return nativeProbe;
}

async function tryNative(source: CanvasImageSource): Promise<string | null> {
  const detector = await nativeQrDetector();
  if (!detector) return null;
  try {
    const found = await detector.detect(source);
    return found.find((code) => code.rawValue)?.rawValue ?? null;
  } catch {
    return null;
  }
}

function fromDecode(result: Extract<QrDecodeResult, { ok: false }>): Extract<QrImageResult, { ok: false }> {
  return { ok: false, reason: result.reason, message: result.message };
}

function unreadableFile(name: string | undefined): Extract<QrImageResult, { ok: false }> {
  return {
    ok: false,
    reason: "FILE_UNREADABLE",
    message:
      `This browser could not open ${name ? `“${name}”` : "that file"}. Photographs from an iPhone are often HEIC, ` +
      "which most browsers cannot read — send it as JPEG or PNG, take a screenshot of it and upload that, or type " +
      "the code printed under the QR."
  };
}

const NO_IMAGE_SUPPORT: Extract<QrImageResult, { ok: false }> = {
  ok: false,
  reason: "NO_IMAGE_SUPPORT",
  message:
    "This browser cannot read pictures on this page, so a photograph of a code cannot be decoded here. Type the code " +
    "printed under the QR instead — it opens exactly the same record."
};

function cropFromRegion(region: QrRegion, sourceWidth: number, sourceHeight: number): Crop {
  const padX = region.width * sourceWidth * 0.1;
  const padY = region.height * sourceHeight * 0.1;
  const left = Math.max(0, region.x * sourceWidth - padX);
  const top = Math.max(0, region.y * sourceHeight - padY);
  const right = Math.min(sourceWidth, (region.x + region.width) * sourceWidth + padX);
  const bottom = Math.min(sourceHeight, (region.y + region.height) * sourceHeight + padY);
  return { x: left, y: top, width: right - left, height: bottom - top };
}

export async function decodeQrFromFile(file: Blob, filename?: string): Promise<QrImageResult> {
  if (typeof createImageBitmap !== "function") return NO_IMAGE_SUPPORT;

  let bitmap: ImageBitmap | null = null;
  try {
    try {
      bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
      return unreadableFile(filename);
    }
    if (bitmap.width < 1 || bitmap.height < 1) return unreadableFile(filename);

    const native = await tryNative(bitmap);
    if (native) return { ok: true, text: native, via: "native" };

    return await decodeBitmapWithLadder(bitmap);
  } finally {
    bitmap?.close();
  }
}

async function decodeBitmapWithLadder(bitmap: ImageBitmap): Promise<QrImageResult> {
  const sourceLongEdge = Math.max(bitmap.width, bitmap.height);

  const whole = await planeFrom(bitmap, null, DETECT_EDGE_PX);
  if (!whole) return NO_IMAGE_SUPPORT;
  const first = decodeQrFromGrey(whole);
  if (first.ok) return { ok: true, text: first.text, via: "bundled" };

  if (first.region) {
    const closer = await planeFrom(bitmap, cropFromRegion(first.region, bitmap.width, bitmap.height), RECUT_EDGE_PX);
    if (closer) {
      const second = decodeQrFromGrey(closer);
      if (second.ok) return { ok: true, text: second.text, via: "bundled" };
    }
  }

  if (!first.region && sourceLongEdge > DEEP_PASS_MIN_SOURCE_PX) {
    const deep = await planeFrom(bitmap, null, DEEP_EDGE_PX);
    if (deep) {
      const third = decodeQrFromGrey(deep);
      if (third.ok) return { ok: true, text: third.text, via: "bundled" };
      if (third.region) {
        const closer = await planeFrom(bitmap, cropFromRegion(third.region, bitmap.width, bitmap.height), RECUT_EDGE_PX);
        if (closer) {
          const fourth = decodeQrFromGrey(closer);
          if (fourth.ok) return { ok: true, text: fourth.text, via: "bundled" };
        }
      }
    }
  }

  return fromDecode(first);
}

export async function decodeQrFromVideoFrame(video: HTMLVideoElement): Promise<QrImageResult | null> {
  if (video.readyState < 2 || video.videoWidth < 1 || video.videoHeight < 1) return null;

  const native = await tryNative(video);
  if (native) return { ok: true, text: native, via: "native" };

  const plane = await planeFrom(video, null, DETECT_EDGE_PX);
  if (!plane) return null;
  const result = decodeQrFromGrey(plane);
  return result.ok ? { ok: true, text: result.text, via: "bundled" } : fromDecode(result);
}
