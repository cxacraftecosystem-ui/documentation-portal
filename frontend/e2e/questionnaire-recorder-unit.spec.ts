import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { prompts } from "@/lib/questionnairePrompts";
import { assembleTake, pickRecorderMime, recoverable, recoveredMinutes, TIMESLICE_MS, type TakeDraft } from "@/lib/recorderStore";

/**
 * DESIGN tests 40 and 41: one recorder at a time (P3), and a tab killed mid-take leaves chunks that
 * reassemble — header first — into a playable take offered by P13 after reload.
 */

const draft: TakeDraft = {
  takeKey: "t1",
  sessionKey: "c-1",
  clientClipKey: "k1",
  deviceCapturedAt: "2026-07-01T12:54:00Z",
  mimeType: "audio/webm",
  targetLabel: "Section D",
  targets: [{ sectionId: "secD" }],
  roster: ["a1"],
  startedAt: 1_000_000,
  finishedAt: null
};

test("chunks reassemble in index order with the header first, duplicates dropped", async () => {
  const chunks = [
    { takeKey: "t1", index: 2, blob: new Blob(["C"]), at: 1_020_000 },
    { takeKey: "t1", index: 0, blob: new Blob(["HEAD"]), at: 1_000_000 },
    { takeKey: "t1", index: 1, blob: new Blob(["B"]), at: 1_010_000 },
    { takeKey: "t1", index: 1, blob: new Blob(["dup"]), at: 1_010_001 }
  ];
  const blob = assembleTake(chunks, "audio/webm");
  expect(await blob.text()).toBe("HEADBC");
  expect(blob.type).toBe("audio/webm");
});

test("@qf:B15 an unfinished draft with chunks is offered by P13, with its length", () => {
  const chunks = [
    { takeKey: "t1", index: 0, blob: new Blob(["x"]), at: draft.startedAt },
    { takeKey: "t1", index: 1, blob: new Blob(["y"]), at: draft.startedAt + 12 * 60_000 }
  ];
  const found = recoverable([draft, { ...draft, takeKey: "t2", finishedAt: 5 }, { ...draft, takeKey: "t3" }], chunks);
  expect(found.map((f) => f.draft.takeKey)).toEqual(["t1"]);
  const minutes = recoveredMinutes(draft, chunks);
  expect(minutes).toBe(12);
  const p13 = prompts.p13({ minutes, target: "Section D" });
  expect(p13.title).toBe("Recovered a recording");
  expect(p13.body).toBe("A 12-minute take of Section D was saved before the app closed.");
  expect(p13.buttons.map((b) => b.label)).toEqual(["Discard", "Keep"]);
});

test("the recorder writes every 10 s chunk, saves the draft first, and never hard-codes a mime type", () => {
  expect(TIMESLICE_MS).toBe(10_000);
  const src = readFileSync(join(__dirname, "..", "components", "questionnaire", "Recorder.tsx"), "utf8");
  expect(src.indexOf("await recorderStore.saveDraft(draft)")).toBeLessThan(src.indexOf("recorder.start(TIMESLICE_MS)"));
  expect(src).toContain("recorderStore.addChunk(");
  expect(src).toContain('wakeLock?.request("screen")');
  expect(pickRecorderMime((t) => t === "audio/mp4")).toEqual({ mimeType: "audio/mp4", extension: "m4a" });
  expect(pickRecorderMime((t) => t.startsWith("audio/webm")).extension).toBe("webm");
});

test("a second recorder asks P3; Keep recording continues the first", () => {
  const p3 = prompts.p3({ target: "Section D", elapsed: "12:40", code: "F3" });
  expect(p3.title).toBe("Stop the Section D recording?");
  expect(p3.body).toBe("It's 12:40 long and will be kept.");
  expect(p3.buttons.map((b) => b.label)).toEqual(["Keep recording", "Stop and record F3"]);
  const ws = readFileSync(join(__dirname, "..", "components", "questionnaire", "SessionWorkspace.tsx"), "utf8");
  expect(ws).toContain("const live = activeRecording();");
  expect(ws).toContain('if (choice === "stop") {');
});
