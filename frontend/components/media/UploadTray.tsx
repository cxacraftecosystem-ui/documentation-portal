"use client";

import { useEffect, useRef, useState } from "react";
import { CheckCircle2, ChevronDown, UploadCloud, X } from "lucide-react";

import { UploadedMediaChips, formatEta } from "@/components/media/UploadProgress";
import { bytes } from "@/lib/format";
import { useUploads } from "@/lib/uploads";

/**
 * Page-level upload dock: one aggregate bar for everything this page is pushing, the per-section
 * breakdown behind it, and the media that already landed. Sections publish into <UploadsProvider>
 * via <UploadProgress sectionId=…>, so a form only ever reports its own batch and this adds up the
 * page scope. Hidden entirely while the page is idle and nothing has been uploaded yet.
 */
export function UploadTray() {
  const { enabled, aggregate, sections, completed, clearCompleted } = useUploads();
  const [expanded, setExpanded] = useState(false);
  // Height of the dock, mirrored into a spacer in normal flow so the fixed card can never sit on
  // top of a form's submit button when the page is scrolled to the bottom.
  const [dockHeight, setDockHeight] = useState(0);
  const dockRef = useRef<HTMLDivElement | null>(null);

  const uploading = Boolean(aggregate);
  const visible = enabled && (uploading || completed.length > 0);

  // Uploading opens the breakdown; the user can collapse it again and it stays collapsed once idle.
  useEffect(() => {
    if (uploading) setExpanded(true);
  }, [uploading]);

  useEffect(() => {
    const node = dockRef.current;
    if (!node || !visible) {
      setDockHeight(0);
      return;
    }
    const measure = () => setDockHeight(node.offsetHeight);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [visible, expanded, sections.length, completed.length]);

  if (!visible) return null;

  const percent = aggregate ? Math.round(aggregate.fraction * 100) : 100;

  return (
    <>
      {/* Flow spacer — reserves exactly the room the fixed dock occupies. */}
      <div aria-hidden style={{ height: dockHeight }} />
      <div
        ref={dockRef}
        className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-4 pt-3"
        style={{ paddingBottom: "max(env(safe-area-inset-bottom), 0.75rem)" }}
      >
        <div
          className="pointer-events-auto mx-auto grid max-w-7xl gap-3 rounded-lg border border-line-200 bg-card p-3 shadow-lg sm:p-4"
          role="status"
          aria-live="polite"
        >
          <div className="flex items-center gap-3">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-purple-900 text-purple-100">
              {uploading ? (
                <UploadCloud className="h-4 w-4 animate-pulse" aria-hidden />
              ) : (
                <CheckCircle2 className="h-4 w-4" aria-hidden />
              )}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate font-display text-sm font-bold text-ink-900">
                {aggregate
                  ? `Uploading ${aggregate.fileCount} file${aggregate.fileCount === 1 ? "" : "s"} across ${sections.length} section${
                      sections.length === 1 ? "" : "s"
                    }`
                  : `${completed.length} file${completed.length === 1 ? "" : "s"} uploaded on this page`}
              </p>
              <p className="truncate text-xs text-ink-500" title={aggregate?.currentFileName}>
                {aggregate
                  ? `${aggregate.currentFileName} · ${bytes(aggregate.uploadedBytes)} of ${bytes(aggregate.totalBytes)} · ${formatEta(
                      aggregate.etaSeconds
                    )}`
                  : "Open one to preview it, or dismiss this summary."}
              </p>
            </div>
            {aggregate ? <span className="shrink-0 text-sm font-semibold tabular-nums text-ink-900">{percent}%</span> : null}
            <button
              type="button"
              onClick={() => setExpanded((current) => !current)}
              aria-expanded={expanded}
              aria-label={expanded ? "Hide upload details" : "Show upload details"}
              className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-500 transition hover:border-purple-300 hover:bg-purple-50"
            >
              <ChevronDown className={`h-4 w-4 transition-transform ${expanded ? "rotate-180" : ""}`} aria-hidden />
            </button>
            {!uploading ? (
              <button
                type="button"
                onClick={clearCompleted}
                aria-label="Dismiss the upload summary"
                className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-500 transition hover:border-purple-300 hover:bg-purple-50"
              >
                <X className="h-4 w-4" aria-hidden />
              </button>
            ) : null}
          </div>

          {aggregate ? (
            <div className="h-2 overflow-hidden rounded-full bg-line-200">
              <div className="h-full rounded-full bg-purple-700 transition-all" style={{ width: `${percent}%` }} />
            </div>
          ) : null}

          {expanded && sections.length ? (
            <ul className="grid gap-2">
              {sections.map((section) => {
                const sectionPercent = Math.round(section.progress.fraction * 100);
                return (
                  <li key={section.id} className="grid gap-1">
                    <div className="flex items-center justify-between gap-3 text-xs">
                      <span className="truncate text-ink-700">{section.label}</span>
                      <span className="shrink-0 tabular-nums text-ink-500">
                        {sectionPercent}% · file {Math.min(section.progress.fileIndex + 1, section.progress.fileCount)} of{" "}
                        {section.progress.fileCount} · {formatEta(section.progress.etaSeconds)}
                      </span>
                    </div>
                    <div className="h-1.5 overflow-hidden rounded-full bg-line-200">
                      <div className="h-full rounded-full bg-purple-600 transition-all" style={{ width: `${sectionPercent}%` }} />
                    </div>
                  </li>
                );
              })}
            </ul>
          ) : null}

          {expanded && completed.length ? (
            <div className="grid gap-1.5 border-t border-line-200 pt-3">
              <p className="text-xs font-semibold uppercase tracking-wide text-ink-500">
                Uploaded on this page ({completed.length})
              </p>
              <div className="max-h-32 overflow-y-auto">
                <UploadedMediaChips items={completed} showSection />
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </>
  );
}
