import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  asciiLeaf,
  dedupeNames,
  field,
  fileName,
  type FileNameInput,
  placeField,
  questionSequence,
  sectionSequence,
  sessionTitle,
  type TitleFields,
  workshopFilename
} from "@/lib/naming";

/**
 * The browser's half of the naming convention, held to the same golden table as `naming.py` and
 * `Naming.kt` (`shared/naming-golden.json`, byte-identical in both portals). No browser is opened.
 */
const GOLDEN = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "naming-golden.json"), "utf8"));

test("the golden table is present and not empty", () => {
  expect(GOLDEN.version).toBe(1);
  expect(GOLDEN.fileNames.length).toBeGreaterThan(30);
  expect(GOLDEN.titles.length).toBeGreaterThan(8);
});

test("file names match the golden table", () => {
  for (const row of GOLDEN.fileNames as { input: FileNameInput; name: string }[]) {
    expect(fileName(row.input), JSON.stringify(row.input)).toBe(row.name);
    expect(row.name.length).toBeLessThanOrEqual(GOLDEN.limits.fileName);
    expect(/^[\x21-\x7e]+$/.test(row.name)).toBe(true);
  }
});

test("display titles match the golden table", () => {
  for (const row of GOLDEN.titles as { input: TitleFields; title: string }[]) {
    expect(sessionTitle(row.input), JSON.stringify(row.input)).toBe(row.title);
  }
});

test("transliteration, fields, places and sequences match the golden table", () => {
  for (const row of GOLDEN.transliteration) expect(field(row.input), row.input).toBe(row.field);
  for (const row of GOLDEN.fields) expect(field(row.input), row.input).toBe(row.field);
  for (const row of GOLDEN.places) expect(placeField(row.input), JSON.stringify(row.input)).toBe(row.place);
  for (const row of GOLDEN.sequences) {
    const { section, question, take, part } = row.input;
    const got = "question" in row.input ? questionSequence(section, question, { take, part }) : sectionSequence(section);
    expect(got, JSON.stringify(row.input)).toBe(row.sequence);
  }
});

test("leaves and collisions match the golden table", () => {
  for (const row of GOLDEN.asciiLeaves) expect(asciiLeaf(row.input), row.input).toBe(row.leaf);
  for (const row of GOLDEN.dedupe) expect(dedupeNames(row.input)).toEqual(row.names);
});

test("workshop file names match the golden table", () => {
  for (const row of GOLDEN.workshops) {
    const { workshop, what, ext, at } = row.input;
    expect(workshopFilename(workshop, what, ext, { at, portal: "DesignWorkshop" }), JSON.stringify(row.input)).toBe(row.name);
  }
});

test("a leaf is always an ASCII S3-safe name", () => {
  for (const raw of ["गिरिराज प्रसाद.m4a", "a/b\\c.JPG", "  ", "NUL", "x".repeat(400)]) {
    const leaf = asciiLeaf(raw);
    expect(leaf).toMatch(/^[A-Za-z0-9._-]{1,150}$/);
  }
});
