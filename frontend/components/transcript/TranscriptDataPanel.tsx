"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronDown, Download, FileText, Loader2 } from "lucide-react";

import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { apiFetch, buildQuery } from "@/lib/api";
import { fetchTranscriptFile, saveBlob } from "@/lib/transcript/api";
import { resolveColorKey, SPEAKER_TEXT, speakerStyle } from "@/lib/transcript/speakers";

import { Actions, SECONDARY } from "./parts";

/**
 * TRANSCRIPTS IN "VIEW DATA" AND "DOWNLOAD DATA" (0.0.6; transcript contract `dataBrowser`), the same
 * panel in both portals. One page of recordings at a time from `GET /data/transcripts`, each with its
 * speakers (mapped names, their colours), its turns with times and English, its summary, quotes,
 * glossary terms and chips; filters by the recording fields; and the streamed downloads (every
 * matching row, not just this page). A recording whose consent was refused shows no words at all,
 * and cost and model names appear only when the server sends them (administrators).
 */

export type DataTurn = {
  index: number;
  speakerKey: string | null;
  speaker: string;
  colorKey: string;
  startMs: number | null;
  start: string | null;
  text: string;
  english: string | null;
  edited: boolean;
};

export type DataRecording = {
  mediaId: string;
  file: string | null;
  uploadedBy: string | null;
  uploadedAt: string | null;
  transcriptStatus: string | null;
  withheld: "CONSENT_REFUSED" | null;
  source: "TURNS" | "TEXT" | null;
  languages: { code: string; label: string }[];
  durationMs: number | null;
  speakers: { speakerKey: string | null; label: string; colorKey: string; role: string | null }[];
  turns: DataTurn[];
  timed: boolean;
  summary: string[];
  quotes: { speaker: string | null; original: string; english: string | null }[];
  glossary: { term: string; gloss: string | null; craft: string | null }[];
  chips?: Record<string, unknown> | null;
  models?: { stt?: string | null; translation?: string | null };
  costUsd?: number;
};

type Filters = { q: string; speaker: string; language: string; status: string; hasEnglish: string; since: string; until: string };

const EMPTY: Filters = { q: "", speaker: "", language: "", status: "", hasEnglish: "", since: "", until: "" };

const STATUS_OPTIONS = [
  { value: "", label: "Any status" },
  { value: "COMPLETED", label: "Transcribed" },
  { value: "PENDING", label: "Waiting" },
  { value: "PROCESSING", label: "Transcribing" },
  { value: "FAILED", label: "Failed" }
];

const ENGLISH_OPTIONS = [
  { value: "", label: "With or without English" },
  { value: "true", label: "With English" },
  { value: "false", label: "Without English" }
];

const DOWNLOADS = [
  { kind: "recordings", fmt: "csv", label: "Recordings (.csv)" },
  { kind: "turns", fmt: "csv", label: "Turns (.csv)" },
  { kind: "glossary", fmt: "csv", label: "Glossary (.csv)" },
  { kind: "recordings", fmt: "ndjson", label: "Everything (.ndjson)" }
] as const;

/** The query the page and the downloads share: empty filters are left out. */
export function transcriptDataQuery(filters: Filters, extra: Record<string, string | number | null | undefined> = {}): string {
  return buildQuery({
    q: filters.q.trim() || undefined,
    speaker: filters.speaker.trim() || undefined,
    language: filters.language || undefined,
    status: filters.status || undefined,
    hasEnglish: filters.hasEnglish || undefined,
    since: filters.since || undefined,
    until: filters.until || undefined,
    ...extra
  });
}

const clockOf = (ms: number | null) => {
  if (ms === null || !Number.isFinite(ms)) return "";
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
};

/** Closed until asked for: the data browser stays the page, and nothing is fetched before then. */
export function TranscriptDataPanel({ languages = [] }: { languages?: { code: string; label: string }[] }) {
  const [shown, setShown] = useState(false);
  return (
    <section className="panel mb-5 grid gap-4 p-4" data-transcript-data>
      <button type="button" className="flex items-center justify-between gap-2 text-left" aria-expanded={shown} onClick={() => setShown((value) => !value)} data-transcript-data-toggle>
        <span className="flex items-center gap-2">
          <FileText className="h-5 w-5 text-purple-700 dark:text-purple-300" aria-hidden />
          <span className="font-display text-lg font-bold text-ink-900">Transcripts</span>
          <span className="text-sm text-ink-500">Turns, summaries, quotes and glossary</span>
        </span>
        <ChevronDown className={`h-5 w-5 shrink-0 text-ink-500 transition-transform ${shown ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {shown ? <TranscriptData languages={languages} /> : null}
    </section>
  );
}

function TranscriptData({ languages }: { languages: { code: string; label: string }[] }) {
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const [items, setItems] = useState<DataRecording[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(async (current: Filters, after: string | null) => {
    const mine = (generation.current += 1);
    setError(null);
    try {
      const page = await apiFetch<{ items: DataRecording[]; nextCursor: string | null }>(`/data/transcripts${transcriptDataQuery(current, { cursor: after, limit: 20 })}`);
      if (mine !== generation.current) return;
      setItems((prev) => (after && prev ? [...prev, ...page.items] : page.items));
      setCursor(page.nextCursor);
    } catch {
      if (mine === generation.current) setError("Couldn't load the transcripts. Try again.");
    }
  }, []);

  useEffect(() => {
    void load(applied, null);
  }, [applied, load]);

  // A change settles for a moment before it asks, so typing sends one request, not one per key.
  useEffect(() => {
    const timer = window.setTimeout(() => setApplied(filters), 350);
    return () => window.clearTimeout(timer);
  }, [filters]);

  async function download(kind: string, fmt: string, label: string) {
    setBusy(label);
    setError(null);
    try {
      const file = await fetchTranscriptFile(`/data/transcripts/${kind}.${fmt}${transcriptDataQuery(applied)}`, `Transcripts.${fmt}`);
      saveBlob(file.blob, file.fileName);
    } catch {
      setError("The download didn't finish. Try again.");
    } finally {
      setBusy(null);
    }
  }

  const set = (patch: Partial<Filters>) => setFilters((current) => ({ ...current, ...patch }));
  const languageOptions = [{ value: "", label: "Any language" }, ...languages.map((language) => ({ value: language.code, label: language.label }))];

  return (
    <div className="grid gap-4">

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" data-transcript-filters>
        <input
          className="field-input"
          type="search"
          value={filters.q}
          onChange={(event) => set({ q: event.target.value })}
          placeholder="Words in the transcript"
          aria-label="Words in the transcript"
          data-filter="q"
        />
        <input
          className="field-input"
          value={filters.speaker}
          onChange={(event) => set({ speaker: event.target.value })}
          placeholder="Speaker"
          aria-label="Speaker"
          data-filter="speaker"
        />
        <SearchableSelect value={filters.language} onChange={(value) => set({ language: value })} options={languageOptions} ariaLabel="Language" advanceOnSelect={false} />
        <SearchableSelect value={filters.status} onChange={(value) => set({ status: value })} options={STATUS_OPTIONS} ariaLabel="Transcript status" advanceOnSelect={false} />
        <SearchableSelect value={filters.hasEnglish} onChange={(value) => set({ hasEnglish: value })} options={ENGLISH_OPTIONS} ariaLabel="English" advanceOnSelect={false} />
        <CalendarField label="Uploaded on or after" value={filters.since} max={filters.until || undefined} onChange={(value) => set({ since: value })} />
        <CalendarField label="Uploaded before" value={filters.until} min={filters.since || undefined} onChange={(value) => set({ until: value })} />
      </div>

      <Actions align="start">
        {DOWNLOADS.map((entry) => (
          <button
            key={entry.label}
            type="button"
            className={SECONDARY}
            disabled={busy !== null}
            onClick={() => void download(entry.kind, entry.fmt, entry.label)}
            data-transcript-download={`${entry.kind}.${entry.fmt}`}
          >
            {busy === entry.label ? <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden /> : <Download className="h-4 w-4" aria-hidden />}
            {entry.label}
          </button>
        ))}
      </Actions>

      {error ? (
        <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200">
          {error}
        </p>
      ) : null}

      {items === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : items.length === 0 ? (
        <p className="text-sm text-ink-500">No recordings match.</p>
      ) : (
        <ul className="grid gap-2" data-transcript-data-list>
          {items.map((item) => (
            <RecordingRow key={item.mediaId} item={item} open={open === item.mediaId} onToggle={() => setOpen(open === item.mediaId ? null : item.mediaId)} />
          ))}
        </ul>
      )}

      {cursor ? (
        <Actions align="center">
          <button type="button" className={SECONDARY} onClick={() => void load(applied, cursor)} data-transcript-data-more>
            Show more
          </button>
        </Actions>
      ) : null}
    </div>
  );
}

function RecordingRow({ item, open, onToggle }: { item: DataRecording; open: boolean; onToggle: () => void }) {
  const facts = [
    item.uploadedAt ? new Date(item.uploadedAt).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) : null,
    item.uploadedBy,
    item.languages.map((language) => language.label).join(", ") || null,
    item.withheld ? null : `${item.turns.length} turn${item.turns.length === 1 ? "" : "s"}`,
    item.models?.stt ? item.models.stt : null,
    typeof item.costUsd === "number" ? `$${item.costUsd.toFixed(2)}` : null
  ].filter(Boolean);
  return (
    <li className="rounded-md border border-line-200 bg-card" data-transcript-data-row={item.mediaId} data-withheld={item.withheld ?? undefined}>
      <button type="button" className="flex w-full items-start justify-between gap-3 px-3 py-2 text-left" aria-expanded={open} onClick={onToggle}>
        <span className="min-w-0">
          <span className="block break-all text-sm font-medium text-ink-900">{item.file ?? item.mediaId}</span>
          <span className="block text-xs text-ink-500">{facts.join(" · ")}</span>
          {item.speakers.length && !item.withheld ? (
            <span className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-xs" data-transcript-data-speakers>
              {item.speakers.map((speaker) => (
                <span key={`${speaker.speakerKey}-${speaker.label}`} className={`font-semibold ${SPEAKER_TEXT}`} style={speakerStyle(resolveColorKey(speaker.colorKey, speaker.speakerKey, speaker.label))}>
                  {speaker.label}
                </span>
              ))}
            </span>
          ) : null}
        </span>
        <ChevronDown className={`mt-1 h-4 w-4 shrink-0 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <div className="grid gap-3 border-t border-line-200 px-3 py-3 text-sm" data-transcript-data-detail>
          {item.withheld ? (
            <p className="text-ink-700">Consent was refused, so no words are shown.</p>
          ) : (
            <>
              {item.summary.length ? (
                <ul className="grid list-disc gap-1 pl-5 text-ink-700">
                  {item.summary.map((line, index) => (
                    <li key={index}>{line}</li>
                  ))}
                </ul>
              ) : null}
              {item.quotes.length ? (
                <div className="grid gap-1">
                  {item.quotes.map((quote, index) => (
                    <blockquote key={index} className="border-l-2 border-purple-300 pl-3 text-ink-900">
                      {quote.original}
                      {quote.english ? <span className="block italic text-ink-700">{quote.english}</span> : null}
                      {quote.speaker ? <span className="block text-xs text-ink-500">{quote.speaker}</span> : null}
                    </blockquote>
                  ))}
                </div>
              ) : null}
              {item.glossary.length ? (
                <p className="text-ink-700">
                  {item.glossary.map((term) => (term.gloss ? `${term.term} (${term.gloss})` : term.term)).join(" · ")}
                </p>
              ) : null}
              <ol className="grid gap-2" data-transcript-data-turns>
                {item.turns.map((turn) => (
                  <li key={turn.index} className="grid gap-0.5">
                    <span className="text-xs">
                      <span className={`font-semibold ${SPEAKER_TEXT}`} style={speakerStyle(resolveColorKey(turn.colorKey, turn.speakerKey, turn.speaker))}>
                        {turn.speaker}
                      </span>
                      {item.timed && turn.start ? <span className="ml-2 font-mono text-ink-500">{turn.start || clockOf(turn.startMs)}</span> : null}
                      {turn.edited ? <span className="ml-2 text-ink-500">Edited</span> : null}
                    </span>
                    <span className="whitespace-pre-wrap text-ink-900">{turn.text}</span>
                    {turn.english ? <span className="whitespace-pre-wrap italic text-ink-700">{turn.english}</span> : null}
                  </li>
                ))}
              </ol>
            </>
          )}
        </div>
      ) : null}
    </li>
  );
}
