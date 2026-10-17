"use client";

import { MediaActionButton } from "@/components/media/MediaActionButton";
import { downloadLocalMedia, downloadUploadedMedia } from "@/lib/localMediaDownload";

/**
 * The one "Download" control on every upload, queue and progress row.
 *
 * `file` is the copy this browser already holds (the attached `File`, a recorded clip, an outbox
 * entry's IndexedDB blob) and wins whenever it is present, because it works mid-upload and offline.
 * `url` is the server copy, used once the upload is done and the local copy has gone. With neither,
 * nothing is drawn.
 *
 * Always `type="button"` — every caller sits inside a record `<form>`, where a bare button submits —
 * and it stops propagation so a click on a tile never also opens the lightbox.
 */
export function DownloadMediaButton({
  file,
  url,
  name,
  mimeType,
  className = ""
}: {
  file?: Blob | null;
  url?: string | null;
  name: string;
  mimeType?: string | null;
  className?: string;
}) {
  if (!file && !url) return null;
  return (
    <MediaActionButton
      kind="download"
      label={`Download ${name}`}
      className={className}
      onClick={() => {
        if (file) downloadLocalMedia(file, name);
        else if (url) void downloadUploadedMedia(url, name, mimeType);
      }}
    />
  );
}

/**
 * One row per file a batch is sending, each with its "Download" control at the trailing end — the
 * same place the control sits on a capture tile. Renders nothing for an empty list.
 *
 * Every file is listed: a list that silently stopped would leave somebody unable to find the one
 * file they wanted to keep. Long batches scroll inside the box instead.
 */
export function LocalFileDownloads({ files, className = "" }: { files: readonly File[] | undefined; className?: string }) {
  if (!files?.length) return null;
  return (
    <ul className={`grid max-h-56 gap-1.5 overflow-y-auto ${className}`}>
      {files.map((file, index) => (
        <li key={`${file.name}-${file.size}-${file.lastModified}-${index}`} className="flex min-w-0 items-center justify-between gap-2 text-xs">
          <span className="min-w-0 truncate text-ink-700" title={file.name}>
            {file.name || "Untitled file"}
          </span>
          <DownloadMediaButton file={file} name={file.name} mimeType={file.type} />
        </li>
      ))}
    </ul>
  );
}
