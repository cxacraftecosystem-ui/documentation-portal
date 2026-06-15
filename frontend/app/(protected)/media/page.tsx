"use client";

import { useEffect, useMemo, useState } from "react";
import { Camera, Upload } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch, listResource } from "@/lib/api";
import { bytes, formatDateTime } from "@/lib/format";
import { locationFromForm, requiredText, textValue } from "@/lib/forms";
import type { MediaFile, PageResult } from "@/lib/types";
import { mediaTypes } from "@/lib/types";

function inferMediaType(file: File | null) {
  if (!file) return "OTHER";
  if (file.type.startsWith("image/")) return "IMAGE";
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  if (file.type === "application/pdf") return "PDF";
  return "DOCUMENT";
}

export default function MediaPage() {
  const [data, setData] = useState<PageResult<MediaFile> | null>(null);
  const [page, setPage] = useState(1);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [mediaType, setMediaType] = useState("OTHER");
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fileSummary = useMemo(() => {
    if (!selectedFile) return null;
    return `${selectedFile.name} · ${bytes(selectedFile.size)} · ${selectedFile.type || "unknown MIME"}`;
  }, [selectedFile]);

  async function load() {
    try {
      setData(await listResource<MediaFile>("/media", { page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load media");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedFile) {
      setError("Choose a file first");
      return;
    }
    setUploading(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    try {
      const linkedRecordType = textValue(form, "linkedRecordType");
      const linkedRecordId = textValue(form, "linkedRecordId");
      const presign = await apiFetch<{
        uploadUrl: string;
        objectKey: string;
        bucket: string;
        headers: Record<string, string>;
        publicUrl?: string;
      }>("/media/presign", {
        method: "POST",
        body: JSON.stringify({
          filename: selectedFile.name,
          mimeType: selectedFile.type || "application/octet-stream",
          mediaType,
          sizeBytes: selectedFile.size,
          linkedRecordType,
          linkedRecordId
        })
      });
      const uploadResponse = await fetch(presign.uploadUrl, {
        method: "PUT",
        headers: presign.headers,
        body: selectedFile
      });
      if (!uploadResponse.ok) throw new Error("Object storage upload failed");

      await apiFetch("/media/complete", {
        method: "POST",
        body: JSON.stringify({
          originalFilename: selectedFile.name,
          mediaType,
          mimeType: selectedFile.type || "application/octet-stream",
          sizeBytes: selectedFile.size,
          objectKey: presign.objectKey,
          bucket: presign.bucket,
          url: presign.publicUrl,
          caption: textValue(form, "caption"),
          linkedRecordType,
          linkedRecordId,
          location: locationFromForm(form)
        })
      });
      event.currentTarget.reset();
      setSelectedFile(null);
      setMediaType("OTHER");
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to upload media");
    } finally {
      setUploading(false);
    }
  }

  async function remove(id: string) {
    if (!window.confirm("Remove this media metadata record? The object in S3 is not deleted by this action.")) return;
    await apiFetch(`/media/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <>
      <PageHeader
        title="Media"
        description="Upload images, video, audio, PDFs and documents through signed S3-compatible object storage URLs."
        icon={<Camera className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="File" required>
            <input
              className="field-input"
              type="file"
              required
              onChange={(event) => {
                const file = event.target.files?.[0] ?? null;
                setSelectedFile(file);
                setMediaType(inferMediaType(file));
              }}
            />
          </Field>
          <Field label="Media type">
            <Select name="mediaType" value={mediaType} onChange={(event) => setMediaType(event.target.value)}>
              {mediaTypes.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Linked record type">
            <Select name="linkedRecordType">
              <option value="">Unlinked</option>
              <option value="artisan">Artisan</option>
              <option value="workshop">Workshop</option>
              <option value="product">Product</option>
              <option value="tool">Tool</option>
            </Select>
          </Field>
          <Field label="Linked record ID">
            <TextInput name="linkedRecordId" placeholder="Paste record ID" />
          </Field>
        </div>
        {fileSummary ? <div className="rounded-md bg-neutral-50 px-3 py-2 text-sm text-neutral-600">{fileSummary}</div> : null}
        <Field label="Caption">
          <TextArea name="caption" />
        </Field>
        <LocationFields />
        <div>
          <button className="field-button" disabled={uploading}>
            <Upload className="h-4 w-4" aria-hidden />
            {uploading ? "Uploading..." : "Upload media"}
          </button>
        </div>
      </form>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No media uploaded" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[920px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Preview</th>
                  <th className="px-4 py-3">File</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Size</th>
                  <th className="px-4 py-3">Linked record</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Uploaded</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((item) => (
                  <tr key={item.id}>
                    <td className="px-4 py-3">
                      {item.mediaType === "IMAGE" && item.url ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={item.url} alt={item.caption ?? item.originalFilename} className="h-14 w-20 rounded-md object-cover" />
                      ) : item.url ? (
                        <a className="font-semibold text-field-700" href={item.url} target="_blank" rel="noreferrer">
                          Open
                        </a>
                      ) : (
                        <span className="text-neutral-500">No URL</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-900">{item.originalFilename}</div>
                      <div className="max-w-xs truncate text-xs text-neutral-500">{item.objectKey}</div>
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{item.mediaType}</td>
                    <td className="px-4 py-3 text-neutral-600">{bytes(item.sizeBytes)}</td>
                    <td className="px-4 py-3 text-neutral-600">
                      {item.linkedRecordType && item.linkedRecordId ? `${item.linkedRecordType}: ${item.linkedRecordId}` : "-"}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDateTime(item.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <button className="text-sm font-semibold text-red-700" onClick={() => remove(item.id)}>
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
    </>
  );
}
