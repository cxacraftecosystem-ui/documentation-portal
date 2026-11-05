"use client";

import { useState } from "react";

import type { Downloads } from "@/components/dataExplorer/useDownloads";
import { trayHeadline, type DownloadItem, type DownloadStatus } from "@/lib/dataExplorer/downloads";
import { bytesOf, formatBytes, percentOf } from "@/lib/dataExplorer/plan";

const TEXT_BUTTON = "min-h-8 rounded-sm px-2.5 text-xs font-semibold transition hover:bg-surface-50 disabled:opacity-60 pointer-coarse:min-h-11";

const ORDER: Record<DownloadStatus, number> = { running: 0, waiting: 1, saved: 2, cancelled: 3, failed: 4 };

function baseName(path: string): string {
  return path.split("/").filter(Boolean).pop() ?? path;
}

export function trayOrder(items: readonly DownloadItem[]): DownloadItem[] {
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => ORDER[a.item.status] - ORDER[b.item.status] || a.index - b.index)
    .map(({ item }) => item);
}

function sheetCount(item: DownloadItem): number | null {
  if (!item.url) return null;
  try {
    const sheets = new URL(item.url, "http://local").searchParams.get("sheets");
    return sheets ? sheets.split(",").filter(Boolean).length : null;
  } catch {
    return null;
  }
}

export function itemLine(item: DownloadItem): string {
  const parts: string[] = [];
  if (item.bytesTotal) parts.push(bytesOf(item.bytesDone, item.bytesTotal));
  else if (item.bytesDone) parts.push(formatBytes(item.bytesDone));
  if (item.filesTotal !== null && item.filesDone !== null && item.kind === "zip") parts.push(`file ${item.filesDone.toLocaleString("en-IN")} of ${item.filesTotal.toLocaleString("en-IN")}`);
  if (item.rate) parts.push(`${formatBytes(item.rate)}/s`);
  if (item.timeLeft) parts.push(item.timeLeft);
  return parts.join(" · ");
}

function savedLine(item: DownloadItem, now: number): string {
  const parts: string[] = [];
  const savedNote = item.kind === "zip" && item.note && /^Saved \d/.test(item.note) ? item.note : null;
  if (savedNote) parts.push(savedNote);
  else if (item.kind === "zip" && item.filesDone !== null) parts.push(`${item.filesDone.toLocaleString("en-IN")} files`);
  if (item.kind === "report" || item.kind === "pack") {
    const sheets = sheetCount(item);
    if (sheets) parts.push(`${sheets} ${sheets === 1 ? "sheet" : "sheets"}`);
  }
  if (item.bytesDone) parts.push(formatBytes(item.bytesDone));
  if (item.note && item.kind !== "zip") parts.push(item.note.charAt(0).toLowerCase() + item.note.slice(1));
  else if (item.note && item.kind === "zip" && !savedNote) parts.push(item.note);
  if (item.finishedAt && item.kind !== "report" && item.kind !== "pack") {
    const minutes = Math.round((now - item.finishedAt) / 60000);
    parts.push(minutes < 1 ? "just now" : `${minutes} min ago`);
  }
  return parts.join(" · ");
}

function Job({ item, downloads, zipRunning }: { item: DownloadItem; downloads: Downloads; zipRunning: boolean }) {
  const percent = item.bytesTotal ? percentOf(item.bytesDone, item.bytesTotal) : null;
  const retryIn = item.retryAt ? Math.max(0, Math.ceil((item.retryAt - downloads.now) / 1000)) : null;
  const paused = item.note === "Paused";
  const tone =
    item.status === "saved"
      ? "border-green-300 bg-success-100 text-green-900 dark:border-[#15803d] dark:bg-[#123b25] dark:text-green-100"
      : item.status === "failed"
        ? "border-red-300 bg-error-100 text-red-900 dark:border-[#b91c1c] dark:bg-[#4a1717] dark:text-red-100"
        : item.status === "waiting"
          ? "border-line-200 bg-surface-50 text-ink-900"
          : "border-line-200 bg-card text-ink-900";
  const sub = item.status === "saved" ? "text-green-800 dark:text-green-200" : item.status === "failed" ? "text-red-800 dark:text-red-200" : "text-ink-500";
  return (
    <li className={`grid gap-1.5 rounded-[14px] border px-3.5 py-3 ${tone}`} data-status={item.status} data-testid="tray-item">
      <div className="flex items-center justify-between gap-2 text-sm">
        <strong className="min-w-0 truncate font-semibold">
          {item.status === "saved" ? "✓ " : ""}
          {item.label}
        </strong>
        <span
          className={`shrink-0 ${
            item.status === "running"
              ? paused
                ? "text-xs text-ink-500"
                : "font-bold text-purple-700 dark:text-purple-300"
              : item.status === "saved"
                ? "font-bold text-success-600 dark:text-green-300"
                : item.status === "failed"
                  ? "font-bold text-red-700 dark:text-red-300"
                  : "text-xs text-ink-500"
          }`}
        >
          {item.status === "running" ? (paused ? "Paused" : percent !== null ? `${percent}%` : "Working") : item.status === "waiting" ? (paused ? "Paused" : "Waiting") : item.status === "saved" ? "Saved" : item.status === "failed" ? "Failed" : "Cancelled"}
        </span>
      </div>
      {item.status === "running" ? (
        <>
          <div
            className={`h-1.5 overflow-hidden rounded-full bg-progress-track ${percent === null ? "animate-pulse" : ""}`}
            role="progressbar"
            aria-label={`${item.label} progress`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent ?? undefined}
          >
            <div className="h-full bg-progress-fill" style={{ width: `${percent ?? 0}%` }} />
          </div>
          <p className="text-xs tabular-nums text-ink-500">{paused ? ["Paused", itemLine(item)].filter(Boolean).join(" · ") : (item.note ?? itemLine(item))}</p>
          {item.current ? <p className="truncate text-xs text-ink-500">Now: {baseName(item.current)}</p> : null}
          {item.failedFiles.length ? <p className="text-xs text-amber-800 dark:text-amber-200">{item.failedFiles.length} {item.failedFiles.length === 1 ? "file" : "files"} will retry.</p> : null}
          <div className="flex gap-1">
            {item.kind !== "report" && item.kind !== "pack" ? (
              paused ? (
                <button type="button" className={`${TEXT_BUTTON} text-ink-700`} onClick={() => downloads.startNow(item.id)}>
                  Resume
                </button>
              ) : (
                <button type="button" className={`${TEXT_BUTTON} text-ink-700`} onClick={() => downloads.pause(item.id)}>
                  Pause
                </button>
              )
            ) : null}
            <button type="button" className={`${TEXT_BUTTON} text-red-700 dark:text-red-300`} data-danger="" onClick={() => downloads.cancel(item.id)}>
              Cancel
            </button>
          </div>
        </>
      ) : null}
      {item.status === "waiting" ? (
        <p className="flex flex-wrap items-center gap-x-1 text-xs text-ink-500">
          <span>{paused ? `Paused at ${formatBytes(item.bytesDone)}` : "Starts when the one above finishes"}</span>
          {item.kind !== "zip" || !zipRunning ? (
            <>
              <span aria-hidden>·</span>
              <button type="button" className="font-semibold text-purple-700 underline underline-offset-2 dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => downloads.startNow(item.id)}>
                {paused ? "Resume" : "Start now"}
              </button>
            </>
          ) : null}
          <span aria-hidden>·</span>
          <button type="button" className="font-semibold text-ink-700 hover:underline pointer-coarse:min-h-11" onClick={() => downloads.cancel(item.id)}>
            Remove
          </button>
        </p>
      ) : null}
      {item.status === "saved" ? <p className={`text-xs ${sub}`}>{savedLine(item, downloads.now)}</p> : null}
      {item.status === "saved" && item.failedFiles.length ? (
        <details className="text-xs" open>
          <summary className="cursor-pointer font-semibold">{item.failedFiles.length} not downloaded</summary>
          <ul className="mt-1 grid gap-0.5" data-testid="failed-files">
            {item.failedFiles.map((entry) => (
              <li key={entry.path} className="break-all">
                {entry.path} · {entry.reason}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      {item.status === "failed" ? (
        <>
          <p className={`text-xs ${sub}`}>
            {item.error}
            {item.note ? ` ${item.note}` : ""}
            {retryIn !== null ? ` Trying again in ${retryIn} s.` : ""}
          </p>
          <div className="flex gap-1">
            {item.kind !== "zip" ? (
              <button type="button" className={`${TEXT_BUTTON} bg-red-700 text-white hover:bg-red-800`} onClick={() => downloads.startNow(item.id)}>
                {retryIn !== null ? "Try now" : "Retry"}
              </button>
            ) : null}
            <button type="button" className={`${TEXT_BUTTON} text-red-900 hover:bg-red-100 dark:text-red-100 dark:hover:bg-red-950`} onClick={() => downloads.dismiss(item.id)}>
              Dismiss
            </button>
          </div>
        </>
      ) : null}
      {item.status === "cancelled" ? (
        <p className="flex items-center gap-2 text-xs text-ink-500">
          <span className="flex-1">{item.note ?? "Cancelled"}</span>
          <button type="button" className={`${TEXT_BUTTON} text-ink-700`} onClick={() => downloads.dismiss(item.id)}>
            Dismiss
          </button>
        </p>
      ) : null}
    </li>
  );
}

function Solo({ item, downloads, minimised, onMinimise }: { item: DownloadItem; downloads: Downloads; minimised: boolean; onMinimise: () => void }) {
  const percent = item.bytesTotal ? percentOf(item.bytesDone, item.bytesTotal) : null;
  const paused = item.note === "Paused";
  const zip = item.kind === "zip";
  const left = [item.bytesTotal ? bytesOf(item.bytesDone, item.bytesTotal) : item.bytesDone ? formatBytes(item.bytesDone) : null, item.rate && !paused ? `${formatBytes(item.rate)}/s` : null].filter(Boolean).join(" · ");
  const phone = [item.bytesTotal ? bytesOf(item.bytesDone, item.bytesTotal) : null, zip && item.filesTotal !== null && item.filesDone !== null ? `file ${item.filesDone.toLocaleString("en-IN")} of ${item.filesTotal.toLocaleString("en-IN")}` : null, item.timeLeft]
    .filter(Boolean)
    .join(" · ");
  return (
    <li className="grid gap-2.5 max-sm:gap-1.5" data-status={item.status} data-testid="tray-item">
      <div className="flex items-center justify-between gap-2 text-sm max-sm:text-[13px]">
        <strong className="min-w-0 truncate font-semibold">
          <span className="max-sm:hidden">Downloading · {item.label}</span>
          <span className="sm:hidden">{zip ? "Downloading folder" : `Downloading · ${item.label}`}</span>
        </strong>
        <span className="text-ink-500 sm:hidden">{paused ? "Paused" : percent !== null ? `${percent}%` : "Working"}</span>
        <button
          type="button"
          className="grid h-8 w-8 shrink-0 place-items-center rounded-sm bg-surface-50 text-ink-700 max-sm:hidden pointer-coarse:h-11 pointer-coarse:w-11"
          aria-label={minimised ? "Show downloads" : "Minimise downloads"}
          aria-expanded={!minimised}
          onClick={onMinimise}
        >
          –
        </button>
      </div>
      <div
        className={`h-2 overflow-hidden rounded-full bg-progress-track max-sm:h-1.5 ${percent === null ? "animate-pulse" : ""}`}
        role="progressbar"
        aria-label={`${item.label} progress`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent ?? undefined}
      >
        <div className="h-full rounded-full bg-progress-fill" style={{ width: `${percent ?? 0}%` }} />
      </div>
      {minimised ? null : (
        <>
          <p className="text-xs text-ink-500 tabular-nums sm:hidden">{item.note && !paused ? item.note : phone}</p>
          <div className="flex justify-between gap-2 text-xs text-ink-700 tabular-nums max-sm:hidden">
            <span>{paused ? ["Paused", left].filter(Boolean).join(" · ") : item.note && !item.bytesDone ? item.note : left}</span>
            {item.timeLeft && !paused ? <span>{item.timeLeft}</span> : null}
          </div>
          {item.current || (zip && item.filesTotal !== null) ? (
            <p className="truncate text-xs text-ink-500 max-sm:hidden">
              {[zip && item.filesTotal !== null && item.filesDone !== null ? `File ${Math.min(item.filesTotal, item.filesDone + 1).toLocaleString("en-IN")} of ${item.filesTotal.toLocaleString("en-IN")}` : null, item.current ? baseName(item.current) : null].filter(Boolean).join(" · ")}
            </p>
          ) : null}
          <p className="rounded-sm bg-amber-100 px-2.5 py-1.5 text-xs text-amber-800 max-sm:hidden dark:bg-amber-950/50 dark:text-amber-200">
            Keeps going if you switch tabs.
            {item.failedFiles.length ? ` ${item.failedFiles.length} ${item.failedFiles.length === 1 ? "file" : "files"} will retry.` : " Closing this tab asks first."}
          </p>
          <div className="flex gap-2 max-sm:hidden">
            {item.kind !== "report" && item.kind !== "pack" ? (
              <button type="button" className="min-h-10 flex-1 rounded-[10px] border border-line-200 bg-card text-[13px] text-ink-900 hover:border-purple-300" onClick={() => (paused ? downloads.startNow(item.id) : downloads.pause(item.id))}>
                {paused ? "Resume" : "Pause"}
              </button>
            ) : null}
            <button type="button" className="min-h-10 flex-1 rounded-[10px] border border-red-200 bg-card text-[13px] text-red-700 hover:bg-error-100 dark:border-red-900 dark:text-red-300" data-danger="" onClick={() => downloads.cancel(item.id)}>
              Cancel
            </button>
          </div>
        </>
      )}
    </li>
  );
}

export function DownloadsTray({ downloads }: { downloads: Downloads }) {
  const [minimised, setMinimised] = useState(false);
  const items = trayOrder(downloads.items);
  const running = items.find((item) => item.status === "running") ?? null;
  const zipRunning = items.some((item) => item.kind === "zip" && item.status === "running");
  const done = items.some((item) => item.status === "saved" || item.status === "cancelled");
  const solo = items.length === 1 && running ? running : null;
  return (
    <>
      <div className="sr-only" role="status" aria-live="polite" data-testid="download-announcer">
        {downloads.announcement}
      </div>
      {solo ? (
        <section
          aria-label="Downloads"
          data-testid="downloads-tray"
          className="fixed inset-x-4 bottom-[calc(var(--bottom-nav-h,0px)+0.75rem+env(safe-area-inset-bottom))] z-40 rounded-lg border border-line-200 bg-card p-4 shadow-[0_8px_32px_rgba(46,16,101,.18)] max-sm:px-3.5 max-sm:py-3 sm:inset-x-auto sm:right-6 sm:bottom-6 sm:w-[360px]"
        >
          <ul aria-label="Download queue">
            <Solo item={solo} downloads={downloads} minimised={minimised} onMinimise={() => setMinimised((value) => !value)} />
          </ul>
        </section>
      ) : items.length ? (
        <section
          aria-label="Downloads"
          data-testid="downloads-tray"
          className="fixed inset-x-3 bottom-[calc(var(--bottom-nav-h,0px)+0.75rem+env(safe-area-inset-bottom))] z-40 grid max-h-[min(72dvh,640px)] overflow-hidden rounded-[20px] border border-line-200 bg-card shadow-[0_16px_48px_rgba(46,16,101,.18)] sm:inset-x-auto sm:right-6 sm:bottom-6 sm:w-[440px]"
        >
          <header className="flex items-center justify-between gap-2 border-b border-line-200 px-4 py-3.5">
            <div className="min-w-0">
              <h2 className="text-[15px] leading-5 font-bold text-ink-900">Downloads</h2>
              <p className="truncate text-xs text-ink-500">{minimised && running ? `${running.label} · ${running.bytesTotal ? `${percentOf(running.bytesDone, running.bytesTotal)}%` : "working"}` : trayHeadline(items)}</p>
            </div>
            <div className="flex shrink-0 gap-1">
              {done && !minimised ? (
                <button type="button" className={`${TEXT_BUTTON} text-ink-700`} onClick={downloads.clearDone}>
                  Clear done
                </button>
              ) : null}
              <button
                type="button"
                className={`${TEXT_BUTTON} text-ink-700`}
                aria-label={minimised ? "Show downloads" : "Minimise downloads"}
                aria-expanded={!minimised}
                onClick={() => setMinimised((value) => !value)}
              >
                {minimised ? "+" : "—"}
              </button>
              {!running ? (
                <button type="button" className={`${TEXT_BUTTON} text-ink-700`} aria-label="Close downloads" onClick={() => items.forEach((item) => downloads.dismiss(item.id))}>
                  ✕
                </button>
              ) : null}
            </div>
          </header>
          {minimised ? null : (
            <>
              <ul className="grid content-start gap-2 overflow-y-auto overscroll-contain p-3" aria-label="Download queue">
                {items.map((item) => (
                  <Job key={item.id} item={item} downloads={downloads} zipRunning={zipRunning} />
                ))}
              </ul>
              <p className="border-t border-line-200 px-4 pt-2.5 pb-3.5 text-xs text-ink-500">Keeps going if you switch tabs. Closing this tab asks first.</p>
            </>
          )}
        </section>
      ) : null}
    </>
  );
}
