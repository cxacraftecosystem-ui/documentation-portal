import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { offlineCheck, preselectReason } from "@/lib/coverageCache";
import type { CoverageCheckResponse, CoverageTake } from "@/lib/questionnaireApi";
import { alreadyRecorded } from "@/lib/questionnairePrompts";
import { CONTRACT } from "./questionnaire-contract-fixture";

/** DESIGN tests 37, 38 and the offline P2 (54, web half): copy, preselected reason, More options, hierarchy rule. */

const take = (over: Partial<CoverageTake> = {}): CoverageTake => ({
  recordingId: "r9",
  position: 1,
  capturedAt: "2026-07-07T05:12:00Z",
  estimated: false,
  durationMs: 14 * 60_000,
  recordedBy: { id: "u3", name: "Nandini" },
  session: { id: "s0", date: "2026-07-07", rosterCount: 3 },
  sameSession: false,
  mine: false,
  repeatReason: null,
  ...over
});

const names = { a1: "Giriraj Prasad Chhipa", a2: "Meena Pal", a3: "Ravi Kumar" };
const WS = readFileSync(join(__dirname, "..", "components", "questionnaire", "SessionWorkspace.tsx"), "utf8");

test("@qf:B11 the contract's check response renders P2 for a group: summary, rows, first-take line", () => {
  const base = CONTRACT.examples.CoverageCheckResponse as CoverageCheckResponse;
  // The contract's answer for Giriraj, with Meena on her first take beside him.
  const check: CoverageCheckResponse = {
    ...base,
    targets: [{ ...base.targets[0], perArtisan: [...base.targets[0].perArtisan, { artisanId: "a2", state: "NOT_RECORDED", viaSection: false, takes: [] }] }]
  };
  const { spec, covered, first } = alreadyRecorded({ check, names, targetLabel: "Section D", code: "D", part2Allowed: false, replaceAllowed: false });
  expect(spec.title).toBe("Already recorded");
  expect(spec.body).toBe("Section D is recorded for 1 of 2 here.");
  expect(covered[0].line).toBe("Giriraj · 14 min · Nandini · 7 Jul · Group of 3");
  expect(first).toEqual(["Meena hasn't been recorded for D. Their take will be their first."]);
  expect(spec.buttons.map((b) => b.label)).toEqual(["Cancel", "Record again"]);
});

test("one subject, a whole-section cover, and a file pick", () => {
  const one: CoverageCheckResponse = {
    asOf: "",
    identical: null,
    suggestedReason: null,
    targets: [{ targetKey: "S:secD", label: "Section D", perArtisan: [{ artisanId: "a1", state: "RECORDED", viaSection: false, takes: [take()] }] }]
  };
  const base = { names, targetLabel: "Section D", code: "D", part2Allowed: false, replaceAllowed: false };
  expect(alreadyRecorded({ ...base, check: one }).spec.body).toBe("Section D was recorded for Giriraj on 7 Jul.");
  const via: CoverageCheckResponse = {
    ...one,
    targets: [{ ...one.targets[0], perArtisan: [{ ...one.targets[0].perArtisan[0], viaSection: true, state: "VIA_SECTION" }] }]
  };
  expect(alreadyRecorded({ ...base, check: via }).spec.body).toBe("Section D was recorded whole for Giriraj on 7 Jul.");
  expect(alreadyRecorded({ ...base, check: one, filePick: true }).spec.buttons[1].label).toBe("Upload again");
});

test("More options: part 2 only when its rule holds, replace only for your own take here", () => {
  const check = CONTRACT.examples.CoverageCheckResponse as CoverageCheckResponse;
  const ids = (p2: boolean, rep: boolean) =>
    alreadyRecorded({ check, names, targetLabel: "Section D", code: "D", part2Allowed: p2, replaceAllowed: rep }).spec.more!.map((b) => b.id);
  expect(ids(false, false)).toEqual(["different"]);
  expect(ids(true, false)).toEqual(["part2", "different"]);
  expect(ids(true, true)).toEqual(["part2", "replace", "different"]);
  // "Use a different question" refiles BEFORE recording: it re-runs the check on the new target.
  expect(WS).toContain("void begin({ ...current.pending, targets, label: labelOf(choice.targetValue)");
  // Cancel records nothing.
  expect(WS).toContain('if (!current || choice.kind === "cancel") return;');
  // P2 is asked before the recorder opens: the recorder is set only after the check.
  expect(WS.indexOf("const answer = await check(pending, sha);")).toBeLessThan(WS.indexOf("setRecording({"));
});

test("the preselected reason: purpose first, then setting, then researcher", () => {
  const earlier = take();
  expect(preselectReason({ purpose: "FOLLOW_UP", rosterCount: 1, earlier, me: "u3" })).toBe("FOLLOW_UP");
  expect(preselectReason({ purpose: "REDO", rosterCount: 1, earlier, me: "u9" })).toBe("REDO");
  expect(preselectReason({ purpose: "FIRST_SITTING", rosterCount: 1, earlier, me: "u3" })).toBe("NEW_SETTING");
  expect(preselectReason({ purpose: "FIRST_SITTING", rosterCount: 3, earlier, me: "u9" })).toBe("RE_INTERVIEW");
  expect(preselectReason({ purpose: "FIRST_SITTING", rosterCount: 3, earlier, me: "u3" })).toBe("REDO");
});

test("offline P2: the snapshot plus the outbox overlay, with the hierarchy rule", () => {
  const base = { recordingId: "r1", sessionId: "s0", artisanId: "a1", targetKey: "S:secD", role: "ANSWERED" as const, capturedAt: "2026-07-07T05:00:00Z", estimated: false, filing: "FILED", position: 1 };
  const snapshot = { asOfSeq: "1", removedRecordingIds: [], takes: [base, { ...base, recordingId: "r2", artisanId: "a2", role: "PRESENT" as const }] };
  const ctx = { sectionOf: (l: string) => (l === "lin_d3" ? "secD" : null), labelOf: (k: string) => k };
  const q = offlineCheck(snapshot, [], { questionnaireId: "q", artisanIds: ["a1", "a2"], targets: [{ questionLineageId: "lin_d3" }] }, ctx);
  expect(q.targets[0].perArtisan.map((p) => p.state)).toEqual(["VIA_SECTION", "NOT_RECORDED"]);
  const sec = offlineCheck({ ...snapshot, takes: [{ ...base, targetKey: "Q:lin_d3" }] }, [], { questionnaireId: "q", artisanIds: ["a1"], targets: [{ sectionId: "secD" }] }, ctx);
  expect(sec.targets[0].perArtisan[0].state).toBe("NOT_RECORDED");
  const overlay = offlineCheck(null, [{ ...base, recordingId: "local", onDevice: true }], { questionnaireId: "q", artisanIds: ["a1"], targets: [{ sectionId: "secD" }] }, ctx);
  expect(overlay.targets[0].perArtisan[0].state).toBe("ON_DEVICE");
});
