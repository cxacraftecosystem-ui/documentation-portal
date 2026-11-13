"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Camera, Check, FolderOpen, Mic, Square, Video } from "lucide-react";

import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { TranscribeChoices } from "@/components/transcript/TranscribeChoices";
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
import { TraceFromCapture } from "@/components/trace/TraceFromCapture";
import { recordingFilename } from "@/lib/naming";

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

function stageStatusLabel(entry: StageEntry | null): ReactNode {
  if (!entry) return null;
  if (entry.status === "ready")
    return (
      <>
        Uploaded
        <Check className="ml-1 inline h-3.5 w-3.5 align-[-2px]" aria-hidden />
      </>
    );
  if (entry.status === "error") return entry.error ? `Upload failed — ${entry.error}` : "Upload failed";
  const percent = entry.total > 0 ? Math.floor((entry.loaded * 100) / entry.total) : 0;
  return `Uploading… ${percent}%`;
}

export function MediaCaptureField({
  files,
  onFilesChange,
  title = "Attach media",
  description = "Photos, video, audio and files.",
  allowDocuments = true,
  allowedTypes,
  transcribeChoice = true,
  artisanIds
}: {
  files: File[];
  onFilesChange: (files: File[]) => void;
  title?: string;
  description?: string;
  allowDocuments?: boolean;
  allowedTypes?: MediaType[];
  transcribeChoice?: boolean;
  artisanIds?: readonly string[];
}) {
  const [recording, setRecording] = useState(false);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [previewItems, setPreviewItems] = useState<PreviewMedia[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const elapsedTimerRef = useRef<number | null>(null);
  const filesRef = useRef(files);
  useEffect(() => {
    filesRef.current = files;
  }, [files]);

  const staging = useEagerStaging(files, title);

  const imageAllowed = !allowedTypes || allowedTypes.includes("IMAGE");
  const videoAllowed = !allowedTypes || allowedTypes.includes("VIDEO");
  const audioAllowed = !allowedTypes || allowedTypes.includes("AUDIO");

  const pickAccept = (
    allowedTypes
      ? allowedTypes.map((type) => ACCEPT_BY_TYPE[type])
      : [imageAccept, videoAccept, audioAccept, allowDocuments ? documentAccept : null]
  )
    .filter(Boolean)
    .join(",");

  function addFiles(fileList: FileList | null) {
    if (!fileList) return;
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
    const preferredType = pickAudioRecorderMimeType();
    const recorder = new MediaRecorder(liveStream, preferredType ? { mimeType: preferredType } : undefined);
    recorderRef.current = recorder;
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunksRef.current.push(event.data);
    };
    recorder.onstop = () => {
      const mimeType = recorder.mimeType || preferredType || "audio/webm";
      const blob = new Blob(chunksRef.current, { type: mimeType });
      const name = recordingFilename(null, audioExtensionForMimeType(mimeType), { capturedAt: new Date() });
      const file = new File([blob], name, { type: mimeType });
      onFilesChange([...filesRef.current, file]);
      liveStream.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    };
    const startedAt = Date.now();
    setElapsedMs(0);
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
          Or drag files here. Uploads start right away.
        </p>
      </div>
      {transcribeChoice ? <TranscribeChoices files={files} artisanIds={artisanIds} /> : null}
      {previewItems.length ? (
        <div className="grid gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-semibold text-ink-700">
              {previewItems.length} file{previewItems.length === 1 ? "" : "s"} attached
            </p>
            <p className={`text-xs ${staging.failed ? "font-medium text-red-800 dark:text-red-300" : "text-ink-500"}`}>
              {staging.allReady
                ? (
                  <>
                    <Check className="mr-1 inline h-3.5 w-3.5 align-[-2px]" aria-hidden />
                    All uploaded — ready to save
                  </>
                )
                : staging.failed
                  ? `${staging.failed} failed — retry below or save`
                  : `Uploading… ${Math.round(staging.fraction * 100)}% (${staging.ready}/${staging.total} files done)`}
            </p>
          </div>
          {!staging.allReady && !staging.failed ? (
            <ProgressBar percent={Math.round(staging.fraction * 100)} aria-hidden fillClassName="transition-all" />
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
                  downloadFile={files[index] ?? null}
                />
              );
            })}
          </div>
        </div>
      ) : null}
      {imageAllowed ? <TraceFromCapture files={files} onFilesChange={onFilesChange} /> : null}
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </section>
  );
}
