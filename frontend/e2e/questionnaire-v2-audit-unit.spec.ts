import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { titlePreviewInput } from "@/components/questionnaire/SessionStartSheet";
import { answersBySection } from "@/components/questionnaire/SessionReadView";
import { conflictSpec } from "@/components/questionnaire/SessionWorkspace";
import { APP_PLATFORM, APP_VERSION_CODE } from "@/lib/api";
import { coveredSectionsOffline, offlineCheck, overlayFromUnits, takesFromRecordings, withTakes } from "@/lib/coverageCache";
import { sessionTitle } from "@/lib/naming";
import type { Answer, AnswerResult, Recording, Snapshot } from "@/lib/questionnaireApi";
import { appendStep, existingSessionUnit, type ClipStep } from "@/lib/questionnaireQueue";
import { consentSubject, isLegacyTake, pickedFileMeta, takeSubjects } from "@/lib/questionnaireView";
import { mergeStartLists, searchCachedArtisans } from "@/lib/startSheetCache";
import { portalWorkshop, titleWorkshop, type WorkshopRow } from "@/lib/questionnairePortal";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * Regression tests for the Field Repository questionnaire audit of 21 Oct 2026 (web half, Phase 2):
 * one block per item, named by its id. The queue items (C3, C4, C5, H18) are in
 * `questionnaire-queue-unit.spec.ts`, the prompts (H16) in `questionnaire-prompts-unit.spec.ts`, the
 * inbox (H13, H14, copy) in `questionnaire-inbox-unit.spec.ts`; the real-backend run is
 * `questionnaire-v2-stack.spec.ts`.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");
const rec = CONTRACT.examples.Recording as Recording;

test("C7: every call sends the platform and the build number, and the proxy forwards both", () => {
  expect(APP_PLATFORM).toBe("web");
  // No build number in a unit run: no code is sent, which the server never refuses on the web.
  expect(APP_VERSION_CODE).toBeNull();
  const api = read("lib", "api.ts");
  expect(api).toContain('headers.set("X-App-Platform", APP_PLATFORM);');
  expect(api).toContain('if (APP_VERSION_CODE) headers.set("X-App-Version-Code", APP_VERSION_CODE);');
  // Digits only: the server reads the code as a positive integer.
  expect(api).toContain("/^[1-9]\\d{0,8}$/");
  const proxy = read("lib", "server", "apiProxy.ts");
  expect(proxy).toContain('"x-app-version-code"');
  expect(proxy).toContain('"x-app-platform"');
  // The number is the deploy run's, which only rises; a local build reads web-0.
  expect(read("next.config.ts")).toContain("process.env.GITHUB_RUN_NUMBER");
});

test("H15: the Builder is the builder alone, for managers, with the page's Back", () => {
  const page = read("app", "(protected)", "questionnaire", "builder", "page.tsx");
  expect(page).toContain("<BuilderScreen />");
  expect(page).not.toContain("QuestionnairePage");
  const screen = read("components", "questionnaire", "BuilderScreen.tsx");
  expect(screen).toContain("const allowed = canManageQuestionnaire(user);");
  expect(screen).toContain("<RestrictedPanel");
  expect(screen).toContain("<QuestionnaireAdminEditor");
  // PageHeader draws the one Back control (back defaults to true); none is added by hand.
  expect(screen).toContain("<PageHeader title={qs(\"builder\")}");
  expect(screen).not.toContain("back={false}");
  expect(screen).not.toMatch(/<form[\s\S]*onSubmit=\{submit\}/);
  const list = read("components", "questionnaire", "SessionsList.tsx");
  expect(list).toMatch(/\{canManageQuestionnaire\(user\) \? \(\s*<Link className="field-button-secondary" href="\/questionnaire\/builder">/);
});

test("H17: a stopped take's draft stays open until the outbox holds it", () => {
  const recorder = read("components", "questionnaire", "Recorder.tsx");
  const onstop = recorder.slice(recorder.indexOf("recorder.onstop"), recorder.indexOf("recorderRef.current = recorder;"));
  expect(onstop, "Stop no longer marks the draft finished").not.toContain("recorderStore.finish(");
  expect(onstop).toContain("takesInHand.add(draft.takeKey);");
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  const queueClip = ws.slice(ws.indexOf("async function queueClip("), ws.indexOf("function chooseP2("));
  expect(queueClip.indexOf("await enqueue(unitSession, step")).toBeGreaterThan(-1);
  expect(queueClip.indexOf("await recorderStore.discard(take.takeKey)"), "the draft goes only after the outbox write").toBeGreaterThan(queueClip.indexOf("await enqueue(unitSession, step"));
  // A failed write leaves the draft, and P13 offers it back at once.
  expect(queueClip).toContain("void checkRecovery();");
});

test("C4: the workspace keys its outbox unit and its drafts on clientKey, else id", () => {
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  expect(ws).toContain("const queue = useSessionQueue(detail ?? id);");
  expect(ws).toContain("sessionKey: sessionUnitKey(session)");
  expect(ws).not.toContain("detail.clientKey &&");
});

const snapshot: Snapshot = {
  asOfSeq: "7",
  takes: [{ recordingId: "r9", sessionId: "s0", artisanId: "a1", targetKey: "S:secD", role: "ANSWERED", capturedAt: "2026-07-07T05:12:00Z", estimated: false, filing: "FILED", position: 1 }],
  removedRecordingIds: []
};
const ctx = { sectionOf: (l: string) => (l.startsWith("lin_d") ? "secD" : null), labelOf: (k: string) => k };

test("M4: offline, P2 asks from the held snapshot plus this device's outbox", () => {
  // Covered by the snapshot.
  const fromSnapshot = offlineCheck(snapshot, [], { questionnaireId: "q", artisanIds: ["a1"], targets: [{ questionLineageId: "lin_d3" }] }, ctx);
  expect(fromSnapshot.targets[0].perArtisan[0].state).toBe("VIA_SECTION");
  expect(fromSnapshot.targets[0].perArtisan[0].takes).toHaveLength(1);
  // Covered only by a take still in the outbox.
  const clip: ClipStep = {
    kind: "clip",
    status: "PENDING",
    clientClipKey: "k1",
    blobKey: "b1",
    sizeBytes: 1,
    contentType: "audio/webm",
    extension: "webm",
    presign: { contentSha256: null, targets: [{ sectionId: "secE" }], subjectArtisanIds: ["a2"], deviceCapturedAt: "2026-07-08T05:00:00Z", clockOffsetMs: 0 },
    recording: {
      targets: [{ sectionId: "secE" }],
      subjects: [
        { artisanId: "a2", role: "ANSWERED", named: false, repeatReason: null },
        { artisanId: "a3", role: "PRESENT", named: true, repeatReason: null }
      ],
      deviceCapturedAt: "2026-07-08T05:00:00Z",
      clockOffsetMs: 0,
      capturedAtSource: "DEVICE",
      durationMs: 1000,
      rosterCount: 2,
      contentSha256: null,
      continuationOfId: null,
      replacesRecordingId: null,
      ackedRecordingIds: []
    }
  };
  const unit = appendStep(existingSessionUnit({ id: "s1", clientKey: null }), clip);
  const overlay = overlayFromUnits([unit]);
  expect(overlay.map((t) => [t.artisanId, t.targetKey])).toEqual([["a2", "S:secE"]]);
  const fromOutbox = offlineCheck(null, overlay, { questionnaireId: "q", artisanIds: ["a2", "a3"], targets: [{ sectionId: "secE" }] }, ctx);
  expect(fromOutbox.targets[0].perArtisan.map((p) => p.state)).toEqual(["ON_DEVICE", "NOT_RECORDED"]);
  // The session's own takes lie over the snapshot: a take removed since is not counted twice.
  const live = takesFromRecordings([{ ...rec, id: "r9", removedAt: null, sessionId: "s0" }]);
  expect(withTakes(snapshot, live).takes.filter((t) => t.recordingId === "r9")).toHaveLength(live.length);
  // A failed check is never read as "not covered": the workspace falls back on ANY failure.
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  const check = ws.slice(ws.indexOf("async function check("), ws.indexOf("async function dropParticipant("));
  expect(check).toContain("readSnapshot(");
  expect(check).toContain("overlayFromUnits(queue.units)");
  expect(check).not.toContain("offlineCheck(null, [],");
});

test("M4: the start sheet starts with no signal from the lists last read, and counts coverage offline", () => {
  const held = mergeStartLists(
    { workshops: [], instruments: [], artisans: [{ id: "a1", name: "Giriraj", localName: null, place: "Bagru", craft: null, workshopId: "w1" }], sections: {}, at: null },
    { artisans: [{ id: "a2", name: "Meena Pal", localName: null, place: "Kolkata", craft: null, workshopId: "w2" }] }
  );
  expect(held.artisans.map((a) => a.id)).toEqual(["a2", "a1"]);
  expect(searchCachedArtisans(held.artisans, "meen", null).map((a) => a.id)).toEqual(["a2"]);
  expect(searchCachedArtisans(held.artisans, "", "w1").map((a) => a.id)).toEqual(["a1"]);
  const sections = { secD: ["lin_d1", "lin_d2"], secE: ["lin_e1"] };
  const takes = [
    { recordingId: "x", sessionId: "s", artisanId: "a1", targetKey: "Q:lin_d1", role: "ANSWERED", capturedAt: "", estimated: false, filing: "FILED", position: 1 },
    { recordingId: "y", sessionId: "s", artisanId: "a1", targetKey: "Q:lin_d2", role: "ANSWERED", capturedAt: "", estimated: false, filing: "FILED", position: 1 },
    { recordingId: "z", sessionId: "s", artisanId: "a1", targetKey: "S:secE", role: "PRESENT", capturedAt: "", estimated: false, filing: "FILED", position: 1 }
  ] as Snapshot["takes"];
  expect(coveredSectionsOffline(takes, "a1", sections), "D by all its questions; E only PRESENT").toBe(1);
  const sheet = read("components", "questionnaire", "SessionStartSheet.tsx");
  expect(sheet).toContain(".catch(() => held.workshops as unknown as Workshop[])");
  expect(sheet).toContain(".catch(() => held.instruments)");
  expect(sheet).toContain("searchCachedArtisans(readStartLists().artisans, search, workshopId)");
  expect(sheet).toContain("coverageLine(coverage, coverageAge)");
});

test("M22: Answered by — an unticked artisan is PRESENT, never dropped", () => {
  const subjects = takeSubjects(["a1", "a2", "a3"], new Set(["a1", "a3"]), { a1: "REDO", a2: "REDO" });
  expect(subjects.map((s) => [s.artisanId, s.role, s.repeatReason])).toEqual([
    ["a1", "ANSWERED", "REDO"],
    ["a2", "PRESENT", null],
    ["a3", "ANSWERED", null]
  ]);
  expect(takeSubjects(["a1"], new Set(["a1"]), {})[0]).toEqual({ artisanId: "a1", role: "ANSWERED", named: false, repeatReason: null });
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  expect(ws).toContain("subjects: takeSubjects(roster, answered, context.reasons)");
  expect(ws).toContain("data-answered-by");
  expect(ws, "+ Add artisan is wired").toContain("onAddArtisan={session.state === \"OPEN\" ? () => setAdding(!adding) : undefined}");
  expect(ws).toContain('op: "add", artisanId: artisan.id');
});

test("M22: Change consent opens for the artisan who refused, not the first one", () => {
  const take = { ...rec, subjects: [{ ...rec.subjects[0], artisanId: "a1" }, { ...rec.subjects[0], artisanId: "a2" }], consent: { verdict: "REFUSED" as const, source: null } };
  expect(consentSubject(take, [{ artisanId: "a1", consent: { state: "GRANTED", source: null } }, { artisanId: "a2", consent: { state: "REFUSED", source: null } }])).toBe("a2");
  expect(consentSubject(take, [])).toBe("a1");
});

test("M22: P5 reads the session the identical file is on, and its Add and Merge act", () => {
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  const p5 = ws.slice(ws.indexOf("async function identicalPrompt("), ws.indexOf("async function begin("));
  expect(p5).toContain("session.recordings.find((r) => r.id === other.recordingId)");
  expect(p5).toContain("await questionnaireApi.getSession(other.sessionId)");
  expect(p5).toContain("await questionnaireApi.patchRecording(take.id,");
  expect(p5).toContain("await questionnaireApi.mergeInto(session.id, target);");
  expect(p5, "merging is a content admin's act").toContain("canDeleteRecords");
});

test("M22: group mode searches artisans on the server", () => {
  const sheet = read("components", "questionnaire", "SessionStartSheet.tsx");
  const group = sheet.slice(sheet.indexOf('form.mode === "GROUP" ? ('), sheet.indexOf(") : (", sheet.indexOf('form.mode === "GROUP" ? (')));
  expect(group).toContain("<SearchInput value={search} onChange={setSearch}");
  expect(group).toContain("searchable={false}");
});

test("P9 from the outbox: another device, an existing answer, or a pinned one", () => {
  const base: AnswerResult = { artisanId: "a1", questionLineageId: "lin_d3", status: "CONFLICT", answer: null, current: { id: "x", version: 2, text: "Chisels.", origin: "TYPED", attribution: "INDIVIDUAL", pinned: false, sourceRecordingId: null } };
  expect(conflictSpec(base, "Giriraj", "D3").title).toBe("Giriraj already has an answer.");
  expect(conflictSpec({ ...base, current: { ...base.current!, editedBy: { id: "u", name: "Nandini" }, editedAt: "2026-07-07T05:34:00Z" } }, "Giriraj", "D3").body).toBe(
    "Nandini edited Giriraj's answer to D3 at 11:04."
  );
  const pinned = conflictSpec({ ...base, status: "FORBIDDEN" }, "Giriraj", "D3");
  expect(pinned.buttons.map((b) => b.id)).toEqual(["theirs"]);
  for (const spec of [pinned, conflictSpec(base, "G", "D3")]) expect(spec.dismiss).toBeNull();
});

test("Low: the Legacy badge shows on every LEGACY_* take", () => {
  expect(isLegacyTake({ provenance: "LEGACY_META" })).toBe(true);
  expect(isLegacyTake({ provenance: "LEGACY" })).toBe(true);
  expect(isLegacyTake({ provenance: "NATIVE" })).toBe(false);
  expect(isLegacyTake({ provenance: "SHIM" })).toBe(false);
  expect(read("components", "questionnaire", "ArtisanQuestionnaireRecord.tsx")).toContain("isLegacyTake(recording)");
});

test("Low: the read view prints each answer with its question and artisan, in section order", () => {
  const grid = {
    sections: [
      { id: "secB", code: "B", title: "Raw material", questions: [{ lineageId: "lin_b1", displayCode: "B1", prompt: "Which clay?" }] },
      { id: "secD", code: "D", title: "Process", questions: [{ lineageId: "lin_d3", displayCode: "D3", prompt: "Which tools?" }] }
    ],
    cells: []
  };
  const answer = (id: string, lineage: string, artisanId: string, text: string, attribution: Answer["attribution"] = "INDIVIDUAL"): Answer => ({
    id,
    version: 1,
    text,
    origin: "TYPED",
    attribution,
    pinned: false,
    sourceRecordingId: null,
    artisanId,
    questionLineageId: lineage
  });
  const groups = answersBySection(
    [answer("1", "lin_d3", "a1", "Chisels."), answer("2", "lin_b1", "a1", "River clay.", "GROUP_SHARED"), answer("3", "lin_b1", "a2", "River clay.", "GROUP_SHARED")],
    grid,
    { a1: "Giriraj", a2: "Meena" }
  );
  expect(groups.map((g) => g.label)).toEqual(["B Raw material", "D Process"]);
  expect(groups[0].rows).toEqual([{ key: expect.any(String), code: "B1", prompt: "Which clay?", who: "Group answer (2) · Giriraj, Meena", text: "River clay." }]);
  expect(groups[1].rows[0]).toMatchObject({ code: "D3", who: "Giriraj", text: "Chisels." });
});

test("Low: the title preview carries the workshop's locality and craft, as the server titles it", () => {
  const golden = JSON.parse(read("..", "shared", "session-title.json")) as { golden: { case: string; title: string }[] };
  const individual = golden.golden.find((g) => g.case === "Individual")!;
  const row = { id: "w1", title: "Bagru", date: "2026-07-01", place: "Bagru workshop", location: { village: "Bagru", district: "Jaipur", state: "Rajasthan" }, crafts: [{ craft: { name: "Hand block printing" } }] } as unknown as WorkshopRow;
  const workshop = portalWorkshop(row);
  expect(titleWorkshop(workshop)).toEqual({ locality: "Bagru", district: "Jaipur", state: "Rajasthan", craft: "Hand block printing" });
  const title = sessionTitle(
    titlePreviewInput({ sessionDate: "2026-07-01", place: "", workshop, artisans: [{ name: "Giriraj Prasad Chhipa", craft: { name: "Hand Block Printing" } }] })
  );
  expect(title).toBe(individual.title);
  // Two crafts at the workshop: no craft from the workshop, as on the server.
  expect(titleWorkshop(portalWorkshop({ ...row, crafts: [{ craft: { name: "Dokra" } }, { craft: { name: "Terracotta" } }] } as unknown as WorkshopRow))?.craft).toBeNull();
});

test("Low: a picked file is dated the session day, and a name with no extension still uploads", () => {
  const meta = pickedFileMeta({ name: "voice note", type: "audio/mpeg" }, "2026-07-07");
  expect(meta).toEqual({ extension: "mp3", contentType: "audio/mpeg", deviceCapturedAt: "2026-07-07T06:30:00.000Z", capturedAtSource: "SESSION_DATE" });
  expect(pickedFileMeta({ name: "clip.M4A", type: "" }, "2026-07-07").extension).toBe("m4a");
  expect(pickedFileMeta({ name: "My clip (1)", type: "" }, null).extension).toBe("m4a");
  expect(pickedFileMeta({ name: "a.webm", type: "audio/webm" }, null).capturedAtSource).toBe("UPLOAD_TIME");
  expect(read("components", "questionnaire", "SessionWorkspace.tsx")).not.toContain("lastModified");
});

test("Low: the leave guard counts a take being recorded or waiting for Answered by", () => {
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  expect(ws).toContain("const inHand = recording !== null || answering !== null ? 1 : 0;");
  expect(ws).toContain("useLeaveGuard(guarded > 0, () => setLeaveOpen(true));");
});
