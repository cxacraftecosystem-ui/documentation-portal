import { apiFetch } from "@/lib/api";
import type { MediaFile, MediaType } from "@/lib/types";

export function inferMediaType(file: File): MediaType {
  if (file.type.startsWith("image/")) return "IMAGE";
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  if (file.type === "application/pdf") return "PDF";
  return "DOCUMENT";
}

const UPLOAD_MAX_ATTEMPTS = 3;

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * PUT a file to object storage with real byte-level progress, via XHR (fetch cannot report upload
 * progress). Reports (loaded, total) so the caller can drive a progress bar + ETA.
 */
function putWithProgress(
  url: string,
  headers: Record<string, string>,
  file: File | Blob,
  onProgress?: (loaded: number, total: number) => void
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url, true);
    Object.entries(headers).forEach(([key, value]) => xhr.setRequestHeader(key, value));
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(event.loaded, event.total);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(file.size, file.size);
        resolve();
      } else {
        reject(new Error(`Object storage upload failed: HTTP ${xhr.status}`));
      }
    };
    xhr.onerror = () => reject(new Error("Object storage upload failed: network error"));
    xhr.ontimeout = () => reject(new Error("Object storage upload timed out"));
    xhr.timeout = 5 * 60 * 1000;
    xhr.send(file);
  });
}

export async function uploadMediaFile({
  file,
  linkedRecordType,
  linkedRecordId,
  caption,
  location,
  extraMetadata,
  recordedAt,
  recordedTimezone,
  transcribeAudio = true,
  processingRequests,
  onProgress
}: {
  file: File;
  linkedRecordType?: string;
  linkedRecordId?: string;
  caption?: string;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  transcribeAudio?: boolean;
  processingRequests?: string[];
  onProgress?: (loaded: number, total: number) => void;
}) {
  const mediaType = inferMediaType(file);
  const queuedProcessing = new Set(processingRequests ?? []);
  if (mediaType === "AUDIO" && transcribeAudio) queuedProcessing.add("TRANSCRIPTION");

  // A fresh presign per attempt keeps the 15-minute signature window from expiring under retries.
  let lastError: unknown;
  for (let attempt = 1; attempt <= UPLOAD_MAX_ATTEMPTS; attempt += 1) {
    try {
      const presign = await apiFetch<{
        uploadUrl: string;
        objectKey: string;
        bucket: string;
        headers: Record<string, string>;
        publicUrl?: string;
      }>("/media/presign", {
        method: "POST",
        body: JSON.stringify({
          filename: file.name,
          mimeType: file.type || "application/octet-stream",
          mediaType,
          sizeBytes: file.size,
          linkedRecordType,
          linkedRecordId
        })
      });
      await putWithProgress(presign.uploadUrl, presign.headers, file, onProgress);
      return await apiFetch<MediaFile>("/media/complete", {
        method: "POST",
        body: JSON.stringify({
          originalFilename: file.name,
          mediaType,
          mimeType: file.type || "application/octet-stream",
          sizeBytes: file.size,
          objectKey: presign.objectKey,
          bucket: presign.bucket,
          url: presign.publicUrl,
          caption,
          linkedRecordType,
          linkedRecordId,
          location,
          extraMetadata,
          recordedAt,
          recordedTimezone,
          processingRequests: Array.from(queuedProcessing)
        })
      });
    } catch (err) {
      lastError = err;
      if (attempt < UPLOAD_MAX_ATTEMPTS) await delay(800 * attempt);
    }
  }
  throw lastError instanceof Error ? lastError : new Error(`Object storage upload failed for ${file.name}`);
}

export type BatchProgress = {
  fileIndex: number;
  fileCount: number;
  uploadedBytes: number;
  totalBytes: number;
  fraction: number;
  etaSeconds: number | null;
  currentFileName: string;
};

export type BatchResult = {
  uploaded: MediaFile[];
  failed: Array<{ name: string; error: string }>;
};

/**
 * Upload many files resiliently: each file retries independently, a failure of one does not abort
 * the rest (so a flaky upload never loses the whole batch), and per-byte progress + ETA is reported
 * across the whole batch. Returns both the successes and any failures so the caller can surface them.
 */
export async function uploadMediaBatch({
  files,
  linkedRecordType,
  linkedRecordId,
  caption,
  location,
  extraMetadata,
  recordedAt,
  recordedTimezone,
  transcribeAudio = true,
  processingRequests,
  onProgress
}: {
  files: File[];
  linkedRecordType: string;
  linkedRecordId: string;
  caption?: string;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  transcribeAudio?: boolean;
  processingRequests?: string[];
  onProgress?: (progress: BatchProgress) => void;
}): Promise<BatchResult> {
  const uploaded: MediaFile[] = [];
  const failed: Array<{ name: string; error: string }> = [];
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0) || 1;
  const startedAt = Date.now();
  let completedBytes = 0;

  const report = (fileIndex: number, currentBytes: number, name: string) => {
    const uploadedBytes = Math.min(totalBytes, completedBytes + currentBytes);
    const fraction = uploadedBytes / totalBytes;
    const elapsed = (Date.now() - startedAt) / 1000;
    const rate = elapsed > 0 ? uploadedBytes / elapsed : 0;
    const etaSeconds = rate > 0 ? Math.max(0, Math.round((totalBytes - uploadedBytes) / rate)) : null;
    onProgress?.({ fileIndex, fileCount: files.length, uploadedBytes, totalBytes, fraction, etaSeconds, currentFileName: name });
  };

  for (let index = 0; index < files.length; index += 1) {
    const file = files[index];
    report(index, 0, file.name);
    try {
      uploaded.push(
        await uploadMediaFile({
          file,
          linkedRecordType,
          linkedRecordId,
          caption,
          location,
          extraMetadata,
          recordedAt,
          recordedTimezone,
          transcribeAudio,
          processingRequests,
          onProgress: (loaded) => report(index, loaded, file.name)
        })
      );
    } catch (err) {
      failed.push({ name: file.name, error: err instanceof Error ? err.message : "Upload failed" });
    }
    completedBytes += file.size;
    report(index, 0, file.name);
  }
  return { uploaded, failed };
}

export async function transcribeMediaFile(file: File, mediaType = inferMediaType(file)) {
  if (mediaType !== "AUDIO") return {};
  const form = new FormData();
  form.append("file", file);
  const result = await apiFetch<{
    available: boolean;
    status: string;
    text?: string | null;
    formattedTranscript?: string | null;
    message?: string;
  }>("/media/transcribe", { method: "POST", body: form });
  return {
    transcriptText: result.formattedTranscript ?? result.text ?? null,
    transcriptStatus: result.status,
    transcriptError: result.available ? null : result.message ?? "Transcription unavailable for now"
  };
}

export async function analyzeMeasurementImage(file: File) {
  const form = new FormData();
  form.append("file", file);
  return apiFetch<{
    available: boolean;
    status: string;
    analysis?: { lengthInches?: number | string | null; breadthInches?: number | string | null; notes?: string } | null;
    message?: string;
  }>("/media/analyze-measurement", { method: "POST", body: form });
}

export async function extractImageExifMetadata(file: File) {
  if (!file.type.startsWith("image/")) return null;
  try {
    const exifr = await import("exifr");
    const metadata = await exifr.parse(file, {
      tiff: true,
      exif: true,
      gps: true,
      reviveValues: false
    });
    if (!metadata) return null;
    const keys = [
      "Make",
      "Model",
      "DateTimeOriginal",
      "CreateDate",
      "ModifyDate",
      "latitude",
      "longitude",
      "GPSLatitude",
      "GPSLongitude",
      "GPSAltitude",
      "Orientation",
      "LensModel"
    ];
    return Object.fromEntries(keys.filter((key) => metadata[key] !== undefined).map((key) => [key, metadata[key]]));
  } catch {
    return null;
  }
}

export async function collectExifMetadata(files: File[]) {
  const results = await Promise.all(
    files.map(async (file) => {
      const metadata = await extractImageExifMetadata(file);
      return metadata ? { filename: file.name, metadata } : null;
    })
  );
  return results.filter(Boolean) as Array<{ filename: string; metadata: Record<string, unknown> }>;
}

export function exifMetadataToRemark(items: Array<{ filename: string; metadata: Record<string, unknown> }>) {
  if (!items.length) return "";
  return items
    .map(({ filename, metadata }) => {
      const camera = [metadata.Make, metadata.Model].filter(Boolean).join(" ");
      const captured = metadata.DateTimeOriginal ?? metadata.CreateDate ?? metadata.ModifyDate;
      const latitude = metadata.latitude ?? metadata.GPSLatitude;
      const longitude = metadata.longitude ?? metadata.GPSLongitude;
      return [
        `Image EXIF (${filename})`,
        camera ? `camera: ${camera}` : null,
        captured ? `captured: ${captured}` : null,
        latitude && longitude ? `gps: ${latitude}, ${longitude}` : null
      ]
        .filter(Boolean)
        .join("; ");
    })
    .join("\n");
}

export function appendRemarksWithExif(remarks: string | null, exifRemark: string) {
  const base = remarks?.trim() ?? "";
  if (!exifRemark) return base || null;
  return [base, exifRemark].filter(Boolean).join("\n\n");
}
