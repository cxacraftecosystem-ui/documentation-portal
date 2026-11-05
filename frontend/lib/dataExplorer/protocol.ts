import type { IncludeKey, ManifestFile } from "@/lib/dataExplorer/contract";
import type { JobOutcome, JobProgress } from "@/lib/dataExplorer/engine";
import type { ManifestSummary } from "@/lib/dataExplorer/plan";
import type { DirectoryHandleLike, FileHandleLike } from "@/lib/dataExplorer/sinks";
import type { ManifestRequest } from "@/lib/dataExplorer/wire";

export type JobTarget =
  | { kind: "file"; handle: FileHandleLike }
  | { kind: "folder"; handle: DirectoryHandleLike }
  | { kind: "stream"; port: MessagePort }
  | { kind: "memory"; limit: number | null };

export type JobSource =
  | { kind: "files"; files: ManifestFile[] }
  | {
      kind: "manifests";
      requests: ManifestRequest[];
      extra: ManifestFile[];
      fallback?: { requests: ManifestRequest[]; extra: ManifestFile[] };
    };

export type StartMessage = {
  type: "start";
  id: string;
  source: JobSource;
  include: IncludeKey[] | null;
  target: JobTarget;
};

export type MeasureMessage = { type: "measure"; id: string; source: JobSource; include: IncludeKey[] | null };

export type CancelMessage = { type: "cancel"; id: string };

export type PauseMessage = { type: "pause" | "resume"; id: string };

export type ToWorker = StartMessage | MeasureMessage | CancelMessage | PauseMessage;

export type ManifestNotice = { files: number; summary: ManifestSummary; truncated: boolean };

export type FromWorker =
  | { type: "manifest"; id: string; manifest: ManifestNotice }
  | { type: "progress"; id: string; progress: JobProgress }
  | { type: "measured"; id: string; manifest: ManifestNotice }
  | { type: "done"; id: string; outcome: JobOutcome; blob: Blob | null; truncated: boolean }
  | { type: "cancelled"; id: string }
  | { type: "paused"; id: string; paused: boolean }
  | { type: "error"; id: string; message: string; limit: number | null };
