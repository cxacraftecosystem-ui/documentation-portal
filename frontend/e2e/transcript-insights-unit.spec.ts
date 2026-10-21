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
  createInsightPoller,
  editRows,
  isWorking,
  needsRegenerateConfirm,
  pollDelay,
  POLL_LIMIT_MS,
  POLL_MS,
  POLL_SLOW_MS,
  type PollTimers
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

test("polling slows down after ten minutes instead of stopping", () => {
  expect(pollDelay(0)).toBe(POLL_MS);
  expect(pollDelay(POLL_LIMIT_MS)).toBe(POLL_MS);
  expect(pollDelay(POLL_LIMIT_MS + 1)).toBe(POLL_SLOW_MS);
  expect(read("lib", "transcriptInsights.ts")).not.toContain("setInterval");
});

test("no editing while a new version is written, and a regenerated translation is not stale", () => {
  const component = read("components", "media", "TranscriptInsights.tsx");
  expect(component).toMatch(/const mayEdit = [^;]*!working;/);
  expect(component).toContain("{mayEdit ? (");
  expect(component).toContain("<InsightBody key={insight.version} view={view} />");
});

test("a page without the confirm dialog asks inline before replacing edits", () => {
  const component = read("components", "media", "TranscriptInsights.tsx");
  expect(component).not.toContain("if (!confirm) return;");
  expect(component).toContain("if (!confirm) return setAskReplace(true);");
  expect(component).toMatch(/onClick=\{\(\) => setAskReplace\(false\)\}[\s\S]*?Keep[\s\S]*?onClick=\{\(\) => void start\(true\)\}[\s\S]*?Replace/);
});

test("the lightbox draws the section only under a completed transcript", () => {
  expect(read("components", "media", "MediaLightbox.tsx")).toContain(
    'item.id && status === "COMPLETED" ? <TranscriptInsights mediaId={item.id} readOnly />'
  );
});

function fakeTimers() {
  let now = 0;
  let seq = 0;
  const pending = new Map<number, { at: number; run: () => void }>();
  const timers: PollTimers = {
    now: () => now,
    set: (run, ms) => {
      seq += 1;
      pending.set(seq, { at: now + ms, run });
      return seq;
    },
    clear: (handle) => void pending.delete(handle as number)
  };
  /** Moves the clock forward, firing every timer that falls due on the way. */
  async function advance(ms: number) {
    const until = now + ms;
    for (;;) {
      const next = [...pending.entries()].sort((a, b) => a[1].at - b[1].at)[0];
      if (!next || next[1].at > until) break;
      pending.delete(next[0]);
      now = next[1].at;
      next[1].run();
      for (let i = 0; i < 10; i += 1) await Promise.resolve();
    }
    now = until;
  }
  return { timers, advance, size: () => pending.size };
}

test("every still-working summary on a page is re-read in one batched request per poll", async () => {
  const calls: string[][] = [];
  const clock = fakeTimers();
  const load = createInsightBatcher(async (ids) => {
    calls.push(ids);
    return { items: Object.fromEntries(ids.map((id) => [id, view(id, { state: "PROCESSING" })])) };
  });
  const watch = createInsightPoller(load, clock.timers);
  const seen: string[] = [];
  const stops = ["a", "b", "c"].map((id) => watch(id, () => 0, () => seen.push(id)));

  await clock.advance(POLL_MS - 1);
  expect(calls).toEqual([]);
  await clock.advance(1);
  expect(calls).toEqual([["a", "b", "c"]]);
  expect(seen.sort()).toEqual(["a", "b", "c"]);
  expect(clock.size()).toBe(1);

  stops[1]();
  await clock.advance(POLL_MS);
  expect(calls).toEqual([["a", "b", "c"], ["a", "c"]]);

  stops[0]();
  stops[2]();
  expect(clock.size()).toBe(0);
});

test("the shared poll keeps each block's own cadence, slowing after ten minutes", async () => {
  const calls: string[][] = [];
  const clock = fakeTimers();
  const load = createInsightBatcher(async (ids) => {
    calls.push(ids);
    return { items: {} };
  });
  const watch = createInsightPoller(load, clock.timers);
  watch("old", () => -POLL_LIMIT_MS - 1, () => undefined);
  watch("new", () => 0, () => undefined);
  await clock.advance(POLL_MS);
  expect(calls).toEqual([["new"]]);
  await clock.advance(POLL_SLOW_MS - POLL_MS);
  expect(calls).toEqual([["new"], ["new"], ["new"], ["old", "new"]]);
});

test("the hook polls through the shared poller, not a timer per block", () => {
  const source = read("lib", "transcriptInsights.ts");
  expect(source).toContain("const sharedWatch = createInsightPoller(sharedLoad);");
  expect(source).not.toContain("window.setTimeout");
});
