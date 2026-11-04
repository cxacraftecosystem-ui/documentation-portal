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

const FRAME = { width: 1000, height: 800 };

test("the panel's clamp and the worker's clamp answer identically, including the awkward inputs", () => {
  const rows = [
    { rect: { x: 0, y: 0, width: 1000, height: 800 }, frame: FRAME },
    { rect: { x: 3.4, y: 9.6, width: 40.2, height: 30.8 }, frame: FRAME },
    { rect: { x: 990, y: 790, width: 40, height: 40 }, frame: FRAME },
    { rect: { x: -50, y: -50, width: 120, height: 120 }, frame: FRAME },
    { rect: { x: 10, y: 10, width: 2, height: 2 }, frame: FRAME },
    { rect: { x: 0, y: 0, width: 5000, height: 5000 }, frame: FRAME },
    { rect: { x: 0, y: 0, width: 16, height: 16 }, frame: { width: 9, height: 40 } },
    { rect: { x: Number.NaN, y: 4, width: Number.NaN, height: 20 }, frame: FRAME },
    { rect: { x: Number.POSITIVE_INFINITY, y: 0, width: 30, height: 30 }, frame: FRAME }
  ];

  for (const row of rows) {
    const mine = clampCropRect(row.rect, row.frame, CROP_MIN_EDGE_PX);
    const theirs = clampCrop(row.rect, row.frame.width, row.frame.height);
    expect(mine, `clamp disagreed for ${JSON.stringify(row)}`).toEqual(theirs);

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

  for (const near of [
    { x: 1, y: 0, width: 999, height: 800 },
    { x: 0, y: 0, width: 1000, height: 799 }
  ]) {
    expect(isWholeCropRect(near, FRAME)).toBe(isWholeFrame(near, 1000, 800));
    expect(isWholeCropRect(near, FRAME)).toBe(false);
  }
});

test("dragging a corner outwards stops at the frame and never moves the opposite corner", () => {
  const start = { x: 100, y: 100, width: 200, height: 200 };

  const wellPast = moveCropCorner(start, "se", 5100, 5100, FRAME, CROP_MIN_EDGE_PX);
  expect(wellPast).toEqual({ x: 100, y: 100, width: 900, height: 700 });

  const onePast = moveCropCorner(start, "se", 1001, 801, FRAME, CROP_MIN_EDGE_PX);
  expect(onePast.x, "the anchored left edge must not move").toBe(100);
  expect(onePast.y, "the anchored top edge must not move").toBe(100);
  expect(onePast).toEqual({ x: 100, y: 100, width: 900, height: 700 });

  const offTopLeft = moveCropCorner(start, "nw", -5, -5, FRAME, CROP_MIN_EDGE_PX);
  expect(offTopLeft.x + offTopLeft.width, "the anchored right edge must not move").toBe(300);
  expect(offTopLeft.y + offTopLeft.height, "the anchored bottom edge must not move").toBe(300);
  expect(offTopLeft).toEqual({ x: 0, y: 0, width: 300, height: 300 });
});

test("dragging a corner past its opposite stops at the minimum instead of inverting the frame", () => {
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
      expect(next).toEqual(clampCrop(next, FRAME.width, FRAME.height));
    }
  }
});

test("sliding the box keeps its size and stops at the frame", () => {
  const moved = moveCropBy(
    { x: 100, y: 100, width: 200, height: 200 },
    5000,
    -5000,
    { width: 1000, height: 1000 },
    CROP_MIN_EDGE_PX
  );
  expect(moved).toEqual({ x: 800, y: 0, width: 200, height: 200 });
});

test("a marquee reads the same in any direction, and a flick still leaves a usable frame", () => {
  const downRight = cropRectFromPoints(120, 90, 470, 380, FRAME, CROP_MIN_EDGE_PX);
  const upLeft = cropRectFromPoints(470, 380, 120, 90, FRAME, CROP_MIN_EDGE_PX);
  expect(downRight).toEqual({ x: 120, y: 90, width: 350, height: 290 });
  expect(upLeft).toEqual(downRight);

  expect(cropRectFromPoints(-200, -200, 300, 250, FRAME, CROP_MIN_EDGE_PX)).toEqual({
    x: 0,
    y: 0,
    width: 300,
    height: 250
  });

  const flick = cropRectFromPoints(500, 400, 504, 403, FRAME, CROP_MIN_EDGE_PX);
  expect(flick.width).toBe(CROP_MIN_EDGE_PX);
  expect(flick.height).toBe(CROP_MIN_EDGE_PX);
});

test("one arrow press is one DRAWN pixel, however big the photograph is", () => {
  expect(nudgeStepFor(4032, 360)).toBe(11);
  expect(nudgeStepFor(360, 360)).toBe(1);
  expect(nudgeStepFor(200, 400)).toBe(1);
  expect(nudgeStepFor(4032, 0)).toBe(1);
  expect(sourcePerDisplayPixel(4032, 0)).toBe(1);
  expect(sourcePerDisplayPixel(4032, Number.NaN)).toBe(1);
  expect(sourcePerDisplayPixel(1000, 250)).toBe(4);
});

test("every preset frame is a legal crop, and the row can say which one is showing", () => {
  const frames = [FRAME, { width: 4032, height: 3024 }, { width: 900, height: 1600 }, { width: 40, height: 17 }];
  for (const frame of frames) {
    for (const preset of FRAME_PRESETS) {
      const rect = presetCropRect(preset, frame, CROP_MIN_EDGE_PX);
      expect(rect, `${preset.id} on ${frame.width}x${frame.height}`).toEqual(
        clampCrop(rect, frame.width, frame.height)
      );
      expect(matchingPresetId(rect, frame, CROP_MIN_EDGE_PX)).not.toBeNull();
    }
    expect(isWholeCropRect(presetCropRect(FRAME_PRESETS[0], frame, CROP_MIN_EDGE_PX), frame)).toBe(true);
  }

  const topHalf = FRAME_PRESETS.find((preset) => preset.id === "top-half");
  expect(topHalf, "the preset row lost 'Top half'").toBeDefined();
  const half = presetCropRect(topHalf!, FRAME, CROP_MIN_EDGE_PX);
  expect(matchingPresetId(half, FRAME, CROP_MIN_EDGE_PX)).toBe("top-half");
  expect(matchingPresetId({ ...half, height: half.height - 1 }, FRAME, CROP_MIN_EDGE_PX)).toBeNull();

  for (const preset of FRAME_PRESETS) {
    expect(preset.label.length).toBeGreaterThan(0);
    expect(preset.hint.length).toBeGreaterThan(0);
  }
});

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
  const source = ramp(200, 160);
  const frame = { width: source.width, height: source.height };

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

  expect(cropped.data).not.toBe(source.data);
  const untouched = ramp(200, 160);
  expect(Array.from(source.data.slice(0, 4096))).toEqual(Array.from(untouched.data.slice(0, 4096)));
});

const TRACE_DIR = join(__dirname, "..", "components", "trace");

const read = (...parts: string[]) => readFileSync(join(...parts), "utf8").replace(/\r\n/g, "\n");

test("FramePanel drives the shared geometry rather than a private copy of it", () => {
  const source = read(TRACE_DIR, "FramePanel.tsx");

  expect(source).toContain('from "./frameGeometry"');
  for (const name of ["clampCropRect", "moveCropCorner", "moveCropBy", "cropRectFromPoints", "nudgeStepFor"]) {
    expect(source, `FramePanel no longer calls ${name}`).toContain(`${name}(`);
  }

  expect(source).toContain('beginDrag(event, "draw")');
  expect(source).toContain("FRAME_PRESETS.map");
  expect(source).toContain("onKeyDown={(event) => nudge(event, corner)}");
  expect(source).toContain("onLostPointerCapture");
});

test("TracePanel traces the frame the panel committed, not the whole decode", () => {
  const panel = read(TRACE_DIR, "TracePanel.tsx");

  expect(panel, "the frame chooser is not mounted").toContain("<FramePanel pixels={pixels}");
  expect(panel, "the committed frame is not held").toContain("const [edited, setEdited]");
  expect(panel, "there is no single source of the traced pixels").toContain("const traceSource = useMemo");
  expect(panel, "the trace does not read the committed frame").toContain("runtime.transferableFrom(traceSource)");
  expect(panel, "the comparison plates do not read the committed frame").toContain(
    "buildComparisonPlates(traceSource, svgInput"
  );
  expect(panel, "a new image does not clear the frame").toContain("setEdited(null)");
  expect(panel, "the frame is missing from the provenance note").toContain("edited.note");
});

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
      const base = resolve(dirname(file), specifier);
      for (const candidate of [`${base}.ts`, `${base}.tsx`]) {
        try {
          readFileSync(candidate);
          queue.push(candidate);
          break;
        } catch {
        }
      }
    }
  }
  return [...seen];
}

function engineImportsIn(file: string): string[] {
  const parsed = ts.createSourceFile(file, read(file), ts.ScriptTarget.ES2020, true);
  const found: string[] = [];
  for (const statement of parsed.statements) {
    if (!ts.isImportDeclaration(statement)) continue;
    const specifier = (statement.moduleSpecifier as ts.StringLiteral).text;
    if (!specifier.startsWith("@/lib/trace")) continue;
    const clause = statement.importClause;
    if (clause?.isTypeOnly === true) continue;
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
  const closure = staticClosureFrom(join(TRACE_DIR, "TracePanel.tsx"));
  const offenders = closure.flatMap(engineImportsIn);
  expect(offenders, "a module on the page's bundle imports the engine at the top level").toEqual([]);

  const names = closure.map((file) => file.split(/[\\/]/).pop());
  for (const expected of ["TracePanel.tsx", "FramePanel.tsx", "frameGeometry.ts", "imageEditRuntime.ts"]) {
    expect(names, `the import walk never reached ${expected}`).toContain(expected);
  }
  expect(names, "geometryToDocument is no longer behind a dynamic import").not.toContain("geometryToDocument.ts");
});

test("the two runtime doors are the only place the engine is named, and both are dynamic", () => {
  const editRuntime = read(TRACE_DIR, "imageEditRuntime.ts");
  expect(editRuntime).toContain('await import("@/lib/trace/imageEditClient")');
  expect(editRuntime, "the crop door is not memoised on its promise").toContain("editorPromise");

  const framePanel = read(TRACE_DIR, "FramePanel.tsx");
  expect(framePanel).toContain('from "./imageEditRuntime"');
  expect(framePanel).toContain("await loadImageEditor()");
});
