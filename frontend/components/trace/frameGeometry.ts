import type { CropRect } from "./imageEditRuntime";

export interface FrameSize {
  readonly width: number;
  readonly height: number;
}

export type FrameCorner = "nw" | "ne" | "sw" | "se";

function clampNumber(value: number, low: number, high: number): number {
  if (!Number.isFinite(value)) return low;
  if (high < low) return low;
  return Math.min(high, Math.max(low, value));
}

export function clampCropRect(rect: CropRect, frame: FrameSize, minEdge: number): CropRect {
  const frameW = Math.max(1, Math.floor(frame.width));
  const frameH = Math.max(1, Math.floor(frame.height));
  const minW = Math.min(minEdge, frameW);
  const minH = Math.min(minEdge, frameH);

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

export function wholeCropRect(frame: FrameSize): CropRect {
  return {
    x: 0,
    y: 0,
    width: Math.max(1, Math.round(frame.width)),
    height: Math.max(1, Math.round(frame.height))
  };
}

export function isWholeCropRect(rect: CropRect, frame: FrameSize): boolean {
  const whole = wholeCropRect(frame);
  return rect.x === 0 && rect.y === 0 && rect.width === whole.width && rect.height === whole.height;
}

export function moveCropCorner(
  base: CropRect,
  corner: FrameCorner,
  x: number,
  y: number,
  frame: FrameSize,
  minEdge: number
): CropRect {
  const frameW = Math.max(1, Math.floor(frame.width));
  const frameH = Math.max(1, Math.floor(frame.height));
  const start = clampCropRect(base, frame, minEdge);
  const minW = Math.min(minEdge, frameW);
  const minH = Math.min(minEdge, frameH);

  let left = start.x;
  let top = start.y;
  let right = start.x + start.width;
  let bottom = start.y + start.height;

  const wantX = Math.round(Number.isFinite(x) ? x : 0);
  const wantY = Math.round(Number.isFinite(y) ? y : 0);

  if (corner === "nw" || corner === "sw") left = clampNumber(wantX, 0, right - minW);
  else right = clampNumber(wantX, left + minW, frameW);

  if (corner === "nw" || corner === "ne") top = clampNumber(wantY, 0, bottom - minH);
  else bottom = clampNumber(wantY, top + minH, frameH);

  return { x: left, y: top, width: right - left, height: bottom - top };
}

export function moveCropBy(
  base: CropRect,
  dx: number,
  dy: number,
  frame: FrameSize,
  minEdge: number
): CropRect {
  const start = clampCropRect(base, frame, minEdge);
  return clampCropRect({ ...start, x: start.x + dx, y: start.y + dy }, frame, minEdge);
}

export function cropRectFromPoints(
  ax: number,
  ay: number,
  bx: number,
  by: number,
  frame: FrameSize,
  minEdge: number
): CropRect {
  const frameW = Math.max(1, Math.floor(frame.width));
  const frameH = Math.max(1, Math.floor(frame.height));
  const x0 = clampNumber(Math.round(Math.min(ax, bx)), 0, frameW);
  const x1 = clampNumber(Math.round(Math.max(ax, bx)), 0, frameW);
  const y0 = clampNumber(Math.round(Math.min(ay, by)), 0, frameH);
  const y1 = clampNumber(Math.round(Math.max(ay, by)), 0, frameH);
  return clampCropRect({ x: x0, y: y0, width: x1 - x0, height: y1 - y0 }, frame, minEdge);
}

export function sourcePerDisplayPixel(sourceEdge: number, displayedEdge: number): number {
  if (!Number.isFinite(sourceEdge) || !Number.isFinite(displayedEdge) || displayedEdge <= 0) return 1;
  return Math.max(1, sourceEdge / displayedEdge);
}

export function nudgeStepFor(sourceEdge: number, displayedEdge: number): number {
  return Math.max(1, Math.round(sourcePerDisplayPixel(sourceEdge, displayedEdge)));
}

export interface FramePreset {
  readonly id: string;
  readonly label: string;
  readonly hint: string;
  readonly rect: (frame: FrameSize) => CropRect;
}

export const FRAME_PRESETS: readonly FramePreset[] = [
  {
    id: "whole",
    label: "The whole photograph",
    hint: "Everything the camera saw.",
    rect: (frame) => wholeCropRect(frame)
  },
  {
    id: "middle-two-thirds",
    label: "Middle two-thirds",
    hint: "Drops the border of table, hands and floor a phone photograph usually has.",
    rect: (frame) => {
      const w = Math.round(frame.width * (2 / 3));
      const h = Math.round(frame.height * (2 / 3));
      return { x: Math.round((frame.width - w) / 2), y: Math.round((frame.height - h) / 2), width: w, height: h };
    }
  },
  {
    id: "middle-square",
    label: "Middle square",
    hint: "The largest square in the centre — a sheet shot square-on from above.",
    rect: (frame) => {
      const side = Math.min(Math.round(frame.width), Math.round(frame.height));
      return {
        x: Math.round((frame.width - side) / 2),
        y: Math.round((frame.height - side) / 2),
        width: side,
        height: side
      };
    }
  },
  {
    id: "top-half",
    label: "Top half",
    hint: "For two drawings on one sheet, or a sheet with notes below it.",
    rect: (frame) => ({ x: 0, y: 0, width: Math.round(frame.width), height: Math.round(frame.height / 2) })
  },
  {
    id: "bottom-half",
    label: "Bottom half",
    hint: "The other half of the same sheet.",
    rect: (frame) => {
      const h = Math.round(frame.height / 2);
      return { x: 0, y: Math.round(frame.height) - h, width: Math.round(frame.width), height: h };
    }
  },
  {
    id: "left-half",
    label: "Left half",
    hint: "For a spread photographed open, or two sheets side by side.",
    rect: (frame) => ({ x: 0, y: 0, width: Math.round(frame.width / 2), height: Math.round(frame.height) })
  },
  {
    id: "right-half",
    label: "Right half",
    hint: "The other page of the same spread.",
    rect: (frame) => {
      const w = Math.round(frame.width / 2);
      return { x: Math.round(frame.width) - w, y: 0, width: w, height: Math.round(frame.height) };
    }
  }
];

export function presetCropRect(preset: FramePreset, frame: FrameSize, minEdge: number): CropRect {
  return clampCropRect(preset.rect(frame), frame, minEdge);
}

export function matchingPresetId(rect: CropRect, frame: FrameSize, minEdge: number): string | null {
  for (const preset of FRAME_PRESETS) {
    const candidate = presetCropRect(preset, frame, minEdge);
    if (
      candidate.x === rect.x &&
      candidate.y === rect.y &&
      candidate.width === rect.width &&
      candidate.height === rect.height
    ) {
      return preset.id;
    }
  }
  return null;
}
