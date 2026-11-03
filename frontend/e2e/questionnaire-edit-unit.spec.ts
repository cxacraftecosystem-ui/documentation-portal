import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

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

function payloadBranches(): { edit: string; create: string } {
  const start = CODE.indexOf("const interviewPayload = editingId");
  expect(start, "`const interviewPayload = editingId` is how this spec finds the two bodies").toBeGreaterThan(-1);
  const end = CODE.indexOf("const outcome = await saveOrQueue", start);
  expect(end, "the payload is expected to be built immediately before the save").toBeGreaterThan(start);
  const region = CODE.slice(start, end);
  const split = region.search(/\n\s*:\s*\{\s*\n/);
  expect(split, "the payload is expected to be a ternary with an object literal on each side").toBeGreaterThan(-1);
  return { edit: region.slice(0, split), create: region.slice(split) };
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
  const { edit, create } = payloadBranches();
  expect(edit).not.toContain("questionnaireId");
  expect(create).toContain("questionnaireId: questionnaireId || null");
});

test("an edit does not restamp when the capture happened", () => {
  const { edit, create } = payloadBranches();
  expect(edit).not.toContain("recordedAt");
  expect(edit).not.toContain("recordedTimezone");
  expect(create).toContain("recordedAt");
  expect(create).toContain("recordedTimezone");
});

test("an edit omits location rather than sending null, which would be refused as a clear", () => {
  const { edit, create } = payloadBranches();
  expect(edit).toMatch(/\.\.\.\(location \? \{ location \} : \{\}\)/);
  expect(create).toMatch(/\blocation\b/);
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
  expect(DATA_CODE).toMatch(/editHref: \(id\) => `\/questionnaire\?edit=\$\{id\}`/);
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
