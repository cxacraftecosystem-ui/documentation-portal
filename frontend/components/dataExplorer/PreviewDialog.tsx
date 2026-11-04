"use client";

import { Download, ExternalLink } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { mediaDownloadUrl, mediaPreviewUrl } from "@/lib/dataExplorer/api";
import type { TreeEntry } from "@/lib/dataExplorer/contract";

export const PREVIEWABLE = new Set(["IMAGE", "VIDEO", "AUDIO", "PDF"]);

export function canPreview(entry: Pick<TreeEntry, "mediaType" | "mediaId" | "url" | "previewPath">): boolean {
  return PREVIEWABLE.has(entry.mediaType ?? "") && Boolean(entry.mediaId || entry.url);
}

export function previewSource(entry: Pick<TreeEntry, "mediaId" | "url" | "previewPath">): string | null {
  if (entry.previewPath) return `/api${entry.previewPath}`;
  if (entry.mediaId) return mediaPreviewUrl(entry.mediaId);
  return entry.url ?? null;
}

export function PreviewDialog({
  entry,
  onClose,
  recordHref
}: {
  entry: TreeEntry | null;
  onClose: () => void;
  recordHref: string | null;
}) {
  const [failed, setFailed] = useState<string | null>(null);
  const source = entry ? previewSource(entry) : null;
  const broken = Boolean(entry && failed === entry.path);
  const fallback = entry?.url && entry.url !== source ? entry.url : null;
  const shown = broken && fallback ? fallback : source;
  const markBroken = () => {
    if (entry) setFailed(entry.path);
  };

  return (
    <FieldDialog
      open={Boolean(entry)}
      onClose={onClose}
      title={entry?.name ?? "Preview"}
      className="max-w-3xl!"
      footer={
        entry ? (
          <>
            {recordHref ? (
              <Link href={recordHref} className="field-button-secondary">
                <ExternalLink className="h-4 w-4" aria-hidden />
                Open file page
              </Link>
            ) : null}
            {entry.mediaId ? (
              <a href={mediaDownloadUrl(entry.mediaId)} download className="field-button">
                <Download className="h-4 w-4" aria-hidden />
                Download
              </a>
            ) : null}
          </>
        ) : null
      }
    >
      {entry && shown ? (
        <div className="grid place-items-center overflow-hidden rounded-md bg-surface-50" data-testid="preview-body">
          {entry.mediaType === "IMAGE" ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={shown} alt={entry.name} className="max-h-[70vh] w-auto object-contain" onError={markBroken} />
          ) : entry.mediaType === "VIDEO" ? (
            <video src={shown} controls preload="metadata" className="max-h-[70vh] w-full bg-black" onError={markBroken}>
              <track kind="captions" />
            </video>
          ) : entry.mediaType === "AUDIO" ? (
            <div className="w-full p-4">
              <AudioPlayer src={shown} />
            </div>
          ) : (
            <object data={shown} type="application/pdf" className="h-[70vh] w-full" aria-label={entry.name}>
              <p className="p-6 text-sm text-ink-500">This browser can&apos;t show the PDF here. Download it instead.</p>
            </object>
          )}
        </div>
      ) : (
        <p className="text-sm text-ink-500">No preview for this file.</p>
      )}
      {broken && !fallback ? <p className="mt-2 text-xs text-red-800">The preview didn&apos;t load.</p> : null}
    </FieldDialog>
  );
}
