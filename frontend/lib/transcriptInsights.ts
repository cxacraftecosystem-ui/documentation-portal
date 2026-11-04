"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { ApiError, apiFetch } from "@/lib/api";
import type { InsightQuote, TranscriptInsightView } from "@/lib/types";

export const BATCH_MAX = 50;
export const POLL_MS = 15_000;
export const POLL_LIMIT_MS = 10 * 60_000;
export const POLL_SLOW_MS = 60_000;
export const SUMMARY_MAX_LINES = 5;
export const SUMMARY_LINE_MAX = 400;
export const QUOTES_MAX = 8;

export type InsightPatch = {
  version: number;
  summaryLines?: string[];
  quotes?: InsightQuote[];
  translationText?: string;
};

export function fetchInsight(mediaId: string): Promise<TranscriptInsightView> {
  return apiFetch<TranscriptInsightView>(`/media/${encodeURIComponent(mediaId)}/insights`);
}

export function patchInsight(mediaId: string, body: InsightPatch): Promise<TranscriptInsightView> {
  return apiFetch<TranscriptInsightView>(`/media/${encodeURIComponent(mediaId)}/insights`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export function regenerateInsight(mediaId: string, discardEdits: boolean): Promise<TranscriptInsightView> {
  return apiFetch<TranscriptInsightView>(`/media/${encodeURIComponent(mediaId)}/insights/regenerate`, {
    method: "POST",
    body: JSON.stringify({ discardEdits })
  });
}

export function fetchInsightBatch(ids: string[]): Promise<{ items: Record<string, TranscriptInsightView> }> {
  return apiFetch(`/media/insights?ids=${ids.map(encodeURIComponent).join(",")}`);
}

export type BatchAnswer = TranscriptInsightView | null | "error";

type Waiter = (answer: BatchAnswer) => void;

export function createInsightBatcher(
  fetchBatch: (ids: string[]) => Promise<{ items: Record<string, TranscriptInsightView> }>,
  schedule: (run: () => void) => void = (run) => queueMicrotask(run)
) {
  let pending = new Map<string, Waiter[]>();
  let scheduled = false;

  async function flush() {
    scheduled = false;
    const batch = pending;
    pending = new Map();
    const ids = [...batch.keys()];
    for (let start = 0; start < ids.length; start += BATCH_MAX) {
      const slice = ids.slice(start, start + BATCH_MAX);
      let answers: Record<string, TranscriptInsightView> | null = null;
      let hidden = false;
      try {
        answers = (await fetchBatch(slice)).items ?? {};
      } catch (error) {
        hidden = error instanceof ApiError && error.status === 404;
      }
      for (const id of slice) {
        const answer: BatchAnswer = answers ? (answers[id] ?? null) : hidden ? null : "error";
        for (const waiter of batch.get(id) ?? []) waiter(answer);
      }
    }
  }

  return function load(id: string): Promise<BatchAnswer> {
    return new Promise((resolve) => {
      const waiters = pending.get(id) ?? [];
      waiters.push(resolve);
      pending.set(id, waiters);
      if (!scheduled) {
        scheduled = true;
        schedule(() => void flush());
      }
    });
  };
}

const sharedLoad = createInsightBatcher(fetchInsightBatch);

export const POLL_COALESCE_MS = 1_000;

export type PollTimers = {
  now: () => number;
  set: (run: () => void, ms: number) => unknown;
  clear: (handle: unknown) => void;
};

const browserTimers: PollTimers = {
  now: () => Date.now(),
  set: (run, ms) => globalThis.setTimeout(run, ms),
  clear: (handle) => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>)
};

export function createInsightPoller(load: (id: string) => Promise<BatchAnswer>, timers: PollTimers = browserTimers) {
  type Watch = { id: string; started: () => number; onAnswer: (answer: BatchAnswer) => void; due: number; inFlight: boolean };
  const watches = new Set<Watch>();
  let handle: unknown = null;

  function arm() {
    if (handle !== null) timers.clear(handle);
    handle = null;
    let next = Infinity;
    for (const watch of watches) if (!watch.inFlight && watch.due < next) next = watch.due;
    if (next === Infinity) return;
    handle = timers.set(fire, Math.max(0, next - timers.now()));
  }

  function fire() {
    handle = null;
    const now = timers.now();
    for (const watch of watches) {
      if (watch.inFlight || watch.due > now + POLL_COALESCE_MS) continue;
      watch.inFlight = true;
      void load(watch.id).then((answer) => {
        watch.inFlight = false;
        if (!watches.has(watch)) return;
        watch.onAnswer(answer);
        const at = timers.now();
        watch.due = at + pollDelay(at - watch.started());
        arm();
      });
    }
    arm();
  }

  return function watch(id: string, started: () => number, onAnswer: (answer: BatchAnswer) => void): () => void {
    const now = timers.now();
    const entry: Watch = { id, started, onAnswer, due: now + pollDelay(now - started()), inFlight: false };
    watches.add(entry);
    arm();
    return () => {
      watches.delete(entry);
      arm();
    };
  };
}

const sharedWatch = createInsightPoller(sharedLoad);

export function isWorking(view: TranscriptInsightView | null | undefined): boolean {
  return view?.state === "QUEUED" || view?.state === "PROCESSING";
}

export function pollDelay(elapsedMs: number): number {
  return elapsedMs > POLL_LIMIT_MS ? POLL_SLOW_MS : POLL_MS;
}

export function useTranscriptInsight(mediaId: string | null | undefined, { enabled = true } = {}) {
  const [view, setView] = useState<TranscriptInsightView | null | undefined>(undefined);
  const started = useRef<number>(0);

  useEffect(() => {
    if (!mediaId || !enabled) return;
    let cancelled = false;
    started.current = Date.now();
    void sharedLoad(mediaId).then((answer) => {
      if (cancelled) return;
      setView(answer === "error" ? null : answer);
    });
    return () => {
      cancelled = true;
    };
  }, [mediaId, enabled]);

  const working = isWorking(view);
  useEffect(() => {
    if (!mediaId || !working) return;
    return sharedWatch(
      mediaId,
      () => started.current,
      (answer) => {
        if (answer !== "error") setView(answer);
      }
    );
  }, [mediaId, working]);

  const replace = useCallback((next: TranscriptInsightView) => {
    started.current = Date.now();
    setView(next);
  }, []);

  return { view, replace };
}

export function cleanSummaryLines(lines: string[]): string[] {
  return lines
    .map((line) => line.trim().slice(0, SUMMARY_LINE_MAX))
    .filter((line) => line.length > 0)
    .slice(0, SUMMARY_MAX_LINES);
}

export function cleanQuotes(quotes: InsightQuote[]): InsightQuote[] {
  return quotes
    .map((q) => ({ original: q.original.trim(), english: q.english.trim(), speaker: q.speaker?.trim() || null }))
    .filter((q) => q.original.length > 0)
    .slice(0, QUOTES_MAX);
}

export function editRows(lines: string[]): string[] {
  const rows = lines.slice(0, SUMMARY_MAX_LINES);
  while (rows.length < SUMMARY_MAX_LINES) rows.push("");
  return rows;
}

export function needsRegenerateConfirm(view: TranscriptInsightView | null | undefined): boolean {
  return Boolean(view?.insight?.edited);
}

export function modelLabel(view: TranscriptInsightView): string {
  const provenance = view.insight?.provenance;
  if (!provenance) return "";
  return provenance.modelId === "UNRECORDED" ? "model not recorded" : provenance.modelId;
}
