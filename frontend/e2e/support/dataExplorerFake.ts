import type { BrowserContext, Route } from "@playwright/test";

type Json = Record<string, unknown>;

export type Call = { method: string; path: string; query: URLSearchParams; body: Json | null; at: number };

type FakeFile = {
  path: string;
  name: string;
  mediaId?: string;
  mediaType?: string;
  content?: string;
  bytes?: number;
  transcript?: boolean;
  broken?: boolean;
};

type FakeFolder = { name: string; path: string; recordType?: string; folders: string[]; files: FakeFile[] };

export const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "base64"
);

const PDF = Buffer.from("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n");

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
  reportBusy = 0;
  treeFailures = new Map<string, number>();
  folders = new Map<string, FakeFolder>();

  constructor() {
    this.folder({ name: "Dataset root", path: "", folders: ["workshops", "crafts"], files: [] });
    this.folder({ name: "By workshop", path: "workshops", recordType: "category", folders: ["workshops/bagru", "workshops/sanganer", "workshops/big"], files: [] });
    this.folder({ name: "By craft", path: "crafts", recordType: "category", folders: [], files: [] });
    this.folder({
      name: "Bagru 2026",
      path: "workshops/bagru",
      recordType: "workshop",
      folders: ["workshops/bagru/kamla"],
      files: [
        { path: "workshops/bagru/details.txt", name: "details.txt", content: "Workshop: Bagru 2026\nCraft: Hand block printing\n" },
        { path: "workshops/bagru/photo.jpg", name: "photo.jpg", mediaId: "img1", mediaType: "IMAGE", bytes: 40_000 },
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
    if (file.content != null) return { kind: "file", name: file.name, path: file.path, content: file.content };
    return {
      kind: "file",
      name: file.name,
      path: file.path,
      mediaId: file.mediaId,
      mediaType: file.mediaType,
      sizeBytes: file.bytes,
      url: `/e2e-files/original-${file.mediaId}`,
      transcriptAvailable: Boolean(file.transcript),
      transcriptPath: file.transcript ? `/data/media/${file.mediaId}/transcript` : undefined,
      thumbnailPath: file.mediaType === "IMAGE" ? `/data/media/${file.mediaId}/thumbnail` : undefined,
      previewPath: file.mediaType && file.mediaType !== "OTHER" ? `/data/media/${file.mediaId}/preview` : undefined,
      downloadPath: `/data/media/${file.mediaId}/download`
    };
  }

  private tree(path: string, cursor: string | null): Json | null {
    const folder = this.folders.get(path);
    if (!folder) return null;
    const crumbs: Json[] = [];
    const parts = path.split("/").filter(Boolean);
    for (let i = 1; i <= parts.length; i += 1) {
      const at = parts.slice(0, i).join("/");
      crumbs.push({ name: this.folders.get(at)?.name ?? at, path: at });
    }
    const all = [
      ...folder.folders.map((child) => ({ kind: "folder", name: this.folders.get(child)!.name, path: child, recordType: this.folders.get(child)!.recordType })),
      ...folder.files.map((file) => this.entry(file))
    ];
    const pageSize = path === "workshops/sanganer" ? 2 : 500;
    const start = cursor ? Number(cursor) : 0;
    const entries = all.slice(start, start + pageSize);
    const next = start + pageSize < all.length ? String(start + pageSize) : null;
    return {
      path,
      crumbs,
      entries,
      nextCursor: next,
      total: all.length,
      truncated: false,
      info: path === "workshops/bagru" ? { title: "Bagru 2026", fields: [{ label: "Craft", value: "Hand block printing" }, { label: "District", value: "Jaipur" }] } : null,
      taxonomies: [
        { id: "workshops", name: "By workshop", path: "workshops", description: "Each workshop and its records.", default: true },
        { id: "crafts", name: "By craft", path: "crafts", description: "Each craft and its records.", default: false }
      ],
      taxonomy: path.startsWith("crafts") ? "crafts" : "workshops"
    };
  }

  private report(query: URLSearchParams): { status: number; json?: unknown; raw?: Buffer; contentType?: string; headers?: Record<string, string> } {
    if (this.reportBusy > 0) {
      this.reportBusy -= 1;
      return { status: 503, json: { detail: "Another report is being built." }, headers: { "retry-after": "60" } };
    }
    const format = query.get("format");
    if (format === "csv") return { status: 200, raw: Buffer.from("﻿Name,Place\nKamla Devi,Bagru\n"), contentType: "text/csv", headers: { "content-disposition": 'attachment; filename="Artisans.csv"' } };
    if (format === "xlsx") return { status: 200, raw: Buffer.from("PK"), contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" };
    const artisans = Array.from({ length: 200 }, (_, index) => [`Artisan ${index + 1}`, index % 2 ? "Bagru" : "Sanganer"]);
    artisans.push(["Rare Weaver", "Kaithoon"]);
    const q = (query.get("q") ?? "").trim().toLowerCase();
    const sheet = { name: "Artisans", color: "FF58068C", columns: ["Name", "Place"], rows: artisans.slice(0, 200), preview: true, totalRows: artisans.length };
    if (q) {
      const matched = artisans.filter((row) => row.join(" ").toLowerCase().includes(q));
      return { status: 200, json: { sheets: [{ ...sheet, rows: matched.slice(0, 200), matchedRows: matched.length }], query: query.get("q") } };
    }
    return { status: 200, json: { sheets: [sheet, { name: "Products", columns: ["Product"], rows: [["Indigo saree"]], totalRows: 1 }], previewRows: 200 } };
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
      const tree = this.tree(query.get("path") ?? "", query.get("cursor"));
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
      if (leaf === "thumbnail") return { status: 200, raw: PNG, contentType: "image/png", headers: { "cache-control": "private, max-age=1800" } };
      if (id === "pdf1") return { status: 200, raw: PDF, contentType: "application/pdf" };
      if (id.startsWith("img")) return { status: 200, raw: PNG, contentType: "image/png" };
      return { status: 200, raw: bytesFor(id, 2048), contentType: "application/octet-stream", headers: { "content-disposition": `attachment; filename="${id}.bin"` } };
    }
    if (path === "/data/browse") {
      const type = query.get("type") ?? "artisan";
      const page = Number(query.get("page") ?? "1");
      const pageSize = Number(query.get("pageSize") ?? "50");
      const q = (query.get("q") ?? "").toLowerCase();
      const all = Array.from({ length: 120 }, (_, index) => ({ id: `${type}-${index + 1}`, name: `${type === "tool" ? "Tool" : "Artisan"} ${index + 1}`, createdAt: "2026-10-01T10:00:00Z", mediaCount: index % 4 })).filter((row) => !q || row.name.toLowerCase().includes(q));
      const items = all.slice((page - 1) * pageSize, page * pageSize);
      const pages = Math.max(1, Math.ceil(all.length / pageSize));
      return { status: 200, json: { items, total: all.length, page, pageSize, pages, truncated: all.length > page * pageSize } };
    }
    if (path === "/data/transcripts/languages") return { status: 200, json: { languages: [{ code: "hi", label: "Hindi", count: 3 }, { code: "raj", label: "Rajasthani", count: 1 }] } };
    if (path === "/data/transcripts") return { status: 200, json: { items: [], nextCursor: null } };
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
      (url) => url.pathname.startsWith("/e2e-files/"),
      async (route: Route) => {
        const id = new URL(route.request().url()).pathname.replace("/e2e-files/", "");
        if (this.fileDelayMs) await new Promise((resolve) => setTimeout(resolve, this.fileDelayMs));
        this.servedFiles.push(id);
        const file = [...this.folders.values()].flatMap((folder) => folder.files).find((candidate) => candidate.mediaId === id);
        if (!file || file.broken) return route.fulfill({ status: 404, body: "gone" });
        if (id === "pdf1") return route.fulfill({ status: 200, contentType: "application/pdf", body: PDF });
        return route.fulfill({ status: 200, contentType: "application/octet-stream", body: bytesFor(id, file.bytes ?? 1000) });
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
        this.calls.push({ method: request.method(), path, query: url.searchParams, body, at: Date.now() });
        const out = this.answer(request.method(), path, url.searchParams, body);
        if (!out) return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "Not Found" }) });
        if (out.raw) return route.fulfill({ status: out.status, contentType: out.contentType, headers: out.headers, body: out.raw });
        return route.fulfill({ status: out.status, contentType: "application/json", headers: { date: new Date().toUTCString(), ...(out.headers ?? {}) }, body: JSON.stringify(out.json ?? null) });
      }
    );
  }
}

export function expectedBytes(id: string, size: number): Buffer {
  return bytesFor(id, size);
}
