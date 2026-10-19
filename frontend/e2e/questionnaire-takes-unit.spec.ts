import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { cellMark, rowSummary } from "@/components/questionnaire/CoverageGrid";
import { takeRow } from "@/components/questionnaire/TakeSheet";
import type { Recording } from "@/lib/questionnaireApi";
import { CONTRACT } from "./questionnaire-contract-fixture";

/** DESIGN test 43: take rows show position, duration, researcher, date and setting; downloads use `downloadName`. */

const rec = CONTRACT.examples.Recording as Recording;

test("a take row is words, never a bare file name", () => {
  expect(takeRow(rec, "a1")).toBe("Take 1 · 14 min · Nandini · 1 Jul · Solo");
  const redo: Recording = { ...rec, rosterCount: 3, subjects: [{ ...rec.subjects[0], position: 2, repeatReason: "REDO" }] };
  expect(takeRow(redo, "a1")).toBe("Take 2 · Redo · 14 min · Nandini · 1 Jul · Group of 3");
  expect(takeRow(rec, "a1")).not.toContain(rec.downloadName);
});

test("download uses the server's downloadName; the client never builds one", () => {
  const sheet = readFileSync(join(__dirname, "..", "components", "questionnaire", "TakeSheet.tsx"), "utf8");
  expect(sheet).toContain("download={r.downloadName}");
  expect(sheet).not.toMatch(/recordingFilename|mediaFileName/);
});

test("grid marks carry words as well as glyphs", () => {
  expect(cellMark({ state: "RECORDED", count: 2, partial: null })).toMatchObject({ mark: "●2", label: "Recorded" });
  expect(cellMark({ state: "PARTIAL", count: 0, partial: { done: 4, of: 10 } }).mark).toBe("◐ 4/10");
  expect(cellMark({ state: "NEEDS_DECISION", count: 1, partial: null }).label).toBe("Needs a decision");
  expect(cellMark(null).mark).toBe("○");
  const grid = {
    sections: [{ id: "secD", code: "D", title: "Process" }],
    cells: [
      { artisanId: "a1", targetKey: "S:secD", state: "RECORDED" as const, count: 1 },
      { artisanId: "a2", targetKey: "S:secD", state: "NEEDS_DECISION" as const, count: 1 }
    ]
  };
  expect(rowSummary(grid, "secD", ["a1", "a2", "a3"])).toEqual({ covered: 1, of: 3, decision: true });
});
