import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { RECORDING_CONSENT_DESCRIPTION, recordingConsentStatus } from "@/components/forms/RecordingConsentToggle";

/** Consent is assumed by default and can be revoked (owner decision, 19 Oct 2026). */

test("the switch is on by default and off only after a refusal", () => {
  const src = readFileSync(join(__dirname, "..", "components", "forms", "RecordingConsentToggle.tsx"), "utf8");
  expect(src).toContain('checked={consent.state !== "REFUSED"}');
  expect(RECORDING_CONSENT_DESCRIPTION).toMatch(/^On by default\. Switch off/);
});

test("the status line says on by default, agreed or declined", () => {
  expect(recordingConsentStatus({ state: "NOT_RECORDED", source: null, recordedAt: null })).toBe("On by default");
  expect(recordingConsentStatus({ state: "GRANTED", source: "owner attestation", recordedAt: null })).toBe("Agreed · confirmed by the owner");
  expect(recordingConsentStatus({ state: "REFUSED", source: "researcher", recordedAt: null })).toBe("Declined");
});

test("the inline notice offers Switch off, and Switch back on after a refusal", () => {
  const src = readFileSync(join(__dirname, "..", "components", "questionnaire", "ConsentQuestion.tsx"), "utf8");
  expect(src).toContain('void record("REFUSED")');
  expect(src).toContain('qs("consentSwitchOff")');
  expect(src).toContain('qs("consentSwitchOn")');
  expect(src).not.toContain("consentNotNow");
});
