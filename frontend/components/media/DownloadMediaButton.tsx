"use client";

import { MediaActionButton } from "@/components/media/MediaActionButton";
import { downloadLocalMedia, downloadUploadedMedia } from "@/lib/localMediaDownload";

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
