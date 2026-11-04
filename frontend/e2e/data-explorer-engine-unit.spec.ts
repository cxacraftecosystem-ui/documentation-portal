import { expect, test } from "@playwright/test";

import { includeKeyOf, type ManifestFile } from "@/lib/dataExplorer/contract";
import { sourcesOf, writeFolder, writeZip, type JobProgress } from "@/lib/dataExplorer/engine";
import {
  announceStep,
  CONVERSION_CONCURRENCY,
  FAILED_LIST_NAME,
  FETCH_CONCURRENCY,
  formatBytes,
  overLimit,
  rateOf,
  secondsLeft,
  summarise,
  summaryLine,
  timeLeftLabel,
  uniquePaths,
  zipLimit
} from "@/lib/dataExplorer/plan";
import { resolveSource, runJob } from "@/lib/dataExplorer/runJob";
import { selectionLabel, selectionSource, toggled, withAll } from "@/lib/dataExplorer/selection";
import { folderSink, memorySink, portSink, SizeLimitReached, type DirectoryHandleLike, type FileHandleLike, type WritableLike } from "@/lib/dataExplorer/sinks";
import { forgetTables, recallTables, rememberTables, TABLE_MEMORY_ENTRIES } from "@/lib/dataExplorer/tableMemory";
import { ancestorsOf, readUrlState, writeUrlState } from "@/lib/dataExplorer/urlState";
import { centralRecord, crc32, endRecords, ZIP32_LIMIT, ZipWriter } from "@/lib/dataExplorer/zip";
import type { FromWorker } from "@/lib/dataExplorer/protocol";
import type { ReportResponse, TreeEntry } from "@/lib/dataExplorer/contract";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

type Entry = { name: string; data: Uint8Array; crc: number };

function readZip(bytes: Uint8Array): Entry[] {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let end = bytes.length - 22;
  while (end >= 0 && view.getUint32(end, true) !== 0x06054b50) end -= 1;
  expect(end, "an end of central directory record").toBeGreaterThanOrEqual(0);
  const count = view.getUint16(end + 10, true);
  let at = view.getUint32(end + 16, true);
  const out: Entry[] = [];
  for (let i = 0; i < count; i += 1) {
    expect(view.getUint32(at, true)).toBe(0x02014b50);
    const crc = view.getUint32(at + 16, true);
    const size = view.getUint32(at + 24, true);
    const nameLength = view.getUint16(at + 28, true);
    const extraLength = view.getUint16(at + 30, true);
    const offset = view.getUint32(at + 42, true);
    const name = decoder.decode(bytes.subarray(at + 46, at + 46 + nameLength));
    const localName = view.getUint16(offset + 26, true);
    const localExtra = view.getUint16(offset + 28, true);
    const start = offset + 30 + localName + localExtra;
    const data = bytes.slice(start, start + size);
    expect(crc32(data), `${name} crc`).toBe(crc);
    out.push({ name, data, crc });
    at += 46 + nameLength + extraLength;
  }
  return out;
}

function chunks(data: Uint8Array, size = 64 * 1024): Uint8Array[] {
  const parts: Uint8Array[] = [];
  for (let at = 0; at < data.length; at += size) parts.push(data.subarray(at, at + size));
  return parts;
}

function bodyOf(data: Uint8Array, size?: number): ReadableStream<Uint8Array> {
  const parts = chunks(data, size);
  return new ReadableStream({
    pull(controller) {
      const next = parts.shift();
      if (next) controller.enqueue(next.slice());
      else controller.close();
    }
  });
}

function bytesOf(length: number, seed = 1): Uint8Array {
  const out = new Uint8Array(length);
  for (let i = 0; i < length; i += 1) out[i] = (i * 31 + seed) & 0xff;
  return out;
}

type Served = { calls: string[]; ranges: string[]; peak: number; peakConversions: number };

function server(files: Record<string, Uint8Array>, options: { fail?: Record<string, number>; headers?: Record<string, Record<string, string>>; dropAfter?: Record<string, number> } = {}) {
  const served: Served = { calls: [], ranges: [], peak: 0, peakConversions: 0 };
  let active = 0;
  let conversions = 0;
  const failures = { ...(options.fail ?? {}) };
  const drops = { ...(options.dropAfter ?? {}) };
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    served.calls.push(url);
    const range = new Headers(init?.headers).get("Range");
    if (range) served.ranges.push(`${url} ${range}`);
    const converting = url.includes("format=mp4");
    active += 1;
    if (converting) conversions += 1;
    served.peak = Math.max(served.peak, active);
    served.peakConversions = Math.max(served.peakConversions, conversions);
    await new Promise((resolve) => setTimeout(resolve, 2));
    active -= 1;
    if (converting) conversions -= 1;
    if (failures[url]) {
      failures[url] -= 1;
      return new Response("busy", { status: 500 });
    }
    const data = files[url];
    if (!data) return new Response("missing", { status: 404 });
    const headers = new Headers(options.headers?.[url] ?? {});
    if (range) {
      const from = Number(/bytes=(\d+)-/.exec(range)?.[1] ?? 0);
      return new Response(bodyOf(data.subarray(from)), { status: 206, headers });
    }
    if (drops[url] !== undefined) {
      const cut = drops[url];
      delete drops[url];
      const parts = chunks(data.subarray(0, cut));
      const broken = new ReadableStream<Uint8Array>({
        pull(controller) {
          const next = parts.shift();
          if (next) controller.enqueue(next.slice());
          else controller.error(new TypeError("network dropped"));
        }
      });
      return new Response(broken, { status: 200, headers });
    }
    return new Response(bodyOf(data), { status: 200, headers });
  }) as typeof fetch;
  return { fetcher, served };
}

function collectSink() {
  const parts: Uint8Array[] = [];
  let closed = false;
  return {
    sink: {
      write: async (chunk: Uint8Array) => {
        parts.push(chunk.slice());
      },
      close: async () => {
        closed = true;
      },
      abort: async () => {
        parts.length = 0;
      }
    },
    bytes: () => {
      const total = parts.reduce((sum, part) => sum + part.length, 0);
      const out = new Uint8Array(total);
      let at = 0;
      for (const part of parts) {
        out.set(part, at);
        at += part.length;
      }
      return out;
    },
    closed: () => closed
  };
}

const noSleep = async () => undefined;

function hooks(fetcher: typeof fetch, signal = new AbortController().signal) {
  const seen: JobProgress[] = [];
  return { seen, hooks: { progress: (p: JobProgress) => seen.push(p), signal, fetcher, sleep: noSleep } };
}

test.describe("@qf:X10 the streaming zip", () => {
  test("a zip of text and fetched files reads back byte for byte", async () => {
    const files: Record<string, Uint8Array> = {};
    const manifest: ManifestFile[] = [{ path: "Artisan/details.txt", content: "Name: Kamla Devi\n" }];
    for (let i = 0; i < 12; i += 1) {
      const url = `https://store.example/f${i}`;
      files[url] = bytesOf(1000 + i * 777, i);
      manifest.push({ path: `Artisan/photo-${i}.jpg`, url, mediaType: "IMAGE", sizeBytes: files[url].length });
    }
    const { fetcher, served } = server(files);
    const out = collectSink();
    const { hooks: h, seen } = hooks(fetcher);
    const outcome = await writeZip(manifest, out.sink, h);
    const entries = readZip(out.bytes());
    expect(entries.map((entry) => entry.name)).toEqual(manifest.map((file) => file.path));
    expect(decoder.decode(entries[0].data)).toBe("Name: Kamla Devi\n");
    for (let i = 0; i < 12; i += 1) expect(entries[i + 1].data).toEqual(files[`https://store.example/f${i}`]);
    expect(outcome.failed).toEqual([]);
    expect(out.closed()).toBe(true);
    expect(served.calls.length, "one request per file, no more").toBe(12);
    const last = seen[seen.length - 1];
    expect(last.bytesDone).toBe(last.bytesTotal);
    expect(served.peak).toBeLessThanOrEqual(FETCH_CONCURRENCY);
  });

  test("zip64 records are written once an offset passes 4 GB", () => {
    const name = encoder.encode("big.mp4");
    const wide = centralRecord({ name, crc: 1, size: 5_000_000_000, offset: 10, time: 0, date: 0 });
    const view = new DataView(wide.buffer);
    expect(view.getUint32(20, true)).toBe(ZIP32_LIMIT);
    expect(view.getUint16(46 + name.length, true)).toBe(0x0001);
    expect(view.getBigUint64(46 + name.length + 4, true)).toBe(BigInt(5_000_000_000));
    const tail = endRecords(3, 200, 6_000_000_000);
    expect(new DataView(tail.buffer).getUint32(0, true)).toBe(0x06064b50);
    expect(tail.length).toBe(56 + 20 + 22);
    const narrow = endRecords(3, 200, 1000);
    expect(narrow.length).toBe(22);
  });

  test("an archive whose bytes pass 4 GB closes with zip64 records", async () => {
    const parts: Uint8Array[] = [];
    const zip = new ZipWriter(async (chunk) => {
      parts.push(chunk);
    });
    (zip as unknown as { offset: number }).offset = ZIP32_LIMIT + 5;
    await zip.addBytes("late.txt", "after four gigabytes");
    await zip.finish();
    const tail = parts[parts.length - 1];
    const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
    expect(view.getUint32(0, true)).toBe(0x06064b50);
  });

  test("a file that fails three times is left out and listed", async () => {
    const files = { "https://s/a": bytesOf(5000), "https://s/b": bytesOf(7000, 3) };
    const { fetcher } = server(files, { fail: { "https://s/b": 3 } });
    const out = collectSink();
    const outcome = await writeZip(
      [
        { path: "a.jpg", url: "https://s/a", sizeBytes: 5000 },
        { path: "b.mp4", url: "https://s/b", sizeBytes: 7000 }
      ],
      out.sink,
      hooks(fetcher).hooks
    );
    const names = readZip(out.bytes()).map((entry) => entry.name);
    expect(names).toEqual(["a.jpg", FAILED_LIST_NAME]);
    expect(outcome.failed.map((entry) => entry.path)).toEqual(["b.mp4"]);
  });

  test("a large file that drops resumes with a Range request and lands whole", async () => {
    const big = bytesOf(20 * 1024 * 1024, 7);
    const url = "https://s/video";
    const { fetcher, served } = server({ [url]: big }, { dropAfter: { [url]: 5 * 1024 * 1024 } });
    const out = collectSink();
    const outcome = await writeZip([{ path: "v.mp4", url, sizeBytes: big.length }], out.sink, hooks(fetcher).hooks);
    expect(outcome.failed).toEqual([]);
    expect(served.ranges).toEqual([`${url} bytes=${5 * 1024 * 1024}-`]);
    expect(readZip(out.bytes())[0].data).toEqual(big);
  });

  test("a large file that cannot resume is left out of the archive, not half in it", async () => {
    const big = bytesOf(18 * 1024 * 1024, 9);
    const url = "https://s/broken";
    const fetcher = (async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (new Headers(init?.headers).get("Range")) return new Response("no ranges", { status: 200 });
      const parts = chunks(big.subarray(0, 1024 * 1024));
      return new Response(
        new ReadableStream<Uint8Array>({
          pull(controller) {
            const next = parts.shift();
            if (next) controller.enqueue(next.slice());
            else controller.error(new TypeError("dropped"));
          }
        })
      );
    }) as typeof fetch;
    const out = collectSink();
    const outcome = await writeZip([{ path: "x.mp4", url, sizeBytes: big.length }, { path: "ok.txt", content: "fine" }], out.sink, hooks(fetcher).hooks);
    expect(outcome.failed.map((entry) => entry.path)).toEqual(["x.mp4"]);
    expect(readZip(out.bytes()).map((entry) => entry.name)).toEqual(["ok.txt", FAILED_LIST_NAME]);
  });

  test("a large file streams into the archive without being held whole", async () => {
    const big = bytesOf(40 * 1024 * 1024, 2);
    let produced = 0;
    const parts = chunks(big, 1024 * 1024);
    const fetcher = (async () =>
      new Response(
        new ReadableStream<Uint8Array>({
          pull(controller) {
            const next = parts.shift();
            if (next) {
              produced += 1;
              controller.enqueue(next.slice());
            } else controller.close();
          }
        })
      )) as typeof fetch;
    let producedAtFirstData = -1;
    let written = 0;
    const sink = {
      write: async (chunk: Uint8Array) => {
        written += chunk.length;
        if (producedAtFirstData < 0 && written > 1024) producedAtFirstData = produced;
      },
      close: async () => undefined,
      abort: async () => undefined
    };
    await writeZip([{ path: "big.mp4", url: "https://s/big", sizeBytes: big.length }], sink, hooks(fetcher).hooks);
    expect(producedAtFirstData, "the archive receives bytes after a few chunks, not after all forty").toBeLessThan(5);
  });

  test("small files are fetched four at a time and audio conversion one at a time", async () => {
    const files: Record<string, Uint8Array> = {};
    const manifest: ManifestFile[] = [];
    for (let i = 0; i < 20; i += 1) {
      const url = `https://s/p${i}`;
      files[url] = bytesOf(2000, i);
      manifest.push({ path: `p${i}.jpg`, url, sizeBytes: 2000 });
    }
    for (let i = 0; i < 4; i += 1) {
      const converted = `/api/data/media/m${i}/download?format=mp4&fallback=original`;
      files[converted] = bytesOf(3000, i);
      manifest.push({ path: `a${i}.m4a`, mediaId: `m${i}`, convertToMp4: true, url: `https://s/o${i}`, sizeBytes: 3000 });
    }
    const { fetcher, served } = server(files);
    const out = collectSink();
    await writeZip(manifest, out.sink, hooks(fetcher).hooks);
    expect(FETCH_CONCURRENCY).toBe(4);
    expect(CONVERSION_CONCURRENCY).toBe(1);
    expect(served.peak).toBeLessThanOrEqual(FETCH_CONCURRENCY);
    expect(served.peakConversions).toBeLessThanOrEqual(CONVERSION_CONCURRENCY);
    expect(readZip(out.bytes()).filter((entry) => entry.name.endsWith(".mp4"))).toHaveLength(4);
  });

  test("@qf:X11 a conversion the server answers with the original is saved under its real name", async () => {
    const original = bytesOf(4000, 5);
    const converted = "/api/data/media/m1/download?format=mp4&fallback=original";
    const { fetcher } = server({ [converted]: original }, { headers: { [converted]: { "X-Conversion": "original" } } });
    const out = collectSink();
    await writeZip([{ path: "Voice/note.mp4", originalPath: "Voice/note.m4a", mediaId: "m1", convertToMp4: true }], out.sink, hooks(fetcher).hooks);
    expect(readZip(out.bytes()).map((entry) => entry.name)).toEqual(["Voice/note.m4a"]);
    expect(sourcesOf({ path: "x.ogg", mediaId: "m2", convertToMp4: true })[0].url).toContain("fallback=original");
  });

  test("@qf:X11 a conversion that redirects to the stored original keeps the original's name", async () => {
    const original = bytesOf(3000, 9);
    const converted = "/api/data/media/m1/download?format=mp4&fallback=original";
    const { fetcher: plain } = server({ [converted]: original });
    const redirecting = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await plain(input, init);
      Object.defineProperty(response, "redirected", { value: true });
      return response;
    }) as typeof fetch;
    const out = collectSink();
    await writeZip([{ path: "Voice/note.mp4", originalPath: "Voice/note.webm", mediaId: "m1", convertToMp4: true }], out.sink, hooks(redirecting).hooks);
    const [entry] = readZip(out.bytes());
    expect(entry.name).toBe("Voice/note.webm");
    expect(entry.data).toEqual(original);
    const sources = sourcesOf({ path: "x.mp4", originalPath: "x.wav", mediaId: "m2", convertToMp4: true });
    expect(sources[1].url).toContain("format=original");
    expect(sources[1].path).toBe("x.wav");
  });

  test("cancel stops the walk and aborts the sink", async () => {
    const controller = new AbortController();
    const files: Record<string, Uint8Array> = {};
    const manifest: ManifestFile[] = [];
    for (let i = 0; i < 30; i += 1) {
      files[`https://s/c${i}`] = bytesOf(1000);
      manifest.push({ path: `c${i}.jpg`, url: `https://s/c${i}`, sizeBytes: 1000 });
    }
    const { fetcher } = server(files);
    let aborted = false;
    const sink = {
      write: async () => undefined,
      close: async () => undefined,
      abort: async () => {
        aborted = true;
      }
    };
    const progress = (p: JobProgress) => {
      if (p.filesDone === 5) controller.abort();
    };
    await expect(writeZip(manifest, sink, { progress, signal: controller.signal, fetcher, sleep: noSleep })).rejects.toThrow();
    expect(aborted).toBe(true);
  });
});

class MemoryFile implements FileHandleLike {
  kind = "file" as const;
  data = new Uint8Array();
  constructor(readonly name: string) {}
  async getFile(): Promise<File> {
    return new File([this.data as BlobPart], this.name);
  }
  async createWritable(): Promise<WritableLike> {
    const parts: Uint8Array[] = [];
    return {
      write: async (chunk) => {
        parts.push(typeof chunk === "string" ? encoder.encode(chunk) : chunk instanceof Blob ? new Uint8Array(await chunk.arrayBuffer()) : chunk.slice());
      },
      close: async () => {
        const total = parts.reduce((sum, part) => sum + part.length, 0);
        const out = new Uint8Array(total);
        let at = 0;
        for (const part of parts) {
          out.set(part, at);
          at += part.length;
        }
        this.data = out;
      },
      abort: async () => undefined
    };
  }
}

class MemoryDir implements DirectoryHandleLike {
  kind = "directory" as const;
  dirs = new Map<string, MemoryDir>();
  files = new Map<string, MemoryFile>();
  constructor(readonly name: string) {}
  async getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<DirectoryHandleLike> {
    let dir = this.dirs.get(name);
    if (!dir) {
      if (!options?.create) throw new DOMException("missing", "NotFoundError");
      dir = new MemoryDir(name);
      this.dirs.set(name, dir);
    }
    return dir;
  }
  async getFileHandle(name: string, options?: { create?: boolean }): Promise<FileHandleLike> {
    let file = this.files.get(name);
    if (!file) {
      if (!options?.create) throw new DOMException("missing", "NotFoundError");
      file = new MemoryFile(name);
      this.files.set(name, file);
    }
    return file;
  }
  async removeEntry(name: string): Promise<void> {
    this.files.delete(name);
  }
}

test.describe("@qf:X10 save to a folder", () => {
  test("a second run into the same folder picks up where the first stopped", async () => {
    const files: Record<string, Uint8Array> = {};
    const manifest: ManifestFile[] = [];
    for (let i = 0; i < 12; i += 1) {
      files[`https://s/r${i}`] = bytesOf(1500, i);
      manifest.push({ path: `Workshop/r${i}.jpg`, url: `https://s/r${i}`, sizeBytes: 1500 });
    }
    const root = new MemoryDir("Downloads");
    const first = new AbortController();
    const one = server(files);
    const stopAt = (p: JobProgress) => {
      if (p.filesDone >= 6) first.abort();
    };
    await expect(writeFolder(manifest, await folderSink(root), { progress: stopAt, signal: first.signal, fetcher: one.fetcher, sleep: noSleep })).rejects.toThrow();
    const two = server(files);
    const outcome = await writeFolder(manifest, await folderSink(root), hooks(two.fetcher).hooks);
    expect(outcome.skipped).toBeGreaterThanOrEqual(6);
    expect(two.served.calls.length).toBeLessThanOrEqual(12 - 6);
    const folder = root.dirs.get("Workshop")!;
    for (let i = 0; i < 12; i += 1) expect(folder.files.get(`r${i}.jpg`)!.data).toEqual(files[`https://s/r${i}`]);
    expect(root.files.has(".download-progress.json"), "the resume note is removed once complete").toBe(false);
  });
});

test.describe("@qf:X10 sinks", () => {
  test("the in-browser zip refuses past its limit", async () => {
    const sink = memorySink(10);
    await sink.write(new Uint8Array(8));
    await expect(sink.write(new Uint8Array(8))).rejects.toBeInstanceOf(SizeLimitReached);
  });

  test("the stream hand-off sends a chunk only for each pull", async () => {
    const sent: unknown[] = [];
    let listener: ((event: MessageEvent) => void) | null = null;
    const port = {
      postMessage: (message: unknown) => sent.push(message),
      addEventListener: (_type: "message", handler: (event: MessageEvent) => void) => {
        listener = handler;
      }
    };
    const sink = portSink(port);
    const pending = sink.write(new Uint8Array([1, 2, 3]));
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(sent).toEqual([]);
    listener!({ data: { type: "pull" } } as MessageEvent);
    await pending;
    expect((sent[0] as { type: string }).type).toBe("chunk");
    await sink.close();
    expect(sent[sent.length - 1]).toEqual({ type: "end" });
  });
});

test.describe("@qf:X2 sizes and progress", () => {
  test("a summary counts files and bytes per type", () => {
    const summary = summarise([
      { path: "a.jpg", mediaType: "IMAGE", sizeBytes: 1024 },
      { path: "b.mp4", mediaType: "VIDEO", sizeBytes: "3145728" },
      { path: "c.wav", mediaType: "AUDIO", sizeBytes: null },
      { path: "d.txt", content: "hello" }
    ]);
    expect(summary.files).toBe(4);
    expect(summary.byType.videos.bytes).toBe(3145728);
    expect(summary.unknown).toBe(1);
    expect(summaryLine(summary)).toBe(`4 files · ${formatBytes(1024 + 3145728 + 5)}+`);
    expect(formatBytes(7.2 * 1024 ** 3)).toBe("7.2 GB");
  });

  test("time left is measured from bytes, and the screen reader hears every tenth", () => {
    expect(rateOf([{ at: 0, bytes: 0 }, { at: 2000, bytes: 2_000_000 }])).toBe(1_000_000);
    expect(timeLeftLabel(secondsLeft(0, 120_000_000, 1_000_000))).toBe("About 2 min left");
    expect(timeLeftLabel(30)).toBe("Less than a minute left");
    expect(announceStep(9, 0)).toBe(false);
    expect(announceStep(10, 9)).toBe(true);
  });

  test("Firefox and Safari save zips only up to a limit", () => {
    expect(zipLimit({ saveFile: true, saveFolder: true, browser: "chromium" })).toBeNull();
    expect(overLimit(3 * 1024 ** 3, { saveFile: false, saveFolder: false, browser: "firefox" })).toBe(true);
    expect(overLimit(100, { saveFile: false, saveFolder: false, browser: "safari" })).toBe(false);
  });

  test("duplicate names inside one zip get _2, _3 in order", () => {
    const named = uniquePaths([{ path: "a/x.jpg" }, { path: "a/x.jpg" }, { path: "a/X.jpg" }]).map((file) => file.path);
    expect(named).toEqual(["a/x.jpg", "a/x_2.jpg", "a/X_3.jpg"]);
  });

  test("a transcript file is counted as a transcript", () => {
    expect(includeKeyOf({ path: "t.md", content: "x", mediaId: "m" })).toBe("transcripts");
    expect(includeKeyOf({ path: "x", typeToken: "videos" })).toBe("videos");
  });
});

function treeEntry(kind: "folder" | "file", path: string, mediaId?: string): TreeEntry {
  return { kind, path, name: path.split("/").pop() ?? path, mediaId };
}

test.describe("@qf:X3 multi-select", () => {
  test("folders and files toggle into one selection that downloads together", async () => {
    let selection = toggled(new Map(), treeEntry("folder", "Artisans/Kamla"));
    selection = toggled(selection, treeEntry("folder", "Artisans/Ramesh"));
    selection = withAll(selection, [treeEntry("file", "Artisans/a.jpg", "m1"), treeEntry("file", "Artisans/b.jpg", "m2")], true);
    expect(selectionLabel(selection)).toBe("2 folders · 2 files");
    const source = selectionSource(selection, ["images"], { status: "APPROVED" });
    expect(source.kind).toBe("manifests");
    if (source.kind !== "manifests") return;
    expect(source.requests[0]).toMatchObject({ method: "POST", endpoint: "/data/manifest", body: { paths: ["Artisans/Kamla", "Artisans/Ramesh"], mediaIds: ["m1", "m2"], include: ["images"] } });
    expect(source.fallback?.requests).toHaveLength(2);
  });

  test("an older server without the selection route falls back to one manifest per folder", async () => {
    const calls: string[] = [];
    const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push(`${init?.method ?? "GET"} ${url}`);
      if (init?.method === "POST") return new Response(JSON.stringify({ detail: "Method Not Allowed" }), { status: 405, headers: { "content-type": "application/json" } });
      const path = new URL(url, "http://x").searchParams.get("path");
      return new Response(JSON.stringify({ files: [{ path: "details.txt", content: path }], totalFiles: 1, totalMedia: 0 }), { headers: { "content-type": "application/json" } });
    }) as typeof fetch;
    let selection = toggled(new Map(), treeEntry("folder", "A/Kamla"));
    selection = toggled(selection, treeEntry("folder", "A/Ramesh"));
    const resolved = await resolveSource(selectionSource(selection, null, {}), null, new AbortController().signal, fetcher);
    expect(resolved.files.map((file) => file.path)).toEqual(["Kamla/details.txt", "Ramesh/details.txt"]);
    expect(calls.filter((call) => call.startsWith("GET"))).toHaveLength(2);
  });
});

test.describe("@qf:X10 the worker job", () => {
  test("a job lists the manifest once, fetches each file once, and reports done", async () => {
    const files: Record<string, Uint8Array> = { "https://s/1": bytesOf(100), "https://s/2": bytesOf(200) };
    const calls: string[] = [];
    const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push(url);
      if (url.startsWith("/api/data/manifest")) {
        const lines = [
          { path: "1.jpg", url: "https://s/1", sizeBytes: 100, mediaType: "IMAGE" },
          { path: "2.jpg", url: "https://s/2", sizeBytes: 200, mediaType: "IMAGE" }
        ]
          .map((line) => JSON.stringify(line))
          .join("\n");
        return new Response(lines, { headers: { "content-type": "application/x-ndjson", "x-dataset-total": "2" } });
      }
      return server(files).fetcher(input, init);
    }) as typeof fetch;
    const posted: FromWorker[] = [];
    let clock = 0;
    await runJob(
      { type: "start", id: "j", source: { kind: "manifests", requests: [{ method: "GET", endpoint: "/data/manifest", params: { path: "A" } }], extra: [] }, include: null, target: { kind: "memory", limit: null } },
      new AbortController().signal,
      (message) => posted.push(message),
      () => (clock += 1000),
      fetcher
    );
    expect(calls.filter((url) => url.startsWith("/api/data/manifest"))).toHaveLength(1);
    expect(calls.filter((url) => url.startsWith("https://s/"))).toHaveLength(2);
    expect(posted[0].type).toBe("manifest");
    const done = posted.find((message) => message.type === "done");
    expect(done && done.type === "done" && done.outcome.filesSaved).toBe(2);
    expect(done && done.type === "done" && done.blob instanceof Blob).toBe(true);
  });
});

test.describe("@qf:X1 url state and table memory", () => {
  test("the folder, view, sheet, search and page round-trip through the URL", () => {
    const search = writeUrlState({ path: "Workshops/Bagru", view: "tables", sheet: "Artisans", q: "kamla", browse: "tool", browsePage: 3, browseQ: "block" }, new URLSearchParams("status=PENDING"));
    const back = readUrlState(new URLSearchParams(search));
    expect(back).toEqual({ path: "Workshops/Bagru", view: "tables", sheet: "Artisans", q: "kamla", browse: "tool", browsePage: 3, browseQ: "block" });
    expect(new URLSearchParams(search).get("status")).toBe("PENDING");
    expect(writeUrlState(readUrlState(new URLSearchParams()))).toBe("");
    expect(ancestorsOf("a/b/c")).toEqual(["", "a", "a/b", "a/b/c"]);
  });

  test("tables are remembered per folder, status and viewer, and the memory is bounded", () => {
    forgetTables();
    const report: ReportResponse = { sheets: [{ name: "Artisans", columns: ["Name"], rows: [["Kamla"]] }] };
    rememberTables({ path: "a", status: "APPROVED", viewer: "u1" }, report, 1000);
    expect(recallTables({ path: "a", status: "APPROVED", viewer: "u1" }, 2000)).toEqual(report);
    expect(recallTables({ path: "a", status: "ALL", viewer: "u1" }, 2000)).toBeNull();
    expect(recallTables({ path: "a", status: "APPROVED", viewer: "u2" }, 2000)).toBeNull();
    for (let i = 0; i < TABLE_MEMORY_ENTRIES + 5; i += 1) rememberTables({ path: `p${i}`, status: "", viewer: "u1" }, report, 3000);
    expect(recallTables({ path: "p0", status: "", viewer: "u1" }, 3000)).toBeNull();
    expect(recallTables({ path: `p${TABLE_MEMORY_ENTRIES + 4}`, status: "", viewer: "u1" }, 3000)).toEqual(report);
    forgetTables();
  });
});
