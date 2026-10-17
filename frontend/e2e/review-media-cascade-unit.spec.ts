import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { alsoApprovesLine, approvedReceipt, fileRefusalLabel, type ReviewReason } from "@/lib/review";

/**
 * FILES APPROVED WITH THEIR RECORD (owner decision 2026-10-18).
 *
 * Approving a record approves its files that are still pending, so every approval says how many go
 * with it, and every file card on a record's page keeps its own Reject — also after the record was
 * approved. The words are pinned here; the wiring is read off the components, because a confirmation
 * that forgot the count would pass every other test.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const MISSING: ReviewReason = { code: "MISSING_DETAILS", label: "Missing details", fixable: true, needsNote: false };
const DUPLICATE: ReviewReason = { code: "DUPLICATE", label: "Duplicate of another record", fixable: false, needsNote: false };

test.describe("the count that goes with an approval", () => {
  test("says how many files, and nothing when there are none", () => {
    expect(alsoApprovesLine(4)).toBe("Also approves 4 files.");
    expect(alsoApprovesLine(1)).toBe("Also approves 1 file.");
    expect(alsoApprovesLine(0)).toBeNull();
    expect(alsoApprovesLine(undefined)).toBeNull();
  });

  test("the receipt names the files only when some went with it", () => {
    expect(approvedReceipt(3)).toBe("Approved, with 3 files.");
    expect(approvedReceipt(0)).toBe("Approved.");
    expect(approvedReceipt(undefined)).toBe("Approved.");
  });

  test("the queue's approve panel, the bulk confirmation and the record's bar all carry it", () => {
    const queue = read("app", "(protected)", "review", "page.tsx");
    expect(queue).toContain("alsoApprovesLine(item.pendingMediaCount)");
    expect(queue).toContain("row.pendingMediaCount ?? 0");
    const bar = read("components", "review", "RecordReviewBar.tsx");
    expect(bar).toContain('confirm({ title: "Approve this record?", body: files, confirmLabel: "Approve" })');
    expect(read("components", "records", "RecordView.tsx")).toContain("approvesWith={mediaReview?.approvesWith ?? 0}");
  });
});

test.describe("one file's Reject", () => {
  test("is labelled for a file, and a fixable reason sends it back instead", () => {
    expect(fileRefusalLabel(DUPLICATE)).toBe("Reject file");
    expect(fileRefusalLabel(null)).toBe("Reject file");
    expect(fileRefusalLabel(MISSING)).toBe("Send for revision");
  });

  test("sits on every file card of a record's page, and only there", () => {
    expect(read("components", "records", "RecordMediaList.tsx")).toContain("<MediaRejectAction mediaId={media.id}");
    expect(read("components", "media", "ExistingMedia.tsx")).toContain("{readOnly ? <MediaRejectAction mediaId={media.id}");
    const view = read("components", "records", "RecordView.tsx");
    expect(view).toContain("<MediaReviewProvider");
    expect(view).toContain("rejectable: new Set(mediaReview.rejectable)");
    // Drawn for the files the server lists — pending, or approved with their record — never a guess.
    expect(read("components", "review", "MediaReview.tsx")).toContain("review?.rejectable.has(mediaId)");
  });
});
