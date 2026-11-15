import type { TreeEntry } from "@/lib/dataExplorer/contract";
import type { Recording } from "@/lib/questionnaire/api";

export type TakeItem = {
  key: string;
  label: string;
  recording?: Pick<Recording, "id" | "mediaFileId" | "downloadName" | "capturedAt"> | null;
  blobKey?: string | null;
  capturedAt?: string | null;
};

export type OpenTake = { slot: string; key: string };

export function takeEntry(recording: Pick<Recording, "id" | "mediaFileId" | "downloadName" | "capturedAt">): TreeEntry {
  return {
    name: recording.downloadName || recording.mediaFileId,
    path: `media:${recording.mediaFileId}`,
    kind: "file",
    mediaId: recording.mediaFileId,
    mediaType: "AUDIO",
    downloadPath: `/questionnaire/recordings/${encodeURIComponent(recording.id)}/download`,
    capturedAt: recording.capturedAt,
    transcriptAvailable: true
  };
}

export function localTakeEntry(item: Pick<TakeItem, "key" | "label" | "capturedAt">, url: string): TreeEntry {
  return { name: item.label, path: `take:${item.key}`, kind: "file", mediaType: "AUDIO", url, capturedAt: item.capturedAt ?? null };
}

export function fullEntry(item: TakeItem): TreeEntry | null {
  return item.recording ? takeEntry(item.recording) : null;
}

export function nextOpen(current: OpenTake | null, slot: string, key: string): OpenTake | null {
  return current && current.slot === slot && current.key === key ? null : { slot, key };
}

export function regionId(slot: string, key: string): string {
  return `take-${slot}-${key}`.replace(/[^A-Za-z0-9_-]/g, "-");
}

type SectionTake = {
  id: string;
  clientClipKey: string;
  capturedAt: string;
  removedAt: string | null;
  targets: { sectionId?: string | null; questionLineageId?: string | null }[];
};

type OutboxTake = { clientClipKey: string; targets: ({ sectionId: string } | { questionLineageId: string })[] };

export function sectionTakes<R extends SectionTake, L extends OutboxTake>(sectionId: string, recordings: R[], outbox: L[]): { server: R[]; local: L[] } {
  const landed = new Set(recordings.map((r) => r.clientClipKey));
  return {
    server: recordings.filter((r) => !r.removedAt && r.targets.some((t) => t.sectionId === sectionId)).sort((a, b) => a.capturedAt.localeCompare(b.capturedAt)),
    local: outbox.filter((s) => !landed.has(s.clientClipKey) && s.targets.some((t) => "sectionId" in t && t.sectionId === sectionId))
  };
}

export function questionTake<R extends SectionTake, L extends OutboxTake>(lineageId: string, recordingId: string | null, recordings: R[], outbox: L[]): R | L | null {
  const found = recordingId ? recordings.find((r) => r.id === recordingId && !r.removedAt) : null;
  if (found) return found;
  const landed = new Set(recordings.map((r) => r.clientClipKey));
  return outbox.find((s) => !landed.has(s.clientClipKey) && s.targets.some((t) => "questionLineageId" in t && t.questionLineageId === lineageId)) ?? null;
}
