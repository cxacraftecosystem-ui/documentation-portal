import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  asciiLeaf,
  dedupeNames,
  exportFilename,
  type ExportNameInput,
  field,
  fieldWords,
  fileName,
  type FileNameInput,
  formatPhone,
  PORTAL,
  placeField,
  questionSequence,
  recordingFilename,
  recordingSequence,
  type RecordingNameInput,
  sectionSequence,
  sessionTitle,
  type TargetRef,
  type TitleFields,
  type TitleInput,
  workshopFilename
} from "@/lib/naming";

/**
 * The browser's half of the naming convention, held to the same two golden tables as `naming.py`
 * and `Naming.kt` (byte-identical in both portals): `shared/session-title.json`, the frozen
 * questionnaire contract (DESIGN test 60, web half), and `shared/naming-golden.json`, every other
 * generated name. No browser is opened.
 */
const GOLDEN = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "naming-golden.json"), "utf8"));

type Row =
  | { kind: "title"; case: string; input: TitleInput; title: string }
  | { kind: "recording"; case: string; input: RecordingNameInput; fileName: string }
  | { kind: "transliteration"; case: string; input: string; ascii: string }
  | { kind: "export"; case: string; input: ExportNameInput; fileName: string }
  | { kind: "collision"; case: string; input: string[]; names: string[] };

const CONTRACT = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "session-title.json"), "utf8")) as { golden: Row[] };

/** The contract's rows are written for documentation-portal; designer-portal's word replaces it. */
const here = (name: string) => name.replace("FieldRepository_", `${PORTAL}_`);

test("the contract table covers every kind", () => {
  const kinds = new Set(CONTRACT.golden.map((r) => r.kind));
  expect([...kinds].sort()).toEqual(["collision", "export", "recording", "title", "transliteration"]);
});

for (const row of CONTRACT.golden) {
  test(`contract ${row.kind}: ${row.case}`, () => {
    switch (row.kind) {
      case "title":
        expect(sessionTitle(row.input)).toBe(row.title);
        break;
      case "recording": {
        const name = recordingFilename(row.input);
        expect(name).toBe(here(row.fileName));
        expect(name).toMatch(/^[A-Za-z0-9._-]{1,150}$/);
        break;
      }
      case "transliteration":
        expect(fieldWords(row.input, true).join("-")).toBe(row.ascii);
        break;
      case "export":
        expect(exportFilename(row.input)).toBe(here(row.fileName));
        break;
      case "collision":
        expect(dedupeNames(row.input)).toEqual(row.names);
        break;
    }
  });
}

test("an estimated time is left out and never invented", () => {
  const session = { sessionDate: "2026-07-01", participants: [{ name: "Meena Pal" }] };
  for (const source of ["SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW"]) {
    expect(recordingFilename({ session, capturedAt: "2026-07-01T06:30:00Z", capturedAtSource: source })).toBe(
      `${PORTAL}_Interview_2026-07-01_Meena-Pal_Recording.m4a`
    );
  }
  expect(recordingFilename({ session, capturedAt: "2026-07-01T06:30:00Z", capturedAtSource: "DEVICE" })).toContain("_1200_");
  expect(recordingFilename("Interview", "m4a", { capturedAt: "2026-07-01T06:30:00Z", timeKnown: false, subject: "Meena Pal" })).toBe(
    `${PORTAL}_Interview_2026-07-01_Meena-Pal_Recording.m4a`
  );
});

test("a non-ASCII leaf is impossible and Windows reserved stems are guarded", () => {
  expect(asciiLeaf("गिरिराज.m4a")).toBe("giriraj.m4a");
  expect(asciiLeaf("CON.txt")).toBe("CON-File.txt");
  expect(asciiLeaf("")).toBe("upload.bin");
});

test("phone display is +91 98765 43210 (owner correction b)", () => {
  expect(formatPhone("+91", "9876543210")).toBe("+91 98765 43210");
  expect(formatPhone("91", "98765 43210")).toBe("+91 98765 43210");
  expect(formatPhone("+44", "2079460000")).toBe("+44 2079460000");
  expect(formatPhone("+91", "")).toBe("");
});

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

test("plain-field titles match the golden table", () => {
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

test("recording sequences read the question after its own section code", () => {
  // "F21" in section F2 is question 1: the section comes from the target, never from the display code.
  type RecRow = {
    input: { targets: TargetRef[]; part?: number | null; position?: number | null; rosterCount?: number | null };
    sequence: string | null;
  };
  expect(GOLDEN.recordingSequences.length).toBeGreaterThan(0);
  for (const row of GOLDEN.recordingSequences as RecRow[]) {
    const { targets, part, position, rosterCount } = row.input;
    expect(recordingSequence(targets, { part, position, rosterCount }), JSON.stringify(row.input)).toBe(row.sequence);
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
