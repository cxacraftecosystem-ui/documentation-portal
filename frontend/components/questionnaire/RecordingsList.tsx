"use client";

import Link from "next/link";
import { AudioLines } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { chipToggler, FilterBar } from "@/components/questionnaire/FilterBar";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { TranscriptStatus } from "@/components/questionnaire/TranscriptStatus";
import { TranscriptPreview } from "@/components/transcript/TranscriptPreview";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { useListPlace, useUrlFilters } from "@/components/questionnaire/useUrlFilters";
import { questionnaireApi, type Recording } from "@/lib/questionnaire/api";
import { cleanFilters, type Filters } from "@/lib/questionnaire/filters";
import { qs } from "@/lib/questionnaire/qs";

/**
 * Every take, newest first, found by what its chips say (owner, 31 Oct 2026): search and filter on
 * the server (`GET /questionnaire/recordings`), the filters in the URL, and a tap on any chip
 * filters by it. A row opens its session.
 */
export function RecordingsList() {
  const { filters, setFilters, ready } = useUrlFilters();
  const config = useQuestionnaireConfig();
  const [items, setItems] = useState<Recording[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [pages, setPages] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(async (current: Filters, after: string | null) => {
    const mine = (generation.current += 1);
    setError(null);
    try {
      const page = await questionnaireApi.listRecordings(current, after);
      if (mine !== generation.current) return;
      setItems((prev) => (after && prev ? [...prev, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setPages((n) => (after ? n + 1 : 1));
    } catch {
      if (mine === generation.current) setError(qs("listLoadError"));
    }
  }, []);

  useEffect(() => {
    if (ready) void load(filters, null);
  }, [ready, filters, load]);

  // Back from a session: as many pages as were open, then the scroll (`useListPlace`).
  const { wantsMore } = useListPlace(pages, items !== null, cursor === null);
  useEffect(() => {
    if (cursor && wantsMore(pages)) void load(filters, cursor);
    // `filters` is read as it stands; a change of filters reloads from page one above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cursor, pages, wantsMore, load]);

  const onChip = chipToggler(filters, setFilters);
  const filtered = Object.keys(cleanFilters(filters)).length > 0;

  return (
    <>
      <PageHeader title={qs("recordingsTitle")} icon={<AudioLines className="h-5 w-5" aria-hidden />} />
      <FilterBar filters={filters} onChange={setFilters} placeholder={qs("filterSearchRecordings")} />
      {error ? (
        <div className="panel grid gap-3 p-4 text-center">
          <p className="text-sm text-ink-700">{error}</p>
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => void load(filters, null)}>
              {qs("tryAgain")}
            </button>
          </ButtonRow>
        </div>
      ) : items === null ? (
        <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>
      ) : items.length === 0 ? (
        <EmptyState title={filtered ? qs("filterNoMatches") : qs("noSessionsYet")} />
      ) : (
        <ul className="grid gap-2" data-recordings-list>
          {items.map((r) => (
            <li key={r.id} className="panel grid gap-2 p-3" data-recording-row={r.id}>
              <RecordingChips chips={r.chips} onChip={onChip} filters={filters} />
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Link
                  className="min-w-0 truncate text-sm text-purple-700 underline-offset-2 hover:underline"
                  href={`/questionnaire/sessions/${encodeURIComponent(r.sessionId)}/view`}
                >
                  {r.chips?.session?.title || r.downloadName}
                </Link>
                <span className="text-xs text-ink-500">{r.recordedBy.name}</span>
              </div>
              <TranscriptStatus recording={r} paused={config?.transcriptionPaused ?? true} />
              <TranscriptPreview preview={r.transcript.preview} text={r.transcript.text} />
            </li>
          ))}
        </ul>
      )}
      {cursor ? (
        <ButtonRow className="mt-4">
          <button type="button" className="field-button-secondary" onClick={() => void load(filters, cursor)}>
            {qs("showMore")}
          </button>
        </ButtonRow>
      ) : null}
    </>
  );
}
