import { expect, test } from "@playwright/test";

import { answerItems } from "@/components/questionnaire/AnswerBox";
import type { Recording } from "@/lib/questionnaireApi";
import { prompts } from "@/lib/questionnairePrompts";
import { approveBlock, reviewFlags } from "@/lib/questionnaireView";
import { CONTRACT } from "./questionnaire-contract-fixture";

/** DESIGN test 47: P9 conflict copy, the review flags line, Approve disabled with its reason. */

const rec = CONTRACT.examples.Recording as Recording;

test("P9 copy and buttons", () => {
  const p9 = prompts.p9({ researcher: "Nandini", name: "Giriraj", code: "D3", time: "11:04" });
  expect(p9.title).toBe("Changed on another device");
  expect(p9.body).toBe("Nandini edited Giriraj's answer to D3 at 11:04.");
  expect(p9.buttons.map((b) => b.label)).toEqual(["Keep theirs", "Keep mine"]);
});

test("a group answer is one GROUP_SHARED item per artisan; an existing answer carries its version", () => {
  const items = answerItems({
    sessionId: "s1",
    lineageId: "lin_d3",
    text: "Chisel",
    forWhom: "__all__",
    participants: [{ artisanId: "a1" }, { artisanId: "a2" }, { artisanId: "a3" }],
    existing: [{ id: "ans1", version: 2, text: "old", origin: "TYPED", attribution: "INDIVIDUAL", pinned: false, sourceRecordingId: null, artisanId: "a1" }]
  });
  expect(items).toHaveLength(3);
  expect(items.every((i) => i.attribution === "GROUP_SHARED")).toBe(true);
  expect(items[0].baseVersion).toBe(2);
  expect(items[1]).not.toHaveProperty("baseVersion");
  const one = answerItems({ sessionId: "s1", lineageId: "lin_d3", text: "x", forWhom: "a2", participants: [{ artisanId: "a1" }, { artisanId: "a2" }], existing: [] });
  expect(one).toEqual([expect.objectContaining({ artisanId: "a2", attribution: "INDIVIDUAL" })]);
});

test("Approve is held with the reason; the flags line", () => {
  expect(approveBlock({ participants: 1, liveRecordings: 2, answers: 0, needsDecision: 1 })).toBe("Decide on 1 take first.");
  expect(approveBlock({ participants: 0, liveRecordings: 2, answers: 0, needsDecision: 0 })).toBe("Add who was there first.");
  expect(approveBlock({ participants: 2, liveRecordings: 0, answers: 0, needsDecision: 0 })).toBe("Nothing recorded or answered yet.");
  expect(approveBlock({ participants: 2, liveRecordings: 1, answers: 0, needsDecision: 0 })).toBeNull();
  const recordings: Recording[] = [
    { ...rec, id: "1", subjects: [{ ...rec.subjects[0], repeatReason: "REDO" }] },
    { ...rec, id: "2", subjects: [{ ...rec.subjects[0], repeatReason: "FOLLOW_UP" }], filing: "NEEDS_DECISION" },
    { ...rec, id: "3", estimated: true, capturedAtSource: "SESSION_DATE" }
  ];
  expect(reviewFlags(recordings)).toBe("2 repeats · 1 needs a decision · dates estimated");
});
