import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  provenanceOf,
  respondentsFor,
  sessionCreateBody,
  startBlocked,
  withArtisans,
  withGuest,
  withMode,
  withNewArtisan,
  workshopPlace,
  type StartGuest,
  type StartSheetState
} from "@/components/questionnaire/SessionStartSheet";
import type { Session, SessionDetail } from "@/lib/questionnaire/api";
import {
  byStatus,
  continuePoint,
  namesLine,
  openToday,
  percentOf,
  questionProgress,
  sectionsDone,
  sessionHref,
  statusCounts,
  subLine
} from "@/lib/questionnaire/sessionsList";

const ROOT = join(__dirname, "..");
const SHEET = readFileSync(join(ROOT, "components", "questionnaire", "SessionStartSheet.tsx"), "utf8");
const LIST = readFileSync(join(ROOT, "components", "questionnaire", "SessionsList.tsx"), "utf8");

const form: StartSheetState = {
  workshopId: "w1",
  questionnaireId: "q1",
  sessionDate: "2026-11-15",
  mode: "INDIVIDUAL",
  artisanIds: ["a1"],
  guests: [],
  purpose: "FIRST_SITTING",
  place: "Lalitgiri",
  languageCode: "or",
  languageOther: "",
  note: ""
};
const guest: StartGuest = { localId: "g1", name: "Shopper", kind: "GUEST", notes: "" };
const me: StartGuest = { localId: "g2", name: "", kind: "SELF", notes: "" };

test("@qf:Q1 mode is Individual or Group, and switching keeps Android's rules", () => {
  expect(SHEET).not.toContain('value: "GUEST"');
  expect(SHEET).toMatch(/\{ value: "INDIVIDUAL", label: qs\("modeIndividual"\) \},\s*\{ value: "GROUP", label: qs\("modeGroup"\) \}\s*\]/);
  const group = { ...form, mode: "GROUP" as const, artisanIds: ["a1", "a2"], guests: [guest] };
  expect(withMode(group, "INDIVIDUAL")).toMatchObject({ mode: "INDIVIDUAL", artisanIds: ["a1"], guests: [] });
  expect(withMode({ ...group, artisanIds: [] }, "INDIVIDUAL")).toMatchObject({ artisanIds: [], guests: [guest] });
  expect(withMode(form, "GROUP")).toMatchObject({ mode: "GROUP", artisanIds: ["a1"] });
});

test("@qf:Q1 + New artisan, Someone else and Myself sit beside the picker with Android's semantics", () => {
  const actions = SHEET.slice(SHEET.indexOf("data-start-subject-actions"), SHEET.indexOf("coverageLine(coverage, coverageAge)"));
  expect(actions.indexOf('qs("newArtisan")')).toBeLessThan(actions.indexOf('qs("modeSomeoneElse")'));
  expect(actions.indexOf('qs("modeSomeoneElse")')).toBeLessThan(actions.indexOf('qs("myself")'));
  expect(withGuest(form, guest)).toMatchObject({ artisanIds: [], guests: [guest] });
  expect(withGuest({ ...form, mode: "GROUP" }, guest)).toMatchObject({ artisanIds: ["a1"], guests: [guest] });
  expect(withArtisans({ ...form, artisanIds: [], guests: [guest] }, ["a2"])).toMatchObject({ artisanIds: ["a2"], guests: [] });
  expect(withArtisans({ ...form, mode: "GROUP", guests: [guest] }, ["a1", "a2"])).toMatchObject({ artisanIds: ["a1", "a2"], guests: [guest] });
  expect(withNewArtisan({ ...form, guests: [guest] }, "a9")).toMatchObject({ artisanIds: ["a9"], guests: [] });
  expect(withNewArtisan({ ...form, mode: "GROUP" }, "a9").artisanIds).toEqual(["a1", "a9"]);
  expect(startBlocked({ ...form, artisanIds: [], guests: [me] })).toBe(false);
  expect(startBlocked({ ...form, artisanIds: [], guests: [{ ...guest, name: "  " }] })).toBe(true);
  expect(respondentsFor([me, { ...guest, name: "  Ravi   Kumar ", notes: " sells at the haat " }], "Nandini")).toEqual([
    { name: "Nandini", kind: "SELF", notes: null },
    { name: "Ravi Kumar", kind: "GUEST", notes: "sells at the haat" }
  ]);
});

test("@qf:Q1 Record by is a segmented Section | Question before Saved as, sent with the session", () => {
  expect(SHEET.indexOf('qs("fieldRecordBy")')).toBeGreaterThan(SHEET.indexOf('qs("fieldNote")'));
  expect(SHEET.indexOf('qs("fieldRecordBy")')).toBeLessThan(SHEET.indexOf('qs("titlePreviewLabel")'));
  expect(sessionCreateBody(form, "c1").recordBy).toBe("SECTION");
  expect(sessionCreateBody({ ...form, recordBy: "QUESTION" }, "c2").recordBy).toBe("QUESTION");
});

test("@qf:Q1 a provenance tag shows only while the value is still the auto-filled one", () => {
  const base = {
    workshopChosen: true,
    resolved: { questionnaireId: "q1", reason: "PRIMARY_BINDING" },
    questionnaireId: "q1",
    questionnaireTouched: false,
    place: "Lalitgiri",
    placeAuto: "Lalitgiri",
    crafts: ["Stone carving"],
    craftsByHand: [] as string[],
    craftSources: [{ name: "Meera Devi", craft: "Stone carving" }],
    spoken: { value: "or", code: "or", source: "artisan" as const }
  };
  expect(provenanceOf(base)).toEqual({ questionnaire: true, place: true, crafts: "Meera Devi", spoken: "Meera Devi" });
  expect(provenanceOf({ ...base, questionnaireTouched: true }).questionnaire).toBe(false);
  expect(provenanceOf({ ...base, resolved: { questionnaireId: "q1", reason: "GLOBAL_DEFAULT" } }).questionnaire).toBe(false);
  expect(provenanceOf({ ...base, place: "Cuttack" }).place).toBe(false);
  expect(provenanceOf({ ...base, crafts: ["Stone carving", "Weaving"], craftsByHand: ["Weaving"] }).crafts).toBeNull();
  expect(provenanceOf({ ...base, spoken: { value: "hi", code: "or", source: "artisan" } }).spoken).toBeNull();
  expect(provenanceOf({ ...base, spoken: { value: "or", code: "or", source: "researcher" } }).spoken).toBeNull();
  expect(workshopPlace({ locality: " Lalitgiri ", district: "Cuttack" })).toBe("Lalitgiri");
  expect(workshopPlace({ locality: null, district: "Cuttack" })).toBe("Cuttack");
  expect(SHEET).toContain('qs("fromThisWorkshop")');
  expect(SHEET).toContain('qs("fromRecordOf", { name: provenance.crafts })');
  expect(SHEET).toContain('qs("fromRecordOf", { name: provenance.spoken })');
});

function session(id: string, over: Partial<Session> = {}): Session {
  return {
    id,
    clientKey: null,
    version: 1,
    title: `Interview_${id}`,
    note: null,
    legacyTitle: null,
    questionnaire: { id: "q1", title: "Form" },
    workshop: { id: "w1", name: "4th Craft Toolkit Workshop" },
    designWorkshopId: null,
    sessionDate: "2026-11-15",
    sessionDateEstimated: false,
    startedAt: "2026-11-15T04:00:00Z",
    endedAt: null,
    closedAt: null,
    state: "OPEN",
    purpose: "FIRST_SITTING",
    origin: "NATIVE",
    mode: "INDIVIDUAL",
    participantCount: 1,
    place: null,
    crafts: ["Stone carving"],
    languageCode: "or",
    languageOther: null,
    joinCode: null,
    joinQr: null,
    participants: [{ artisanId: "a1", name: "Meera Devi", joinedAt: null, leftAt: null, consent: null }],
    researchers: [{ userId: "u1", name: "Nandini", role: "LEAD" }],
    status: "PENDING",
    submittedAt: null,
    reviewReasons: [],
    counts: { sections: 3, takes: 4, needsDecision: 0, answeredQuestions: 18, totalQuestions: 108 },
    ...over
  };
}

test("@qf:Q7 All, Open and Finished count and filter the loaded sessions", () => {
  const rows = [session("s1"), session("s2", { state: "CLOSED" }), session("s3", { state: "CLOSED" })];
  expect(statusCounts(rows)).toEqual({ ALL: 3, OPEN: 1, FINISHED: 2 });
  expect(byStatus(rows, "OPEN").map((s) => s.id)).toEqual(["s1"]);
  expect(byStatus(rows, "FINISHED").map((s) => s.id)).toEqual(["s2", "s3"]);
  expect(byStatus(rows, "ALL")).toHaveLength(3);
  expect(LIST).toContain('qs("showingOf", { shown: shown.length, total: filtered.length })');
  expect(LIST).toContain('qs("showAll", { count: filtered.length })');
});

test("@qf:Q7 rows show question progress, names and crafts with the workshop", () => {
  const s = session("s1");
  expect(questionProgress(s)).toEqual({ answered: 18, total: 108 });
  expect(percentOf(questionProgress(s))).toBe(17);
  expect(questionProgress(session("s2", { counts: { sections: 0, takes: 0, needsDecision: 0 } }))).toBeNull();
  expect(questionProgress({ ...s, progress: { answeredQuestions: 20, totalQuestions: 108, sections: [] } })).toEqual({ answered: 20, total: 108 });
  expect(namesLine(s)).toBe("Meera Devi");
  const three = session("s3", {
    participants: ["Ramesh Maharana", "Sita", "Gita"].map((name, i) => ({ artisanId: `a${i}`, name, joinedAt: null, leftAt: null, consent: null }))
  });
  expect(namesLine(three)).toBe("Ramesh Maharana and 2 others");
  expect(subLine(s, {})).toBe("Stone carving · 4th Craft Toolkit Workshop");
  expect(subLine(s, {}, true)).toBe("Stone carving · 4th Craft Toolkit Workshop · Odia");
  expect(subLine({ ...s, workshop: null, designWorkshopId: "dw1" }, { dw1: "Bagru 2026" })).toBe("Stone carving · Bagru 2026");
  expect(sessionHref(s)).toBe("/questionnaire/sessions/s1");
  expect(sessionHref(s, "secE")).toBe("/questionnaire/sessions/s1?section=secE");
  expect(sessionHref({ ...s, state: "CLOSED" })).toBe("/questionnaire/sessions/s1/view");
  expect(LIST).not.toContain('qs("colCoverage")');
});

test("@qf:Q7 the open-today card picks today's open session and continues at the last section worked in", () => {
  const rows = [session("old", { sessionDate: "2026-11-14" }), session("other", { researchers: [] }), session("mine", { startedAt: "2026-11-15T03:00:00Z" }), session("done", { state: "CLOSED" })];
  expect(openToday(rows, "2026-11-15", "u1")?.id).toBe("mine");
  expect(openToday(rows, "2026-11-15", null)?.id).toBe("other");
  expect(openToday(rows, "2026-11-16", "u1")).toBeNull();
  const grid = { questionnaireId: "q1", artisans: [], sections: ["A", "D", "E"].map((code) => ({ sectionId: `sec${code}`, code, title: code, cells: [] })) };
  const take = (capturedAt: string, sectionId: string | null, displayCode: string | null) =>
    ({ capturedAt, removedAt: null, targets: [{ targetKey: "x", sectionId, questionLineageId: null, askedQuestionId: null, displayCode, label: "" }] }) as unknown as SessionDetail["recordings"][number];
  const progress = { answeredQuestions: 8, totalQuestions: 20, sections: [{ sectionId: "secA", answeredQuestions: 5, totalQuestions: 5, inSectionTake: true }, { sectionId: "secD", answeredQuestions: 3, totalQuestions: 9, inSectionTake: false }] };
  const detail = { grid, progress, recordings: [take("2026-11-15T04:00:00Z", "secA", "A"), take("2026-11-15T05:00:00Z", null, "E3")] } as unknown as SessionDetail;
  expect(continuePoint(detail)).toEqual({ sectionId: "secE", code: "E3" });
  expect(continuePoint({ ...detail, recordings: [] })).toEqual({ sectionId: "secD", code: "D" });
  expect(sectionsDone(detail)).toEqual({ done: 1, total: 3 });
  expect(LIST).toContain('qs("continueAt", { code: point.code })');
  expect(LIST).toContain('qs("questionsOfTotal", progress)');
});

test("@qf:Q7 Inbox and New session stay as buttons; Recordings, Coverage, Forms and Deleted live in More", () => {
  const actions = LIST.slice(LIST.indexOf("actions={"), LIST.indexOf("<AnchoredPopover"));
  expect(actions).toContain('href="/questionnaire/inbox"');
  expect(actions).toContain('href="/questionnaire/sessions/new"');
  expect(actions).not.toContain("/questionnaire/recordings");
  const menu = LIST.slice(LIST.indexOf("<AnchoredPopover"), LIST.indexOf("</AnchoredPopover>"));
  for (const item of ['href="/questionnaire/recordings"', 'href="/questionnaire/coverage"', "href={FORMS_HREF}", "data-deleted-toggle"]) expect(menu).toContain(item);
});
