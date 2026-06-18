"use client";

import { useEffect, useRef, useState } from "react";
import { Camera, FileUp, ImagePlus, Mic, Square, Trash2, Video } from "lucide-react";

import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { inferMediaType } from "@/lib/media";
import type { MediaType } from "@/lib/types";

const imageAccept = "image/*,.jpg,.jpeg,.png,.gif,.webp,.heic,.heif,.tif,.tiff,.bmp,.avif";
const audioAccept = "audio/*,.mp3,.wav,.m4a,.aac,.ogg,.oga,.opus,.webm,.flac,.amr";
const videoAccept = "video/*,.mp4,.mov,.m4v,.webm,.mkv,.avi,.3gp";
const documentAccept = ".pdf,.txt,.csv,.doc,.docx,.xls,.xlsx,.json";

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

export function MediaCaptureField({
  files,
  onFilesChange,
  title = "Media",
  description = "Upload existing files or capture images, videos, and audio during fieldwork.",
  allowDocuments = true,
  defaultAudio = false,
  allowedTypes
}: {
  files: File[];
  onFilesChange: (files: File[]) => void;
  title?: string;
  description?: string;
  allowDocuments?: boolean;
  defaultAudio?: boolean;
  allowedTypes?: MediaType[];
}) {
  const [recording, setRecording] = useState(false);
  const [audioLevel, setAudioLevel] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [previewItems, setPreviewItems] = useState<PreviewMedia[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const animationRef = useRef<number | null>(null);

  function addFiles(fileList: FileList | null) {
    const next = mergeFiles(files, fileList).filter((file) => !allowedTypes || allowedTypes.includes(inferMediaType(file)));
    onFilesChange(next);
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
      onFilesChange([...files, file]);
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

  useEffect(() => {
    if (defaultAudio && files.length === 0) {
      // Audio remains user-triggered because browsers require a click to open the microphone.
    }
    return () => {
      if (animationRef.current) cancelAnimationFrame(animationRef.current);
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, [defaultAudio, files.length]);

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
    <section className="grid gap-3 rounded-lg border border-[#e6dfd8] bg-field-100 p-4">
      <div>
        <h3 className="font-serif text-lg text-ink">{title}</h3>
        <p className="mt-1 text-sm text-ink-muted">{description}</p>
      </div>
      <div
        className={`grid gap-3 rounded-lg border-2 border-dashed p-3 transition ${
          dragging ? "border-field-600 bg-field-200" : "border-[#d8d0c4] bg-field-50"
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
        <div className="flex flex-wrap gap-2">
          <label className="field-button-secondary cursor-pointer">
            <ImagePlus className="h-4 w-4" aria-hidden />
            Upload images
            <input className="hidden" type="file" accept={imageAccept} multiple onChange={(event) => addFiles(event.target.files)} />
          </label>
          <label className="field-button-secondary cursor-pointer">
            <Camera className="h-4 w-4" aria-hidden />
            Capture image
            <input className="hidden" type="file" accept={imageAccept} capture="environment" multiple onChange={(event) => addFiles(event.target.files)} />
          </label>
          <label className="field-button-secondary cursor-pointer">
            <Video className="h-4 w-4" aria-hidden />
            Upload or record video
            <input className="hidden" type="file" accept={videoAccept} capture="environment" multiple onChange={(event) => addFiles(event.target.files)} />
          </label>
          <label className="field-button-secondary cursor-pointer">
            <Mic className="h-4 w-4" aria-hidden />
            Upload audio
            <input className="hidden" type="file" accept={audioAccept} multiple onChange={(event) => addFiles(event.target.files)} />
          </label>
          {allowDocuments ? (
            <label className="field-button-secondary cursor-pointer">
              <FileUp className="h-4 w-4" aria-hidden />
              Upload documents
              <input className="hidden" type="file" accept={documentAccept} multiple onChange={(event) => addFiles(event.target.files)} />
            </label>
          ) : null}
          {!recording ? (
            <button type="button" className="field-button-secondary" onClick={startAudioRecording}>
              <Mic className="h-4 w-4" aria-hidden />
              Record audio
            </button>
          ) : (
            <button type="button" className="field-button-secondary" onClick={stopAudioRecording}>
              <Square className="h-4 w-4" aria-hidden />
              Stop
            </button>
          )}
        </div>
        <div className="h-3 overflow-hidden rounded-full bg-[#e6dfd8]">
          <div className="h-full rounded-full bg-field-600 transition-all" style={{ width: `${recording ? audioLevel : 0}%` }} />
        </div>
        <p className="text-xs text-ink-muted">Drag and drop files here, or use the buttons above. Captured files are uploaded unchanged so embedded EXIF metadata is retained.</p>
      </div>
      {previewItems.length ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {previewItems.map((item, index) => (
            <MediaPreviewTile
              key={item.key}
              item={item}
              onOpen={() => setActivePreview(item)}
              onRemove={() => onFilesChange(files.filter((_, itemIndex) => itemIndex !== index))}
              removeLabel="Discard"
              action={
                <button type="button" className="inline-flex items-center gap-1 text-xs font-semibold text-red-700" onClick={() => onFilesChange(files.filter((_, itemIndex) => itemIndex !== index))}>
                  <Trash2 className="h-3.5 w-3.5" aria-hidden />
                  Remove
                </button>
              }
            />
          ))}
        </div>
      ) : null}
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </section>
  );
}
