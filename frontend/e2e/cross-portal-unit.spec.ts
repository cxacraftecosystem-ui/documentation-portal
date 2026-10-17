import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { consentStatusLine } from "@/components/forms/SharingConsentToggle";
import { isMachineSuggested, MACHINE_SUGGESTED_LABEL } from "@/components/questionnaire/AnswerDraftsPanel";

/**
 * C27 and C28 on the web: the sharing switch's status line, the machine-suggested label, and both
 * features mounted only behind their server flags. No browser.
 */
test("the consent status line says what was recorded", () => {
  expect(consentStatusLine({ granted: false, recorded: false, recordedAt: null, linkedRecords: 0 })).toBe("Not asked yet");
  expect(consentStatusLine({ granted: true, recorded: true, recordedAt: null, linkedRecords: 1 })).toBe(
    "Agreed · linked to 1 Designer Portal record"
  );
  expect(consentStatusLine({ granted: false, recorded: true, recordedAt: null, linkedRecords: 0 })).toBe("Declined");
});

test("both machine origins are labelled, a typed answer is not", () => {
  expect(MACHINE_SUGGESTED_LABEL).toBe("Machine-suggested");
  expect(isMachineSuggested("MACHINE_SUGGESTED")).toBe(true);
  expect(isMachineSuggested("MACHINE_SUGGESTED_EDITED")).toBe(true);
  expect(isMachineSuggested(null)).toBe(false);
});

test("each feature is drawn only behind its server flag", () => {
  const toggle = readFileSync(join(__dirname, "../components/forms/SharingConsentToggle.tsx"), "utf8");
  expect(toggle).toContain('apiFetch<{ enabled: boolean }>("/cross-portal/status")');
  expect(toggle).toContain("if (!enabled || !consent) return null;");
  expect(toggle).toContain('label="Share with the Designer Portal"');
  const drafts = readFileSync(join(__dirname, "../components/questionnaire/AnswerDraftsPanel.tsx"), "utf8");
  expect(drafts).toContain('"/questionnaire/answer-drafts/status"');
  expect(drafts).toContain("if (!enabled || !list) return null;");
  const page = readFileSync(join(__dirname, "../app/(protected)/questionnaire/page.tsx"), "utf8");
  expect(page).toContain("<AnswerDraftsPanel");
  expect(page).toContain("<MachineSuggestedBadge");
});
