import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  asciiLeaf,
  dedupeNames,
  exportFilename,
  formatPhone,
  recordingFilename,
  sessionTitle,
  toAscii,
  type RecordingNameInput,
  type TitleInput
} from "@/lib/naming";

/**
 * DESIGN test 60 (web half) and owner correction (a): `lib/naming.ts` reproduces every golden row of
 * `shared/session-title.json` — the table `backend/tests/test_naming.py` and Android's `NamingTest`
 * run too — titles, recordings, transliteration, exports and collisions.
 */

type Row =
  | { kind: "title"; case: string; input: TitleInput; title: string }
  | { kind: "recording"; case: string; input: Omit<RecordingNameInput, "ext"> & { ext: string }; fileName: string }
  | { kind: "transliteration"; case: string; input: string; ascii: string }
  | { kind: "export"; case: string; input: Parameters<typeof exportFilename>[0]; fileName: string }
  | { kind: "collision"; case: string; input: string[]; names: string[] };

const TABLE = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "session-title.json"), "utf8")) as { golden: Row[] };

test("the golden table is the shared one and covers every kind", () => {
  const kinds = new Set(TABLE.golden.map((r) => r.kind));
  expect([...kinds].sort()).toEqual(["collision", "export", "recording", "title", "transliteration"]);
});

for (const row of TABLE.golden) {
  test(`${row.kind}: ${row.case}`, () => {
    switch (row.kind) {
      case "title":
        expect(sessionTitle(row.input)).toBe(row.title);
        break;
      case "recording": {
        const name = recordingFilename(row.input);
        expect(name).toBe(row.fileName);
        expect(name).toMatch(/^[A-Za-z0-9._-]{1,150}$/);
        break;
      }
      case "transliteration":
        expect(
          toAscii(row.input)
            .split(/[^A-Za-z0-9]+/)
            .filter(Boolean)
            .map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase())
            .join("-")
        ).toBe(row.ascii);
        break;
      case "export":
        expect(exportFilename(row.input)).toBe(row.fileName);
        break;
      case "collision":
        expect(dedupeNames(row.input)).toEqual(row.names);
        break;
    }
  });
}

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
