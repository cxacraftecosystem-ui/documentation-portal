/**
 * Save a piece of media the person is uploading — or has queued to upload — to their own device.
 *
 * ── WHY THIS EXISTS ────────────────────────────────────────────────────────────────────────────
 *
 * A voice note recorded in a village, or a photograph picked on a laptop, sits in this page's
 * memory (or in the offline outbox's IndexedDB store) for as long as the upload takes, and on a bad
 * connection that is minutes. Until the upload finishes, that in-browser copy is the ONLY copy. The
 * Download button on every upload/queue row hands it to the browser's own download machinery so the
 * researcher can keep their file without waiting for the network.
 *
 * ── WHAT IT MUST NEVER DO ──────────────────────────────────────────────────────────────────────
 *
 * Touch the upload. Everything here reads the same in-memory `Blob` the transfer is streaming from,
 * through an object URL; nothing aborts, pauses, re-reads into a new buffer, or awaits anything the
 * upload is waiting on. The object URL is released on a later task by `saveBlobToDevice` (see the
 * header of `lib/saveToDevice.ts` for why it cannot be released on the next line).
 *
 * The filename rules are pure and shared, word for word, with the Android `saveToDownloads` helper
 * (`ui/MediaDownloads.kt`); `e2e/local-media-download-unit.spec.ts` and `MediaDownloadsTest.kt`
 * drive the same table.
 */

import { saveBlobToDevice, saveUrlToDevice } from "@/lib/saveToDevice";
import { recordingFilename } from "@/lib/naming";

/** Exact MIME → extension pairs. Anything not listed falls back to the subtype (see below). */
const EXTENSION_BY_MIME: Record<string, string> = {
  "audio/webm": "webm",
  "audio/ogg": "ogg",
  "audio/opus": "ogg",
  "audio/mp4": "m4a",
  "audio/x-m4a": "m4a",
  "audio/m4a": "m4a",
  "audio/aac": "aac",
  "audio/mpeg": "mp3",
  "audio/mp3": "mp3",
  "audio/wav": "wav",
  "audio/x-wav": "wav",
  "audio/wave": "wav",
  "audio/flac": "flac",
  "audio/amr": "amr",
  "audio/3gpp": "3gp",
  "image/jpeg": "jpg",
  "image/jpg": "jpg",
  "image/png": "png",
  "image/gif": "gif",
  "image/webp": "webp",
  "image/heic": "heic",
  "image/heif": "heif",
  "image/avif": "avif",
  "image/bmp": "bmp",
  "image/tiff": "tif",
  "video/mp4": "mp4",
  "video/webm": "webm",
  "video/quicktime": "mov",
  "video/x-matroska": "mkv",
  "video/3gpp": "3gp",
  "video/x-msvideo": "avi",
  "application/pdf": "pdf",
  "text/plain": "txt",
  "text/csv": "csv",
  "application/json": "json",
  "application/msword": "doc",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
  "application/vnd.ms-excel": "xls",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx"
};

/**
 * File extension for a MIME type, without the dot.
 *
 * Parameters (`audio/webm;codecs=opus`) are dropped before the lookup — that is the exact string a
 * Chrome `MediaRecorder` reports. An unlisted type uses its subtype when it is a plain word
 * (`image/x-icon` → `x-icon`), and anything else — blank, malformed, or a `+suffix` structured type —
 * is `bin`, because a made-up extension is worse than an honest generic one.
 */
export function extensionForMimeType(mimeType: string | null | undefined): string {
  const base = (mimeType ?? "").split(";")[0].trim().toLowerCase();
  if (!base) return "bin";
  const known = EXTENSION_BY_MIME[base];
  if (known) return known;
  const slash = base.indexOf("/");
  const subtype = slash >= 0 ? base.slice(slash + 1) : "";
  return /^[a-z0-9][a-z0-9-]{0,15}$/.test(subtype) ? subtype : "bin";
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** `YYYY-MM-DD-HHmmss` in local time — sortable, and legal in every filesystem's names. */
export function fileStamp(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}-` +
    `${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
  );
}

/** Characters no Windows, macOS or Android filesystem accepts in a name. */
const ILLEGAL_NAME_CHARS = /[\\/:*?"<>|\u0000-\u001f]/g;

/**
 * The name the saved file gets.
 *
 * - The ORIGINAL name, whenever there is one — it is the person's own file, and the name is how
 *   they will find it again. Path separators and other illegal characters become `_`.
 * - An original name with no extension gets one from the MIME type, so the saved file opens in
 *   the right app.
 * - No name at all: the NAMING 2.1 `<Portal>_<IST date>_<HHMM>_<What>.<ext>`.
 */
export function localMediaFileName(name: string | null | undefined, mimeType: string | null | undefined, now: Date): string {
  const cleaned = (name ?? "").replace(ILLEGAL_NAME_CHARS, "_").trim().replace(/^\.+/, "");
  const extension = extensionForMimeType(mimeType);
  if (cleaned) {
    const dot = cleaned.lastIndexOf(".");
    const hasExtension = dot > 0 && dot < cleaned.length - 1;
    return hasExtension || extension === "bin" ? cleaned : `${cleaned}.${extension}`;
  }
  // NAMING 2.1: FieldRepository_2026-03-07_0905_Recording.webm, dated and timed in IST.
  const base = (mimeType ?? "").trim().toLowerCase();
  const what = base.startsWith("audio/") ? "Recording" : base.startsWith("video/") ? "Video" : base.startsWith("image/") ? "Photo" : "File";
  return recordingFilename(null, extension, { capturedAt: now, what });
}

/**
 * Save the in-memory copy of a file the page is uploading (or has queued) to the device.
 *
 * Synchronous on purpose: no await stands between the click and the download, so the browser still
 * treats it as user-initiated, and nothing here can stall the upload running beside it. Returns the
 * name the file was saved under.
 */
export function downloadLocalMedia(source: Blob, name?: string | null, now: Date = new Date()): string {
  const fallbackName = typeof File !== "undefined" && source instanceof File ? source.name : null;
  const fileName = localMediaFileName(name ?? fallbackName, source.type, now);
  saveBlobToDevice(source, fileName);
  return fileName;
}

/**
 * Save a file that has finished uploading, from the server URL the API returned for it.
 *
 * Fetched as a blob so the browser saves rather than navigates (a cross-origin URL ignores the
 * anchor's `download` hint); if CORS blocks the fetch, the anchor falls back to opening the URL.
 */
export async function downloadUploadedMedia(url: string, name: string | null | undefined, mimeType?: string | null): Promise<void> {
  const fileName = localMediaFileName(name, mimeType, new Date());
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error("The file couldn’t be downloaded.");
    saveBlobToDevice(await response.blob(), fileName);
  } catch {
    saveUrlToDevice(url, fileName, { newTab: true });
  }
}
