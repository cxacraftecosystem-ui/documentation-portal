import { CONVERSION_HEADER, expectedBytes, mediaPath, type ManifestFile } from "@/lib/dataExplorer/contract";
import {
  CONVERSION_CONCURRENCY,
  FAILED_LIST_NAME,
  FETCH_CONCURRENCY,
  FILE_ATTEMPTS,
  failedListText,
  mp4Path,
  retryDelay,
  SMALL_FILE_BYTES,
  type FailedFile
} from "@/lib/dataExplorer/plan";
import { isAbort, type ByteSink, type FolderSink } from "@/lib/dataExplorer/sinks";
import { apiUrl } from "@/lib/dataExplorer/wire";
import { ZipWriter, type ZipEntryWriter } from "@/lib/dataExplorer/zip";

export type JobProgress = {
  bytesDone: number;
  bytesTotal: number;
  filesDone: number;
  filesTotal: number;
  current: string | null;
  failed: FailedFile[];
  skipped: number;
};

export type JobOutcome = {
  filesSaved: number;
  filesTotal: number;
  bytesWritten: number;
  failed: FailedFile[];
  skipped: number;
};

export type EngineHooks = {
  progress(progress: JobProgress): void;
  signal: AbortSignal;
  fetcher?: typeof fetch;
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
};

const encoder = new TextEncoder();

export class Semaphore {
  private active = 0;
  private readonly waiting: Array<() => void> = [];
  constructor(private readonly size: number) {}
  get running(): number {
    return this.active;
  }
  async acquire(): Promise<() => void> {
    if (this.active >= this.size) await new Promise<void>((resolve) => this.waiting.push(resolve));
    this.active += 1;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.active -= 1;
      this.waiting.shift()?.();
    };
  }
}

function abortError(): DOMException {
  return new DOMException("Cancelled", "AbortError");
}

export function waitFor(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(abortError());
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", stop);
      resolve();
    }, ms);
    const stop = () => {
      clearTimeout(timer);
      reject(abortError());
    };
    signal.addEventListener("abort", stop, { once: true });
  });
}

class FileFailed extends Error {
  constructor(message: string, readonly partial = false) {
    super(message);
  }
}

type Source = { url: string; path: string; fallbackPath: string | null; converted: boolean };

function originalUrl(file: ManifestFile): string | null {
  if (file.url) return file.url;
  return file.mediaId ? apiUrl(mediaPath(file.mediaId, "download"), { format: "original" }) : null;
}

export function sourcesOf(file: ManifestFile): Source[] {
  const original = originalUrl(file);
  const originalName = file.originalPath ?? file.path;
  if (file.convertToMp4 && file.mediaId) {
    const converted = file.convertedUrl ?? apiUrl(mediaPath(file.mediaId, "download"), { format: "mp4", fallback: "original" });
    const sources: Source[] = [{ url: converted, path: mp4Path(file.path), fallbackPath: originalName, converted: true }];
    if (original) sources.push({ url: original, path: originalName, fallbackPath: null, converted: false });
    return sources;
  }
  return original ? [{ url: original, path: file.path, fallbackPath: null, converted: false }] : [];
}

function pathFor(source: Source, response: Response): string {
  if (!source.converted || !source.fallbackPath) return source.path;
  const said = (response.headers.get(CONVERSION_HEADER) ?? "").toLowerCase();
  return said === "original" || (!said && response.redirected) ? source.fallbackPath : source.path;
}

type Streamed = { path: string; bytes: number };

type OpenEntry = { path: string; write(chunk: Uint8Array): Promise<void>; written(): number };

type ChunkTarget = {
  open(path: string): Promise<OpenEntry>;
};

async function streamWithResume(
  source: Source,
  target: ChunkTarget,
  hooks: EngineHooks,
  onBytes: (count: number) => void
): Promise<Streamed> {
  const fetcher = hooks.fetcher ?? fetch;
  const sleep = hooks.sleep ?? waitFor;
  let entry: Awaited<ReturnType<ChunkTarget["open"]>> | null = null;
  let lastError = "The file couldn't be downloaded.";
  for (let attempt = 1; attempt <= FILE_ATTEMPTS; attempt += 1) {
    if (hooks.signal.aborted) throw abortError();
    const from = entry?.written() ?? 0;
    try {
      const headers: Record<string, string> = from > 0 ? { Range: `bytes=${from}-` } : {};
      const response = await fetcher(source.url, { signal: hooks.signal, headers, credentials: "same-origin" });
      if (from > 0 && response.status !== 206) {
        response.body?.cancel().catch(() => undefined);
        throw new FileFailed("Stopped part-way and couldn't resume.", true);
      }
      if (!response.ok && response.status !== 206) {
        lastError = response.status === 404 ? "The file is no longer stored." : `The server answered ${response.status}.`;
        if (response.status >= 400 && response.status < 500 && response.status !== 408 && response.status !== 429) {
          if (!entry) throw new FileFailed(lastError);
        }
        throw new Error(lastError);
      }
      if (!entry) entry = await target.open(pathFor(source, response));
      if (!response.body) {
        const data = new Uint8Array(await response.arrayBuffer());
        await entry.write(data);
        onBytes(data.length);
      } else {
        const reader = response.body.getReader();
        try {
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            if (value && value.length) {
              await entry.write(value);
              onBytes(value.length);
            }
          }
        } finally {
          reader.releaseLock();
        }
      }
      return { path: entry.path, bytes: entry.written() };
    } catch (error) {
      if (isAbort(error) || hooks.signal.aborted) throw abortError();
      if (error instanceof FileFailed) throw error;
      lastError = error instanceof Error && error.message && !/fetch|network/i.test(error.message) ? error.message : "The connection dropped.";
      if (attempt < FILE_ATTEMPTS) await sleep(retryDelay(attempt), hooks.signal);
    }
  }
  throw new FileFailed(lastError, Boolean(entry && entry.written() > 0));
}

type Prefetched = { kind: "bytes"; path: string; data: Uint8Array } | { kind: "stream" } | { kind: "failed"; reason: string };

async function prefetchSmall(
  file: ManifestFile,
  hooks: EngineHooks,
  conversions: Semaphore
): Promise<Prefetched> {
  const sources = sourcesOf(file);
  if (sources.length === 0) return { kind: "failed", reason: "This file isn't available to download." };
  let reason = "The file couldn't be downloaded.";
  for (const source of sources) {
    const release = source.converted ? await conversions.acquire() : null;
    try {
      const chunks: Uint8Array[] = [];
      let size = 0;
      const result = await streamWithResume(
        source,
        {
          async open(path) {
            chunks.length = 0;
            size = 0;
            return {
              path,
              write: async (chunk: Uint8Array) => {
                chunks.push(chunk);
                size += chunk.length;
              },
              written: () => size
            };
          }
        },
        hooks,
        () => undefined
      );
      const data = new Uint8Array(size);
      let at = 0;
      for (const chunk of chunks) {
        data.set(chunk, at);
        at += chunk.length;
      }
      return { kind: "bytes", path: result.path, data };
    } catch (error) {
      if (isAbort(error)) throw error;
      reason = error instanceof Error ? error.message : reason;
    } finally {
      release?.();
    }
  }
  return { kind: "failed", reason };
}

function isSmall(file: ManifestFile): boolean {
  if (file.content != null) return true;
  const size = expectedBytes(file);
  return size !== null && size <= SMALL_FILE_BYTES;
}

export function totalBytes(files: ManifestFile[]): number {
  return files.reduce((sum, file) => sum + (expectedBytes(file) ?? 0), 0);
}

export async function writeZip(files: ManifestFile[], sink: ByteSink, hooks: EngineHooks): Promise<JobOutcome> {
  const zip = new ZipWriter(sink.write.bind(sink));
  const conversions = new Semaphore(CONVERSION_CONCURRENCY);
  const failed: FailedFile[] = [];
  const progress: JobProgress = {
    bytesDone: 0,
    bytesTotal: totalBytes(files),
    filesDone: 0,
    filesTotal: files.length,
    current: null,
    failed,
    skipped: 0
  };
  let saved = 0;
  const report = () => hooks.progress({ ...progress, failed: [...failed] });
  const pending = new Map<number, Promise<Prefetched>>();
  let nextPrefetch = 0;
  const fill = (from: number) => {
    while (nextPrefetch < files.length && nextPrefetch < from + FETCH_CONCURRENCY) {
      const index = nextPrefetch;
      const file = files[index];
      nextPrefetch += 1;
      if (file.content != null || !isSmall(file)) continue;
      const job = prefetchSmall(file, hooks, conversions);
      job.catch(() => undefined);
      pending.set(index, job);
    }
  };
  const credit = (file: ManifestFile, actual: number) => {
    const expected = expectedBytes(file);
    if (expected === null || expected !== actual) {
      progress.bytesTotal += actual - (expected ?? 0);
    }
  };
  try {
    for (let index = 0; index < files.length; index += 1) {
      if (hooks.signal.aborted) throw abortError();
      fill(index);
      const file = files[index];
      progress.current = file.path;
      report();
      if (file.content != null) {
        const data = encoder.encode(file.content);
        await zip.addBytes(file.path, data);
        progress.bytesDone += data.length;
        credit(file, data.length);
        saved += 1;
      } else if (pending.has(index)) {
        const ready = await pending.get(index)!;
        pending.delete(index);
        if (ready.kind === "bytes") {
          await zip.addBytes(ready.path, ready.data);
          progress.bytesDone += ready.data.length;
          credit(file, ready.data.length);
          saved += 1;
        } else if (ready.kind === "failed") {
          failed.push({ path: file.path, reason: ready.reason });
          progress.bytesTotal -= expectedBytes(file) ?? 0;
        }
      } else {
        const outcome = await streamLarge(file, zip, hooks, conversions, (count) => {
          progress.bytesDone += count;
          report();
        });
        if (outcome.ok) {
          credit(file, outcome.bytes);
          saved += 1;
        } else {
          failed.push({ path: file.path, reason: outcome.reason });
          progress.bytesTotal += outcome.bytes - (expectedBytes(file) ?? 0);
        }
      }
      progress.filesDone += 1;
      report();
    }
    if (failed.length) await zip.addBytes(FAILED_LIST_NAME, failedListText(failed));
    await zip.finish();
    await sink.close();
    progress.current = null;
    report();
    return { filesSaved: saved, filesTotal: files.length, bytesWritten: zip.bytesWritten, failed, skipped: 0 };
  } catch (error) {
    await sink.abort();
    throw error;
  }
}

async function streamLarge(
  file: ManifestFile,
  zip: ZipWriter,
  hooks: EngineHooks,
  conversions: Semaphore,
  onBytes: (count: number) => void
): Promise<{ ok: true; bytes: number } | { ok: false; reason: string; bytes: number }> {
  const sources = sourcesOf(file);
  if (sources.length === 0) return { ok: false, reason: "This file isn't available to download.", bytes: 0 };
  let reason = "The file couldn't be downloaded.";
  let wasted = 0;
  for (const source of sources) {
    const release = source.converted ? await conversions.acquire() : null;
    let entry: ZipEntryWriter | null = null;
    try {
      const result = await streamWithResume(
        source,
        {
          async open(path) {
            const current = await zip.begin(path);
            entry = current;
            return { path, write: (chunk) => current.write(chunk), written: () => current.written };
          }
        },
        hooks,
        onBytes
      );
      const done: ZipEntryWriter | null = entry;
      if (done) await (done as ZipEntryWriter).close();
      return { ok: true, bytes: result.bytes + wasted };
    } catch (error) {
      const open: ZipEntryWriter | null = entry;
      if (open) {
        wasted += (open as ZipEntryWriter).written;
        await (open as ZipEntryWriter).discard();
      }
      if (isAbort(error)) throw error;
      reason = error instanceof Error ? error.message : reason;
    } finally {
      release?.();
    }
  }
  return { ok: false, reason, bytes: wasted };
}

export async function writeFolder(files: ManifestFile[], folder: FolderSink, hooks: EngineHooks): Promise<JobOutcome> {
  const failed: FailedFile[] = [];
  const conversions = new Semaphore(CONVERSION_CONCURRENCY);
  const lanes = new Semaphore(FETCH_CONCURRENCY);
  const progress: JobProgress = {
    bytesDone: 0,
    bytesTotal: totalBytes(files),
    filesDone: 0,
    filesTotal: files.length,
    current: null,
    failed,
    skipped: 0
  };
  let saved = 0;
  const report = () => hooks.progress({ ...progress, failed: [...failed] });
  const one = async (file: ManifestFile) => {
    const release = await lanes.acquire();
    try {
      if (hooks.signal.aborted) throw abortError();
      const already = folder.done[file.path];
      if (typeof already === "number") {
        progress.skipped += 1;
        progress.bytesDone += already;
        progress.bytesTotal += already - (expectedBytes(file) ?? 0);
        saved += 1;
        return;
      }
      progress.current = file.path;
      report();
      if (file.content != null) {
        await folder.writeText(file.path, file.content);
        const size = encoder.encode(file.content).length;
        progress.bytesDone += size;
        await folder.markDone(file.path, size);
        saved += 1;
        return;
      }
      const sources = sourcesOf(file);
      let reason = sources.length ? "The file couldn't be downloaded." : "This file isn't available to download.";
      for (const source of sources) {
        const gate = source.converted ? await conversions.acquire() : null;
        let sink: ByteSink | null = null;
        let written = 0;
        try {
          const result = await streamWithResume(
            source,
            {
              async open(path) {
                sink = await folder.open(path);
                const current = sink;
                return {
                  path,
                  write: async (chunk: Uint8Array) => {
                    await current.write(chunk);
                    written += chunk.length;
                  },
                  written: () => written
                };
              }
            },
            hooks,
            (count) => {
              progress.bytesDone += count;
              report();
            }
          );
          if (sink) await (sink as ByteSink).close();
          progress.bytesTotal += result.bytes - (expectedBytes(file) ?? 0);
          await folder.markDone(file.path, result.bytes);
          saved += 1;
          return;
        } catch (error) {
          if (sink) await (sink as ByteSink).abort();
          progress.bytesDone -= written;
          if (isAbort(error)) throw error;
          reason = error instanceof Error ? error.message : reason;
        } finally {
          gate?.();
        }
      }
      failed.push({ path: file.path, reason });
      progress.bytesTotal -= expectedBytes(file) ?? 0;
    } finally {
      progress.filesDone += 1;
      report();
      release();
    }
  };
  await Promise.all(files.map((file) => one(file)));
  if (hooks.signal.aborted) throw abortError();
  if (failed.length) await folder.writeText(FAILED_LIST_NAME, failedListText(failed));
  else await folder.finish();
  progress.current = null;
  report();
  return { filesSaved: saved, filesTotal: files.length, bytesWritten: progress.bytesDone, failed, skipped: progress.skipped };
}
