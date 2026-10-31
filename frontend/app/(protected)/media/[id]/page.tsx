"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { Images } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { RecordDangerZone } from "@/components/records/RecordDangerZone";
import { RECORD_LIST_HREF } from "@/components/records/dangerZoneRules";
import { RecordMediaList } from "@/components/records/RecordMediaList";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useChipNavigator } from "@/components/questionnaire/useUrlFilters";
import type { MediaFile } from "@/lib/types";

/**
 * One uploaded file, read-only (docs/SEARCH.md): its preview, which opens the lightbox and its
 * Download button, its transcript, and what it is. Media and transcript search results open here.
 * A stored file has no edit form, so the header offers none.
 */
export default function MediaViewPage() {
  const { id } = useParams<{ id: string }>();
  const goChip = useChipNavigator();
  const [media, setMedia] = useState<MediaFile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setMedia(null);
    setError(null);
    apiFetch<MediaFile>(`/media/${encodeURIComponent(id)}`)
      .then((loaded) => {
        if (!cancelled) setMedia(loaded);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Couldn't load this file.");
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const title = media ? media.caption?.trim() || media.originalFilename || "File" : "File";
  return (
    <>
      <PageHeader title={title} icon={<Images className="h-5 w-5" aria-hidden />} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {!media ? (
        error ? null : <div className="panel p-4 text-sm text-ink-muted">Loading this file…</div>
      ) : (
        <div className="grid gap-5">
          <section className="panel grid gap-3 p-4" aria-label="File">
            <RecordingChips chips={media.questionnaireChips} size="md" onChip={goChip} />
            <RecordMediaList items={[media]} />
          </section>
          <section className="panel p-4" aria-label={`${title} details`}>
            <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
              {mediaRows(media).map((row) => (
                <div key={row.label} className="min-w-0">
                  <dt className="field-label">{row.label}</dt>
                  <dd className="mt-0.5 break-words text-sm text-ink-900">{row.value}</dd>
                </div>
              ))}
            </dl>
          </section>
          <RecordDangerZone noun="file" endpoint={`/media/${encodeURIComponent(id)}`} afterHref={RECORD_LIST_HREF.media} />
        </div>
      )}
    </>
  );
}

function mediaRows(media: MediaFile): { label: string; value: string }[] {
  const rows = [
    { label: "File name", value: media.originalFilename },
    { label: "Type", value: media.mediaType ? String(media.mediaType).charAt(0) + String(media.mediaType).slice(1).toLowerCase() : "" },
    { label: "Recorded", value: media.recordedAt ? formatDateTime(media.recordedAt) : "" }
  ];
  return rows.filter((row) => row.value && row.value.trim());
}
