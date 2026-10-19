import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { Recording } from "@/lib/questionnaireApi";
import { transcriptView } from "@/lib/questionnaireView";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * Owner correction (d), DESIGN §3.14: transcription ships paused. The take reads "Transcription
 * paused" calmly; Retry is disabled with "Transcription is paused."; a refusal is stated, not an
 * error, and offers Change consent inline. Consent is assumed by default (19 Oct 2026), so an older
 * take still marked AWAITING_CONSENT reads "Waiting to be sent" with nothing to answer. No control
 * can start it while paused.
 */

const rec = CONTRACT.examples.Recording as Recording;

test("paused: neutral, Retry disabled with the reason", () => {
  const view = transcriptView(rec, true);
  expect(view).toEqual({ label: "Transcription paused", tone: "neutral", retryEnabled: false, retryReason: "Transcription is paused.", offerConsent: false });
  // Even a failed job cannot be retried while paused.
  expect(transcriptView({ ...rec, transcript: { ...rec.transcript, status: "FAILED", paused: true } }, true).retryEnabled).toBe(false);
});

test("an older waiting take and a refused one", () => {
  const waiting = transcriptView({ ...rec, consent: { verdict: "AWAITING", source: null } }, true);
  expect(waiting.label).toBe("Waiting to be sent");
  expect(waiting.offerConsent).toBe(false);
  expect(waiting.tone).toBe("neutral");
  const refused = transcriptView({ ...rec, consent: { verdict: "REFUSED", source: "researcher" } }, true);
  expect(refused.label).toBe("Not sent: consent refused");
  expect(refused.offerConsent).toBe(true);
  expect(refused.retryEnabled).toBe(false);
});

test("no answer on record is consent: the take is not held and offers nothing", () => {
  const view = transcriptView({ ...rec, consent: { verdict: "ALLOW", source: null } }, true);
  expect(view.offerConsent).toBe(false);
  expect(view.label).not.toMatch(/consent/i);
});

test("the owner's attestation shows no consent badge; unpaused failure can retry", () => {
  expect(transcriptView({ ...rec, consent: { verdict: "ALLOW", source: "owner attestation" } }, true).offerConsent).toBe(false);
  expect(transcriptView({ ...rec, transcript: { ...rec.transcript, status: "FAILED", paused: false } }, false).retryEnabled).toBe(true);
});

test("the Retry control is disabled, not hidden, and never calls the API while paused", () => {
  const src = readFileSync(join(__dirname, "..", "components", "questionnaire", "TranscriptStatus.tsx"), "utf8");
  expect(src).toContain("disabled={!view.retryEnabled}");
  expect(src).toContain("onClick={view.retryEnabled ? onRetry : undefined}");
});
