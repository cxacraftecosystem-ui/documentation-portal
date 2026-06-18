"use client";

import { useEffect, useState } from "react";

import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { TranscriptBlock } from "@/components/media/TranscriptBlock";
import { ApiError, apiFetch, listResource } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { MediaFile } from "@/lib/types";

/**
 * Shows the media already attached to a saved record (by linked type/id), with each item's
 * upload provenance (who uploaded it, when) and — for audio — its transcript / a "transcribing…"
 * spinner while the Whisper job is still running. Used on every edit page so previously uploaded
 * media is always visible.
 */
export function ExistingMedia({
  linkedRecordType,
  linkedRecordId,
  title = "Previously uploaded media"
}: {
  linkedRecordType: string;
  linkedRecordId: string;
  title?: string;
}) {
  const [items, setItems] = useState<MediaFile[] | null>(null);
  const [active, setActive] = useState<PreviewMedia | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);

  async function removeMedia(media: MediaFile) {
    if (!window.confirm(`Remove "${media.caption || media.originalFilename}" from this record? This permanently deletes the file.`)) return;
    setError(null);
    setRemovingId(media.id);
    try {
      await apiFetch(`/media/${media.id}`, { method: "DELETE" });
      setItems((current) => (current ? current.filter((m) => m.id !== media.id) : current));
      setActive((current) => (current && current.key === media.id ? null : current));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Unable to remove this media file.");
    } finally {
      setRemovingId(null);
    }
  }

  useEffect(() => {
    let cancelled = false;
    listResource<MediaFile>("/media", { linkedRecordType, linkedRecordId, pageSize: 100 })
      .then((result) => {
        if (!cancelled) setItems(result.items);
      })
      .catch(() => {
        if (!cancelled) setItems([]);
      });
    return () => {
      cancelled = true;
    };
  }, [linkedRecordType, linkedRecordId]);

  if (items === null) return <p className="text-sm text-ink-muted">Loading attached media…</p>;
  if (items.length === 0) return <p className="text-sm text-ink-muted">No media attached to this record yet.</p>;

  return (
    <section className="grid gap-3 rounded-lg border border-[#e6dfd8] bg-field-100 p-4">
      <div>
        <h3 className="font-serif text-lg text-ink">{title}</h3>
        <p className="mt-1 text-sm text-ink-muted">
          {items.length} file{items.length === 1 ? "" : "s"} already attached. Audio transcripts appear once processing finishes. Use the ✕ on a file to remove it.
        </p>
        {error ? <p className="mt-1 text-sm text-red-700">{error}</p> : null}
      </div>
      <div className="grid gap-3">
        {items.map((media) => {
          const preview: PreviewMedia = {
            key: media.id,
            name: media.originalFilename,
            mediaType: media.mediaType,
            mimeType: media.mimeType,
            sizeBytes: media.sizeBytes,
            url: media.url,
            caption: media.caption
          };
          return (
            <div key={media.id} className="grid gap-2 rounded-md border border-[#e6dfd8] bg-field-50 p-2 sm:grid-cols-[200px_1fr] sm:items-start">
              <MediaPreviewTile
                item={preview}
                onOpen={() => setActive(preview)}
                onRemove={removingId === media.id ? undefined : () => removeMedia(media)}
                removeLabel="Remove"
              />
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
      </div>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </section>
  );
}
