import { useEffect, useState, useSyncExternalStore } from "react";

import { apiFetch } from "@/lib/api";

export type UploadPolicy = { autoTranscribeVideo: boolean; autoTranscribeAudio: boolean };
export type TranscribableKind = "AUDIO" | "VIDEO";
type ChoiceFile = Pick<File, "type" | "name" | "size" | "lastModified">;

export const UPLOAD_POLICY_ROUTE = "/settings/transcription/upload-policy";
export const TRANSCRIPTION_REQUEST = "TRANSCRIPTION";
export const NO_AUTO_POLICY: UploadPolicy = { autoTranscribeVideo: false, autoTranscribeAudio: false };

const POLICY_TTL_MS = 5 * 60 * 1000;
const VIDEO_EXTENSIONS = /\.(mp4|m4v|mov|webm|mkv|3gp|avi)$/i;
const AUDIO_EXTENSIONS = /\.(m4a|mp3|wav|aac|ogg|oga|opus|flac|amr|weba)$/i;

const byFile = new WeakMap<object, boolean>();
const bySignature = new Map<string, boolean>();
const listeners = new Set<() => void>();
let version = 0;

let policy: UploadPolicy | null = null;
let policyAt = 0;
let policyRequest: Promise<UploadPolicy> | null = null;

function signature(file: ChoiceFile): string {
  return `${file.size}:${file.lastModified}:${file.type}`;
}

function emit() {
  version += 1;
  listeners.forEach((listener) => listener());
}

export function transcribableKind(file: ChoiceFile): TranscribableKind | null {
  const type = (file.type || "").toLowerCase();
  if (type.startsWith("video/")) return "VIDEO";
  if (type.startsWith("audio/")) return "AUDIO";
  if (type) return null;
  if (VIDEO_EXTENSIONS.test(file.name)) return "VIDEO";
  if (AUDIO_EXTENSIONS.test(file.name)) return "AUDIO";
  return null;
}

export function transcribableFiles<T extends ChoiceFile>(files: readonly T[]): T[] {
  return files.filter((file) => transcribableKind(file) !== null);
}

export function lockedByPolicy(kind: TranscribableKind | null, current: UploadPolicy | null): boolean {
  if (!kind || !current) return false;
  return kind === "VIDEO" ? current.autoTranscribeVideo : current.autoTranscribeAudio;
}

export function transcribeChoice(file: ChoiceFile): boolean {
  if (transcribableKind(file) === null) return false;
  const direct = byFile.get(file);
  if (direct !== undefined) return direct;
  return bySignature.get(signature(file)) ?? false;
}

export function setTranscribeChoice(file: ChoiceFile, on: boolean) {
  if (transcribableKind(file) === null) return;
  byFile.set(file, on);
  bySignature.set(signature(file), on);
  emit();
}

export function setTranscribeChoices(files: readonly ChoiceFile[], on: boolean) {
  let changed = false;
  for (const file of files) {
    if (transcribableKind(file) === null) continue;
    byFile.set(file, on);
    bySignature.set(signature(file), on);
    changed = true;
  }
  if (changed) emit();
}

export function cachedUploadPolicy(): UploadPolicy | null {
  return policy;
}

export function wantsTranscription(file: ChoiceFile, current: UploadPolicy | null = policy): boolean {
  const kind = transcribableKind(file);
  if (!kind) return false;
  return lockedByPolicy(kind, current) || transcribeChoice(file);
}

export function transcribeFlags(files: readonly ChoiceFile[]): boolean[] {
  return files.map((file) => wantsTranscription(file));
}

export function processingFor(
  file: ChoiceFile,
  requested: readonly string[] | undefined,
  options: { always?: boolean; flag?: boolean } = {}
): string[] {
  const queued = new Set((requested ?? []).filter((item) => item.toUpperCase() !== TRANSCRIPTION_REQUEST));
  const kind = transcribableKind(file);
  const on = kind !== null && (options.always || (options.flag ?? wantsTranscription(file)));
  if (on) queued.add(TRANSCRIPTION_REQUEST);
  return Array.from(queued);
}

export function loadUploadPolicy(force = false): Promise<UploadPolicy> {
  if (!force && policy && Date.now() - policyAt < POLICY_TTL_MS) return Promise.resolve(policy);
  if (policyRequest) return policyRequest;
  policyRequest = apiFetch<Partial<UploadPolicy>>(UPLOAD_POLICY_ROUTE)
    .then((answer) => {
      policy = {
        autoTranscribeVideo: Boolean(answer?.autoTranscribeVideo),
        autoTranscribeAudio: Boolean(answer?.autoTranscribeAudio)
      };
      policyAt = Date.now();
      emit();
      return policy;
    })
    .catch(() => policy ?? NO_AUTO_POLICY)
    .finally(() => {
      policyRequest = null;
    });
  return policyRequest;
}

export function resetUploadChoicesForTests() {
  bySignature.clear();
  policy = null;
  policyAt = 0;
  emit();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useUploadChoiceVersion(): number {
  return useSyncExternalStore(
    subscribe,
    () => version,
    () => 0
  );
}

export function useUploadPolicy(enabled = true): UploadPolicy | null {
  useUploadChoiceVersion();
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    void loadUploadPolicy().then(() => {
      if (live) setTick((tick) => tick + 1);
    });
    return () => {
      live = false;
    };
  }, [enabled]);
  return enabled ? policy : null;
}
