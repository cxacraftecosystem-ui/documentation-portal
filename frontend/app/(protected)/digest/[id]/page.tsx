"use client";

/**
 * /digest/[id] — one Monday summary, the reader's own. A snapshot taken at 08:00 IST; every row links
 * to the live queue it counted. The Field Repository's summary is counts only (artisan records are
 * personal data held under consent), so sections carry rows and no titles.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { CalendarClock, ChevronRight } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { readableError } from "@/components/review/reviewErrors";
import {
  digestTiles,
  fetchDigest,
  visibleItems,
  visibleLines,
  weekWords,
  type WeeklyDigest
} from "@/lib/weeklyDigest";

export default function DigestPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id ?? "";
  const [digest, setDigest] = useState<WeeklyDigest | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setDigest(null);
    setError(null);
    fetchDigest(id)
      .then((answer) => {
        if (!cancelled) setDigest(answer);
      })
      .catch((failure) => {
        if (!cancelled) setError(readableError(failure, "Couldn't open this summary."));
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const title = digest ? `Week of ${weekWords(digest.weekStart)}` : "Weekly summary";

  return (
    <>
      <PageHeader
        title={title}
        description={digest ? "As of Monday 08:00." : undefined}
        icon={<CalendarClock className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : digest === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : (
        <>
          <ul className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-label="This week">
            {digestTiles(digest).map((tile) => (
              <li key={tile.key} className="panel p-4">
                <p className="text-xs font-medium uppercase tracking-wide text-ink-500">{tile.label}</p>
                <p className="mt-1 font-display text-3xl font-bold tabular-nums text-ink-900">{tile.figure}</p>
              </li>
            ))}
          </ul>

          <div className="grid gap-4 lg:grid-cols-3">
            {digest.sections.map((section) => {
              const lines = visibleLines(section);
              const items = visibleItems(section);
              return (
                <section key={section.key} className="panel p-4" aria-labelledby={`digest-${section.key}`}>
                  <h2 id={`digest-${section.key}`} className="font-display font-bold text-ink-900">
                    {section.label}
                  </h2>
                  {lines.length === 0 && items.length === 0 ? (
                    <p className="mt-2 text-sm text-ink-500">Nothing here.</p>
                  ) : (
                    <ul className="mt-2 divide-y divide-line-200">
                      {lines.map((line) => (
                        <li key={line.key}>
                          {line.link ? (
                            <Link
                              href={line.link}
                              className="flex items-center justify-between gap-3 py-2.5 text-sm text-ink-700 hover:text-purple-700"
                            >
                              <span className="min-w-0">{line.label}</span>
                              <span className="flex items-center gap-1 font-semibold tabular-nums text-ink-900">
                                {line.count}
                                <ChevronRight className="h-4 w-4 text-ink-300" aria-hidden />
                              </span>
                            </Link>
                          ) : (
                            <span className="flex items-center justify-between gap-3 py-2.5 text-sm text-ink-700">
                              <span className="min-w-0">{line.label}</span>
                              <span className="font-semibold tabular-nums text-ink-900">{line.count}</span>
                            </span>
                          )}
                        </li>
                      ))}
                      {items.map((item, index) =>
                        item.link ? (
                          <li key={`item-${index}`}>
                            <Link href={item.link} className="block truncate py-2 text-sm text-purple-700 hover:underline">
                              {item.title}
                            </Link>
                          </li>
                        ) : null
                      )}
                    </ul>
                  )}
                </section>
              );
            })}
          </div>

          {digest.coverage?.scope ? (
            <p className="mt-4 text-center text-xs text-ink-500">
              {digest.coverage.label}: {digest.coverage.scope}
            </p>
          ) : null}
        </>
      )}
    </>
  );
}
