"use client";

import { Download, ExternalLink, FileText, Headphones, Image as ImageIcon, Loader2, Maximize2, Video, X } from "lucide-react";
import type { ReactNode } from "react";

import { bytes } from "@/lib/format";
import type { MediaType } from "@/lib/types";

export type PreviewMedia = {
  key: string;
  name: string;
  mediaType: MediaType;
  mimeType?: string | null;
  sizeBytes?: number | string | null;
  url?: string | null;
  caption?: string | null;
  transcriptStatus?: string | null;
  transcriptText?: string | null;
  transcriptError?: string | null;
};

function LightboxTranscript({ item }: { item: PreviewMedia }) {
  if (item.mediaType !== "AUDIO") return null;
  const status = (item.transcriptStatus ?? "").toUpperCase();
  const text = item.transcriptText?.trim();
  if (text) {
    return (
      <div className="rounded-md border border-[#e6dfd8] bg-field-50 p-3">
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Transcript</div>
        <p className="whitespace-pre-wrap text-sm text-ink">{text}</p>
      </div>
    );
  }
  if (["QUEUED", "PROCESSING", "PENDING", "RUNNING"].includes(status) || !status) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
        <span>Transcribing audio… the transcript will appear here once processing finishes.</span>
      </div>
    );
  }
  if (["COMPLETED", "EMPTY", "DONE"].includes(status)) {
    return <div className="rounded-md border border-[#e6dfd8] bg-field-50 px-3 py-2 text-sm text-ink-muted">Transcript completed — no speech detected.</div>;
  }
  return (
    <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      Transcript {status.toLowerCase()}
      {item.transcriptError ? `: ${item.transcriptError}` : "."}
    </div>
  );
}

function mediaLabel(item: PreviewMedia) {
  return [item.mediaType, item.sizeBytes ? bytes(item.sizeBytes) : null, item.mimeType].filter(Boolean).join(" - ");
}

function iconForType(type: MediaType) {
  if (type === "IMAGE") return <ImageIcon className="h-5 w-5" aria-hidden />;
  if (type === "VIDEO") return <Video className="h-5 w-5" aria-hidden />;
  if (type === "AUDIO") return <Headphones className="h-5 w-5" aria-hidden />;
  return <FileText className="h-5 w-5" aria-hidden />;
}

export function MediaPreviewTile({
  item,
  onOpen,
  action
}: {
  item: PreviewMedia;
  onOpen: () => void;
  action?: ReactNode;
}) {
  return (
    <div className="group grid gap-2 rounded-md border border-[#e6dfd8] bg-field-50 p-2">
      <button
        type="button"
        className="relative grid aspect-[4/3] w-full place-items-center overflow-hidden rounded-md bg-field-100 text-left text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600"
        onClick={onOpen}
        aria-label={`Open preview for ${item.name}`}
      >
        {item.mediaType === "IMAGE" && item.url ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={item.url} alt={item.caption || item.name} className="h-full w-full object-cover" loading="lazy" />
        ) : item.mediaType === "VIDEO" && item.url ? (
          <video src={item.url} className="h-full w-full object-cover" muted playsInline />
        ) : item.mediaType === "AUDIO" ? (
          <div className="grid gap-2 text-center">
            <div className="mx-auto rounded-full bg-white p-3 text-field-700 shadow-sm">{iconForType(item.mediaType)}</div>
            <span className="text-xs font-semibold">Audio clip</span>
          </div>
        ) : (
          <div className="grid gap-2 text-center">
            <div className="mx-auto rounded-full bg-white p-3 text-field-700 shadow-sm">{iconForType(item.mediaType)}</div>
            <span className="text-xs font-semibold">{item.mediaType === "PDF" ? "PDF document" : "Document"}</span>
          </div>
        )}
        <span className="absolute right-2 top-2 rounded-full bg-white/95 p-1 text-ink shadow-sm">
          <Maximize2 className="h-3.5 w-3.5" aria-hidden />
        </span>
      </button>
      <div className="min-w-0">
        <div className="truncate text-sm font-medium text-ink" title={item.name}>{item.name}</div>
        <div className="truncate text-xs text-ink-muted">{mediaLabel(item)}</div>
      </div>
      {action}
    </div>
  );
}

/**
 * Force a download of the (usually cross-origin S3) file to the user's device. We fetch it as a blob
 * so the browser saves rather than navigates; if CORS blocks the fetch we fall back to a download
 * anchor, and finally to opening the URL in a new tab.
 */
async function saveToDevice(url: string, name: string) {
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = name || "media";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(objectUrl);
  } catch {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = name || "media";
    anchor.target = "_blank";
    anchor.rel = "noreferrer";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }
}

export function MediaLightbox({ item, onClose }: { item: PreviewMedia; onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-4" role="dialog" aria-modal="true" aria-label={`Preview ${item.name}`}>
      <div className="grid max-h-[92vh] w-full max-w-5xl gap-3 overflow-hidden rounded-lg bg-field-50 p-4 shadow-2xl">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="truncate font-serif text-2xl text-ink">{item.caption || item.name}</h2>
            <p className="text-sm text-ink-muted">{mediaLabel(item)}</p>
          </div>
          <div className="flex items-center gap-2">
            {item.url ? (
              <button type="button" className="field-button-secondary" onClick={() => saveToDevice(item.url as string, item.name)}>
                <Download className="h-4 w-4" aria-hidden />
                Save
              </button>
            ) : null}
            {item.url ? (
              <a className="field-button-secondary" href={item.url} target="_blank" rel="noreferrer">
                <ExternalLink className="h-4 w-4" aria-hidden />
                Open
              </a>
            ) : null}
            <button type="button" className="field-button-secondary" onClick={onClose} aria-label="Close preview">
              <X className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </div>
        <div className="grid max-h-[74vh] place-items-center overflow-auto rounded-md bg-white p-3">
          {item.mediaType === "IMAGE" && item.url ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={item.url} alt={item.caption || item.name} className="max-h-[70vh] max-w-full rounded-md object-contain" />
          ) : item.mediaType === "VIDEO" && item.url ? (
            <video src={item.url} controls className="max-h-[70vh] w-full rounded-md bg-black" />
          ) : item.mediaType === "AUDIO" && item.url ? (
            <audio src={item.url} controls className="w-full" />
          ) : item.mediaType === "PDF" && item.url ? (
            <iframe src={item.url} title={item.name} className="h-[70vh] w-full rounded-md border border-[#e6dfd8]" />
          ) : item.url ? (
            <div className="grid gap-3 text-center text-ink-muted">
              {iconForType(item.mediaType)}
              <p>This file type cannot be rendered inline. Use Open to view or download it.</p>
            </div>
          ) : (
            <div className="grid gap-3 text-center text-ink-muted">
              {iconForType(item.mediaType)}
              <p>No preview URL is available yet.</p>
            </div>
          )}
        </div>
        <LightboxTranscript item={item} />
      </div>
    </div>
  );
}
