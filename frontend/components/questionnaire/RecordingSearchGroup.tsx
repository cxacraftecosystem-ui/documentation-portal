"use client";

import Link from "next/link";
import { AudioLines } from "lucide-react";
import { useEffect, useState } from "react";

import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { useRecordingSort } from "@/components/questionnaire/useRecordingSort";
import { TranscriptPreview } from "@/components/transcript/TranscriptPreview";
import { RECORDINGS_HREF, useChipNavigator } from "@/components/questionnaire/useUrlFilters";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { questionnaireApi, type Recording } from "@/lib/questionnaire/api";
import { filtersToQuery } from "@/lib/questionnaire/filters";
import { qs } from "@/lib/questionnaire/qs";
import { isRecordingSort, recordingSortOptions } from "@/lib/questionnaire/recordingSort";

export function RecordingSearchGroup({ q, limit = 5 }: { q: string; limit?: number }) {
  const on = Boolean(useQuestionnaireConfig()?.v2Enabled);
  const goChip = useChipNavigator();
  const { sort, ready, choose } = useRecordingSort();
  const [items, setItems] = useState<Recording[] | null>(null);
  const [more, setMore] = useState(false);
  const term = q.trim();

  useEffect(() => {
    if (!on || !term || !ready) return;
    let cancelled = false;
    void questionnaireApi
      .listRecordings({ q: term }, null, null, limit, sort)
      .then((page) => {
        if (cancelled) return;
        setItems(page.items);
        setMore(Boolean(page.nextCursor));
      })
      .catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [on, term, limit, sort, ready]);

  if (!on || !term || !items || items.length === 0) return null;
  return (
    <section className="panel grid gap-2 p-4" data-search-recordings data-recordings-sort={sort}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 font-display text-base font-bold text-ink-900">
          <AudioLines className="h-4 w-4 text-purple-700" aria-hidden />
          {qs("recordingsTitle")}
        </h2>
        <SegmentedChoice
          label={qs("sortRecordingsBy")}
          value={sort}
          options={recordingSortOptions()}
          onChange={(next) => {
            if (isRecordingSort(next)) choose(next);
          }}
        />
      </div>
      <ul className="grid gap-2">
        {items.map((r) => (
          <li key={r.id} className="grid gap-1 rounded-md border border-line-200 p-2">
            <Link className="truncate text-sm text-purple-700 underline-offset-2 hover:underline dark:text-purple-300" href={`/questionnaire/sessions/${encodeURIComponent(r.sessionId)}/view`}>
              {r.chips?.session?.title || r.downloadName}
            </Link>
            <RecordingChips chips={r.chips} onChip={goChip} />
            <TranscriptPreview preview={r.transcript.preview} text={r.transcript.text} lines={2} />
          </li>
        ))}
      </ul>
      {more ? (
        <Link className="w-fit justify-self-end text-sm text-purple-700 underline-offset-2 hover:underline dark:text-purple-300" href={`${RECORDINGS_HREF}?${filtersToQuery({ q: term })}`}>
          {qs("showMore")}
        </Link>
      ) : null}
    </section>
  );
}
