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
  return <span aria-hidden className={`block animate-pulse rounded-sm bg-[linear-gradient(90deg,#efedf6,#f7f6fb,#efedf6)] dark:bg-[linear-gradient(90deg,#2a2638,#211d2e,#2a2638)] ${className}`} />;
}

export function FolderSkeleton() {
  return (
    <div className="grid gap-3 p-6" role="status" aria-label="Loading">
      <Skeleton className="h-[22px] w-[70%]" />
      <Skeleton className="h-3.5 w-1/2" />
      <div className="flex gap-2">
        <Skeleton className="h-10 flex-1" />
        <Skeleton className="h-10 flex-1" />
      </div>
      {["w-[60%]", "w-[40%]", "w-[55%]"].map((width) => (
        <div key={width} className="flex items-center gap-2.5">
          <Skeleton className="h-11 w-11 shrink-0" />
          <div className="grid flex-1 gap-1.5">
            <Skeleton className="h-3" />
            <Skeleton className={`h-2.5 ${width}`} />
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
    <div role="alert" className="grid justify-items-center gap-3 rounded-[20px] border border-red-300 bg-error-100 px-6 py-8 text-center text-red-900 dark:border-[#b91c1c] dark:bg-[#4a1717] dark:text-red-100">
      <span className="grid h-14 w-14 place-items-center rounded-[16px] bg-card text-2xl text-red-700 dark:text-red-300" aria-hidden>
        !
      </span>
      <p className="font-display text-lg leading-6 font-extrabold">{title}</p>
      <p className="max-w-xs text-sm leading-[21px]">
        {children ? <>{children} </> : null}
        {left > 0 ? `Trying again in ${left} s. Your selection and filters are kept.` : "Trying again…"}
      </p>
      <button
        type="button"
        className="min-h-11 rounded-md bg-red-700 px-4 text-sm font-semibold text-white hover:bg-red-800 pointer-coarse:min-h-11"
        onClick={() => {
          setLeft(seconds);
          onRetry();
        }}
      >
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
    <p role="status" className="rounded-lg border border-[#fcd34d] bg-amber-100 px-4 py-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950/50 dark:text-amber-100">
      <strong className="font-semibold">You&apos;re offline.</strong> Showing what was loaded {minutesAgo(loadedAt)}. Downloads pause and pick up again by themselves when you&apos;re back online.
    </p>
  );
}

export function PillMenu({ label, ariaLabel, value, options, onChange, className = "" }: { label: string; ariaLabel: string; value: string; options: { value: string; label: string }[]; onChange: (value: string) => void; className?: string }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) setOpen(false);
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("pointerdown", away);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("pointerdown", away);
      window.removeEventListener("keydown", key);
    };
  }, [open]);
  const pick = (next: string) => {
    onChange(next);
    setOpen(false);
  };
  return (
    <div className="relative" ref={box}>
      <button type="button" aria-haspopup="listbox" aria-expanded={open} aria-label={ariaLabel} onClick={() => setOpen((shown) => !shown)} className={className}>
        {label} ▾
      </button>
      {open ? (
        <ul role="listbox" aria-label={ariaLabel} className="absolute left-0 top-[calc(100%+4px)] z-30 grid max-h-72 w-52 gap-0.5 overflow-y-auto rounded-md border border-line-200 bg-card p-1 shadow-md">
          {options.map((option) => (
            <li
              key={option.value || "__any"}
              role="option"
              aria-selected={option.value === value}
              tabIndex={0}
              onClick={() => pick(option.value)}
              onKeyDown={(event) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                pick(option.value);
              }}
              className={`flex min-h-10 cursor-pointer items-center rounded-sm px-3 text-sm ${option.value === value ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-900 hover:bg-purple-50 dark:hover:bg-purple-950/40"}`}
            >
              {option.label}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
