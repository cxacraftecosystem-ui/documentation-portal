import { expect, test } from "@playwright/test";

import {
  CM_PER_INCH,
  cmTextFromInches,
  inchesTextFromCm,
  parseDimension,
  propagate
} from "@/components/forms/dimensionUnits";

/**
 * THE CENTIMETRE ↔ INCH CONVERSION, DRIVEN ROW BY ROW — and the Kotlin twin it has to agree with.
 *
 * `ToolDocumentation` pairs `height` with `heightInches` and `width` with `breadthInches`, filled
 * from whichever box the researcher types in. A conversion is the class of change whose broken state
 * looks EXACTLY like its working state — a plausible number in a box — so the only thing that can
 * catch a wrong one is a table of worked values somebody computed independently and both
 * implementations then had to reproduce.
 *
 * ── THE TABLE IS SHARED WITH ANDROID, VERBATIM ────────────────────────────────────────────────
 * `android/app/src/test/java/com/fieldrepository/app/DimensionUnitsTest.kt` drives the SAME rows
 * against `ui/DimensionUnits.kt`. Two tests, one table, copied across — that is the point of it, and
 * it is the only mechanism either repository has that can catch a TypeScript/Kotlin divergence
 * before a researcher finds two different numbers on a phone and a laptop. If you add a row here,
 * add it there.
 *
 * ── WHY THE EXPECTED VALUES ARE STRINGS AND NOT NUMBERS ───────────────────────────────────────
 * What lands in the box is TEXT and what reaches `Decimal(10, 2)` is that text parsed once. The
 * render is built from a hundredths-scaled INTEGER precisely so that neither language's float
 * formatter is ever involved — JavaScript prints "3" for an integral 3.0 where Kotlin prints "3.0" —
 * so asserting on numbers would be asserting on the half of the behaviour that cannot drift while
 * ignoring the half that can.
 *
 * This is a Playwright spec that never opens a browser, the same shape and the same argument as
 * `record-pickers-unit.spec.ts`: there is no React renderer in this repository's devDependencies,
 * which is why the rule lives in a pure module in the first place.
 */

/** input → [inches→cm, cm→inches]. Computed in V8 and reproduced by both implementations exactly. */
const TABLE: Array<[string, string, string]> = [
  ["1", "2.54", "0.39"],
  // The same value with a trailing zero: the render drops it, so both rows must agree.
  ["1.0", "2.54", "0.39"],
  ["0.5", "1.27", "0.2"],
  // The leading-dot form a person types before they reach the digit.
  [".5", "1.27", "0.2"],
  ["2", "5.08", "0.79"],
  ["3", "7.62", "1.18"],
  ["10", "25.4", "3.94"],
  ["100", "254", "39.37"],
  ["2.54", "6.45", "1"],
  ["12.34", "31.34", "4.86"],
  ["0.1", "0.25", "0.04"],
  /*
    THE HALFWAY ROWS, WHICH ARE THE WHOLE REASON THE ROUNDING RULE IS WRITTEN AS `floor(x + 0.5)`.
    Each of these has an intermediate that is EXACTLY a half-hundredth in binary64 — 1.25 in inches
    is 317.5 hundredths of a centimetre — so the tie-break is not incidental, it is the value. Round
    half to EVEN (Python's `round`, and the backend's own `_inches_to_cm`) answers 3.17 here, and
    `kotlin.math.round` breaks ties away from zero, which agrees for these non-negative values and
    would not for a signed one. The rule is spelled out rather than inherited from a standard library
    for exactly that reason.
  */
  ["1.25", "3.18", "0.49"],
  ["0.125", "0.32", "0.05"],
  ["1.875", "4.76", "0.74"],
  ["6.25", "15.88", "2.46"],
  // And one whose intermediate is a hair BELOW the halfway point (12.699999999999999289), so it must
  // NOT round up for the same reason the four above must.
  ["0.05", "0.13", "0.02"],
  ["1.005", "2.55", "0.4"]
];

test.describe("the inch is 25.4 mm, and both directions say so", () => {
  test("the constant is exact and is never a rounded convenience", () => {
    // Defined rather than measured, since the 1959 international yard-and-pound agreement. "2.5 for
    // readability" would be a 1.6% error on every dimension in the repository.
    expect(CM_PER_INCH).toBe(2.54);
  });

  for (const [input, cm, inches] of TABLE) {
    test(`${input} in → ${cm} cm`, () => {
      expect(cmTextFromInches(input)).toBe(cm);
    });
    test(`${input} cm → ${inches} in`, () => {
      expect(inchesTextFromCm(input)).toBe(inches);
    });
  }

  test("the render drops a trailing zero and keeps a leading one", () => {
    // Built from the scaled integer, so "25.4" is not "25.40" and "0.04" is not ".04". Both shapes
    // reach a `Decimal(10, 2)` column unchanged, and both have to read as a number to a person.
    expect(cmTextFromInches("10")).toBe("25.4");
    expect(inchesTextFromCm("0.1")).toBe("0.04");
    expect(cmTextFromInches("100")).toBe("254");
  });
});

test.describe("what is not a number", () => {
  /*
    ONE GRAMMAR, NARROWER THAN EITHER LANGUAGE'S OWN PARSER, because the two disagree at the edges:
    `Number("0x1A")` is 26 while `"0x1A".toDouble()` throws, `"1.5f".toDouble()` is 1.5 while
    `Number("1.5f")` is NaN, and both accept "Infinity" and "NaN". A regular expression both
    languages implement identically is the only shape four clients cannot diverge on.
  */
  for (const bad of ["", "   ", "-", "-1", "1.2.3", "abc", "1e3", "0x1A", "1.5f", "Infinity", "NaN", "1,5", "1 000"]) {
    test(`${JSON.stringify(bad)} is not a dimension`, () => {
      expect(parseDimension(bad)).toBeNull();
      expect(cmTextFromInches(bad)).toBeNull();
      expect(inchesTextFromCm(bad)).toBeNull();
    });
  }

  test("a partially typed decimal IS a number", () => {
    /*
      THE DECISION A PERSON ACTUALLY EXPERIENCES AS CORRECT, and it is a decision rather than a
      consequence. Typing 1 → 1. → 1.5 walks the partner 2.54 → 2.54 → 3.81; the partner never blanks
      and never flickers. Rejecting "1." instead would empty the partner for one keystroke, and an
      empty partner is this module's statement for "no value" — which somebody mid-decimal has not
      made.
    */
    expect(parseDimension("1.")).toBe(1);
    expect(cmTextFromInches("1.")).toBe("2.54");
    expect(cmTextFromInches("1.5")).toBe("3.81");
    // And the keystroke after that, which genuinely is not a number: the partner is left alone rather
    // than blanked, so it holds 3.81 for one keystroke and is corrected on the next.
    expect(cmTextFromInches("1.5.")).toBeNull();
  });

  test("whitespace a paste carries is tolerated; whitespace inside a number is not", () => {
    expect(cmTextFromInches(" 1 ")).toBe("2.54");
    expect(cmTextFromInches("\t1\t")).toBe("2.54");
    expect(cmTextFromInches("1 000")).toBeNull();
  });

  test("a value the column cannot hold writes nothing rather than a truncated number", () => {
    /*
      `Decimal(10, 2)` tops out at 99,999,999.99. Postgres refuses the row at the boundary with
      `numeric field overflow`, which reaches the researcher as an opaque 500 long after the box was
      filled and naming a column rather than the box they typed into. Refusing at the conversion is
      the only place that can be silent AND safe: the partner keeps whatever it had and the box the
      person is typing in still holds their number, so nothing is lost and nothing is invented.
    */
    expect(cmTextFromInches("99999999.99")).toBeNull();
    expect(cmTextFromInches("39370078.74")).toBeNull();
    // Just inside it, both directions still answer.
    expect(cmTextFromInches("39370078")).toBe("99999998.12");
    expect(inchesTextFromCm("99999999.99")).toBe("39370078.74");
  });
});

test.describe("propagate: the three rules a keystroke obeys", () => {
  /** Records every write, so "wrote nothing" and "wrote an empty string" are distinguishable. */
  function spy() {
    const writes: string[] = [];
    return { writes, set: (value: string) => writes.push(value) };
  }

  test("an empty box CLEARS its partner — the one non-number that means something", () => {
    // Empty means "no value". Leaving a stale converted number standing beside it is a lie the
    // record cannot detect afterwards, which is why this branch is tested BEFORE the parse.
    const partner = spy();
    propagate("", cmTextFromInches, partner.set);
    expect(partner.writes).toEqual([""]);
    // Whitespace-only is the same statement — a researcher who selected the box and hit space has
    // emptied it as surely as one who backspaced.
    const blank = spy();
    propagate("   ", cmTextFromInches, blank.set);
    expect(blank.writes).toEqual([""]);
  });

  test("anything else that is not a number writes NOTHING at all", () => {
    for (const bad of ["-", "abc", "1.2.3", "1e3", "99999999999"]) {
      const partner = spy();
      propagate(bad, cmTextFromInches, partner.set);
      expect(partner.writes, `${bad} must not reach the partner`).toEqual([]);
    }
  });

  test("a number writes the converted text, once", () => {
    const partner = spy();
    propagate("1.25", cmTextFromInches, partner.set);
    expect(partner.writes).toEqual(["3.18"]);
  });

  test("the two directions are not each other's inverse, and must never be chained", () => {
    /*
      THE DEFECT THE ONE-DIRECTIONAL RULE EXISTS FOR, demonstrated rather than described: 1 cm is
      0.39 in, and 0.39 in is 0.99 cm. A watcher effect over both values re-derives the source from
      the value it just wrote and the number decays on every keystroke. This is why the partner is
      written from inside the SOURCE box's own handler and by nothing else, and why there is no
      `useEffect` over `[height, heightInches]` in `ToolForm`.
    */
    expect(inchesTextFromCm("1")).toBe("0.39");
    expect(cmTextFromInches("0.39")).toBe("0.99");
    expect(cmTextFromInches("0.99")).not.toBe("1");
  });
});
