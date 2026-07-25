"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import type { BatchProgress } from "@/lib/media";
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
