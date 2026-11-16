import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { overflowActions } from "@/components/questionnaire/QuestionOverflow";
import { STILL_TO_ASK_SHOWN, stillToAsk, stillToAskLine } from "@/components/questionnaire/ReviewFinish";
import { saveLine, saveState } from "@/components/questionnaire/SavePill";
import { sessionMenuItems, MENU_LABEL } from "@/components/questionnaire/SessionActions";
import { headerNames } from "@/components/questionnaire/SessionHeader";
import { scrollSectionIntoView, sectionsToShow, sectionSubline, sectionTone, SECTIONS_SHOWN } from "@/components/questionnaire/SessionWorkspace";
import { uploadButtonLabel } from "@/components/questionnaire/UploadSheet";
import { ApiError } from "@/lib/api";
import { questionProgress } from "@/lib/questionnaire/coverageCache";
import { prompts } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";
import {
  memoryQueueStore,
  newSessionUnit,
  existingSessionUnit,
  queuedRecordBy,
  runUnit,
  sendDetails,
  withRecordBy,
  type ClipStep,
  type QueueApi,
  type SessionUnit
} from "@/lib/questionnaire/queue";
import { flagDuplicates, recordedAtOf, toUpload } from "@/lib/questionnaire/uploads";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");
const OPTS = { multipartThresholdBytes: 8 * 1024 * 1024, partSizeBytes: 5 * 1024 * 1024 };
const noBytes = { put: async () => ({ etag: null }) } as never;

const SECTIONS = [
  { id: "sA", questions: [{ lineageId: "a1" }, { lineageId: "a2" }, { lineageId: "a3" }] },
  { id: "sB", questions: [{ lineageId: "b1" }, { lineageId: "b2" }] },
  { id: "sC", questions: [{ lineageId: "c1" }] }
];
const answered = [{ artisanId: "x", role: "ANSWERED" }];

test("@qf:Q5 progress counts questions: a section take answers every question once, a typed answer is not counted twice", () => {
  const p = questionProgress({
    sections: SECTIONS,
    recordings: [
      { id: "r1", removedAt: null, durationMs: 60_000, targets: [{ sectionId: "sA", questionLineageId: null }], subjects: answered },
      { id: "r2", removedAt: null, durationMs: 192_000, targets: [{ sectionId: "sB", questionLineageId: "b2" }], subjects: answered },
      { id: "r3", removedAt: "2026-11-01T00:00:00Z", durationMs: 1, targets: [{ sectionId: "sC", questionLineageId: null }], subjects: answered }
    ],
    answers: [
      { questionLineageId: "a1", text: "Typed too" },
      { questionLineageId: "b1", text: "Rough cutting takes four days" },
      { questionLineageId: "b2", text: "  " }
    ]
  });
  expect(p.total).toBe(6);
  expect(p.answered).toBe(5);
  expect(p.sectionsDone).toBe(2);
  expect(p.sections.sA).toMatchObject({ answered: 3, total: 3, inSectionTake: true });
  expect(Object.values(p.sections.sA.marks).every((m) => m.kind === "SECTION_TAKE")).toBe(true);
  expect(p.sections.sB.marks.b1).toEqual({ kind: "TYPED", text: "Rough cutting takes four days" });
  expect(p.sections.sB.marks.b2).toMatchObject({ kind: "QUESTION_TAKE", durationMs: 192_000 });
  expect(p.sections.sC).toMatchObject({ answered: 0, total: 1, inSectionTake: false });
  expect(sectionSubline(p.sections.sA)).toBe("3 of 3 · In the section take");
  expect(sectionSubline(p.sections.sC)).toBe("1 question");
  expect(sectionTone(p.sections.sA, false)).toBe("done");
  expect(sectionTone(p.sections.sC, true)).toBe("current");
});

test("@qf:Q5 a group question counts once any participant answers it; a take with no one answering counts nothing", () => {
  const p = questionProgress({
    sections: SECTIONS,
    recordings: [{ targets: [{ questionLineageId: "c1" }], subjects: [{ role: "PRESENT" }, { role: "PRESENT" }] }],
    answers: [{ questionLineageId: "a2", text: "Only Meena answered" }],
    pending: [{ targets: [{ questionLineageId: "a3" }], subjects: [{ role: "PRESENT" }, { role: "ANSWERED" }] }]
  });
  expect(p.sections.sA.answered).toBe(2);
  expect(p.sections.sC.answered).toBe(0);
  expect(qs("questionsOfTotal", { answered: p.answered, total: p.total })).toBe("2 of 6 questions");
});

test("@qf:Q2 the accordion scrolls the opened header to the top, instantly under reduced motion, and caps the list with Show all", () => {
  const calls: ScrollIntoViewOptions[] = [];
  const node = { scrollIntoView: (o: ScrollIntoViewOptions) => calls.push(o) };
  scrollSectionIntoView(node, false);
  scrollSectionIntoView(node, true);
  scrollSectionIntoView(null, false);
  expect(calls).toEqual([
    { behavior: "smooth", block: "start" },
    { behavior: "auto", block: "start" }
  ]);
  expect(sectionsToShow(23, -1, false)).toBe(SECTIONS_SHOWN);
  expect(sectionsToShow(23, 15, false)).toBe(16);
  expect(sectionsToShow(23, 2, true)).toBe(23);
  expect(sectionsToShow(5, -1, false)).toBe(5);
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  expect(ws).toContain("requestAnimationFrame(() => scrollSectionIntoView(");
  expect(ws).toContain("scroll-mt-[var(--session-header-h,0px)]");
  expect(ws).not.toContain("typeAnswers");
  expect(ws).not.toContain("<CoverageGrid");
  expect(read("components", "questionnaire", "SessionReadView.tsx")).toContain("<CoverageGrid");
});

test("@qf:Q3 Record and Upload are one equal pair; by question the overflow holds only Type an answer", () => {
  expect(overflowActions("SECTION")).toEqual(["record", "upload", "type"]);
  expect(overflowActions("QUESTION")).toEqual(["type"]);
  const pair = read("components", "questionnaire", "RecordUploadPair.tsx");
  expect(pair).toContain("<ButtonRow fill");
  expect(qs("recordSection")).toBe("Record section");
  expect(qs("upload")).toBe("Upload");
});

test("@qf:Q4 the upload dialog flags and skips a file already uploaded, counts only what uploads, and dates a file by its metadata", () => {
  const rows = [
    { key: "k1", sha: "aaa", duplicateOn: null as string | null },
    { key: "k2", sha: "bbb", duplicateOn: null as string | null },
    { key: "k3", sha: "ccc", duplicateOn: null as string | null }
  ];
  const flagged = flagDuplicates(rows, [{ sha: "bbb", uploadedAt: "2026-11-14T10:00:00Z" }], { ccc: "2026-11-13T09:00:00Z" }, "2026-11-15");
  expect(flagged).toEqual({ k2: "2026-11-14T10:00:00Z", k3: "2026-11-13T09:00:00Z" });
  const marked = rows.map((r) => ({ ...r, duplicateOn: flagged[r.key] ?? null }));
  expect(toUpload(marked).map((r) => r.key)).toEqual(["k1"]);
  expect(uploadButtonLabel(1)).toBe("Upload 1 file");
  expect(uploadButtonLabel(3)).toBe("Upload 3 files");
  expect(qs("alreadyUploadedOn", { date: "14 Nov" })).toBe("Already uploaded on 14 Nov");
  const now = new Date("2026-11-15T12:00:00Z");
  expect(recordedAtOf({ lastModified: Date.parse("2026-11-14T10:42:00Z") }, "2026-11-15", now)).toEqual({ at: "2026-11-14T10:42:00.000Z", source: "FILE_METADATA" });
  expect(recordedAtOf({ lastModified: 0 }, "2026-11-15", now)).toEqual({ at: "2026-11-15T06:30:00.000Z", source: "SESSION_DATE" });
  expect(read("components", "questionnaire", "UploadSheet.tsx")).toContain(".shaLookup(");
  expect(prompts.micOff().buttons.map((b) => b.label)).toEqual(["Upload instead", "Open Settings"]);
});

test("@qf:Q1 Record by edits a session still waiting to be created in place, and otherwise queues one details step", () => {
  const body = {
    clientKey: "ck-1",
    questionnaireId: "f1",
    workshopId: null,
    designWorkshopId: null,
    sessionDate: "2026-11-15",
    startedAt: "2026-11-15T05:00:00Z",
    purpose: "FIRST_SITTING" as const,
    place: null,
    languageCode: null,
    languageOther: null,
    note: null,
    participants: [{ artisanId: "a1" }]
  };
  const fresh = withRecordBy(newSessionUnit(body), "QUESTION", 0);
  expect(fresh.steps).toHaveLength(1);
  expect(fresh.steps[0]).toMatchObject({ kind: "session", body: { recordBy: "QUESTION" } });
  expect(queuedRecordBy(fresh)).toBe("QUESTION");
  const saved = existingSessionUnit({ id: "s-1", clientKey: "ck-s-1" });
  const once = withRecordBy(saved, "QUESTION", 3, "d1");
  const twice = withRecordBy(once, "SECTION", 3, "d2");
  expect(twice.steps.filter((s) => s.kind === "details")).toEqual([{ kind: "details", status: "PENDING", key: "d2", version: 3, recordBy: "SECTION" }]);
  expect(queuedRecordBy(twice)).toBe("SECTION");
});

test("@qf:Q1 an offline Record by change replays as a PATCH, and a 409 is sent again on the fresh version", async () => {
  const calls: { name: string; body?: unknown }[] = [];
  let stale = true;
  const api = new Proxy({} as QueueApi, {
    get(_t, name: string) {
      return async (...args: unknown[]) => {
        calls.push({ name, body: args[1] });
        if (name === "patchSession") {
          if (stale) {
            stale = false;
            throw new ApiError(409, "STALE_VERSION", { detail: { code: "STALE_VERSION" } });
          }
          return { id: "s-1", version: 9, recordBy: (args[1] as { recordBy: string }).recordBy };
        }
        if (name === "getSession") return { id: "s-1", version: 8 };
        throw new Error(`unexpected ${name}`);
      };
    }
  });
  const store = memoryQueueStore();
  const unit: SessionUnit = withRecordBy(existingSessionUnit({ id: "s-1", clientKey: "ck-s-1" }), "QUESTION", 7, "d1");
  await store.save(unit);
  await runUnit(structuredClone(unit), api, store, noBytes, OPTS);
  expect(calls.map((c) => c.name)).toEqual(["patchSession", "getSession", "patchSession"]);
  expect(calls[0].body).toEqual({ version: 7, recordBy: "QUESTION" });
  expect(calls[2].body).toEqual({ version: 8, recordBy: "QUESTION" });
  expect(await store.get(unit.id)).toBeNull();
  await expect(sendDetails({}, "s-1", { version: 1, recordBy: "SECTION" })).rejects.toThrow();
});

test("@qf:Q8 the session menu keeps its order and puts the destructive items last", () => {
  const all = sessionMenuItems({ saved: true, hasJoin: true, canRemove: true, isContentAdmin: true });
  expect([...all.regular, ...all.danger].map((i) => qs(MENU_LABEL[i]))).toEqual([
    "Edit details",
    "Add researcher",
    "Join code and QR",
    "Read view",
    "Continue another day",
    "Remove artisan",
    "Merge into another session",
    "Delete session"
  ]);
  expect(sessionMenuItems({ saved: true, hasJoin: false, canRemove: false, isContentAdmin: false })).toEqual({
    regular: ["editDetails", "addResearcher", "readView", "continueAnotherDay"],
    danger: []
  });
  expect(sessionMenuItems({ saved: false, hasJoin: true, canRemove: true, isContentAdmin: true })).toEqual({ regular: [], danger: [] });
  const menu = read("components", "questionnaire", "SessionActions.tsx");
  expect(menu.indexOf("data-record-by")).toBeLessThan(menu.indexOf('role="menu"'));
  expect(qs("recordByHelper")).toBe("Applies to this session");
});

test("@qf:Q6 Review lists what is left per section and caps the list", () => {
  const p = questionProgress({ sections: SECTIONS, recordings: [], answers: [{ questionLineageId: "a1", text: "x" }] });
  const rows = stillToAsk(
    [
      { id: "sA", code: "A", title: "Origin" },
      { id: "sB", code: "B", title: "Family" },
      { id: "sC", code: "C", title: "Migration" }
    ],
    p,
    null
  );
  expect(rows.map((r) => [r.code, stillToAskLine(r)])).toEqual([
    ["A", "2 left"],
    ["B", "Not asked · 2"],
    ["C", "Not asked · 1"]
  ]);
  expect(STILL_TO_ASK_SHOWN).toBe(5);
  const served = stillToAsk([], p, { sessionId: "s-1", answered: 1, total: 6, sectionsDone: 0, pendingUploads: 0, needsDecision: [], stillToAsk: [{ sectionId: "sB", code: "B", title: "Family", answered: 1, total: 2, lineages: ["b1"] }] });
  expect(served).toEqual([{ sectionId: "sB", code: "B", title: "Family", left: 1, total: 2 }]);
  expect(qs("reviewSummary", { sections: 3, names: "Meera", pending: 2 })).toBe("3 sections done for Meera. 2 takes still uploading.");
});

test("@qf:Q2 the save pill names the state and opens the uploads", () => {
  const clip = (over: Partial<ClipStep>): ClipStep => ({ kind: "clip", status: "PENDING", clientClipKey: "c1", blobKey: "b", sizeBytes: 1, contentType: "audio/webm", extension: "webm", presign: {} as ClipStep["presign"], recording: {} as ClipStep["recording"], ...over });
  expect(saveLine(saveState([], {}, true))).toBe("Saved");
  expect(saveLine(saveState([clip({}), clip({ clientClipKey: "c2" })], { c1: 0.8 }, true))).toBe("2 takes uploading · 40%");
  expect(saveLine(saveState([clip({})], { c1: 0.8 }, true))).toBe("1 take uploading · 80%");
  expect(saveLine(saveState([clip({})], {}, false))).toBe("Waiting for signal");
  expect(saveLine(saveState([clip({ status: "FAILED", error: "x" })], {}, true))).toBe("Upload failed. Retrying.");
  expect(headerNames({ title: "T", participants: [{ name: "Ramesh Maharana" }, { name: "Bikash Behera" }, { name: "Sarojini Das" }], respondents: [] } as never)).toBe("Ramesh Maharana and 2 others");
  expect(read("components", "questionnaire", "SessionWorkspace.tsx")).toContain("<SavePill");
  expect(read("components", "questionnaire", "SessionWorkspace.tsx")).not.toContain("<OutboxTray sessionId={session.id} />\n      <SessionHeader");
});
