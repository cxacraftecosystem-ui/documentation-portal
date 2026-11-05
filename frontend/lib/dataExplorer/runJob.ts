import { includeKeyOf, type IncludeKey, type ManifestFile } from "@/lib/dataExplorer/contract";
import { writeFolder, writeZip, type JobProgress, type PauseGate } from "@/lib/dataExplorer/engine";
import { prefixed, summarise, uniquePaths } from "@/lib/dataExplorer/plan";
import type { FromWorker, JobSource, ManifestNotice, MeasureMessage, StartMessage } from "@/lib/dataExplorer/protocol";
import { fileSink, folderSink, isAbort, memorySink, portSink, SizeLimitReached } from "@/lib/dataExplorer/sinks";
import { DownloadRefused, fetchManifest, isUnsupported, type ManifestRequest } from "@/lib/dataExplorer/wire";

export const PROGRESS_EVERY_MS = 250;

export type Resolved = { files: ManifestFile[]; notice: ManifestNotice };

async function collect(
  requests: ManifestRequest[],
  signal: AbortSignal,
  fetcher: typeof fetch,
  onCut: (truncated: boolean) => void
): Promise<ManifestFile[]> {
  let files: ManifestFile[] = [];
  for (const request of requests) {
    const manifest = await fetchManifest(request, signal, undefined, fetcher);
    onCut(manifest.truncated);
    const prefix = request.method === "GET" ? request.prefix : undefined;
    files = files.concat(prefix ? prefixed(manifest.files, prefix) : manifest.files);
  }
  return files;
}

export async function resolveSource(
  initial: JobSource,
  include: IncludeKey[] | null,
  signal: AbortSignal,
  fetcher: typeof fetch = fetch
): Promise<Resolved> {
  const source = initial;
  let truncated = false;
  let files: ManifestFile[] = [];
  if (source.kind === "files") files = source.files;
  else {
    let requests = source.requests;
    let extra = source.extra;
    try {
      files = await collect(requests, signal, fetcher, (cut) => (truncated ||= cut));
    } catch (error) {
      if (!(error instanceof DownloadRefused) || !isUnsupported(error.status) || !source.fallback) throw error;
      requests = source.fallback.requests;
      extra = source.fallback.extra;
      files = await collect(requests, signal, fetcher, (cut) => (truncated ||= cut));
    }
    files = files.concat(extra);
  }
  if (include && include.length) {
    const wanted = new Set(include);
    files = files.filter((file) => wanted.has(includeKeyOf(file)));
  }
  files = uniquePaths(files);
  return { files, notice: { files: files.length, summary: summarise(files), truncated } };
}

export async function measureJob(message: MeasureMessage, signal: AbortSignal, post: (message: FromWorker) => void, fetcher: typeof fetch = fetch): Promise<void> {
  try {
    const { notice } = await resolveSource(message.source, message.include, signal, fetcher);
    post({ type: "measured", id: message.id, manifest: notice });
  } catch (error) {
    if (isAbort(error) || signal.aborted) {
      post({ type: "cancelled", id: message.id });
      return;
    }
    post({ type: "error", id: message.id, message: error instanceof Error ? error.message : "The size didn't load.", limit: null });
  }
}

export async function runJob(
  message: StartMessage,
  signal: AbortSignal,
  post: (message: FromWorker) => void,
  clock: () => number = () => Date.now(),
  fetcher: typeof fetch = fetch,
  gate?: PauseGate
): Promise<void> {
  const { id, target } = message;
  let lastPost = 0;
  let latest: JobProgress | null = null;
  const progress = (next: JobProgress) => {
    latest = next;
    const now = clock();
    if (next.filesDone === next.filesTotal || now - lastPost >= PROGRESS_EVERY_MS) {
      lastPost = now;
      post({ type: "progress", id, progress: next });
    }
  };
  const hooks = { progress, signal, fetcher, gate };
  try {
    const { files, notice } = await resolveSource(message.source, message.include, signal, fetcher);
    post({ type: "manifest", id, manifest: notice });
    if (files.length === 0) {
      post({ type: "done", id, outcome: { filesSaved: 0, filesTotal: 0, bytesWritten: 0, failed: [], skipped: 0 }, blob: null, truncated: notice.truncated });
      return;
    }
    const flush = () => {
      if (latest) post({ type: "progress", id, progress: latest });
    };
    if (target.kind === "folder") {
      const outcome = await writeFolder(files, await folderSink(target.handle), hooks);
      flush();
      post({ type: "done", id, outcome, blob: null, truncated: notice.truncated });
      return;
    }
    if (target.kind === "file" || target.kind === "stream") {
      const sink = target.kind === "file" ? await fileSink(target.handle) : portSink(target.port);
      const outcome = await writeZip(files, sink, hooks);
      flush();
      post({ type: "done", id, outcome, blob: null, truncated: notice.truncated });
      return;
    }
    const sink = memorySink(target.limit);
    const outcome = await writeZip(files, sink, hooks);
    flush();
    post({ type: "done", id, outcome, blob: sink.blob(), truncated: notice.truncated });
  } catch (error) {
    if (isAbort(error) || signal.aborted) {
      post({ type: "cancelled", id });
      return;
    }
    if (error instanceof SizeLimitReached) {
      post({ type: "error", id, message: error.message, limit: error.limit });
      return;
    }
    const text = error instanceof DownloadRefused || error instanceof Error ? error.message : "The download stopped.";
    post({ type: "error", id, message: text || "The download stopped.", limit: null });
  }
}
