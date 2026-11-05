"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { pickerCancelled, pickTarget, saveBlob, saveCapabilities, useDownloadJob, type SaveMode } from "@/components/dataExplorer/useDownloadJob";
import { handOff } from "@/lib/dataExplorer/api";
import type { IncludeKey } from "@/lib/dataExplorer/contract";
import { retryDelaySeconds, type DownloadItem, type DownloadRequest, type DownloadStatus } from "@/lib/dataExplorer/downloads";
import { fetchDownload, NotAFile, type PartialFile } from "@/lib/dataExplorer/fetchDownload";
import { formatBytes, pushSample, rateOf, secondsLeft, timeLeftLabel, type RateSample } from "@/lib/dataExplorer/plan";
import type { JobSource, JobTarget } from "@/lib/dataExplorer/protocol";
import { DownloadRefused } from "@/lib/dataExplorer/wire";

export const IN_MEMORY_FILE_LIMIT = 512 * 1024 * 1024;
export const AUTO_RETRIES = 3;

export type ZipRequest = {
  label: string;
  fileName: string;
  source: JobSource;
  include: IncludeKey[] | null;
  mode: SaveMode;
  bytes: number | null;
  files: number | null;
};

type Pending = { target: JobTarget; request: ZipRequest };

function newId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function blank(id: string, kind: DownloadItem["kind"], label: string): DownloadItem {
  return {
    id,
    kind,
    label,
    status: "waiting",
    url: null,
    fileName: null,
    mediaId: null,
    path: null,
    bytesDone: 0,
    bytesTotal: null,
    filesDone: null,
    filesTotal: null,
    rate: null,
    timeLeft: null,
    current: null,
    note: null,
    error: null,
    retryAt: null,
    finishedAt: null,
    failedFiles: []
  };
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function useDownloads() {
  const job = useDownloadJob();
  const capabilities = useMemo(() => saveCapabilities(), []);
  const [items, setItems] = useState<DownloadItem[]>([]);
  const itemsRef = useRef<DownloadItem[]>([]);
  const requests = useRef(new Map<string, DownloadRequest>());
  const zips = useRef(new Map<string, Pending>());
  const controllers = useRef(new Map<string, AbortController>());
  const partials = useRef(new Map<string, PartialFile>());
  const attempts = useRef(new Map<string, number>());
  const paused = useRef(new Set<string>());
  const zipActive = useRef<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [announcement, setAnnouncement] = useState("");

  const patch = useCallback((id: string, change: Partial<DownloadItem> | ((item: DownloadItem) => Partial<DownloadItem>)) => {
    setItems((previous) => {
      const next = previous.map((item) => (item.id === id ? { ...item, ...(typeof change === "function" ? change(item) : change) } : item));
      itemsRef.current = next;
      return next;
    });
  }, []);

  const add = useCallback((item: DownloadItem) => {
    setItems((previous) => {
      const next = [item, ...previous];
      itemsRef.current = next;
      return next;
    });
  }, []);

  const runFetch = useCallback(
    async (id: string) => {
      const request = requests.current.get(id);
      if (!request) return;
      const known = itemsRef.current.find((item) => item.id === id);
      if (request.kind === "file" && (request.bytes ?? 0) > IN_MEMORY_FILE_LIMIT) {
        handOff(request.url, request.fileName);
        patch(id, { status: "saved", note: "Handed to your browser's downloads.", finishedAt: Date.now() });
        return;
      }
      const controller = new AbortController();
      controllers.current.set(id, controller);
      paused.current.delete(id);
      const samples: { list: RateSample[] } = { list: [] };
      let lastPaint = 0;
      patch(id, {
        status: "running",
        error: null,
        retryAt: null,
        note: request.kind === "report" ? "Preparing report" : known?.bytesDone ? `Picking up at ${formatBytes(known.bytesDone)}` : null
      });
      try {
        const fetched = await fetchDownload(request.url, {
          signal: controller.signal,
          expect: request.kind === "report" ? "xlsx" : "any",
          fallbackName: request.fileName ?? request.label,
          resume: partials.current.get(id) ?? null,
          onPartial: (partial) => partials.current.set(id, partial),
          onProgress: ({ bytesDone, bytesTotal }) => {
            const at = Date.now();
            samples.list = pushSample(samples.list, { at, bytes: bytesDone });
            if (at - lastPaint < 200 && bytesDone !== bytesTotal) return;
            lastPaint = at;
            const rate = rateOf(samples.list);
            patch(id, {
              bytesDone,
              bytesTotal: bytesTotal ?? request.bytes ?? null,
              rate,
              note: bytesDone > 0 ? null : request.kind === "report" ? "Preparing report" : null,
              timeLeft: bytesTotal ? timeLeftLabel(secondsLeft(bytesDone, bytesTotal, rate)) : null
            });
          }
        });
        partials.current.delete(id);
        attempts.current.delete(id);
        saveBlob(fetched.blob, fetched.name);
        patch(id, {
          status: "saved",
          fileName: fetched.name,
          bytesDone: fetched.blob.size,
          bytesTotal: fetched.blob.size,
          note: request.kind === "report" ? "Checked, opens in Excel" : null,
          timeLeft: null,
          finishedAt: Date.now()
        });
      } catch (error) {
        if (controller.signal.aborted) {
          if (paused.current.has(id)) patch(id, { status: "waiting", note: "Paused", timeLeft: null, rate: null });
          else {
            partials.current.delete(id);
            patch(id, { status: "cancelled", note: "Cancelled", timeLeft: null, finishedAt: Date.now() });
          }
          return;
        }
        const tries = (attempts.current.get(id) ?? 0) + 1;
        attempts.current.set(id, tries);
        const status = error instanceof DownloadRefused ? error.status : 0;
        const transient = !(error instanceof NotAFile) && (status === 0 || status === 429 || status >= 500);
        const retryAfter = error instanceof DownloadRefused ? error.retryAfter : null;
        const again = transient && tries <= AUTO_RETRIES ? Date.now() + retryDelaySeconds(tries, retryAfter) * 1000 : null;
        const base = request.kind === "report" ? "The report couldn't be built. Nothing was saved." : errorText(error, "The download failed.");
        patch(id, (item) => ({
          status: "failed",
          error: status === 503 && request.kind === "report" ? "Reports are busy. Nothing was saved." : error instanceof NotAFile ? error.message : base,
          note: item.bytesDone > 0 && request.kind !== "report" ? `Stopped at ${formatBytes(item.bytesDone)}${item.bytesTotal ? ` of ${formatBytes(item.bytesTotal)}` : ""}. Retrying picks up from there.` : null,
          retryAt: again,
          timeLeft: null,
          rate: null,
          finishedAt: Date.now()
        }));
      } finally {
        controllers.current.delete(id);
      }
    },
    [patch]
  );

  const { start: startJob, busy: zipBusy, state: jobState, dismiss: dismissJob, cancel: cancelJob, pause: pauseJob, resume: resumeJob, measure } = job;

  const startZip = useCallback(
    (id: string) => {
      const pending = zips.current.get(id);
      if (!pending || zipBusy) return false;
      const started = startJob({ label: pending.request.label, fileName: pending.request.fileName, source: pending.request.source, include: pending.request.include, target: pending.target, mode: pending.request.mode });
      if (started) {
        zipActive.current = id;
        patch(id, { status: "running", note: null, error: null });
      }
      return started;
    },
    [startJob, zipBusy, patch]
  );

  useEffect(() => {
    const id = zipActive.current;
    if (!id) return;
    const state = jobState;
    const progress = state.progress;
    if (state.phase === "preparing") {
      patch(id, { status: "running", note: "Listing files…" });
      return;
    }
    if (state.phase === "running") {
      patch(id, {
        status: "running",
        note: state.paused ? "Paused" : null,
        bytesDone: progress?.bytesDone ?? 0,
        bytesTotal: progress?.bytesTotal ?? state.manifest?.summary.bytes ?? null,
        filesDone: progress?.filesDone ?? 0,
        filesTotal: progress?.filesTotal ?? state.manifest?.summary.files ?? null,
        current: progress?.current ?? null,
        rate: state.paused ? null : state.rate,
        timeLeft: state.paused ? null : state.timeLeft,
        failedFiles: progress?.failed ?? []
      });
      return;
    }
    if (state.phase === "done" || state.phase === "cancelled" || state.phase === "error") {
      zipActive.current = null;
      setAnnouncement(state.announcement);
      const failed = state.outcome?.failed ?? [];
      const outcome = state.outcome;
      const status: DownloadStatus = state.phase === "done" ? (failed.length && outcome && outcome.filesSaved === 0 && outcome.filesTotal > 0 ? "failed" : "saved") : state.phase === "cancelled" ? "cancelled" : "failed";
      patch(id, {
        status,
        bytesDone: progress?.bytesDone ?? 0,
        filesDone: outcome?.filesSaved ?? progress?.filesDone ?? null,
        filesTotal: outcome?.filesTotal ?? progress?.filesTotal ?? null,
        timeLeft: null,
        rate: null,
        current: null,
        failedFiles: failed,
        error: state.phase === "error" ? state.error : null,
        note:
          state.phase === "done"
            ? outcome?.filesTotal === 0
              ? "Nothing here to download."
              : failed.length
                ? `Saved ${outcome?.filesSaved ?? 0} of ${outcome?.filesTotal ?? 0} files. ${failed.length} failed.${outcome?.skipped ? ` ${outcome.skipped} were already in the folder.` : ""}`
                : `Saved ${(outcome?.filesSaved ?? 0).toLocaleString("en-IN")} files${state.folderName ? ` into ${state.folderName}` : ""}.${outcome?.skipped ? ` ${outcome.skipped} were already in the folder.` : ""}`
            : state.phase === "cancelled"
              ? state.mode === "folder"
                ? "Cancelled. Save to the same folder to pick up where it stopped."
                : "Cancelled"
              : null,
        finishedAt: Date.now()
      });
      dismissJob();
    }
  }, [jobState, dismissJob, patch]);

  const running = items.some((item) => item.status === "running");
  useEffect(() => {
    if (running) return;
    const next = [...itemsRef.current].reverse().find((item) => item.status === "waiting" && item.note !== "Paused");
    if (!next) return;
    if (next.kind === "zip") startZip(next.id);
    else void runFetch(next.id);
  }, [running, items, runFetch, startZip]);

  const retrying = items.some((item) => item.retryAt !== null);
  useEffect(() => {
    if (!retrying) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [retrying]);

  useEffect(() => {
    for (const item of itemsRef.current) {
      if (item.status === "failed" && item.retryAt !== null && item.retryAt <= now) {
        patch(item.id, { retryAt: null });
        if (item.kind !== "zip") void runFetch(item.id);
      }
    }
  }, [now, patch, runFetch]);

  useEffect(() => {
    if (!running) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [running]);

  useEffect(
    () => () => {
      for (const controller of controllers.current.values()) controller.abort();
    },
    []
  );

  const enqueue = useCallback(
    (request: DownloadRequest) => {
      const id = newId();
      requests.current.set(id, request);
      add({ ...blank(id, request.kind, request.label), url: request.url, fileName: request.fileName ?? null, mediaId: request.mediaId ?? null, path: request.path ?? null, bytesTotal: request.bytes ?? null });
      return id;
    },
    [add]
  );

  const enqueueZip = useCallback(
    async (request: ZipRequest): Promise<string | null> => {
      try {
        const target = await pickTarget(request.mode, request.fileName, capabilities, request.bytes);
        const id = newId();
        zips.current.set(id, { target, request });
        add({ ...blank(id, "zip", request.label), fileName: request.fileName, bytesTotal: request.bytes, filesTotal: request.files });
        return id;
      } catch (error) {
        if (pickerCancelled(error)) return null;
        const id = newId();
        add({ ...blank(id, "zip", request.label), status: "failed", error: errorText(error, "The download didn't start."), finishedAt: Date.now() });
        return id;
      }
    },
    [add, capabilities]
  );

  const startNow = useCallback(
    (id: string) => {
      const item = itemsRef.current.find((candidate) => candidate.id === id);
      if (item && item.kind === "zip" && item.status === "running" && zipActive.current === id) {
        resumeJob();
        return;
      }
      if (!item || (item.status !== "waiting" && item.status !== "failed")) return;
      if (item.kind === "zip") {
        if (item.status === "failed") return;
        startZip(id);
      } else void runFetch(id);
    },
    [runFetch, startZip, resumeJob]
  );

  const pause = useCallback((id: string) => {
    if (zipActive.current === id) {
      pauseJob();
      return;
    }
    if (!controllers.current.has(id)) return;
    paused.current.add(id);
    controllers.current.get(id)?.abort();
  }, [pauseJob]);

  const cancel = useCallback(
    (id: string) => {
      if (zipActive.current === id) {
        cancelJob();
        return;
      }
      if (controllers.current.has(id)) {
        paused.current.delete(id);
        controllers.current.get(id)?.abort();
        return;
      }
      partials.current.delete(id);
      patch(id, { status: "cancelled", note: "Cancelled", retryAt: null, finishedAt: Date.now() });
    },
    [cancelJob, patch]
  );

  const dismiss = useCallback((id: string) => {
    requests.current.delete(id);
    zips.current.delete(id);
    partials.current.delete(id);
    setItems((previous) => {
      const next = previous.filter((item) => item.id !== id || item.status === "running");
      itemsRef.current = next;
      return next;
    });
  }, []);

  const clearDone = useCallback(() => {
    setItems((previous) => {
      const next = previous.filter((item) => item.status === "running" || item.status === "waiting" || item.status === "failed");
      itemsRef.current = next;
      return next;
    });
  }, []);

  const mediaStatus = useMemo(() => {
    const out = new Map<string, DownloadStatus>();
    for (const item of [...items].reverse()) if (item.mediaId) out.set(item.mediaId, item.status);
    return out;
  }, [items]);

  const reportFor = useCallback((path: string) => items.find((item) => item.kind === "report" && item.path === path) ?? null, [items]);

  return {
    items,
    now,
    capabilities,
    measure,
    announcement: jobState.phase === "idle" ? announcement : jobState.announcement,
    zipBusy,
    enqueue,
    enqueueZip,
    startNow,
    pause,
    cancel,
    dismiss,
    clearDone,
    mediaStatus,
    reportFor
  };
}

export type Downloads = ReturnType<typeof useDownloads>;
