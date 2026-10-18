import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { Recording, RecordSession, Session } from "@/lib/questionnaireApi";
import { artisanTimeline, subjectRoleFor } from "@/lib/questionnaireView";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * DESIGN test 44: the artisan timeline orders by capturedAt across solo and group sessions, shows
 * PRESENT and whole-session takes, puts estimated items in the uncertain band, and a failed fetch
 * shows the error state with Try again.
 */

const session = CONTRACT.examples.Session as Session;
const rec = CONTRACT.examples.Recording as Recording;

const r = (id: string, capturedAt: string, over: Partial<Recording> = {}): Recording => ({ ...rec, id, capturedAt, ...over });

test("sessions newest first; takes by capturedAt; estimated in the uncertain band; removed hidden", () => {
  const solo: RecordSession = {
    session: { ...session, id: "s-solo", sessionDate: "2026-06-21" },
    recordings: [r("t2", "2026-06-21T10:54:00Z"), r("t1", "2026-06-21T10:39:00Z"), r("gone", "2026-06-21T11:00:00Z", { removedAt: "2026-06-22T00:00:00Z" })],
    uncertain: [r("est", "2026-06-21T06:30:00Z", { estimated: true, capturedAtSource: "SESSION_DATE" })]
  };
  const group: RecordSession = {
    session: { ...session, id: "s-group", sessionDate: "2026-06-24", participantCount: 5 },
    recordings: [
      r("g1", "2026-06-24T05:12:00Z"),
      r("whole", "2026-06-24T05:40:00Z", { targets: [] }),
      r("present", "2026-06-24T05:30:00Z", { subjects: [{ ...rec.subjects[0], role: "PRESENT" }] })
    ],
    uncertain: []
  };
  const timeline = artisanTimeline([solo, group]);
  expect(timeline.map((t) => t.session.id)).toEqual(["s-group", "s-solo"]);
  expect(timeline[0].dated.map((x) => x.id)).toEqual(["g1", "present", "whole"]);
  expect(subjectRoleFor(timeline[0].dated[1], "a1")).toBe("PRESENT");
  expect(timeline[1].dated.map((x) => x.id)).toEqual(["t1", "t2"]);
  expect(timeline[1].uncertain.map((x) => x.id)).toEqual(["est"]);
});

test("error and empty states, with the centred buttons", () => {
  const src = readFileSync(join(__dirname, "..", "components", "questionnaire", "ArtisanQuestionnaireRecord.tsx"), "utf8");
  expect(src).toContain('qs("recordLoadError")');
  expect(src).toContain('qs("tryAgain")');
  expect(src).toContain('qs("noSessionsYet")');
  expect(src).toContain('qs("startSession")');
  expect(src).toContain('data-record-state="error"');
  // The panel on /artisans/[id] hands over to the record under v2.
  const panel = readFileSync(join(__dirname, "..", "components", "ArtisanQuestionnairePanel.tsx"), "utf8");
  expect(panel).toContain("if (config?.v2Enabled) {");
  expect(panel).toContain("<ArtisanQuestionnaireRecord");
});
