"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Camera, Mic, Square, Upload, Video } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { bytes, formatDateTime } from "@/lib/format";
import { locationFromForm, textValue } from "@/lib/forms";
import { isAdmin } from "@/lib/permissions";
import type { MediaFile, MediaType, PageResult } from "@/lib/types";
import { mediaTypes } from "@/lib/types";

function inferMediaType(file: File): MediaType {
  if (file.type.startsWith("image/")) return "IMAGE";
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  if (file.type === "application/pdf") return "PDF";
  return "DOCUMENT";
}

function mergeFiles(existing: File[], incoming: FileList | null) {
  if (!incoming) return existing;
  const merged = [...existing];
  Array.from(incoming).forEach((file) => {
    if (!merged.some((item) => item.name === file.name && item.size === file.size && item.lastModified === file.lastModified)) {
      merged.push(file);
    }
  });
  return merged;
}

export default function MediaPage() {
  const { user } = useAuth();
  const [data, setData] = useState<PageResult<MediaFile> | null>(null);
  const [page, setPage] = useState(1);
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [mediaTypeOverride, setMediaTypeOverride] = useState("");
  const [uploading, setUploading] = useState(false);
  const [recording, setRecording] = useState(false);
  const [audioLevel, setAudioLevel] = useState(0);
  const [progress, setProgress] = useState<string | null>(null);
  const [warning] = useState<string | null>("Audio transcription is queued after upload. If OPENAI_API_KEY is missing, the media record is still retained and marked unavailable.");
  const [error, setError] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const animationRef = useRef<number | null>(null);

  const fileSummary = useMemo(
    () => selectedFiles.map((file) => `${file.name} · ${bytes(file.size)} · ${file.type || "unknown MIME"}`),
    [selectedFiles]
  );

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
    return () => {
      if (animationRef.current) cancelAnimationFrame(animationRef.current);
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function uploadOne(file: File, form: FormData, index: number) {
    const inferredType = inferMediaType(file);
    const mediaType = (mediaTypeOverride || inferredType) as MediaType;
    const linkedRecordType = textValue(form, "linkedRecordType");
    const linkedRecordId = textValue(form, "linkedRecordId");
    setProgress(`Uploading ${index + 1}/${selectedFiles.length}: ${file.name}`);
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
    await apiFetch("/media/complete", {
      method: "POST",
      body: JSON.stringify({
        originalFilename: file.name,
        mediaType,
        mimeType: file.type || "application/octet-stream",
        sizeBytes: file.size,
        objectKey: presign.objectKey,
        bucket: presign.bucket,
        url: presign.publicUrl,
        caption: textValue(form, "caption"),
        linkedRecordType,
        linkedRecordId,
        location: locationFromForm(form),
        processingRequests: mediaType === "AUDIO" ? ["TRANSCRIPTION"] : []
      })
    });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selectedFiles.length === 0) {
      setError("Choose or record at least one file first");
      return;
    }
    setUploading(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    try {
      for (let index = 0; index < selectedFiles.length; index += 1) {
        await uploadOne(selectedFiles[index], form, index);
      }
      event.currentTarget.reset();
      setSelectedFiles([]);
      setMediaTypeOverride("");
      setProgress(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to upload media");
    } finally {
      setUploading(false);
    }
  }

  async function startAudioRecording() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    streamRef.current = stream;
    chunksRef.current = [];
    const recorder = new MediaRecorder(stream);
    recorderRef.current = recorder;
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunksRef.current.push(event.data);
    };
    recorder.onstop = () => {
      const blob = new Blob(chunksRef.current, { type: "audio/webm" });
      const file = new File([blob], `field-recording-${Date.now()}.webm`, { type: "audio/webm" });
      setSelectedFiles((files) => [...files, file]);
      stream.getTracks().forEach((track) => track.stop());
      setAudioLevel(0);
    };
    const context = new AudioContext();
    const analyser = context.createAnalyser();
    const source = context.createMediaStreamSource(stream);
    source.connect(analyser);
    const dataArray = new Uint8Array(analyser.frequencyBinCount);
    const tick = () => {
      analyser.getByteFrequencyData(dataArray);
      const average = dataArray.reduce((sum, value) => sum + value, 0) / dataArray.length;
      setAudioLevel(Math.min(100, Math.round((average / 255) * 140)));
      animationRef.current = requestAnimationFrame(tick);
    };
    tick();
    recorder.start();
    setRecording(true);
  }

  function stopAudioRecording() {
    recorderRef.current?.stop();
    if (animationRef.current) cancelAnimationFrame(animationRef.current);
    setRecording(false);
  }

  async function remove(id: string) {
    if (!window.confirm("Remove this media metadata record? The object in S3 is not deleted by this action.")) return;
    await apiFetch(`/media/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <>
      <PageHeader
        title="Media Capture"
        description="Capture precise field location, batch upload images/videos/audio, record interviews, and store transcripts with repository media."
        icon={<Camera className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {warning ? <div className="mb-4 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">{warning}</div> : null}
      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="Images">
            <input className="field-input" type="file" accept="image/*" capture="environment" multiple onChange={(event) => setSelectedFiles((files) => mergeFiles(files, event.target.files))} />
          </Field>
          <Field label="Videos">
            <input className="field-input" type="file" accept="video/*" capture="environment" multiple onChange={(event) => setSelectedFiles((files) => mergeFiles(files, event.target.files))} />
          </Field>
          <Field label="Any media/documents">
            <input className="field-input" type="file" multiple onChange={(event) => setSelectedFiles((files) => mergeFiles(files, event.target.files))} />
          </Field>
          <Field label="Media type override">
            <Select value={mediaTypeOverride} onChange={(event) => setMediaTypeOverride(event.target.value)}>
              <option value="">Infer per file</option>
              {mediaTypes.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="rounded-md border border-[#e6dfd8] bg-field-100 p-3">
          <div className="mb-3 flex flex-wrap items-center gap-3">
            {!recording ? (
              <button type="button" className="field-button-secondary" onClick={startAudioRecording}>
                <Mic className="h-4 w-4" aria-hidden />
                Record audio
              </button>
            ) : (
              <button type="button" className="field-button-secondary" onClick={stopAudioRecording}>
                <Square className="h-4 w-4" aria-hidden />
                Stop recording
              </button>
            )}
            <span className="inline-flex items-center gap-2 text-sm text-ink-muted">
              <Video className="h-4 w-4" aria-hidden />
              Use the video picker above to record or attach video clips.
            </span>
          </div>
          <div className="h-3 overflow-hidden rounded-full bg-[#e6dfd8]">
            <div className="h-full rounded-full bg-field-600 transition-all" style={{ width: `${recording ? audioLevel : 0}%` }} />
          </div>
        </div>
        {fileSummary.length ? (
          <div className="grid gap-2 rounded-md bg-neutral-50 px-3 py-2 text-sm text-neutral-600">
            {fileSummary.map((summary, index) => (
              <div key={summary} className="flex items-center justify-between gap-3">
                <span>{summary}</span>
                <button type="button" className="text-xs font-semibold text-red-700" onClick={() => setSelectedFiles((files) => files.filter((_, itemIndex) => itemIndex !== index))}>
                  Remove
                </button>
              </div>
            ))}
          </div>
        ) : null}
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="Linked record type">
            <Select name="linkedRecordType">
              <option value="">Unlinked</option>
              <option value="artisan">Artisan</option>
              <option value="workshop">Workshop</option>
              <option value="product">Product</option>
              <option value="tool">Tool</option>
              <option value="questionnaire">Questionnaire</option>
            </Select>
          </Field>
          <Field label="Linked record ID">
            <TextInput name="linkedRecordId" placeholder="Paste record ID" />
          </Field>
        </div>
        <Field label="Caption">
          <TextArea name="caption" />
        </Field>
        <LocationFields />
        {progress ? <div className="text-sm text-ink-muted">{progress}</div> : null}
        <div>
          <button className="field-button" disabled={uploading}>
            <Upload className="h-4 w-4" aria-hidden />
            {uploading ? "Uploading batch..." : `Upload ${selectedFiles.length || ""} media file${selectedFiles.length === 1 ? "" : "s"}`}
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
            <table className="w-full min-w-[1040px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Preview</th>
                  <th className="px-4 py-3">File</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Size</th>
                  <th className="px-4 py-3">Linked record</th>
                  <th className="px-4 py-3">Transcript</th>
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
                    <td className="px-4 py-3 text-neutral-600">
                      {item.transcriptText ? (
                        <details>
                          <summary className="cursor-pointer font-semibold text-field-700">View transcript</summary>
                          <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-md bg-field-100 p-3 text-xs text-ink-body">{item.transcriptText}</pre>
                        </details>
                      ) : (
                        item.transcriptStatus ?? "-"
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDateTime(item.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      {isAdmin(user) ? (
                        <button className="text-sm font-semibold text-red-700" onClick={() => remove(item.id)}>
                          Delete
                        </button>
                      ) : (
                        <span className="text-xs text-neutral-500">Admin only</span>
                      )}
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
