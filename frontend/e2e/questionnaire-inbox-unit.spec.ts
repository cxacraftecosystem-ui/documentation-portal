import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { continueBody, latestSession } from "@/components/questionnaire/ContinueWithDialog";
import { readInbox } from "@/components/questionnaire/useInboxCount";
import type { Recording, RecordSession, Session } from "@/lib/questionnaireApi";
import {
  artisanReadDue,
  artisanReadOnInboxReload,
  inboxLabel,
  inboxRowKey,
  normaliseInbox,
  reviewRequest,
  visibleInbox
} from "@/lib/questionnaireInbox";
import { prompts } from "@/lib/questionnairePrompts";
import { answersFromTakes, takesFor } from "@/lib/questionnaireView";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * The inbox rows as `GET /questionnaire/inbox` sends them (backend `inbox.py`), the review route,
 * P8 and P10, and the "Continue with…" body.
 */

const BACKEND_INBOX = {
  toFile: [{ mediaId: "m1", name: "f-sec-1718.webm", date: "2026-06-23T05:00:00Z", sessionId: "s1", candidate: null }],
  needsDecision: [
    { recordingId: "r1", sessionId: "s1", decisionKind: "BURST", targets: ["S:sec_d"], artisanNames: "Giriraj Prasad", capturedAt: "2026-07-07T05:12:00Z" }
  ],
  legacy: [{ recordingId: "r2", sessionId: "s2", candidates: [{ questionLineageId: "lin_f7", prompt: "Which tools?", score: 0.8 }], capturedAt: "2026-06-20T06:00:00Z" }],
  setDates: [{ workshopId: "w1", workshop: "Bankura", sessionIds: ["s3", "s4"], from: "2026-06-19", to: "2026-06-24", count: 18 }],
  noParticipants: [{ sessionId: "s5", title: "Legacy", date: "2026-09-23" }],
  emptySessions: [{ sessionId: "s6", title: "Empty", approvedAt: "2026-06-25T06:00:00Z" }],
  primaryChanged: [{ eventId: "e1", recordingId: "r3", change: {}, at: "2026-07-08T06:00:00Z" }],
  counts: { total: 7, olderThan24h: 3 }
};

test("every inbox row the backend sends gets a readable label", () => {
  const inbox = normaliseInbox(BACKEND_INBOX);
  expect(inboxLabel("toFile", inbox.toFile[0])).toBe("f-sec-1718.webm · 23 Jun");
  expect(inboxLabel("needsDecision", inbox.needsDecision[0])).toBe("Takes for Giriraj Prasad, 7 Jul 10:42");
  expect(inboxLabel("legacy", inbox.legacy[0])).toBe("Recorded 20 Jun. The question has changed since.");
  expect(inboxLabel("setDates", inbox.setDates[0])).toBe("18 legacy sessions · Bankura · 19–24 Jun");
  expect(inboxLabel("noParticipants", inbox.noParticipants[0])).toBe("Session of 23 Sep has no artisans");
  expect(inboxLabel("emptySessions", inbox.emptySessions[0])).toBe("Empty session, approved 25 Jun");
  for (const k of ["toFile", "needsDecision", "legacy", "setDates", "noParticipants", "emptySessions", "primaryChanged"] as const) {
    expect(inboxLabel(k, inbox[k][0])).not.toMatch(/undefined|null|\{/);
  }
});

test("a section the server leaves out reads as empty; acknowledged rows drop out of the count", () => {
  const inbox = normaliseInbox({ toFile: BACKEND_INBOX.toFile, emptySessions: BACKEND_INBOX.emptySessions, primaryChanged: BACKEND_INBOX.primaryChanged });
  expect(inbox.legacy).toEqual([]);
  expect(inbox.counts.total).toBe(3);
  const hidden = new Set([inboxRowKey("emptySessions", inbox.emptySessions[0]), inboxRowKey("primaryChanged", inbox.primaryChanged[0])]);
  const shown = visibleInbox(inbox, hidden);
  expect(shown.emptySessions).toEqual([]);
  expect(shown.counts.total).toBe(1);
});

test("Approve and Return post to the questionnaire review route; Return needs words", () => {
  expect(reviewRequest("approve", "s1", "")).toEqual({ path: "/review/questionnaire/s1/approve", body: { notes: null } });
  expect(reviewRequest("revise", "s1", "  ")).toBeNull();
  expect(reviewRequest("revise", "s1", " Add the place ")).toEqual({ path: "/review/questionnaire/s1/revise", body: { notes: "Add the place" } });
});

const rec = CONTRACT.examples.Recording as Recording;

test("P10 counts the artisan's live takes, with the exact copy", () => {
  const recordings: Recording[] = [
    { ...rec, id: "1" },
    { ...rec, id: "2", subjects: [{ ...rec.subjects[0], role: "PRESENT" }] },
    { ...rec, id: "3", removedAt: "2026-07-01T00:00:00Z" },
    { ...rec, id: "4", subjects: [{ ...rec.subjects[0], artisanId: "other" }] }
  ];
  expect(takesFor(recordings, rec.subjects[0].artisanId)).toBe(2);
  const p10 = prompts.p10({ name: "Ravi", count: 3 });
  expect(p10.title).toBe("Remove Ravi from this session?");
  expect(p10.buttons.map((b) => b.label)).toEqual(["Cancel", "Remove"]);
});

test("P8 asks only for answers that came from a take and would change", () => {
  const existing = [
    { artisanId: "a1", text: "Chisel", sourceRecordingId: "r9" },
    { artisanId: "a2", text: "Adze", sourceRecordingId: null },
    { artisanId: "a3", text: "Saw", sourceRecordingId: "r8" }
  ];
  expect(answersFromTakes(existing, ["a1", "a2", "a3"], "Saw")).toEqual([{ artisanId: "a1", sourceRecordingId: "r9" }]);
  expect(answersFromTakes(existing, ["a2"], "x")).toEqual([]);
  const p8 = prompts.p8({ name: "Giriraj", code: "D3", researcher: "Nandini", date: "7 Jul" });
  expect(p8.title).toBe("Replace Giriraj's answer to D3?");
  expect(p8.body).toBe("The current answer came from Nandini's take of 7 Jul.");
  expect(p8.buttons.map((b) => b.label)).toEqual(["Keep current", "Replace"]);
});

test("Continue with… carries on from the latest session; one artisan is one session", () => {
  const s = CONTRACT.examples.Session as Session;
  const sessions: RecordSession[] = [
    { session: { ...s, id: "old", sessionDate: "2026-06-20" }, recordings: [], uncertain: [] },
    { session: { ...s, id: "new", sessionDate: "2026-07-07" }, recordings: [], uncertain: [] }
  ];
  expect(latestSession(sessions)?.session.id).toBe("new");
  expect(continueBody({ fromSessionId: "new", artisanIds: ["a1"], split: "EACH", sessionDate: "2026-10-18" }).split).toBe("ONE");
  expect(continueBody({ fromSessionId: "new", artisanIds: ["a1", "a2"], split: "EACH", sessionDate: "2026-10-18" }).split).toBe("EACH");
});

test("the inbox reads the artisan list once for every Who-was-there row, within the API's cap", () => {
  const source = readFileSync(join(__dirname, "..", "components/questionnaire/InboxList.tsx"), "utf8");
  expect(source.match(/listResource<Artisan>\(/g)?.length).toBe(1);
  expect(source).toContain('listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })');
  expect(source).not.toContain("pageSize: 200");
  expect(source).toContain("options={artisanOptions}");
});

test("a failed artisan read is asked again on the next inbox read, never in a loop", () => {
  expect(artisanReadDue(true, "idle")).toBe(true);
  expect(artisanReadDue(false, "idle")).toBe(false);
  // In flight, done, or failed: nothing new until the inbox is read again.
  for (const state of ["reading", "read", "failed"] as const) expect(artisanReadDue(true, state)).toBe(false);
  expect(artisanReadOnInboxReload("failed")).toBe("idle");
  expect(artisanReadDue(true, artisanReadOnInboxReload("failed"))).toBe(true);
  expect(artisanReadOnInboxReload("read")).toBe("read");
  expect(artisanReadOnInboxReload("reading")).toBe("reading");
  const source = readFileSync(join(__dirname, "..", "components/questionnaire/InboxList.tsx"), "utf8");
  expect(source).toContain('.catch(() => setArtisanRead("failed"))');
  expect(source).toContain("setArtisanRead(artisanReadOnInboxReload);");
  expect(source).toContain("if (!artisanReadDue(needsArtisans, artisanRead)) return;");
});

test("the badge and the inbox page share one inbox request; a reload after an action reads afresh", async () => {
  let calls = 0;
  const fetchInbox = async () => {
    calls += 1;
    return normaliseInbox({});
  };
  await Promise.all([readInbox(false, fetchInbox), readInbox(false, fetchInbox)]);
  expect(calls).toBe(1);
  await Promise.all([readInbox(false, fetchInbox), readInbox(true, fetchInbox)]);
  expect(calls).toBe(3);
  const list = readFileSync(join(__dirname, "..", "components/questionnaire/InboxList.tsx"), "utf8");
  expect(list).toContain("void load(false);");
  expect(list).toContain("setRaw(await readInbox(fresh));");
});
