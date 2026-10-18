import { expect, test } from "@playwright/test";

import {
  choicesToSend,
  defaultChoices,
  optionsFor,
  reseedChoices,
  resultValue,
  type MergeField
} from "@/lib/duplicates";

/**
 * The merge screen's field table: what each row defaults to, which rows are locked, and what is sent.
 * The server computes the defaults per survivor; the client re-seeds them when the survivor changes
 * and never sends a choice for a locked row.
 */

const FIELDS: MergeField[] = [
  { key: "name", label: "Name", a: "Giriraj Prasad", b: "Giriraj Prasaad", default: "A", options: ["A", "B"], defaults: { A: "A", B: "B" } },
  { key: "phone", label: "Phone", a: null, b: "9825012345", default: "B", options: ["A", "B"], defaults: { A: "B", B: "B" } },
  { key: "notes", label: "Notes", a: "Met at the fair", b: "Prefers mornings", default: "BOTH", options: ["A", "B", "BOTH"], defaults: { A: "BOTH", B: "BOTH" } },
  { key: "aadhaar", label: "Aadhaar", a: null, b: "XXXX XXXX 9012", default: "B", options: ["B"], defaults: { A: "B", B: "B" }, locked: true }
];

test("defaults follow the survivor, and an empty side takes the other's value", () => {
  expect(defaultChoices(FIELDS, "A")).toEqual({ name: "A", phone: "B", notes: "BOTH", aadhaar: "B" });
  expect(defaultChoices(FIELDS, "B")).toEqual({ name: "B", phone: "B", notes: "BOTH", aadhaar: "B" });
});

test("a locked row offers only the side that holds it", () => {
  expect(optionsFor(FIELDS[3], "A")).toEqual(["B"]);
  expect(optionsFor(FIELDS[2], "A")).toEqual(["A", "B", "BOTH"]);
});

test("switching the survivor keeps what the admin changed and re-seeds the rest", () => {
  const touched = new Set(["notes"]);
  const choices = { ...defaultChoices(FIELDS, "A"), notes: "A" as const };
  const next = reseedChoices(FIELDS, choices, touched, "B");
  expect(next.name).toBe("B");
  expect(next.notes).toBe("A");
  expect(next.aadhaar).toBe("B");
});

test("locked rows and illegal values are never sent", () => {
  const sent = choicesToSend(FIELDS, { name: "B", phone: "BOTH", notes: "BOTH", aadhaar: "A" });
  expect(sent).toEqual({ name: "B", notes: "BOTH" });
});

test("Both joins the two values on a new line, once when they agree", () => {
  expect(resultValue(FIELDS[2], "BOTH")).toBe("Met at the fair\nPrefers mornings");
  expect(resultValue({ ...FIELDS[2], b: "Met at the fair" }, "BOTH")).toBe("Met at the fair");
  expect(resultValue(FIELDS[1], "A")).toBeNull();
  expect(resultValue(FIELDS[1], "B")).toBe("9825012345");
});
