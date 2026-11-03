import { UNNAMED_SOURCE_STEM } from "./geometryToSvg";

export const DECODE_MAX_EDGE_PX = 4096;

export interface DecodedPixels {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly sourceWidth: number;
  readonly sourceHeight: number;
  readonly decodeMs: number;
}

export interface DecodeRefusal {
  readonly reason: string;
}

export type DecodeOutcome = DecodedPixels | DecodeRefusal;

export function isDecoded(outcome: DecodeOutcome): outcome is DecodedPixels {
  return "data" in outcome;
}

export const TRACEABLE_IMAGE_TYPES = "JPEG, PNG, WebP, GIF, BMP, HEIC and AVIF";

export const TRACEABLE_ACCEPT = "image/*";

function readPixels(bitmap: ImageBitmap, width: number, height: number): Uint8ClampedArray | null {
  if (typeof OffscreenCanvas !== "undefined") {
    const canvas = new OffscreenCanvas(width, height);
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) return null;
    context.drawImage(bitmap, 0, 0, width, height);
    return context.getImageData(0, 0, width, height).data;
  }
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) return null;
  context.drawImage(bitmap, 0, 0, width, height);
  return context.getImageData(0, 0, width, height).data;
}

export function workingSizeFor(
  width: number,
  height: number,
  maxEdge: number = DECODE_MAX_EDGE_PX
): { width: number; height: number } {
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  if (scale >= 1) return { width, height };
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
}

export async function decodeToPixels(
  file: Blob,
  maxEdge: number = DECODE_MAX_EDGE_PX
): Promise<DecodeOutcome> {
  if (typeof createImageBitmap === "undefined") {
    return {
      reason:
        "This browser cannot decode images in the page, so a drawing cannot be traced here. " +
        "The image itself is unaffected — attach it as it is, or trace it on a handset."
    };
  }

  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  let full: ImageBitmap | null = null;
  let scaled: ImageBitmap | null = null;
  try {
    full = await createImageBitmap(file);
    const sourceWidth = full.width;
    const sourceHeight = full.height;
    if (sourceWidth < 1 || sourceHeight < 1) {
      return { reason: "That file decoded to an empty image. Try another photograph." };
    }

    const working = workingSizeFor(sourceWidth, sourceHeight, maxEdge);
    scaled =
      working.width === sourceWidth && working.height === sourceHeight
        ? null
        : await createImageBitmap(full, { resizeWidth: working.width, resizeHeight: working.height });

    const data = readPixels(scaled ?? full, working.width, working.height);
    if (data === null) {
      return {
        reason:
          "This browser would not give the page a drawing surface to read the image back from, " +
          "so it cannot be traced here."
      };
    }

    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    return {
      data,
      width: working.width,
      height: working.height,
      sourceWidth,
      sourceHeight,
      decodeMs: now - startedAt
    };
  } catch {
    return {
      reason:
        `This browser could not read that image. It reads ${TRACEABLE_IMAGE_TYPES} where the ` +
        "platform supports them; an image in another format can still be attached as it is."
    };
  } finally {
    full?.close();
    scaled?.close();
  }
}

export async function fetchImageBlob(url: string): Promise<Blob | DecodeRefusal> {
  let response: Response;
  try {
    response = await fetch(url);
  } catch {
    return {
      reason: "The image couldn’t be loaded. Check your connection and try again."
    };
  }
  if (!response.ok) {
    return {
      reason: "The image couldn’t be loaded. Reload the record and try again."
    };
  }
  return await response.blob();
}

export function nameFromUrl(url: string): string {
  let path = url;
  try {
    path = new URL(url, "http://localhost").pathname;
  } catch {
    path = url.split(/[?#]/)[0] ?? url;
  }
  const segment = path.split("/").filter(Boolean).pop() ?? "";
  const decoded = (() => {
    try {
      return decodeURIComponent(segment);
    } catch {
      return segment;
    }
  })();
  return decoded.trim().length > 0 ? decoded : UNNAMED_SOURCE_STEM;
}
