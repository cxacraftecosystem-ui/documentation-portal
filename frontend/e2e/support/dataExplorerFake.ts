import { deflateSync } from "node:zlib";

import type { BrowserContext, Route } from "@playwright/test";

type Json = Record<string, unknown>;

export type Call = { method: string; path: string; query: URLSearchParams; body: Json | null; at: number; headers: Record<string, string> };

type FakeFile = {
  path: string;
  name: string;
  mediaId?: string;
  mediaType?: string;
  content?: string;
  bytes?: number;
  transcript?: boolean;
  broken?: boolean;
  pixels?: { width: number; height: number };
  capturedAt?: string;
};

const MANY_FILES = 1234;

export const SORTED_FOLDER = "workshops/sorted";

export const LONG_PARENT = "workshops/long";

export const LONG_NAME = `Artisan ${"Gangaram Chhipa of Bagru, ".repeat(8)}`.slice(0, 200);

export const SORTED_FILES = [
  { name: "alpha.jpg", mediaId: "srt1", mediaType: "IMAGE", bytes: 3000, capturedAt: "2026-05-03T10:00:00Z" },
  { name: "Bravo.mp4", mediaId: "srt2", mediaType: "VIDEO", bytes: 9000, capturedAt: "2026-05-01T10:00:00Z" },
  { name: "charlie.m4a", mediaId: "srt3", mediaType: "AUDIO", bytes: 1000, capturedAt: "2026-05-05T10:00:00Z" },
  { name: "delta.jpg", mediaId: "srt4", mediaType: "IMAGE", bytes: 7000, capturedAt: "2026-05-02T10:00:00Z" },
  { name: "echo.pdf", mediaId: "srt5", mediaType: "PDF", bytes: 5000, capturedAt: "2026-05-04T10:00:00Z" }
];

type SortableEntry = { kind?: unknown; name?: unknown; path?: unknown; mediaType?: unknown; recordType?: unknown; sizeBytes?: unknown; bytes?: unknown; capturedAt?: unknown };

function sortValue(entry: SortableEntry, key: string): string | number | null {
  if (key === "name") return String(entry.name).toLowerCase();
  if (key === "recorded") return typeof entry.capturedAt === "string" ? Date.parse(entry.capturedAt) : null;
  if (key === "size") {
    const size = entry.kind === "folder" ? entry.bytes : entry.sizeBytes;
    return typeof size === "number" ? size : null;
  }
  if (key === "type") return String(entry.kind === "folder" ? (entry.recordType ?? "") : (entry.mediaType ?? "TEXT")).toLowerCase() || null;
  return null;
}

export function fakeSorted<T extends SortableEntry>(entries: T[], sort: string | null): T[] {
  const [key, dir] = (sort || "name:asc").split(":");
  const byName = [...entries].sort((a, b) => String(a.name).toLowerCase().localeCompare(String(b.name).toLowerCase()) || String(a.path).localeCompare(String(b.path)));
  const present = byName.filter((entry) => sortValue(entry, key) !== null);
  const missing = byName.filter((entry) => sortValue(entry, key) === null);
  present.sort((a, b) => {
    const left = sortValue(a, key)!;
    const right = sortValue(b, key)!;
    const order = left < right ? -1 : left > right ? 1 : 0;
    return dir === "desc" ? -order : order;
  });
  return [...present, ...missing].sort((a, b) => (a.kind === b.kind ? 0 : a.kind === "folder" ? -1 : 1));
}

type FakeFolder = { name: string; path: string; recordType?: string; folders: string[]; files: FakeFile[] };

export type SearchCorpusGroup = { type: string; label: string; singular: string; total: number };

function corpusItem(group: SearchCorpusGroup, n: number) {
  return { portal: "designer", local: true, type: group.type, id: `${group.type}-${n}`, title: `${group.singular} ${n} of ${group.total}`, subtitle: null, snippet: "", highlights: [], href: `/artisans/${group.type}-${n}`, openUrl: null };
}

export function corpusOrder(group: SearchCorpusGroup, sort: string | null): number[] {
  const all = Array.from({ length: group.total }, (_, index) => index + 1);
  const [key, dir] = (sort ?? "").split(":");
  if (key === "name") {
    const title = (n: number) => `${group.singular} ${n} of ${group.total}`.toLowerCase();
    const named = all.sort((a, b) => (title(a) < title(b) ? -1 : title(a) > title(b) ? 1 : 0));
    return dir === "desc" ? named.reverse() : named;
  }
  if (key === "recorded") return dir === "asc" ? all : all.reverse();
  return all;
}

export const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "base64"
);

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(bytes: Buffer): number {
  let c = 0xffffffff;
  for (const byte of bytes) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, "ascii");
  const tail = Buffer.alloc(4);
  tail.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, tail]);
}

export function solidPng(width: number, height: number): Buffer {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 2;
  const row = Buffer.alloc(1 + width * 3);
  for (let x = 0; x < width; x += 1) row.set([145, 72, 210], 1 + x * 3);
  const raw = Buffer.concat(Array.from({ length: height }, () => row));
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), pngChunk("IHDR", header), pngChunk("IDAT", deflateSync(raw)), pngChunk("IEND", Buffer.alloc(0))]);
}

export const PORTRAIT = { width: 300, height: 400 };

const PORTRAIT_PNG = solidPng(PORTRAIT.width, PORTRAIT.height);

const PDF = Buffer.from("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n");

const ZIP = Buffer.concat([Buffer.from([0x50, 0x4b, 0x05, 0x06]), Buffer.alloc(18)]);

const TURN = (index: number, startMs: number, endMs: number, speakerKey: string, speakerName: string, text: string, english: string | null) => ({
  id: `t${index}`,
  index,
  speakerKey,
  speakerName,
  startMs,
  endMs,
  language: "hi",
  text,
  textSource: "MACHINE",
  lowConfidence: [],
  words: [],
  translation: { text: english, status: english ? "TRANSLATED" : "ALREADY_ENGLISH", source: "MACHINE", model: null },
  revision: 1
});

export const TRANSCRIPT_AUD1 = {
  mediaId: "aud1",
  title: "interview.m4a",
  status: "READY",
  queueReason: null,
  statusMessage: null,
  version: 1,
  languages: [{ code: "hi", label: "Hindi" }, { code: "en", label: "English" }],
  durationMs: 754_000,
  speakerCount: 2,
  badges: [],
  speakers: [
    { speakerKey: "speaker_0", label: "Speaker 1", name: "Interviewer", role: "RESEARCHER", personId: null, suggested: false },
    { speakerKey: "speaker_1", label: "Speaker 2", name: "Sunita", role: "ARTISAN", personId: null, suggested: false }
  ],
  turns: [
    TURN(0, 0, 9_000, "speaker_0", "Interviewer", "Mitti mein kya milate hain?", "What goes into the mud?"),
    TURN(1, 9_000, 40_000, "speaker_1", "Sunita", "Chuna aur gond. Chuna se daraar nahin aati.", "Lime and gum. Lime stops it cracking."),
    TURN(2, 41_000, 50_000, "speaker_0", "Interviewer", "Chuna kahan se laate hain?", "Where do you buy the lime?")
  ],
  summary: [],
  quotes: [],
  cost: null,
  audio: { url: null, durationMs: 754_000 },
  permissions: { edit: false, retry: true, transcribeNow: false, ask: false, autodraft: false, quoteCard: false, mapSpeakers: false },
  readOnly: null,
  revision: 1,
  updatedAt: "2026-09-22T10:00:00Z"
};

export const OVERVIEW = {
  path: "workshops/bagru",
  title: "Bagru 2026",
  subtitle: "Hand block printing",
  totals: { files: 7, bytes: 186_000, fields: { filled: 10, total: 20 }, transcripts: { total: 2, ready: 1, translating: 0, failed: 1 }, review: { approved: 1, pending: 1, total: 2 } },
  sections: [
    { key: "identity", label: "Identity" },
    { key: "tools", label: "Tools" },
    { key: "process", label: "Process" }
  ],
  artisans: [
    {
      id: "a-kamla",
      name: "Kamla Devi",
      craft: "Dabu printing",
      path: "workshops/bagru/kamla",
      fields: { filled: 8, total: 10 },
      media: { files: 1, bytes: 20_000 },
      transcripts: { total: 1, ready: 1, translating: 0, failed: 0 },
      review: { approved: 2, pending: 0, total: 2 },
      sections: [
        { key: "identity", state: "complete", missing: [] },
        { key: "tools", state: "partial", missing: ["Tool names"], href: "/questionnaire/sessions/s-kamla/view?section=tools" },
        { key: "process", state: "complete", missing: [] }
      ]
    },
    {
      id: "a-mohan",
      name: "Mohan Lal",
      craft: "Dabu printing",
      path: "workshops/bagru/kamla",
      fields: { filled: 2, total: 10 },
      media: { files: 0, bytes: 0 },
      transcripts: { total: 0, ready: 0, translating: 0, failed: 0 },
      review: { approved: 0, pending: 1, total: 1 },
      sections: [
        { key: "identity", state: "partial", missing: ["Phone"] },
        { key: "tools", state: "missing", missing: ["Tool names", "Materials"] },
        { key: "process", state: "missing", missing: ["Dye recipe"] }
      ]
    }
  ],
  truncated: false
};

function silentWav(seconds: number): Buffer {
  const rate = 8000;
  const samples = Math.round(rate * seconds);
  const out = Buffer.alloc(44 + samples * 2);
  out.write("RIFF", 0);
  out.writeUInt32LE(36 + samples * 2, 4);
  out.write("WAVE", 8);
  out.write("fmt ", 12);
  out.writeUInt32LE(16, 16);
  out.writeUInt16LE(1, 20);
  out.writeUInt16LE(1, 22);
  out.writeUInt32LE(rate, 24);
  out.writeUInt32LE(rate * 2, 28);
  out.writeUInt16LE(2, 32);
  out.writeUInt16LE(16, 34);
  out.write("data", 36);
  out.writeUInt32LE(samples * 2, 40);
  return out;
}

const WAV = silentWav(40);

export const BIG_FILES = 60;
export const BIG_FILE_BYTES = 256 * 1024;

function bytesFor(name: string, size: number): Buffer {
  const out = Buffer.alloc(size);
  let seed = 0;
  for (const char of name) seed = (seed * 31 + char.charCodeAt(0)) & 0xffff;
  for (let i = 0; i < size; i += 1) out[i] = (i * 7 + seed) & 0xff;
  return out;
}

function typeToken(file: FakeFile): string {
  if (file.content != null) return file.transcript ? "transcripts" : "text";
  switch (file.mediaType) {
    case "IMAGE":
      return "images";
    case "VIDEO":
      return "videos";
    case "AUDIO":
      return "audios";
    case "PDF":
      return "documents";
    default:
      return "other";
  }
}

export class DataExplorerFake {
  calls: Call[] = [];
  servedFiles: string[] = [];
  fileDelayMs = 0;
  fileDelays = new Map<string, number>();
  rangeRequests: { id: string; from: number }[] = [];
  reportBusy = 0;
  xlsxFailures = 0;
  transcriptStatus: Record<string, string> = { aud1: "READY", vid1: "NEEDS_ATTENTION" };
  reportOptions: Json | null = null;
  isolated = false;
  shares: Json[] = [];
  landed: string[] = [];
  schedules: Json[] = [];
  bundles: Record<string, Json> = { b1: { id: "b1", label: "Bagru 2026 · every file", path: "workshops/bagru", createdAt: "2026-11-05T10:00:00Z", fileCount: 6, bytes: 182_000, listedCount: 6, hiddenCount: 0, manifestPath: "/data/bundles/b1/manifest", chunk: 500 } };
  isolateAsked = 0;
  views: Json[] = [];
  sharedViews: Json[] = [{ id: "v-shared", name: "Sanganer tables", path: "workshops/sanganer", state: { view: "tables", sheet: "", q: "", filters: {} }, shared: true, mine: false, ownerName: "Ravi Kumar", createdAt: "2026-11-01T10:00:00Z", updatedAt: "2026-11-01T10:00:00Z" }];
  visit: Json = { previousVisitAt: "2026-11-01T10:00:00Z", newFiles: 3, newTranscripts: 1 };
  treeFailures = new Map<string, number>();
  repeatCursor = new Set<string>();
  sortableFolders = new Set<string>([SORTED_FOLDER]);
  transcriptSorting = false;
  folders = new Map<string, FakeFolder>();
  searchCorpus: SearchCorpusGroup[] = [];
  unifiedSearch = true;

  constructor() {
    this.folder({ name: "Dataset root", path: "", folders: ["workshops", "crafts"], files: [] });
    this.folder({ name: "By workshop", path: "workshops", recordType: "taxonomy", folders: ["workshops/bagru", "workshops/sanganer", "workshops/big"], files: [] });
    this.folder({ name: "By craft", path: "crafts", recordType: "taxonomy", folders: [], files: [] });
    this.folder({
      name: "Bagru 2026",
      path: "workshops/bagru",
      recordType: "workshop",
      folders: ["workshops/bagru/kamla"],
      files: [
        { path: "workshops/bagru/details.txt", name: "details.txt", content: "Workshop: Bagru 2026\nCraft: Hand block printing\n" },
        { path: "workshops/bagru/photo.jpg", name: "photo.jpg", mediaId: "img1", mediaType: "IMAGE", bytes: 40_000, pixels: PORTRAIT },
        { path: "workshops/bagru/loom.mp4", name: "loom.mp4", mediaId: "vid1", mediaType: "VIDEO", bytes: 90_000 },
        { path: "workshops/bagru/interview.m4a", name: "interview.m4a", mediaId: "aud1", mediaType: "AUDIO", bytes: 30_000, transcript: true },
        { path: "workshops/bagru/brief.pdf", name: "brief.pdf", mediaId: "pdf1", mediaType: "PDF", bytes: PDF.length },
        { path: "workshops/bagru/missing.jpg", name: "missing.jpg", mediaId: "gone1", mediaType: "IMAGE", bytes: 1000, broken: true }
      ]
    });
    this.folder({
      name: "Kamla Devi",
      path: "workshops/bagru/kamla",
      recordType: "artisan",
      folders: [],
      files: [{ path: "workshops/bagru/kamla/portrait.jpg", name: "portrait.jpg", mediaId: "img2", mediaType: "IMAGE", bytes: 20_000 }]
    });
    this.folder({
      name: "Sanganer",
      path: "workshops/sanganer",
      recordType: "workshop",
      folders: [],
      files: ["a", "b", "c"].map((letter) => ({ path: `workshops/sanganer/${letter}.jpg`, name: `${letter}.jpg`, mediaId: `s${letter}`, mediaType: "IMAGE", bytes: 5000 }))
    });
    this.folder({ name: "Long names", path: LONG_PARENT, recordType: "workshop", folders: [`${LONG_PARENT}/one`, `${LONG_PARENT}/two`], files: [] });
    this.folder({ name: LONG_NAME, path: `${LONG_PARENT}/one`, recordType: "artisan", folders: [], files: [] });
    this.folder({ name: "Short", path: `${LONG_PARENT}/two`, recordType: "artisan", folders: [], files: [] });
    this.folder({
      name: "Sorting check",
      path: SORTED_FOLDER,
      recordType: "workshop",
      folders: [],
      files: SORTED_FILES.map((file) => ({ ...file, path: `${SORTED_FOLDER}/${file.name}` }))
    });
    this.folder({
      name: "Many photos",
      path: "workshops/many",
      recordType: "workshop",
      folders: [],
      files: Array.from({ length: MANY_FILES }, (_, index) => ({
        path: `workshops/many/photo-${String(index + 1).padStart(4, "0")}.jpg`,
        name: `photo-${String(index + 1).padStart(4, "0")}.jpg`,
        mediaId: `many${index + 1}`,
        mediaType: "IMAGE",
        bytes: 1000
      }))
    });
    this.folder({
      name: "Big archive",
      path: "workshops/big",
      recordType: "workshop",
      folders: [],
      files: Array.from({ length: BIG_FILES }, (_, index) => ({
        path: `workshops/big/clip-${String(index).padStart(2, "0")}.mp4`,
        name: `clip-${String(index).padStart(2, "0")}.mp4`,
        mediaId: `big${index}`,
        mediaType: "VIDEO",
        bytes: BIG_FILE_BYTES
      }))
    });
  }

  private folder(folder: FakeFolder) {
    this.folders.set(folder.path, folder);
  }

  calledWith(method: string, path: string): Call[] {
    return this.calls.filter((call) => call.method === method && call.path === path);
  }

  private walk(path: string): FakeFile[] {
    const folder = this.folders.get(path);
    if (!folder) return [];
    return [...folder.files, ...folder.folders.flatMap((child) => this.walk(child))];
  }

  private manifestFiles(paths: string[], mediaIds: string[], include: string[] | null): Json[] {
    const out: Json[] = [];
    for (const path of paths) {
      const base = path ? `${path}/` : "";
      const prefix = paths.length > 1 || mediaIds.length ? `${this.folders.get(path)?.name ?? "Folder"}/` : "";
      for (const file of this.walk(path)) out.push(this.manifestLine(file, prefix + file.path.slice(base.length)));
    }
    for (const id of mediaIds) {
      const file = [...this.folders.values()].flatMap((folder) => folder.files).find((candidate) => candidate.mediaId === id);
      if (file) out.push(this.manifestLine(file, file.name));
    }
    return include ? out.filter((line) => include.includes(String(line.typeToken))) : out;
  }

  private manifestLine(file: FakeFile, path: string): Json {
    if (file.content != null) return { path, content: file.content, typeToken: typeToken(file) };
    return {
      path,
      url: `/e2e-files/${file.mediaId}`,
      mediaId: file.mediaId,
      mediaType: file.mediaType,
      sizeBytes: file.bytes ?? 0,
      typeToken: typeToken(file)
    };
  }

  private stats(files: Json[]): Json {
    const byType: Record<string, { files: number; bytes: number }> = {};
    let bytes = 0;
    for (const line of files) {
      const size = typeof line.content === "string" ? Buffer.byteLength(line.content) : Number(line.sizeBytes ?? 0);
      const key = String(line.typeToken);
      byType[key] ??= { files: 0, bytes: 0 };
      byType[key].files += 1;
      byType[key].bytes += size;
      bytes += size;
    }
    return { files: files.length, bytes, unknownSizeFiles: 0, byType, truncated: false };
  }

  private entry(file: FakeFile): Json {
    if (file.content != null) return { kind: "file", name: file.name, path: file.path, content: file.content, capturedAt: null };
    return {
      kind: "file",
      name: file.name,
      path: file.path,
      originalFilename: file.name,
      capturedAt: file.capturedAt ?? (file.mediaId === "img1" ? "2026-09-23T10:00:00Z" : null),
      mediaId: file.mediaId,
      mediaType: file.mediaType,
      sizeBytes: file.bytes ?? null,
      url: `/e2e-files/original-${file.mediaId}`,
      ...(file.pixels ? { pixels: file.pixels } : {}),
      transcriptAvailable: Boolean(file.transcript),
      transcriptPath: file.transcript ? `/data/media/${file.mediaId}/transcript` : undefined,
      transcriptFiles: file.transcript
        ? [{ name: file.name.replace(/\.[^.]+$/, ".vtt"), format: "vtt", track: "source", timestamps: true, url: `/api/transcripts/${file.mediaId}/download?format=vtt` }]
        : undefined,
      thumbnailPath: file.mediaType === "IMAGE" ? `/data/media/${file.mediaId}/thumbnail` : undefined,
      previewPath: file.mediaType && file.mediaType !== "OTHER" ? `/data/media/${file.mediaId}/preview` : undefined,
      downloadPath: `/data/media/${file.mediaId}/download`
    };
  }

  private tree(path: string, cursor: string | null, sort: string | null = null): Json | null {
    const folder = this.folders.get(path);
    if (!folder) return null;
    const crumbs: Json[] = [{ name: "Repository", path: "" }];
    const parts = path.split("/").filter(Boolean);
    for (let i = 1; i <= parts.length; i += 1) {
      const at = parts.slice(0, i).join("/");
      crumbs.push({ name: this.folders.get(at)?.name ?? at, path: at });
    }
    const unsorted = [
      ...folder.folders.map((child) => {
        const inner = this.walk(child).filter((item) => item.content == null);
        return { kind: "folder", name: this.folders.get(child)!.name, path: child, recordType: this.folders.get(child)!.recordType, count: inner.length, bytes: inner.reduce((sum, item) => sum + (item.bytes ?? 0), 0) };
      }),
      ...folder.files.map((file) => this.entry(file))
    ];
    const sortable = this.sortableFolders.has(path);
    const all = sortable ? fakeSorted(unsorted, sort) : unsorted.sort((a, b) => (a.kind === b.kind ? 0 : a.kind === "folder" ? -1 : 1) || String(a.name).toLowerCase().localeCompare(String(b.name).toLowerCase()));
    const [sortKey, sortDir] = (sort || "name:asc").split(":");
    const pageSize = path === "workshops/sanganer" || path === SORTED_FOLDER ? 2 : 500;
    const start = cursor ? Number(cursor) : 0;
    const entries = all.slice(start, start + pageSize);
    const next = cursor && this.repeatCursor.has(path) ? cursor : start + pageSize < all.length ? String(start + pageSize) : null;
    return {
      path,
      crumbs,
      entries,
      nextCursor: next,
      total: all.length,
      truncated: false,
      info: path === "workshops/bagru" ? { title: "Bagru 2026", fields: [{ label: "Craft", value: "Hand block printing" }, { label: "District", value: "Jaipur" }], record: { kind: "workshop", id: "w-bagru" } } : null,
      taxonomies: [
        { id: "workshops", name: "By workshop", path: "workshops", description: "Each workshop and its records.", default: true },
        { id: "crafts", name: "By craft", path: "crafts", description: "Each craft and its records.", default: false }
      ],
      taxonomy: path.startsWith("crafts") ? "crafts" : "workshops",
      designWorkshopsVisible: true,
      designWorkshopsDownloadable: true,
      ...(sortable ? { sort: { key: sortKey, dir: sortDir }, sorts: ["name", "recorded", "added", "modified", "size", "type"] } : {})
    };
  }

  private report(query: URLSearchParams): { status: number; json?: unknown; raw?: Buffer; contentType?: string; headers?: Record<string, string> } {
    if (this.reportBusy > 0) {
      this.reportBusy -= 1;
      return { status: 503, json: { detail: "Another report is being built." }, headers: { "retry-after": "60" } };
    }
    const format = query.get("format");
    if (format === "csv") return { status: 200, raw: Buffer.from("﻿Name,Place\nKamla Devi,Bagru\n"), contentType: "text/csv", headers: { "content-disposition": 'attachment; filename="Artisans.csv"' } };
    if (format === "xlsx") {
      if (this.xlsxFailures > 0) {
        this.xlsxFailures -= 1;
        return { status: 500, json: { detail: "The report couldn't be built." } };
      }
      return { status: 200, raw: Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.alloc(60)]), contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", headers: { "content-disposition": 'attachment; filename="Dataset_Report_Bagru-2026.xlsx"' } };
    }
    const artisans = Array.from({ length: 200 }, (_, index) => [`Artisan ${index + 1}`, index % 2 ? "Bagru" : "Sanganer"]);
    artisans.push(["Rare Weaver", "Kaithoon"]);
    const q = (query.get("q") ?? "").trim().toLowerCase();
    const sheet = { name: "Artisans", color: "FF58068C", columns: ["Name", "Place"], rows: artisans.slice(0, 200), rowNumbers: artisans.slice(0, 200).map((_row, index) => index + 1), preview: true, totalRows: artisans.length };
    if (q) {
      const matched = artisans.map((row, index) => ({ row, number: index + 1 })).filter(({ row }) => row.join(" ").toLowerCase().includes(q));
      return { status: 200, json: { sheets: [{ ...sheet, rows: matched.slice(0, 200).map((hit) => hit.row), rowNumbers: matched.slice(0, 200).map((hit) => hit.number), matchedRows: matched.length }], query: query.get("q") } };
    }
    return { status: 200, json: { sheets: [sheet, { name: "Products", columns: ["Product"], rows: [["Indigo saree"]], rowNumbers: [1], totalRows: 1 }], previewRows: 200 } };
  }

  answer(method: string, path: string, query: URLSearchParams, body: Json | null): { status: number; json?: unknown; raw?: Buffer; contentType?: string; headers?: Record<string, string> } | null {
    if (path === "/me") return { status: 200, json: { id: "u-lead", name: "Nandini Rao", email: "nandini@example.org", role: "ADMIN", isActive: true, mustChangePassword: false, twoFactorEnabled: false, usageConsentAt: "2026-01-01T00:00:00Z" } };
    if (path === "/preferences/me") return { status: 200, json: {} };
    if (path === "/data/tree") {
      const failing = this.treeFailures.get(query.get("path") ?? "") ?? 0;
      if (failing > 0) {
        this.treeFailures.set(query.get("path") ?? "", failing - 1);
        return { status: 500, json: { detail: "The folder could not be read." } };
      }
      const tree = this.tree(query.get("path") ?? "", query.get("cursor"), query.get("sort"));
      return tree ? { status: 200, json: tree } : { status: 404, json: { detail: "No such folder." } };
    }
    if (path === "/data/stats" && method === "GET") {
      const include = query.get("include")?.split(",") ?? null;
      return { status: 200, json: this.stats(this.manifestFiles([query.get("path") ?? ""], [], include)) };
    }
    if (path === "/data/stats" && method === "POST") {
      return { status: 200, json: this.stats(this.manifestFiles((body?.paths as string[]) ?? [], (body?.mediaIds as string[]) ?? [], (body?.include as string[] | null) ?? null)) };
    }
    if (path === "/data/manifest") {
      const files =
        method === "POST"
          ? this.manifestFiles((body?.paths as string[]) ?? [], (body?.mediaIds as string[]) ?? [], (body?.include as string[] | null) ?? null)
          : this.manifestFiles([query.get("path") ?? ""], [], query.get("include")?.split(",") ?? null);
      const lines = files.map((line) => JSON.stringify(line)).join("\n");
      return { status: 200, raw: Buffer.from(lines), contentType: "application/x-ndjson", headers: { "x-dataset-total": String(files.length), "x-dataset-truncated": "false" } };
    }
    if (path === "/export/dataset") {
      const files = this.manifestFiles(["workshops/bagru"], [], null);
      return { status: 200, json: { files, totalFiles: files.length, totalMedia: files.length, truncated: false } };
    }
    if (path === "/data/report") return this.report(query);
    const media = /^\/data\/media\/([^/]+)\/(thumbnail|preview|download|transcript)$/.exec(path);
    if (media) {
      const [, id, leaf] = media;
      if (leaf === "transcript") return id === "aud1" ? { status: 200, json: { mediaId: id, name: "interview.md", content: "**Speaker 1:** Namaste, this is the block.", language: "hi" } } : { status: 404, json: { detail: "Not Found" } };
      if (leaf === "thumbnail") return { status: 200, raw: id === "img1" ? PORTRAIT_PNG : PNG, contentType: "image/png", headers: { "cache-control": "private, max-age=1800" } };
      if (id === "pdf1") return { status: 200, raw: PDF, contentType: "application/pdf" };
      if (id === "aud1" && leaf === "preview") return { status: 200, raw: WAV, contentType: "audio/wav" };
      if (id === "img1" && leaf === "preview") return { status: 200, raw: PORTRAIT_PNG, contentType: "image/png" };
      if (id.startsWith("img")) return { status: 200, raw: PNG, contentType: "image/png" };
      return { status: 200, raw: bytesFor(id, 2048), contentType: "application/octet-stream", headers: { "content-disposition": `attachment; filename="${id}.bin"` } };
    }
    if (path === "/data/browse") {
      const type = query.get("type") ?? "artisan";
      const page = Number(query.get("page") ?? "1");
      const pageSize = Number(query.get("pageSize") ?? "50");
      const q = (query.get("q") ?? "").toLowerCase();
      const all = Array.from({ length: 120 }, (_, index) => ({ id: `${type}-${index + 1}`, name: `${type === "tool" ? "Tool" : "Artisan"} ${index + 1}`, createdAt: "2026-10-01T10:00:00Z", mediaCount: index % 4 })).filter((row) => !q || row.name.toLowerCase().includes(q));
      const pages = Math.max(1, Math.ceil(all.length / pageSize));
      const [browseKey, browseDir] = (query.get("sort") || "added:desc").split(":");
      const ordered = browseKey === "name" ? [...all].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0) * (browseDir === "desc" ? -1 : 1)) : all;
      const shown = ordered.slice((page - 1) * pageSize, page * pageSize);
      return { status: 200, json: { items: shown, total: all.length, page, pageSize, pages, truncated: all.length > page * pageSize, sort: { key: browseKey, dir: browseDir }, sorts: ["name", "recorded", "added", "modified"] } };
    }
    if (path === "/transcripts/aud1" && method === "GET") return { status: 200, json: TRANSCRIPT_AUD1 };
    if (path === "/transcripts/batch") {
      const ids = (query.get("ids") ?? "").split(",").filter(Boolean);
      const items = ids
        .filter((id) => id in this.transcriptStatus)
        .map((id) => ({ mediaId: id, status: this.transcriptStatus[id], languages: [{ code: "hi", label: "Hindi" }, { code: "en", label: "English" }], durationMs: id === "aud1" ? 754_000 : 61_000, speakerCount: id === "aud1" ? 2 : 1 }));
      return { status: 200, json: { items } };
    }
    const retried = /^\/transcripts\/([^/]+)\/retry$/.exec(path);
    if (retried && method === "POST") {
      this.transcriptStatus[retried[1]] = "TRANSLATING";
      return { status: 200, json: { ...TRANSCRIPT_AUD1, mediaId: retried[1], status: "TRANSLATING" } };
    }
    if (/^\/transcripts\/[^/]+\/download$/.test(path) || path === "/data/transcript-bundle" || path === "/data/transcripts/export") {
      return { status: 200, raw: ZIP, contentType: "application/zip", headers: { "content-disposition": 'attachment; filename="Transcripts.zip"' } };
    }
    if (path === "/data/media/aud1/peaks") return { status: 200, json: { peaks: Array.from({ length: 800 }, (_, index) => ((index * 37) % 100) / 100), durationMs: 754_000, isolated: this.isolated, canIsolate: !this.isolated } };
    if (path === "/data/media/aud1/isolate" && method === "POST") {
      this.isolateAsked += 1;
      this.isolated = true;
      return { status: 202, json: { status: "queued", isolated: false } };
    }
    if (path === "/data/report/sheets") {
      return {
        status: 200,
        json: {
          path: query.get("path") ?? "",
          sheets: [
            { key: "artisans", name: "Artisans", plugin: "core", count: query.has("status") ? 0 : 201, files: 3, group: null, description: "Every artisan in this folder.", record: true },
            { key: "products", name: "Products", plugin: "core", count: query.has("status") ? 0 : 1, files: 0, group: null, description: "Products recorded here.", record: true },
            { key: "questionBank", name: "Question bank", plugin: "core", count: 365, files: 0, group: null, description: "Every question.", record: false }
          ],
          records: query.has("status") ? 0 : 202,
          extras: [
            { key: "quotes", title: "Quotes sheet" },
            { key: "glossary", title: "Glossary sheet" }
          ],
          locales: ["en", "hi"]
        }
      };
    }
    if (path === "/data/report/pack") {
      return { status: 200, raw: ZIP, contentType: "application/zip", headers: { "content-disposition": 'attachment; filename="Dataset_ReportPack_Bagru-2026.zip"', "x-report-transcripts": "1" } };
    }
    if (path === "/data/report/shares" && method === "GET") return { status: 200, json: { items: this.shares.filter((share) => share.path === (query.get("path") ?? "")) } };
    if (path === "/data/report/shares" && method === "POST") {
      const id = `sh${this.shares.length + 1}`;
      const share = { id, url: `http://localhost:3000/report-share/token-${id}`, path: body?.path, title: "Bagru 2026", options: body?.options ?? {}, createdAt: "2026-11-05T10:00:00Z", expiresAt: "2026-11-12T10:00:00Z", revokedAt: null, state: "active", views: 0, lastOpenedAt: null };
      this.shares.push(share);
      return { status: 201, json: share };
    }
    const shareMatch = /^\/data\/report\/shares\/([^/]+)(\/views)?$/.exec(path);
    if (shareMatch) {
      const share = this.shares.find((item) => item.id === shareMatch[1]);
      if (!share) return { status: 404, json: { detail: "Not Found" } };
      if (shareMatch[2]) return { status: 200, json: { items: [], total: 0, truncated: false } };
      if (method === "DELETE") {
        share.state = "revoked";
        share.revokedAt = "2026-11-05T11:00:00Z";
        return { status: 204 };
      }
    }
    if (path === "/data/report/schedules" && method === "GET") return { status: 200, json: { items: this.schedules, enabled: true, mailAvailable: true, maxPerOwner: 5, attachMaxBytes: 10_000_000, defaultTimezone: "Asia/Kolkata" } };
    if (path === "/data/report/schedules" && method === "POST") {
      const view = [...this.views, ...this.sharedViews].find((item) => item.id === body?.savedViewId);
      const schedule = { id: `rs${this.schedules.length + 1}`, savedViewId: body?.savedViewId, viewName: view?.name ?? null, path: view?.path ?? null, weekday: body?.weekday ?? 0, hourLocal: body?.hourLocal ?? 8, timezone: body?.timezone ?? "Asia/Kolkata", label: "Mondays at 8 am", options: body?.options ?? {}, paused: false, nextRunAt: "2026-11-09T02:30:00Z", lastSentAt: null, lastError: null, lastFile: null, createdAt: "2026-11-05T10:00:00Z" };
      this.schedules.push(schedule);
      return { status: 201, json: schedule };
    }
    const scheduleMatch = /^\/data\/report\/schedules\/([^/]+)$/.exec(path);
    if (scheduleMatch) {
      const schedule = this.schedules.find((item) => item.id === scheduleMatch[1]);
      if (!schedule) return { status: 404, json: { detail: "Not Found" } };
      if (method === "DELETE") {
        this.schedules = this.schedules.filter((item) => item.id !== scheduleMatch[1]);
        return { status: 204 };
      }
      if (method === "PATCH") {
        Object.assign(schedule, body ?? {});
        return { status: 200, json: schedule };
      }
      return { status: 200, json: schedule };
    }
    const bundleMatch = /^\/data\/bundles\/([^/]+)(\/manifest)?$/.exec(path);
    if (bundleMatch) {
      const bundle = this.bundles[bundleMatch[1]];
      if (!bundle) return { status: 404, json: { detail: "Not Found" } };
      if (!bundleMatch[2]) return { status: 200, json: bundle };
      const files = this.manifestFiles([String(bundle.path)], [], null).filter((line) => line.content == null);
      const lines = files.map((line) => JSON.stringify(line)).join("\n");
      return { status: 200, raw: Buffer.from(lines), contentType: "application/x-ndjson", headers: { "x-dataset-total": String(files.length), "x-dataset-truncated": "false" } };
    }
    if (path === "/data/go/media/img1/download") return { status: 200, json: { url: "/e2e-landing?file=img1", fileName: "photo.jpg", mediaId: "img1" } };
    if (path === "/data/go/open/artisan/a-kamla") return { status: 200, json: { kind: "artisan", id: "a-kamla", href: "/data?path=workshops%2Fbagru%2Fkamla" } };
    if (path === "/data/go/transcript/aud1/docx") return { status: 200, json: { mediaId: "aud1", format: "docx", apiPath: "/transcripts/aud1/download?format=docx&track=both", openHref: "/data?path=workshops%2Fbagru&preview=aud1" } };
    if (path.startsWith("/data/go/")) return { status: 404, json: { detail: "Not Found" } };
    if (path === "/data/overview") return query.get("path") === "workshops/bagru" ? { status: 200, json: OVERVIEW } : { status: 404, json: { detail: "Not a workshop folder." } };
    if (path === "/data/visits" && method === "POST") return { status: 200, json: { path: body?.path, visitedAt: "2026-11-05T10:00:00Z", ...this.visit } };
    if (path === "/data/report-options") {
      if (method === "PUT") {
        this.reportOptions = (body?.options as Json) ?? null;
        return { status: 200, json: { options: this.reportOptions } };
      }
      return { status: 200, json: { options: this.reportOptions } };
    }
    if (path === "/data/views" && method === "GET") return { status: 200, json: { mine: this.views, shared: this.sharedViews } };
    if (path === "/data/views" && method === "POST") {
      const view = { id: `v${this.views.length + 1}`, name: body?.name, path: body?.path, state: body?.state, shared: Boolean(body?.shared), mine: true, ownerName: "Nandini Rao", createdAt: "2026-11-05T10:00:00Z", updatedAt: "2026-11-05T10:00:00Z" };
      this.views.push(view);
      return { status: 201, json: view };
    }
    const viewMatch = /^\/data\/views\/([^/]+)$/.exec(path);
    if (viewMatch) {
      const id = decodeURIComponent(viewMatch[1]);
      const found = [...this.views, ...this.sharedViews].find((view) => view.id === id);
      if (method === "DELETE") {
        this.views = this.views.filter((view) => view.id !== id);
        return { status: 204 };
      }
      if (method === "PATCH" && found) {
        Object.assign(found, body ?? {});
        return { status: 200, json: found };
      }
      return found ? { status: 200, json: found } : { status: 404, json: { detail: "Not Found" } };
    }
    if (path === "/data/transcripts/languages") return { status: 200, json: { languages: [{ code: "hi", label: "Hindi", count: 3 }, { code: "raj", label: "Rajasthani", count: 1 }] } };
    if (path === "/data/transcripts") return { status: 200, json: { items: [], nextCursor: null } };
    if (path === "/data/transcripts/list") {
      const q = (query.get("q") ?? "").toLowerCase();
      const items = [
        { mediaId: "aud1", name: "interview.m4a", path: "interview.m4a", mediaType: "AUDIO", hasText: true, capturedAt: "2026-09-22T10:00:00Z", text: "Namaste, this is the block." },
        { mediaId: "vid1", name: "loom.mp4", path: "loom.mp4", mediaType: "VIDEO", hasText: false, capturedAt: "2026-09-23T10:00:00Z", text: "" }
      ]
        .filter((item) => !q || item.text.toLowerCase().includes(q))
        .map(({ text, ...item }) => (q ? { ...item, snippet: text } : item));
      if (!this.transcriptSorting) return { status: 200, json: { items, total: items.length, truncated: false, query: q || null } };
      const [txKey, txDir] = (query.get("sort") || "folder:asc").split(":");
      const sorted = txKey === "name" || txKey === "recorded" ? fakeSorted(items.map((item) => ({ ...item, kind: "file" })), `${txKey}:${txDir}`).map(({ kind: _kind, ...item }) => item) : items;
      return { status: 200, json: { items: sorted, total: sorted.length, truncated: false, query: q || null, sort: { key: txKey, dir: txDir }, sorts: ["folder", "name", "recorded", "duration", "type"] } };
    }
    if (path === "/search/unified" && this.unifiedSearch && this.searchCorpus.length && query.get("q") === "indgo") {
      return { status: 200, json: { query: "indgo", mode: "text", suggestion: "indigo", suggestionTotal: 38, groups: [], remote: { portal: "fieldRepository", label: "Field Repository", status: "off" }, typesRefused: [], page: 1, limit: 5 } };
    }
    if (path === "/search/unified" && this.unifiedSearch && this.searchCorpus.length) {
      const type = query.get("type");
      const limit = Number(query.get("limit") ?? 5);
      const page = Number(query.get("page") ?? 1);
      const offset = (page - 1) * limit;
      const groups = this.searchCorpus
        .filter((group) => !type || group.type === type)
        .map((group) => ({
          type: group.type,
          label: group.label,
          total: group.total,
          localTotal: group.total,
          remoteTotal: 0,
          items: corpusOrder(group, query.get("sort"))
            .slice(offset, offset + limit)
            .map((n) => corpusItem(group, n))
        }));
      const [sortKey, sortDir] = (query.get("sort") ?? "relevance:desc").split(":");
      return { status: 200, json: { query: query.get("q") ?? "", mode: "text", suggestion: null, groups, remote: { portal: "fieldRepository", label: "Field Repository", status: "off" }, typesRefused: [], page, limit, sort: { key: sortKey, dir: sortDir }, sorts: ["relevance", "name", "recorded"], ...(type ? { type } : {}) } };
    }
    if (path === "/search" && this.searchCorpus.length) {
      const pageSize = Number(query.get("pageSize") ?? 10);
      const page = Number(query.get("page") ?? 1);
      const wanted = (query.get("types") ?? "").split(",").filter(Boolean);
      const out: Record<string, unknown> = { totals: {} as Record<string, number> };
      for (const group of this.searchCorpus) {
        if (wanted.length && !wanted.includes(group.type)) continue;
        (out.totals as Record<string, number>)[group.type] = group.total;
        const from = (page - 1) * pageSize;
        out[group.type] = Array.from({ length: Math.max(0, Math.min(pageSize, group.total - from)) }, (_, index) => {
          const n = from + index + 1;
          return { id: `${group.type}-${n}`, name: `${group.singular} ${n} of ${group.total}`, place: "Bagru" };
        });
      }
      return { status: 200, json: out };
    }
    if (path === "/search") {
      const q = (query.get("q") ?? "").toLowerCase();
      const artisans = q.includes("kamla") ? [{ id: "a-kamla", name: "Kamla Devi", place: "Bagru" }] : q.includes("meera") ? [{ id: "a-meera", name: "Meera Bai", place: "Sanganer" }] : [];
      return { status: 200, json: { artisans, workshops: [], products: [], tools: [], media: [], total: artisans.length } };
    }
    if (path === "/data/locate") {
      const id = query.get("id");
      if (id === "a-kamla") return { status: 200, json: { path: "workshops/bagru/kamla", locatable: true, record: null } };
      if (id === "a-meera") return { status: 200, json: { path: null, locatable: false, record: { kind: "artisan", id } } };
      return { status: 200, json: { path: null, locatable: false, record: null } };
    }
    if (path === "/workshops") return { status: 200, json: { items: [], total: 0, page: 1, pageSize: 100, pages: 0 } };
    return null;
  }

  async install(context: BrowserContext, theme: "light" | "dark" = "light"): Promise<void> {
    await context.addInitScript((chosen) => {
      window.localStorage.setItem("field_repo_token", "e2e-data-explorer");
      window.localStorage.setItem("field_repo_preferences", JSON.stringify({ theme: chosen, reducedMotion: true, largerText: false, highContrast: false }));
      const picker = window as unknown as Record<string, unknown>;
      picker.showSaveFilePicker = async (options: { suggestedName?: string }) => {
        const root = await navigator.storage.getDirectory();
        const name = options?.suggestedName || "download.zip";
        (window as unknown as { __savedAs?: string }).__savedAs = name;
        return root.getFileHandle(name, { create: true });
      };
      picker.showDirectoryPicker = async () => {
        const root = await navigator.storage.getDirectory();
        return root.getDirectoryHandle("saved-folder", { create: true });
      };
    }, theme);
    await context.route(
      (url) => url.pathname === "/e2e-landing",
      async (route: Route) => {
        this.landed.push(new URL(route.request().url()).searchParams.get("file") ?? "");
        return route.fulfill({ status: 200, contentType: "text/html", body: "<!doctype html><title>Signed file</title><p>signed file</p>" });
      }
    );
    await context.route(
      (url) => url.pathname.startsWith("/e2e-files/"),
      async (route: Route) => {
        const id = new URL(route.request().url()).pathname.replace("/e2e-files/", "");
        const delay = this.fileDelays.get(id) ?? this.fileDelayMs;
        if (delay) await new Promise((resolve) => setTimeout(resolve, delay));
        this.servedFiles.push(id);
        const file = [...this.folders.values()].flatMap((folder) => folder.files).find((candidate) => candidate.mediaId === id);
        if (!file || file.broken) return route.fulfill({ status: 404, body: "gone" });
        if (id === "pdf1") return route.fulfill({ status: 200, contentType: "application/pdf", body: PDF });
        const whole = bytesFor(id, file.bytes ?? 1000);
        const range = /^bytes=(\d+)-$/.exec(route.request().headers()["range"] ?? "");
        if (range) {
          const from = Number(range[1]);
          this.rangeRequests.push({ id, from });
          if (from >= whole.length) return route.fulfill({ status: 416, headers: { "content-range": `bytes */${whole.length}` }, body: "" });
          return route.fulfill({
            status: 206,
            contentType: "application/octet-stream",
            headers: { "content-range": `bytes ${from}-${whole.length - 1}/${whole.length}`, "accept-ranges": "bytes" },
            body: whole.subarray(from)
          });
        }
        return route.fulfill({ status: 200, contentType: "application/octet-stream", headers: { "accept-ranges": "bytes" }, body: whole });
      }
    );
    await context.route(
      (url) => url.pathname.startsWith("/api/") && !url.pathname.startsWith("/api/session/"),
      async (route: Route) => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname.replace(/^\/api/, "");
        let body: Json | null = null;
        try {
          body = request.postDataJSON() as Json | null;
        } catch {
          body = null;
        }
        this.calls.push({ method: request.method(), path, query: url.searchParams, body, at: Date.now(), headers: request.headers() });
        const out = this.answer(request.method(), path, url.searchParams, body);
        if (!out) return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "Not Found" }) });
        if (out.status === 204) return route.fulfill({ status: 204 });
        if (out.raw) return route.fulfill({ status: out.status, contentType: out.contentType, headers: out.headers, body: out.raw });
        return route.fulfill({ status: out.status, contentType: "application/json", headers: { date: new Date().toUTCString(), ...(out.headers ?? {}) }, body: JSON.stringify(out.json ?? null) });
      }
    );
  }
}

export function expectedBytes(id: string, size: number): Buffer {
  return bytesFor(id, size);
}
