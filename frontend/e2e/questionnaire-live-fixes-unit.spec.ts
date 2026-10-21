import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { filesStillToSend, settleBatch, unsentMessage } from "@/lib/questionnaireUploads";

/**
 * Audit 21 Oct 2026, phase 1 (the legacy flow users have today):
 * H21 — a partly failed upload was swallowed and the form reset; a resend re-created the interview
 * and re-uploaded every clip. Legacy status — the form defaulted a new sitting to APPROVED.
 */

const PAGE = readFileSync(join(__dirname, "..", "app", "(protected)", "questionnaire", "page.tsx"), "utf8");

function file(name: string): File {
  return new File(["x"], name, { type: "audio/webm" });
}

test("H21: only the files that failed are kept, matched by name as a multiset", () => {
  const files = [file("a.webm"), file("b.webm"), file("a.webm")];
  expect(filesStillToSend(files, [{ name: "a.webm" }]).length).toBe(1);
  expect(filesStillToSend(files, [{ name: "a.webm" }, { name: "a.webm" }]).map((f) => f.name)).toEqual(["a.webm", "a.webm"]);
  expect(filesStillToSend(files, [])).toEqual([]);
});

test("H21: a batch that failed whole keeps every file; a partial one keeps the failures", async () => {
  const files = [file("a.webm"), file("b.webm")];
  const whole = await settleBatch(files, async () => {
    throw new Error("No files uploaded");
  });
  expect(whole.left).toHaveLength(2);
  const partial = await settleBatch(files, async () => ({ uploaded: ["ok"], failed: [{ name: "b.webm", error: "timeout" }] }));
  expect(partial.left.map((f) => f.name)).toEqual(["b.webm"]);
  expect(partial.uploaded).toEqual(["ok"]);
  const none = await settleBatch([], async () => {
    throw new Error("never called");
  });
  expect(none.left).toEqual([]);
});

test("H21: the message counts and names what is left", () => {
  expect(unsentMessage(["a.webm"])).toBe("Saved, but 1 recording didn't upload (a.webm). Save again to send it.");
  expect(unsentMessage(["a", "b", "c", "d"])).toContain("4 recordings didn't upload (a, b, c and 1 more)");
});

test("H21: the page settles every batch and turns a partial save into an edit of the saved interview", () => {
  expect(PAGE).toMatch(/const interviewBatch = await settleBatch\(mediaFiles,/);
  expect(PAGE).toMatch(/const clipBatchOutcome = await settleBatch\(files,/);
  const branch = PAGE.slice(PAGE.indexOf("if (unsent.length) {"));
  expect(branch).toMatch(/seedFromInterview\(saved\);\s*setMediaFiles\(interviewBatch\.left\);\s*setQuestionAudioFiles\(clipsLeft\);/);
  // No bare `const { uploaded } = await uploadMediaBatch(` left: every result is inspected.
  expect(PAGE).not.toMatch(/const \{ uploaded \} = await uploadMediaBatch\(/);
});

test("Legacy status: a new sitting defaults to PENDING, never APPROVED", () => {
  expect(PAGE).toMatch(/defaultValue=\{editing\?\.status \?\? "PENDING"\}/);
  expect(PAGE).toMatch(/textValue\(form, "status"\) \|\| "PENDING"/);
  expect(PAGE).not.toMatch(/\|\| "APPROVED"/);
});

test("M20: a transcript is offered to a question by the clip's link, not by its caption number", () => {
  expect(PAGE).toMatch(/media\.find\(\(item\) => clipAnswersQuestion\(item, question\) && transcriptAnswerText\(item\.transcriptText\)\)/);
  const rule = PAGE.slice(PAGE.indexOf("function clipAnswersQuestion("), PAGE.indexOf("function captionBelongsToSection("));
  expect(rule).toMatch(/extra\.questionId === question\.id/);
  expect(rule).toMatch(/words\(found\[3\]\) === words\(question\.prompt\)/);
  expect(rule).not.toMatch(/sortOrder/);
});
