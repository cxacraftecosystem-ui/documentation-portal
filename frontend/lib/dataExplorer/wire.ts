import {
  MANIFEST_MEDIA_HEADER,
  MANIFEST_SKIPPED_HEADER,
  MANIFEST_TOTAL_HEADER,
  MANIFEST_TRUNCATED_HEADER,
  type Manifest,
  type ManifestFile
} from "@/lib/dataExplorer/contract";

export type QueryParams = Record<string, string | number | undefined | null>;

export const API_PREFIX = "/api";

export function query(params: QueryParams): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

export function apiUrl(path: string, params: QueryParams = {}): string {
  return `${API_PREFIX}${path}${query(params)}`;
}

export class DownloadRefused extends Error {
  constructor(message: string, readonly status: number, readonly retryAfter: number | null = null) {
    super(message);
    this.name = "DownloadRefused";
  }
}

export async function refusalOf(response: Response, fallback: string): Promise<DownloadRefused> {
  let message = fallback;
  try {
    const body = (await response.json()) as { detail?: unknown };
    if (typeof body.detail === "string" && body.detail.trim()) message = body.detail;
    else if (body.detail && typeof body.detail === "object" && "message" in body.detail) {
      const text = (body.detail as { message?: unknown }).message;
      if (typeof text === "string" && text.trim()) message = text;
    }
  } catch {
    message = fallback;
  }
  const retry = Number(response.headers.get("retry-after"));
  return new DownloadRefused(message, response.status, Number.isFinite(retry) && retry > 0 ? retry : null);
}

export async function readNdjson(response: Response, onLine: (value: ManifestFile) => void, signal?: AbortSignal): Promise<void> {
  if (!response.body) {
    const text = await response.text();
    for (const line of text.split("\n")) if (line.trim()) onLine(JSON.parse(line) as ManifestFile);
    return;
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let carry = "";
  try {
    for (;;) {
      if (signal?.aborted) throw new DOMException("Cancelled", "AbortError");
      const { value, done } = await reader.read();
      if (done) break;
      carry += decoder.decode(value, { stream: true });
      let newline = carry.indexOf("\n");
      while (newline >= 0) {
        const line = carry.slice(0, newline).trim();
        carry = carry.slice(newline + 1);
        if (line) onLine(JSON.parse(line) as ManifestFile);
        newline = carry.indexOf("\n");
      }
    }
    carry += decoder.decode();
    if (carry.trim()) onLine(JSON.parse(carry.trim()) as ManifestFile);
  } finally {
    reader.releaseLock();
  }
}

export type ManifestRequest =
  | { method: "GET"; endpoint: string; params: QueryParams; prefix?: string }
  | { method: "POST"; endpoint: string; body: Record<string, unknown> };

export function isUnsupported(status: number): boolean {
  return status === 404 || status === 405;
}

export async function fetchManifest(
  request: ManifestRequest,
  signal?: AbortSignal,
  onFile?: (file: ManifestFile) => void,
  fetcher: typeof fetch = fetch
): Promise<Manifest> {
  const response =
    request.method === "GET"
      ? await fetcher(apiUrl(request.endpoint, { ...request.params, stream: "true" }), { signal, credentials: "same-origin" })
      : await fetcher(apiUrl(request.endpoint), {
          method: "POST",
          signal,
          credentials: "same-origin",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ...request.body, stream: true })
        });
  if (!response.ok) throw await refusalOf(response, "The file list didn't load. Try again.");
  const type = response.headers.get("content-type") ?? "";
  const files: ManifestFile[] = [];
  const take = (file: ManifestFile) => {
    files.push(file);
    onFile?.(file);
  };
  if (type.includes("application/json")) {
    const body = (await response.json()) as Partial<Manifest>;
    (body.files ?? []).forEach(take);
    return {
      files,
      totalFiles: body.totalFiles ?? files.length,
      totalMedia: body.totalMedia ?? 0,
      truncated: Boolean(body.truncated),
      skippedMedia: body.skippedMedia ?? 0
    };
  }
  await readNdjson(response, take, signal);
  return {
    files,
    totalFiles: Number(response.headers.get(MANIFEST_TOTAL_HEADER) ?? files.length) || files.length,
    totalMedia: Number(response.headers.get(MANIFEST_MEDIA_HEADER) ?? 0) || 0,
    truncated: response.headers.get(MANIFEST_TRUNCATED_HEADER) === "true",
    skippedMedia: Number(response.headers.get(MANIFEST_SKIPPED_HEADER) ?? 0) || 0
  };
}
