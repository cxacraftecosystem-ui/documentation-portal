import { apiFetch } from "@/lib/api";
import type { MediaFile, MediaType } from "@/lib/types";

export function inferMediaType(file: File): MediaType {
  if (file.type.startsWith("image/")) return "IMAGE";
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  if (file.type === "application/pdf") return "PDF";
  return "DOCUMENT";
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
  processingRequests
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
}) {
  const mediaType = inferMediaType(file);
  const queuedProcessing = new Set(processingRequests ?? []);
  if (mediaType === "AUDIO" && transcribeAudio) queuedProcessing.add("TRANSCRIPTION");
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
  const uploadResponse = await fetch(presign.uploadUrl, {
    method: "PUT",
    headers: presign.headers,
    body: file
  });
  if (!uploadResponse.ok) throw new Error(`Object storage upload failed for ${file.name}`);
  return apiFetch<MediaFile>("/media/complete", {
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
}

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
  processingRequests
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
}) {
  const uploaded: MediaFile[] = [];
  for (const file of files) {
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
        processingRequests
      })
    );
  }
  return uploaded;
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
