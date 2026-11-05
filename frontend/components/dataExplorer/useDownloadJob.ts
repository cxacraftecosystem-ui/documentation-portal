"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { IncludeKey } from "@/lib/dataExplorer/contract";
import type { JobOutcome, JobProgress } from "@/lib/dataExplorer/engine";
import {
  announceStep,
  browserKind,
  percentOf,
  pushSample,
  rateOf,
  secondsLeft,
  timeLeftLabel,
  zipLimit,
  type RateSample,
  type SaveCapabilities
} from "@/lib/dataExplorer/plan";
import type { FromWorker, JobSource, JobTarget, ManifestNotice, ToWorker } from "@/lib/dataExplorer/protocol";
import { PauseGate } from "@/lib/dataExplorer/engine";
import { measureJob, runJob } from "@/lib/dataExplorer/runJob";
import { isAbort, type DirectoryHandleLike, type FileHandleLike } from "@/lib/dataExplorer/sinks";
import { openStreamHandoff, streamHandoffSupported } from "@/lib/dataExplorer/streamHandoff";

export const DOWNLOAD_LOCK = "data-explorer-download";

export type SaveMode = "zip" | "folder";

export type JobPhase = "idle" | "preparing" | "running" | "done" | "cancelled" | "error";

export type JobState = {
  phase: JobPhase;
  label: string;
  mode: SaveMode;
  manifest: ManifestNotice | null;
  progress: JobProgress | null;
  outcome: JobOutcome | null;
  truncated: boolean;
  error: string | null;
  limit: number | null;
  rate: number | null;
  timeLeft: string | null;
  announcement: string;
  folderName: string | null;
  fileName: string;
  paused: boolean;
};

const IDLE: JobState = {
  phase: "idle",
  label: "",
  mode: "zip",
  manifest: null,
  progress: null,
  outcome: null,
  truncated: false,
  error: null,
  limit: null,
  rate: null,
  timeLeft: null,
  announcement: "",
  folderName: null,
  fileName: "",
  paused: false
};

type PickerWindow = Window & {
  showSaveFilePicker?: (options: unknown) => Promise<FileHandleLike>;
  showDirectoryPicker?: (options: unknown) => Promise<DirectoryHandleLike>;
};

export function saveCapabilities(): SaveCapabilities & { stream: boolean } {
  if (typeof window === "undefined") return { saveFile: false, saveFolder: false, browser: "other", stream: false };
  const picker = window as PickerWindow;
  return {
    saveFile: typeof picker.showSaveFilePicker === "function",
    saveFolder: typeof picker.showDirectoryPicker === "function",
    browser: browserKind(navigator.userAgent),
    stream: typeof picker.showSaveFilePicker !== "function" && streamHandoffSupported()
  };
}

export async function pickTarget(
  mode: SaveMode,
  suggestedName: string,
  capabilities: ReturnType<typeof saveCapabilities>,
  size: number | null
): Promise<JobTarget> {
  const picker = window as PickerWindow;
  if (mode === "folder") {
    const handle = await picker.showDirectoryPicker!({ id: "data-explorer-folder", mode: "readwrite" });
    return { kind: "folder", handle };
  }
  if (capabilities.saveFile) {
    const handle = await picker.showSaveFilePicker!({
      suggestedName,
      id: "data-explorer-zip",
      types: [{ description: "Zip archive", accept: { "application/zip": [".zip"] } }]
    });
    return { kind: "file", handle };
  }
  if (capabilities.stream) {
    const port = await openStreamHandoff(suggestedName, size);
    if (port) return { kind: "stream", port };
  }
  return { kind: "memory", limit: zipLimit(capabilities) };
}

export function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name;
  anchor.rel = "noopener";
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

type Runner = { send(message: ToWorker, transfer?: Transferable[]): void; dispose(): void };

function workerRunner(onMessage: (message: FromWorker) => void): Runner {
  try {
    if (typeof Worker === "undefined") throw new Error("no worker");
    const worker = new Worker(new URL("../../lib/dataExplorer/download.worker.ts", import.meta.url), { type: "module" });
    worker.addEventListener("message", (event: MessageEvent<FromWorker>) => onMessage(event.data));
    return { send: (message, transfer) => worker.postMessage(message, transfer ?? []), dispose: () => worker.terminate() };
  } catch {
    const running = new Map<string, AbortController>();
    const gates = new Map<string, PauseGate>();
    return {
      send: (message) => {
        if (message.type === "cancel") {
          running.get(message.id)?.abort();
          gates.get(message.id)?.resume();
          return;
        }
        if (message.type === "pause" || message.type === "resume") {
          const gate = gates.get(message.id);
          if (!gate) return;
          if (message.type === "pause") gate.pause();
          else gate.resume();
          onMessage({ type: "paused", id: message.id, paused: gate.paused });
          return;
        }
        const controller = new AbortController();
        running.set(message.id, controller);
        const gate = new PauseGate();
        if (message.type === "start") gates.set(message.id, gate);
        const work =
          message.type === "measure"
            ? measureJob(message, controller.signal, onMessage)
            : runJob(message as Extract<ToWorker, { type: "start" }>, controller.signal, onMessage, undefined, undefined, gate);
        void work.finally(() => {
          running.delete(message.id);
          gates.delete(message.id);
        });
      },
      dispose: () => running.forEach((controller) => controller.abort())
    };
  }
}

export function holdLock(until: Promise<void>): boolean {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  if (!locks?.request) return false;
  void locks.request(DOWNLOAD_LOCK, { mode: "shared" }, () => until).catch(() => undefined);
  return true;
}

export type StartOptions = {
  label: string;
  fileName: string;
  source: JobSource;
  include: IncludeKey[] | null;
  target: JobTarget;
  mode: SaveMode;
};

export type MeasureOutcome = { manifest: ManifestNotice } | { error: string };

function newId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function useDownloadJob() {
  const [state, setState] = useState<JobState>(IDLE);
  const latest = useRef<JobState>(IDLE);
  const runner = useRef<Runner | null>(null);
  const activeId = useRef<string | null>(null);
  const samples = useRef<RateSample[]>([]);
  const lastPercent = useRef<number | null>(null);
  const release = useRef<(() => void) | null>(null);
  const measures = useRef(new Map<string, (outcome: MeasureOutcome) => void>());
  const hidden = useRef(false);

  const commit = useCallback((next: JobState, quiet = false) => {
    latest.current = next;
    if (!quiet || !hidden.current) setState(next);
  }, []);

  const finishLock = useCallback(() => {
    release.current?.();
    release.current = null;
  }, []);

  const onMessage = useCallback(
    (message: FromWorker) => {
      const waiting = measures.current.get(message.id);
      if (waiting) {
        if (message.type === "measured") waiting({ manifest: message.manifest });
        else if (message.type === "error") waiting({ error: message.message });
        else if (message.type === "cancelled") waiting({ error: "Cancelled." });
        else return;
        measures.current.delete(message.id);
        return;
      }
      if (message.id !== activeId.current) return;
      const current = latest.current;
      if (message.type === "manifest") {
        commit({ ...current, phase: "running", manifest: message.manifest, truncated: message.manifest.truncated });
        return;
      }
      if (message.type === "progress") {
        const { progress } = message;
        samples.current = pushSample(samples.current, { at: Date.now(), bytes: progress.bytesDone });
        const rate = rateOf(samples.current);
        const percent = percentOf(progress.bytesDone, progress.bytesTotal);
        const speak = announceStep(percent, lastPercent.current);
        if (speak) lastPercent.current = percent;
        commit(
          {
            ...current,
            phase: "running",
            progress,
            rate,
            timeLeft: timeLeftLabel(secondsLeft(progress.bytesDone, progress.bytesTotal, rate)),
            announcement: speak ? `${percent}% downloaded` : current.announcement
          },
          true
        );
        return;
      }
      if (message.type === "measured") return;
      if (message.type === "paused") {
        commit({ ...current, paused: message.paused, rate: null, timeLeft: null, announcement: message.paused ? "Download paused." : "Download resumed." });
        return;
      }
      activeId.current = null;
      finishLock();
      if (message.type === "done") {
        if (message.blob) saveBlob(message.blob, current.fileName);
        const failed = message.outcome.failed.length;
        commit({
          ...current,
          phase: "done",
          outcome: message.outcome,
          truncated: message.truncated || current.truncated,
          timeLeft: null,
          announcement: failed ? `Download finished. ${failed} ${failed === 1 ? "file" : "files"} failed.` : "Download finished."
        });
      } else if (message.type === "cancelled") {
        commit({ ...current, phase: "cancelled", timeLeft: null, announcement: "Download cancelled." });
      } else {
        commit({ ...current, phase: "error", error: message.message, limit: message.limit, timeLeft: null, announcement: message.message });
      }
    },
    [commit, finishLock]
  );

  useEffect(() => {
    const onVisibility = () => {
      hidden.current = document.visibilityState === "hidden";
      if (!hidden.current) setState({ ...latest.current });
    };
    onVisibility();
    document.addEventListener("visibilitychange", onVisibility);
    return () => document.removeEventListener("visibilitychange", onVisibility);
  }, []);

  const busy = state.phase === "running" || state.phase === "preparing";

  useEffect(() => {
    if (!busy) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy]);

  useEffect(
    () => () => {
      if (activeId.current) runner.current?.send({ type: "cancel", id: activeId.current });
      runner.current?.dispose();
      runner.current = null;
      finishLock();
    },
    [finishLock]
  );

  const ensureRunner = useCallback(() => {
    runner.current ??= workerRunner((message) => onMessage(message));
    return runner.current;
  }, [onMessage]);

  const start = useCallback(
    (options: StartOptions) => {
      if (activeId.current) return false;
      const id = newId();
      activeId.current = id;
      samples.current = [];
      lastPercent.current = null;
      holdLock(new Promise<void>((resolve) => (release.current = resolve)));
      commit({
        ...IDLE,
        phase: "preparing",
        label: options.label,
        mode: options.mode,
        fileName: options.fileName,
        folderName: options.target.kind === "folder" ? options.target.handle.name : null,
        announcement: "Download started."
      });
      const transfer = options.target.kind === "stream" ? [options.target.port] : [];
      ensureRunner().send({ type: "start", id, source: options.source, include: options.include, target: options.target }, transfer);
      return true;
    },
    [commit, ensureRunner]
  );

  const measure = useCallback(
    (source: JobSource, include: IncludeKey[] | null) =>
      new Promise<MeasureOutcome>((resolve) => {
        const id = newId();
        measures.current.set(id, resolve);
        ensureRunner().send({ type: "measure", id, source, include });
      }),
    [ensureRunner]
  );

  const cancel = useCallback(() => {
    if (activeId.current) runner.current?.send({ type: "cancel", id: activeId.current });
  }, []);

  const pause = useCallback(() => {
    if (activeId.current) runner.current?.send({ type: "pause", id: activeId.current });
  }, []);

  const resume = useCallback(() => {
    if (!activeId.current) return;
    samples.current = [];
    runner.current?.send({ type: "resume", id: activeId.current });
  }, []);

  const dismiss = useCallback(() => {
    if (activeId.current) return;
    commit(IDLE);
  }, [commit]);

  const fail = useCallback(
    (label: string, message: string) => {
      if (activeId.current) return;
      commit({ ...IDLE, phase: "error", label, error: message, announcement: message });
    },
    [commit]
  );

  return { state, start, measure, cancel, pause, resume, dismiss, fail, busy };
}

export function pickerCancelled(error: unknown): boolean {
  return isAbort(error);
}
