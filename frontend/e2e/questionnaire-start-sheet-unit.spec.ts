import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { constrainArtisans, sessionCreateBody, startBlocked, type StartSheetState } from "@/components/questionnaire/SessionStartSheet";
import { coverageLine } from "@/lib/coverageCache";
import { prompts } from "@/lib/questionnairePrompts";

/** DESIGN tests 36 and 61: the start sheet's gate, mode, coverage line, P1, no title, consent inline. */

const form: StartSheetState = {
  workshopId: "w_bankura",
  questionnaireId: "q_ctw3",
  sessionDate: "2026-07-01",
  mode: "INDIVIDUAL",
  artisanIds: ["a1"],
  purpose: "FIRST_SITTING",
  place: "Bagru, Jaipur",
  languageCode: "hi",
  languageOther: "",
  note: "Under the neem tree"
};
const SHEET = readFileSync(join(__dirname, "..", "components", "questionnaire", "SessionStartSheet.tsx"), "utf8");

test("Start is blocked without an instrument, a date or artisans", () => {
  expect(startBlocked(form)).toBe(false);
  expect(startBlocked({ ...form, questionnaireId: "" })).toBe(true);
  expect(startBlocked({ ...form, sessionDate: "" })).toBe(true);
  expect(startBlocked({ ...form, artisanIds: [] })).toBe(true);
});

test("the segmented mode constrains the picker", () => {
  expect(constrainArtisans("INDIVIDUAL", ["a1", "a2", "a3"])).toEqual(["a1"]);
  expect(constrainArtisans("GROUP", ["a1", "a2", "a3"])).toEqual(["a1", "a2", "a3"]);
  expect(SHEET).toContain('form.mode === "GROUP" ? (');
  expect(SHEET).toContain("<SearchableMultiSelect");
  expect(SHEET).toContain("<SegmentedChoice");
  expect(SHEET).toContain("<CalendarField");
});

test("the coverage line for 1, 3 and 8 artisans", () => {
  expect(coverageLine([{ name: "Giriraj Prasad Chhipa", covered: 9, total: 24 }])).toBe("Giriraj has 9 of 24 sections.");
  expect(
    coverageLine([
      { name: "Giriraj", covered: 9, total: 24 },
      { name: "Meena", covered: 0, total: 24 },
      { name: "Ravi", covered: 14, total: 24 }
    ])
  ).toBe("Giriraj 9 of 24 · Meena none yet · Ravi 14 of 24.");
  const eight = Array.from({ length: 8 }, (_, i) => ({ name: `A${i}`, covered: i < 3 ? 5 : 0, total: 24 }));
  expect(coverageLine(eight)).toBe("3 of 8 have earlier recordings");
  expect(coverageLine([{ name: "Giriraj", covered: 9, total: 24 }], "2 h ago")).toBe("Giriraj has 9 of 24 sections · Checked 2 h ago");
});

test("P1 on an open match: exact copy, two buttons on one line", () => {
  const p1 = prompts.p1({ researcher: "Nandini", artisans: "Giriraj and Meena", time: "10:12" });
  expect(p1.title).toBe("Session already open");
  expect(p1.body).toBe("Nandini started one with Giriraj and Meena at 10:12.");
  expect(p1.buttons.map((b) => b.label)).toEqual(["New session", "Join"]);
  expect(SHEET).toContain(".openMatch(");
});

test("no screen asks for a title: the create body has none, and the preview is the generated one", () => {
  const created = sessionCreateBody(form, "c-1", new Date("2026-07-01T12:54:00Z"));
  expect(created).not.toHaveProperty("title");
  expect(created.note).toBe("Under the neem tree");
  expect(sessionCreateBody({ ...form, note: "x".repeat(120) }, "c-2").note).toHaveLength(80);
  expect(created.workshopId).toBe("w_bankura");
  expect(sessionCreateBody({ ...form, workshopId: "__field_visit__" }, "c-3").workshopId).toBeNull();
  expect(SHEET).not.toMatch(/name="title"|label="Title"|"Interview title"/);
  expect(SHEET).toContain("data-title-preview");
  expect(SHEET).toContain("sessionTitle(");
});

test("consent is asked inline and never blocks Start", () => {
  expect(SHEET).toContain("<ConsentQuestion");
  expect(startBlocked.toString()).not.toMatch(/consent/i);
});

test("each request goes out once per real change: artisan page, consent, coverage", () => {
  // The artisan page is re-asked only for a new search or workshop, not for a tick.
  expect(SHEET).toContain("}, [search, form.workshopId]);");
  // Consent is marked as asked before its request, so one answer does not re-ask the others.
  expect(SHEET).toMatch(/consentRequested\.current\.has\(id\)\) continue;\s*consentRequested\.current\.add\(id\);/);
  // Coverage is fetched per instrument and selection; names are laid over it on render.
  expect(SHEET).toContain("}, [form.questionnaireId, artisanKey]);");
  expect(SHEET).toContain("}, [coverageGrid, artisans]);");
});
