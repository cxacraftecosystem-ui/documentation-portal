import { expect, test } from "@playwright/test";

import {
  CM_PER_INCH,
  cmTextFromInches,
  inchesTextFromCm,
  parseDimension,
  propagate
} from "@/components/forms/dimensionUnits";

const TABLE: Array<[string, string, string]> = [
  ["1", "2.54", "0.39"],
  ["1.0", "2.54", "0.39"],
  ["0.5", "1.27", "0.2"],
  [".5", "1.27", "0.2"],
  ["2", "5.08", "0.79"],
  ["3", "7.62", "1.18"],
  ["10", "25.4", "3.94"],
  ["100", "254", "39.37"],
  ["2.54", "6.45", "1"],
  ["12.34", "31.34", "4.86"],
  ["0.1", "0.25", "0.04"],
  ["1.25", "3.18", "0.49"],
  ["0.125", "0.32", "0.05"],
  ["1.875", "4.76", "0.74"],
  ["6.25", "15.88", "2.46"],
  ["0.05", "0.13", "0.02"],
  ["1.005", "2.55", "0.4"]
];

test.describe("the inch is 25.4 mm, and both directions say so", () => {
  test("the constant is exact and is never a rounded convenience", () => {
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
    expect(cmTextFromInches("10")).toBe("25.4");
    expect(inchesTextFromCm("0.1")).toBe("0.04");
    expect(cmTextFromInches("100")).toBe("254");
  });
});

test.describe("what is not a number", () => {
  for (const bad of ["", "   ", "-", "-1", "1.2.3", "abc", "1e3", "0x1A", "1.5f", "Infinity", "NaN", "1,5", "1 000"]) {
    test(`${JSON.stringify(bad)} is not a dimension`, () => {
      expect(parseDimension(bad)).toBeNull();
      expect(cmTextFromInches(bad)).toBeNull();
      expect(inchesTextFromCm(bad)).toBeNull();
    });
  }

  test("a partially typed decimal IS a number", () => {
    expect(parseDimension("1.")).toBe(1);
    expect(cmTextFromInches("1.")).toBe("2.54");
    expect(cmTextFromInches("1.5")).toBe("3.81");
    expect(cmTextFromInches("1.5.")).toBeNull();
  });

  test("whitespace a paste carries is tolerated; whitespace inside a number is not", () => {
    expect(cmTextFromInches(" 1 ")).toBe("2.54");
    expect(cmTextFromInches("\t1\t")).toBe("2.54");
    expect(cmTextFromInches("1 000")).toBeNull();
  });

  test("a value the column cannot hold writes nothing rather than a truncated number", () => {
    expect(cmTextFromInches("99999999.99")).toBeNull();
    expect(cmTextFromInches("39370078.74")).toBeNull();
    expect(cmTextFromInches("39370078")).toBe("99999998.12");
    expect(inchesTextFromCm("99999999.99")).toBe("39370078.74");
  });
});

test.describe("propagate: the three rules a keystroke obeys", () => {
  function spy() {
    const writes: string[] = [];
    return { writes, set: (value: string) => writes.push(value) };
  }

  test("an empty box CLEARS its partner — the one non-number that means something", () => {
    const partner = spy();
    propagate("", cmTextFromInches, partner.set);
    expect(partner.writes).toEqual([""]);
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
    expect(inchesTextFromCm("1")).toBe("0.39");
    expect(cmTextFromInches("0.39")).toBe("0.99");
    expect(cmTextFromInches("0.99")).not.toBe("1");
  });
});
