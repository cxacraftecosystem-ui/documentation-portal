"use client";

import { useEffect, useState } from "react";
import { BookOpenText } from "lucide-react";

import { fetchGlossary } from "@/lib/transcript/api";
import type { GlossaryTerm } from "@/lib/transcript/contract";
import { clock } from "@/lib/transcript/format";
import { ts } from "@/lib/transcript/strings";

import { SECONDARY } from "./parts";

/**
 * A craft's living glossary (design §10.4): each term heard across its transcripts, its gloss, how
 * often, and where (up to five recordings, linked at the moment). The same list feeds Scribe's key
 * terms and the translator's glossary on the next run, server-side. A refused or failed first read
 * draws nothing: the glossary is extra, never a broken panel.
 */
export function GlossaryView({ craftId, mediaHref }: { craftId: string; mediaHref: (mediaId: string, startMs: number | null) => string }) {
  const [terms, setTerms] = useState<GlossaryTerm[] | null | undefined>(undefined);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setTerms(undefined);
    fetchGlossary(craftId)
      .then((page) => {
        if (cancelled) return;
        setTerms(page.items);
        setCursor(page.nextCursor);
      })
      .catch(() => {
        if (!cancelled) setTerms(null);
      });
    return () => {
      cancelled = true;
    };
  }, [craftId]);

  async function more() {
    if (!cursor) return;
    setLoadingMore(true);
    try {
      const page = await fetchGlossary(craftId, cursor);
      setTerms((current) => [...(current ?? []), ...page.items]);
      setCursor(page.nextCursor);
    } catch {
      setCursor(null);
    } finally {
      setLoadingMore(false);
    }
  }

  if (!terms) return null;
  return (
    <section className="panel grid gap-3 p-4" aria-label={ts("glossaryTitle")} data-craft-glossary={craftId}>
      <h2 className="flex items-center gap-2 font-display text-base font-bold text-ink-900">
        <BookOpenText className="h-5 w-5 text-purple-700 dark:text-purple-300" aria-hidden />
        {ts("glossaryTitle")}
      </h2>
      {terms.length === 0 ? (
        <p className="text-sm text-ink-500">{ts("glossaryEmpty")}</p>
      ) : (
        <dl className="grid gap-3 sm:grid-cols-2">
          {terms.map((term) => (
            <div key={term.id} className="min-w-0 rounded-md border border-line-200 bg-surface-50 p-3" data-glossary-term={term.term}>
              <dt className="font-semibold italic text-ink-900">{term.term}</dt>
              {term.gloss ? <dd className="mt-0.5 text-sm text-ink-700">{term.gloss}</dd> : null}
              <dd className="mt-2 text-xs text-ink-500">{ts("glossaryHeard", { count: term.occurrences })}</dd>
              {term.sightings.length ? (
                <dd className="mt-1 flex flex-wrap gap-1.5">
                  {term.sightings.map((sighting, index) => (
                    <a
                      key={`${sighting.mediaId}-${sighting.turnId ?? index}`}
                      href={mediaHref(sighting.mediaId, sighting.startMs)}
                      className="inline-flex min-h-8 items-center rounded-full border border-line-200 bg-card px-2.5 font-mono text-xs text-purple-700 hover:border-purple-300 max-sm:min-h-10 dark:text-purple-300"
                      aria-label={ts("playFrom", { time: clock(sighting.startMs ?? 0) })}
                    >
                      {clock(sighting.startMs ?? 0)}
                    </a>
                  ))}
                </dd>
              ) : null}
            </div>
          ))}
        </dl>
      )}
      {cursor ? (
        <div className="flex justify-center">
          <button type="button" className={SECONDARY} onClick={() => void more()} disabled={loadingMore}>
            {ts("glossaryMore")}
          </button>
        </div>
      ) : null}
    </section>
  );
}
