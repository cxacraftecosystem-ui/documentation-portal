import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  formatPhone,
  languageName,
  languageOptions,
  materialFamilyFromText,
  normalizeGender,
  normalizeLanguage,
  normalizeMaterialFamily,
  normalizePhone,
  normalizeTradition
} from "@/lib/standardValues";

/**
 * THE BROWSER'S STANDARD VALUES, PROVED EQUAL TO THE SERVER'S AND THE HANDSET'S.
 *
 * `shared/standard-values-vectors.json` is the one table all three run; the server's copy of the
 * lists is `backend/app/data/standard_values.json`, and the web reads a byte copy of it.
 */
const ROOT = join(__dirname, "..", "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8").replace(/\r\n/g, "\n");
const vectors = JSON.parse(read("shared/standard-values-vectors.json"));

type ListRow = { input: string | null; value?: string | null; error?: string };

test("the web copy of the lists is the server's file, byte for byte", () => {
  expect(read("frontend/lib/standard-values.json")).toBe(read("backend/app/data/standard_values.json"));
});

test("phone vectors", () => {
  expect(vectors.phone.length).toBeGreaterThanOrEqual(25);
  for (const row of vectors.phone) {
    const result = normalizePhone(row.countryCode, row.number);
    if (row.error) {
      expect(result, JSON.stringify(row)).toEqual({ error: row.error });
      continue;
    }
    expect(result, JSON.stringify(row)).toEqual({ code: row.code, digits: row.digits });
    expect(formatPhone(row.code, row.digits)).toBe(row.display);
  }
});

const LISTS: [string, (value: unknown) => { value?: string | null; error?: string }][] = [
  ["language", normalizeLanguage],
  ["gender", normalizeGender],
  ["traditionType", normalizeTradition],
  ["materialFamily", normalizeMaterialFamily]
];

for (const [section, normalise] of LISTS) {
  test(`${section} vectors`, () => {
    const rows: ListRow[] = vectors[section];
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      const result = normalise(row.input);
      if (row.error) expect(result, JSON.stringify(row)).toEqual({ error: row.error });
      else expect(result, JSON.stringify(row)).toEqual({ value: row.value });
    }
  });
}

test("material from text and language names", () => {
  for (const row of vectors.materialFamilyFromText) {
    expect(materialFamilyFromText(row.input), JSON.stringify(row)).toBe(row.value);
  }
  for (const row of vectors.languageName) {
    expect(languageName(row.input), JSON.stringify(row)).toBe(row.value);
  }
});

test("the language dropdown stores codes and keeps a legacy value first", () => {
  const options = languageOptions(null);
  expect(options[0]).toEqual({ value: "hi", label: "Hindi" });
  expect(options.find((option) => option.value === "bn")?.label).toBe("Bengali");
  expect(languageOptions("Kutchi")[0]).toEqual({ value: "Kutchi", label: "Kutchi" });
  expect(languageOptions("bn")).toEqual(options);
});
