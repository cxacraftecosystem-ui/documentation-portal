import { LIMITS, sizeOf, type IncludeKey, type ManifestFile, type TreeEntry } from "@/lib/dataExplorer/contract";
import type { JobSource } from "@/lib/dataExplorer/protocol";
import type { ManifestRequest, QueryParams } from "@/lib/dataExplorer/wire";

export type SelectedItem = {
  key: string;
  kind: "folder" | "file";
  name: string;
  path: string;
  mediaId: string | null;
  entry: TreeEntry | null;
};

export type Selection = ReadonlyMap<string, SelectedItem>;

export const EMPTY_SELECTION: Selection = new Map();

export function selectionKey(entry: Pick<TreeEntry, "kind" | "path" | "mediaId">): string {
  return entry.kind === "file" && entry.mediaId ? `media:${entry.mediaId}` : `${entry.kind}:${entry.path}`;
}

export function itemOf(entry: TreeEntry): SelectedItem {
  return {
    key: selectionKey(entry),
    kind: entry.kind,
    name: entry.name,
    path: entry.path,
    mediaId: entry.mediaId ?? null,
    entry
  };
}

export function toggled(selection: Selection, entry: TreeEntry): Selection {
  const next = new Map(selection);
  const key = selectionKey(entry);
  if (next.has(key)) next.delete(key);
  else next.set(key, itemOf(entry));
  return next;
}

export function withAll(selection: Selection, entries: TreeEntry[], on: boolean): Selection {
  const next = new Map(selection);
  for (const entry of entries) {
    const key = selectionKey(entry);
    if (on) next.set(key, itemOf(entry));
    else next.delete(key);
  }
  return next;
}

export function selectionCounts(selection: Selection): { folders: number; files: number } {
  let folders = 0;
  let files = 0;
  for (const item of selection.values()) {
    if (item.kind === "folder") folders += 1;
    else files += 1;
  }
  return { folders, files };
}

export function selectionLabel(selection: Selection): string {
  const { folders, files } = selectionCounts(selection);
  const parts: string[] = [];
  if (folders) parts.push(`${folders} ${folders === 1 ? "folder" : "folders"}`);
  if (files) parts.push(`${files} ${files === 1 ? "file" : "files"}`);
  return parts.join(" · ") || "Nothing selected";
}

export function selectionPayload(selection: Selection): { paths: string[]; mediaIds: string[]; loose: SelectedItem[] } {
  const paths: string[] = [];
  const mediaIds: string[] = [];
  const loose: SelectedItem[] = [];
  for (const item of selection.values()) {
    if (item.kind === "folder") paths.push(item.path);
    else if (item.mediaId) mediaIds.push(item.mediaId);
    else loose.push(item);
  }
  return { paths: paths.slice(0, LIMITS.manifestMaxPaths), mediaIds: mediaIds.slice(0, LIMITS.manifestMaxMediaIds), loose };
}

export function overSelectionLimit(selection: Selection): boolean {
  const { folders, files } = selectionCounts(selection);
  return folders > LIMITS.manifestMaxPaths || files > LIMITS.manifestMaxMediaIds;
}

export function looseFile(item: SelectedItem): ManifestFile {
  const entry = item.entry;
  return {
    path: item.name,
    url: entry?.url ?? null,
    content: entry?.content ?? null,
    mediaId: item.mediaId,
    mediaType: entry?.mediaType ?? null,
    sizeBytes: sizeOf(entry?.sizeBytes) ?? null
  };
}

export function selectionSource(selection: Selection, include: readonly IncludeKey[] | null, extra: QueryParams): JobSource {
  const wanted = include && include.length ? [...include] : null;
  const { paths, mediaIds, loose } = selectionPayload(selection);
  const files = [...selection.values()].filter((item) => item.kind === "file");
  const folderRequests: ManifestRequest[] = [...selection.values()]
    .filter((item) => item.kind === "folder")
    .map((item) => ({ method: "GET", endpoint: "/data/manifest", params: { path: item.path, include: wanted?.join(","), ...extra }, prefix: item.name }));
  const request: ManifestRequest = { method: "POST", endpoint: "/data/manifest", body: { paths, mediaIds, include: wanted, ...extra } };
  return {
    kind: "manifests",
    requests: [request],
    extra: loose.map(looseFile),
    fallback: { requests: folderRequests, extra: files.map(looseFile) }
  };
}
