import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export type TimedLine = { startMs: number; endMs: number; speakerKey: string | null; speakerName: string; text: string; english: string | null };

export type FindHit = { line: number; start: number; end: number };

export type Track = "original" | "english";

export const SEEK_STEP_SECONDS = 10;

export const PLAYBACK_RATES = [0.75, 1, 1.25, 1.5, 2] as const;

export const PEAK_BARS = 160;

export const TRANSCRIPT_BATCH = 50;

export function shortClock(ms: number | null | undefined): string {
  const total = Math.max(0, Math.floor((Number.isFinite(ms ?? NaN) ? (ms as number) : 0) / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const two = (value: number) => String(value).padStart(2, "0");
  return hours > 0 ? `${hours}:${two(minutes)}:${two(seconds)}` : `${two(minutes)}:${two(seconds)}`;
}

export function talkMinutes(ms: number): string {
  if (ms <= 0) return "0 min";
  if (ms < 60_000) return "<1 min";
  return `${Math.round(ms / 60_000)} min`;
}

export function parseStartParam(raw: string | null | undefined): number | null {
  const text = (raw ?? "").trim().toLowerCase();
  if (!text) return null;
  if (/^\d+(\.\d+)?s?$/.test(text)) return Math.round(parseFloat(text) * 1000);
  if (/^\d+ms$/.test(text)) return Number(text.slice(0, -2));
  const parts = text.split(":");
  if (parts.length < 2 || parts.length > 3 || parts.some((part) => !/^\d+$/.test(part))) return null;
  const numbers = parts.map(Number);
  const [h, m, s] = numbers.length === 3 ? numbers : [0, numbers[0], numbers[1]];
  if (m > 59 && numbers.length === 3) return null;
  if (s > 59) return null;
  return ((h * 60 + m) * 60 + s) * 1000;
}

export function lineAt(lines: readonly Pick<TimedLine, "startMs">[], ms: number): number {
  let low = 0;
  let high = lines.length - 1;
  let found = -1;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (lines[mid].startMs <= ms) {
      found = mid;
      low = mid + 1;
    } else high = mid - 1;
  }
  return found;
}

function fold(text: string): string {
  return text.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase();
}

export function findHits(lines: readonly TimedLine[], needle: string, track: Track | "both"): FindHit[] {
  const wanted = fold(needle.trim());
  if (!wanted) return [];
  const out: FindHit[] = [];
  lines.forEach((line, index) => {
    const texts = track === "english" ? [line.english ?? ""] : track === "original" ? [line.text] : [line.text, line.english ?? ""];
    for (const text of texts) {
      const folded = fold(text);
      if (folded.length !== text.length) {
        if (folded.includes(wanted)) out.push({ line: index, start: -1, end: -1 });
        continue;
      }
      let at = folded.indexOf(wanted);
      while (at >= 0) {
        out.push({ line: index, start: at, end: at + wanted.length });
        at = folded.indexOf(wanted, at + Math.max(1, wanted.length));
      }
    }
  });
  return out;
}

export function hitLabel(hits: readonly FindHit[], current: number): string {
  if (!hits.length) return "No matches";
  return `${Math.min(hits.length, Math.max(1, current + 1))} of ${hits.length}`;
}

export function stepHit(count: number, current: number, delta: 1 | -1): number {
  if (count <= 0) return 0;
  return (((current + delta) % count) + count) % count;
}

export function highlightRanges(text: string, needle: string): { text: string; hit: boolean }[] {
  const wanted = fold(needle.trim());
  if (!wanted || !text) return text ? [{ text, hit: false }] : [];
  const folded = fold(text);
  if (folded.length !== text.length) return [{ text, hit: false }];
  const out: { text: string; hit: boolean }[] = [];
  let cursor = 0;
  let at = folded.indexOf(wanted);
  while (at >= 0) {
    if (at > cursor) out.push({ text: text.slice(cursor, at), hit: false });
    out.push({ text: text.slice(at, at + wanted.length), hit: true });
    cursor = at + wanted.length;
    at = folded.indexOf(wanted, cursor);
  }
  if (cursor < text.length) out.push({ text: text.slice(cursor), hit: false });
  return out;
}

export type SpeakerTime = { key: string; name: string; ms: number };

export function talkTime(lines: readonly TimedLine[]): SpeakerTime[] {
  const byKey = new Map<string, SpeakerTime>();
  for (const line of lines) {
    const key = line.speakerKey ?? line.speakerName ?? "";
    const entry = byKey.get(key) ?? { key, name: line.speakerName || "Speaker", ms: 0 };
    entry.ms += Math.max(0, line.endMs - line.startMs);
    byKey.set(key, entry);
  }
  return [...byKey.values()];
}

export type LaneSpan = { left: number; width: number };

export function speakerLanes(lines: readonly TimedLine[], durationMs: number | null): Map<string, LaneSpan[]> {
  const lanes = new Map<string, LaneSpan[]>();
  const total = durationMs && durationMs > 0 ? durationMs : lines.reduce((most, line) => Math.max(most, line.endMs), 0);
  if (total <= 0) return lanes;
  for (const line of lines) {
    const key = line.speakerKey ?? line.speakerName ?? "";
    const left = Math.max(0, Math.min(1, line.startMs / total));
    const width = Math.max(0.002, Math.min(1 - left, (line.endMs - line.startMs) / total));
    const list = lanes.get(key) ?? [];
    const last = list[list.length - 1];
    if (last && left - (last.left + last.width) < 0.004) last.width = Math.min(1 - last.left, left + width - last.left);
    else list.push({ left, width });
    lanes.set(key, list);
  }
  return lanes;
}

export function hitMarkers(lines: readonly TimedLine[], hits: readonly FindHit[], durationMs: number | null): number[] {
  const total = durationMs && durationMs > 0 ? durationMs : lines.reduce((most, line) => Math.max(most, line.endMs), 0);
  if (total <= 0) return [];
  const seen = new Set<number>();
  const out: number[] = [];
  for (const hit of hits) {
    if (seen.has(hit.line)) continue;
    seen.add(hit.line);
    const line = lines[hit.line];
    if (line) out.push(Math.max(0, Math.min(1, line.startMs / total)));
  }
  return out;
}

export function downsample(peaks: readonly number[], bars: number = PEAK_BARS): number[] {
  const clean = peaks.map((value) => (Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0));
  if (bars <= 0 || clean.length === 0) return [];
  if (clean.length <= bars) return clean;
  const out: number[] = [];
  for (let bar = 0; bar < bars; bar += 1) {
    const from = Math.floor((bar * clean.length) / bars);
    const to = Math.max(from + 1, Math.floor(((bar + 1) * clean.length) / bars));
    let most = 0;
    for (let i = from; i < to; i += 1) most = Math.max(most, clean[i]);
    out.push(most);
  }
  return out;
}

export function baseName(name: string): string {
  return name.replace(/\.[A-Za-z0-9]{1,5}$/, "");
}

export function prettyTitle(name: string): string {
  const base = baseName(name).trim();
  if (!/[_-]/.test(base) || /\s/.test(base)) return base || name;
  return base
    .split("_")
    .map((part) => part.replace(/-/g, " ").trim())
    .filter(Boolean)
    .join(" · ");
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export function dayLabel(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  const local = new Date(at.getTime() + 330 * 60_000);
  return `${local.getUTCDate()} ${MONTHS[local.getUTCMonth()]} ${local.getUTCFullYear()}`;
}

export function hitBars(markers: readonly number[], bars: number): Set<number> {
  const out = new Set<number>();
  if (bars <= 0) return out;
  for (const at of markers) {
    const bar = Math.min(bars - 1, Math.max(0, Math.floor(at * bars)));
    out.add(bar);
    if (bar + 1 < bars) out.add(bar + 1);
  }
  return out;
}

export function fractionOf(clientX: number, rect: { left: number; width: number }): number {
  if (rect.width <= 0) return 0;
  return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
}

export function timeLink(href: string, ms: number): string {
  const url = new URL(href);
  url.searchParams.set("t", String(Math.max(0, Math.floor(ms / 1000))));
  return url.toString();
}

export function quoteLine(line: Pick<TimedLine, "speakerName" | "text" | "startMs">, link: string): string {
  return `${line.speakerName ? `${line.speakerName}: ` : ""}“${line.text.trim()}” (${shortClock(line.startMs)}) ${link}`;
}

export function nextIndex(count: number, index: number, delta: 1 | -1): number | null {
  const next = index + delta;
  return next >= 0 && next < count ? next : null;
}

export type Peaks = { peaks: number[]; durationMs: number | null; isolated: boolean; canIsolate: boolean };

export async function fetchPeaks(mediaId: string, signal?: AbortSignal): Promise<Peaks | null> {
  try {
    const answer = await apiFetch<Partial<Peaks>>(`/data/media/${encodeURIComponent(mediaId)}/peaks`, { signal });
    const peaks = Array.isArray(answer?.peaks) ? answer.peaks : [];
    return { peaks, durationMs: answer?.durationMs ?? null, isolated: answer?.isolated === true, canIsolate: answer?.canIsolate === true };
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) return null;
    throw error;
  }
}

export type IsolateStatus = { status: "queued" | "running" | "ready"; isolated: boolean };

export function isolateMedia(mediaId: string): Promise<IsolateStatus> {
  return apiFetch<IsolateStatus>(`/data/media/${encodeURIComponent(mediaId)}/isolate`, { method: "POST" });
}

export function isolatedPreviewUrl(mediaId: string): string {
  return `/api/data/media/${encodeURIComponent(mediaId)}/preview?variant=isolated`;
}

export const ISOLATE_POLL_MS = 10_000;

export type FolderTranscript = {
  mediaId: string;
  name: string;
  path: string;
  mediaType: "AUDIO" | "VIDEO" | null;
  hasText: boolean;
  capturedAt: string | null;
  snippet?: string | null;
};

export type FolderTranscripts = { items: FolderTranscript[]; total: number; truncated: boolean; query: string | null };

export async function fetchFolderTranscripts(path: string, q: string, extra: QueryParams, signal?: AbortSignal): Promise<FolderTranscripts | null> {
  try {
    const answer = await apiFetch<Partial<FolderTranscripts>>(`/data/transcripts/list${buildQuery({ path, q: q.trim() || undefined, ...extra })}`, { signal });
    const items = Array.isArray(answer.items) ? answer.items : [];
    return { items, total: typeof answer.total === "number" ? answer.total : items.length, truncated: Boolean(answer.truncated), query: answer.query ?? null };
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) return null;
    throw error;
  }
}

export type TranscriptSummary = {
  mediaId: string;
  status: string;
  languages: { code: string; label: string }[];
  durationMs: number | null;
  speakerCount: number;
  translation?: { done: number; total: number; percent: number | null } | null;
};

export function translatingLabel(summary: Pick<TranscriptSummary, "status" | "translation"> | null | undefined): string {
  if (summary?.status === "TRANSCRIBING") return "Transcribing";
  const percent = summary?.translation?.percent;
  return typeof percent === "number" ? `Translating ${percent}%` : "Translating";
}

export function chunks<T>(items: readonly T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

export async function fetchTranscriptSummaries(ids: readonly string[], signal?: AbortSignal): Promise<Map<string, TranscriptSummary> | null> {
  const out = new Map<string, TranscriptSummary>();
  for (const part of chunks([...new Set(ids)], TRANSCRIPT_BATCH)) {
    try {
      const answer = await apiFetch<{ items?: TranscriptSummary[] }>(`/transcripts/batch${buildQuery({ ids: part.join(",") })}`, { signal });
      for (const item of answer.items ?? []) out.set(item.mediaId, item);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  }
  return out;
}

export type RowState = "ready" | "working" | "failed" | "waiting" | "none";

export function rowState(status: string | null | undefined, hasText: boolean): RowState {
  switch (status) {
    case "READY":
      return "ready";
    case "TRANSCRIBING":
    case "TRANSLATING":
      return "working";
    case "NEEDS_ATTENTION":
      return "failed";
    case "QUEUED":
    case "WAITING_FOR_CONSENT":
      return "waiting";
    default:
      return hasText ? "ready" : "none";
  }
}

export type StateFilter = "all" | "ready" | "working" | "failed";

export function matchesState(state: RowState, filter: StateFilter): boolean {
  return filter === "all" || state === filter;
}

export function languagesLine(languages: readonly { label: string }[]): string | null {
  const labels = languages.map((language) => language.label).filter(Boolean);
  if (!labels.length) return null;
  const english = labels.findIndex((label) => label.toLowerCase() === "english");
  if (english > 0 || (english === 0 && labels.length > 1)) {
    const others = labels.filter((_, index) => index !== english);
    return `${others.join(", ")} → English`;
  }
  return labels.join(", ");
}

export function speakersLine(count: number): string | null {
  if (!count || count < 1) return null;
  return `${count} ${count === 1 ? "speaker" : "speakers"}`;
}

export type PaletteHit = { id: string; title: string; subtitle?: string; type: string; path?: string | null };

export type PaletteAction = { key: string; label: string; run: (hit: PaletteHit) => void };

export type ShortcutName =
  | "search"
  | "next"
  | "prev"
  | "open"
  | "toggle"
  | "selectAll"
  | "up"
  | "files"
  | "tables"
  | "transcripts"
  | "download"
  | "goData"
  | "palette"
  | "help";

export const PALETTE_LIMIT = 8;
export const CHORD_MS = 1200;

export const SHORTCUTS: { label: string; keys: string[] }[] = [
  { label: "Quick jump", keys: ["Ctrl", "K"] },
  { label: "Search", keys: ["/"] },
  { label: "Move in the list", keys: ["J", "K"] },
  { label: "Open / preview", keys: ["↵", "Space"] },
  { label: "Select", keys: ["X"] },
  { label: "Select all", keys: ["Ctrl", "A"] },
  { label: "Up a folder", keys: ["Backspace"] },
  { label: "Files · Tables · Transcripts", keys: ["1", "2", "3"] },
  { label: "Download selected", keys: ["D"] },
  { label: "Show downloads", keys: ["G", "D"] },
  { label: "These shortcuts", keys: ["?"] }
];

export type KeyLike = { key: string; ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean };

export function shortcutAction(event: KeyLike, typing: boolean, chord: boolean): ShortcutName | "chord" | null {
  const key = event.key;
  const command = event.ctrlKey || event.metaKey;
  if (command && !event.altKey && key.toLowerCase() === "k") return "palette";
  if (typing) return null;
  if (command && !event.altKey && !event.shiftKey && key.toLowerCase() === "a") return "selectAll";
  if (command || event.altKey) return null;
  if (chord) {
    if (key.toLowerCase() === "d") return "goData";
    return null;
  }
  if (key === "?") return "help";
  if (key === "/") return "search";
  switch (key.toLowerCase()) {
    case "j":
      return "next";
    case "k":
      return "prev";
    case "enter":
    case " ":
      return "open";
    case "x":
      return "toggle";
    case "backspace":
      return "up";
    case "1":
      return "files";
    case "2":
      return "tables";
    case "3":
      return "transcripts";
    case "d":
      return "download";
    case "g":
      return "chord";
    default:
      return null;
  }
}

export type PaletteEntry = { kind: "hit"; hit: PaletteHit } | { kind: "action"; action: PaletteAction } | { kind: "everything" };

export function paletteEntries(hits: readonly PaletteHit[], actions: readonly PaletteAction[], query: string): PaletteEntry[] {
  const out: PaletteEntry[] = hits.slice(0, PALETTE_LIMIT).map((hit) => ({ kind: "hit", hit }));
  if (hits.length) out.push(...actions.map((action) => ({ kind: "action" as const, action })));
  if (query.trim()) out.push({ kind: "everything" });
  return out;
}

export function stateCounts(rows: readonly { state: RowState }[]): Record<StateFilter, number> {
  return {
    all: rows.length,
    ready: rows.filter((row) => row.state === "ready").length,
    working: rows.filter((row) => row.state === "working").length,
    failed: rows.filter((row) => row.state === "failed").length
  };
}

export function languageOptions(rows: readonly { summary: Pick<TranscriptSummary, "languages"> | null }[]): { value: string; label: string }[] {
  const seen = new Map<string, string>();
  for (const row of rows) for (const language of row.summary?.languages ?? []) if (language.code && !seen.has(language.code)) seen.set(language.code, language.label || language.code);
  return [{ value: "", label: "Any language" }, ...[...seen.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([value, label]) => ({ value, label }))];
}
