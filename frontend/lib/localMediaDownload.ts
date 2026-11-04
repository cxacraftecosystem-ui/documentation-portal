import { saveBlobToDevice, saveUrlToDevice } from "@/lib/saveToDevice";
import { recordingFilename } from "@/lib/naming";

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

export function fileStamp(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}-` +
    `${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
  );
}

const ILLEGAL_NAME_CHARS = /[\\/:*?"<>|\u0000-\u001f]/g;

export function localMediaFileName(name: string | null | undefined, mimeType: string | null | undefined, now: Date): string {
  const cleaned = (name ?? "").replace(ILLEGAL_NAME_CHARS, "_").trim().replace(/^\.+/, "");
  const extension = extensionForMimeType(mimeType);
  if (cleaned) {
    const dot = cleaned.lastIndexOf(".");
    const hasExtension = dot > 0 && dot < cleaned.length - 1;
    return hasExtension || extension === "bin" ? cleaned : `${cleaned}.${extension}`;
  }
  const base = (mimeType ?? "").trim().toLowerCase();
  const what = base.startsWith("audio/") ? "Recording" : base.startsWith("video/") ? "Video" : base.startsWith("image/") ? "Photo" : "File";
  return recordingFilename(null, extension, { capturedAt: now, what });
}

export function downloadLocalMedia(source: Blob, name?: string | null, now: Date = new Date()): string {
  const fallbackName = typeof File !== "undefined" && source instanceof File ? source.name : null;
  const fileName = localMediaFileName(name ?? fallbackName, source.type, now);
  saveBlobToDevice(source, fileName);
  return fileName;
}

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
