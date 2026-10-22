import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { continueBody, latestSession } from "@/components/questionnaire/ContinueWithDialog";
import { readInbox } from "@/components/questionnaire/useInboxCount";
import type { Recording, RecordSession, Session } from "@/lib/questionnaireApi";
import {
  artisanReadDue,
  artisanReadOnInboxReload,
  decisionLabel,
  decisionPartner,
  fileItBody,
  fileItDefaults,
  inboxLabel,
  inboxRowKey,
  normaliseInbox,
  reviewRequest,
  visibleInbox,
  type InboxDecision
} from "@/lib/questionnaireInbox";
import { WEB_ONLY_STRINGS } from "@/lib/strings/qs";
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

test("@qf:B25 every inbox row the backend sends gets a readable label", () => {
  const inbox = normaliseInbox(BACKEND_INBOX);
  expect(inboxLabel("toFile", inbox.toFile[0])).toBe("f-sec-1718.webm · 23 Jun");
  expect(inboxLabel("needsDecision", inbox.needsDecision[0])).toBe("Takes for Giriraj Prasad, 7 Jul 10:42");
  expect(inboxLabel("legacy", inbox.legacy[0])).toBe("Recorded 20 Jun. The question has changed since.");
  // The shared table's row (audit, inbox copy drift), then the workshop the rows are grouped by.
  expect(inboxLabel("setDates", inbox.setDates[0])).toBe("18 legacy sessions, 19–24 Jun · Bankura");
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

const take = (id: string, at: string, over: Partial<Recording> = {}): Recording => ({
  ...rec,
  id,
  capturedAt: at,
  removedAt: null,
  targets: [{ targetKey: "S:sec_d", sectionId: "sec_d", questionLineageId: null, askedQuestionId: null, displayCode: null, label: "Section D" }],
  subjects: [{ artisanId: "a1", role: "ANSWERED", named: false, repeatReason: null, flag: null, position: 1 }],
  ...over
});
const decision: InboxDecision = { recordingId: "r2", sessionId: "s1", decisionKind: "CONCURRENT", targets: ["S:sec_d"], artisanNames: "Giriraj Prasad", capturedAt: "2026-07-07T05:14:00Z" };

test("H13: a decision row finds the take it clashes with, and names both", () => {
  const recordings = [
    take("r1", "2026-07-07T05:12:00Z"),
    take("r2", "2026-07-07T05:14:00Z"),
    take("other-target", "2026-07-07T05:13:00Z", { targets: [{ targetKey: "S:sec_e", sectionId: "sec_e", questionLineageId: null, askedQuestionId: null, displayCode: null, label: "Section E" }] }),
    take("removed", "2026-07-07T05:14:30Z", { removedAt: "2026-07-07T06:00:00Z" })
  ];
  expect(decisionPartner(decision, recordings)?.id).toBe("r1");
  // The server's own pointer wins when it sends one.
  expect(decisionPartner({ ...decision, otherRecordingId: "other-target" }, recordings)?.id).toBe("other-target");
  expect(decisionPartner({ ...decision, recordingId: "nope" }, recordings)).toBeNull();
  expect(decisionLabel(decision, recordings[1], recordings[0])).toBe("Two takes of D for Giriraj, 7 Jul 10:42 and 10:44");
});

test("H13, H14: Keep one, Part 2 and File it happen in the inbox, never by a link to the session", () => {
  const list = readFileSync(join(__dirname, "..", "components", "questionnaire", "InboxList.tsx"), "utf8");
  expect(list).toContain('questionnaireApi.decide(kept, { action: "KEEP_THIS", otherRecordingId: gone })');
  expect(list).toContain('questionnaireApi.decide(later.id, { action: "CONTINUATION", otherRecordingId: earlier.id })');
  expect(list).toContain('{ action: "KEEP_BOTH", otherRecordingId: other.id }');
  expect(list).toContain("questionnaireApi.fileClip(");
  expect(list, "Not needed is stored on the server (audit M22)").toContain("questionnaireApi.dismissClip(r.mediaId)");
  expect(list).not.toMatch(/href=\{sessionHref\(r\.sessionId\)\}>\s*\{qs\("(keepOne|fileIt)"\)\}/);
  // Keep one removes the other take: drawn for content admins only, as the server refuses anyone else.
  expect(list).toContain("{other && canDeleteRecords ? (");
  expect(list).toContain("{choosing && canDeleteRecords && self && other ? (");
  const api = readFileSync(join(__dirname, "..", "lib", "questionnaire", "api.ts"), "utf8");
  expect(api).toContain("`${Q}/media/${encodeURIComponent(mediaId)}/file`");
  expect(api).toContain("`${Q}/media/${encodeURIComponent(mediaId)}/not-needed`");
});

test("H14: File it starts from the candidate, else the clip's session, and sends the contract's targets", () => {
  const row = { mediaId: "m1", name: "f.webm", date: null, sessionId: "s1", candidate: null };
  expect(fileItDefaults(row)).toEqual({ sessionId: "s1", targetKey: "", subjectArtisanIds: [] });
  expect(fileItDefaults({ ...row, candidate: { sessionId: "s2", questionLineageId: "lin_f7", subjectArtisanIds: ["a1"] } })).toEqual({
    sessionId: "s2",
    targetKey: "Q:lin_f7",
    subjectArtisanIds: ["a1"]
  });
  expect(fileItBody({ sessionId: "s2", targetKey: "S:sec_f", subjectArtisanIds: ["a1"] })).toEqual({
    sessionId: "s2",
    targets: [{ sectionId: "sec_f" }],
    subjects: [{ artisanId: "a1", role: "ANSWERED" }]
  });
  expect(fileItBody({ sessionId: "s2", targetKey: "Q:lin_f7", subjectArtisanIds: [] }).targets).toEqual([{ questionLineageId: "lin_f7" }]);
});

test("inbox rows use the shared string table (audit, inbox copy drift)", () => {
  for (const key of ["inboxSetDatesRow", "inboxNoArtisansRow", "inboxEmptyRow"]) expect(Object.keys(WEB_ONLY_STRINGS)).not.toContain(key);
  const inbox = normaliseInbox(BACKEND_INBOX);
  expect(inboxLabel("legacy", { ...inbox.legacy[0], code: "F7", section: "F" })).toBe("F7 recorded 20 Jun. F has changed since.");
  expect(inboxLabel("primaryChanged", { ...inbox.primaryChanged[0], name: "Giriraj Prasad", target: "D" })).toBe("Giriraj's main take of D changed (late upload)");
});
