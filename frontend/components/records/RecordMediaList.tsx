"use client";

import { useState } from "react";

import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { TranscriptBlock } from "@/components/media/TranscriptBlock";
import { formatDateTime } from "@/lib/format";
import type { MediaFile } from "@/lib/types";

function toPreview(media: MediaFile): PreviewMedia {
  return {
    key: media.id,
    id: media.id,
    name: media.originalFilename,
    mediaType: media.mediaType,
    mimeType: media.mimeType,
    sizeBytes: media.sizeBytes,
    url: media.url,
    caption: media.caption,
    transcriptStatus: media.transcriptStatus,
    transcriptText: media.transcriptText,
    transcriptError: media.transcriptError
  };
}

/**
 * Files that arrived INSIDE a record (an interview's recordings, a process step's photographs),
 * drawn read-only for its view: a preview tile per file that opens the lightbox — which carries the
 * Download button — and the transcript under any audio. Nothing here removes or replaces a file;
 * that is the edit form's job, behind "Update".
 */
export function RecordMediaList({ items, empty = "No files attached." }: { items: MediaFile[]; empty?: string }) {
  const [active, setActive] = useState<PreviewMedia | null>(null);
  if (items.length === 0) return <p className="text-sm text-ink-muted">{empty}</p>;
  return (
    <div className="grid gap-3">
      {items.map((media) => {
        const preview = toPreview(media);
        return (
          <div key={media.id} className="grid gap-2 rounded-md border border-line-200 bg-field-50 p-2 sm:grid-cols-[200px_1fr] sm:items-start">
            <MediaPreviewTile item={preview} onOpen={() => setActive(preview)} />
            <div className="min-w-0">
              <div className="truncate text-sm font-medium text-ink" title={media.originalFilename}>
                {media.caption || media.originalFilename}
              </div>
              <div className="text-xs text-ink-muted">
                Uploaded by {media.uploadedBy?.name ?? "Unknown"} · {formatDateTime(media.createdAt)}
              </div>
              <TranscriptBlock media={media} />
            </div>
          </div>
        );
      })}
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </div>
  );
}
