import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { changedResponses, editedFields, fillsOnly, keepFilledFields, sameAsStored, submissionRefusal, viewOnly } from "@/lib/recordDiff";
import { artisanBody } from "@/lib/recordPayloads";

const STORED = {
  id: "art-1",
  createdById: "u-master",
  editAccess: "FILL_EMPTY" as const,
  name: "Ramesh Kumar",
  localName: null,
  gender: "Male",
  dateOfBirth: "1980-04-02T00:00:00+00:00",
  craftStartDate: "2000-01-01T00:00:00+00:00",
  experienceYears: 24,
  experienceMonths: null,
  phone: "9876543210",
  phoneCountryCode: "+91",
  email: null,
  place: "Bagru",
  address: null,
  notes: "Carves temple pillars.",
  aadhaarNumber: "XXXX XXXX 1234",
  pehchanCardAvailable: false,
  pehchanCardNumber: null,
  dos: "Wet the stone first.",
  donts: "Never dry it in the sun.",
  craftId: "c-stone",
  workshopId: "w-stone",
  status: "APPROVED",
  recordedTimezone: "Asia/Kolkata",
  extraMetadata: { fieldProvenance: { name: { by: "u-master" } } }
};

function formOf(values: Record<string, string>): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(values)) form.set(key, value);
  return form;
}

const UNTOUCHED = {
  name: "Ramesh Kumar",
  localName: "",
  gender: "Male",
  dateOfBirth: "1980-04-02",
  craftStartDate: "2000-01-01",
  experienceYears: "24",
  experienceMonths: "",
  phone: "98765 43210",
  phoneCountryCode: "+91",
  email: "",
  place: "Bagru",
  address: "",
  notes: "Carves temple pillars.",
  aadhaarNumber: "XXXX XXXX 1234",
  pehchanCardAvailable: "false",
  pehchanCardNumber: "",
  dos: "Wet the stone first.",
  donts: "Never dry it in the sun.",
  status: "APPROVED"
};

function bodyFor(values: Record<string, string>, exif = { items: [] as { filename: string; metadata: Record<string, unknown> }[], remark: "" }) {
  return artisanBody(formOf(values), {
    isUpdate: true,
    craftId: "c-stone",
    aadhaarNotShared: false,
    storedAadhaarNotShared: false,
    workshopId: "w-stone",
    initialStatus: "APPROVED",
    dismissed: [],
    formContract: 2,
    exifItems: exif.items,
    exifRemark: exif.remark
  });
}

test("an untouched artisan edit sends only the form contract", () => {
  expect(editedFields(bodyFor(UNTOUCHED), STORED)).toEqual({ formContract: 2 });
});

test("a filled blank and a real change are sent, and nothing else", () => {
  const sent = editedFields(bodyFor({ ...UNTOUCHED, email: "ramesh@example.com", place: "Bagru Kalan" }), STORED);
  expect(sent).toEqual({ formContract: 2, email: "ramesh@example.com", place: "Bagru Kalan" });
});

test("clearing a filled field is sent as a clear", () => {
  const sent = editedFields(bodyFor({ ...UNTOUCHED, notes: "" }), STORED);
  expect(sent).toEqual({ formContract: 2, notes: null });
});

test("photo exif travels with the save without touching a filled field", () => {
  const sent = editedFields(
    bodyFor(UNTOUCHED, { items: [{ filename: "pillar.jpg", metadata: { Make: "Pixel" } }], remark: "" }),
    STORED
  );
  expect(sent).toEqual({ formContract: 2, extraMetadata: { mediaExif: [{ filename: "pillar.jpg", metadata: { Make: "Pixel" } }] } });
});

test("a create sends the whole body", () => {
  const body = bodyFor(UNTOUCHED);
  expect(editedFields(body, null)).toBe(body);
});

test("dates, numbers and blanks compare by meaning, not by spelling", () => {
  expect(sameAsStored("1980-04-02", "1980-04-02T00:00:00+00:00")).toBe(true);
  expect(sameAsStored("1980-04-03", "1980-04-02T00:00:00+00:00")).toBe(false);
  expect(sameAsStored(24, "24")).toBe(true);
  expect(sameAsStored("", null)).toBe(true);
  expect(sameAsStored("x", null)).toBe(false);
});

test("the edit level from the server decides the form's mode", () => {
  expect(fillsOnly({ editAccess: "FILL_EMPTY" })).toBe(true);
  expect(fillsOnly({ editAccess: "FULL" })).toBe(false);
  expect(viewOnly({ editAccess: "NONE" })).toBe(true);
  expect(fillsOnly(null)).toBe(false);
});

test("a workshop refusal names the real reason", () => {
  expect(submissionRefusal({ canSubmit: true })).toBeNull();
  expect(submissionRefusal({ canSubmit: false, requestStatus: "PENDING" })).toMatch(/waiting on an admin/);
  expect(submissionRefusal({ canSubmit: false, requestStatus: "DENIED" })).toMatch(/denied/);
  expect(submissionRefusal({ canSubmit: false, requestStatus: "REVOKED" })).toMatch(/revoked/);
  expect(submissionRefusal({ canSubmit: false, requestStatus: "GRANTED", accessLevel: "VIEW" })).toMatch(/View only/);
  expect(submissionRefusal({ canSubmit: false, assigned: false })).toMatch(/not assigned/);
});

test("an interview edit sends only new or changed answers, matched by question", () => {
  const stored = [
    { questionId: "q1", answerText: "Teak" },
    { questionId: "q2", answerText: "Sheesham", notes: "seasoned" }
  ];
  const typed = [
    { questionId: "q2", answerText: "Sheesham" },
    { questionId: "q1", answerText: "Teak " },
    { questionId: "q3", answerText: "Brass" }
  ];
  expect(changedResponses(typed, stored)).toEqual([{ questionId: "q3", answerText: "Brass" }]);
  expect(changedResponses([{ questionId: "q1", answerText: "Rosewood" }], stored)).toEqual([{ questionId: "q1", answerText: "Rosewood" }]);
  expect(changedResponses(typed, null)).toEqual(typed);
});

test("the filled-field lock is set again whenever the form remounts", () => {
  for (const name of ["ArtisanForm", "ProductForm"]) {
    const source = readFileSync(join(__dirname, "..", "components", "forms", `${name}.tsx`), "utf8");
    expect(source, name).toMatch(/return lockFilledFields\(formRef\.current, initial, FILLED_\w+_FIELDS, FILLED_FIELD_REASON\);\n {2}\}, \[initial, formKey\]\);/);
  }
});

test("measurement methods travel only with the measurements that changed", () => {
  const stored = { lengthInches: 10, breadthInches: 4, productName: "Tray" };
  const marks = { lengthInches: { method: "PHOTO" }, breadthInches: { method: "TAPE" } };
  expect(editedFields({ lengthInches: 10, breadthInches: 4, measurementMethods: marks, productName: "Tray" }, stored)).toEqual({});
  expect(editedFields({ lengthInches: 12, breadthInches: 4, measurementMethods: marks }, stored)).toEqual({
    lengthInches: 12,
    measurementMethods: { lengthInches: { method: "PHOTO" } }
  });
});

test("a linked-portal refresh fills only empty fields when the viewer can only fill", () => {
  const values = { name: "Meena Devi", localName: "मीना देवी", dateOfBirth: "1980-05-01" };
  expect(keepFilledFields(values, { editAccess: "FILL_EMPTY", name: "Meena", localName: null, dateOfBirth: "" })).toEqual({
    localName: "मीना देवी",
    dateOfBirth: "1980-05-01"
  });
  expect(keepFilledFields(values, { editAccess: "FULL", name: "Meena" })).toEqual(values);
});

test("existing process steps stay as recorded for a fill-only viewer, and new steps can still be added", () => {
  const source = readFileSync(join(__dirname, "..", "components", "forms", "ProcessForm.tsx"), "utf8");
  expect(source).toContain("disabled={fillsOnly(initial) && Boolean(step.serverId)}");
  expect(source).toContain('<p data-testid="step-locked" className="mt-1 text-xs text-ink-500">{FILLED_FIELD_REASON}</p>');
  expect(source).toContain("{fillsOnly(initial) && step.serverId ? null : (");
});
