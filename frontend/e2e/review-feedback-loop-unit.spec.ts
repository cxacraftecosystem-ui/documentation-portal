import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { censusCaption, parseCensus } from "@/components/hero/corpusCensus";
import { statusFilterParam } from "@/components/review/RecordStatusFilter";
import { completionVerdictLabel, nextCompletionStatus } from "@/lib/completionVerdict";
import {
  bulkReceipt,
  dataStatusLabel,
  decisionBody,
  isReturned,
  queueFiltersFrom,
  queueQuery,
  refusalButtonLabel,
  refusalOutcome,
  refusalReady,
  returnedHeading,
  returnedNextStep,
  reviewQueueHref,
  reviseReasons,
  sentBackNote,
  sentBackTitle,
  statusQuery,
  type ReviewReason
} from "@/lib/review";

/**
 * THE REVIEW FEEDBACK LOOP ON THE WEB (A1, A2, A3, A13, B9, B12, C19, C21).
 *
 * The decisions are pure functions so they run here without a browser; the wiring (which screens
 * draw the note, the bar, the card and the filters) is read off disk below, so a screen that quietly
 * drops one fails here rather than in somebody's hands.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const MISSING: ReviewReason = { code: "MISSING_DETAILS", label: "Missing details", fixable: true, needsNote: false };
const DUPLICATE: ReviewReason = { code: "DUPLICATE", label: "Duplicate of another record", fixable: false, needsNote: false };
const OTHER: ReviewReason = { code: "OTHER", label: "Other", fixable: false, needsNote: true };

test.describe("the note back to the uploader", () => {
  test("only a returned record has a note to show", () => {
    expect(isReturned("NEEDS_REVISION")).toBe(true);
    expect(isReturned("REJECTED")).toBe(true);
    expect(isReturned("PENDING")).toBe(false);
    expect(isReturned(undefined)).toBe(false);
  });

  test("the heading names who decided, and the next step says what saving does", () => {
    expect(returnedHeading("NEEDS_REVISION", "Dr Rao")).toBe("Sent back by Dr Rao");
    expect(returnedHeading("REJECTED", null)).toBe("Not accepted by A reviewer");
    expect(returnedNextStep("REJECTED")).toContain("reopen");
    expect(returnedNextStep("NEEDS_REVISION")).toContain("send it back for review");
  });

  test("the dashboard card counts both kinds", () => {
    expect(sentBackTitle(3)).toBe("Sent back to you (3)");
    expect(sentBackNote(2, 1)).toBe("2 records to fix · 1 not accepted");
    expect(sentBackNote(1, 0)).toBe("1 record to fix");
  });
});

test.describe("a reason picked from the short list", () => {
  test("a fixable reason sends the record back instead of rejecting it", () => {
    expect(refusalOutcome(MISSING)).toBe("NEEDS_REVISION");
    expect(refusalButtonLabel(MISSING)).toBe("Send for revision");
    expect(refusalOutcome(DUPLICATE)).toBe("REJECTED");
    expect(refusalButtonLabel(DUPLICATE)).toBe("Reject record");
    expect(refusalOutcome(null)).toBe("REJECTED");
  });

  test("a reason is required, and Other needs words", () => {
    expect(refusalReady(null, "anything")).toBe(false);
    expect(refusalReady(DUPLICATE, "")).toBe(true);
    expect(refusalReady(OTHER, "  ")).toBe(false);
    expect(refusalReady(OTHER, "Wrong artisan")).toBe(true);
  });

  test("Send for revision offers only the fixable reasons, and a reason travels only when picked", () => {
    expect(reviseReasons([MISSING, DUPLICATE, OTHER])).toEqual([MISSING]);
    expect(decisionBody("  Add the village. ", MISSING)).toEqual({ notes: "Add the village.", reason: "MISSING_DETAILS" });
    expect(decisionBody("", null)).toEqual({ notes: null });
  });

  test("both revise panels carry the reason", () => {
    for (const file of ["app/(protected)/review/page.tsx", "components/review/RecordReviewBar.tsx"]) {
      const source = read(file);
      expect(source, file).toContain("reviseReasons(reasons)");
      expect(source, file).toContain("decisionBody(notes,");
    }
  });
});

test.describe("the queue's filters", () => {
  test("Tasks' link seeds them and the query carries them", () => {
    const filters = queueFiltersFrom(new URLSearchParams("createdById=u1&workshopId=w1&recordType=product&order=newest"));
    expect(filters).toEqual({ createdById: "u1", workshopId: "w1", recordType: "product", order: "newest" });
    expect(queueQuery(2, 20, filters)).toBe("?page=2&pageSize=20&workshopId=w1&createdById=u1&recordType=product&order=newest");
    expect(queueQuery(1, 20, {})).toBe("?page=1&pageSize=20");
    expect(queueFiltersFrom(new URLSearchParams("order=sideways")).order).toBeUndefined();
    expect(reviewQueueHref({ createdById: "u1", workshopId: "w1" })).toBe("/review?createdById=u1&workshopId=w1");
    // A task's artisans narrow the queue to exactly that task's records (B12).
    const task = queueFiltersFrom(new URLSearchParams("createdById=u1&recordType=product&artisanId=a1,a2"));
    expect(task.artisanId).toBe("a1,a2");
    expect(queueQuery(1, 20, task)).toBe("?page=1&pageSize=20&createdById=u1&recordType=product&artisanId=a1%2Ca2");
  });

  test("the workshop picker searches the server, and short choices are segments", () => {
    const page = read("app/(protected)/review/page.tsx");
    expect(page).toContain("onSearch={setWorkshopSearch}");
    expect(page).not.toContain('listResource<Workshop>("/workshops", { pageSize: 100 })');
    expect(page).toContain("<SegmentedChoice");
    expect(read("app/(protected)/data/page.tsx")).toContain("<SegmentedChoice");
  });

  test("the record page asks for its review only with review access", () => {
    expect(read("components/review/RecordReviewBar.tsx")).toContain("if (!pending || !reviewer)");
  });

  test("a bulk receipt names what failed", () => {
    expect(bulkReceipt(3, 0)).toBe("3 records approved.");
    expect(bulkReceipt(1, 0)).toBe("1 record approved.");
    expect(bulkReceipt(2, 1)).toBe("2 approved, 1 failed (still ticked).");
  });
});

test.describe("review status on the data surfaces", () => {
  test("every status sends no filter; a choice sends itself", () => {
    expect(statusQuery("ALL")).toBeUndefined();
    expect(statusQuery("APPROVED")).toBe("APPROVED");
    expect(dataStatusLabel("PENDING")).toBe("Pending review");
    expect(statusFilterParam("ALL")).toBeUndefined();
    expect(statusFilterParam("NEEDS_REVISION")).toBe("NEEDS_REVISION");
  });

  test("the landing page prints approved counts when the API sends them, held counts otherwise", () => {
    const counts = { artisans: 1, crafts: 2, products: 3, processes: 4, tools: 5, interviews: 6, media: 7, workshops: 8 };
    const both = parseCensus({ counted: true, asOfDate: "2026-10-20", recordsHeld: { ...counts, media: 900 }, recordsPublished: counts });
    expect(both?.counts.media).toBe(7);
    expect(both?.basis).toBe("approved");
    expect(censusCaption(both!, "20 Oct")).toBe("Approved records, as of 20 Oct.");
    const old = parseCensus({ counted: true, asOfDate: "2026-10-20", recordsHeld: counts });
    expect(old?.basis).toBe("held");
    expect(censusCaption(old!, "20 Oct")).toBe("Records held, as of 20 Oct.");
  });
});

test.describe("the completion matrix's three verdicts", () => {
  test("a press walks Android's order and then clears", () => {
    expect(nextCompletionStatus(null)).toBe("COMPLETED");
    expect(nextCompletionStatus("COMPLETED")).toBe("NEEDS_REVIEW");
    expect(nextCompletionStatus("NEEDS_REVIEW")).toBe("NEEDS_REDO");
    expect(nextCompletionStatus("NEEDS_REDO")).toBeNull();
    expect(completionVerdictLabel("NEEDS_REVIEW")).toBe("requires review");
  });
});

test.describe("the wiring", () => {
  test("every edit surface draws the reviewer's note", () => {
    for (const file of [
      "app/(protected)/artisans/[id]/edit/page.tsx",
      "app/(protected)/products/[id]/edit/page.tsx",
      "app/(protected)/tools/[id]/edit/page.tsx",
      "components/forms/ProcessForm.tsx",
      "app/(protected)/workshops/page.tsx",
      "app/(protected)/questionnaire/page.tsx",
      "components/records/RecordView.tsx"
    ]) {
      expect(read(file), file).toContain("<ReviewNoteBanner");
    }
  });

  test("a record's own page carries the decisions, and the dashboard the sent-back card", () => {
    expect(read("components/records/RecordView.tsx")).toContain("<RecordReviewBar");
    expect(read("app/(protected)/dashboard/page.tsx")).toContain("<SentBackCard");
    expect(read("app/(protected)/sent-back/page.tsx")).toContain("fetchReturnedRecords");
  });

  test("the queue approves in one request and offers the reasons", () => {
    const page = read("app/(protected)/review/page.tsx");
    expect(page).toContain("bulkApprove(");
    expect(page).not.toContain("for (const [index, item] of rows.entries())");
    expect(page).toContain("fetchReviewReasons");
    expect(page).toContain("resubmittedNote(item)");
  });

  test("every record list has a status filter, and the Data page a review-status choice", () => {
    for (const kind of ["artisans", "products", "tools", "processes"]) {
      expect(read(`app/(protected)/${kind}/page.tsx`), kind).toContain("<RecordStatusFilter");
    }
    const data = read("app/(protected)/data/page.tsx");
    expect(data).toContain("status: statusParam");
    expect(data).toContain("Show all statuses");
  });

  test("tasks show the records still waiting for review", () => {
    expect(read("components/tasks/MyTaskCard.tsx")).toContain("<TaskPendingRecords");
    expect(read("components/tasks/AccountabilityBoard.tsx")).toContain("<TaskPendingRecords");
  });
});
