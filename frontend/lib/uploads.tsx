"use client";

import { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState, useSyncExternalStore } from "react";

import {
  discardStagedFile,
  getServerStagingSnapshot,
  getStagingSnapshot,
  releaseStagedOwner,
  retryStagedFile,
  scheduleStagedObjectSweep,
  stageFiles,
  subscribeStaging,
  type BatchProgress,
  type StageEntry
} from "@/lib/media";
import type { MediaFile } from "@/lib/types";

/**
 * Page-scope upload state. Every media section on a page (the record form, the interview audio, the
 * per-question clips…) reports its own `BatchProgress` under a stable section id; the provider keeps
 * them keyed so a page-level dock can show one aggregate bar plus the per-section breakdown, and so
 * the media that finished uploading on this page can be listed in both places at once.
 */

export type UploadSectionState = {
  id: string;
  label: string;
  progress: BatchProgress;
  /** When this section last reported — used to name the file the page is currently pushing. */
  updatedAt: number;
};

export type PageUploadAggregate = {
  sections: UploadSectionState[];
  uploadedBytes: number;
  totalBytes: number;
  fraction: number;
  etaSeconds: number | null;
  /** Files finished across every active section, and how many there are in total. */
  fileIndex: number;
  fileCount: number;
  currentFileName: string;
};

export type CompletedUpload = {
  key: string;
  sectionId: string;
  sectionLabel: string;
  file: MediaFile;
};

type UploadsContextValue = {
  /** False when a page has no <UploadsProvider>; consumers then degrade to section-only progress. */
  enabled: boolean;
  sections: UploadSectionState[];
  aggregate: PageUploadAggregate | null;
  completed: CompletedUpload[];
  reportSection: (id: string, label: string, progress: BatchProgress | null) => void;
  addCompleted: (sectionId: string, sectionLabel: string, files: MediaFile[]) => void;
  clearCompleted: () => void;
};

const NOOP_CONTEXT: UploadsContextValue = {
  enabled: false,
  sections: [],
  aggregate: null,
  completed: [],
  reportSection: () => undefined,
  addCompleted: () => undefined,
  clearCompleted: () => undefined
};

const UploadsContext = createContext<UploadsContextValue>(NOOP_CONTEXT);

type ProviderState = {
  sections: Record<string, UploadSectionState>;
  /** When the page went from idle to uploading — the clock the page-level ETA is measured against. */
  startedAt: number | null;
};

const EMPTY_STATE: ProviderState = { sections: {}, startedAt: null };

export function UploadsProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<ProviderState>(EMPTY_STATE);
  const [completed, setCompleted] = useState<CompletedUpload[]>([]);

  // Reclaim objects a previous visit pre-uploaded and never linked (tab closed mid-form, crash…).
  useEffect(() => {
    scheduleStagedObjectSweep();
  }, []);

  const reportSection = useCallback((id: string, label: string, progress: BatchProgress | null) => {
    // `now` is read outside the updater so the updater stays pure (React may replay it).
    const now = Date.now();
    setState((current) => {
      // Clearing a section that was never registered is a no-op — return the SAME state so an idle
      // page (every questionnaire question mounts its own <UploadProgress>) never re-renders.
      if (!progress && !current.sections[id]) return current;
      const sections = { ...current.sections };
      if (progress) sections[id] = { id, label, progress, updatedAt: now };
      else delete sections[id];
      const active = Object.keys(sections).length > 0;
      return { sections, startedAt: active ? current.startedAt ?? now : null };
    });
  }, []);

  const addCompleted = useCallback((sectionId: string, sectionLabel: string, files: MediaFile[]) => {
    if (!files.length) return;
    setCompleted((current) => {
      const known = new Set(current.map((item) => item.file.id));
      const additions = files
        .filter((file) => !known.has(file.id))
        .map((file) => ({ key: `${sectionId}-${file.id}`, sectionId, sectionLabel, file }));
      return additions.length ? [...current, ...additions] : current;
    });
  }, []);

  const clearCompleted = useCallback(() => setCompleted([]), []);

  const value = useMemo<UploadsContextValue>(() => {
    const sections = Object.values(state.sections).sort((a, b) => a.label.localeCompare(b.label));
    let aggregate: PageUploadAggregate | null = null;
    if (sections.length) {
      const totalBytes = sections.reduce((sum, item) => sum + item.progress.totalBytes, 0);
      const uploadedBytes = sections.reduce((sum, item) => sum + item.progress.uploadedBytes, 0);
      const fileCount = sections.reduce((sum, item) => sum + item.progress.fileCount, 0);
      const fileIndex = sections.reduce(
        (sum, item) => sum + Math.min(item.progress.fileIndex, Math.max(0, item.progress.fileCount - 1)),
        0
      );
      // Page-level ETA from the page-level rate, not a sum of per-section guesses. "Now" is the
      // newest section report rather than Date.now(): reading the clock during render is impure,
      // and progress ticks arrive continuously while a batch runs, so it tracks just as closely.
      const newest = sections.reduce((latest, item) => (item.updatedAt >= latest.updatedAt ? item : latest), sections[0]);
      const elapsed = state.startedAt ? (newest.updatedAt - state.startedAt) / 1000 : 0;
      const rate = elapsed > 0 ? uploadedBytes / elapsed : 0;
      aggregate = {
        sections,
        uploadedBytes,
        totalBytes,
        fraction: totalBytes > 0 ? Math.min(1, uploadedBytes / totalBytes) : 0,
        etaSeconds: rate > 0 ? Math.max(0, Math.round((totalBytes - uploadedBytes) / rate)) : null,
        fileIndex,
        fileCount,
        currentFileName: newest.progress.currentFileName
      };
    }
    return { enabled: true, sections, aggregate, completed, reportSection, addCompleted, clearCompleted };
  }, [state, completed, reportSection, addCompleted, clearCompleted]);

  return <UploadsContext.Provider value={value}>{children}</UploadsContext.Provider>;
}

export function useUploads() {
  return useContext(UploadsContext);
}

/**
 * Drop-in replacement for `useState<BatchProgress | null>(null)` that also publishes this section's
 * progress to the page-level tray. Swapping the useState line is the only change a media form needs.
 */
export function useUploadSection(
  id: string,
  label: string
): [BatchProgress | null, (progress: BatchProgress | null) => void] {
  const { reportSection } = useUploads();
  const [progress, setProgress] = useState<BatchProgress | null>(null);

  const report = useCallback(
    (next: BatchProgress | null) => {
      setProgress(next);
      reportSection(id, label, next);
    },
    [id, label, reportSection]
  );

  // Leaving the page mid-upload must not strand a ghost row in the tray.
  useEffect(() => {
    return () => reportSection(id, label, null);
  }, [id, label, reportSection]);

  return [progress, report];
}

export type EagerStagingSummary = {
  /** One entry per file, in the caller's order; null while a file has not started staging. */
  entries: Array<StageEntry | null>;
  total: number;
  ready: number;
  failed: number;
  uploading: number;
  /** 0..1 across every attached file, by bytes. */
  fraction: number;
  /** True once every attached file is in object storage — "ready to save". */
  allReady: boolean;
  retry: (file: File) => void;
};

const EMPTY_STAGING_SUMMARY: EagerStagingSummary = {
  entries: [],
  total: 0,
  ready: 0,
  failed: 0,
  uploading: 0,
  fraction: 0,
  allReady: false,
  retry: () => undefined
};

/**
 * Eager upload, as a hook. Every attached file starts streaming to object storage the moment it is
 * added — mirroring the Android capture screen — so the transfer overlaps the minutes spent filling
 * the form and saving only has to link the objects. Returns per-file transfer state for the capture
 * UI and publishes one aggregate row into the page-level <UploadTray>.
 *
 * Removal, failure, navigation and tab-close are all handled by the store in `lib/media`: discarding
 * a file aborts and deletes its object, an unmounted owner's unclaimed objects are deleted after a
 * short grace period, and anything that still slips through is reclaimed by the orphan sweep.
 */
export function useEagerStaging(files: File[], label = "Attach media"): EagerStagingSummary {
  const ownerId = useId();
  const { reportSection } = useUploads();
  const snapshot = useSyncExternalStore(subscribeStaging, getStagingSnapshot, getServerStagingSnapshot);
  const trackedRef = useRef<File[]>([]);
  const startedAtRef = useRef<number | null>(null);

  useEffect(() => {
    const previous = trackedRef.current;
    trackedRef.current = files;
    stageFiles(files, ownerId);
    // A file the user just removed: abort its transfer and delete whatever reached storage.
    previous.filter((file) => !files.includes(file)).forEach(discardStagedFile);
  }, [files, ownerId]);

  // Unmount (or SPA navigation) releases this owner's claim; the store deletes anything orphaned.
  useEffect(() => {
    return () => releaseStagedOwner(ownerId);
  }, [ownerId]);

  const summary = useMemo<EagerStagingSummary>(() => {
    if (!files.length) return EMPTY_STAGING_SUMMARY;
    const entries = files.map((file) => snapshot.get(file) ?? null);
    const known = entries.filter((entry): entry is StageEntry => entry !== null);
    const totalBytes = files.reduce((sum, file) => sum + file.size, 0) || 1;
    const uploadedBytes = known.reduce((sum, entry) => sum + (entry.status === "ready" ? entry.total : entry.loaded), 0);
    const ready = known.filter((entry) => entry.status === "ready").length;
    const failed = known.filter((entry) => entry.status === "error").length;
    return {
      entries,
      total: files.length,
      ready,
      failed,
      uploading: known.length - ready - failed,
      fraction: Math.min(1, uploadedBytes / totalBytes),
      // Zero-byte files are never staged, so "all ready" is measured against what CAN be staged.
      allReady: known.length > 0 && ready === known.length,
      retry: retryStagedFile
    };
  }, [files, snapshot]);

  // Publish the eager transfer into the page dock, exactly like a save-time batch. A file claimed by
  // a save drops out of the store, so the eager row clears at the moment the batch row appears —
  // the two never double-count.
  useEffect(() => {
    if (!summary.uploading && !summary.failed) {
      startedAtRef.current = null;
      reportSection(ownerId, label, null);
      return;
    }
    const now = Date.now();
    if (startedAtRef.current === null) startedAtRef.current = now;
    const totalBytes = files.reduce((sum, file) => sum + file.size, 0) || 1;
    const uploadedBytes = Math.round(summary.fraction * totalBytes);
    const elapsed = (now - startedAtRef.current) / 1000;
    const rate = elapsed > 0 ? uploadedBytes / elapsed : 0;
    const active = summary.entries.find((entry) => entry?.status === "uploading");
    reportSection(ownerId, label, {
      fileIndex: Math.min(summary.ready, Math.max(0, summary.total - 1)),
      fileCount: summary.total,
      uploadedBytes,
      totalBytes,
      fraction: summary.fraction,
      etaSeconds: rate > 0 ? Math.max(0, Math.round((totalBytes - uploadedBytes) / rate)) : null,
      currentFileName: active?.file.name ?? files[0]?.name ?? ""
    });
  }, [files, label, ownerId, reportSection, summary]);

  // Unmounting mid-transfer must not strand a ghost row in the tray.
  useEffect(() => {
    return () => reportSection(ownerId, label, null);
  }, [ownerId, label, reportSection]);

  return summary;
}
