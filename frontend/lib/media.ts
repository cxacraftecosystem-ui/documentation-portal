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
  caption
}: {
  file: File;
  linkedRecordType?: string;
  linkedRecordId?: string;
  caption?: string;
}) {
  const mediaType = inferMediaType(file);
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
      linkedRecordId
    })
  });
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
