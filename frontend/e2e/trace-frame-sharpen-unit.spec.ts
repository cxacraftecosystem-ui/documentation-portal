import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { gaussianKernel } from "@/lib/trace/engine/convolve";
import { RgbaImage } from "@/lib/trace/engine/buffers";
import { toGray } from "@/lib/trace/engine/color";
import {
  CROP_MIN_EDGE_PX,
  NO_SHARPEN,
  SHARPEN_AMOUNT_MAX,
  SHARPEN_MAX_PIXELS,
  SHARPEN_RADIUS_MAX,
  SHARPEN_RADIUS_MIN,
  SHARPEN_THRESHOLD_MAX,
  applyEdit,
  clampCrop,
  cropPixels,
  describeEdit,
  isSharpenOff,
  isWholeFrame,
  lumaPlane,
  planSharpen,
  sharpenKernelTaps,
  sharpenPixels,
  wholeFrame,
  type EditablePixels
} from "@/lib/trace/imageEdit";

function ramp(width: number, height: number): EditablePixels {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const i = (y * width + x) * 4;
      data[i] = (x * 7) % 256;
      data[i + 1] = (y * 11) % 256;
      data[i + 2] = (x * y) % 256;
      data[i + 3] = 200 + ((x + y) % 56);
    }
  }
  return { data, width, height };
}

function step(width: number, height: number): EditablePixels {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const i = (y * width + x) * 4;
      const v = x < width / 2 ? 40 : 215;
      data[i] = v;
      data[i + 1] = v;
      data[i + 2] = v;
      data[i + 3] = 255;
    }
  }
  return { data, width, height };
}

function pixelAt(image: EditablePixels, x: number, y: number): [number, number, number, number] {
  const i = (y * image.width + x) * 4;
  return [image.data[i], image.data[i + 1], image.data[i + 2], image.data[i + 3]];
}

test("clampCrop keeps a box inside the frame, whole-numbered, and no smaller than the minimum", () => {
  expect(clampCrop({ x: 3.4, y: 9.6, width: 40.2, height: 30.8 }, 100, 100)).toEqual({
    x: 3,
    y: 10,
    width: 40,
    height: 31
  });

  expect(clampCrop({ x: 90, y: 90, width: 40, height: 40 }, 100, 100)).toEqual({
    x: 60,
    y: 60,
    width: 40,
    height: 40
  });

  expect(clampCrop({ x: -50, y: -50, width: 500, height: 500 }, 100, 80)).toEqual({
    x: 0,
    y: 0,
    width: 100,
    height: 80
  });

  const tiny = clampCrop({ x: 10, y: 10, width: 1, height: 0 }, 100, 100);
  expect(tiny.width).toBe(CROP_MIN_EDGE_PX);
  expect(tiny.height).toBe(CROP_MIN_EDGE_PX);

  expect(clampCrop({ x: 0, y: 0, width: 1, height: 1 }, 8, 4)).toEqual({ x: 0, y: 0, width: 8, height: 4 });

  const broken = clampCrop({ x: Number.NaN, y: Number.POSITIVE_INFINITY, width: Number.NaN, height: 20 }, 60, 60);
  expect(Number.isInteger(broken.x)).toBe(true);
  expect(Number.isInteger(broken.y)).toBe(true);
  expect(broken.width).toBe(60);
  expect(broken.height).toBe(20);
});

test("wholeFrame and isWholeFrame agree about what 'no crop' is", () => {
  expect(isWholeFrame(wholeFrame(37, 21), 37, 21)).toBe(true);
  expect(isWholeFrame({ x: 1, y: 0, width: 36, height: 21 }, 37, 21)).toBe(false);
  expect(isWholeFrame({ x: 0, y: 0, width: 37, height: 20 }, 37, 21)).toBe(false);
});

test("cropPixels copies exactly the region asked for, and never aliases the source", () => {
  const source = ramp(64, 48);
  const box = { x: 3, y: 2, width: 20, height: 17 };
  const cropped = cropPixels(source, box);

  expect(cropped.width).toBe(20);
  expect(cropped.height).toBe(17);
  expect(cropped.data.length).toBe(20 * 17 * 4);

  for (let y = 0; y < box.height; y += 1) {
    for (let x = 0; x < box.width; x += 1) {
      expect(pixelAt(cropped, x, y)).toEqual(pixelAt(source, box.x + x, box.y + y));
    }
  }

  const identity = cropPixels(source, wholeFrame(64, 48));
  expect(identity.data).not.toBe(source.data);
  expect(Array.from(identity.data)).toEqual(Array.from(source.data));
});

test("lumaPlane is the same plane engine/color.toGray produces, sample for sample", () => {
  const source = ramp(23, 17);
  const mine = lumaPlane(source);
  const theirs = toGray(RgbaImage.fromImageData({ data: source.data, width: source.width, height: source.height }));

  expect(mine.width).toBe(theirs.width);
  expect(mine.height).toBe(theirs.height);
  for (let i = 0; i < mine.data.length; i += 1) {
    expect(mine.data[i]).toBe(theirs.data[i]);
  }
});

test("sharpenKernelTaps counts the kernel engine/convolve actually builds", () => {
  for (const radius of [0, 0.04, 0.05, 0.3, 1, 1.5, 2.7, 8, 32]) {
    expect(sharpenKernelTaps(radius)).toBe(gaussianKernel(radius).length);
  }
});

test("amount 0 is off, and off is a copy rather than the same buffer", () => {
  expect(isSharpenOff(NO_SHARPEN)).toBe(true);
  expect(isSharpenOff({ amount: 0.01, radius: 1, threshold: 0 })).toBe(false);

  const source = ramp(9, 7);
  const out = sharpenPixels(source, NO_SHARPEN);
  expect(out.data).not.toBe(source.data);
  expect(Array.from(out.data)).toEqual(Array.from(source.data));
});

test("a step edge gains overshoot on both sides, and the alpha channel is untouched", () => {
  const source = step(32, 8);
  const out = sharpenPixels(source, { amount: 1.5, radius: 1.5, threshold: 0 });

  const y = 4;
  const darkBefore = pixelAt(source, 14, y)[0];
  const darkAfter = pixelAt(out, 14, y)[0];
  const lightBefore = pixelAt(source, 17, y)[0];
  const lightAfter = pixelAt(out, 17, y)[0];

  expect(darkAfter).toBeLessThan(darkBefore);
  expect(lightAfter).toBeGreaterThan(lightBefore);

  expect(pixelAt(out, 1, y)[0]).toBe(pixelAt(source, 1, y)[0]);
  expect(pixelAt(out, 30, y)[0]).toBe(pixelAt(source, 30, y)[0]);

  for (let i = 3; i < out.data.length; i += 4) {
    expect(out.data[i]).toBe(source.data[i]);
  }
});

test("the delta is applied equally to all three channels, so nothing changes hue", () => {
  const width = 24;
  const height = 4;
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const i = (y * width + x) * 4;
      const dark = x < width / 2;
      data[i] = dark ? 70 : 150;
      data[i + 1] = dark ? 85 : 165;
      data[i + 2] = dark ? 100 : 180;
      data[i + 3] = 255;
    }
  }
  const source: EditablePixels = { data, width, height };
  const out = sharpenPixels(source, { amount: 0.8, radius: 1.2, threshold: 0 });

  for (let x = 0; x < width; x += 1) {
    const before = pixelAt(source, x, 2);
    const after = pixelAt(out, x, 2);
    const deltas = [after[0] - before[0], after[1] - before[1], after[2] - before[2]];
    expect(deltas[1]).toBe(deltas[0]);
    expect(deltas[2]).toBe(deltas[0]);
  }
});

test("the threshold leaves small differences alone", () => {
  const width = 16;
  const data = new Uint8ClampedArray(width * 4);
  for (let x = 0; x < width; x += 1) {
    const i = x * 4;
    data[i] = 100 + x;
    data[i + 1] = 100 + x;
    data[i + 2] = 100 + x;
    data[i + 3] = 255;
  }
  const source: EditablePixels = { data, width, height: 1 };

  const gated = sharpenPixels(source, { amount: 3, radius: 2, threshold: 0.2 });
  expect(Array.from(gated.data)).toEqual(Array.from(source.data));

  const ungated = sharpenPixels(source, { amount: 3, radius: 2, threshold: 0 });
  expect(Array.from(ungated.data)).not.toEqual(Array.from(source.data));
});

test("planSharpen refuses over the cap, names the number, and points at the crop", () => {
  const inside = planSharpen(SHARPEN_MAX_PIXELS, 1.5);
  expect(inside.refusal).toBeNull();
  expect(inside.taps).toBe(sharpenKernelTaps(1.5));
  expect(inside.peakBytes).toBe(SHARPEN_MAX_PIXELS * 20);

  const over = planSharpen(SHARPEN_MAX_PIXELS + 1, 1.5);
  expect(over.refusal).not.toBeNull();
  expect(over.refusal).toContain("8.0 MP max");
  expect(over.refusal).toContain("Crop it first");
});

test("applyEdit crops first, so the sharpen runs on the smaller frame", () => {
  const source = ramp(40, 30);
  const crop = { x: 5, y: 4, width: 20, height: 16 };
  const out = applyEdit(source, { crop, sharpen: { amount: 1, radius: 1, threshold: 0 } });
  expect(out.width).toBe(20);
  expect(out.height).toBe(16);
  expect(out.data.length).toBe(20 * 16 * 4);

  const plain = applyEdit(source, { crop, sharpen: NO_SHARPEN });
  expect(Array.from(plain.data)).toEqual(Array.from(cropPixels(source, crop).data));
});

test("describeEdit says only what was done, and says the original is unchanged", () => {
  expect(describeEdit({ crop: wholeFrame(100, 80), sharpen: NO_SHARPEN }, 100, 80)).toBe("");

  const cropOnly = describeEdit({ crop: { x: 4, y: 6, width: 50, height: 40 }, sharpen: NO_SHARPEN }, 100, 80);
  expect(cropOnly).toContain("50x40 at (4, 6) of 100x80");
  expect(cropOnly).not.toContain("unsharp");

  const both = describeEdit(
    { crop: { x: 0, y: 0, width: 60, height: 80 }, sharpen: { amount: 1.25, radius: 2, threshold: 0.05 } },
    100,
    80
  );
  expect(both).toContain("60x80");
  expect(both).toContain("amount 1.25");
  expect(both).toContain("radius 2px");
  expect(both).toContain("threshold 0.05");
  expect(both).toContain("The original photograph is unchanged.");
});

test("the crop clause is character-for-character the handset's, because both land in one archive", () => {
  const note = describeEdit({ crop: { x: 12, y: 34, width: 200, height: 150 }, sharpen: NO_SHARPEN }, 400, 300);
  expect(note).toBe("Cropped on the device to 200x150 at (12, 34) of 400x300.");
});

const FRAME_PANEL = join(__dirname, "..", "components", "trace", "FramePanel.tsx");

test("FramePanel's copied bounds still match the module they came from", () => {
  const source = readFileSync(FRAME_PANEL, "utf8").replace(/\r\n/g, "\n");
  const literal = (name: string): number => {
    const match = new RegExp(`const ${name} = ([0-9_.]+);`).exec(source);
    expect(match, `FramePanel.tsx no longer declares ${name}`).not.toBeNull();
    return Number((match as RegExpExecArray)[1].replace(/_/g, ""));
  };

  expect(literal("SHARPEN_AMOUNT_MAX")).toBe(SHARPEN_AMOUNT_MAX);
  expect(literal("SHARPEN_RADIUS_MIN")).toBe(SHARPEN_RADIUS_MIN);
  expect(literal("SHARPEN_RADIUS_MAX")).toBe(SHARPEN_RADIUS_MAX);
  expect(literal("SHARPEN_THRESHOLD_MAX")).toBe(SHARPEN_THRESHOLD_MAX);
  expect(literal("SHARPEN_MAX_PIXELS")).toBe(SHARPEN_MAX_PIXELS);
  expect(literal("CROP_MIN_EDGE_PX")).toBe(CROP_MIN_EDGE_PX);
});

test("the sharpening bounds are the engine's own, so two controls called 'sharpen' cannot disagree", () => {
  expect(SHARPEN_AMOUNT_MAX).toBe(5);
  expect(SHARPEN_RADIUS_MIN).toBeGreaterThan(0.05);
  expect(SHARPEN_RADIUS_MAX).toBeLessThanOrEqual(32);
  expect(NO_SHARPEN.amount).toBe(0);
  expect(NO_SHARPEN.radius).toBe(1.5);
  expect(isSharpenOff(NO_SHARPEN)).toBe(true);
});
