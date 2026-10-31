"use client";

import Link from "next/link";
import { AudioLines } from "lucide-react";
import { useEffect, useState } from "react";

import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { RECORDINGS_HREF, useChipNavigator } from "@/components/questionnaire/useUrlFilters";
import { questionnaireApi, type Recording } from "@/lib/questionnaire/api";
import { filtersToQuery } from "@/lib/questionnaire/filters";
import { qs } from "@/lib/questionnaire/qs";

/**
 * The Recordings group of a search results page (owner, 31 Oct 2026): the first takes whose chips,
 * transcript or file name match the words, each with its chips, and a link to all of them on the
 * recordings list. Draws nothing for an empty query, with the questionnaire off, or with no match.
 */
export function RecordingSearchGroup({ q, limit = 5 }: { q: string; limit?: number }) {
  const on = Boolean(useQuestionnaireConfig()?.v2Enabled);
  const goChip = useChipNavigator();
  const [items, setItems] = useState<Recording[] | null>(null);
  const [more, setMore] = useState(false);
  const term = q.trim();

  useEffect(() => {
    if (!on || !term) return;
    let cancelled = false;
    void questionnaireApi
      .listRecordings({ q: term }, null, null, limit)
      .then((page) => {
        if (cancelled) return;
        setItems(page.items);
        setMore(Boolean(page.nextCursor));
      })
      .catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [on, term, limit]);

  if (!on || !term || !items || items.length === 0) return null;
  return (
    <section className="panel grid gap-2 p-4" data-search-recordings>
      <div className="flex items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 font-display text-base font-bold text-ink-900">
          <AudioLines className="h-4 w-4 text-purple-700" aria-hidden />
          {qs("recordingsTitle")}
        </h2>
        {more ? (
          <Link className="text-sm text-purple-700 underline-offset-2 hover:underline" href={`${RECORDINGS_HREF}?${filtersToQuery({ q: term })}`}>
            {qs("showMore")}
          </Link>
        ) : null}
      </div>
      <ul className="grid gap-2">
        {items.map((r) => (
          <li key={r.id} className="grid gap-1 rounded-md border border-line-200 p-2">
            <Link className="truncate text-sm text-purple-700 underline-offset-2 hover:underline" href={`/questionnaire/sessions/${encodeURIComponent(r.sessionId)}/view`}>
              {r.chips?.session?.title || r.downloadName}
            </Link>
            <RecordingChips chips={r.chips} onChip={goChip} />
          </li>
        ))}
      </ul>
    </section>
  );
}
