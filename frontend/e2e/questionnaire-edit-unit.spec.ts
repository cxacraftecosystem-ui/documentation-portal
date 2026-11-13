import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { interviewBody } from "@/lib/recordPayloads";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");
const DATA_PAGE = read("app", "(protected)", "data", "page.tsx");

function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = codeOnly(PAGE);
const DATA_CODE = codeOnly(DATA_PAGE);

function bodies(withGps: boolean): { edit: Record<string, unknown>; create: Record<string, unknown> } {
  const form = new FormData();
  form.set("recordedAt", "2026-11-10T05:30:00.000Z");
  if (withGps) {
    form.set("latitude", "27.1767");
    form.set("longitude", "78.0081");
  }
  const ctx = {
    automaticTitle: "Interview",
    canPickStatus: false,
    workshopId: "",
    artisanIds: ["artisan-1"],
    responses: [],
    questionnaireId: "questionnaire-1"
  };
  return {
    edit: interviewBody(form, { ...ctx, editingId: "interview-1" }),
    create: interviewBody(form, { ...ctx, editingId: null })
  };
}

test("the page reads ?edit= through the shared hook, inside the Suspense boundary Next 16 needs", () => {
  expect(CODE).toContain('import { useEditDeepLink } from "@/components/hooks/useEditDeepLink"');
  expect(CODE).toMatch(/endpoint:\s*"\/questionnaire\/interviews"/);
  expect(CODE).toMatch(/basePath:\s*"\/questionnaire"/);
  expect(CODE).toMatch(/<Suspense\b/);
  expect(CODE).toMatch(/import \{ Suspense,/);
});

test("an edit PATCHes the record's own path and a capture still POSTs to the collection", () => {
  expect(CODE).toMatch(/method:\s*editingId\s*\?\s*"PATCH"\s*:\s*"POST"/);
  expect(CODE).toMatch(/endpoint:\s*editingId\s*\?\s*`\/questionnaire\/interviews\/\$\{editingId\}`/);
  expect(CODE).toMatch(/const outcome = await saveOrQueue<QuestionnaireInterview>\(\{/);
});

test("the update body never names an instrument, because the API forbids the field", () => {
  const { edit, create } = bodies(true);
  expect(edit).not.toHaveProperty("questionnaireId");
  expect(create.questionnaireId).toBe("questionnaire-1");
  expect(CODE).toContain("const interviewPayload = interviewBody(form, {");
});

test("an edit does not restamp when the capture happened", () => {
  const { edit, create } = bodies(true);
  expect(edit).not.toHaveProperty("recordedAt");
  expect(edit).not.toHaveProperty("recordedTimezone");
  expect(create.recordedAt).toBe("2026-11-10T05:30:00.000Z");
  expect(create.recordedTimezone).toBe("Asia/Kolkata");
});

test("an edit omits location rather than sending null, which would be refused as a clear", () => {
  expect(bodies(false).edit).not.toHaveProperty("location");
  expect(bodies(true).edit.location).toMatchObject({ latitude: 27.1767, longitude: 78.0081 });
  expect(bodies(true).create.location).toMatchObject({ latitude: 27.1767, longitude: 78.0081 });
});

test("the instrument picker is disabled on an edit, as it is on the handset", () => {
  expect(CODE).toMatch(/disabled=\{saving \|\| Boolean\(editing\) \|\| instrumentOptions\.length === 0\}/);
  expect(CODE).toMatch(/const instrumentOptions = useMemo\(/);
  expect(CODE).toMatch(/if \(own && !rows\.some\(\(row\) => row\.id === own\.id\)\) rows\.push\(own\)/);
});

test("the form remounts per record, so the uncontrolled boxes re-seed", () => {
  expect(CODE).toMatch(/key=\{`\$\{editing\?\.id \?\? "new"\}-\$\{draftNonce\}`\}/);
  expect(CODE).toMatch(/ref=\{formRef\}/);
  expect(CODE).toMatch(/defaultValue=\{restoredNotes \?\? editing\?\.notes \?\? null\}/);
  expect(CODE).toMatch(/defaultValue=\{editing\?\.status \?\? "PENDING"\}/);
});

test("the answers are seeded by question id, not by position", () => {
  expect(CODE).toMatch(/\.map\(\(response\) => \[response\.questionId, response\.answerText \?\? ""\]\)/);
  expect(CODE).toMatch(/\(interview\.artisans \?\? \[\]\)\.map\(\(link\) => link\.artisan\?\.id\)/);
});

test("the browse table offers Update, and it is the same ?edit= navigation as everywhere else", () => {
  expect(CODE).toMatch(/href=\{recordUpdateHref\("questionnaire", interview\.id\)!\}/);
  expect(CODE).toMatch(/\{canDeleteRecords \? \(\s*<button className=\{rowAction\("danger"\)\}/);
});

test("the data browser carries the interview id instead of dropping it", () => {
  expect(DATA_CODE).toContain("updateHref: canUpdateRecord(user, value) ? (id: string) => recordUpdateHref(value, id) : null");
  expect(DATA_CODE).not.toMatch(/editHref: \(\) => "\/questionnaire"/);
});

test("the shared-entry banner is not shown against the record being edited", () => {
  expect(CODE).toMatch(/existingEntry && existingEntry\.id !== editing\?\.id/);
});

test("the workshop effect cannot re-point the instrument under an open edit", () => {
  expect(CODE).toMatch(/if \(editing \|\| instrumentTouched\.current\) return;/);
});

test("an edit carries nothing forward and leaves edit mode on save", () => {
  expect(CODE).toMatch(/if \(editingId\) \{\s*resetToCreate\(\);\s*await loadInterviews\(\);\s*return;\s*\}/);
  expect(CODE).toMatch(/function resetToCreate\(\)/);
});

test("these assertions fail on the code and not on the comments explaining the code", () => {
  expect(PAGE).toContain("questionnaireId");
  expect(codeOnly("/* questionnaireId: x */\nconst a = 1;")).not.toContain("questionnaireId");
  expect(codeOnly("// recordedAt\nconst b = 2;")).not.toContain("recordedAt");
  expect(codeOnly(" * editHref: () => \"/questionnaire\"\nconst c = 3;")).not.toContain("editHref");
});
