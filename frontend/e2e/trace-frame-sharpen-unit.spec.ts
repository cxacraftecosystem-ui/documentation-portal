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

/**
 * The crop and the sharpen, as arithmetic — no browser, no worker, no React.
 *
 * WHY A `-unit` SPEC AND NOT A RENDERED ONE. `lib/trace/imageEdit.ts` was deliberately written with no
 * DOM, no `import.meta` and no `File` in it, precisely so a file like this can call it directly: a
 * `-unit` spec needs no services, and the arithmetic is the half that is worth checking on every push.
 * `e2e/trace-frame-panel.spec.ts` renders the panel that drives it, in a real browser, with the worker
 * stood in for.
 *
 * THIS MODULE IS VENDORED (`lib/trace/UPSTREAM-MANIFEST.txt` — it is byte-identical to the designer
 * portal's copy) and nothing in this repository exercised it until the frame chooser was ported. It is
 * not decorative coverage: four of these cases hold a duplication in place that could not be avoided,
 * and every one of the four is a place where two implementations of one thing could quietly diverge.
 *
 *  · `lumaPlane` reproduces `engine/color.toGray` rather than calling it, because `toGray` takes an
 *    `RgbaImage` whose backing store is packed ARGB and building one would cost a full extra pass and
 *    4 bytes per pixel on the device with the least memory. The luminance the trace sees and the
 *    luminance the sharpen lifts have to be the SAME plane, so the parity case compares the two
 *    implementations sample by sample.
 *  · `sharpenKernelTaps` reproduces `engine/convolve.gaussianKernel`'s length rule, because the panel
 *    prints the tap count in a sentence before any engine module has been fetched — importing
 *    `convolve` to read a number would put the engine on the page's bundle, which `TracePanel.tsx`'s
 *    third property forbids. The case builds the real kernel and counts it.
 *  · `FramePanel.tsx` re-declares six bounds as literals for the same bundle reason. The last two
 *    cases read that file's source and check the numbers still agree.
 *
 * Run it with: `cd frontend && npx playwright test trace-frame-sharpen --reporter=line`
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures
 * ──────────────────────────────────────────────────────────────────────────── */

/** RGBA where every channel is a function of (x, y), so a mis-indexed read is visible rather than plausible. */
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

/** A vertical step: black on the left half, white on the right. The classic thing to sharpen. */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The crop
 * ──────────────────────────────────────────────────────────────────────────── */

test("clampCrop keeps a box inside the frame, whole-numbered, and no smaller than the minimum", () => {
  // Fractional in, integer out. A fractional origin reaches `cropPixels` as a fractional row offset
  // and silently truncates, which reads as a one-pixel drift nobody traces back to a rounding rule.
  expect(clampCrop({ x: 3.4, y: 9.6, width: 40.2, height: 30.8 }, 100, 100)).toEqual({
    x: 3,
    y: 10,
    width: 40,
    height: 31
  });

  // Pushed off the far edge: the SIZE is honoured and the ORIGIN gives way, which is the order that
  // makes a drag past the edge stop rather than shrink.
  expect(clampCrop({ x: 90, y: 90, width: 40, height: 40 }, 100, 100)).toEqual({
    x: 60,
    y: 60,
    width: 40,
    height: 40
  });

  // Larger than the frame in both axes: the box becomes the frame, not a box hanging off it.
  expect(clampCrop({ x: -50, y: -50, width: 500, height: 500 }, 100, 80)).toEqual({
    x: 0,
    y: 0,
    width: 100,
    height: 80
  });

  // Below the minimum edge on both axes.
  const tiny = clampCrop({ x: 10, y: 10, width: 1, height: 0 }, 100, 100);
  expect(tiny.width).toBe(CROP_MIN_EDGE_PX);
  expect(tiny.height).toBe(CROP_MIN_EDGE_PX);

  // A frame SMALLER than the minimum is not made bigger than itself — the minimum stands down.
  expect(clampCrop({ x: 0, y: 0, width: 1, height: 1 }, 8, 4)).toEqual({ x: 0, y: 0, width: 8, height: 4 });

  // NaN and Infinity are the values a `<input type="number">` produces mid-edit, so they are answered
  // rather than propagated: an unguarded NaN reaches `new Uint8ClampedArray(NaN * 4)` as length 0.
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
  // AT OR ABOVE `CROP_MIN_EDGE_PX` ON BOTH EDGES. A smaller box is legal to ASK for and `clampCrop`
  // grows it, which is the right behaviour and the wrong fixture: the case would then be checking the
  // minimum rather than the copy.
  const box = { x: 3, y: 2, width: 20, height: 17 };
  const cropped = cropPixels(source, box);

  expect(cropped.width).toBe(20);
  expect(cropped.height).toBe(17);
  expect(cropped.data.length).toBe(20 * 17 * 4);

  // Every pixel, against the source's own coordinates. A row-offset error of one shows up here and
  // nowhere else — a photograph cropped one row out looks completely normal.
  for (let y = 0; y < box.height; y += 1) {
    for (let x = 0; x < box.width; x += 1) {
      expect(pixelAt(cropped, x, y)).toEqual(pixelAt(source, box.x + x, box.y + y));
    }
  }

  // THE WHOLE FRAME STILL COPIES. `TracePanel` keeps the decoded photograph so a frame can be widened
  // back out, and returning the caller's own array from the identity case would hand the worker a
  // buffer it then transfers — detaching the page's copy of the photograph.
  const identity = cropPixels(source, wholeFrame(64, 48));
  expect(identity.data).not.toBe(source.data);
  expect(Array.from(identity.data)).toEqual(Array.from(source.data));
});

/* ────────────────────────────────────────────────────────────────────────────
 * The luminance parity — the duplication this file exists to hold in place
 * ──────────────────────────────────────────────────────────────────────────── */

test("lumaPlane is the same plane engine/color.toGray produces, sample for sample", () => {
  const source = ramp(23, 17);
  const mine = lumaPlane(source);
  const theirs = toGray(RgbaImage.fromImageData({ data: source.data, width: source.width, height: source.height }));

  expect(mine.width).toBe(theirs.width);
  expect(mine.height).toBe(theirs.height);
  // EXACTLY EQUAL, not close. Both compute `(0.299r + 0.587g + 0.114b) * (1/255)` in that order and
  // store to float32, so any difference at all means the multiplication order drifted — and a
  // tolerance here would let exactly that through while claiming the two agree.
  for (let i = 0; i < mine.data.length; i += 1) {
    expect(mine.data[i]).toBe(theirs.data[i]);
  }
});

test("sharpenKernelTaps counts the kernel engine/convolve actually builds", () => {
  for (const radius of [0, 0.04, 0.05, 0.3, 1, 1.5, 2.7, 8, 32]) {
    expect(sharpenKernelTaps(radius)).toBe(gaussianKernel(radius).length);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * The sharpen
 * ──────────────────────────────────────────────────────────────────────────── */

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

  // An unsharp mask makes the dark side of an edge darker and the light side lighter. That IS the
  // filter — a case that only asserted "something changed" would pass on a blur.
  expect(darkAfter).toBeLessThan(darkBefore);
  expect(lightAfter).toBeGreaterThan(lightBefore);

  // Far from the edge nothing happens: the blur and the original agree there, so the difference is 0.
  expect(pixelAt(out, 1, y)[0]).toBe(pixelAt(source, 1, y)[0]);
  expect(pixelAt(out, 30, y)[0]).toBe(pixelAt(source, 30, y)[0]);

  // ALPHA IS COPIED, NEVER SHARPENED. Adding an edge signal to alpha puts a halo of partial
  // transparency around every stroke, visible only once something is composited behind it.
  for (let i = 3; i < out.data.length; i += 4) {
    expect(out.data[i]).toBe(source.data[i]);
  }
});

test("the delta is applied equally to all three channels, so nothing changes hue", () => {
  // A coloured step, so a per-channel sharpen would pull the channels apart and this would fail.
  const width = 24;
  const height = 4;
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const i = (y * width + x) * 4;
      const dark = x < width / 2;
      // Well clear of 0 and 255 on both sides: an unsharp mask overshoots at an edge by design, and a
      // channel that clips is a channel whose delta is no longer the shared one. That is correct
      // behaviour and a useless fixture.
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
    // Not clipped anywhere in this fixture, so the three deltas must be identical integers.
    const deltas = [after[0] - before[0], after[1] - before[1], after[2] - before[2]];
    expect(deltas[1]).toBe(deltas[0]);
    expect(deltas[2]).toBe(deltas[0]);
  }
});

test("the threshold leaves small differences alone", () => {
  // A very shallow ramp: every neighbouring difference is tiny, so a threshold above them all must
  // reproduce the input exactly while a threshold of 0 must not.
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

/* ────────────────────────────────────────────────────────────────────────────
 * The cap, and the order of the two operations
 * ──────────────────────────────────────────────────────────────────────────── */

test("planSharpen refuses over the cap, names the number, and points at the crop", () => {
  const inside = planSharpen(SHARPEN_MAX_PIXELS, 1.5);
  expect(inside.refusal).toBeNull();
  expect(inside.taps).toBe(sharpenKernelTaps(1.5));
  expect(inside.peakBytes).toBe(SHARPEN_MAX_PIXELS * 20);

  const over = planSharpen(SHARPEN_MAX_PIXELS + 1, 1.5);
  expect(over.refusal).not.toBeNull();
  // The sentence has to carry the cap AND the way through, or it is a dead end wearing an explanation.
  expect(over.refusal).toContain("8.0 megapixels");
  expect(over.refusal).toContain("Crop it first");
});

test("applyEdit crops first, so the sharpen runs on the smaller frame", () => {
  const source = ramp(40, 30);
  const crop = { x: 5, y: 4, width: 20, height: 16 };
  const out = applyEdit(source, { crop, sharpen: { amount: 1, radius: 1, threshold: 0 } });
  expect(out.width).toBe(20);
  expect(out.height).toBe(16);
  expect(out.data.length).toBe(20 * 16 * 4);

  // And with the sharpen off it is exactly the crop — the same bytes `cropPixels` would give.
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
  // THE PROMISE THE WHOLE FEATURE RESTS ON, written into the file that leaves this device.
  expect(both).toContain("The original photograph is unchanged.");
});

test("the crop clause is character-for-character the handset's, because both land in one archive", () => {
  /*
    `TraceCrop.traceCropNote` builds the same sentence in Kotlin and its own header explains why it
    keeps a lower-case `x` between the two numbers against that client's house style: "a drawing traced
    on a handset and a drawing traced in the portal land in the same submission, and a reviewer holding
    one of each should not have to work out whether two phrasings of 'cropped' mean two different
    operations."

    So the shape is pinned here, on the web side, in the one place a change to it would be noticed.
  */
  const note = describeEdit({ crop: { x: 12, y: 34, width: 200, height: 150 }, sharpen: NO_SHARPEN }, 400, 300);
  expect(note).toBe("Cropped on the device to 200x150 at (12, 34) of 400x300.");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The bounds FramePanel re-declares
 * ──────────────────────────────────────────────────────────────────────────── */

const FRAME_PANEL = join(__dirname, "..", "components", "trace", "FramePanel.tsx");

test("FramePanel's copied bounds still match the module they came from", () => {
  /*
    WHY THE NUMBERS ARE COPIED AT ALL. `FramePanel.tsx` needs these bounds in order to draw its sliders
    and to state its own cap, and `lib/trace/imageEdit.ts` imports `engine/contrast` and
    `engine/convolve` — so importing it from a component would put the convolution code on the bundle
    of every page that mounts the tracer, for every visitor who never touches an image.
    `TracePanel.tsx`'s third property forbids exactly that, in as many words: "Do not add a top-level
    import from `@/lib/trace/*` to this file."

    So the numbers are literals, and this case is what stops them drifting. Reading the source rather
    than importing the component is deliberate: the component is a React module and this spec runs in
    Node with no renderer.
  */
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
  /*
    `traceParamTable.ts` already surfaces `preprocess.unsharpAmount` and `preprocess.unsharpSigma` as
    "Sharpen amount" and "Sharpen radius". This panel's sliders are the same two names over the same
    units applied one stage earlier, and the amount's ceiling is read off the engine's own range
    (`params.ts`: 0..5). A panel whose "amount 2" meant something different from the group below it
    would be the worst of both.
  */
  expect(SHARPEN_AMOUNT_MAX).toBe(5);
  expect(SHARPEN_RADIUS_MIN).toBeGreaterThan(0.05);
  expect(SHARPEN_RADIUS_MAX).toBeLessThanOrEqual(32);
  expect(NO_SHARPEN.amount).toBe(0);
  expect(NO_SHARPEN.radius).toBe(1.5);
  // The off state the panel opens in is the module's own, so "nothing has been applied" means one
  // thing on both sides of the worker boundary.
  expect(isSharpenOff(NO_SHARPEN)).toBe(true);
});
