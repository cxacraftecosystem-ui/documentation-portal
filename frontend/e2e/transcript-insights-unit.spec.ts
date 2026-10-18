/**
 * Interview summaries, key quotes and translations — the pure half of `lib/transcriptInsights.ts`
 * plus source assertions for what lives in JSX (there is no React renderer in devDependencies).
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  BATCH_MAX,
  cleanQuotes,
  cleanSummaryLines,
  createInsightBatcher,
  editRows,
  isWorking,
  needsRegenerateConfirm
} from "@/lib/transcriptInsights";
import type { TranscriptInsightView } from "@/lib/types";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function view(mediaId: string, patch: Partial<TranscriptInsightView> = {}): TranscriptInsightView {
  return {
    mediaId,
    state: "READY",
    stale: false,
    canEdit: true,
    canRegenerate: true,
    error: null,
    insight: {
      id: `ins-${mediaId}`,
      version: 1,
      sourceLanguage: "hi",
      summaryLines: ["One."],
      quotes: [],
      translation: { status: "COMPLETED", text: null, note: null },
      provenance: {
        provider: "openai",
        modelId: "gpt-4o-mini",
        modelVersion: null,
        promptVersion: "transcript-insights/1",
        generatedAt: "2026-10-30T10:00:00Z"
      },
      edited: null
    },
    ...patch
  };
}

function manualSchedule() {
  const queue: (() => void)[] = [];
  return { schedule: (run: () => void) => queue.push(run), run: () => queue.splice(0).forEach((f) => f()) };
}

test("the batch loader sends one request for N blocks mounted together", async () => {
  const calls: string[][] = [];
  const clock = manualSchedule();
  const load = createInsightBatcher(async (ids) => {
    calls.push(ids);
    return { items: Object.fromEntries(ids.map((id) => [id, view(id)])) };
  }, clock.schedule);
  const answers = Promise.all(["a", "b", "c", "a"].map((id) => load(id)));
  clock.run();
  const resolved = await answers;
  expect(calls).toEqual([["a", "b", "c"]]);
  expect(resolved.map((answer) => (answer && answer !== "error" ? answer.mediaId : answer))).toEqual(["a", "b", "c", "a"]);
});

test("more than 50 ids are split across requests", async () => {
  const sizes: number[] = [];
  const clock = manualSchedule();
  const load = createInsightBatcher(async (ids) => {
    sizes.push(ids.length);
    return { items: {} };
  }, clock.schedule);
  const all = Promise.all(Array.from({ length: 120 }, (_, i) => load(`m${i}`)));
  clock.run();
  await all;
  expect(sizes).toEqual([BATCH_MAX, BATCH_MAX, 20]);
});

test("a 404 hides the section and an id left out of the batch is hidden too", async () => {
  const clock = manualSchedule();
  const off = createInsightBatcher(async () => {
    throw new ApiError(404, "Not found", { detail: "Not found" });
  }, clock.schedule);
  const hidden = off("a");
  clock.run();
  expect(await hidden).toBeNull();

  const missing = createInsightBatcher(async () => ({ items: {} }), clock.schedule);
  const gone = missing("x");
  clock.run();
  expect(await gone).toBeNull();

  const flaky = createInsightBatcher(async () => {
    throw new ApiError(500, "boom", null);
  }, clock.schedule);
  const failed = flaky("y");
  clock.run();
  expect(await failed).toBe("error");
});

test("the edit form keeps at most five summary lines", () => {
  expect(cleanSummaryLines(["1", " ", "2", "3", "4", "5", "6"])).toEqual(["1", "2", "3", "4", "5"]);
  expect(cleanSummaryLines(["x".repeat(500)])[0]).toHaveLength(400);
  expect(editRows(["a", "b"])).toEqual(["a", "b", "", "", ""]);
  expect(editRows(["1", "2", "3", "4", "5", "6"])).toHaveLength(5);
  expect(cleanQuotes([{ original: " ", english: "x", speaker: null }, { original: "a", english: "b", speaker: " " }])).toEqual([
    { original: "a", english: "b", speaker: null }
  ]);
});

test("the confirm dialog shows only for edited insights", () => {
  expect(needsRegenerateConfirm(view("a"))).toBe(false);
  const edited = view("b");
  edited.insight!.edited = { at: "2026-10-30T11:00:00Z" };
  expect(needsRegenerateConfirm(edited)).toBe(true);
  expect(needsRegenerateConfirm(null)).toBe(false);
  expect(isWorking(view("c", { state: "QUEUED" }))).toBe(true);
  expect(isWorking(view("c"))).toBe(false);

  const component = read("components", "media", "TranscriptInsights.tsx");
  expect(component).toContain("needsRegenerateConfirm(view)");
  expect(component).toContain('title: "Regenerating replaces your edits."');
  expect(component).toContain('confirmLabel: "Replace"');
  expect(component).toContain('cancelLabel: "Keep"');
});

test("the section is drawn under a completed transcript and read-only in the lightbox", () => {
  expect(read("components", "media", "TranscriptBlock.tsx")).toMatch(
    /status === "COMPLETED" && media\.id \? <TranscriptInsights mediaId=\{media\.id\} \/>/
  );
  expect(read("components", "media", "MediaLightbox.tsx")).toContain("<TranscriptInsights mediaId={item.id} readOnly />");
  const component = read("components", "media", "TranscriptInsights.tsx");
  expect(component).toContain("Machine-generated ·");
  expect(component).toContain("Edited by a researcher ·");
  expect(component).toContain("The transcript changed after this summary.");
  expect(component).toContain("Summarising…");
  expect(component).not.toMatch(/coming soon|not available yet/i);
});
