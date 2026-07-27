"use client";

import { useEffect, useRef, useState } from "react";
import { Camera, FolderOpen, Mic, Square, Video } from "lucide-react";

import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { RecordingStrip } from "@/components/media/Waveform";
import {
  audioExtensionForMimeType,
  inferMediaType,
  pickAudioRecorderMimeType,
  SPEECH_AUDIO_CONSTRAINTS,
  type StageEntry
} from "@/lib/media";
import { useEagerStaging } from "@/lib/uploads";
import type { MediaType } from "@/lib/types";

const imageAccept = "image/*,.jpg,.jpeg,.png,.gif,.webp,.heic,.heif,.tif,.tiff,.bmp,.avif";
const audioAccept = "audio/*,.mp3,.wav,.m4a,.aac,.ogg,.oga,.opus,.webm,.flac,.amr";
const videoAccept = "video/*,.mp4,.mov,.m4v,.webm,.mkv,.avi,.3gp";
const documentAccept = ".pdf,.txt,.csv,.doc,.docx,.xls,.xlsx,.json";

const ACCEPT_BY_TYPE: Record<MediaType, string> = {
  IMAGE: imageAccept,
  VIDEO: videoAccept,
  AUDIO: audioAccept,
  PDF: ".pdf",
  DOCUMENT: documentAccept,
  OTHER: ""
};

function mergeFiles(existing: File[], incoming: File[]) {
  const merged = [...existing];
  incoming.forEach((file) => {
    if (!merged.some((item) => item.name === file.name && item.size === file.size && item.lastModified === file.lastModified)) {
      merged.push(file);
    }
  });
  return merged;
}

/** Per-file transfer wording, mirroring the Android capture screen's attachment rows. */
function stageStatusLabel(entry: StageEntry | null): string | null {
  if (!entry) return null;
  if (entry.status === "ready") return "Uploaded ✓";
  if (entry.status === "error") return entry.error ? `Upload failed — ${entry.error}` : "Upload failed";
  const percent = entry.total > 0 ? Math.floor((entry.loaded * 100) / entry.total) : 0;
  return `Uploading… ${percent}%`;
}

/**
 * Attach-media card, mirroring the Android `MediaCaptureSection`: the same option buttons in the
 * same order — Pick files, Take photo, Record video, Record audio — plus a live waveform while
 * audio records and a tap-to-preview tile grid with per-file remove.
 *
 * Attaching a file starts its upload IMMEDIATELY (Android's eager pre-upload), so the transfer
 * overlaps the time spent filling the form and saving only has to link the finished object. The
 * selected `File[]` still flows to the caller unchanged — `uploadMediaBatch` recognises the files
 * that are already in object storage, so no call site has to know this happened.
 */
export function MediaCaptureField({
  files,
  onFilesChange,
  title = "Attach media",
  description = "Photos, video, audio and files link to this record automatically. Audio is queued for transcription after upload.",
  allowDocuments = true,
  allowedTypes
}: {
  files: File[];
  onFilesChange: (files: File[]) => void;
  title?: string;
  description?: string;
  allowDocuments?: boolean;
  allowedTypes?: MediaType[];
}) {
  const [recording, setRecording] = useState(false);
  // The live stream is state, not just a ref, because <Waveform> needs to re-render on it.
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [previewItems, setPreviewItems] = useState<PreviewMedia[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const elapsedTimerRef = useRef<number | null>(null);
  // Latest files, so async callbacks (recorder.onstop) never append to a stale snapshot.
  const filesRef = useRef(files);
  useEffect(() => {
    filesRef.current = files;
  }, [files]);

  // Eager pre-upload: every attached file starts streaming to object storage right away.
  const staging = useEagerStaging(files, title);

  const imageAllowed = !allowedTypes || allowedTypes.includes("IMAGE");
  const videoAllowed = !allowedTypes || allowedTypes.includes("VIDEO");
  const audioAllowed = !allowedTypes || allowedTypes.includes("AUDIO");

  // "Pick files" accepts everything the field allows; addFiles still filters against allowedTypes.
  const pickAccept = (
    allowedTypes
      ? allowedTypes.map((type) => ACCEPT_BY_TYPE[type])
      : [imageAccept, videoAccept, audioAccept, allowDocuments ? documentAccept : null]
  )
    .filter(Boolean)
    .join(",");

  function addFiles(fileList: FileList | null) {
    if (!fileList) return;
    // Only the NEW files are filtered against allowedTypes — files already added stay untouched.
    const incoming = Array.from(fileList).filter((file) => !allowedTypes || allowedTypes.includes(inferMediaType(file)));
    if (!incoming.length) return;
    onFilesChange(mergeFiles(files, incoming));
  }

  function stopElapsedTimer() {
    if (elapsedTimerRef.current !== null) {
      window.clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
  }

  async function startAudioRecording() {
    const liveStream = await navigator.mediaDevices.getUserMedia({ audio: SPEECH_AUDIO_CONSTRAINTS });
    streamRef.current = liveStream;
    chunksRef.current = [];
    // Ask the browser what it can actually record: Safari/iOS produces audio/mp4, so a hardcoded
    // "audio/webm" name and type would lie about the bytes and break playback and transcription.
    const preferredType = pickAudioRecorderMimeType();
    const recorder = new MediaRecorder(liveStream, preferredType ? { mimeType: preferredType } : undefined);
    recorderRef.current = recorder;
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunksRef.current.push(event.data);
    };
    recorder.onstop = () => {
      const mimeType = recorder.mimeType || preferredType || "audio/webm";
      const blob = new Blob(chunksRef.current, { type: mimeType });
      const file = new File([blob], `field-recording-${Date.now()}.${audioExtensionForMimeType(mimeType)}`, { type: mimeType });
      onFilesChange([...filesRef.current, file]);
      liveStream.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    };
    const startedAt = Date.now();
    setElapsedMs(0);
    // Only the clock needs a timer now — the bars run on <Waveform>'s own requestAnimationFrame loop,
    // which owns (and tears down) the AudioContext and analyser.
    elapsedTimerRef.current = window.setInterval(() => setElapsedMs(Date.now() - startedAt), 250);
    recorder.start();
    setStream(liveStream);
    setRecording(true);
  }

  function stopAudioRecording() {
    recorderRef.current?.stop();
    stopElapsedTimer();
    setRecording(false);
    setStream(null);
    setElapsedMs(0);
  }

  useEffect(() => {
    return () => {
      stopElapsedTimer();
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  useEffect(() => {
    const items = files.map((file, index) => ({
      key: `${file.name}-${file.size}-${file.lastModified}-${index}`,
      name: file.name,
      mediaType: inferMediaType(file),
      mimeType: file.type || "unknown MIME",
      sizeBytes: file.size,
      url: URL.createObjectURL(file)
    }));
    setPreviewItems(items);
    return () => {
      items.forEach((item) => {
        if (item.url) URL.revokeObjectURL(item.url);
      });
    };
  }, [files]);

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-card p-4 shadow-sm">
      <div>
        <h3 className="font-display font-bold text-lg text-ink-900">{title}</h3>
        <p className="mt-1 text-sm text-ink-500">{description}</p>
      </div>
      <div
        className={`grid gap-3 rounded-lg border-2 border-dashed p-3 transition ${
          dragging ? "border-purple-600 bg-purple-50" : "border-line-200 bg-surface-50"
        }`}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          addFiles(event.dataTransfer.files);
        }}
      >
        {/* Android order: Pick files · Take photo · Record video · Record audio. */}
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <label className="file-trigger">
            <FolderOpen className="h-4 w-4" aria-hidden />
            Pick files
            <input className="hidden" type="file" accept={pickAccept || undefined} multiple onChange={(event) => addFiles(event.target.files)} />
          </label>
          {imageAllowed ? (
            <label className="file-trigger">
              <Camera className="h-4 w-4" aria-hidden />
              Take photo
              <input className="hidden" type="file" accept={imageAccept} capture="environment" multiple onChange={(event) => addFiles(event.target.files)} />
            </label>
          ) : null}
          {videoAllowed ? (
            <label className="file-trigger">
              <Video className="h-4 w-4" aria-hidden />
              Record video
              <input className="hidden" type="file" accept={videoAccept} capture="environment" multiple onChange={(event) => addFiles(event.target.files)} />
            </label>
          ) : null}
          {audioAllowed ? (
            !recording ? (
              <button type="button" className="file-trigger" onClick={startAudioRecording}>
                <Mic className="h-4 w-4" aria-hidden />
                Record audio ●
              </button>
            ) : (
              <button type="button" className="file-trigger" onClick={stopAudioRecording}>
                <Square className="h-4 w-4" aria-hidden />
                Stop audio
              </button>
            )
          ) : null}
        </div>
        {recording ? <RecordingStrip stream={stream} elapsedMs={elapsedMs} /> : null}
        <p className="text-xs text-ink-500">
          Drag and drop files here, or use the buttons above. Uploading starts the moment a file is attached — saving
          then only links it — and captured files go up unchanged so embedded EXIF metadata is retained.
        </p>
      </div>
      {previewItems.length ? (
        <div className="grid gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-semibold text-ink-700">
              {previewItems.length} file{previewItems.length === 1 ? "" : "s"} attached
            </p>
            {/* Android parity wording: "All uploaded ✓ — ready to save". */}
            <p className={`text-xs ${staging.failed ? "text-error-600" : "text-ink-500"}`}>
              {staging.allReady
                ? "All uploaded ✓ — ready to save"
                : staging.failed
                  ? `${staging.failed} upload${staging.failed === 1 ? "" : "s"} failed — retry below, or just save to try again`
                  : `Uploading… ${Math.round(staging.fraction * 100)}% (${staging.ready}/${staging.total} files done)`}
            </p>
          </div>
          {!staging.allReady && !staging.failed ? (
            <div className="h-1.5 overflow-hidden rounded-full bg-line-200" aria-hidden>
              <div
                className="h-full rounded-full bg-purple-700 transition-all"
                style={{ width: `${Math.round(staging.fraction * 100)}%` }}
              />
            </div>
          ) : null}
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {previewItems.map((item, index) => {
              const entry = staging.entries[index] ?? null;
              return (
                <MediaPreviewTile
                  key={item.key}
                  item={item}
                  onOpen={() => setActivePreview(item)}
                  onRemove={() => onFilesChange(files.filter((_, itemIndex) => itemIndex !== index))}
                  removeLabel="Discard"
                  progress={entry && entry.total > 0 ? entry.loaded / entry.total : null}
                  failed={entry?.status === "error"}
                  statusLabel={stageStatusLabel(entry)}
                  onRetry={entry ? () => staging.retry(entry.file) : undefined}
                />
              );
            })}
          </div>
        </div>
      ) : null}
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </section>
  );
}
