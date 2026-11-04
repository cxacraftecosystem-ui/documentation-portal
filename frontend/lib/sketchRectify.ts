import { type GreyPlane, contrastStdDev, toGreyPlane, resampleGrey } from "@/lib/imageQuality";
import { type Homography, type Point, applyHomography, solveHomography } from "@/lib/photoMeasure";

export const RECTIFY_MAX_EDGE_PX = 1600;

export const GUESS_EDGE_PX = 480;

export const SAUVOLA_K = 0.2;

export const SAUVOLA_R = 128;

export const SAUVOLA_WINDOW_FRACTION = 1 / 24;

export const SAUVOLA_MIN_WINDOW = 15;

export const OUTSIDE_VALUE = 255;

export const GUESS_MIN_FILL = 0.85;

export const GUESS_MIN_FRAME_SHARE = 0.15;

export const GUESS_MIN_EDGE_SUPPORT = 0.85;

export const GUESS_MIN_CONTRAST = 12;

export type Corners = readonly [Point, Point, Point, Point];

export type RectifyRefusal = { ok: false; reason: string };

function finite(...values: number[]): boolean {
  return values.every((value) => Number.isFinite(value));
}

export function orderCorners(points: readonly Point[]): Corners | null {
  if (points.length !== 4) return null;
  for (const point of points) if (!finite(point.x, point.y)) return null;

  const centreX = (points[0].x + points[1].x + points[2].x + points[3].x) / 4;
  const centreY = (points[0].y + points[1].y + points[2].y + points[3].y) / 4;

  const ring = [...points].sort(
    (left, right) => Math.atan2(left.y - centreY, left.x - centreX) - Math.atan2(right.y - centreY, right.x - centreX)
  );

  let start = 0;
  for (let index = 1; index < 4; index += 1) {
    if (ring[index].x + ring[index].y < ring[start].x + ring[start].y) start = index;
  }
  return [ring[start % 4], ring[(start + 1) % 4], ring[(start + 2) % 4], ring[(start + 3) % 4]];
}

export function rectifiedSize(
  corners: Corners,
  options?: { maxEdge?: number; aspect?: number }
): { width: number; height: number } | null {
  const maxEdge = options?.maxEdge ?? RECTIFY_MAX_EDGE_PX;
  if (!Number.isFinite(maxEdge) || maxEdge < 1) return null;
  const [topLeft, topRight, bottomRight, bottomLeft] = corners;
  for (const point of corners) if (!finite(point.x, point.y)) return null;

  const top = Math.hypot(topRight.x - topLeft.x, topRight.y - topLeft.y);
  const bottom = Math.hypot(bottomRight.x - bottomLeft.x, bottomRight.y - bottomLeft.y);
  const left = Math.hypot(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y);
  const right = Math.hypot(bottomRight.x - topRight.x, bottomRight.y - topRight.y);

  let width = Math.max(top, bottom);
  let height = Math.max(left, right);
  if (!(width >= 1) || !(height >= 1)) return null;

  const aspect = options?.aspect;
  if (aspect !== undefined) {
    if (!Number.isFinite(aspect) || aspect <= 0) return null;
    if (width / height > aspect) width = height * aspect;
    else height = width / aspect;
  }

  const scale = Math.min(1, maxEdge / Math.max(width, height));
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
}

export function bilinearSample(plane: GreyPlane, x: number, y: number, outside: number = OUTSIDE_VALUE): number {
  if (!finite(x, y)) return outside;
  if (x < -0.5 || y < -0.5 || x > plane.width - 0.5 || y > plane.height - 0.5) return outside;

  const x0 = Math.floor(x);
  const y0 = Math.floor(y);
  const fx = x - x0;
  const fy = y - y0;
  const left = Math.min(Math.max(x0, 0), plane.width - 1);
  const right = Math.min(Math.max(x0 + 1, 0), plane.width - 1);
  const top = Math.min(Math.max(y0, 0), plane.height - 1);
  const bottom = Math.min(Math.max(y0 + 1, 0), plane.height - 1);

  const topLeft = plane.data[top * plane.width + left];
  const topRight = plane.data[top * plane.width + right];
  const bottomLeft = plane.data[bottom * plane.width + left];
  const bottomRight = plane.data[bottom * plane.width + right];

  const upper = topLeft + (topRight - topLeft) * fx;
  const lower = bottomLeft + (bottomRight - bottomLeft) * fx;
  return upper + (lower - upper) * fy;
}

export function rectifyHomography(corners: Corners, width: number, height: number): Homography | null {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width < 2 || height < 2) return null;
  const destination: Point[] = [
    { x: 0, y: 0 },
    { x: width - 1, y: 0 },
    { x: width - 1, y: height - 1 },
    { x: 0, y: height - 1 }
  ];
  return solveHomography(destination, [...corners]);
}

export function rectifyPlane(plane: GreyPlane, corners: Corners, width: number, height: number): GreyPlane | null {
  const homography = rectifyHomography(corners, width, height);
  if (!homography) return null;

  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y += 1) {
    const rowOffset = y * width;
    for (let x = 0; x < width; x += 1) {
      const source = applyHomography(homography, { x, y });
      data[rowOffset + x] = bilinearSample(plane, source.x, source.y);
    }
  }
  return { data, width, height };
}

export function integralPlanes(plane: GreyPlane): { sum: Float64Array; squares: Float64Array; stride: number } {
  const stride = plane.width + 1;
  const sum = new Float64Array(stride * (plane.height + 1));
  const squares = new Float64Array(stride * (plane.height + 1));
  for (let y = 0; y < plane.height; y += 1) {
    let rowSum = 0;
    let rowSquares = 0;
    const source = y * plane.width;
    const above = y * stride;
    const current = (y + 1) * stride;
    for (let x = 0; x < plane.width; x += 1) {
      const value = plane.data[source + x];
      rowSum += value;
      rowSquares += value * value;
      sum[current + x + 1] = sum[above + x + 1] + rowSum;
      squares[current + x + 1] = squares[above + x + 1] + rowSquares;
    }
  }
  return { sum, squares, stride };
}

export function sauvolaWindow(plane: GreyPlane): number {
  const shortEdge = Math.min(plane.width, plane.height);
  const scaled = Math.round(shortEdge * SAUVOLA_WINDOW_FRACTION);
  const bounded = Math.max(SAUVOLA_MIN_WINDOW, scaled);
  return bounded % 2 === 1 ? bounded : bounded + 1;
}

export function sauvolaThreshold(
  plane: GreyPlane,
  options?: { window?: number; k?: number; r?: number }
): GreyPlane {
  const window = options?.window ?? sauvolaWindow(plane);
  const k = options?.k ?? SAUVOLA_K;
  const r = options?.r ?? SAUVOLA_R;
  const radius = Math.max(1, Math.floor(window / 2));
  const { sum, squares, stride } = integralPlanes(plane);
  const data = new Uint8ClampedArray(plane.width * plane.height);

  for (let y = 0; y < plane.height; y += 1) {
    const top = Math.max(0, y - radius);
    const bottom = Math.min(plane.height - 1, y + radius);
    const topRow = top * stride;
    const bottomRow = (bottom + 1) * stride;
    const rows = bottom - top + 1;
    for (let x = 0; x < plane.width; x += 1) {
      const left = Math.max(0, x - radius);
      const right = Math.min(plane.width - 1, x + radius);
      const count = rows * (right - left + 1);

      const total = sum[bottomRow + right + 1] - sum[bottomRow + left] - sum[topRow + right + 1] + sum[topRow + left];
      const totalSquares =
        squares[bottomRow + right + 1] - squares[bottomRow + left] - squares[topRow + right + 1] + squares[topRow + left];

      const mean = total / count;
      const variance = Math.max(0, totalSquares / count - mean * mean);
      const threshold = mean * (1 + k * (Math.sqrt(variance) / r - 1));
      data[y * plane.width + x] = plane.data[y * plane.width + x] < threshold ? 0 : 255;
    }
  }
  return { data, width: plane.width, height: plane.height };
}

export function otsuThreshold(plane: GreyPlane): number {
  const histogram = new Float64Array(256);
  for (let index = 0; index < plane.data.length; index += 1) histogram[plane.data[index]] += 1;
  const total = plane.data.length;
  if (total === 0) return 128;

  let sumAll = 0;
  for (let level = 0; level < 256; level += 1) sumAll += level * histogram[level];

  let backgroundWeight = 0;
  let backgroundSum = 0;
  let best = 0;
  let bestVariance = -1;
  for (let level = 0; level < 256; level += 1) {
    backgroundWeight += histogram[level];
    if (backgroundWeight === 0) continue;
    const foregroundWeight = total - backgroundWeight;
    if (foregroundWeight === 0) break;
    backgroundSum += level * histogram[level];
    const backgroundMean = backgroundSum / backgroundWeight;
    const foregroundMean = (sumAll - backgroundSum) / foregroundWeight;
    const between = backgroundWeight * foregroundWeight * (backgroundMean - foregroundMean) ** 2;
    if (between > bestVariance) {
      bestVariance = between;
      best = level;
    }
  }
  return best;
}

export function globalThreshold(plane: GreyPlane, level?: number): GreyPlane {
  const cut = level ?? otsuThreshold(plane);
  const data = new Uint8ClampedArray(plane.width * plane.height);
  for (let index = 0; index < data.length; index += 1) data[index] = plane.data[index] <= cut ? 0 : 255;
  return { data, width: plane.width, height: plane.height };
}

export type CornerGuess = {
  corners: Corners;
  fill: number;
  frameShare: number;
  edgeSupport: number;
};

function edgeSupport(
  corners: Corners,
  bright: Uint8Array,
  width: number,
  height: number,
  samples: number = 32
): number {
  const centreX = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) / 4;
  const centreY = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) / 4;
  let weakest = 1;
  for (let edge = 0; edge < 4; edge += 1) {
    const from = corners[edge];
    const to = corners[(edge + 1) % 4];
    let hits = 0;
    let total = 0;
    for (let step = 1; step < samples; step += 1) {
      const t = step / samples;
      const x = from.x + (to.x - from.x) * t;
      const y = from.y + (to.y - from.y) * t;
      const towardX = centreX - x;
      const towardY = centreY - y;
      const length = Math.hypot(towardX, towardY);
      if (length < 1) continue;
      const px = Math.round(x + (towardX / length) * 2);
      const py = Math.round(y + (towardY / length) * 2);
      if (px < 0 || py < 0 || px >= width || py >= height) continue;
      total += 1;
      if (bright[py * width + px] === 1) hits += 1;
    }
    if (total === 0) return 0;
    weakest = Math.min(weakest, hits / total);
  }
  return weakest;
}

function closeBrightMask(mask: Uint8Array, width: number, height: number, radius: number): Uint8Array {
  if (radius < 1) return mask;

  const boxPass = (source: Uint8Array, keep: (sum: number, count: number) => boolean): Uint8Array => {
    const stride = width + 1;
    const table = new Float64Array(stride * (height + 1));
    for (let y = 0; y < height; y += 1) {
      let row = 0;
      for (let x = 0; x < width; x += 1) {
        row += source[y * width + x];
        table[(y + 1) * stride + x + 1] = table[y * stride + x + 1] + row;
      }
    }
    const out = new Uint8Array(source.length);
    for (let y = 0; y < height; y += 1) {
      const top = Math.max(0, y - radius);
      const bottom = Math.min(height - 1, y + radius);
      const topRow = top * stride;
      const bottomRow = (bottom + 1) * stride;
      for (let x = 0; x < width; x += 1) {
        const left = Math.max(0, x - radius);
        const right = Math.min(width - 1, x + radius);
        const sum =
          table[bottomRow + right + 1] - table[bottomRow + left] - table[topRow + right + 1] + table[topRow + left];
        const count = (bottom - top + 1) * (right - left + 1);
        out[y * width + x] = keep(sum, count) ? 1 : 0;
      }
    }
    return out;
  };

  const dilated = boxPass(mask, (sum) => sum > 0);
  return boxPass(dilated, (sum, count) => sum === count);
}

export const GUESS_CLOSE_FRACTION = 1 / 120;

function shoelaceArea(corners: Corners): number {
  let total = 0;
  for (let index = 0; index < 4; index += 1) {
    const current = corners[index];
    const next = corners[(index + 1) % 4];
    total += current.x * next.y - next.x * current.y;
  }
  return Math.abs(total) / 2;
}

export function guessSheetCorners(plane: GreyPlane): CornerGuess | null {
  if (plane.width < 8 || plane.height < 8) return null;

  const scale = Math.min(1, GUESS_EDGE_PX / Math.max(plane.width, plane.height));
  const width = Math.max(4, Math.round(plane.width * scale));
  const height = Math.max(4, Math.round(plane.height * scale));
  const small = scale < 1 ? resampleGrey(plane, width, height) : plane;

  if (contrastStdDev(small) < GUESS_MIN_CONTRAST) return null;

  const cut = otsuThreshold(small);
  const raw = new Uint8Array(small.width * small.height);
  for (let index = 0; index < raw.length; index += 1) raw[index] = small.data[index] > cut ? 1 : 0;
  const bright = closeBrightMask(
    raw,
    small.width,
    small.height,
    Math.max(1, Math.round(Math.min(small.width, small.height) * GUESS_CLOSE_FRACTION))
  );

  const labels = new Int32Array(bright.length).fill(-1);
  const stack: number[] = [];
  let bestArea = 0;
  let bestLabel = -1;
  let label = 0;
  for (let seed = 0; seed < bright.length; seed += 1) {
    if (bright[seed] === 0 || labels[seed] !== -1) continue;
    labels[seed] = label;
    stack.push(seed);
    let area = 0;
    while (stack.length) {
      const index = stack.pop() as number;
      area += 1;
      const x = index % small.width;
      const y = (index - x) / small.width;
      if (x > 0 && bright[index - 1] === 1 && labels[index - 1] === -1) {
        labels[index - 1] = label;
        stack.push(index - 1);
      }
      if (x < small.width - 1 && bright[index + 1] === 1 && labels[index + 1] === -1) {
        labels[index + 1] = label;
        stack.push(index + 1);
      }
      if (y > 0 && bright[index - small.width] === 1 && labels[index - small.width] === -1) {
        labels[index - small.width] = label;
        stack.push(index - small.width);
      }
      if (y < small.height - 1 && bright[index + small.width] === 1 && labels[index + small.width] === -1) {
        labels[index + small.width] = label;
        stack.push(index + small.width);
      }
    }
    if (area > bestArea) {
      bestArea = area;
      bestLabel = label;
    }
    label += 1;
  }
  if (bestLabel === -1 || bestArea < 16) return null;

  let topLeft = { x: 0, y: 0 };
  let topRight = { x: 0, y: 0 };
  let bottomRight = { x: 0, y: 0 };
  let bottomLeft = { x: 0, y: 0 };
  let minSum = Infinity;
  let maxSum = -Infinity;
  let minDiff = Infinity;
  let maxDiff = -Infinity;
  for (let index = 0; index < labels.length; index += 1) {
    if (labels[index] !== bestLabel) continue;
    const x = index % small.width;
    const y = (index - x) / small.width;
    if (x + y < minSum) {
      minSum = x + y;
      topLeft = { x, y };
    }
    if (x + y > maxSum) {
      maxSum = x + y;
      bottomRight = { x, y };
    }
    if (x - y > maxDiff) {
      maxDiff = x - y;
      topRight = { x, y };
    }
    if (x - y < minDiff) {
      minDiff = x - y;
      bottomLeft = { x, y };
    }
  }

  const ordered = orderCorners([topLeft, topRight, bottomRight, bottomLeft]);
  if (!ordered) return null;
  const quadArea = shoelaceArea(ordered);
  if (quadArea <= 0) return null;

  const fill = bestArea / quadArea;
  const frameShare = quadArea / (small.width * small.height);
  const support = edgeSupport(ordered, bright, small.width, small.height);
  if (fill < GUESS_MIN_FILL || frameShare < GUESS_MIN_FRAME_SHARE || support < GUESS_MIN_EDGE_SUPPORT) return null;

  const back = 1 / (scale < 1 ? scale : 1);
  const lift = (point: Point): Point => ({ x: (point.x + 0.5) * back, y: (point.y + 0.5) * back });
  const lifted = orderCorners([lift(ordered[0]), lift(ordered[1]), lift(ordered[2]), lift(ordered[3])]);
  if (!lifted) return null;
  return { corners: lifted, fill, frameShare, edgeSupport: support };
}

export type PlateOptions = {
  aspect?: number;
  maxEdge?: number;
  lineArt?: boolean;
  sauvola?: { window?: number; k?: number; r?: number };
};

export type Plate = {
  ok: true;
  plane: GreyPlane;
  elapsedMs: number;
};

export function makePlate(plane: GreyPlane, corners: Corners, options?: PlateOptions): Plate | RectifyRefusal {
  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  const size = rectifiedSize(corners, { maxEdge: options?.maxEdge, aspect: options?.aspect });
  if (!size) {
    return { ok: false, reason: "Those four marks do not enclose a sheet. Drag the handles to the corners of the paper." };
  }
  const rectified = rectifyPlane(plane, corners, size.width, size.height);
  if (!rectified) {
    return {
      ok: false,
      reason: "Those corners lie in a straight line, so there is no sheet between them. Move one of the handles."
    };
  }
  const plane2 = options?.lineArt === false ? rectified : sauvolaThreshold(rectified, options?.sauvola);
  const elapsedMs = (typeof performance !== "undefined" ? performance.now() : Date.now()) - startedAt;
  return { ok: true, plane: plane2, elapsedMs };
}

export function greyToRgba(plane: GreyPlane): Uint8ClampedArray<ArrayBuffer> {
  const rgba = new Uint8ClampedArray(new ArrayBuffer(plane.width * plane.height * 4));
  for (let index = 0; index < plane.data.length; index += 1) {
    const value = plane.data[index];
    const offset = index * 4;
    rgba[offset] = value;
    rgba[offset + 1] = value;
    rgba[offset + 2] = value;
    rgba[offset + 3] = 255;
  }
  return rgba;
}

function canvasFor(width: number, height: number): OffscreenCanvas | HTMLCanvasElement | null {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

function readPixels(bitmap: ImageBitmap, width: number, height: number): Uint8ClampedArray | null {
  const canvas = canvasFor(width, height);
  if (!canvas) return null;
  const context = canvas.getContext("2d", { willReadFrequently: true }) as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D
    | null;
  if (!context) return null;
  context.drawImage(bitmap, 0, 0, width, height);
  return context.getImageData(0, 0, width, height).data;
}

export async function loadSketchPlane(
  file: Blob,
  maxEdge: number = RECTIFY_MAX_EDGE_PX
): Promise<{ plane: GreyPlane; sourceWidth: number; sourceHeight: number; elapsedMs: number } | null> {
  if (typeof createImageBitmap === "undefined") return null;
  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  let full: ImageBitmap | null = null;
  let scaled: ImageBitmap | null = null;
  try {
    full = await createImageBitmap(file);
    const sourceWidth = full.width;
    const sourceHeight = full.height;
    if (sourceWidth < 1 || sourceHeight < 1) return null;

    const scale = Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight));
    const workWidth = Math.max(1, Math.round(sourceWidth * scale));
    const workHeight = Math.max(1, Math.round(sourceHeight * scale));
    scaled =
      scale < 1
        ? await createImageBitmap(full, { resizeWidth: workWidth, resizeHeight: workHeight, resizeQuality: "high" })
        : full;
    if (scaled !== full) {
      full.close();
      full = null;
    }

    const rgba = readPixels(scaled, workWidth, workHeight);
    if (!rgba) return null;
    return {
      plane: toGreyPlane(rgba, workWidth, workHeight),
      sourceWidth,
      sourceHeight,
      elapsedMs: (typeof performance !== "undefined" ? performance.now() : Date.now()) - startedAt
    };
  } catch {
    return null;
  } finally {
    full?.close();
    if (scaled && scaled !== full) scaled.close();
  }
}

export async function encodePlatePng(plane: GreyPlane): Promise<Blob | null> {
  const canvas = canvasFor(plane.width, plane.height);
  if (!canvas) return null;
  const context = canvas.getContext("2d") as OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D | null;
  if (!context) return null;
  context.putImageData(new ImageData(greyToRgba(plane), plane.width, plane.height), 0, 0);
  if ("convertToBlob" in canvas) return canvas.convertToBlob({ type: "image/png" });
  return new Promise((resolve) => (canvas as HTMLCanvasElement).toBlob((blob) => resolve(blob), "image/png"));
}

export function derivedPlateName(originalName: string, lineArt: boolean): string {
  const stem = originalName.replace(/\.[^.]+$/, "") || "sketch";
  return `${stem}-${lineArt ? "lineart" : "rectified"}.png`;
}

export type RectifyOutcome = {
  file: File;
  plane: GreyPlane;
  decodeMs: number;
  processMs: number;
};

export async function rectifySketchFile(
  file: Blob,
  originalName: string,
  corners: Corners,
  options?: PlateOptions
): Promise<RectifyOutcome | RectifyRefusal> {
  const loaded = await loadSketchPlane(file, options?.maxEdge ?? RECTIFY_MAX_EDGE_PX);
  if (!loaded) {
    return { ok: false, reason: "This browser could not decode that photograph, so it cannot be straightened here." };
  }
  const plate = makePlate(loaded.plane, corners, options);
  if (!plate.ok) return plate;
  const blob = await encodePlatePng(plate.plane);
  if (!blob) return { ok: false, reason: "This browser could not write the straightened image out." };
  const lineArt = options?.lineArt !== false;
  return {
    file: new File([blob], derivedPlateName(originalName, lineArt), { type: "image/png" }),
    plane: plate.plane,
    decodeMs: loaded.elapsedMs,
    processMs: plate.elapsedMs
  };
}
