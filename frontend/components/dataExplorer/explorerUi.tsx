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
import { useEffect, useRef, useState, type ReactNode } from "react";

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

export function Problem({ children, onRetry, retryLabel = "Retry" }: { children: ReactNode; onRetry?: () => void; retryLabel?: string }) {
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

export function Skeleton({ className = "" }: { className?: string }) {
  return <span aria-hidden className={`block animate-pulse rounded-md bg-field-200 ${className}`} />;
}

export function FolderSkeleton() {
  return (
    <div className="grid gap-3 p-4" role="status" aria-label="Loading">
      <Skeleton className="h-5 w-1/3" />
      <Skeleton className="h-4 w-1/2" />
      {[0, 1, 2, 3].map((row) => (
        <div key={row} className="flex items-center gap-3">
          <Skeleton className="h-10 w-12 shrink-0" />
          <div className="grid flex-1 gap-1.5">
            <Skeleton className="h-3.5 w-2/3" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </div>
      ))}
    </div>
  );
}

export const AUTO_RETRY_SECONDS = 8;

export function LoadFailed({ title, onRetry, seconds = AUTO_RETRY_SECONDS, children }: { title: string; onRetry: () => void; seconds?: number; children?: ReactNode }) {
  const [left, setLeft] = useState(seconds);
  const retry = useRef(onRetry);
  useEffect(() => {
    retry.current = onRetry;
  });
  useEffect(() => {
    if (left <= 0) {
      retry.current();
      return;
    }
    const timer = window.setTimeout(() => setLeft((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [left]);
  return (
    <div role="alert" className="grid justify-items-center gap-2 rounded-md border border-red-300 bg-error-100 px-4 py-6 text-center text-red-900">
      <p className="font-display text-base font-bold">{title}</p>
      <p className="text-sm">
        {children ? <>{children} </> : null}
        {left > 0 ? `Trying again in ${left} s. Your selection and filters are kept.` : "Trying again…"}
      </p>
      <button
        type="button"
        className="inline-flex min-h-10 items-center gap-1.5 rounded-md border border-red-300 bg-card px-4 text-sm font-semibold text-red-900 hover:bg-error-100 pointer-coarse:min-h-11"
        onClick={() => {
          setLeft(seconds);
          onRetry();
        }}
      >
        <RefreshCw className="h-4 w-4" aria-hidden />
        Try now
      </button>
    </div>
  );
}

export function useOnline(): { online: boolean; since: number | null } {
  const [state, setState] = useState<{ online: boolean; since: number | null }>({ online: true, since: null });
  useEffect(() => {
    const update = () => setState(navigator.onLine ? { online: true, since: null } : { online: false, since: Date.now() });
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);
  return state;
}

export function minutesAgo(at: number | null, now = Date.now()): string {
  if (!at) return "a moment ago";
  const minutes = Math.round((now - at) / 60000);
  return minutes < 1 ? "just now" : `${minutes} min ago`;
}

export function OfflineNote({ loadedAt }: { loadedAt: number | null }) {
  return (
    <p role="status" className="rounded-md border border-amber-500/30 bg-amber-100 px-4 py-2.5 text-sm text-amber-800">
      You&apos;re offline. Showing what was loaded {minutesAgo(loadedAt)}. Downloads pick up again when you&apos;re back online.
    </p>
  );
}
