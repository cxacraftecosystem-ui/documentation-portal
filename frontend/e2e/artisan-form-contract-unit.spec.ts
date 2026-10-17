import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  ARTISAN_FORM_CONTRACT,
  ARTISAN_FORM_CONTRACT_KEY,
  ARTISAN_REQUIRED_DATES,
  EXPERIENCE_MONTH_OPTIONS,
  EXPERIENCE_NOT_STATED,
  experienceMonthsLabel,
  missingArtisanDatesSentence
} from "@/lib/artisanFormContract";

/**
 * The web half of the artisan form contract (2026-10-17), against `shared/artisan-form-contract.json`
 * — the one table both portals carry byte for byte, which the server and the handset also run. The
 * server's `test_artisan_form_contract.py` reads `ArtisanForm.tsx` itself for the labels, their order
 * and the retired words; this file proves the sentence, the key and the month rows. No browser.
 */

type Vector = { dateOfBirth: string | null; craftStartDate: string | null; sentence: string | null };

const contract = JSON.parse(
  readFileSync(join(__dirname, "..", "..", "shared", "artisan-form-contract.json"), "utf-8")
) as {
  bodyKey: string;
  datesRequiredFrom: number;
  requiredDates: { field: string; label: string; words: string }[];
  refusalVectors: Vector[];
  experience: { months: { min: number; max: number; empty: string; optionVectors: [number, string][] } };
};

test("the body key and its value are the contract's", () => {
  expect(ARTISAN_FORM_CONTRACT_KEY).toBe(contract.bodyKey);
  expect(ARTISAN_FORM_CONTRACT).toBe(contract.datesRequiredFrom);
  expect(ARTISAN_REQUIRED_DATES.map(({ field, label, words }) => ({ field, label, words }))).toEqual(
    contract.requiredDates
  );
});

test("the missing-dates sentence matches every shared vector", () => {
  expect(contract.refusalVectors.length).toBeGreaterThanOrEqual(4);
  for (const row of contract.refusalVectors) {
    expect(
      missingArtisanDatesSentence({ dateOfBirth: row.dateOfBirth, craftStartDate: row.craftStartDate }),
      JSON.stringify(row)
    ).toBe(row.sentence);
  }
});

test("the months box offers 0 to 11 under the shared words", () => {
  const months = contract.experience.months;
  expect(EXPERIENCE_NOT_STATED).toBe(months.empty);
  expect(EXPERIENCE_MONTH_OPTIONS[0]).toBe(months.min);
  expect(EXPERIENCE_MONTH_OPTIONS[EXPERIENCE_MONTH_OPTIONS.length - 1]).toBe(months.max);
  expect(EXPERIENCE_MONTH_OPTIONS).toHaveLength(months.max - months.min + 1);
  for (const [value, label] of months.optionVectors) expect(experienceMonthsLabel(value)).toBe(label);
});
