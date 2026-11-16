import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { questionnaireClient, type RecordingList } from "@/lib/questionnaire/api";
import { prompts } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";
import { DEFAULT_RECORDING_SORT, readRecordingSort, recordingSortKey, recordingSortOptions, writeRecordingSort } from "@/lib/questionnaire/recordingSort";
import { questionLocalTakes, takeSlot, wholeSessionTakes } from "@/lib/questionnaire/takeInPlace";
import { batchOutcome, duplicateStates, duplicateView, flagDuplicates, toUpload, uploadedLine, type QueuedCopy } from "@/lib/questionnaire/uploads";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const NOW = new Date("2026-11-16T09:00:00Z");

test("a duplicate row says what is true: picked twice, still on this device, or already on the server and where", () => {
  const rows = [
    { key: "first", sha: "aaa" },
    { key: "again", sha: "aaa" },
    { key: "queued", sha: "bbb" },
    { key: "here", sha: "ccc" },
    { key: "there", sha: "ddd" },
    { key: "untitled", sha: "eee" },
    { key: "sent-offline", sha: "fff" },
    { key: "fresh", sha: "ggg" },
    { key: "hashing", sha: null }
  ];
  const queued: Record<string, QueuedCopy> = {
    bbb: { at: "2026-11-16T08:00:00Z", uploaded: false, sessionId: "s-1", sameSession: true },
    fff: { at: "2026-11-15T08:00:00Z", uploaded: true, sessionId: "s-1", sameSession: true }
  };
  const server = [
    { sha: "ccc", uploadedAt: "2026-11-13T10:00:00Z", sameSession: true, sessionId: "s-1", sessionTitle: "Interview_Meera", recordingId: "r-here" },
    { sha: "ddd", uploadedAt: "2026-11-13T10:00:00Z", sameSession: false, sessionId: "s-2", sessionTitle: "Interview_2026-11-13_Puri_Sandesh-P", recordingId: "r-there" },
    { sha: "eee", uploadedAt: "2026-11-12T10:00:00Z", sameSession: false, sessionId: "s-3", sessionTitle: "  ", recordingId: null }
  ];
  const states = duplicateStates(rows, server, queued, "2026-11-16", NOW);
  expect(states.first).toBeUndefined();
  expect(states.again).toEqual({ kind: "LIST", at: NOW.toISOString() });
  expect(states.queued).toEqual({ kind: "QUEUED", at: "2026-11-16T08:00:00Z" });
  expect(states.here).toEqual({ kind: "SERVER", at: "2026-11-13T10:00:00Z", sameSession: true, sessionId: "s-1", sessionTitle: "Interview_Meera", recordingId: "r-here" });
  expect(states.there).toMatchObject({ kind: "SERVER", sameSession: false, sessionId: "s-2", sessionTitle: "Interview_2026-11-13_Puri_Sandesh-P" });
  expect(states.untitled).toMatchObject({ kind: "SERVER", sessionTitle: null, recordingId: null });
  expect(states["sent-offline"]).toMatchObject({ kind: "SERVER", sameSession: true, at: "2026-11-15T08:00:00Z" });
  expect(states.fresh).toBeUndefined();
  expect(states.hashing).toBeUndefined();

  expect(duplicateView(states.again)).toEqual({ chip: "Already in this list", where: null, open: null });
  expect(duplicateView(states.queued)).toEqual({ chip: "Uploading from this device", where: null, open: null });
  expect(duplicateView(states.here)).toEqual({ chip: "Already uploaded on 13 Nov", where: "In this session", open: { kind: "TAKE", recordingId: "r-here" } });
  expect(duplicateView(states.there)).toEqual({
    chip: "Already uploaded on 13 Nov",
    where: "In Interview_2026-11-13_Puri_Sandesh-P",
    open: { kind: "SESSION", sessionId: "s-2", recordingId: "r-there" }
  });
  expect(duplicateView(states.untitled)).toEqual({ chip: "Already uploaded on 12 Nov", where: "In another session", open: { kind: "SESSION", sessionId: "s-3", recordingId: null } });

  const marked = rows.map((r) => ({ ...r, duplicateOn: states[r.key]?.at ?? null }));
  expect(toUpload(marked).map((r) => r.key), "every duplicate is skipped").toEqual(["first", "fresh", "hashing"]);
});

test("a pending server match is not a duplicate the sheet names, and the date projection keeps its old answers", () => {
  const sheet = read("components", "questionnaire", "UploadSheet.tsx");
  expect(sheet).toContain("!m.pending");
  expect(sheet).toContain("duplicateStates(");
  expect(sheet).toContain("data-duplicate-open");
  expect(flagDuplicates([{ key: "k1", sha: "aaa" }, { key: "k2", sha: "bbb" }], [{ sha: "aaa", uploadedAt: "2026-11-12T08:00:00Z" }], { bbb: "2026-11-13T09:00:00Z" }, "2026-11-15")).toEqual({
    k1: "2026-11-12T08:00:00Z",
    k2: "2026-11-13T09:00:00Z"
  });
});

test("a finished batch is confirmed once, named for where it went", () => {
  expect(batchOutcome(["a", "b"], new Set(["a"]), new Set())).toEqual({ settled: false, landed: 1, failed: 0 });
  expect(batchOutcome(["a", "b"], new Set(["a", "b"]), new Set())).toEqual({ settled: true, landed: 2, failed: 0 });
  expect(batchOutcome(["a", "b"], new Set(["a"]), new Set(["b"]))).toEqual({ settled: true, landed: 1, failed: 1 });
  expect(batchOutcome(["a"], new Set(), new Set(["a"]))).toEqual({ settled: true, landed: 0, failed: 1 });
  expect(uploadedLine(1, "Section E")).toBe("1 recording uploaded to Section E");
  expect(uploadedLine(3, qs("wholeSessionTarget"))).toBe("3 recordings uploaded to the whole session");
  expect(uploadedLine(2, "E2")).toBe("2 recordings uploaded to E2");
});

test("whole-session takes are the ones with no target, from the server and from this device, and mark no section", () => {
  const take = (id: string, targets: { sectionId?: string | null; questionLineageId?: string | null }[], extra: Partial<{ removedAt: string | null; capturedAt: string }> = {}) => ({
    id,
    clientClipKey: `clip-${id}`,
    capturedAt: extra.capturedAt ?? "2026-11-13T10:00:00Z",
    removedAt: extra.removedAt ?? null,
    targets
  });
  const recordings = [
    take("w2", [], { capturedAt: "2026-11-14T10:00:00Z" }),
    take("w1", []),
    take("gone", [], { removedAt: "2026-11-15T10:00:00Z" }),
    take("e", [{ sectionId: "s-e" }]),
    take("q", [{ questionLineageId: "lin-e2" }])
  ];
  const outbox = [
    { clientClipKey: "clip-w1", targets: [] },
    { clientClipKey: "local-w", targets: [] },
    { clientClipKey: "local-e", targets: [{ sectionId: "s-e" }] },
    { clientClipKey: "local-q1", targets: [{ questionLineageId: "lin-e2" }] },
    { clientClipKey: "local-q2", targets: [{ questionLineageId: "lin-e2" }] }
  ];
  const whole = wholeSessionTakes(recordings, outbox);
  expect(whole.server.map((r) => r.id)).toEqual(["w1", "w2"]);
  expect(whole.local.map((l) => l.clientClipKey), "a landed take is not drawn twice").toEqual(["local-w"]);
  expect(questionLocalTakes("lin-e2", recordings, outbox).map((l) => l.clientClipKey), "every file put on a question gets its own row").toEqual(["local-q1", "local-q2"]);
  const sectionOf = (lineage: string) => (lineage === "lin-e2" ? "s-e" : null);
  expect(takeSlot([], sectionOf)).toEqual({ slot: "W", sectionId: null });
  expect(takeSlot([{ sectionId: "s-e" }], sectionOf)).toEqual({ slot: "S:s-e", sectionId: "s-e" });
  expect(takeSlot([{ questionLineageId: "lin-e2" }], sectionOf)).toEqual({ slot: "Q:lin-e2", sectionId: "s-e" });
});

test("the session screen lists whole-session takes under the sections with Record and Upload as an equal pair", () => {
  const workspace = read("components", "questionnaire", "SessionWorkspace.tsx");
  const block = workspace.slice(workspace.indexOf("data-whole-session\n"), workspace.indexOf("data-whole-session-takes"));
  expect(block).toContain("<RecordUploadPair");
  expect(block).toContain('label={qs("wholeSessionActions")}');
  expect(block).toContain('onUpload={() => setUploadFor({ kind: "SESSION" })}');
  expect(block).toContain("live && !recording");
  expect(workspace).toContain('slot="W"');
  expect(workspace).toContain("<TakeInPlace");
  expect(workspace).not.toMatch(/<ButtonRow>\s*<button type="button" className="field-button" onClick=\{\(\) => press\(\[\], qs\("wholeSession"\), ""\)\}>/);
  expect(workspace).toContain("wantedRecording.current = wanted.get(\"recording\")");
  expect(read("components", "questionnaire", "TakeInPlace.tsx")).toContain("data-take-retry");
  expect(qs("wholeSession")).toBe("Whole session");
  expect(qs("takeCount", { count: 2 })).toBe("2 takes");
});

test("the leave prompt says what happens to takes still uploading, and never that they upload in the background", () => {
  const two = prompts.leave({ count: 2 });
  expect(two.title).toBe("Leave this session?");
  expect(two.body).toBe("2 takes are still uploading. They keep going while this tab is open; if you close it, they finish next time you open your sessions.");
  expect(prompts.leave({ count: 1 }).body).toBe("1 take is still uploading. It keeps going while this tab is open; if you close it, it finishes next time you open your sessions.");
  expect(prompts.leave({ count: 0, recording: true }).body).toBe("The take you're recording stops. You can keep it when you reopen this session.");
  expect(prompts.leave({ count: 1, recording: true }).body).toContain("1 take is still uploading.");
  expect(prompts.leave({ count: 1, recording: true }).body).toContain("You can keep it when you reopen this session.");
  for (const body of [two.body, prompts.leave({ count: 1 }).body]) expect(body).not.toMatch(/background/i);
  expect(read("components", "questionnaire", "SessionHeader.tsx")).toContain("interceptLeave(() => router.push(\"/questionnaire\"))");
  expect(read("components", "questionnaire", "useSessionQueue.ts")).toContain('typeof indexedDB === "undefined" ? memoryQueueStore() : indexedDbQueueStore()');
});

test("the recordings order is Added unless this viewer chose Recorded, and the client asks for it by name", async () => {
  const held = new Map<string, string>();
  const store = { getItem: (k: string) => held.get(k) ?? null, setItem: (k: string, v: string) => void held.set(k, v) };
  expect(DEFAULT_RECORDING_SORT).toBe("added");
  expect(readRecordingSort("u1", store)).toBe("added");
  writeRecordingSort("u1", "recorded", store);
  expect(readRecordingSort("u1", store)).toBe("recorded");
  expect(readRecordingSort("u2", store), "remembered per viewer").toBe("added");
  held.set(recordingSortKey("u3"), "newest");
  expect(readRecordingSort("u3", store)).toBe("added");
  const broken = { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); } };
  expect(readRecordingSort("u1", broken)).toBe("added");
  expect(() => writeRecordingSort("u1", "recorded", broken)).not.toThrow();
  expect(recordingSortOptions()).toEqual([
    { value: "added", label: "Added" },
    { value: "recorded", label: "Recorded" }
  ]);
  const asked: string[] = [];
  const client = questionnaireClient((async (path: string) => {
    asked.push(path);
    return { items: [], nextCursor: null, sort: "added" } satisfies RecordingList;
  }) as never);
  await client.listRecordings({}, null, null, 30, "recorded");
  await client.listRecordings({}, "added|2026-11-16T09:00:00|r1", null, 30, "added");
  await client.listRecordings({ q: "loom" });
  expect(asked[0]).toContain("sort=recorded");
  expect(asked[1]).toContain("sort=added");
  expect(asked[1]).toContain(`cursor=${encodeURIComponent("added|2026-11-16T09:00:00|r1")}`);
  expect(asked[2]).not.toContain("sort=");
  const list = read("components", "questionnaire", "RecordingsList.tsx");
  expect(list).toContain("<SegmentedChoice");
  expect(list).toContain("useRecordingSort()");
  expect(read("components", "questionnaire", "RecordingSearchGroup.tsx")).toContain("useRecordingSort()");
});
