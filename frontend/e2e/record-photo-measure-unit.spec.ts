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

/**
 * **THE DETERMINISTIC MEASUREMENT ON THE PRODUCT AND TOOL FORMS.**
 *
 * ── WHAT IS PINNED HERE AND WHAT DELIBERATELY IS NOT ─────────────────────────────────────────────
 *
 * The projective geometry has its own suite (`e2e/photo-measure-unit.spec.ts`) against
 * `lib/photoMeasure.ts`, which is the single authority both panels call, and nothing here re-tests
 * it. What this file covers is the layer the record forms added on top and that nothing else could
 * catch: turning "I marked across six squares" into a reference length, turning a measured value
 * into a number a `Decimal(10, 2)` column will actually accept, and deciding WHICH ACCEPTED
 * DIMENSIONS MAY STILL SAY A MACHINE PRODUCED THEM when the form is saved.
 *
 * There is no React renderer in devDependencies, so the pure halves live outside the components and
 * are called directly:
 *
 * * `components/media/recordMeasure.ts` — squares into a reference length, and a measured value into
 *   a number a `Decimal(10, 2)` column will accept;
 * * `components/forms/measurementMethods.ts` — the marker's staleness rule. That judgement is the one
 *   least suited to being looked at on a screen: a marker left standing over a number somebody typed
 *   over afterwards renders identically to a correct one, and is only ever read a year later by
 *   somebody costing a production run off the record it lied to.
 *
 * The judgements that cannot be expressed that way — "nothing is written into a form field until a
 * person presses a button", and "the marker is filed under the box the number went into" — are
 * asserted against the SOURCE, because they are rules about where a call site is, and a rule about
 * placement is exactly what a count or a mock cannot hold.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The reference: N squares of a known sheet
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("counting grid squares into a reference length", () => {
  test("N squares of the kit's own sheet is N inches, and the arithmetic is read back", () => {
    // THE WHOLE INSIGHT THIS FEATURE RESTS ON, in one assertion: the 1-inch grid sheet the researcher
    // was already photographing the object on IS a perfect deterministic reference, so the vision
    // model was never needed to read a dimension off that photograph.
    const inch = gridPitchById("IN_1");
    const six = gridSpan("6", inch);
    expect(six.ok).toBe(true);
    if (!six.ok) return;
    expect(six.length).toBe(6);
    expect(six.unit).toBe("in");
    // The sentence is not decoration. Miscounting the squares is the one mistake on this path that
    // nothing downstream could ever detect, and printing the multiplication beside the paper is what
    // makes it visible while the researcher is still looking at both.
    expect(six.sentence).toBe("6 squares × 1 in = 6 in");
  });

  test("one square says “square”, not “squares”", () => {
    const one = gridSpan("1", gridPitchById("IN_1"));
    expect(one.ok && one.sentence).toBe("1 square × 1 in = 1 in");
  });

  test("a metric sheet is 2.54 times a different answer, which is why the sheet is a stated choice", () => {
    /*
      THE DEFECT THIS PREVENTS. Ordinary graph paper is metric and lives in the same drawer as the
      kit's sheet. Six centimetre squares read as six inches is a record 2.54× too big — plausible,
      silent, and multiplied into a cost sheet. The pitch is therefore chosen rather than assumed,
      and the two answers below are why "it's obviously an inch grid" is not a safe default to hide.
    */
    expect(gridSpan("6", gridPitchById("CM_1"))).toMatchObject({ ok: true, length: 6, unit: "cm" });
    expect(gridSpan("6", gridPitchById("MM_5"))).toMatchObject({ ok: true, length: 30, unit: "mm" });
    // …but the kit's own sheet is what a researcher meets first, because it is the one shipped with it.
    expect(DEFAULT_GRID_PITCH_ID).toBe("IN_1");
    expect(GRID_PITCHES[0].id).toBe("IN_1");
  });

  test("an unknown pitch id falls back to the kit's sheet rather than throwing", () => {
    // The id is only ever a string off a dropdown, but a stale saved value or a renamed constant
    // must degrade to the programme's sheet, not to a crash inside a form somebody is filling in.
    expect(gridPitchById("NOT_A_SHEET").id).toBe("IN_1");
  });

  test("every refusal is a different sentence naming what is missing", () => {
    /*
      DISTINGUISHABLE AND ACTIONABLE, which a bare null is not. The squares box is the one input on
      this panel a researcher can leave blank without noticing — the marks are on the photograph and
      look finished, so an empty box simply means no answer appears and the control reads as broken.
    */
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
    expect(reasons[0]).toContain("how many grid squares");
    expect(reasons[1]).toContain("“six”");
    // Zero and a negative are the same mistake said the same way, and that is the deliberate pairing.
    expect(reasons[2]).toBe(reasons[3]);
  });

  test("a rectangle of squares names which edge failed, because the two are not interchangeable", () => {
    const inch = gridPitchById("IN_1");
    const good = gridRectangle("8", "5", inch);
    expect(good).toMatchObject({ ok: true, width: 8, height: 5, unit: "in" });

    // `measureByRectification` reads corner 1 → 2 as the WIDTH edge and 2 → 3 as the height. Getting
    // the pair the wrong way round rectifies a real rectangle that is not the one photographed, so a
    // refusal that did not say which edge it was about would leave the researcher guessing at the
    // exact moment the guess is the defect.
    const missingWidth = gridRectangle("", "5", inch);
    const missingHeight = gridRectangle("8", "", inch);
    expect(missingWidth.ok).toBe(false);
    expect(missingHeight.ok).toBe(false);
    expect(!missingWidth.ok && missingWidth.reason).toContain("corner 1 → 2");
    expect(!missingHeight.ok && missingHeight.reason).toContain("corner 2 → 3");
  });

  test("a stated length is the same shape of answer, for a photograph with no grid in it", () => {
    // Not every record photograph has the sheet in it, so the general reference stays available.
    expect(statedLength("300", "mm")).toMatchObject({ ok: true, length: 300, unit: "mm" });
    expect(statedLength("", "mm").ok).toBe(false);
    expect(statedLength("0", "mm").ok).toBe(false);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * Reference → value, end to end through the geometry
 * ──────────────────────────────────────────────────────────────────────────── */

test("an object twice the length of a six-square reference measures 12 inches", () => {
  /*
    THE ARITHMETIC THE WHOLE FEATURE IS, ASSERTED THROUGH THE REAL GEOMETRY RATHER THAN AROUND IT.
    The reference spans 6 squares — 6 inches — and 600 image pixels. The object spans 1200 pixels in
    the same plane. Twice the pixels at the same scale is twice the length: 12 inches. Constructed
    from the answer, so a module that returned the reference unchanged, or that dropped the unit
    conversion, cannot pass it.
  */
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

  // And the proposal that reaches the box is that number in the column's unit, with an error bar the
  // researcher was shown before pressing anything.
  const proposal = proposalFor(result.value, result.uncertainty, result.unit, "in");
  expect(proposal.ok).toBe(true);
  if (!proposal.ok) return;
  expect(Number(proposal.text)).toBeCloseTo(12, 2);
  expect(Number(proposal.doubt)).toBeGreaterThan(0);
});

test("a millimetre reference still proposes inches, because the COLUMN's unit is what a box stores", () => {
  /*
    A steel rule is 300 mm and `lengthInches` is inches. `photoMeasure` answers in the reference's
    own unit, so the conversion is this layer's job — and it must happen BEFORE the rounding, or the
    value is rounded in millimetres and the rounding error converted up with everything else.

    254 mm is exactly 10 inches, chosen so the assertion cannot pass on a near miss.
  */
  const proposal = proposalFor(254, 0.5, "mm", "in");
  expect(proposal.ok).toBe(true);
  if (!proposal.ok) return;
  expect(Number(proposal.text)).toBeCloseTo(10, 6);
  expect(proposal.unit).toBe("in");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The rounding, which is the part that reaches the database
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("getting a measured value into a Decimal(10, 2) box", () => {
  test("a value quoted finer than the column holds is rounded to it, and says so", () => {
    /*
      ── THE DEFECT THIS EXISTS TO PREVENT, WHICH IS A BROWSER REFUSAL AND NOT A WRONG NUMBER ──
      `roundToUncertainty` quotes a value to the decimal place of its own error bar, which for a
      carefully zoomed mark is routinely three or four decimals. Every dimension box on both forms is
      `type="number" step="0.01"` and neither form carries `noValidate`, so the browser's own
      constraint validation REFUSES a value off the step ladder: pressing Save on an accepted 4.213
      raises "the two nearest valid values are 4.21 and 4.22" on a box the researcher never typed in,
      over a number they were just told was measured. Underneath it, `Decimal(10, 2)` would have
      dropped the third decimal anyway with nobody told.
    */
    const fine = proposalFor(4.2137, 0.0008, "in", "in");
    expect(fine.ok).toBe(true);
    if (!fine.ok) return;
    expect(fine.text).toBe("4.21");
    expect(fine.decimals).toBeLessThanOrEqual(COLUMN_DECIMALS);
    // And the researcher is told a digit was given back — printed only when it happened, because a
    // note under every proposal is noise that trains a reader past the row where it matters.
    expect(fine.clamped).toBe(true);
  });

  test("a coarse measurement is NOT padded out to two decimals it has not earned", () => {
    /*
      The clamp is a ceiling, never a floor. An error bar of ±0.4 in supports one decimal place and
      no more, and printing "5.30" instead of "5.3" would claim a hundredth of an inch that nothing
      in the measurement supports — which is the failure this whole panel exists to refuse, in the
      one direction that looks tidier.
    */
    const coarse = proposalFor(5.27, 0.4, "in", "in");
    expect(coarse.ok).toBe(true);
    if (!coarse.ok) return;
    expect(coarse.text).toBe("5.3");
    expect(coarse.decimals).toBe(1);
    expect(coarse.clamped).toBe(false);
  });

  test("the value is rounded ONCE from the original, not through its own intermediate", () => {
    /*
      4.2149 rounded to three places is 4.215, and 4.215 rounded to two is 4.22 — but 4.2149 rounded
      once to two places is 4.21. Double rounding is a defect that only ever shows on the values
      sitting exactly on a boundary, which is precisely where nobody looks, and the obvious
      implementation (clamp `roundToUncertainty`'s OUTPUT) has it.
    */
    const boundary = proposalFor(4.2149, 0.0008, "in", "in");
    expect(boundary.ok && boundary.text).toBe("4.21");
  });

  test("the error bar shown beside the value is rounded UP, never down", () => {
    // An error bar that is a little too generous is honest; one that is quietly too flattering is
    // the same lie as an over-precise value, wearing the other hat.
    const proposal = proposalFor(3.5, 0.111, "in", "in");
    expect(proposal.ok).toBe(true);
    if (!proposal.ok) return;
    expect(Number(proposal.doubt)).toBeGreaterThanOrEqual(0.111);
  });

  test("a value that rounds to zero is refused rather than stored", () => {
    /*
      Zero in a dimension column does not read as "under five thousandths of an inch". It reads as a
      measurement of nothing, and it is printed as `0.00` in a report's dimensions cell beside two
      real numbers. A needle's thickness on a tool record is the case that gets here.
    */
    const tiny = proposalFor(0.001, 0.0002, "in", "in");
    expect(tiny.ok).toBe(false);
    if (tiny.ok) return;
    expect(tiny.reason).toContain("rounds to zero");
  });

  test("a unit this cannot convert is a named refusal, not a missing button", () => {
    const bad = proposalFor(10, 1, "in", "furlong" as never);
    expect(bad.ok).toBe(false);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * Nothing is written until a person accepts it
 * ──────────────────────────────────────────────────────────────────────────── */

const ROOT = join(__dirname, "..");

/**
 * Read a source file with its line endings normalised.
 *
 * `.replace(/\r\n/g, "\n")` and not a bare `readFileSync`, exactly as `record-parity-fields-unit.spec.ts`
 * does: `core.autocrlf` is true on the machines this is developed on, so the working tree is CRLF
 * while the repository stores LF, and every placement assertion below is a regex or an `indexOf` over
 * the raw text. Without it the tests pass on CI and fail on the machine the code was written on,
 * which reads as a flaky suite rather than as a line-ending problem.
 */
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").replace(/\r\n/g, "\n");

const PANEL = "components/media/RecordPhotoMeasure.tsx";
const PRODUCT_FORM = "components/forms/ProductForm.tsx";
const TOOL_FORM = "components/forms/ToolForm.tsx";

test("the panel reaches a form field only from a button press", () => {
  /*
    ── THE STANDING RULE, ASSERTED WHERE IT CAN ACTUALLY BE BROKEN ─────────────────────────────
    A machine-produced value is a PROPOSAL, never a silent write, and `records.merge_field_provenance`
    says exactly what a silent one costs: every changed field is stamped with the `{by, byName, at}`
    of whoever pressed Save, so a number that filled itself in is stored asserting that a named human
    measured it. The record does not merely fail to say a machine produced it — it positively asserts
    the opposite, in somebody's name, on a government record.

    ASSERTED BY PLACEMENT AND NOT BY A COUNT: what matters is that the ONLY reference to `onPropose`
    inside the panel sits in an `onClick`. A test that counted call sites would pass with the call
    moved into an effect, and an effect is exactly how this rule gets broken by accident — a
    `useEffect` that "fills the field once the measurement is ready" reads like a convenience.
  */
  const source = read(PANEL);
  const calls = source.match(/onPropose\(/g) ?? [];
  expect(calls.length, "one call, and one only").toBe(1);
  // THE METHOD IS COMPOSED IN THE SAME EXPRESSION AS THE FIGURE, and that is asserted rather than
  // merely allowed: `methodMarker(result)` reads the SAME `result` the button's number came out of,
  // so the technique it names can never belong to a different measurement than the value beside it.
  expect(source).toMatch(/onClick=\{\(\) => onPropose\(column\.key, proposal\.text, methodMarker\(result\)\)\}/);
  // Belt: no effect in this file may name it.
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
    /*
      ORDER IS THE DECISION, not decoration. The deterministic on-device measurement is the PRIMARY
      path and the vision model is a labelled fallback; whichever control a researcher meets first is
      the one they learn, so a panel added below `GridMeasurement` would satisfy the letter of that
      and none of it.
    */
    const source = read(path);
    const panel = source.indexOf("<RecordPhotoMeasure");
    const grid = source.indexOf("<GridMeasurement");
    expect(panel, "the deterministic panel is mounted").toBeGreaterThan(-1);
    expect(grid, "the vision route is KEPT, not deleted").toBeGreaterThan(-1);
    expect(panel, "deterministic first").toBeLessThan(grid);
  });

  test(`${name} labels the vision route as an estimate that needs a connection`, () => {
    /*
      Two claims, both of which a researcher has to be able to read BEFORE choosing: it is an estimate
      from a model rather than a measurement, and it is the one measurement control in the app that
      cannot work with no signal — `POST /media/analyze-measurement` has no queue, no outbox and no
      retry behind it.
    */
    const source = read(path);
    const wrapper = source.slice(source.indexOf("If you cannot mark it"), source.indexOf("<GridMeasurement"));
    expect(wrapper, "the fallback is named as an estimate").toContain("<strong>estimate</strong>");
    expect(wrapper, "and as needing a connection").toContain("Needs a connection");
  });

  test(`${name} proposes into the columns this record actually has`, () => {
    /*
      THE COLUMNS ARE READ OFF THE SCHEMA, NOT GUESSED. Both records store `lengthInches` /
      `breadthInches` / `heightInches`, and every one of the six says its unit in its own name. A
      panel proposing into a key the record does not have would set React state that the payload
      never reads: the researcher sees the box fill, presses Save, and the dimension is simply not
      there.

      THE TOOL FORM'S SECOND HEIGHT BOX IS THE CASE THIS HOLDS SHUT. `ToolDocumentation` also carries
      a plain unit-less `height`, and it is NOT in `measurement_provenance.DIMENSION_FIELDS` — so a
      reading accepted into it is recorded as having no method at all, and a marker naming it is a 422
      on the whole save. It stays on the form and still saves what people typed into it; what it must
      no longer be is a MACHINE destination.
      Re-check: `grep -n heightInches backend/prisma/schema.prisma backend/app/schemas/records.py`.
    */
    const source = read(path);
    const block = source.slice(source.indexOf("const MEASURE_COLUMNS"), source.indexOf("];", source.indexOf("const MEASURE_COLUMNS")));
    for (const key of keys) expect(block, `${key} is a destination`).toContain(`key: "${key}"`);
    if (name === "ToolForm") {
      // `key: "heightInches"` does not contain `key: "height"` — the closing quote is what separates
      // them, so this is an exact test for the unit-less column and not a prefix match on the new one.
      expect(block, "the unit-less `height` column is not a proposal destination").not.toContain(
        'key: "height"'
      );
      expect(block, "and no entry needs a note about its unit").not.toContain("note:");
    }
  });

  test(`${name} sends the measurementMethods marker, and only through the one gate`, () => {
    /*
      ── WHAT IS PINNED: THAT THERE IS EXACTLY ONE GATE IN FRONT OF THE KEY ──────────────────────
      A marker is a CLAIM about how a number was obtained, so the failure mode is a marker that
      outlives the number it describes — `PHOTO_GEOMETRY` standing over a figure somebody typed over
      afterwards, which is a false statement in a record nobody can check and strictly worse than the
      `UNRECORDED` an absent marker earns. The one thing a source read can hold shut is that the
      payload's value comes from `measurementMethodsFor` and never from a literal assembled in the
      form, because that function is where the staleness rule lives and is the half these tests can
      actually drive.

      THE SERVER HALF HAS TO LAND WITH IT. `APIModel` is `ConfigDict(extra="forbid")`, so this key is
      a 422 on the whole save — which `saveOrQueue` will not queue — until the schemas declare it:

          grep -n "MARKER_BODY_KEY" backend/app/services/access.py
          grep -n "measurementMethods" backend/app/schemas/records.py
    */
    const source = read(path);
    const payload = source.slice(source.indexOf("const payload = {"), source.indexOf("// Offline this queues"));
    expect(payload, "the save body carries the marker").toMatch(/^\s*measurementMethods: measurementMethodsFor\(/m);
    // No second door: the key's value is a call to the gate and never a literal assembled here, and
    // there is exactly one such call per form — one create/update body.
    expect(source.match(/measurementMethodsFor\(/g)?.length, "one call, and one only").toBe(1);
    expect(source, "the marker object is never hand-built in the form").not.toContain("measurementMethods: {");
  });

  test(`${name} keys the marker off the same box the number was written into`, () => {
    /*
      THE MISMATCH THIS CATCHES IS SILENT AND UNRECOVERABLE. `rememberAcceptance` takes the column
      name and the text separately, so a hand-written key — or the tool form's `height` in place of
      `heightInches` — would file the marker under a dimension the reading never went into. The
      server would then either stamp the wrong column or, for a key outside `DIMENSION_FIELDS`,
      refuse the whole save with a 422 that `saveOrQueue` will not queue.

      The deterministic panel's call passes `key` through verbatim, which is why it is asserted as a
      variable rather than as a name; the grid route knows its own destinations and names them.
    */
    const source = read(path);
    expect(source, "the panel files the acceptance under the key it just wrote").toContain(
      "rememberAcceptance(current, key, text, method)"
    );
    // And every one of the three boxes forgets its acceptance when a person types in it, through the
    // one handler that does both — see each form's factory for why it is a factory.
    expect(source, "the typing handler forgets the acceptance").toContain("forgetAcceptance(current, key)");
    /*
      THE TWO FORMS NAME THAT FACTORY DIFFERENTLY NOW, AND THE DIFFERENCE IS A REAL ONE.

      `ProductForm` keeps `typeInto(setter, column)`: its three dimensions are inches and nothing
      else. `ToolForm`'s is `typeInches(setter, column, setPartner?)`, because two of its three inch
      boxes have a CENTIMETRE partner they fill as the researcher types (`heightInches` ↔ `height`,
      `breadthInches` ↔ `width`) and `lengthInches` deliberately has none. The optional third
      argument is why this pattern admits one: a regex demanding `)}` right after the column name
      would go red on the two paired boxes and say nothing about what was actually wrong.

      What is held shut here is unchanged and is the point of the test: every dimension box goes
      through ITS FORM'S factory, keyed by the column it writes.
    */
    const factory = name === "ToolForm" ? "typeInches" : "typeInto";
    for (const key of keys) {
      expect(source, `${key} is wired through it`).toMatch(
        new RegExp(`onChange=\\{${factory}\\(set\\w+, "${key}"(, set\\w+)?\\)\\}`)
      );
    }
    if (name === "ToolForm") {
      // `height` is not in `DIMENSION_FIELDS`; a marker naming it is a REJECTED SAVE, not a dropped
      // hint. `"heightInches"` does not match `"height"` — the closing quote separates them — so this
      // is an exact test for the centimetre box and not a prefix match on the inch one. It holds
      // after the pairing for the same reason it held before: the centimetre boxes now receive a
      // CONVERTED figure from the accept callbacks, and a converted figure carries no provenance of
      // its own, so neither of them may ever appear as a provenance KEY.
      expect(source, "the centimetre height column never records an acceptance").not.toMatch(
        /rememberAcceptance\(current, "height"[,)]/
      );
      expect(source, "nor the centimetre width").not.toMatch(/rememberAcceptance\(current, "width"[,)]/);
      expect(source, "and neither is wired through the forgetting handler either").not.toMatch(
        /type(Into|Inches)\(set\w+, "(height|width)"[,)]/
      );
      // The centimetre boxes have their OWN factory, which takes no column name at all — there is no
      // key it could file a marker under even by accident.
      expect(source, "the centimetre boxes go through the partner-writing factory").toContain(
        "onChange={typeCm(setHeight, setHeightInches)}"
      );
      expect(source, "both of them").toContain("onChange={typeCm(setWidth, setBreadth)}");
    }
  });

  test(`${name} keeps the photograph the measurement was read off`, () => {
    /*
      A DIMENSION WHOSE EVIDENCE IS GONE IS A NUMBER NOBODY CAN RE-DERIVE — and being re-derivable is
      the whole advantage this route has over the vision model. Both the offline path (the
      `saveOrQueue` media batches) and the online path (the per-file uploads after the record lands)
      have to carry it, because a photograph kept only on one of them is a photograph lost whenever
      the researcher happened to be in the other state.
    */
    const source = read(path);
    expect(source, "the panel reports its photograph up").toContain("onPhotoChange={(photo) => {");
    expect(source, "and the queued save carries it").toMatch(/\.\.\.\(measurePhoto\s*\n?\s*\?\s*\[/);
    expect(source, "and the online save uploads it").toMatch(/if \(measurePhoto\) \{[\s\S]{0,200}uploadMediaFile\(\{/);
    // Its EXIF is collected with the rest, so a measurement photo contributes its capture clock to
    // the record's remark like every other image the form sends.
    expect(source, "and its EXIF is read with the others").toContain("measurePhoto?.file");
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * A marker stops being sent the moment it stops being true
 * ──────────────────────────────────────────────────────────────────────────── */

/** A vision-model marker exactly as the server composes it. */
const VISION: MeasurementMethodMarker = {
  method: "VISION_MODEL",
  provider: "gemini",
  modelId: "gemini-2.5-flash-lite",
  selfReportedConfidence: 0.8
};

/** A geometry marker exactly as `photoMeasure.methodMarker` composes it. */
const GEOMETRY: MeasurementMethodMarker = { method: "PHOTO_GEOMETRY", technique: "SCALE" };

test.describe("what a save may still claim about a dimension", () => {
  test("an accepted reading left alone travels with its marker", () => {
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(accepted, { lengthInches: "6.25" })).toEqual({ lengthInches: GEOMETRY });
  });

  test("typed over after acceptance, the marker is dropped — UNRECORDED is the honest answer", () => {
    /*
      THE CORRECTNESS POINT THE WHOLE MODULE EXISTS FOR. A marker is a claim about how THIS number
      was obtained. A researcher who accepts a geometry reading of 6.25 and then types 7 has a typed
      number, and a `PHOTO_GEOMETRY` marker on it is a FALSE claim — worse than no marker, because
      the server reads an absent one as `UNRECORDED`, which is honest and distinguishable.
    */
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(accepted, { lengthInches: "7" })).toBeUndefined();
  });

  test("the check is on the VALUE, not on the event that changed it", () => {
    /*
      THE PROPERTY THAT MAKES THIS AUDITABLE BY READING ONE FUNCTION. Nothing is trusted about HOW the
      box came to differ — a keystroke, a paste, a browser autofill, or some future third writer
      nobody has written yet. `measurementMethodsFor` is handed the CURRENT text and compares it,
      character for character, against what the route wrote. A whitespace-padded copy of the same
      digits is a different string and loses the marker, which is the safe direction: the number in
      the box is no longer byte-for-byte the one the panel proposed.
    */
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(accepted, { lengthInches: " 6.25" })).toBeUndefined();
    expect(measurementMethodsFor(accepted, { lengthInches: "6.250" })).toBeUndefined();
    expect(measurementMethodsFor(accepted, { lengthInches: "6.25" })).toEqual({ lengthInches: GEOMETRY });
  });

  test("cleared after acceptance, the marker is dropped — and the save is not a 422", () => {
    /*
      TWO REASONS, AND THE SECOND IS THE EXPENSIVE ONE. A box with no number carries no claim about
      one; and the server computes the present dimensions from the NON-NULL values on the same body,
      so a method for a dimension sent as `null` is refused by name — a 422 on the whole save, which
      `saveOrQueue` will not queue.
    */
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
    /*
      THE TOOL FORM'S SECOND HEIGHT BOX. `ToolDocumentation.height` is unit-less and is not in
      `measurement_provenance.DIMENSION_FIELDS`, so a marker naming it is not a dropped hint at the
      API boundary — it is a 422 naming the key, and the researcher loses the form. Refused at the
      point of acceptance rather than filtered at the payload, so nothing can hold it in the first
      place.
    */
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "height", "12", GEOMETRY);
    expect(accepted).toEqual({});
    expect(measurementMethodsFor(accepted, { height: "12" } as Record<string, string>)).toBeUndefined();
  });

  test("a reading the server sent no marker for records nothing, and forgets any older one", () => {
    /*
      An API that predates `methodMarker` answers with a number and no method. The reflex — keep
      whatever marker was already stored for that box — is exactly wrong: the box has just been
      overwritten with a NEW machine number, so the old marker describes a value that is gone. The
      acceptance is forgotten instead and the save records `UNRECORDED`, which is what happened.
    */
    let accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    accepted = rememberAcceptance(accepted, "lengthInches", "8.00", null);
    expect(accepted).toEqual({});
    expect(measurementMethodsFor(accepted, { lengthInches: "8.00" })).toBeUndefined();
  });

  test("a typing researcher's keystroke forgets the acceptance, identical digits included", () => {
    /*
      The one case value-equality cannot see. Typing 6.25 back over an accepted 6.25 leaves a string
      that matches, so only the box's own `onChange` can know a person did it. `forgetAcceptance`
      returns the SAME object when there is nothing to forget, which is what keeps a keystroke in a
      dimension box off the re-render path on a form nobody has measured on.
    */
    const accepted = rememberAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches", "6.25", GEOMETRY);
    expect(measurementMethodsFor(forgetAcceptance(accepted, "lengthInches"), { lengthInches: "6.25" })).toBeUndefined();
    expect(forgetAcceptance(NO_ACCEPTED_MEASUREMENTS, "lengthInches")).toBe(NO_ACCEPTED_MEASUREMENTS);
    expect(forgetAcceptance(accepted, "breadthInches")).toBe(accepted);
  });

  test("nothing is ever synthesised as TYPED", () => {
    /*
      `UNRECORDED` IS NEVER A SYNONYM FOR TYPED. There is no branch anywhere in this module that
      composes `{method: "TYPED"}` for a box somebody typed into, and its absence is deliberate rather
      than unfinished: this client cannot tell a number read off a tape from one copied out of a
      message, and the rows already in the database include model estimates that auto-filled a form
      field. Defaulting absence to TYPED would assert a human measured a number a machine guessed, for
      exactly the rows where that is false.
    */
    const typedByHand = measurementMethodsFor(NO_ACCEPTED_MEASUREMENTS, {
      lengthInches: "6.25",
      breadthInches: "4",
      heightInches: "3"
    });
    expect(typedByHand).toBeUndefined();
    expect(JSON.stringify(typedByHand ?? null)).not.toContain("TYPED");
  });

  test("nothing accepted sends no key at all, not an empty object and not a null", () => {
    /*
      `undefined` so `JSON.stringify` drops the key entirely. The web deploys to Vercel and the API
      to EC2 separately, so a newer web build can meet an older API, and `APIModel` is
      `ConfigDict(extra="forbid")` — a `measurementMethods: null` PRESENT on the body would be a 422
      on the whole save against a server that predates the declaration. An absent key is refused by
      nothing, ever, and means `UNRECORDED`.
    */
    expect(measurementMethodsFor(NO_ACCEPTED_MEASUREMENTS, { lengthInches: "6.25" })).toBeUndefined();
    const body = JSON.stringify({
      lengthInches: 6.25,
      measurementMethods: measurementMethodsFor(NO_ACCEPTED_MEASUREMENTS, { lengthInches: "6.25" })
    });
    expect(body).toBe('{"lengthInches":6.25}');
  });
});
