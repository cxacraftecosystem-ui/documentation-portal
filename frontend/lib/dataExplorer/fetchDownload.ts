import { DownloadRefused, refusalOf } from "@/lib/dataExplorer/wire";
import { XLSX_TYPE, fileNameFrom, looksLikeZip } from "@/lib/dataExplorer/downloads";

export type FetchProgress = { bytesDone: number; bytesTotal: number | null };

export type Fetched = { blob: Blob; name: string };

export type PartialFile = { chunks: Uint8Array[]; bytes: number; total: number | null; etag: string | null };

export type FetchOptions = {
  signal?: AbortSignal;
  onProgress?: (progress: FetchProgress) => void;
  expect?: "xlsx" | "zip" | "any";
  fallbackName: string;
  resume?: PartialFile | null;
  onPartial?: (partial: PartialFile) => void;
};

export class NotAFile extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotAFile";
  }
}

function totalOf(response: Response, offset: number): number | null {
  const range = response.headers.get("content-range");
  const whole = range ? /\/(\d+)\s*$/.exec(range) : null;
  if (whole) return Number(whole[1]);
  const length = Number(response.headers.get("content-length"));
  return Number.isFinite(length) && length > 0 ? length + offset : null;
}

export async function fetchDownload(url: string, options: FetchOptions): Promise<Fetched> {
  const resume = options.expect === "xlsx" ? null : (options.resume ?? null);
  const headers = new Headers();
  if (resume && resume.bytes > 0) {
    headers.set("Range", `bytes=${resume.bytes}-`);
    if (resume.etag) headers.set("If-Range", resume.etag);
  }
  const response = await fetch(url, { signal: options.signal, headers, credentials: "same-origin", cache: "no-store" });
  if (!response.ok) throw await refusalOf(response, "The download didn't start.");
  const continuing = Boolean(resume && resume.bytes > 0 && response.status === 206);
  const chunks: Uint8Array[] = continuing && resume ? [...resume.chunks] : [];
  let bytes = continuing && resume ? resume.bytes : 0;
  const total = totalOf(response, continuing ? bytes : 0);
  const etag = response.headers.get("etag");
  const type = response.headers.get("content-type") ?? "";
  if (options.expect === "xlsx" && type.includes("application/json")) {
    throw await refusalOf(response, "The report couldn't be built.");
  }
  options.onProgress?.({ bytesDone: bytes, bytesTotal: total });
  const reader = response.body?.getReader();
  if (reader) {
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!value) continue;
        chunks.push(value);
        bytes += value.byteLength;
        options.onProgress?.({ bytesDone: bytes, bytesTotal: total });
      }
    } catch (error) {
      options.onPartial?.({ chunks, bytes, total, etag });
      throw error;
    }
  } else {
    const whole = new Uint8Array(await response.arrayBuffer());
    chunks.push(whole);
    bytes += whole.byteLength;
  }
  if (total !== null && bytes < total) {
    options.onPartial?.({ chunks, bytes, total, etag });
    throw new DownloadRefused("The connection dropped before the file finished.", 0);
  }
  const blob = new Blob(chunks as BlobPart[], { type: options.expect === "xlsx" ? XLSX_TYPE : type || "application/octet-stream" });
  if (options.expect === "xlsx" || options.expect === "zip") {
    const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
    if (!looksLikeZip(head)) throw new NotAFile("The report came back damaged. Nothing was saved.");
  }
  return { blob, name: fileNameFrom(response.headers.get("content-disposition"), options.fallbackName) };
}
