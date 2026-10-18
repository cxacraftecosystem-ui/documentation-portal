import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { sha256Hex } from "@/lib/hashFile";
import { prompts } from "@/lib/questionnairePrompts";

/** DESIGN test 39: an identical picked file is caught before upload (P5) and nothing is sent. */

test("the streaming sha256 matches a whole-file digest across slice boundaries", async () => {
  const bytes = new Uint8Array(9 * 1024 * 1024 + 123).map((_, i) => i % 251);
  const expected = createHash("sha256").update(bytes).digest("hex");
  expect(await sha256Hex(new Blob([bytes]))).toBe(expected);
});

test("P5 copy for the three cases", () => {
  const same = prompts.p5({ name: "Giriraj", date: "7 Jul", case: "SAME_SESSION", addName: "Meena" });
  expect(same.title).toBe("Already uploaded");
  expect(same.body).toBe("This exact file is on Giriraj's session of 7 Jul.");
  expect(same.buttons.map((b) => b.label)).toEqual(["Cancel", "Add Meena to it"]);
  expect(prompts.p5({ name: "G", date: "7 Jul", case: "OTHER_SESSION" }).buttons.map((b) => b.label)).toEqual(["Cancel", "Merge sessions"]);
  expect(prompts.p5({ name: "G", date: "7 Jul", case: "OTHER" }).buttons.map((b) => b.label)).toEqual(["OK"]);
});

test("a picked file is hashed and checked first; an identical one returns before anything is queued", () => {
  const ws = readFileSync(join(__dirname, "..", "components", "questionnaire", "SessionWorkspace.tsx"), "utf8");
  const begin = ws.slice(ws.indexOf("async function begin("), ws.indexOf("function ackedOf("));
  expect(begin).toContain("const sha = pending.file ? await sha256Hex(pending.file) : null;");
  expect(begin).toContain("if (pending.file && answer.identical) {");
  const identicalBranch = begin.slice(begin.indexOf("if (pending.file && answer.identical) {"), begin.indexOf("const covered ="));
  expect(identicalBranch).toContain("return;");
  expect(identicalBranch).not.toMatch(/enqueue|queueClip|proceed\(/);
});
