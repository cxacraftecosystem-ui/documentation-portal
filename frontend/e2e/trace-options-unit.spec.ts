import { expect, test } from "@playwright/test";

import {
  ADVANCED_COUNT,
  CHOICES,
  ESSENTIAL_KEYS,
  PARAM_COUNT,
  PARAM_GROUPS,
  SLIDERS,
  TOGGLES,
  applyParamPatch,
  changedAdvancedLabels,
  changedLabels,
  formatValue,
  inactiveReason,
  isEssential,
  mergeParams,
  overwriteNotice
} from "@/components/trace/traceParamTable";
import {
  MAX_SHAPES_PER_FILE,
  VERB_CUBIC,
  VERB_LINE,
  VERB_QUAD,
  buildSvg,
  derivedFileName,
  sanitizeDimension,
  shapePathData,
  truncationNoteFor,
  type FlatGeometry,
  type GeometryStyle
} from "@/components/trace/geometryToSvg";
import { defaultTraceParams, sanitizeTraceParams, withOverrides } from "@/lib/trace/engine/params";
import type { TraceParams } from "@/lib/trace/traceClient";

/**
 * THE TRACING CONTROLS, AND THE SVG THEY PRODUCE — what `components/trace/` promises about a table
 * that describes an engine it deliberately does not import.
 *
 * WHY THIS SPEC NEVER OPENS A BROWSER. `TracePanel.tsx` is a `"use client"` component and this
 * repository has no React renderer in its devDependencies, so nothing inside its JSX can be exercised
 * at all. That is why the parameter table, the merge, the "this control does nothing" sentences and
 * the SVG writer live in non-React modules — exactly as `record-pickers-unit`, `task-authority-unit`
 * and `access-roster-unit` do beside this file — and it is what this spec stands in front of.
 *
 * ── THE THREE CLAIMS THAT WOULD ROT FIRST, AND WHY EACH ONE MATTERS ───────────────────────────
 *
 * 1. **`mergeParams` IS A LOCAL COPY OF `engine/params.withOverrides`'s MERGE HALF.** The copy exists
 *    because importing that function as a VALUE from a table the page imports statically would put
 *    ~3.2 KB gzipped of engine source into every page's bundle — the thing `TracePanel.tsx`'s property
 *    3 forbids. A local copy of somebody else's rule drifts unless something watches it, so every
 *    entry in the table is pushed through BOTH and the two answers are compared. The day the engine
 *    grows a nested section this spread does not know about, this fails instead of a setting silently
 *    stopping working.
 *
 * 2. **EVERY CHOICE VALUE IS A PLAIN STRING CAST TO AN ENUM.** `traceParamTable.ts`'s header explains
 *    why (naming the enum as a value is the same static import), and admits the cost: a typo would be
 *    SILENT, because the worker's `sanitizeTraceParams` answers an unrecognised string with the
 *    documented default rather than throwing. So every option value is round-tripped through the real
 *    sanitiser and has to survive.
 *
 * 3. **`ADVANCED_COUNT` IS THE NUMBER THE DISCLOSURE BUTTON PRINTS.** A button reading "Show all 32
 *    controls" while seven of the thirty-two are already on screen promises 32 and reveals 25. The two
 *    halves the panel draws are `isEssential` and its negation, so they must be exhaustive and
 *    disjoint, and the count on the button must be the size of the second one.
 */

const defaults = defaultTraceParams();

/**
 * Set one enum-valued control THROUGH THE TABLE'S OWN PATCH.
 *
 * NOT AN INLINE OBJECT LITERAL, and the reason is the same one `traceParamTable.ts`'s header gives for
 * the casts being there at all: `TraceParamsInput` is typed over the engine's string enums, so
 * `{ edge: { engine: "CANNY" } }` written here does not compile — a spec would have to import the enum
 * as a VALUE, which is the static engine import the table exists to avoid, or cast, which would mean
 * the spec agreeing with itself rather than with the table. Going through `spec.patch` means these
 * cases exercise the one place the cast actually lives.
 */
function choose(base: TraceParams, key: string, value: string): TraceParams {
  const spec = CHOICES.find((candidate) => candidate.key === key);
  if (spec === undefined) throw new Error(`no choice named ${key} in the table`);
  return applyParamPatch(base, spec.patch(value), sanitizeTraceParams);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The table's own accounting
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the table is the one register of how many controls there are", () => {
  test("PARAM_COUNT is the three arrays added up, and nothing else", () => {
    expect(PARAM_COUNT).toBe(SLIDERS.length + TOGGLES.length + CHOICES.length);
    // The upstream's thirty plus the two sharpening sliders this table adds. Written here as an
    // arithmetic identity rather than as a literal total, so adding a control moves both sides.
    expect(SLIDERS.length + TOGGLES.length + CHOICES.length).toBeGreaterThanOrEqual(30);
  });

  test("the two halves the panel draws are exhaustive and disjoint", () => {
    const every = [...SLIDERS, ...TOGGLES, ...CHOICES].map((spec) => spec.key);
    const essential = every.filter(isEssential);
    const advanced = every.filter((key) => !isEssential(key));
    expect(essential.length + advanced.length).toBe(PARAM_COUNT);
    expect(essential.filter((key) => advanced.includes(key))).toEqual([]);
  });

  test("ADVANCED_COUNT is what the press actually reveals, not the total", () => {
    const every = [...SLIDERS, ...TOGGLES, ...CHOICES].map((spec) => spec.key);
    expect(ADVANCED_COUNT).toBe(every.filter((key) => !isEssential(key)).length);
    expect(ADVANCED_COUNT).toBeLessThan(PARAM_COUNT);
  });

  test("every essential key names a control that exists", () => {
    // The key list is what the panel ASKS for and the table is what it HAS. An essential key naming a
    // control that has been renamed leaves the two disagreeing, and the disagreement surfaces as a
    // button promising one more control than the press produces.
    const every = new Set([...SLIDERS, ...TOGGLES, ...CHOICES].map((spec) => spec.key));
    for (const key of ESSENTIAL_KEYS) expect(every.has(key), `${key} is not in the table`).toBe(true);
  });

  test("every control belongs to a declared group, and no key is used twice", () => {
    const seen = new Set<string>();
    for (const spec of [...SLIDERS, ...TOGGLES, ...CHOICES]) {
      expect(PARAM_GROUPS, `${spec.key} is in group "${spec.group}"`).toContain(spec.group);
      expect(seen.has(spec.key), `${spec.key} appears twice`).toBe(false);
      seen.add(spec.key);
    }
  });

  test("every hint says something the label does not", () => {
    // A comment that only restates the code is the one kind this repository refuses, and a hint that
    // only restates its label is the same failure on screen: it costs a line and teaches nothing.
    for (const spec of [...SLIDERS, ...TOGGLES, ...CHOICES]) {
      expect(spec.hint.length, `${spec.key} has no hint`).toBeGreaterThan(spec.label.length);
      expect(spec.hint.toLowerCase(), `${spec.key}'s hint is its label`).not.toBe(spec.label.toLowerCase());
    }
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * The merge, against the engine's own
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("mergeParams agrees with engine/params.withOverrides for every entry in the table", () => {
  test("every slider, at both ends of its range", () => {
    for (const spec of SLIDERS) {
      for (const value of [spec.min, spec.max, (spec.min + spec.max) / 2]) {
        const patch = spec.patch(value);
        const mine = sanitizeTraceParams(mergeParams(defaults, patch));
        const theirs = withOverrides(defaults, patch);
        expect(mine, `${spec.key} at ${value}`).toEqual(theirs);
      }
    }
  });

  test("every toggle, both ways", () => {
    for (const spec of TOGGLES) {
      for (const value of [true, false]) {
        const patch = spec.patch(value);
        expect(sanitizeTraceParams(mergeParams(defaults, patch)), `${spec.key} = ${value}`).toEqual(
          withOverrides(defaults, patch)
        );
      }
    }
  });

  test("every choice, for every option it offers", () => {
    for (const spec of CHOICES) {
      for (const option of spec.options) {
        const patch = spec.patch(option.value);
        expect(sanitizeTraceParams(mergeParams(defaults, patch)), `${spec.key} = ${option.value}`).toEqual(
          withOverrides(defaults, patch)
        );
      }
    }
  });

  test("edge.flow is the one nested object and survives the merge", () => {
    // Spread explicitly in `mergeParams` for exactly this reason: a shallow spread of `edge` would
    // replace the whole `flow` object with the patch's, silently discarding every other flow setting.
    const spec = SLIDERS.find((s) => s.key === "edge.flow.sigmaM");
    expect(spec, "the flow slider is in the table").toBeTruthy();
    const merged = sanitizeTraceParams(mergeParams(defaults, spec!.patch(4)));
    expect(merged.edge.flow.sigmaM).toBeCloseTo(4, 6);
    // Everything else under `flow` is still the default rather than undefined-then-sanitised-back.
    expect(merged.edge.flow).toEqual({ ...defaults.edge.flow, sigmaM: merged.edge.flow.sigmaM });
  });
});

test.describe("every option value survives the engine's own sanitiser", () => {
  test("a string the enum does not know would be replaced, and none of ours is", () => {
    for (const spec of CHOICES) {
      for (const option of spec.options) {
        const applied = applyParamPatch(defaults, spec.patch(option.value), sanitizeTraceParams);
        expect(String(spec.read(applied)), `${spec.key} = ${option.value}`).toBe(option.value);
      }
    }
  });

  test("the guard has teeth: a misspelled value does NOT survive", () => {
    // Without this case the one above would pass against a sanitiser that accepted anything, which is
    // the whole failure mode the round trip exists to catch.
    const engine = CHOICES.find((c) => c.key === "edge.engine");
    expect(engine).toBeTruthy();
    const applied = applyParamPatch(defaults, engine!.patch("FDOGG"), sanitizeTraceParams);
    expect(engine!.read(applied)).not.toBe("FDOGG");
  });

  test("every slider's declared range is inside what the engine will accept", () => {
    for (const spec of SLIDERS) {
      for (const value of [spec.min, spec.max]) {
        const applied = applyParamPatch(defaults, spec.patch(value), sanitizeTraceParams);
        const rounded = spec.step >= 1 ? Math.round(value) : value;
        expect(spec.read(applied), `${spec.key} at ${value} was clamped by the engine`).toBeCloseTo(rounded, 5);
      }
    }
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * Telling the researcher what changed
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("a preset that overwrites hand-set values says so", () => {
  test("changedLabels names the control by the word on screen", () => {
    const spec = SLIDERS.find((s) => s.key === "output.simplify")!;
    const moved = applyParamPatch(defaults, spec.patch(spec.max), sanitizeTraceParams);
    expect(changedLabels(defaults, moved)).toEqual([spec.label]);
  });

  test("nothing changed is an empty list and a null notice, never an empty box", () => {
    expect(changedLabels(defaults, defaults)).toEqual([]);
    expect(overwriteNotice("The style", defaults, defaults)).toBeNull();
  });

  test("one change and several changes read as different sentences", () => {
    const one = applyParamPatch(defaults, { output: { simplify: 5 } }, sanitizeTraceParams);
    expect(overwriteNotice("The “Ink line” style", defaults, one)).toContain("changed one setting");

    const many = applyParamPatch(
      defaults,
      { output: { simplify: 5, corner: 12, strokeWidth: 3 } },
      sanitizeTraceParams
    );
    const sentence = overwriteNotice("The “Ink line” style", defaults, many);
    expect(sentence).toContain("changed 3 settings");
    expect(sentence).toContain("Simplify");
  });

  test("changedAdvancedLabels reports only what the collapsed half hides", () => {
    // Progressive disclosure is only honest if what it hides can still announce itself — and only if
    // it does NOT also announce what is in plain sight, which would be noise on a row the reader is
    // looking at.
    const essential = SLIDERS.find((s) => isEssential(s.key))!;
    const advanced = SLIDERS.find((s) => !isEssential(s.key))!;
    const moved = applyParamPatch(
      defaults,
      mergeParams(defaults, { ...essential.patch(essential.max), ...advanced.patch(advanced.max) }),
      sanitizeTraceParams
    );
    const hidden = changedAdvancedLabels(defaults, moved);
    expect(hidden).toContain(advanced.label);
    expect(hidden).not.toContain(essential.label);
  });
});

test.describe("the numeric readout", () => {
  test("an integer control never shows a decimal point", () => {
    expect(formatValue(1024, 64)).toBe("1024");
    expect(formatValue(12.4, 1)).toBe("12");
  });

  test("a fine control never shows a bare integer where it has travel", () => {
    expect(formatValue(0.5, 0.01)).toBe("0.50");
    expect(formatValue(1.25, 0.1)).toBe("1.3");
  });

  test("a value that is not a number reads as 0 rather than NaN", () => {
    expect(formatValue(Number.NaN, 0.01)).toBe("0");
    expect(formatValue(Number.POSITIVE_INFINITY, 1)).toBe("0");
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * "This control is doing nothing right now"
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("inactiveReason, whose every arm was read off engine/pipeline.ts", () => {
  test("THE TRAP: the median filter never reads Noise reduction, and says so", () => {
    // `pipeline.ts`'s MEDIAN arm calls `Denoise.median(grey, p.preprocess.medianRadius)` and reads
    // `denoiseStrength` nowhere. MEDIAN is what the `sketch` subject selects, so this is the commonest
    // configuration this panel has — a researcher can drag that slider for a minute and conclude the
    // trace is broken.
    const median = choose(defaults, "preprocess.denoise", "MEDIAN");
    expect(inactiveReason("preprocess.denoiseStrength", median)).toContain("fixed radius");

    const bilateral = choose(defaults, "preprocess.denoise", "BILATERAL");
    expect(inactiveReason("preprocess.denoiseStrength", bilateral)).toBeNull();

    const none = choose(defaults, "preprocess.denoise", "NONE");
    expect(inactiveReason("preprocess.denoiseStrength", none)).toContain("None");
  });

  test("the edge settings answer to the engine that is actually selected", () => {
    const canny = choose(defaults, "edge.engine", "CANNY");
    expect(inactiveReason("edge.blurSigma", canny)).toBeNull();
    expect(inactiveReason("edge.flow.sigmaM", canny)).toContain("Flow engine");
    expect(inactiveReason("edge.xdogPhi", canny)).toContain("XDoG and Flow");

    const fdog = choose(defaults, "edge.engine", "FDOG");
    expect(inactiveReason("edge.flow.sigmaM", fdog)).toBeNull();
    expect(inactiveReason("edge.xdogPhi", fdog)).toBeNull();
    expect(inactiveReason("edge.blurSigma", fdog)).toContain("Canny");
  });

  test("pruning reports BOTH conditions that can make it inert", () => {
    // `pipeline.ts` runs the prune inside the skeletonize branch, which itself runs only outside
    // outline mode. A sentence that named one of the two would be wrong half the time.
    const noSkeleton = applyParamPatch(defaults, { cleanup: { skeletonize: false } }, sanitizeTraceParams);
    expect(inactiveReason("cleanup.pruneSpurs", noSkeleton)).toContain("centrelines");

    const outline = choose(
      applyParamPatch(defaults, { cleanup: { skeletonize: true } }, sanitizeTraceParams),
      "output.vectorMode",
      "OUTLINE"
    );
    expect(inactiveReason("cleanup.pruneSpurs", outline)).toContain("Outline mode");
    expect(inactiveReason("cleanup.skeletonize", outline)).toContain("Outline mode");
  });

  test("the two flag-gated sliders answer to their own flags", () => {
    const noBridge = applyParamPatch(defaults, { cleanup: { bridgeGaps: false } }, sanitizeTraceParams);
    expect(inactiveReason("cleanup.maxGap", noBridge)).toContain("Bridge stroke ends");

    const noClahe = applyParamPatch(defaults, { preprocess: { claheEnabled: false } }, sanitizeTraceParams);
    expect(inactiveReason("preprocess.claheClip", noClahe)).toContain("Equalise local contrast");

    const noSharpen = applyParamPatch(defaults, { preprocess: { unsharpAmount: 0 } }, sanitizeTraceParams);
    expect(inactiveReason("preprocess.unsharpSigma", noSharpen)).toContain("Sharpen amount");

    const sharpening = applyParamPatch(defaults, { preprocess: { unsharpAmount: 1.5 } }, sanitizeTraceParams);
    expect(inactiveReason("preprocess.unsharpSigma", sharpening)).toBeNull();
  });

  test("a control with no condition attached is never accused of doing nothing", () => {
    for (const spec of [...SLIDERS, ...TOGGLES, ...CHOICES]) {
      const reason = inactiveReason(spec.key, defaults);
      if (reason !== null) expect(reason.length, `${spec.key}'s sentence is empty`).toBeGreaterThan(10);
    }
    expect(inactiveReason("edge.sensitivity", defaults)).toBeNull();
    expect(inactiveReason("a.key.that.does.not.exist", defaults)).toBeNull();
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * The SVG this feature writes rather than the engine's
 * ──────────────────────────────────────────────────────────────────────────── */

const INK: GeometryStyle = {
  stroke: 0xff000000,
  strokeWidth: 1.5,
  fill: null,
  fillRule: "EVENODD",
  cap: "ROUND",
  join: "ROUND",
  miterLimit: 4,
  opacity: 1
};

/** One shape: a start point, then whichever verbs the caller asks for. */
function geometryOf(
  coords: number[],
  verbs: number[],
  closed = 0,
  styleTable: readonly GeometryStyle[] = [INK]
): FlatGeometry {
  return {
    coords: new Float32Array(coords),
    verbs: new Uint8Array(verbs),
    verbStarts: new Uint32Array([0, verbs.length]),
    coordStarts: new Uint32Array([0, coords.length]),
    closed: new Uint8Array([closed]),
    styleTable,
    styleIndex: new Uint32Array([0])
  };
}

test.describe("shapePathData walks the flat arrays the worker actually sends", () => {
  test("a start point and three verb kinds", () => {
    const g = geometryOf([0, 0, 10, 0, 20, 0, 30, 0, 40, 0, 50, 0, 60, 0], [VERB_LINE, VERB_QUAD, VERB_CUBIC]);
    expect(shapePathData(g, 0, 2)).toBe("M0 0 L10 0 Q20 0 30 0 C40 0 50 0 60 0");
  });

  test("a closed shape gets its Z", () => {
    const g = geometryOf([0, 0, 10, 0], [VERB_LINE], 1);
    expect(shapePathData(g, 0, 2)).toBe("M0 0 L10 0 Z");
  });

  test("a run that cannot supply what its verbs claim is TRUNCATED, never read past", () => {
    // A trace interrupted mid-post is the one way this happens, and half a drawing is a better answer
    // than an exception in a component's render — or, worse, `undefined` coordinates reaching the file
    // as NaN, which makes renderers drop elements silently.
    const g: FlatGeometry = {
      coords: new Float32Array([0, 0, 10, 0]),
      verbs: new Uint8Array([VERB_LINE, VERB_CUBIC]),
      verbStarts: new Uint32Array([0, 2]),
      coordStarts: new Uint32Array([0, 4]),
      closed: new Uint8Array([0]),
      styleTable: [INK],
      styleIndex: new Uint32Array([0])
    };
    const d = shapePathData(g, 0, 2);
    expect(d).toBe("M0 0 L10 0");
    expect(d).not.toContain("NaN");
  });

  test("a shape with no start point is dropped rather than emitted empty", () => {
    const g: FlatGeometry = {
      coords: new Float32Array([]),
      verbs: new Uint8Array([]),
      verbStarts: new Uint32Array([0, 0]),
      coordStarts: new Uint32Array([0, 0]),
      closed: new Uint8Array([0]),
      styleTable: [INK],
      styleIndex: new Uint32Array([0])
    };
    expect(shapePathData(g, 0, 2)).toBe("");
  });
});

test.describe("buildSvg follows engine/svgWriter.ts's own conventions", () => {
  test("a non-finite coordinate becomes 0 rather than NaN", () => {
    // The upstream's reason, verbatim: "A single NaN or Infinity anywhere in an SVG attribute makes
    // the renderer drop the element that carries it — silently, and only in some renderers … A wrong
    // pixel is debuggable; a blank export is not."
    const g = geometryOf([0, 0, Number.NaN, Number.POSITIVE_INFINITY], [VERB_LINE]);
    const { svg } = buildSvg({ geometry: g, width: 100, height: 100, background: null });
    expect(svg).not.toContain("NaN");
    expect(svg).not.toContain("Infinity");
  });

  test("a canvas dimension that is not a usable number falls back to 1 unit", () => {
    expect(sanitizeDimension(Number.NaN)).toBe(1);
    expect(sanitizeDimension(0)).toBe(1);
    expect(sanitizeDimension(-5)).toBe(1);
    expect(sanitizeDimension(640)).toBe(640);
    const { svg } = buildSvg({
      geometry: geometryOf([0, 0, 1, 1], [VERB_LINE]),
      width: Number.NaN,
      height: 0,
      background: null
    });
    expect(svg).toContain('width="1" height="1"');
  });

  test("alpha is split out of the colour, and only when it is not opaque", () => {
    const opaque = buildSvg({
      geometry: geometryOf([0, 0, 1, 1], [VERB_LINE]),
      width: 10,
      height: 10,
      background: null
    }).svg;
    expect(opaque).toContain('stroke="#000000"');
    expect(opaque).not.toContain("stroke-opacity");

    const half = buildSvg({
      geometry: geometryOf([0, 0, 1, 1], [VERB_LINE], 0, [{ ...INK, stroke: 0x80000000 }]),
      width: 10,
      height: 10,
      background: null
    }).svg;
    expect(half).toContain("stroke-opacity");
  });

  test("a transparent document writes no background rectangle and an opaque one does", () => {
    const clear = buildSvg({
      geometry: geometryOf([0, 0, 1, 1], [VERB_LINE]),
      width: 10,
      height: 10,
      background: null
    }).svg;
    expect(clear).not.toContain("<rect");

    const white = buildSvg({
      geometry: geometryOf([0, 0, 1, 1], [VERB_LINE]),
      width: 10,
      height: 10,
      background: 0xffffffff
    }).svg;
    expect(white).toContain('<rect x="0" y="0"');
    expect(white).toContain('fill="#ffffff"');
  });

  test("a provenance note is written and cannot break the XML", () => {
    // `--` cannot appear inside an XML comment. A note that produced a malformed file would be a worse
    // outcome than a note that reads slightly oddly.
    const { svg } = buildSvg(
      { geometry: geometryOf([0, 0, 1, 1], [VERB_LINE]), width: 10, height: 10, background: null },
      { provenanceNote: "Traced on the device -- from photo.jpg" }
    );
    const body = svg.slice(svg.indexOf("<!--") + 4, svg.indexOf("-->"));
    expect(body).toContain("Traced on the device");
    // The run of hyphens is collapsed, so the comment cannot terminate itself early.
    expect(body).not.toContain("--");
  });

  test("NOTHING IDENTIFYING reaches the file unless the caller put it there", () => {
    // This file is uploaded to a shared archive and handed on. A comment naming the person who traced
    // it would be an identity leak by a path nobody would think to audit.
    const { svg } = buildSvg({
      geometry: geometryOf([0, 0, 1, 1], [VERB_LINE]),
      width: 10,
      height: 10,
      background: null
    });
    expect(svg).not.toContain("<!--");
    expect(svg).not.toMatch(/\d{4}-\d{2}-\d{2}/);
  });

  test("a style index pointing outside the table draws a black hairline rather than nothing", () => {
    const g: FlatGeometry = {
      ...geometryOf([0, 0, 1, 1], [VERB_LINE]),
      styleIndex: new Uint32Array([7])
    };
    const { svg } = buildSvg({ geometry: g, width: 10, height: 10, background: null });
    expect(svg).toContain('stroke="#000000"');
  });
});

test.describe("the shape ceiling is reported, never silent", () => {
  test("under the cap there is no sentence", () => {
    expect(truncationNoteFor(10, 10)).toBeNull();
    expect(truncationNoteFor(5, MAX_SHAPES_PER_FILE)).toBeNull();
  });

  test("over it the sentence says both numbers and what to do", () => {
    const note = truncationNoteFor(250000, MAX_SHAPES_PER_FILE);
    // `en-IN` GROUPING, NOT `en-US`. Two hundred and fifty thousand is "2,50,000" to a reader in the
    // place this application is used, and the rest of this app formats counts the same way — a lakh
    // grouped in thousands is the kind of small wrongness that makes a sentence read as machine output.
    expect(note).toContain((250000).toLocaleString("en-IN"));
    expect(note).toContain((MAX_SHAPES_PER_FILE).toLocaleString("en-IN"));
    expect(note).toContain("2,50,000");
    // Actionable rather than merely apologetic: the two controls that actually reduce the count.
    expect(note).toContain("Minimum speck");
    expect(note).toContain("Simplify");
  });
});

test.describe("derivedFileName keeps the source's own name", () => {
  test("the stem survives and the suffix is added before the extension", () => {
    expect(derivedFileName("block-print.jpg", "svg")).toBe("block-print-line-art.svg");
    expect(derivedFileName("block-print.jpg", "png", "traced")).toBe("block-print-traced.png");
  });

  test("punctuation a phone gallery allows and a filesystem does not is replaced", () => {
    // Windows, S3 keys and a .docx relationship id each dislike a different subset, so the
    // intersection is what survives.
    expect(derivedFileName("photo:1*?.jpg", "svg")).toBe("photo_1_-line-art.svg");
  });

  test("a source with no usable name gets a stem rather than an empty one", () => {
    expect(derivedFileName("", "svg")).toBe("image-line-art.svg");
    expect(derivedFileName(".jpg", "svg")).toBe("image-line-art.svg");
  });

  test("an absurdly long name is cut before it reaches a filesystem", () => {
    const long = `${"a".repeat(300)}.jpg`;
    const name = derivedFileName(long, "svg");
    expect(name.length).toBeLessThanOrEqual(80 + "-line-art.svg".length);
  });

  test("the suffix goes through the same deny-list as the stem", () => {
    // A name-building function that trusts one of its inputs and sanitises the other is a function
    // whose next caller has to know which is which.
    expect(derivedFileName("a.jpg", "svg", "line/art")).toBe("a-line_art.svg");
  });
});
