"use client";

import { ChevronRight } from "lucide-react";

import { qs } from "@/lib/questionnaire/qs";
import { clipUploadState, type ClipStep, type Step } from "@/lib/questionnaire/queue";

export type SaveState = { kind: "SAVED" } | { kind: "UPLOADING"; count: number; percent: number } | { kind: "WAITING" } | { kind: "FAILED" };

export function saveState(steps: Step[], progress: Record<string, number>, online: boolean): SaveState {
  const open = steps.filter((s) => s.status !== "DONE");
  if (open.some((s) => s.status === "FAILED" || (s.kind === "clip" && s.error && s.error !== "MISSING_FILE" && online))) return { kind: "FAILED" };
  const clips = open.filter((s): s is ClipStep => s.kind === "clip" && s.error !== "MISSING_FILE");
  if (!open.length) return { kind: "SAVED" };
  if (!online) return { kind: "WAITING" };
  if (!clips.length) return { kind: "SAVED" };
  const parts = clips.map((s) => {
    const state = clipUploadState(s, progress[s.clientClipKey], online);
    return state.kind === "UPLOADING" ? state.percent : 0;
  });
  return { kind: "UPLOADING", count: clips.length, percent: Math.round(parts.reduce((a, b) => a + b, 0) / clips.length) };
}

export function saveLine(state: SaveState): string {
  switch (state.kind) {
    case "UPLOADING":
      return state.count === 1 ? qs("takesUploadingPercentOne", { percent: state.percent }) : qs("takesUploadingPercent", { count: state.count, percent: state.percent });
    case "WAITING":
      return qs("waitingForSignal");
    case "FAILED":
      return qs("uploadFailedRetrying");
    default:
      return qs("saved");
  }
}

const TONE: Record<SaveState["kind"], string> = {
  SAVED: "bg-success-100 text-success-600 dark:bg-success-600/25 dark:text-green-400",
  UPLOADING: "bg-purple-100 text-purple-800 dark:bg-purple-700/25 dark:text-purple-200",
  WAITING: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-100",
  FAILED: "bg-error-100 text-error-600 dark:bg-error-600/25 dark:text-red-300"
};

const DOT: Record<SaveState["kind"], string> = {
  SAVED: "bg-success-600 dark:bg-green-400",
  UPLOADING: "bg-purple-700 dark:bg-purple-300",
  WAITING: "bg-amber-500",
  FAILED: "bg-error-600 dark:bg-red-300"
};

export function SavePill({ state, onOpen, onRetry }: { state: SaveState; onOpen: () => void; onRetry?: () => void }) {
  const line = saveLine(state);
  return (
    <span className="inline-flex flex-wrap items-center gap-2" data-save-pill={state.kind}>
      <button
        type="button"
        aria-label={`${line}. ${qs("openUploads")}`}
        className={`inline-flex min-h-11 items-center gap-2 rounded-full px-3.5 text-xs font-semibold ${TONE[state.kind]}`}
        onClick={onOpen}
      >
        <span aria-hidden className={`h-2 w-2 shrink-0 rounded-full ${DOT[state.kind]}`} />
        <span>{line}</span>
        <ChevronRight className="h-4 w-4 shrink-0 opacity-70" aria-hidden />
      </button>
      {state.kind === "FAILED" && onRetry ? (
        <button type="button" className="min-h-11 px-2 text-xs font-semibold text-purple-700 underline dark:text-purple-300" onClick={onRetry} data-save-retry>
          {qs("retry")}
        </button>
      ) : null}
    </span>
  );
}
