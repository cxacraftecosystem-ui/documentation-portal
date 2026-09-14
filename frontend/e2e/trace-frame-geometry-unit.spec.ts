import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

import { expect, test } from "@playwright/test";
import * as ts from "typescript";

import {
  FRAME_PRESETS,
  clampCropRect,
  cropRectFromPoints,
  isWholeCropRect,
  matchingPresetId,
  moveCropBy,
  moveCropCorner,
  nudgeStepFor,
  presetCropRect,
  sourcePerDisplayPixel
} from "@/components/trace/frameGeometry";
import {
  CROP_MIN_EDGE_PX,
  clampCrop,
  cropPixels,
  isWholeFrame,
  type EditablePixels
} from "@/lib/trace/imageEdit";

/**
 * The crop rectangle somebody aims — the arithmetic, with no browser, no worker and no React.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS FILE EXISTS, WHICH IS A DEFECT AND NOT A GAP IN COVERAGE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The designer portal's owner reported the frame selection as non-functional. It was, through every
 * route except the one a rendered panel spec already covered — type four numbers, press "Use this
 * frame for the trace", check the rectangle that reaches the editor, and that passed throughout. The
 * pointer routes did not, and the reason is in the comment that used to sit above the broken function
 * inside the component:
 *
 *     "WHAT KEEPS THE TWO HONEST IS NOT A TEST OF THIS FUNCTION — it is not exported and this spec
 *      suite has no React renderer to reach it through."
 *
 * So the arithmetic lives in `components/trace/frameGeometry.ts`, which imports nothing at run time
 * (its one `import type` is erased), and this file is the test that comment said could not exist. It
 * is a `-unit` spec because the arithmetic is the half worth checking on every push and it needs no
 * browser at all; `e2e/trace-frame-panel.spec.ts` renders the panel that drives it, in a real one.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE FOUR THINGS IT HOLDS IN PLACE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 *  1. **THE PANEL'S CLAMP AND THE WORKER'S CLAMP ARE THE SAME FUNCTION.** They cannot be the same
 *     CODE — `lib/trace/imageEdit.ts` pulls `engine/convolve` onto whatever imports it, which
 *     `TracePanel.tsx`'s third property forbids a component from doing — so they are two
 *     implementations, and the only thing that can keep them equal is a case that runs both. If they
 *     disagree, the rectangle drawn on screen and the rectangle the pixels come from are different
 *     rectangles, and nothing anywhere says so.
 *
 *  2. **A DRAGGED CORNER MOVES ITS OWN TWO EDGES AND NOTHING ELSE.** The numbers in those cases are
 *     `TraceCropTest`'s numbers — `android/.../ui/trace/TraceCrop.kt` is the same arithmetic in
 *     Kotlin — so the handset and this client cannot drift on what a drag means. They are also the
 *     regression: the shipped code answered the second of them with the WHOLE PHOTOGRAPH.
 *
 *  3. **WHAT IS FRAMED IS WHAT IS COPIED.** One case runs a rectangle produced by the panel's own drag
 *     arithmetic through the real `cropPixels` and checks the bytes, so "the frame is what gets
 *     traced" is a property this suite can fail rather than a sentence in a header.
 *
 *  4. **THE ENGINE IS STILL NOT ON THE PAGE'S BUNDLE.** The last section walks the static import
 *     closure of `TracePanel.tsx` and counts the top-level `@/lib/trace` imports in it. Adding a crop
 *     tool is precisely the change that would break `TracePanel.tsx`'s third property by accident —
 *     the obvious spelling is `import { ImageEditor } from "@/lib/trace/imageEditClient"` at the top
 *     of the component — so the count is pinned here rather than left to a reviewer.
 *
 * Run it with: `cd frontend && npx playwright test trace-frame-geometry --reporter=line`
 */

/** The frame every case aims in unless it says otherwise. Landscape, so a transposed axis is visible. */
const FRAME = { width: 1000, height: 800 };

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The two clamps are one clamp
 * ──────────────────────────────────────────────────────────────────────────── */

test("the panel's clamp and the worker's clamp answer identically, including the awkward inputs", () => {
  /*
    THE TABLE IS THE POINT. A single happy-path rectangle would pass against almost any clamp; every
    row here is a case where two plausible implementations differ — fractional input, a box larger
    than the frame, a box pushed off the far edge, a box under the minimum edge, a frame smaller than
    the minimum edge, and the values `Number.isFinite` exists to catch.
  */
  const rows = [
    { rect: { x: 0, y: 0, width: 1000, height: 800 }, frame: FRAME },
    { rect: { x: 3.4, y: 9.6, width: 40.2, height: 30.8 }, frame: FRAME },
    { rect: { x: 990, y: 790, width: 40, height: 40 }, frame: FRAME },
    { rect: { x: -50, y: -50, width: 120, height: 120 }, frame: FRAME },
    { rect: { x: 10, y: 10, width: 2, height: 2 }, frame: FRAME },
    { rect: { x: 0, y: 0, width: 5000, height: 5000 }, frame: FRAME },
    // A frame smaller than the minimum edge on one axis: the minimum has to give way to the frame,
    // or the clamp answers a rectangle that does not fit in the picture it is a rectangle of.
    { rect: { x: 0, y: 0, width: 16, height: 16 }, frame: { width: 9, height: 40 } },
    { rect: { x: Number.NaN, y: 4, width: Number.NaN, height: 20 }, frame: FRAME },
    { rect: { x: Number.POSITIVE_INFINITY, y: 0, width: 30, height: 30 }, frame: FRAME }
  ];

  for (const row of rows) {
    const mine = clampCropRect(row.rect, row.frame, CROP_MIN_EDGE_PX);
    const theirs = clampCrop(row.rect, row.frame.width, row.frame.height);
    expect(mine, `clamp disagreed for ${JSON.stringify(row)}`).toEqual(theirs);

    // And whatever they agreed on is a legal rectangle: whole numbers, inside the frame, never under
    // the minimum unless the FRAME is.
    expect(Number.isInteger(mine.x) && Number.isInteger(mine.y)).toBe(true);
    expect(Number.isInteger(mine.width) && Number.isInteger(mine.height)).toBe(true);
    expect(mine.x).toBeGreaterThanOrEqual(0);
    expect(mine.y).toBeGreaterThanOrEqual(0);
    expect(mine.x + mine.width).toBeLessThanOrEqual(Math.max(1, Math.floor(row.frame.width)));
    expect(mine.y + mine.height).toBeLessThanOrEqual(Math.max(1, Math.floor(row.frame.height)));
    expect(mine.width).toBeGreaterThanOrEqual(Math.min(CROP_MIN_EDGE_PX, row.frame.width));
    expect(mine.height).toBeGreaterThanOrEqual(Math.min(CROP_MIN_EDGE_PX, row.frame.height));
  }
});

test("'the whole frame' means the same thing on the panel's side and the worker's", () => {
  const whole = { x: 0, y: 0, width: 1000, height: 800 };
  expect(isWholeCropRect(whole, FRAME)).toBe(isWholeFrame(whole, 1000, 800));
  expect(isWholeCropRect(whole, FRAME)).toBe(true);

  // One pixel off on either axis is NOT the whole frame, on either side. This is what decides whether
  // `cropPixels` copies row by row or hands back the source, and whether the provenance sentence
  // mentions a crop at all.
  for (const near of [
    { x: 1, y: 0, width: 999, height: 800 },
    { x: 0, y: 0, width: 1000, height: 799 }
  ]) {
    expect(isWholeCropRect(near, FRAME)).toBe(isWholeFrame(near, 1000, 800));
    expect(isWholeCropRect(near, FRAME)).toBe(false);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The corners — the defect that was reported
 * ──────────────────────────────────────────────────────────────────────────── */

test("dragging a corner outwards stops at the frame and never moves the opposite corner", () => {
  /*
    THE REGRESSION, IN THE NUMBERS THE SHIPPED CODE ACTUALLY PRODUCED.

    `moveCorner` built a rectangle out of the dragged corner and the opposite one and handed the
    FINISHED rectangle to a clamp. A clamp is total, so a box that hung off the edge came back SLID
    back inside — which moves the corner nobody is touching. Measured on the shipped code, 1000x800,
    crop {100,100,200,200}:

      · bottom-right dragged one pixel past the edge → {99, 99, 901, 701}
      · bottom-right dragged well past it            → {0, 0, 1000, 800}   ← the crop, silently gone

    Pulling a corner out to the edge of the sheet is the commonest gesture this tool has, so every
    such gesture ended with the frame back at "the whole photograph" and nothing said why. These are
    `TraceCropTest`'s numbers, so the two clients cannot drift on what a drag means.
  */
  const start = { x: 100, y: 100, width: 200, height: 200 };

  const wellPast = moveCropCorner(start, "se", 5100, 5100, FRAME, CROP_MIN_EDGE_PX);
  expect(wellPast).toEqual({ x: 100, y: 100, width: 900, height: 700 });

  const onePast = moveCropCorner(start, "se", 1001, 801, FRAME, CROP_MIN_EDGE_PX);
  expect(onePast.x, "the anchored left edge must not move").toBe(100);
  expect(onePast.y, "the anchored top edge must not move").toBe(100);
  expect(onePast).toEqual({ x: 100, y: 100, width: 900, height: 700 });

  // The same failure from the other side: a top-left handle pulled off the picture used to push the
  // bottom-right corner out with it, from (300, 300) to (305, 305).
  const offTopLeft = moveCropCorner(start, "nw", -5, -5, FRAME, CROP_MIN_EDGE_PX);
  expect(offTopLeft.x + offTopLeft.width, "the anchored right edge must not move").toBe(300);
  expect(offTopLeft.y + offTopLeft.height, "the anchored bottom edge must not move").toBe(300);
  expect(offTopLeft).toEqual({ x: 0, y: 0, width: 300, height: 300 });
});

test("dragging a corner past its opposite stops at the minimum instead of inverting the frame", () => {
  // `TraceCropTest`'s first corner case, by its numbers. The old code answered the whole photograph
  // here — `Math.abs` on a negative width flips the rectangle, and the clamp then grew the flipped box
  // back to the frame.
  const dragged = moveCropCorner(
    { x: 100, y: 100, width: 200, height: 200 },
    "nw",
    5100,
    5100,
    { width: 1000, height: 1000 },
    CROP_MIN_EDGE_PX
  );

  expect(dragged.x + dragged.width, "the right edge must not have moved").toBe(300);
  expect(dragged.y + dragged.height, "the bottom edge must not have moved").toBe(300);
  expect(dragged.width).toBe(CROP_MIN_EDGE_PX);
  expect(dragged.height).toBe(CROP_MIN_EDGE_PX);
});

test("each corner owns exactly two edges, and the other two are arithmetically unable to move", () => {
  /*
    THE PROPERTY BEHIND THE THREE CASES ABOVE, over the whole plane rather than at three points. If it
    holds for every corner at every target, then no drag can ever move the corner somebody is holding
    still — which is the only behaviour a drag can have and still be aiming at something.
  */
  const start = { x: 240, y: 180, width: 300, height: 260 };
  const held = {
    nw: { x: start.x + start.width, y: start.y + start.height },
    ne: { x: start.x, y: start.y + start.height },
    sw: { x: start.x + start.width, y: start.y },
    se: { x: start.x, y: start.y }
  } as const;

  for (const corner of ["nw", "ne", "sw", "se"] as const) {
    for (const target of [
      { x: -900, y: -900 },
      { x: 9000, y: 9000 },
      { x: -900, y: 9000 },
      { x: 9000, y: -900 },
      { x: 250, y: 190 },
      { x: 530, y: 430 },
      { x: 0, y: 0 }
    ]) {
      const next = moveCropCorner(start, corner, target.x, target.y, FRAME, CROP_MIN_EDGE_PX);
      const stillX = corner === "nw" || corner === "sw" ? next.x + next.width : next.x;
      const stillY = corner === "nw" || corner === "ne" ? next.y + next.height : next.y;
      expect(stillX, `${corner} moved the held vertical edge at ${JSON.stringify(target)}`).toBe(held[corner].x);
      expect(stillY, `${corner} moved the held horizontal edge at ${JSON.stringify(target)}`).toBe(held[corner].y);
      // …and the answer is still a legal crop of the picture.
      expect(next).toEqual(clampCrop(next, FRAME.width, FRAME.height));
    }
  }
});

test("sliding the box keeps its size and stops at the frame", () => {
  // The one operation where clamping the finished rectangle IS right: nothing is resizing, so the
  // clamp has nothing to take from one edge and give to another.
  const moved = moveCropBy(
    { x: 100, y: 100, width: 200, height: 200 },
    5000,
    -5000,
    { width: 1000, height: 1000 },
    CROP_MIN_EDGE_PX
  );
  expect(moved).toEqual({ x: 800, y: 0, width: 200, height: 200 });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Drawing a new frame, which is the gesture that did nothing at all
 * ──────────────────────────────────────────────────────────────────────────── */

test("a marquee reads the same in any direction, and a flick still leaves a usable frame", () => {
  // Pulled down-right and pulled up-left describe the same rectangle. Nobody decides which corner to
  // start from, and a tool that only understood one direction would look broken half the time —
  // which, given that the whole gesture did nothing before this change, is the failure mode this case
  // is guarding the fix against.
  const downRight = cropRectFromPoints(120, 90, 470, 380, FRAME, CROP_MIN_EDGE_PX);
  const upLeft = cropRectFromPoints(470, 380, 120, 90, FRAME, CROP_MIN_EDGE_PX);
  expect(downRight).toEqual({ x: 120, y: 90, width: 350, height: 290 });
  expect(upLeft).toEqual(downRight);

  // Started outside the picture, which a drag that begins on the frame's edge does: clipped, not
  // refused.
  expect(cropRectFromPoints(-200, -200, 300, 250, FRAME, CROP_MIN_EDGE_PX)).toEqual({
    x: 0,
    y: 0,
    width: 300,
    height: 250
  });

  // A flick smaller than the minimum edge grows to it rather than being discarded, so the gesture
  // ends with something that can be pulled out to size instead of with nothing at all.
  const flick = cropRectFromPoints(500, 400, 504, 403, FRAME, CROP_MIN_EDGE_PX);
  expect(flick.width).toBe(CROP_MIN_EDGE_PX);
  expect(flick.height).toBe(CROP_MIN_EDGE_PX);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The magnification, which is what made the arrow keys look dead
 * ──────────────────────────────────────────────────────────────────────────── */

test("one arrow press is one DRAWN pixel, however big the photograph is", () => {
  /*
    THE DEFECT: the nudge stepped one PHOTOGRAPH pixel. A 4032px sheet drawn 360px wide is 11.2
    photograph pixels per drawn pixel, so a press moved the handle by a ninth of a pixel and Shift's
    ten moved it by nine tenths of one. Thirty presses showed nothing, on the route `FramePanel`'s
    header calls the one that works for every input method.
  */
  expect(nudgeStepFor(4032, 360)).toBe(11);
  expect(nudgeStepFor(360, 360)).toBe(1);
  // A picture drawn LARGER than the photograph still moves by at least one photograph pixel: a step
  // of zero is a control that does nothing, which is worse than one that moves too far.
  expect(nudgeStepFor(200, 400)).toBe(1);
  // Nothing measured yet, and nothing divided by zero.
  expect(nudgeStepFor(4032, 0)).toBe(1);
  expect(sourcePerDisplayPixel(4032, 0)).toBe(1);
  expect(sourcePerDisplayPixel(4032, Number.NaN)).toBe(1);
  expect(sourcePerDisplayPixel(1000, 250)).toBe(4);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. "Choose a frame"
 * ──────────────────────────────────────────────────────────────────────────── */

test("every preset frame is a legal crop, and the row can say which one is showing", () => {
  const frames = [FRAME, { width: 4032, height: 3024 }, { width: 900, height: 1600 }, { width: 40, height: 17 }];
  for (const frame of frames) {
    for (const preset of FRAME_PRESETS) {
      const rect = presetCropRect(preset, frame, CROP_MIN_EDGE_PX);
      // Legal by the WORKER's rule, not only by this module's — a preset that produced a rectangle
      // the worker then had to move would put the chip and the pixels in disagreement.
      expect(rect, `${preset.id} on ${frame.width}x${frame.height}`).toEqual(
        clampCrop(rect, frame.width, frame.height)
      );
      // And the chooser can light the row that is showing, which is how a control says its own state.
      expect(matchingPresetId(rect, frame, CROP_MIN_EDGE_PX)).not.toBeNull();
    }
    // The first row really is the way back to everything.
    expect(isWholeCropRect(presetCropRect(FRAME_PRESETS[0], frame, CROP_MIN_EDGE_PX), frame)).toBe(true);
  }

  // A frame that was then nudged is honestly none of them — a chip that stayed lit would be the panel
  // rounding somebody's aim off for them.
  const topHalf = FRAME_PRESETS.find((preset) => preset.id === "top-half");
  expect(topHalf, "the preset row lost 'Top half'").toBeDefined();
  const half = presetCropRect(topHalf!, FRAME, CROP_MIN_EDGE_PX);
  expect(matchingPresetId(half, FRAME, CROP_MIN_EDGE_PX)).toBe("top-half");
  expect(matchingPresetId({ ...half, height: half.height - 1 }, FRAME, CROP_MIN_EDGE_PX)).toBeNull();

  // Every preset carries a sentence, because `title` is not a route on a handset or a keyboard.
  for (const preset of FRAME_PRESETS) {
    expect(preset.label.length).toBeGreaterThan(0);
    expect(preset.hint.length).toBeGreaterThan(0);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. End to end: the frame that was aimed is the frame that is copied
 * ──────────────────────────────────────────────────────────────────────────── */

/** RGBA where every channel is a function of (x, y), so a mis-indexed read is visible, not plausible. */
function ramp(width: number, height: number): EditablePixels {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const i = (y * width + x) * 4;
      data[i] = x % 256;
      data[i + 1] = y % 256;
      data[i + 2] = (x + y) % 256;
      data[i + 3] = 255;
    }
  }
  return { data, width, height };
}

test("a rectangle aimed with the panel's own arithmetic copies exactly the pixels under it", () => {
  /*
    THE WHOLE CLAIM, IN ONE CASE. The panel's job is "what you framed is what gets traced", and the two
    halves of that sentence are otherwise checked by different files that never meet: the geometry on
    its own, and `cropPixels` handed rectangles a test wrote by hand. Here the rectangle is produced
    the way a drag produces one — draw a marquee, then pull a corner — and the bytes that come out are
    compared against the source at the offset the rectangle names.
  */
  const source = ramp(200, 160);
  const frame = { width: source.width, height: source.height };

  // A marquee pulled from (40, 30) to (150, 110), then the bottom-right corner pulled well past the
  // edge — the gesture that used to reset the crop to the whole photograph.
  const drawn = cropRectFromPoints(40, 30, 150, 110, frame, CROP_MIN_EDGE_PX);
  expect(drawn).toEqual({ x: 40, y: 30, width: 110, height: 80 });
  const aimed = moveCropCorner(drawn, "se", 900, 900, frame, CROP_MIN_EDGE_PX);
  expect(aimed).toEqual({ x: 40, y: 30, width: 160, height: 130 });

  const cropped = cropPixels(source, aimed);
  expect(cropped.width).toBe(aimed.width);
  expect(cropped.height).toBe(aimed.height);
  expect(cropped.data.length).toBe(aimed.width * aimed.height * 4);

  for (const [px, py] of [
    [0, 0],
    [1, 0],
    [0, 1],
    [aimed.width - 1, aimed.height - 1],
    [Math.floor(aimed.width / 2), Math.floor(aimed.height / 2)]
  ]) {
    const to = (py * aimed.width + px) * 4;
    const from = ((aimed.y + py) * source.width + (aimed.x + px)) * 4;
    expect(
      [cropped.data[to], cropped.data[to + 1], cropped.data[to + 2], cropped.data[to + 3]],
      `pixel (${px}, ${py}) of the crop`
    ).toEqual([source.data[from], source.data[from + 1], source.data[from + 2], source.data[from + 3]]);
  }

  // AND THE SOURCE IS UNTOUCHED. "The photograph is never altered" is the promise the whole feature
  // rests on, and a crop that wrote into its own input would break it silently — the next crop taken
  // from a wider frame would read pixels the last one had overwritten.
  expect(cropped.data).not.toBe(source.data);
  const untouched = ramp(200, 160);
  expect(Array.from(source.data.slice(0, 4096))).toEqual(Array.from(untouched.data.slice(0, 4096)));
});

/* ────────────────────────────────────────────────────────────────────────────
 * 7. The panel really uses this arithmetic
 * ──────────────────────────────────────────────────────────────────────────── */

const TRACE_DIR = join(__dirname, "..", "components", "trace");

/** Source as written, with Windows line endings normalised so a substring check is portable. */
const read = (...parts: string[]) => readFileSync(join(...parts), "utf8").replace(/\r\n/g, "\n");

test("FramePanel drives the shared geometry rather than a private copy of it", () => {
  /*
    THE CASE THAT STOPS THIS FILE BECOMING DECORATIVE. Everything above tests a module; none of it
    tests that the panel CALLS it, and the defect this whole module exists for was a private closure
    that no test could reach. Reading the source rather than importing the component is deliberate:
    the component is a React module and this spec runs in Node with no renderer. The rendered half is
    `e2e/trace-frame-panel.spec.ts`.
  */
  const source = read(TRACE_DIR, "FramePanel.tsx");

  expect(source).toContain('from "./frameGeometry"');
  for (const name of ["clampCropRect", "moveCropCorner", "moveCropBy", "cropRectFromPoints", "nudgeStepFor"]) {
    expect(source, `FramePanel no longer calls ${name}`).toContain(`${name}(`);
  }

  // The four routes the panel's own header promises, each still reachable: a marquee, a move, four
  // corner handles that take the arrow keys, and the preset row.
  expect(source).toContain('beginDrag(event, "draw")');
  expect(source).toContain("FRAME_PRESETS.map");
  expect(source).toContain("onKeyDown={(event) => nudge(event, corner)}");
  // A gesture whose pointer capture is taken away must end, or the next hover over the picture
  // resizes the frame with no button held.
  expect(source).toContain("onLostPointerCapture");
});

test("TracePanel traces the frame the panel committed, not the whole decode", () => {
  /*
    THE ONE WIRING MISTAKE THAT WOULD BE INVISIBLE. A crop that reaches `FramePanel`'s own readout but
    not `runTrace` leaves a panel that says it is using 800x600 while the drawing on screen is the
    whole sheet — and the comparison plates are worse, because they would stack a cropped drawing over
    an uncropped photograph, aligned by nothing. All three consumers must read the one memo.
  */
  const panel = read(TRACE_DIR, "TracePanel.tsx");

  expect(panel, "the frame chooser is not mounted").toContain("<FramePanel pixels={pixels}");
  expect(panel, "the committed frame is not held").toContain("const [edited, setEdited]");
  expect(panel, "there is no single source of the traced pixels").toContain("const traceSource = useMemo");
  expect(panel, "the trace does not read the committed frame").toContain("runtime.transferableFrom(traceSource)");
  expect(panel, "the comparison plates do not read the committed frame").toContain(
    "buildComparisonPlates(traceSource, svgInput"
  );
  // A frame chosen on one image is meaningless on the next, and the reset has to happen where the
  // panel is unmounted rather than inside it.
  expect(panel, "a new image does not clear the frame").toContain("setEdited(null)");
  // The provenance sentence comes from the worker and is carried, not re-derived.
  expect(panel, "the frame is missing from the provenance note").toContain("edited.note");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 8. The engine is still not on the page's bundle
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Every module reachable from `TracePanel.tsx` by STATIC imports, as absolute paths.
 *
 * STATIC ONLY, WHICH IS THE ENTIRE DISTINCTION THIS CASE IS ABOUT. `traceExport.ts` reaches
 * `geometryToDocument.ts` — which really does import `@/lib/trace/engine/path` as a value — through
 * `await import("./geometryToDocument")`, so it is on a chunk nobody fetches until a vector export is
 * asked for. Following dynamic imports here would report that as a bundle violation and the check
 * would be abandoned as noisy; following only static ones is the question the property actually asks.
 *
 * Type-only imports are skipped for the same reason the compiler erases them: they are not there at
 * run time and cannot pull a byte onto any bundle.
 */
function staticClosureFrom(entry: string): string[] {
  const seen = new Set<string>();
  const queue = [entry];
  while (queue.length > 0) {
    const file = queue.pop() as string;
    if (seen.has(file)) continue;
    seen.add(file);
    const parsed = ts.createSourceFile(file, read(file), ts.ScriptTarget.ES2020, true);
    for (const statement of parsed.statements) {
      if (!ts.isImportDeclaration(statement)) continue;
      if (statement.importClause?.isTypeOnly === true) continue;
      const specifier = (statement.moduleSpecifier as ts.StringLiteral).text;
      if (!specifier.startsWith(".")) continue;
      // Siblings only: this feature's directory is self-contained apart from `@/components/ui` and
      // `@/lib/*`, neither of which can reach the engine without an import this case would see.
      const base = resolve(dirname(file), specifier);
      for (const candidate of [`${base}.ts`, `${base}.tsx`]) {
        try {
          readFileSync(candidate);
          queue.push(candidate);
          break;
        } catch {
          // The next extension, or a package this walk does not follow.
        }
      }
    }
  }
  return [...seen];
}

/** Top-level VALUE imports of `@/lib/trace/*` in one file, as `"file → specifier"` strings. */
function engineImportsIn(file: string): string[] {
  const parsed = ts.createSourceFile(file, read(file), ts.ScriptTarget.ES2020, true);
  const found: string[] = [];
  for (const statement of parsed.statements) {
    if (!ts.isImportDeclaration(statement)) continue;
    const specifier = (statement.moduleSpecifier as ts.StringLiteral).text;
    if (!specifier.startsWith("@/lib/trace")) continue;
    const clause = statement.importClause;
    // `import type { … }` — erased outright.
    if (clause?.isTypeOnly === true) continue;
    // `import { type A, type B }` — every binding erased, so the import is too.
    if (
      clause?.namedBindings !== undefined &&
      ts.isNamedImports(clause.namedBindings) &&
      clause.name === undefined &&
      clause.namedBindings.elements.every((element) => element.isTypeOnly)
    ) {
      continue;
    }
    found.push(`${file.split(/[\\/]/).pop()} → ${specifier}`);
  }
  return found;
}

test("nothing reachable from TracePanel imports the engine at the top level — the count is zero", () => {
  /*
    `TracePanel.tsx`'s third property, measured rather than asserted in prose: "Do not add a top-level
    import from `@/lib/trace/*` to this file." Adding a crop tool is exactly the change that breaks it
    by accident, because the obvious spelling of the crop worker's door is
    `import { ImageEditor } from "@/lib/trace/imageEditClient"` at the top of the component — which
    would put the client, the worker reference and `engine/contrast` + `engine/convolve` on the bundle
    of every page that mounts the panel, for every visitor who never touches an image.

    The closure is checked rather than the one file, because the property is about what the PAGE ends
    up holding: a clean `TracePanel.tsx` importing a `FramePanel.tsx` that imports the client is the
    same bundle with one more hop in it.
  */
  const closure = staticClosureFrom(join(TRACE_DIR, "TracePanel.tsx"));
  const offenders = closure.flatMap(engineImportsIn);
  expect(offenders, "a module on the page's bundle imports the engine at the top level").toEqual([]);

  // The walk really reached the new files, so a passing count is not a walk that found nothing.
  const names = closure.map((file) => file.split(/[\\/]/).pop());
  for (const expected of ["TracePanel.tsx", "FramePanel.tsx", "frameGeometry.ts", "imageEditRuntime.ts"]) {
    expect(names, `the import walk never reached ${expected}`).toContain(expected);
  }
  // …and it did NOT reach the one module that legitimately imports the engine as a value, because that
  // one is behind an `await import()`. If this ever flips, the check above has started passing for the
  // wrong reason or the dynamic import has been made static.
  expect(names, "geometryToDocument is no longer behind a dynamic import").not.toContain("geometryToDocument.ts");
});

test("the two runtime doors are the only place the engine is named, and both are dynamic", () => {
  // One `await import()` per door, inside a function body. `traceRuntime.ts` is the tracer's; this
  // feature's crop worker has its own, for the reason that file's header gives — a researcher who
  // traces without cropping never fetches the second chunk.
  const editRuntime = read(TRACE_DIR, "imageEditRuntime.ts");
  expect(editRuntime).toContain('await import("@/lib/trace/imageEditClient")');
  expect(editRuntime, "the crop door is not memoised on its promise").toContain("editorPromise");

  // And the panel reaches it through that door rather than through the client.
  const framePanel = read(TRACE_DIR, "FramePanel.tsx");
  expect(framePanel).toContain('from "./imageEditRuntime"');
  expect(framePanel).toContain("await loadImageEditor()");
});
