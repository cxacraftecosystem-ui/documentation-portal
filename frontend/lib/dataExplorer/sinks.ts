export type WritableLike = {
  write(data: Uint8Array | Blob | string): Promise<void>;
  close(): Promise<void>;
  abort?(reason?: unknown): Promise<void>;
};

export type FileHandleLike = {
  kind: "file";
  name: string;
  getFile(): Promise<File>;
  createWritable(options?: { keepExistingData?: boolean }): Promise<WritableLike>;
};

export type DirectoryHandleLike = {
  kind: "directory";
  name: string;
  getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<DirectoryHandleLike>;
  getFileHandle(name: string, options?: { create?: boolean }): Promise<FileHandleLike>;
  removeEntry?(name: string): Promise<void>;
};

export type ByteSink = {
  write(chunk: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
};

export class SizeLimitReached extends Error {
  constructor(readonly limit: number) {
    super("The zip is larger than this browser can save.");
    this.name = "SizeLimitReached";
  }
}

export function isAbort(error: unknown): boolean {
  return (error as { name?: string } | null)?.name === "AbortError";
}

function writableSink(writable: WritableLike): ByteSink {
  return {
    write: (chunk) => writable.write(chunk),
    close: () => writable.close(),
    abort: async () => {
      try {
        await writable.abort?.();
      } catch {
        return;
      }
    }
  };
}

export async function fileSink(handle: FileHandleLike): Promise<ByteSink> {
  return writableSink(await handle.createWritable({ keepExistingData: false }));
}

const CONSOLIDATE_BYTES = 32 * 1024 * 1024;

export type MemorySink = ByteSink & { bytes(): number; blob(): Blob | null };

export function memorySink(limit: number | null): MemorySink {
  const parts: Blob[] = [];
  let pending: Uint8Array[] = [];
  let pendingBytes = 0;
  let total = 0;
  let result: Blob | null = null;
  const flush = () => {
    if (pending.length === 0) return;
    parts.push(new Blob(pending as BlobPart[]));
    pending = [];
    pendingBytes = 0;
  };
  return {
    bytes: () => total,
    blob: () => result,
    async write(chunk) {
      if (limit !== null && total + chunk.length > limit) throw new SizeLimitReached(limit);
      pending.push(chunk.slice());
      pendingBytes += chunk.length;
      total += chunk.length;
      if (pendingBytes >= CONSOLIDATE_BYTES) flush();
    },
    async close() {
      flush();
      result = new Blob(parts, { type: "application/zip" });
      parts.length = 0;
    },
    async abort() {
      parts.length = 0;
      pending = [];
      result = null;
    }
  };
}

export const RESUME_STATE_NAME = ".download-progress.json";

export type ResumeState = { version: 1; done: Record<string, number> };

export type FolderSink = {
  name: string;
  done: Record<string, number>;
  open(path: string): Promise<ByteSink>;
  markDone(path: string, size: number): Promise<void>;
  writeText(path: string, text: string): Promise<void>;
  finish(): Promise<void>;
};

export function pathSegments(path: string): string[] {
  return path
    .replace(/\\/g, "/")
    .split("/")
    .map((part) => part.trim())
    .filter((part) => part && part !== "." && part !== "..");
}

async function directoryFor(root: DirectoryHandleLike, parts: string[]): Promise<DirectoryHandleLike> {
  let dir = root;
  for (const part of parts) dir = await dir.getDirectoryHandle(part, { create: true });
  return dir;
}

export async function readResumeState(root: DirectoryHandleLike): Promise<ResumeState> {
  try {
    const handle = await root.getFileHandle(RESUME_STATE_NAME);
    const parsed = JSON.parse(await (await handle.getFile()).text()) as Partial<ResumeState>;
    if (parsed && parsed.version === 1 && parsed.done && typeof parsed.done === "object") return { version: 1, done: parsed.done };
  } catch {
    return { version: 1, done: {} };
  }
  return { version: 1, done: {} };
}

export async function folderSink(root: DirectoryHandleLike): Promise<FolderSink> {
  const state = await readResumeState(root);
  let saving: Promise<void> = Promise.resolve();
  const persist = () => {
    saving = saving
      .then(async () => {
        const handle = await root.getFileHandle(RESUME_STATE_NAME, { create: true });
        const writable = await handle.createWritable();
        await writable.write(JSON.stringify(state));
        await writable.close();
      })
      .catch(() => undefined);
    return saving;
  };
  const open = async (path: string) => {
    const parts = pathSegments(path);
    const leaf = parts.pop() ?? "file";
    const dir = await directoryFor(root, parts);
    return fileSink(await dir.getFileHandle(leaf, { create: true }));
  };
  return {
    name: root.name,
    done: state.done,
    open,
    async markDone(path, size) {
      state.done[path] = size;
      await persist();
    },
    async writeText(path, text) {
      const sink = await open(path);
      await sink.write(new TextEncoder().encode(text));
      await sink.close();
    },
    async finish() {
      await saving;
      try {
        await root.removeEntry?.(RESUME_STATE_NAME);
      } catch {
        return;
      }
    }
  };
}

export type PortLike = {
  postMessage(message: unknown, transfer?: Transferable[]): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  start?(): void;
  close?(): void;
};

export function portSink(port: PortLike): ByteSink {
  let credits = 0;
  let cancelled = false;
  let wake: (() => void) | null = null;
  port.addEventListener("message", (event: MessageEvent) => {
    const data = event.data as { type?: string } | null;
    if (data?.type === "pull") credits += 1;
    if (data?.type === "cancel") cancelled = true;
    const resume = wake;
    wake = null;
    resume?.();
  });
  port.start?.();
  return {
    async write(chunk) {
      while (credits === 0 && !cancelled) await new Promise<void>((resolve) => (wake = resolve));
      if (cancelled) throw new DOMException("Cancelled", "AbortError");
      credits -= 1;
      const copy = chunk.slice();
      port.postMessage({ type: "chunk", chunk: copy }, [copy.buffer]);
    },
    async close() {
      port.postMessage({ type: "end" });
      port.close?.();
    },
    async abort() {
      port.postMessage({ type: "abort" });
      port.close?.();
    }
  };
}
