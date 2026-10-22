import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { Session } from "@/lib/questionnaireApi";
import { coverageCell, modeLabel, sessionMatches } from "@/lib/questionnaireView";
import { CONTRACT } from "./questionnaire-contract-fixture";

/** DESIGN test 45: names, mode, instrument and coverage counts; search over legacyTitle; no "0 answers". */

const session = CONTRACT.examples.Session as Session;
const LIST = readFileSync(join(__dirname, "..", "components", "questionnaire", "SessionsList.tsx"), "utf8").replace(/\/\*\*[\s\S]*?\*\//g, "");

test("mode and coverage cells", () => {
  expect(modeLabel(session)).toBe("Individual");
  expect(modeLabel({ participantCount: 6 })).toBe("Group of 6");
  expect(coverageCell(session)).toBe("6 sections · 9 takes");
  expect(coverageCell({ counts: { sections: 0, takes: 0, needsDecision: 0 } })).toBe("0 sections · 0 takes");
  expect(LIST).not.toMatch(/answers\b/);
});

test("@qf:B28 the list shows the generated title with the note under it, and search matches the legacy title", () => {
  expect(LIST).toContain("{s.title}");
  expect(LIST).toContain("{s.note ? ");
  expect(LIST).not.toContain("legacyTitle}");
  const legacy = { ...session, legacyTitle: "section F Bankura old" };
  expect(sessionMatches(legacy, "section f")).toBe(true);
  expect(sessionMatches(legacy, "neem")).toBe(true);
  expect(sessionMatches(legacy, "Bagru-Jaipur")).toBe(true);
  expect(sessionMatches(legacy, "nothing")).toBe(false);
});

test("@qf:B36 /questionnaire is the sessions list only when v2 is on", () => {
  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "questionnaire", "page.tsx"), "utf8");
  expect(page).toContain('if (config.v2Enabled && !pathname.startsWith("/questionnaire/builder")) {');
  expect(page).toContain("<SessionsList />");
});
