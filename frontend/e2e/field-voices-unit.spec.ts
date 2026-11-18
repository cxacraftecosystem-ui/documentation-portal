import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import STRINGS from "@/lib/fieldVoices/strings.json";
import {
  BASES,
  NOTE_MAX_LENGTH,
  basisOptions,
  fieldVoiceSubjectOf,
  fieldVoicesText,
  isWithdrawalBasis,
  withdrawalBody,
  withdrawalLine,
  withdrawalPath,
  type Withdrawal
} from "@/lib/fieldVoices/withdrawals";

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");
const DESIGNER_PORTAL = existsSync(join(ROOT, "app", "(protected)", "design-workshops"));

const SHOWN: Withdrawal = {
  subjectType: "ARTISAN",
  subjectId: "a1",
  shown: true,
  state: "NOT_RECORDED",
  basis: null,
  note: null,
  recordedAt: null
};

test("the three bases are the ones the withdrawal route accepts", () => {
  expect([...BASES]).toEqual(["VERBAL_RECORDED", "VERBAL_WITNESSED", "SIGNED_FORM"]);
  expect(basisOptions()).toEqual([
    { value: "VERBAL_RECORDED", label: "Said on a recording" },
    { value: "VERBAL_WITNESSED", label: "Said before a witness" },
    { value: "SIGNED_FORM", label: "Signed form" }
  ]);
  expect(isWithdrawalBasis("SIGNED_FORM")).toBe(true);
  expect(isWithdrawalBasis("")).toBe(false);
  expect(isWithdrawalBasis("signed_form")).toBe(false);
});

test("a record page names its subject, and only artisans, workshops and files have one", () => {
  expect(fieldVoiceSubjectOf("artisan")).toBe("ARTISAN");
  expect(fieldVoiceSubjectOf("workshop")).toBe("WORKSHOP");
  expect(fieldVoiceSubjectOf("media")).toBe("RECORDING");
  expect(fieldVoiceSubjectOf("product")).toBeNull();
  expect(fieldVoiceSubjectOf("craft")).toBeNull();
});

test("the path encodes the id and the body trims the note to the route's limit", () => {
  expect(withdrawalPath("RECORDING", "m 1/2")).toBe("/field-voices/withdrawals/RECORDING/m%201%2F2");
  expect(withdrawalBody({ shown: false, basis: "VERBAL_RECORDED", note: "  asked at the workshop  " })).toEqual({
    shown: false,
    basis: "VERBAL_RECORDED",
    note: "asked at the workshop"
  });
  expect(withdrawalBody({ shown: true, basis: "SIGNED_FORM", note: "   " })).toEqual({
    shown: true,
    basis: "SIGNED_FORM",
    note: null
  });
  expect(NOTE_MAX_LENGTH).toBe(500);
  expect(withdrawalBody({ shown: false, basis: "SIGNED_FORM", note: "x".repeat(600) }).note).toHaveLength(500);
});

test("the status line says shown, or withdrawn with its day", () => {
  expect(withdrawalLine(SHOWN, () => "never")).toBe("Shown on KalaVigyan");
  const withdrawn = { ...SHOWN, shown: false, state: "REFUSED", recordedAt: "2026-11-18T06:00:00Z" };
  expect(withdrawalLine(withdrawn, () => "18 Nov 2026")).toBe("Withdrawn from KalaVigyan on 18 Nov 2026");
  expect(withdrawalLine({ ...withdrawn, recordedAt: null }, () => "never")).toBe("Withdrawn from KalaVigyan");
  expect(fieldVoicesText("withdrawnOn")).toBe("Withdrawn from KalaVigyan on {date}");
});

test("every string is short and filled", () => {
  for (const [key, value] of Object.entries(STRINGS.strings)) {
    expect(value.trim(), key).not.toBe("");
    expect(value.length, key).toBeLessThanOrEqual(60);
  }
});

test("the control is on every page a withdrawal is made from", () => {
  const panel = read("components/fieldVoices/FieldVoicesPanel.tsx");
  expect(panel).toContain("isAdmin(user)");
  expect(panel).toContain("adminChromeVisible(user, adminMode)");
  const mounts = DESIGNER_PORTAL
    ? [
        "components/records/RecordView.tsx",
        "app/(protected)/design-workshops/[id]/page.tsx",
        "app/(protected)/designers/[userId]/profile/page.tsx"
      ]
    : ["components/records/RecordView.tsx", "app/(protected)/media/[id]/page.tsx", "app/(protected)/users/[id]/page.tsx"];
  for (const rel of mounts) expect(read(rel), rel).toContain("<FieldVoicesPanel");
  expect(read("components/records/RecordView.tsx")).toContain("fieldVoiceSubjectOf(");
});
