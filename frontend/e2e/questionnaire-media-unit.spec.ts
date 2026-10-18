import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/** DESIGN test 46: `/media` has no Questionnaire link type under v2; the read view has no transcript action; old URLs redirect. */

const read = (...p: string[]) => readFileSync(join(__dirname, "..", ...p), "utf8");

test("/media drops the Questionnaire link type under v2", () => {
  const media = read("app", "(protected)", "media", "page.tsx");
  expect(media).toContain('const V2_LINK_TYPES = LINK_TYPES.filter((t) => t.value !== "questionnaire");');
  expect(media).toContain("const linkTypes = questionnaireConfig?.v2Enabled ? V2_LINK_TYPES : LINK_TYPES;");
  expect(media).toContain("options={linkTypes}");
});

test("the read view has no transcript or Add media action", () => {
  const view = read("components", "questionnaire", "SessionReadView.tsx").replace(/\/\*\*[\s\S]*?\*\//g, "");
  expect(view).not.toMatch(/onRetry|transcribe\(|Add media/);
  expect(view).toContain('qs("update")');
});

test("old interview and consolidated URLs redirect under v2", () => {
  expect(read("app", "(protected)", "questionnaire", "interviews", "[id]", "page.tsx")).toContain("useV2Redirect(`/questionnaire/sessions/");
  expect(read("app", "(protected)", "questionnaire", "consolidated", "[artisanId]", "page.tsx")).toContain(
    "useV2Redirect(`/artisans/${encodeURIComponent(artisanId)}?tab=questionnaire`)"
  );
});
