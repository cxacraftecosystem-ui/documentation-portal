import { deflateSync } from "node:zlib";

import type { BrowserContext, Route } from "@playwright/test";

import { AUDIO_ID, DesignFidelityFake, GIRIRAJ, VIDEO_ID, WORKSHOP } from "./designFidelityFake";

type Json = Record<string, unknown>;

type Answer = { status: number; json?: unknown; raw?: Buffer; contentType?: string; headers?: Record<string, string> };

const MB = 1024 * 1024;

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buffer: Buffer): number {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

export function gradientPng(from: [number, number, number], to: [number, number, number], width = 96, height = 72): Buffer {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (width * 3 + 1)] = 0;
    for (let x = 0; x < width; x += 1) {
      const t = (x / width + y / height) / 2;
      const at = y * (width * 3 + 1) + 1 + x * 3;
      for (let c = 0; c < 3; c += 1) raw[at + c] = Math.round(from[c] + (to[c] - from[c]) * t);
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 2;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", header), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

const WOOD = gradientPng([0xc9, 0xb2, 0xa1], [0x8c, 0x6d, 0x5a]);

const SIZES: Record<string, number> = {
  [VIDEO_ID]: 701 * MB,
  "img-carve-03": Math.round(3.4 * MB),
  [AUDIO_ID]: Math.round(14.2 * MB),
  "pdf-consent": 412 * 1024
};

const OVERVIEW_SECTIONS = ["A · Identity", "B · Craft", "C · Tools", "D · Materials", "E · Process", "F · Products", "G · Market", "H · Consent"];

type Cell = "complete" | "partial" | "missing";

function artisanRow(id: string, name: string, craft: string, path: string, filled: number, files: number, bytes: number, transcripts: number, review: "APPROVED" | "PENDING", cells: Cell[]) {
  const approved = review === "APPROVED" ? files + 1 : Math.floor(files / 2);
  return {
    id,
    name,
    craft,
    path,
    fields: { filled, total: 40 },
    media: { files, bytes },
    transcripts: { total: transcripts, ready: transcripts, translating: 0, failed: 0 },
    review: { approved, pending: files + 1 - approved, total: files + 1 },
    sections: cells.map((state, index) => ({ key: `s${index}`, state, missing: state === "complete" ? [] : [`${OVERVIEW_SECTIONS[index].slice(4)} answers`], href: `/questionnaire/sessions/${id}/view?section=s${index}` }))
  };
}

const C: Cell = "complete";
const P: Cell = "partial";
const M: Cell = "missing";

export const WORKSHOP_OVERVIEW = {
  path: WORKSHOP,
  title: "3rd Craft Toolkit Workshop",
  subtitle: "Bagru, Rajasthan · 14–22 Sep 2026 · 12 artisans · Dabu printing, Block carving",
  totals: { files: 926, bytes: 7_677_000_000, fields: { filled: 418, total: 480 }, transcripts: { total: 112, ready: 104, translating: 6, failed: 2 }, review: { approved: 9, pending: 3, total: 12 } },
  sections: OVERVIEW_SECTIONS.map((label, index) => ({ key: `s${index}`, label })),
  artisans: [
    artisanRow("a-mohan", "Mohan Lal Chhipa", "Block carving", `${WORKSHOP}/mohan`, 18, 22, 410 * MB, 3, "PENDING", [C, C, P, M, M, P, M, M]),
    artisanRow("a-sunita", "Sunita Devi", "Dabu printing", `${WORKSHOP}/sunita`, 30, 61, 880 * MB, 9, "PENDING", [C, C, C, P, C, P, C, M]),
    artisanRow("a-giriraj", "Giriraj Prasad Chhipa", "Dabu printing", GIRIRAJ, 34, 48, 1_331_439_861, 12, "APPROVED", [C, C, C, C, C, P, C, C]),
    artisanRow("a-ramesh", "Ramesh Chhipa", "Block carving", `${WORKSHOP}/ramesh`, 40, 97, Math.round(1.9 * 1024 * MB), 14, "APPROVED", [C, C, C, C, C, C, C, C])
  ],
  truncated: false
};

const TOOL_COLUMNS = ["Tool", "Linked artisans", "Linked workshops", "Craft", "Review", "Material", "Used in", "Size", "Made by", "Age", "Notes"];

const TOOL_ROWS: (string | null)[][] = [
  ["Indigo vat stirrer", "Giriraj Prasad Chhipa, Manisha Verma", "3rd Craft Toolkit, Jaipur Blue Pottery", "Dabu printing", "Approved", "Teak wood", "Dyeing", "1.2 m", "Local carpenter", "12 years", "Kept by the vat"],
  ["Indigo dipping frame", "Giriraj Prasad Chhipa", "3rd Craft Toolkit", "Dabu printing", "Pending", "Clay", "Dyeing", "60 cm", "Family", "4 years", "Holds the cloth flat"],
  ["Indigo sieve", "Nandkishor Kumawat", "3rd Craft Toolkit", "Blue pottery", "Approved", "Sheesham", "Printing", "30 cm", "Market", "2 years", "Fine mesh"]
];

function filler(count: number, columns: number, word: string): (string | null)[][] {
  return Array.from({ length: count }, (_, index) => Array.from({ length: columns }, (_c, column) => (column === 0 ? `${word} ${index + 1}` : column === 4 ? "Approved" : "-")));
}

const SHEETS = [
  { name: "Artisans", columns: ["Artisan", "Craft", "Location", "Workshops", "Review"], rows: filler(24, 5, "Artisan"), total: 24 },
  { name: "Tools", columns: TOOL_COLUMNS, rows: [...TOOL_ROWS, ...filler(117, TOOL_COLUMNS.length, "Tool")], total: 120 },
  { name: "Products", columns: ["Product", "Artisan", "Craft", "Price", "Review"], rows: filler(58, 5, "Product"), total: 58 },
  { name: "Processes", columns: ["Process", "Steps", "Artisan", "Craft", "Review"], rows: filler(31, 5, "Process"), total: 31 },
  { name: "Media", columns: ["File", "Type", "Size", "Captured", "Review"], rows: filler(200, 5, "File"), total: 926 },
  { name: "Transcript recordings", columns: ["Recording", "Language", "Length", "Speakers", "Status"], rows: filler(200, 5, "Recording"), total: 214 },
  { name: "Transcript turns", columns: ["Recording", "Time", "Speaker", "Text", "English"], rows: filler(200, 5, "Turn"), total: 7992 }
];

function matches(sheet: (typeof SHEETS)[number], q: string) {
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  return sheet.rows.map((row, index) => ({ row, number: index + 1 })).filter(({ row }) => words.every((word) => row.join(" ").toLowerCase().includes(word)));
}

function transcriptsList(): Json {
  const items = [
    { mediaId: VIDEO_ID, name: "Interview_Dabu-mud-resist_Take-1.mp4", mediaType: "VIDEO" },
    { mediaId: AUDIO_ID, name: "Section-D_Materials_Group-of-3.m4a", mediaType: "AUDIO" },
    { mediaId: "vid-kiln-07", name: "Kiln-firing_wide_07.mov", mediaType: "VIDEO" },
    { mediaId: "aud-ambient", name: "Ambient_market-street.m4a", mediaType: "AUDIO" },
    { mediaId: "vid-carve-02", name: "Block-carving_close-up_02.mp4", mediaType: "VIDEO" },
    ...Array.from({ length: 7 }, (_, index) => ({ mediaId: `aud-g${index}`, name: `Section-${String.fromCharCode(65 + index)}_Answers_${String(index + 1).padStart(2, "0")}.m4a`, mediaType: "AUDIO" }))
  ].map((item) => ({ ...item, path: `${GIRIRAJ}/${item.name}`, hasText: item.mediaId !== "aud-ambient", capturedAt: "2026-09-22T10:00:00Z" }));
  return { items, total: items.length, truncated: false, query: null };
}

const SUMMARIES: Record<string, Json> = {
  [VIDEO_ID]: { status: "READY", languages: [{ code: "hi", label: "Hindi" }, { code: "en", label: "English" }], durationMs: 1_458_000, speakerCount: 2 },
  [AUDIO_ID]: { status: "READY", languages: [{ code: "mwr", label: "Marwari" }, { code: "en", label: "English" }], durationMs: 2_462_000, speakerCount: 4 },
  "vid-kiln-07": { status: "TRANSLATING", languages: [{ code: "hi", label: "Hindi" }], durationMs: 527_000, speakerCount: 1, translation: { done: 7, total: 10, percent: 70 } },
  "aud-ambient": { status: "NEEDS_ATTENTION", statusMessage: "No speech found in the recording", languages: [], durationMs: 190_000, speakerCount: 0 },
  "vid-carve-02": { status: "READY", languages: [{ code: "hi", label: "Hindi" }, { code: "en", label: "English" }], durationMs: 355_000, speakerCount: 1 }
};

function summaryOf(id: string): Json | null {
  if (SUMMARIES[id]) return { mediaId: id, ...SUMMARIES[id] };
  if (id.startsWith("aud-g")) return { mediaId: id, status: "READY", languages: [{ code: "hi", label: "Hindi" }, { code: "en", label: "English" }], durationMs: 600_000 + Number(id.slice(5)) * 37_000, speakerCount: 2 };
  return null;
}

function item(type: string, id: string, title: string, rest: Json = {}): Json {
  return { portal: "designer", local: true, type, id, title, href: `/data?path=${encodeURIComponent(GIRIRAJ)}`, ...rest };
}

function group(type: string, label: string, total: number, items: Json[]): Json {
  return { type, label, total, localTotal: total, remoteTotal: 0, items };
}

export function unifiedFor(q: string, scope: string | null): Json {
  const text = q.trim().toLowerCase();
  const remote = { portal: "fieldRepository", label: "Field Repository", status: "ok" };
  const base = { query: q, mode: "text", remote, typesRefused: [] as string[] };
  if (text.startsWith("giri")) {
    return {
      ...base,
      groups: [group("artisans", "Artisans", 2, [item("artisans", "a-giriraj", "Giriraj Prasad Chhipa", { subtitle: "3rd Craft Toolkit" }), item("artisans", "a-giridhar", "Giridhar Soni", { subtitle: "Bagru workshop" })])]
    };
  }
  if (text === "indgo") return { ...base, groups: [], suggestion: "indigo", suggestionTotal: 38 };
  if (text.includes("indigo") && scope !== "local" && text.includes("vat")) {
    return {
      ...base,
      groups: [
        group("transcripts", "Transcripts", 23, [
          { portal: "fieldRepository", local: false, type: "transcripts", id: "fr-bagru-dyers", title: "Bagru dyers · group interview", snippet: "…the indigo vat has to rest for three days…", highlights: [[7, 13], [14, 17]], startMs: 192_000, mediaType: "AUDIO", href: "", openUrl: "https://field-repository.example.org/data?preview=fr-bagru-dyers" }
        ]),
        group("tools", "Tools", 14, [item("tools", "t-vat", "Indigo vat stirrer", { subtitle: "2 artisans · 2 workshops" })]),
        group("artisans", "Artisans", 1, [item("artisans", "a-giriraj", "Giriraj Prasad Chhipa", { subtitle: "48 files here, 31 in Field Repository", alsoIn: { portal: "fieldRepository", id: "fr-giriraj", subtitle: "48 files here, 31 in Field Repository", openUrl: "https://field-repository.example.org/data?artisan=fr-giriraj" } })])
      ]
    };
  }
  if (text.includes("indigo")) {
    return {
      ...base,
      groups: [
        group("transcripts", "Transcripts", 21, [item("transcripts", AUDIO_ID, "Section D · Materials · Group of 3", { snippet: "We mix the mud with gum and lime so it resists the indigo where we press the block…", highlights: [[11, 14], [39, 46], [51, 57]], startMs: 724_000, mediaType: "AUDIO", subtitle: "Giriraj Prasad Chhipa · 3rd Craft Toolkit Workshop · 22 Sep 2026" })]),
        group("tools", "Tools", 3, [item("tools", "t-vat", "Indigo vat stirrer", { snippet: "Used after the mud resist dries, to keep the vat even.", highlights: [[15, 25]], subtitle: "Giriraj Prasad Chhipa, Manisha Verma · 3rd Craft Toolkit, Jaipur Blue Pottery" })]),
        group("processes", "Processes", 6, [item("processes", "p-dabu", "Dabu mud resist application", { snippet: "Step 4 of 9 · before the indigo dip.", highlights: [[25, 31]], subtitle: "Giriraj Prasad Chhipa · 3rd Craft Toolkit Workshop" })]),
        group("artisans", "Artisans", 4, []),
        group("media", "Media", 4, [])
      ]
    };
  }
  return { ...base, groups: [] };
}

export const QUEUE_FILES = 926;
export const KILN_ID = "vid-g2";
export const KILN_NAME = "Kiln-firing_wide_07.mov";
export const KILN_BYTES = 220 * 1024;
const QUEUE_FILE_BYTES = 8 * 1024;

export class DesignFidelityWebFake extends DesignFidelityFake {
  workshopZip = false;
  holdFrom = Number.POSITIVE_INFINITY;
  failingFile: number | null = null;
  private held: (() => void)[] = [];

  release() {
    for (const go of this.held.splice(0)) go();
  }

  private workshopManifest(): Json[] {
    return Array.from({ length: QUEUE_FILES }, (_, index) => ({
      path: index >= 500 && index < 613 ? `Giriraj Prasad Chhipa/Take ${index - 499}/Interview_Dabu-mud-resist_Take-1.mp4` : `Artisan ${(index % 12) + 1}/Clip_${String(index + 1).padStart(3, "0")}.jpg`,
      url: `/e2e-files/wk-${index}`,
      mediaId: `wk-${index}`,
      mediaType: "IMAGE",
      sizeBytes: QUEUE_FILE_BYTES,
      typeToken: "images"
    }));
  }

  override answer(method: string, path: string, query: URLSearchParams, body: Json | null): Answer | null {
    const at = query.get("path") ?? "";
    if (path === "/data/tree" && at === "") {
      const base = super.answer(method, path, query, body);
      const json = base?.json as Json | undefined;
      if (!json) return base;
      const entries = (json.entries as Json[]).map((entry) => (entry.path === "workshops" ? { ...entry, count: 3412 } : entry));
      return { status: 200, json: { ...json, entries } };
    }
    if (path === "/data/tree" && at === GIRIRAJ) {
      const base = super.answer(method, path, query, body);
      const json = base?.json as Json | undefined;
      if (!json) return base;
      const renamed = (json.entries as Json[]).map((entry) => {
        const named = entry.mediaId === KILN_ID ? { ...entry, name: KILN_NAME, path: `${GIRIRAJ}/${KILN_NAME}`, sizeBytes: KILN_BYTES } : entry;
        if (named.mediaType === "IMAGE") return { ...named, pixels: { width: 4032, height: 3024 } };
        if (named.mediaType === "PDF") return { ...named, pages: named.mediaId === "pdf-consent" ? 2 : 3 };
        return named;
      });
      const kiln = renamed.filter((entry) => entry.mediaId === KILN_ID);
      const rest = renamed.filter((entry) => entry.mediaId !== KILN_ID);
      const entries = [...rest.slice(0, 8), ...kiln, ...rest.slice(8)];
      return { status: 200, json: { ...json, entries, info: json.info ? { ...(json.info as Json), record: { kind: "artisan", id: "a-giriraj" }, counts: { tools: 6 } } : null } };
    }
    if (path === "/data/stats" && method === "GET" && at === GIRIRAJ) {
      return {
        status: 200,
        json: {
          files: 48,
          bytes: 1_331_439_861,
          unknownSizeFiles: 0,
          byType: { images: { files: 22, bytes: 61 * MB }, videos: { files: 4, bytes: 980 * MB }, audios: { files: 14, bytes: 196 * MB }, documents: { files: 8, bytes: 3 * MB }, transcripts: { files: 12, bytes: Math.round(1.2 * MB) } },
          truncated: false
        }
      };
    }
    if (path === "/data/stats" && method === "GET" && at === WORKSHOP) {
      if (this.workshopZip) return { status: 200, json: { files: QUEUE_FILES, bytes: QUEUE_FILES * QUEUE_FILE_BYTES, unknownSizeFiles: 0, byType: { images: { files: QUEUE_FILES, bytes: QUEUE_FILES * QUEUE_FILE_BYTES } }, truncated: false } };
      return { status: 200, json: { files: 926, bytes: 7_677_000_000, unknownSizeFiles: 0, byType: { images: { files: 512, bytes: 1_400_000_000 }, videos: { files: 96, bytes: 4_900_000_000 }, audios: { files: 214, bytes: 1_300_000_000 }, documents: { files: 104, bytes: 77_000_000 } }, truncated: false } };
    }
    if (path === "/data/stats" && method === "POST") {
      const ids = (body?.mediaIds as string[] | undefined) ?? [];
      const byType: Record<string, { files: number; bytes: number }> = {};
      let bytes = 0;
      for (const id of ids) {
        const size = SIZES[id] ?? 2 * MB;
        const key = id.startsWith("vid") ? "videos" : id.startsWith("aud") ? "audios" : id.startsWith("pdf") ? "documents" : "images";
        byType[key] ??= { files: 0, bytes: 0 };
        byType[key].files += 1;
        byType[key].bytes += size;
        bytes += size;
      }
      const transcripts = ids.filter((id) => id === VIDEO_ID || id === AUDIO_ID || id.startsWith("aud-g")).length;
      if (transcripts) byType.transcripts = { files: transcripts, bytes: 0 };
      return { status: 200, json: { files: ids.length, bytes, unknownSizeFiles: 0, byType, truncated: false } };
    }
    if (path === "/data/manifest" && at === GIRIRAJ) {
      const lines = Array.from({ length: 48 }, (_, index) => JSON.stringify({ path: `Clip_${String(index + 1).padStart(2, "0")}.jpg`, url: `/e2e-files/wk-${5000 + index}`, mediaId: `wk-${5000 + index}`, mediaType: "IMAGE", sizeBytes: QUEUE_FILE_BYTES, typeToken: "images" })).join("\n");
      return { status: 200, raw: Buffer.from(lines), contentType: "application/x-ndjson", headers: { "x-dataset-total": "48", "x-dataset-truncated": "false" } };
    }
    if (path === "/data/manifest" && at === WORKSHOP) {
      const lines = this.workshopManifest().map((line) => JSON.stringify(line)).join("\n");
      return { status: 200, raw: Buffer.from(lines), contentType: "application/x-ndjson", headers: { "x-dataset-total": String(QUEUE_FILES), "x-dataset-truncated": "false" } };
    }
    if (path === "/data/overview") {
      if (at === WORKSHOP) return { status: 200, json: WORKSHOP_OVERVIEW };
      return { status: 404, json: { detail: "Not a workshop folder." } };
    }
    if (path === "/data/visits" && method === "POST") {
      return { status: 200, json: { path: body?.path, visitedAt: "2026-11-05T10:00:00Z", previousVisitAt: "2026-11-01T10:00:00Z", newFiles: 38, newTranscripts: 4 } };
    }
    if (path === "/search/unified") return { status: 200, json: unifiedFor(query.get("q") ?? "", query.get("scope")) };
    if (path === "/data/peer") return { status: 200, json: { portal: "fieldRepository", portalLabel: "Field Repository", status: "ok", items: [], total: 0, page: 1, pageSize: 20, pages: 1, notApplied: [] } };
    if (path === "/data/tree" && at === `${WORKSHOP}/deepak`) {
      return {
        status: 200,
        json: {
          path: at,
          crumbs: [{ name: "All workshops", path: "workshops" }, { name: "3rd Craft Toolkit Workshop", path: WORKSHOP }, { name: "Deepak Prajapati", path: at }],
          entries: [],
          nextCursor: null,
          total: 0,
          truncated: false,
          info: null,
          taxonomies: [{ id: "workshops", name: "Workshop", path: "workshops", description: "Each workshop and its records.", default: true }],
          taxonomy: "workshops"
        }
      };
    }
    if (path === "/transcripts/batch") {
      const ids = (query.get("ids") ?? "").split(",").filter(Boolean);
      return { status: 200, json: { items: ids.map(summaryOf).filter(Boolean) } };
    }
    if (path === "/data/report/sheets" && (at === GIRIRAJ || at === WORKSHOP)) {
      return {
        status: 200,
        json: {
          path: at,
          sheets: SHEETS.map((sheet) => ({ key: sheet.name.toLowerCase().replace(/\s+/g, "-"), name: sheet.name, plugin: "core", count: sheet.total, files: 0, group: null, description: null })),
          extras: [],
          locales: ["en", "hi"]
        }
      };
    }
    if (path === "/data/transcripts/list" && (at === GIRIRAJ || at === WORKSHOP)) return { status: 200, json: transcriptsList() };
    if (path === "/data/report" && (at === GIRIRAJ || at === WORKSHOP) && (query.get("format") ?? "json") === "json") {
      const q = (query.get("q") ?? "").trim();
      const sheets = SHEETS.map((sheet) => {
        if (!q) return { name: sheet.name, columns: sheet.columns, rows: sheet.rows.slice(0, 200), rowNumbers: sheet.rows.slice(0, 200).map((_row, index) => index + 1), preview: sheet.total > 200, totalRows: sheet.total };
        const hits = matches(sheet, q);
        return { name: sheet.name, columns: sheet.columns, rows: hits.map((hit) => hit.row), rowNumbers: hits.map((hit) => hit.number), totalRows: sheet.total, matchedRows: hits.length };
      });
      return { status: 200, json: { sheets, builtAt: new Date(Date.now() - 2 * 60_000).toISOString(), query: q || null, previewRows: 200 } };
    }
    const thumb = /^\/data\/media\/([^/]+)\/thumbnail$/.exec(path);
    if (thumb && thumb[1].startsWith("img-")) return { status: 200, raw: WOOD, contentType: "image/png" };
    if (thumb && thumb[1].startsWith("vid-")) return { status: 404, json: { detail: "Not Found" } };
    return super.answer(method, path, query, body);
  }

  override async install(context: BrowserContext, theme: "light" | "dark" = "light"): Promise<void> {
    await super.install(context, theme);
    await context.route(
      (url) => url.pathname.startsWith("/e2e-files/wk-"),
      async (route: Route) => {
        const index = Number(new URL(route.request().url()).pathname.replace("/e2e-files/wk-", ""));
        if (index === this.failingFile) return route.fulfill({ status: 404, body: "gone" });
        if (index >= this.holdFrom && index < QUEUE_FILES) await new Promise<void>((resolve) => this.held.push(resolve));
        else await new Promise((resolve) => setTimeout(resolve, 6));
        return route.fulfill({ status: 200, contentType: "application/octet-stream", headers: { "accept-ranges": "bytes" }, body: Buffer.alloc(QUEUE_FILE_BYTES, index & 0xff) });
      }
    );
  }
}
