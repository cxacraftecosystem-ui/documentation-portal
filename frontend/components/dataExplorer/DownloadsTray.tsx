"use client";

import { CheckCircle2, ChevronDown, ChevronUp, Download, RefreshCw, X } from "lucide-react";
import { useState } from "react";

import type { Downloads } from "@/components/dataExplorer/useDownloads";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { TONE_CLASS, toneOf, trayHeadline, type DownloadItem } from "@/lib/dataExplorer/downloads";
import { formatBytes, percentOf } from "@/lib/dataExplorer/plan";

const TRAY_BUTTON =
  "inline-flex min-h-9 items-center justify-center gap-1.5 rounded-md border border-line-200 bg-card px-3 text-xs font-semibold text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60 pointer-coarse:min-h-11";

export function itemLine(item: DownloadItem): string {
  const parts: string[] = [];
  if (item.bytesTotal) parts.push(`${formatBytes(item.bytesDone)} of ${formatBytes(item.bytesTotal)}`);
  else if (item.bytesDone) parts.push(formatBytes(item.bytesDone));
  if (item.filesTotal !== null && item.filesDone !== null && item.kind === "zip") parts.push(`file ${item.filesDone.toLocaleString("en-IN")} of ${item.filesTotal.toLocaleString("en-IN")}`);
  if (item.rate) parts.push(`${formatBytes(item.rate)}/s`);
  if (item.timeLeft) parts.push(item.timeLeft);
  return parts.join(" · ");
}

function savedLine(item: DownloadItem, now: number): string {
  const parts: string[] = [];
  if (item.kind === "zip" && item.filesDone !== null) parts.push(`${item.filesDone.toLocaleString("en-IN")} files`);
  if (item.bytesDone) parts.push(formatBytes(item.bytesDone));
  if (item.note) parts.push(item.note);
  if (item.finishedAt) {
    const minutes = Math.round((now - item.finishedAt) / 60000);
    parts.push(minutes < 1 ? "just now" : `${minutes} min ago`);
  }
  return parts.join(" · ");
}

function Row({ item, downloads, zipRunning }: { item: DownloadItem; downloads: Downloads; zipRunning: boolean }) {
  const tone = toneOf(item.status);
  const percent = item.bytesTotal ? percentOf(item.bytesDone, item.bytesTotal) : null;
  const retryIn = item.retryAt ? Math.max(0, Math.ceil((item.retryAt - downloads.now) / 1000)) : null;
  return (
    <li className={`grid gap-1.5 rounded-md border p-3 ${TONE_CLASS[tone]}`} data-status={item.status} data-testid="tray-item">
      <div className="flex items-center gap-2 text-sm">
        {item.status === "saved" ? <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden /> : null}
        <strong className="min-w-0 flex-1 truncate font-semibold">{item.label}</strong>
        <span className="shrink-0 text-xs font-semibold">
          {item.status === "running" ? (item.note === "Paused" ? "Paused" : percent !== null ? `${percent}%` : "Working") : item.status === "waiting" ? (item.note === "Paused" ? "Paused" : "Waiting") : item.status === "saved" ? "Saved" : item.status === "failed" ? "Failed" : "Cancelled"}
        </span>
      </div>
      {item.status === "running" ? (
        <>
          <ProgressBar
            percent={percent ?? 0}
            className={`h-2 ${percent === null ? "animate-pulse" : ""}`}
            role="progressbar"
            aria-label={`${item.label} progress`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent ?? undefined}
          />
          <p className="text-xs text-ink-700 tabular-nums">{item.note === "Paused" ? ["Paused", itemLine(item)].filter(Boolean).join(" · ") : (item.note ?? itemLine(item))}</p>
          {item.current ? <p className="truncate text-xs text-ink-500">Now: {item.current}</p> : null}
          {item.failedFiles.length ? <p className="rounded-sm bg-amber-100 px-2 py-1 text-xs text-amber-800">{item.failedFiles.length} {item.failedFiles.length === 1 ? "file" : "files"} will be listed as failed.</p> : null}
          <ActionGroup align="start">
            {item.kind !== "report" ? (
              item.note === "Paused" ? (
                <button type="button" className={TRAY_BUTTON} onClick={() => downloads.startNow(item.id)}>
                  Resume
                </button>
              ) : (
                <button type="button" className={TRAY_BUTTON} onClick={() => downloads.pause(item.id)}>
                  Pause
                </button>
              )
            ) : null}
            <button type="button" className={TRAY_BUTTON} data-danger="" onClick={() => downloads.cancel(item.id)}>
              Cancel
            </button>
          </ActionGroup>
        </>
      ) : null}
      {item.status === "waiting" ? (
        <p className="flex flex-wrap items-center gap-x-2 text-xs text-ink-500">
          <span>{item.note === "Paused" ? `Paused at ${formatBytes(item.bytesDone)}` : "Starts when the one above finishes"}</span>
          {item.kind !== "zip" || !zipRunning ? (
            <button type="button" className="min-h-9 font-semibold text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => downloads.startNow(item.id)}>
              {item.note === "Paused" ? "Resume" : "Start now"}
            </button>
          ) : null}
          <button type="button" className="min-h-9 font-semibold text-ink-700 hover:underline pointer-coarse:min-h-11" onClick={() => downloads.cancel(item.id)}>
            Remove
          </button>
        </p>
      ) : null}
      {item.status === "saved" ? <p className="text-xs">{savedLine(item, downloads.now)}</p> : null}
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
          <p className="text-xs">
            {item.error}
            {item.note ? ` ${item.note}` : ""}
            {retryIn !== null ? ` Trying again in ${retryIn} s.` : ""}
          </p>
          <ActionGroup align="start">
            {item.kind !== "zip" ? (
              <button type="button" className={TRAY_BUTTON} onClick={() => downloads.startNow(item.id)}>
                <RefreshCw className="h-3.5 w-3.5" aria-hidden />
                {retryIn !== null ? "Try now" : "Retry"}
              </button>
            ) : null}
            <button type="button" className={TRAY_BUTTON} onClick={() => downloads.dismiss(item.id)}>
              Dismiss
            </button>
          </ActionGroup>
        </>
      ) : null}
      {item.status === "cancelled" ? (
        <p className="flex items-center gap-2 text-xs text-ink-500">
          <span className="flex-1">{item.note ?? "Cancelled"}</span>
          <button type="button" className="min-h-9 font-semibold hover:underline pointer-coarse:min-h-11" onClick={() => downloads.dismiss(item.id)}>
            Dismiss
          </button>
        </p>
      ) : null}
    </li>
  );
}

export function DownloadsTray({ downloads }: { downloads: Downloads }) {
  const [minimised, setMinimised] = useState(false);
  const items = downloads.items;
  const running = items.find((item) => item.status === "running") ?? null;
  const zipRunning = items.some((item) => item.kind === "zip" && item.status === "running");
  const done = items.some((item) => item.status === "saved" || item.status === "cancelled");
  return (
    <>
      <div className="sr-only" role="status" aria-live="polite" data-testid="download-announcer">
        {downloads.announcement}
      </div>
      {items.length ? (
        <section
          aria-label="Downloads"
          data-testid="downloads-tray"
          className="fixed inset-x-3 bottom-[calc(var(--bottom-nav-h,0px)+0.75rem+env(safe-area-inset-bottom))] z-40 grid max-h-[min(70dvh,560px)] gap-2 overflow-hidden rounded-lg border border-line-200 bg-card shadow-lg sm:inset-x-auto sm:right-6 sm:bottom-6 sm:w-96"
        >
          <header className="flex items-center gap-2 border-b border-line-200 px-4 py-3">
            <Download className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
            <div className="min-w-0 flex-1">
              <h2 className="font-display text-sm font-bold text-ink-900">Downloads</h2>
              <p className="truncate text-xs text-ink-500">{minimised && running ? `${running.label} · ${running.bytesTotal ? `${percentOf(running.bytesDone, running.bytesTotal)}%` : "working"}` : trayHeadline(items)}</p>
            </div>
            {done && !minimised ? (
              <button type="button" className={TRAY_BUTTON} onClick={downloads.clearDone}>
                Clear done
              </button>
            ) : null}
            <button
              type="button"
              className="grid h-9 w-9 place-items-center rounded-md text-ink-700 hover:bg-purple-50 pointer-coarse:h-11 pointer-coarse:w-11"
              aria-label={minimised ? "Show downloads" : "Minimise downloads"}
              aria-expanded={!minimised}
              onClick={() => setMinimised((value) => !value)}
            >
              {minimised ? <ChevronUp className="h-4 w-4" aria-hidden /> : <ChevronDown className="h-4 w-4" aria-hidden />}
            </button>
            {!running ? (
              <button
                type="button"
                className="grid h-9 w-9 place-items-center rounded-md text-ink-700 hover:bg-purple-50 pointer-coarse:h-11 pointer-coarse:w-11"
                aria-label="Close downloads"
                onClick={() => items.forEach((item) => downloads.dismiss(item.id))}
              >
                <X className="h-4 w-4" aria-hidden />
              </button>
            ) : null}
          </header>
          {minimised ? null : (
            <>
              <ul className="grid gap-2 overflow-y-auto overscroll-contain px-3" aria-label="Download queue">
                {items.map((item) => (
                  <Row key={item.id} item={item} downloads={downloads} zipRunning={zipRunning} />
                ))}
              </ul>
              <p className="border-t border-line-200 px-4 py-2 text-xs text-ink-500">Keeps going if you switch tabs. Closing this tab asks first.</p>
            </>
          )}
        </section>
      ) : null}
    </>
  );
}
