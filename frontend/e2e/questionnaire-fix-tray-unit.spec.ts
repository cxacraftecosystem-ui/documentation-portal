import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { memoryQueueStore, type SessionUnit } from "@/lib/questionnaire/queue";
import { discardStep, sendAgain, sendWithoutConflicts, stuckSteps, trayActions, TRAY_COPY } from "@/lib/questionnaireFixTray";

/** The questionnaire outbox's fix tray (UNIFY B39): Send again, Discard, Send without the conflicting answers. */

const item = (artisanId: string, lineage: string, text: string) => ({
  artisanId,
  questionLineageId: lineage,
  text,
  origin: "TYPED" as const,
  attribution: "INDIVIDUAL" as const,
  sourceRecordingId: null
});

function unit(): SessionUnit {
  return {
    id: "ck-1",
    sessionId: "s-1",
    createdAt: "2026-10-22T05:00:00Z",
    steps: [
      { kind: "participant", status: "FAILED", op: "add", artisanId: "a2", at: "2026-10-22T05:01:00Z", idempotencyKey: "p-1", error: "That artisan was merged." },
      {
        kind: "clip",
        status: "FAILED",
        clientClipKey: "clip-1",
        blobKey: "blob-1",
        label: "Section D",
        sizeBytes: 10,
        contentType: "audio/webm",
        extension: "webm",
        presign: {} as never,
        recording: {} as never,
        attempts: 5,
        error: "The bucket refused the file."
      },
      {
        kind: "answers",
        status: "PENDING",
        key: "answers-1",
        items: [item("a1", "l1", "Mine"), item("a1", "l2", "Also mine")],
        error: "CONFLICT",
        conflicts: [
          { artisanId: "a1", respondentId: null, questionLineageId: "l1", status: "CONFLICT", answer: null, current: { id: "x", version: 3, text: "Theirs" } },
          { artisanId: "a1", respondentId: null, questionLineageId: "l2", status: "CONFLICT", answer: null, current: { id: "y", version: 2, text: "Theirs too" } }
        ] as never
      }
    ]
  };
}

test("@qf:B39 the tray lists what is stuck, with its way forward, and acts through the one writer", async () => {
  const store = memoryQueueStore();
  await store.save(unit());
  await store.putBlob("blob-1", new Blob(["x"]));
  const stuck = stuckSteps(await store.load());
  expect(stuck.map((s) => [s.label, s.reason])).toEqual([
    ["Artisan added", "That artisan was merged."],
    ["Recording · Section D", "The bucket refused the file."],
    ["2 answers", "2 changed on another device"]
  ]);
  expect(trayActions(stuck[1])).toEqual(["sendAgain", "discard"]);
  expect(trayActions(stuck[2])).toEqual(["withoutConflicts", "discard"]);

  // Send again: back to PENDING, the error cleared, the attempts reset.
  await sendAgain(store, "ck-1", "clip:clip-1");
  const clip = (await store.get("ck-1"))!.steps.find((s) => s.kind === "clip")!;
  expect(clip).toMatchObject({ status: "PENDING", attempts: 0 });
  expect(clip.error).toBeUndefined();

  // Send without the conflicting answers: every held answer keeps the other device's text.
  await sendWithoutConflicts(store, "ck-1", "answers:answers-1");
  const answers = (await store.get("ck-1"))!.steps.find((s) => s.kind === "answers")!;
  expect(answers.status).toBe("DONE");

  // Discard: the step leaves the device, with a recording's bytes.
  await discardStep(store, "ck-1", "clip:clip-1");
  expect(store.blobs.has("blob-1")).toBe(false);
  await discardStep(store, "ck-1", "participant:p-1");
  expect(store.units.size).toBe(0);

  expect(TRAY_COPY.withoutConflicts).toBe("Send without the conflicting answers");
  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "questionnaire", "page.tsx"), "utf8");
  expect(page).toContain("<QuestionnaireFixTray />");
});
