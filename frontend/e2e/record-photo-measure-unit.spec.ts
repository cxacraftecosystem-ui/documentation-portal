import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  forgetAcceptance,
  measurementMethodsFor,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance
} from "@/components/forms/measurementMethods";
import {
  COLUMN_DECIMALS,
  DEFAULT_GRID_PITCH_ID,
  GRID_PITCHES,
  gridPitchById,
  gridRectangle,
  gridSpan,
  proposalFor,
  statedLength
} from "@/components/media/recordMeasure";
import type { MeasurementMethodMarker } from "@/lib/media";
import { measureBySameScale } from "@/lib/photoMeasure";

test.describe("counting grid squares into a reference length", () => {
  test("N squares of the kit's own sheet is N inches, and the arithmetic is read back", () => {
    const inch = gridPitchById("IN_1");
    const six = gridSpan("6", inch);
    expect(six.ok).toBe(true);
    if (!six.ok) return;
    expect(six.length).toBe(6);
    expect(six.unit).toBe("in");
    expect(six.sentence).toBe("6 squares × 1 in = 6 in");
  });

  test("one square says “square”, not “squares”", () => {
    const one = gridSpan("1", gridPitchById("IN_1"));
    expect(one.ok && one.sentence).toBe("1 square × 1 in = 1 in");
  });

  test("a metric sheet is 2.54 times a different answer, which is why the sheet is a stated choice", () => {
    expect(gridSpan("6", gridPitchById("CM_1"))).toMatchObject({ ok: true, length: 6, unit: "cm" });
    expect(gridSpan("6", gridPitchById("MM_5"))).toMatchObject({ ok: true, length: 30, unit: "mm" });
    expect(DEFAULT_GRID_PITCH_ID).toBe("IN_1");
    expect(GRID_PITCHES[0].id).toBe("IN_1");
  });

  test("an unknown pitch id falls back to the kit's sheet rather than throwing", () => {
    expect(gridPitchById("NOT_A_SHEET").id).toBe("IN_1");
  });

  test("every refusal is a different sentence naming what is missing", () => {
    const inch = gridPitchById("IN_1");
    const blank = gridSpan("   ", inch);
    const nonsense = gridSpan("six", inch);
    const zero = gridSpan("0", inch);
    const negative = gridSpan("-2", inch);
    expect(blank.ok).toBe(false);
    expect(nonsense.ok).toBe(false);
    expect(zero.ok).toBe(false);
    expect(negative.ok).toBe(false);
    const reasons = [blank, nonsense, zero, negative].map((entry) => (entry.ok ? "" : entry.reason));
    expect(new Set(reasons).size, "four different failures, three different sentences").toBe(3);
    expect(reasons[0]).toContain("how many squares");
    expect(reasons[1]).toContain("“six”");
    expect(reasons[2]).toBe(reasons[3]);
  });

  test("a rectangle of squares names which edge failed, because the two are not interchangeable", () => {
    const inch = gridPitchById("IN_1");
    const good = gridRectangle("8", "5", inch);
    expect(good).toMatchObject({ ok: true, width: 8, height: 5, unit: "in" });

    const missingWidth = gridRectangle("", "5", inch);
    const missingHeight = gridRectangle("8", "", inch);
    expect(missingWidth.ok).toBe(false);
    expect(missingHeight.ok).toBe(false);
    expect(!missingWidth.ok && missingWidth.reason).toContain("corner 1 → 2");
    expect(!missingHeight.ok && missingHeight.reason).toContain("corner 2 → 3");
  });

  test("a stated length is the same shape of answer, for a photograph with no grid in it", () => {
    expect(statedLength("300", "mm")).toMatchObject({ ok: true, length: 300, unit: "mm" });
    expect(statedLength("", "mm").ok).toBe(false);
    expect(statedLength("0", "mm").ok).toBe(false);
  });
});

test("an object twice the length of a six-square reference measures 12 inches", () => {
  const reference = gridSpan("6", gridPitchById("IN_1"));
  expect(reference.ok).toBe(true);
  if (!reference.ok) return;

  const result = measureBySameScale({
    reference: { from: { x: 0, y: 0 }, to: { x: 600, y: 0 }, length: reference.length, unit: reference.unit },
    target: { from: { x: 0, y: 400 }, to: { x: 1200, y: 400 } },
    markSigmaPx: 2
  });
  expect(result.ok).toBe(true);
  if (!result.ok) return;
  expect(result.value).toBeCloseTo(12, 9);
  expect(result.unit).toBe("in");

  const proposal = proposalFor(result.value, result.uncertainty, result.unit, "in");
  expect(proposal.ok).toBe(true);
  if (!proposal.ok) return;
  expect(Number(proposal.text)).toBeCloseTo(12, 2);
  expect(Number(proposal.doubt)).toBeGreaterThan(0);
});

test("a millimetre reference still proposes inches, because the COLUMN's unit is what a box stores", () => {
  const proposal = proposalFor(254, 0.5, "mm", "in");
  expect(proposal.ok).toBe(true);
  if (!proposal.ok) return;
  expect(Number(proposal.text)).toBeCloseTo(10, 6);
  expect(proposal.unit).toBe("in");
});

test.describe("getting a measured value into a Decimal(10, 2) box", () => {
  test("a value quoted finer than the column holds is rounded to it, and says so", () => {
    const fine = proposalFor(4.2137, 0.0008, "in", "in");
    expect(fine.ok).toBe(true);
    if (!fine.ok) return;
    expect(fine.text).toBe("4.21");
    expect(fine.decimals).toBeLessThanOrEqual(COLUMN_DECIMALS);
    expect(fine.clamped).toBe(true);
  });

  test("a coarse measurement is NOT padded out to two decimals it has not earned", () => {
    const coarse = proposalFor(5.27, 0.4, "in", "in");
    expect(coarse.ok).toBe(true);
    if (!coarse.ok) return;
    expect(coarse.text).toBe("5.3");
    expect(coarse.decimals).toBe(1);
    expect(coarse.clamped).toBe(false);
  });

  test("the value is rounded ONCE from the original, not through its own intermediate", () => {
    const boundary = proposalFor(4.2149, 0.0008, "in", "in");
    expect(boundary.ok && boundary.text).toBe("4.21");
  });

  test("the error bar shown beside the value is rounded UP, never down", () => {
    const proposal = proposalFor(3.5, 0.111, "in", "in");
    expect(proposal.ok).toBe(true);
    if (!proposal.ok) return;
    expect(Number(proposal.doubt)).toBeGreaterThanOrEqual(0.111);
  });

  test("a value that rounds to zero is refused rather than stored", () => {
    const tiny = proposalFor(0.001, 0.0002, "in", "in");
    expect(tiny.ok).toBe(false);
    if (tiny.ok) return;
    expect(tiny.reason).toContain("Too small for this field");
  });

  test("a unit this cannot convert is a named refusal, not a missing button", () => {
    const bad = proposalFor(10, 1, "in", "furlong" as never);
    expect(bad.ok).toBe(false);
  });
});

const ROOT = join(__dirname, "..");

const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").replace(/\r\n/g, "\n");

const PANEL = "components/media/RecordPhotoMeasure.tsx";
const PRODUCT_FORM = "components/forms/ProductForm.tsx";
const TOOL_FORM = "components/forms/ToolForm.tsx";

test("the panel reaches a form field only from a button press", () => {
  const source = read(PANEL);
  const calls = source.match(/onPropose\(/g) ?? [];
  expect(calls.length, "one call, and one only").toBe(1);
  expect(source).toMatch(/onClick=\{\(\) => onPropose\(column\.key, proposal\.text, methodMarker\(result\)\)\}/);
  const effects = source.match(/useEffect\([\s\S]*?\n  \}, \[[^\]]*\]\);/g) ?? [];
  for (const effect of effects) {
    expect(effect, "no effect writes a dimension").not.toContain("onPropose");
  }
});

for (const [name, path, keys] of [
  ["ProductForm", PRODUCT_FORM, ["lengthInches", "breadthInches", "heightInches"]],
  ["ToolForm", TOOL_FORM, ["lengthInches", "breadthInches", "heightInches"]]
] as const) {
  test(`${name} mounts the deterministic panel ABOVE the vision-model route`, () => {
    const source = read(path);
    const panel = source.indexOf("<RecordPhotoMeasure");
    const grid = source.indexOf("<GridMeasurement");
    expect(panel, "the deterministic panel is mounted").toBeGreaterThan(-1);
    expect(grid, "the vision route is KEPT, not deleted").toBeGreaterThan(-1);
    expect(panel, "deterministic first").toBeLessThan(grid);
  });

  test(`${name} labels the vision route as an estimate that needs a connection`, () => {
    const source = read(path);
    const wrapper = source.slice(source.indexOf("Estimate with AI"), source.indexOf("<GridMeasurement"));
    expect(wrapper, "the fallback is named as an estimate").toContain("<strong>estimate</strong>");
    expect(wrapper, "and as needing a connection").toContain("Needs a connection");
  });

  test(`${name} proposes into the columns this record actually has`, () => {
    const source = read(path);
    const block = source.slice(source.indexOf("const MEASURE_COLUMNS"), source.indexOf("];", source.indexOf("const MEASURE_COLUMNS")));
    for (const key of keys) expect(block, `${key} is a destination`).toContain(`key: "${key}"`);
    if (name === "ToolForm") {
      expect(block, "the unit-less `height` column is not a proposal destination").not.toContain(
        'key: "height"'
      );
      expect(block, "and no entry needs a note about its unit").not.toContain("note:");
    }
  });

  test(`${name} sends the measurementMethods marker, and only through the one gate`, () => {
    const source = read(path);
    const payloads = read("lib/recordPayloads.ts");
    const builder = name === "ToolForm" ? "toolBody" : "productBody";
    const start = payloads.indexOf(`export function ${builder}(`);
    const payload = payloads.slice(start, payloads.indexOf("\n}\n", start));
    expect(source, "the form builds its body through the shared builder").toContain(`const payload = ${builder}(form, {`);
    expect(payload, "the save body carries the marker").toMatch(/^\s*measurementMethods: measurementMethodsFor\(/m);
    expect(payload.match(/measurementMethodsFor\(/g)?.length, "one call, and one only").toBe(1);
    expect(source, "the form never calls it itself").not.toMatch(/measurementMethodsFor\(/);
    expect(source, "the marker object is never hand-built in the form").not.toContain("measurementMethods: {");
  });

  test(`${name} keys the marker off the same box the number was written into`, () => {
    const source = read(path);
    expect(source, "the panel files the acceptance under the key it just wrote").toContain(
      "rememberAcceptance(current, key, text, method)"
    );
    expect(source, "the typing handler forgets the acceptance").toContain("forgetAcceptance(current, key)");
    const factory = name === "ToolForm" ? "typeInches" : "typeInto";
    for (const key of keys) {
      expect(source, `${key} is wired through it`).toMatch(
        new RegExp(`onChange=\\{${factory}\\(set\\w+, "${key}"(, set\\w+)?\\)\\}`)
      );
    }
    if (name === "ToolForm") {
      expect(source, "the centimetre height column never records an acceptance").not.toMatch(
        /rememberAcceptance\(current, "height"[,)]/
      );
      expect(source, "nor the centimetre width").not.toMatch(/rememberAcceptance\(current, "width"[,)]/);
      expect(source, "and neither is wired through the forgetting handler either").not.toMatch(
        /type(Into|Inches)\(set\w+, "(height|width)"[,)]/
      );
      expect(source, "the centimetre boxes go through the partner-writing factory").toContain(
        "onChange={typeCm(setHeight, setHeightInches)}"
      );
      expect(source, "both of them").toContain("onChange={typeCm(setWidth, setBreadth)}");
    }
  });

  test(`${name} keeps the photograph the measurement was read off`, () => {
    const source = read(path);
    expect(source, "the panel reports its photograph up").toContain("onPhotoChange={(photo) => {");
    expect(source, "and the queued save carries it").toMatch(/\.\.\.\(measurePhoto\s*\n?\s*\?\s*\[/);
    expect(source, "and the online save uploads it").toMatch(/if \(measurePhoto\) \{[\s\S]{0,200}uploadMediaFile\(\{/);
    expect(source, "and its EXIF is read with the others").toContain("measurePhoto?.file");
  });
}

const VISION: MeasurementMethodMarker = {
  method: "VISION_MODEL",
  provider: "gemini",
  modelId: "gemini-2.5-flash-lite",
  selfReportedConfidence: 0.8
};

const GEOMETRY: MeasurementMethodMarker = { method: "PHOTO_GEOMETRY", technique: "SCALE" };

test.describe("what a save may still claim about a dimension", () => {
  test("an accepted reading left alone travels with its marker", () => {
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(accepted, { lengthInches: "6.25" })).toEqual({ lengthInches: GEOMETRY });
  });

  test("typed over after acceptance, the marker is dropped — UNRECORDED is the honest answer", () => {
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(accepted, { lengthInches: "7" })).toBeUndefined();
  });

  test("the check is on the VALUE, not on the event that changed it", () => {
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(accepted, { lengthInches: " 6.25" })).toBeUndefined();
    expect(measurementMethodsFor(accepted, { lengthInches: "6.250" })).toBeUndefined();
    expect(measurementMethodsFor(accepted, { lengthInches: "6.25" })).toEqual({ lengthInches: GEOMETRY });
  });

  test("cleared after acceptance, the marker is dropped — and the save is not a 422", () => {
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "heightInches", "3.50", GEOMETRY);
    expect(measurementMethodsFor(accepted, { heightInches: "" })).toBeUndefined();
    expect(measurementMethodsFor(accepted, { heightInches: "   " })).toBeUndefined();
  });

  test("one dimension edited does not drag the others' markers down with it", () => {
    let accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", VISION);
    accepted = rememberAcceptance(accepted, "breadthInches", "4.00", VISION);
    expect(measurementMethodsFor(accepted, { lengthInches: "6.25", breadthInches: "9" })).toEqual({
      lengthInches: VISION
    });
  });

  test("a marker naming a column outside DIMENSION_FIELDS is never recorded at all", () => {
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "height", "12", GEOMETRY);
    expect(accepted).toEqual({});
    expect(measurementMethodsFor(accepted, { height: "12" } as Record<string, string>)).toBeUndefined();
  });

  test("a reading the server sent no marker for records nothing, and forgets any older one", () => {
    let accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    accepted = rememberAcceptance(accepted, "lengthInches", "8.00", null);
    expect(accepted).toEqual({});
    expect(measurementMethodsFor(accepted, { lengthInches: "8.00" })).toBeUndefined();
  });

  test("a typing researcher's keystroke forgets the acceptance, identical digits included", () => {
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(forgetAcceptance(accepted, "lengthInches"), { lengthInches: "6.25" })).toBeUndefined();
    expect(forgetAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches")).toBe(NO_ACCEPTED_MEASUREMENTS);
    expect(forgetAcceptance(accepted, "breadthInches")).toBe(accepted);
  });

  test("nothing is ever synthesised as TYPED", () => {
    const typedByHand = measurementMethodsFor(NO_ACCEPTED_MEASUREMENTS, {
      lengthInches: "6.25",
      breadthInches: "4",
      heightInches: "3"
    });
    expect(typedByHand).toBeUndefined();
    expect(JSON.stringify(typedByHand ?? null)).not.toContain("TYPED");
  });

  test("nothing accepted sends no key at all, not an empty object and not a null", () => {
    expect(measurementMethodsFor(NO_ACCEPTED_MEASUREMENTS, { lengthInches: "6.25" })).toBeUndefined();
    const body = JSON.stringify({
      lengthInches: 6.25,
      measurementMethods: measurementMethodsFor(NO_ACCEPTED_MEASUREMENTS, { lengthInches: "6.25" })
    });
    expect(body).toBe('{"lengthInches":6.25}');
  });
});
