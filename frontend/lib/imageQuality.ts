import type { MediaFile } from "@/lib/types";

export type GreyPlane = {
  data: Uint8ClampedArray;
  width: number;
  height: number;
};

export const WORK_EDGE_PX = 640;

export const MIN_CONTRAST_STDDEV = 12;

export const BLUR_VARIANCE_FLOOR = 60;

export const MIN_LONG_EDGE_PX = 1280;

export const NEAR_DUPLICATE_MAX_DISTANCE = 6;

export function toGreyPlane(rgba: Uint8ClampedArray, width: number, height: number): GreyPlane {
  const data = new Uint8ClampedArray(width * height);
  for (let index = 0; index < data.length; index += 1) {
    const offset = index * 4;
    data[index] = (rgba[offset] * 299 + rgba[offset + 1] * 587 + rgba[offset + 2] * 114) / 1000;
  }
  return { data, width, height };
}

export function resampleGrey(plane: GreyPlane, width: number, height: number): GreyPlane {
  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y += 1) {
    const sourceTop = Math.floor((y * plane.height) / height);
    const sourceBottom = Math.max(sourceTop + 1, Math.floor(((y + 1) * plane.height) / height));
    for (let x = 0; x < width; x += 1) {
      const sourceLeft = Math.floor((x * plane.width) / width);
      const sourceRight = Math.max(sourceLeft + 1, Math.floor(((x + 1) * plane.width) / width));
      let total = 0;
      let count = 0;
      for (let sy = sourceTop; sy < sourceBottom; sy += 1) {
        for (let sx = sourceLeft; sx < sourceRight; sx += 1) {
          total += plane.data[sy * plane.width + sx];
          count += 1;
        }
      }
      data[y * width + x] = count > 0 ? total / count : 0;
    }
  }
  return { data, width, height };
}

export function contrastStdDev(plane: GreyPlane): number {
  const { data } = plane;
  if (data.length === 0) return 0;
  let sum = 0;
  for (let index = 0; index < data.length; index += 1) sum += data[index];
  const mean = sum / data.length;
  let squares = 0;
  for (let index = 0; index < data.length; index += 1) {
    const delta = data[index] - mean;
    squares += delta * delta;
  }
  return Math.sqrt(squares / data.length);
}

export function laplacianVariance(plane: GreyPlane): number {
  const { data, width, height } = plane;
  if (width < 3 || height < 3) return 0;
  let sum = 0;
  let squares = 0;
  let count = 0;
  for (let y = 1; y < height - 1; y += 1) {
    for (let x = 1; x < width - 1; x += 1) {
      const index = y * width + x;
      const response =
        data[index - width] + data[index + width] + data[index - 1] + data[index + 1] - 4 * data[index];
      sum += response;
      squares += response * response;
      count += 1;
    }
  }
  if (count === 0) return 0;
  const mean = sum / count;
  return squares / count - mean * mean;
}

const HASH_EDGE = 8;

export function differenceHash(plane: GreyPlane): string {
  const small = resampleGrey(plane, HASH_EDGE + 1, HASH_EDGE);
  let hex = "";
  for (let y = 0; y < HASH_EDGE; y += 1) {
    let byte = 0;
    for (let x = 0; x < HASH_EDGE; x += 1) {
      const left = small.data[y * (HASH_EDGE + 1) + x];
      const right = small.data[y * (HASH_EDGE + 1) + x + 1];
      byte = (byte << 1) | (left > right ? 1 : 0);
    }
    hex += byte.toString(16).padStart(2, "0");
  }
  return hex;
}

export function hammingDistance(left: string, right: string): number {
  if (left.length !== right.length) return HASH_EDGE * HASH_EDGE;
  let distance = 0;
  for (let index = 0; index < left.length; index += 1) {
    let diff = parseInt(left[index], 16) ^ parseInt(right[index], 16);
    while (diff) {
      distance += diff & 1;
      diff >>= 1;
    }
  }
  return distance;
}

export type ImageMeasurement = {
  width: number;
  height: number;
  blurScore: number;
  contrast: number;
  perceptualHash: string;
  elapsedMs: number;
};

export function isBlurred(measurement: Pick<ImageMeasurement, "blurScore" | "contrast">): boolean {
  if (measurement.contrast < MIN_CONTRAST_STDDEV) return false;
  return measurement.blurScore < BLUR_VARIANCE_FLOOR;
}

export function isUnderResolution(measurement: Pick<ImageMeasurement, "width" | "height">): boolean {
  return Math.max(measurement.width, measurement.height) < MIN_LONG_EDGE_PX;
}

export type QualityFlag = "BLUR" | "LOW_RESOLUTION" | "DUPLICATE" | "MISSING_VIEW";

export type QualitySeverity = "LOW" | "MEDIUM" | "HIGH";

export type QualityFinding = {
  flag: QualityFlag;
  severity: QualitySeverity;
  message: string;
};

export type AttachedImage = {
  label: string;
  checksum?: string | null;
  perceptualHash?: string | null;
};

export function attachedImageFromMedia(media: Pick<MediaFile, "originalFilename"> & { checksum?: string | null }): AttachedImage {
  return { label: media.originalFilename, checksum: media.checksum ?? null };
}

function plural(count: number, one: string, many: string): string {
  return count === 1 ? one : many;
}

export function findQualityIssues({
  measurement,
  checksum,
  attached
}: {
  measurement: ImageMeasurement;
  checksum?: string | null;
  attached?: AttachedImage[];
}): QualityFinding[] {
  const findings: QualityFinding[] = [];

  if (isBlurred(measurement)) {
    findings.push({
      flag: "BLUR",
      severity: "MEDIUM",
      message:
        `Sharpness score ${Math.round(measurement.blurScore)} — a sharp photograph normally scores ` +
        `well above ${BLUR_VARIANCE_FLOOR} here. This one may be out of focus. If it is meant to be ` +
        `soft, or it is the only shot you have, keep it.`
    });
  }

  if (isUnderResolution(measurement)) {
    findings.push({
      flag: "LOW_RESOLUTION",
      severity: "MEDIUM",
      message:
        `${measurement.width}x${measurement.height} — a catalogue plate needs about ` +
        `${MIN_LONG_EDGE_PX}px on the long edge to print sharply at A4. This will still be saved; it ` +
        `will simply print soft.`
    });
  }

  const candidates = attached ?? [];

  const identical = checksum ? candidates.filter((item) => item.checksum && item.checksum === checksum) : [];
  if (identical.length) {
    findings.push({
      flag: "DUPLICATE",
      severity: "LOW",
      message:
        `The same file is already attached here as ${identical.map((item) => `"${item.label}"`).join(", ")} ` +
        `— identical content, so this looks like the same import twice.`
    });
  } else {
    const near = candidates
      .map((item) => ({
        item,
        distance:
          item.perceptualHash && measurement.perceptualHash
            ? hammingDistance(measurement.perceptualHash, item.perceptualHash)
            : Number.POSITIVE_INFINITY
      }))
      .filter((entry) => entry.distance <= NEAR_DUPLICATE_MAX_DISTANCE)
      .sort((left, right) => left.distance - right.distance);
    if (near.length) {
      findings.push({
        flag: "DUPLICATE",
        severity: "LOW",
        message:
          `This looks like the same shot as ${near.map((entry) => `"${entry.item.label}"`).join(", ")}. ` +
          `Two views of one object are worth keeping; two copies of one view are not.`
      });
    }
  }

  return findings;
}

export const NAMED_VIEW_SLOTS: Record<string, Record<string, string>> = {
  existingProduct: { viewFront: "Front view", viewBack: "Back view", viewDetail: "Detail view" }
};

export function findMissingViews(entityKey: string, filled: Record<string, unknown>): QualityFinding[] {
  const slots = NAMED_VIEW_SLOTS[entityKey];
  if (!slots) return [];
  const keys = Object.keys(slots);
  const present = keys.filter((key) => {
    const value = filled[key];
    return Array.isArray(value) ? value.length > 0 : Boolean(value);
  });
  if (present.length === 0 || present.length === keys.length) return [];
  const missing = keys.filter((key) => !present.includes(key));
  return [
    {
      flag: "MISSING_VIEW",
      severity: "LOW",
      message:
        `${present.length} of ${keys.length} views captured — no ${missing
          .map((key) => slots[key].toLowerCase())
          .join(", no ")}. ${plural(missing.length, "That view is", "Those views are")} easiest to add now, ` +
        `while the product is still in front of you.`
    }
  ];
}

export function isMeasurableImage(file: File): boolean {
  return file.type.startsWith("image/") && file.type !== "image/svg+xml";
}

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

export async function measureImageFile(file: File): Promise<ImageMeasurement | null> {
  if (typeof createImageBitmap === "undefined" || !isMeasurableImage(file)) return null;
  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  let full: ImageBitmap | null = null;
  let scaled: ImageBitmap | null = null;
  try {
    full = await createImageBitmap(file);
    const width = full.width;
    const height = full.height;
    if (width < 1 || height < 1) return null;

    const scale = Math.min(1, WORK_EDGE_PX / Math.max(width, height));
    const workWidth = Math.max(1, Math.round(width * scale));
    const workHeight = Math.max(1, Math.round(height * scale));
    scaled =
      scale < 1
        ? await createImageBitmap(full, {
            resizeWidth: workWidth,
            resizeHeight: workHeight,
            resizeQuality: "high"
          })
        : full;
    if (scaled !== full) {
      full.close();
      full = null;
    }

    const rgba = readPixels(scaled, workWidth, workHeight);
    if (!rgba) return null;
    const plane = toGreyPlane(rgba, workWidth, workHeight);
    const elapsedMs = (typeof performance !== "undefined" ? performance.now() : Date.now()) - startedAt;
    return {
      width,
      height,
      blurScore: laplacianVariance(plane),
      contrast: contrastStdDev(plane),
      perceptualHash: differenceHash(plane),
      elapsedMs
    };
  } catch {
    return null;
  } finally {
    full?.close();
    if (scaled && scaled !== full) scaled.close();
  }
}
