/**
 * The crop rectangle's arithmetic — no React, no DOM, no engine.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS FILE EXISTS AT ALL, WHICH IS A BUG RATHER THAN A PREFERENCE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Every one of these functions lived as a closure inside the designer portal's `FramePanel.tsx` until
 * 2026-08-28, and the comment above the one that mattered said so in as many words: "WHAT KEEPS THE
 * TWO HONEST IS NOT A TEST OF THIS FUNCTION — it is not exported and this spec suite has no React
 * renderer to reach it through." That is exactly what happened. `moveCorner` had been shipping a
 * defect since the frame tool was written, nothing could see it, and the owner's report of it was
 * three words: the cropping is non-functional.
 *
 * **THE DEFECT, NAMED, BECAUSE THE SENTENCE IS WORTH MORE THAN THE PATCH.** The old `moveCorner` built
 * a rectangle out of the dragged corner and the opposite one and then handed the FINISHED rectangle to
 * a clamp. A clamp is total — it always answers with a legal rectangle — so a corner dragged past the
 * edge of the photograph came back as a rectangle that had been SLID back inside, which moves the
 * corner the researcher is not touching. Measured on 2026-08-28 with the shipped code, on a 1000x800
 * frame with the crop at {100,100,200,200}:
 *
 *   · bottom-right handle dragged ONE pixel past the edge → {99, 99, 901, 701}. The anchored top-left
 *     corner moved. Every further pixel of the drag moves it again.
 *   · bottom-right handle dragged well past the edge → {0, 0, 1000, 800} — **the whole photograph**.
 *     The crop is silently gone, and pulling a corner out to the edge of the sheet is the commonest
 *     gesture this tool has.
 *   · top-left handle dragged past the bottom-right one → {0, 0, 1000, 1000} instead of a 16px box at
 *     the corner that was not moving. Again the whole photograph.
 *
 * So the tool DID work — through the four number boxes, which is the route a rendered panel spec
 * exercises — and did not work through the one route anybody reaches for first. A gesture that ends
 * with the frame back at "the whole photograph" is indistinguishable from a gesture that did nothing.
 *
 * **THE FIX IS ANDROID'S, AND IT IS ALREADY ARGUED THERE — in this repository.**
 * `android/app/src/main/java/com/fieldrepository/app/ui/trace/TraceCrop.kt`'s `traceMoveCorner` carries
 * the rule in its own header — "THE MOVED EDGES ARE CLAMPED, NOT THE FINISHED RECTANGLE" — with the
 * same failure written out, and `TraceCropTest` pins it. {@link moveCropCorner} is that function, in
 * TypeScript, producing the same answers for the same inputs; `e2e/trace-frame-geometry-unit.spec.ts`
 * pins the cases the Kotlin test pins, by the same numbers, so the two clients cannot drift on what a
 * drag means.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS HERE AND NOT IN `lib/trace/imageEdit.ts`, WHICH IS WHERE THE REST OF THE CROP LIVES
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `lib/trace/imageEdit.ts` imports `engine/contrast` and `engine/convolve`. A component that imported
 * it would put the convolution code on the bundle of every page that mounts the tracer, for every
 * visitor who never touches a photograph — `TracePanel.tsx`'s third property forbids it by name ("Do
 * not add a top-level import from `@/lib/trace/*` to this file"), and `FramePanel.tsx` re-declares six
 * numeric bounds as literals for that exact reason. This file is the other half of that seam: **pure
 * numbers, no runtime import of anything at all.** The single `import type` below is erased by
 * TypeScript, so nothing follows it into the bundle.
 *
 * The consequence is that the minimum edge is a PARAMETER here rather than a constant. `FramePanel`
 * keeps its own `CROP_MIN_EDGE_PX = 16` literal — `e2e/trace-frame-sharpen-unit.spec.ts` reads that
 * file's source and checks the literal against `imageEdit.CROP_MIN_EDGE_PX` — and passes it in. So
 * there is still exactly one number, checked against the module that owns it, and this file cannot be
 * the place it drifts.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * ONE COORDINATE SYSTEM: THE DECODED PHOTOGRAPH'S OWN PIXELS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Every rectangle in this file is in the pixels of the frame the tracer will be handed — the decode's
 * own size, at most `DECODE_MAX_EDGE_PX` on its long edge. Not the preview's pixels, which are a tenth
 * of that; not the file's stored pixels, which the engine never sees. `TraceCropPanel.kt`'s header
 * states the same rule for the handset and gives the reason: the four numbers on screen are the four
 * numbers in the exported drawing's provenance sentence, so there is one system to reason about.
 *
 * The DISPLAY is where the second system would come from, and {@link sourcePerDisplayPixel} is the one
 * place it is converted — from a measurement of the element as it is actually drawn, never from the
 * size the preview was asked to be. That distinction is not pedantry: the preview is `max-w-full`, so
 * on a narrow handset it is drawn smaller than it was laid out, and a drag that divided by the
 * laid-out scale would frame a different region from the one under the finger.
 */

// TYPE-ONLY, AND THAT IS LOAD-BEARING. `import type` is erased outright, so naming the crop's shape
// here costs nothing at run time and keeps one declaration of it across the page, the worker and the
// engine. See the header on why a value import from `@/lib/trace/*` may not appear in this directory.
import type { CropRect } from "./imageEditRuntime";

/** The frame a rectangle lives in: the decoded photograph's own pixel size. */
export interface FrameSize {
  readonly width: number;
  readonly height: number;
}

/** Which handle is being dragged. Named by where it sits, which is what its spoken label says. */
export type FrameCorner = "nw" | "ne" | "sw" | "se";

/**
 * Clamp `value` into `[low, high]`, answering `low` when the range is empty.
 *
 * The empty case cannot arise from any call here — {@link moveCropCorner} clamps its start first, so
 * every bound it computes is ordered — but Kotlin's `coerceIn` THROWS on an inverted range and this
 * returns a number, so the two clients would fail differently on the same impossible input. Answering
 * the low bound keeps a future caller inside the frame instead of inside an exception.
 */
function clampNumber(value: number, low: number, high: number): number {
  if (!Number.isFinite(value)) return low;
  if (high < low) return low;
  return Math.min(high, Math.max(low, value));
}

/**
 * Force `rect` to be a usable crop of `frame`, at least `minEdge` on each side.
 *
 * **A LINE-FOR-LINE PORT OF `lib/trace/imageEdit.clampCrop`, AND THE ORDER IS THE POINT.** The size is
 * clamped to the frame FIRST, then the origin is clamped so the box still fits. Clamping the origin
 * first lets a large box push itself back off the far edge — a rectangle that reads pixels which are
 * not there, which in `cropPixels` is a `subarray` running past the end and, because a
 * `Uint8ClampedArray` reads past its end as `undefined`, a black band nobody would trace back to a
 * rounding rule.
 *
 * It is a port rather than a call for the bundle reason in the header. What stops the two drifting is
 * `e2e/trace-frame-geometry-unit.spec.ts`, which runs a table of rectangles through THIS function and
 * through the real `clampCrop` and requires identical answers — the check the old private closure
 * could not have, and said so.
 */
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

/** @returns the crop that is the whole frame — what "no crop" is, spelled as a rectangle. */
export function wholeCropRect(frame: FrameSize): CropRect {
  return {
    x: 0,
    y: 0,
    width: Math.max(1, Math.round(frame.width)),
    height: Math.max(1, Math.round(frame.height))
  };
}

/** True when `rect` is the entire frame, i.e. when there is nothing to say about a crop. */
export function isWholeCropRect(rect: CropRect, frame: FrameSize): boolean {
  const whole = wholeCropRect(frame);
  return rect.x === 0 && rect.y === 0 && rect.width === whole.width && rect.height === whole.height;
}

/**
 * `base` with one corner moved to (`x`, `y`), in the frame's own pixels.
 *
 * **THE MOVED EDGES ARE CLAMPED, NOT THE FINISHED RECTANGLE — see this file's header for the three
 * measured failures the other order produced.** Each edge the dragged corner owns is clamped against
 * the edge it must not cross (its opposite, plus the minimum) and against the frame. The two edges the
 * researcher is NOT touching are then arithmetically incapable of moving, which is the only behaviour
 * a drag can have and still be aiming at something.
 *
 * The corner is given as an absolute position rather than as a delta, because that is what a pointer
 * drag computes: the anchor is snapshotted at pointerdown and every move is measured from it, so a
 * gesture cannot accumulate rounding. `TraceCrop.traceMoveCorner` takes a delta and adds it to the
 * same snapshot one line earlier; the answers are the same and the unit spec pins them by the Kotlin
 * test's own numbers.
 */
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

/**
 * `base` slid by (`dx`, `dy`) frame pixels, keeping its size and staying inside the frame.
 *
 * A slide is the one operation where clamping the finished rectangle IS right: the size is not
 * changing, so the clamp has nothing to take from one edge and give to another. It stops at the frame,
 * which is what a box being pushed against an edge should do.
 */
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

/**
 * The rectangle two corners describe, in either order — what a marquee drawn across the photograph is.
 *
 * WHY THE PANEL NEEDED THIS AT ALL. The frame opens as the whole photograph, so the frame's own box
 * covers the entire picture, and every press on it was read as "move this box" — a box that by
 * definition cannot move, because it already fills the frame. So the first gesture anybody makes on a
 * crop tool, press and drag a rectangle over the part they want, did nothing whatsoever and said
 * nothing about why. Drawing is now what a drag on the picture means while the frame is whole, and
 * what a drag OUTSIDE the box means once it is not.
 *
 * A box smaller than `minEdge` is grown by {@link clampCropRect} rather than refused: somebody who
 * flicked out a tiny rectangle gets the smallest legal one at that spot, which they can then pull out
 * to size, instead of a gesture that silently did nothing.
 */
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

/**
 * How many photograph pixels one DRAWN pixel is worth.
 *
 * **MEASURED, NEVER ASSUMED, AND THIS IS THE SECOND HALF OF THE DEFECT THIS FILE WAS WRITTEN FOR.**
 * The panel used to divide a pointer delta by the scale the preview was LAID OUT at — correct only for
 * as long as nothing constrains the preview's width, which stopped being true the moment it was made
 * to fit a 360px-wide handset. `displayedEdge` is read from `getBoundingClientRect()` at pointerdown,
 * so the answer describes the picture the finger is actually on.
 *
 * It is also what makes the arrow keys work. The nudge stepped one PHOTOGRAPH pixel per press, which
 * on a 4032px sheet drawn 360px wide is one eleventh of a drawn pixel: thirty presses moved the handle
 * less than three pixels and the keyboard route read as dead. One press is now one drawn pixel — at
 * least one photograph pixel, never zero — and the handle's spoken label says how many that is.
 *
 * @returns at least 1, so a nudge always moves and a division is never by zero.
 */
export function sourcePerDisplayPixel(sourceEdge: number, displayedEdge: number): number {
  if (!Number.isFinite(sourceEdge) || !Number.isFinite(displayedEdge) || displayedEdge <= 0) return 1;
  return Math.max(1, sourceEdge / displayedEdge);
}

/** The nudge in photograph pixels for one arrow press at this magnification. Always at least 1. */
export function nudgeStepFor(sourceEdge: number, displayedEdge: number): number {
  return Math.max(1, Math.round(sourcePerDisplayPixel(sourceEdge, displayedEdge)));
}

/* ────────────────────────────────────────────────────────────────────────────
 * "Choose a frame" — the preset rectangles
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the preset chooser.
 *
 * `rect` takes the frame and answers in the frame's own pixels, which is the SAME space the drag and
 * the four number boxes write in. That is the whole reason the presets are declared here rather than
 * built in the JSX: a preset that wrote a rectangle in the preview's pixels would put the two routes
 * into two coordinate systems, and the disagreement would only show on a photograph big enough to be
 * scaled — i.e. on every real one.
 */
export interface FramePreset {
  readonly id: string;
  /** What the button says. Never a bare direction — see `hint`. */
  readonly label: string;
  /** One clause saying what it is FOR, because "Top half" does not say when to press it. */
  readonly hint: string;
  readonly rect: (frame: FrameSize) => CropRect;
}

/**
 * The frames worth one press.
 *
 * SEVEN, AND EVERY ONE OF THEM IS A SHAPE A PHOTOGRAPH OF A SHEET ACTUALLY COMES IN. A researcher
 * photographing a block on a workshop table gets the sheet in the middle with workbench around it
 * (the two middles), or two drawings on one sheet (the halves). They are a starting point rather than
 * an answer: every one of them can then be dragged, nudged or typed, which is why the presets write
 * the same `crop` state the other three routes do instead of applying anything themselves.
 *
 * "The whole photograph" is FIRST and is a preset like the others, so the way back is in the same row
 * as the ways out. It is not the same control as "Use the whole photograph" below the picture: that
 * one un-applies a committed frame, this one only moves the box.
 */
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

/**
 * The preset's rectangle, already clamped to the frame.
 *
 * Clamped HERE rather than trusted, because the declarations above round independently on each axis
 * and a 17px-tall photograph would otherwise get a half of 8 — under the minimum edge, and legal only
 * because nothing had checked. The clamp is the same one every other route ends with.
 */
export function presetCropRect(preset: FramePreset, frame: FrameSize, minEdge: number): CropRect {
  return clampCropRect(preset.rect(frame), frame, minEdge);
}

/**
 * Which preset the current rectangle IS, or null when it is none of them.
 *
 * WHAT IT IS FOR: a chooser whose rows never light up is a chooser that cannot tell a researcher what
 * they picked, and this repository's rule is that a control shows its own state. It is an exact match
 * on purpose — a frame the researcher then nudged by one pixel is genuinely no longer "Top half", and
 * claiming it still is would be the panel rounding somebody's aim off for them.
 */
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
