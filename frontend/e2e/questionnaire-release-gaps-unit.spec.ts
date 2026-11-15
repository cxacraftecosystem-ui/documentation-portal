import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { headerLine, headerNames } from "@/components/questionnaire/SessionHeader";
import { overflowActions } from "@/components/questionnaire/QuestionOverflow";
import { profileFillItems, questionCountLabel } from "@/components/questionnaire/SessionWorkspace";
import { questionnaireClient, type SessionList } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";
import { questionnaireStrings } from "@/lib/questionnaire/strings";
import { namesLine, STATUS_PARAM, todayLocal, totalsCounts } from "@/lib/questionnaire/sessionsList";
import { flagDuplicates } from "@/lib/questionnaire/uploads";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

test("@qf:Q7 the status pills ask the server for one state and count its totals", async () => {
  expect(totalsCounts({ all: 41, open: 6, finished: 35 })).toEqual({ ALL: 41, OPEN: 6, FINISHED: 35 });
  expect(STATUS_PARAM).toEqual({ ALL: "all", OPEN: "open", FINISHED: "finished" });
  const asked: string[] = [];
  const client = questionnaireClient((async (path: string) => {
    asked.push(path);
    return { items: [], nextCursor: null, capped: false, state: "open", totals: { all: 3, open: 1, finished: 2 } } satisfies SessionList;
  }) as never);
  const out = await client.sessions({}, { limit: 20, state: "open" });
  expect(out.totals).toEqual({ all: 3, open: 1, finished: 2 });
  await client.sessions({}, { limit: 20, state: "all" });
  expect(asked[0]).toContain("state=open");
  expect(asked[1]).not.toContain("state=");
  const list = read("components", "questionnaire", "SessionsList.tsx");
  expect(list).not.toContain("MAX_PAGES");
  expect(list).toContain("state: STATUS_PARAM[wanted]");
  expect(list).toContain('qs("showAll", { count: total })');
});

test("@qf:Q4 a duplicate shows the date the server says it was uploaded", () => {
  const rows = [{ key: "r1", sha: "aaa" }];
  expect(flagDuplicates(rows, [{ sha: "aaa", uploadedAt: "2026-11-12T08:00:00Z" }], {}, "2026-11-15")).toEqual({ r1: "2026-11-12T08:00:00Z" });
  expect(read("components", "questionnaire", "UploadSheet.tsx")).toContain("!m.pending");
});

test("@qf:Q2 names, today and a single question read as the boards do", () => {
  const people = [{ name: "Ramesh Maharana" }, { name: "Bikash Behera" }, { name: "Sarojini Das" }];
  expect(headerNames({ title: "T", participants: people, respondents: [] } as never)).toBe("Ramesh Maharana and 2 others");
  expect(namesLine({ participants: people, respondents: [] } as never)).toBe("Ramesh Maharana and 2 others");
  expect(headerLine({ crafts: ["Stone carving"], languageCode: null, languageOther: null, sessionDate: todayLocal() })).toBe("Stone carving · Today");
  expect(questionCountLabel(1)).toBe("1 question");
  expect(questionCountLabel(5)).toBe("5 questions");
  expect(qs("openTypedAnswer", { code: "E1" })).toBe("Open typed answer for E1");
  expect(qs("answerTo", { code: "E1" })).toBe("Answer to E1");
  expect(qs("addPeople")).toBe("+ Add");
  expect(qs("backToInterview")).toBe("Back to the interview");
});

test("@qf:Q2 Continue at opens the section named in the link", () => {
  const ws = read("components", "questionnaire", "SessionWorkspace.tsx");
  expect(ws).toContain('new URLSearchParams(window.location.search).get("section")');
  expect(read("lib", "questionnaire", "sessionsList.ts")).toContain("?section=${encodeURIComponent(section)}");
});

test("@qf:Q3 Fill from profile asks the server per artisan and never writes over a typed answer", () => {
  const detail = {
    id: "s-1",
    participants: [{ artisanId: "a1" }, { artisanId: "a2" }, { artisanId: "a3" }],
    answers: [
      { artisanId: "a2", questionLineageId: "q1", text: "Typed by hand", origin: "TYPED", version: 3 },
      { artisanId: "a3", questionLineageId: "q1", text: "Old", origin: "PREFILL", version: 2 }
    ]
  } as never;
  const items = profileFillItems(detail, "q1");
  expect(items.map((i) => [i.artisanId, i.origin, i.text, i.baseVersion])).toEqual([
    ["a1", "PREFILL", "", undefined],
    ["a3", "PREFILL", "", 2]
  ]);
  expect(overflowActions("SECTION")).toEqual(["record", "upload", "type"]);
  expect(qs("fillFromProfile")).toBe("Fill from profile");
  expect(read("components", "questionnaire", "SessionWorkspace.tsx")).toContain('extra={profileFields.has(q.lineageId) && session.participants.length > 0 ? ["prefill"] : []}');
});

test("@qf:Q5 progress never carries a per-artisan qualifier", () => {
  expect(Object.keys(questionnaireStrings)).not.toContain("forArtisansOfTotal");
  for (const text of Object.values(questionnaireStrings).map(String)) expect(text).not.toMatch(/for \{?\w*\}? of \{?\w*\}? artisans/);
});
