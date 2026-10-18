import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { defaultPicks, movesSentence, plural, resultValue, undoState, type MergeField, type MergeSummary } from "@/lib/artisanMerge";
import {
  autoMapLine,
  bucketsNeedingReview,
  needsReviewCount,
  reviewRungs,
  suggestedCount,
  totalNeedsReview,
  type MappingBucket,
  type WorkshopMappingPlan
} from "@/components/settings/workshopMapping";

/**
 * Two data-hygiene tools (2026-10-20), their pure halves.
 *
 * A6 — the "Records not filed under a workshop" card now leads with what is left for a PERSON: the
 * server files the clear-cut rows (a parent record already names the workshop) once a night. Every
 * count falls back to the older payload, so the card still reads right against a server without the
 * nightly job.
 *
 * C24 — the duplicate-artisan merge. The server decides the defaults and the moves; these helpers
 * only word them.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function bucket(partial: Partial<MappingBucket>): MappingBucket {
  return {
    bucket: "media",
    singular: "media file",
    plural: "media files",
    unassigned: 0,
    resolved: 0,
    unresolved: 0,
    byRung: [],
    byReason: [],
    byWorkshop: [],
    rows: [],
    rowsTruncated: false,
    applied: null,
    ...partial
  };
}

test("the card counts only what needs a person, from the new fields or the old ones", () => {
  const fresh = bucket({ unassigned: 10, resolved: 8, unresolved: 2, clearCut: 6, needsReview: 4 });
  expect(needsReviewCount(fresh)).toBe(4);
  expect(suggestedCount(fresh)).toBe(2);
  // An older server: no clearCut field, so it is read off the PARENT rung count.
  const old = bucket({
    unassigned: 10,
    resolved: 8,
    unresolved: 2,
    byRung: [
      { rung: "PARENT", copy: "the record it belongs to already names that workshop", count: 6 },
      { rung: "WINDOW", copy: "it was recorded inside that workshop's dates", count: 2 }
    ]
  });
  expect(needsReviewCount(old)).toBe(4);
  expect(reviewRungs(old).map((entry) => entry.rung)).toEqual(["WINDOW"]);
  const plan: WorkshopMappingPlan = {
    workshops: [],
    buckets: [old, bucket({ bucket: "products", unassigned: 3, resolved: 3, clearCut: 3, needsReview: 0 })],
    totals: { unassigned: 13, resolved: 11, unresolved: 2, applied: null }
  };
  expect(totalNeedsReview(plan)).toBe(4);
  expect(bucketsNeedingReview(plan).map((entry) => entry.bucket)).toEqual(["media"]);
});

test("the nightly line says when it runs and what it last did, in a few words", () => {
  const status = { enabled: true, runsAt: "02:00", timezone: "Asia/Kolkata", lastRunAt: "2026-10-20T20:30:00Z", lastFiled: 12 };
  expect(autoMapLine(status, 3)).toBe("Every night at 02:00. 3 records tonight. Last run filed 12.");
  expect(autoMapLine({ ...status, lastRunAt: null }, 0)).toBe("Every night at 02:00.");
  expect(autoMapLine({ ...status, enabled: false }, 1)).toBe("1 record waiting to be filed.");
  expect(autoMapLine(undefined, 5)).toBe("");
});

test("the card offers the nightly switch through the shared Toggle", () => {
  const source = read("components", "settings", "WorkshopMappingPanel.tsx");
  expect(source).toContain('label="File clear-cut records each night"');
  expect(source).toContain("/workshops/unmapped/auto");
});

const field = (partial: Partial<MergeField>): MergeField => ({
  key: "name",
  label: "Name",
  keep: "Ramesh Kumar",
  merge: "Ramesh K",
  same: false,
  default: "keep",
  allowBoth: false,
  ...partial
});

test("the merge starts from the server's default for every field that differs", () => {
  const picks = defaultPicks([
    field({}),
    field({ key: "aadhaarNumber", keep: null, merge: "XXXX XXXX 2346", default: "merge" }),
    field({ key: "place", keep: "Bagru", merge: "Bagru", same: true })
  ]);
  expect(picks).toEqual({ name: "keep", aadhaarNumber: "merge" });
});

test("the kept record's value follows the pick, and Keep both joins", () => {
  const notes = field({ key: "notes", keep: "Block printer.", merge: "Uses indigo.", allowBoth: true });
  expect(resultValue(notes, "keep")).toBe("Block printer.");
  expect(resultValue(notes, "merge")).toBe("Uses indigo.");
  expect(resultValue(notes, "both")).toBe("Block printer. · Uses indigo.");
});

test("moves and undo are worded, never left blank", () => {
  expect(plural(1, "product record")).toBe("1 product record");
  expect(movesSentence([])).toBe("Nothing else is linked to it.");
  expect(movesSentence([{ key: "products", label: "2 product records", count: 2, folded: 0 }])).toBe(
    "Moves 2 product records."
  );
  const summary: MergeSummary = {
    id: "m1",
    status: "DONE",
    survivorId: "a",
    survivorName: "A",
    mergedId: "b",
    mergedName: "B",
    mergedByName: "Admin",
    createdAt: "2026-10-20T00:00:00Z",
    undoneAt: null,
    undoneByName: null,
    undoUntil: "2026-11-19T00:00:00Z",
    canUndo: true,
    moves: [],
    kept: []
  };
  expect(undoState(summary, new Date("2026-10-25T00:00:00Z")).canUndo).toBe(true);
  expect(undoState(summary, new Date("2026-11-20T00:00:00Z"))).toEqual({ canUndo: false, label: "Can no longer be undone" });
  expect(undoState({ ...summary, status: "UNDONE" }).label).toBe("Undone");
});

test("the merge tool is on the admin hub and under the /admin guard", () => {
  expect(read("app", "(protected)", "admin", "page.tsx")).toContain('href: "/admin/artisan-merge"');
  const page = read("app", "(protected)", "admin", "artisan-merge", "page.tsx");
  expect(page).toContain("isAdmin(user)");
  expect(page).toContain("<ButtonRow");
});
