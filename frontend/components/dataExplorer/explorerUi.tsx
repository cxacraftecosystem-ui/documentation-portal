"use client";

import {
  AudioLines,
  Brush,
  ClipboardList,
  File as FileIcon,
  FileImage,
  FileText,
  Film,
  Folder,
  GitBranch,
  HardDrive,
  Layers,
  Package,
  RefreshCw,
  User,
  UserCircle,
  UsersRound,
  Wrench
} from "lucide-react";
import type { ReactNode } from "react";

import type { TreeEntry } from "@/lib/dataExplorer/contract";

export function RecordIcon({ recordType, root = false, className }: { recordType?: string | null; root?: boolean; className?: string }) {
  if (root) return <HardDrive className={className} aria-hidden />;
  switch (recordType) {
    case "workshop":
      return <UsersRound className={className} aria-hidden />;
    case "craft":
    case "designWorkshopStage":
      return <Brush className={className} aria-hidden />;
    case "artisan":
      return <User className={className} aria-hidden />;
    case "product":
      return <Package className={className} aria-hidden />;
    case "tool":
      return <Wrench className={className} aria-hidden />;
    case "process":
      return <GitBranch className={className} aria-hidden />;
    case "interview":
      return <ClipboardList className={className} aria-hidden />;
    case "user":
      return <UserCircle className={className} aria-hidden />;
    case "designWorkshop":
      return <Layers className={className} aria-hidden />;
    default:
      return <Folder className={className} aria-hidden />;
  }
}

export function FileTypeIcon({ entry, className }: { entry: Pick<TreeEntry, "mediaType" | "content">; className?: string }) {
  switch (entry.mediaType) {
    case "IMAGE":
      return <FileImage className={className} aria-hidden />;
    case "VIDEO":
      return <Film className={className} aria-hidden />;
    case "AUDIO":
      return <AudioLines className={className} aria-hidden />;
    case "PDF":
    case "DOCUMENT":
      return <FileText className={className} aria-hidden />;
    default:
      return entry.content != null ? <FileText className={className} aria-hidden /> : <FileIcon className={className} aria-hidden />;
  }
}

export const LINK_BUTTON =
  "inline-flex min-h-9 items-center gap-1.5 rounded-md px-2 text-sm font-semibold text-purple-700 hover:bg-purple-50 hover:underline disabled:opacity-50 dark:text-purple-300 dark:hover:bg-purple-950/40 pointer-coarse:min-h-11";

export const CHIP_BUTTON =
  "field-button-secondary min-h-9 px-3 text-xs pointer-coarse:min-h-11";

export function Problem({ children, onRetry, retryLabel = "Try again" }: { children: ReactNode; onRetry?: () => void; retryLabel?: string }) {
  return (
    <div role="alert" className="flex flex-wrap items-center gap-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
      <span className="min-w-0 flex-1">{children}</span>
      {onRetry ? (
        <button type="button" className="field-button-secondary min-h-9 px-3 text-xs pointer-coarse:min-h-11" onClick={onRetry}>
          <RefreshCw className="h-3.5 w-3.5" aria-hidden />
          {retryLabel}
        </button>
      ) : null}
    </div>
  );
}

export function Note({ children, tone = "info" }: { children: ReactNode; tone?: "info" | "warn" | "done" }) {
  const look =
    tone === "warn"
      ? "border-amber-500/30 bg-amber-100 text-amber-800"
      : tone === "done"
        ? "border-success-600/25 bg-success-100 text-success-600"
        : "border-line-200 bg-surface-50 text-ink-700";
  return <p className={`rounded-md border px-3 py-2 text-xs ${look}`}>{children}</p>;
}

export function Spinner({ label }: { label: string }) {
  return (
    <p className="flex items-center gap-2 text-sm text-ink-500" role="status">
      <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden />
      {label}
    </p>
  );
}
