"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

import { LoadFailed, Skeleton } from "@/components/dataExplorer/explorerUi";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { fetchOverview } from "@/lib/dataExplorer/api";
import type { CoverageState, FolderVisit, WorkshopOverview as Overview } from "@/lib/dataExplorer/contract";
import { formatBytes } from "@/lib/dataExplorer/plan";
import { completeness, percentLabel, sortArtisans, type OverviewSort } from "@/lib/dataExplorer/explorerView";
import type { QueryParams } from "@/lib/dataExplorer/wire";

const SQUARE: Record<CoverageState, string> = {
  complete: "bg-purple-700",
  partial: "bg-purple-300",
  missing: "border border-line-200 bg-card"
};

const SQUARE_WORD: Record<CoverageState, string> = { complete: "complete", partial: "partly recorded", missing: "missing" };

function Card({ label, big, small }: { label: string; big: string; small: React.ReactNode }) {
  return (
    <div className="grid gap-0.5 rounded-md border border-line-200 bg-card p-3">
      <span className="text-xs text-ink-500">{label}</span>
      <span className="font-display text-2xl font-extrabold tabular-nums text-ink-900">{big}</span>
      <span className="text-xs text-ink-500">{small}</span>
    </div>
  );
}

export function WorkshopOverview({ path, extra, visit, onOpen, onShowNew }: { path: string; extra: QueryParams; visit: FolderVisit | null; onOpen: (path: string) => void; onShowNew: () => void }) {
  const [answer, setAnswer] = useState<{ key: string; overview: Overview | null; error: string | null } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [sort, setSort] = useState<OverviewSort>("least");
  const extraKey = JSON.stringify(extra);
  const key = `${path}\u0000${extraKey}\u0000${attempt}`;
  useEffect(() => {
    const abort = new AbortController();
    fetchOverview(path, JSON.parse(extraKey) as QueryParams, abort.signal)
      .then((overview) => {
        if (!abort.signal.aborted) setAnswer({ key, overview, error: null });
      })
      .catch((error) => {
        if (!abort.signal.aborted) setAnswer({ key, overview: null, error: error instanceof Error && error.message ? error.message : "The overview didn't load." });
      });
    return () => abort.abort();
  }, [path, extraKey, key]);
  const state = answer?.key === key ? answer : null;
  const overview = state?.overview ?? (answer?.overview?.path === path ? answer.overview : null);
  const artisans = useMemo(() => sortArtisans(overview?.artisans ?? [], sort), [overview, sort]);
  if (state?.error) return <LoadFailed title="The workshop overview didn't load" onRetry={() => setAttempt((value) => value + 1)}>{state.error}</LoadFailed>;
  if (!overview) {
    if (state) return null;
    return (
      <div className="grid grid-cols-2 gap-2.5 lg:grid-cols-5" aria-hidden>
        {[0, 1, 2, 3, 4].map((tile) => (
          <Skeleton key={tile} className="h-24" />
        ))}
      </div>
    );
  }
  const totals = overview.totals;
  const fresh = visit && visit.previousVisitAt ? visit.newFiles : null;
  return (
    <section aria-label="Workshop overview" className="grid gap-4" data-testid="workshop-overview">
      <div className="grid grid-cols-2 gap-2.5 lg:grid-cols-5">
        <Card label="Files" big={totals.files.toLocaleString("en-IN")} small={formatBytes(totals.bytes)} />
        <Card label="Recorded fields" big={percentLabel(totals.fields)} small={`${totals.fields.filled.toLocaleString("en-IN")} of ${totals.fields.total.toLocaleString("en-IN")}`} />
        <Card label="Transcripts" big={totals.transcripts.total.toLocaleString("en-IN")} small={`${totals.transcripts.ready} ready · ${totals.transcripts.translating} translating · ${totals.transcripts.failed} failed`} />
        <Card label="Review" big={`${totals.review.approved} / ${totals.review.total}`} small={`approved · ${totals.review.pending} pending`} />
        <Card
          label="New since you looked"
          big={fresh === null ? "-" : `+${fresh.toLocaleString("en-IN")}`}
          small={
            fresh === null ? (
              "First visit"
            ) : (
              <>
                files · {visit?.newTranscripts ?? 0} transcripts
                {fresh > 0 ? (
                  <>
                    {" · "}
                    <button type="button" className="font-semibold text-purple-700 hover:underline dark:text-purple-300" onClick={onShowNew}>
                      Show
                    </button>
                  </>
                ) : null}
              </>
            )
          }
        />
      </div>
      {overview.artisans.length ? (
        <div className="panel overflow-hidden">
          <div className="flex flex-wrap items-center gap-3 border-b border-line-200 px-4 py-3">
            <h3 className="mr-auto font-display text-base font-bold text-ink-900">Artisans · what&apos;s been captured</h3>
            <span className="flex items-center gap-3 text-xs text-ink-500" aria-hidden>
              <span className="flex items-center gap-1">
                <span className={`h-3 w-3 rounded-[3px] ${SQUARE.complete}`} />
                Complete
              </span>
              <span className="flex items-center gap-1">
                <span className={`h-3 w-3 rounded-[3px] ${SQUARE.partial}`} />
                Partial
              </span>
              <span className="flex items-center gap-1">
                <span className={`h-3 w-3 rounded-[3px] ${SQUARE.missing}`} />
                Missing
              </span>
            </span>
            <div className="w-48">
              <SearchableSelect
                ariaLabel="Sort artisans"
                value={sort}
                onChange={(value) => setSort(value as OverviewSort)}
                options={[
                  { value: "least", label: "Least complete first" },
                  { value: "most", label: "Most complete first" },
                  { value: "name", label: "By name" }
                ]}
              />
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase tracking-wide text-ink-500">
                <tr>
                  <th scope="col" className="px-4 py-2.5 font-semibold">
                    Artisan
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    Sections
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    Fields
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    Media
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    Transcripts
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    Review
                  </th>
                  <th scope="col" className="px-4 py-2.5">
                    <span className="sr-only">Open</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {artisans.map((artisan) => (
                  <tr key={artisan.id} className="align-middle">
                    <td className="px-4 py-3">
                      <strong className="block text-ink-900">{artisan.name}</strong>
                      {artisan.craft ? <span className="text-xs text-ink-500">{artisan.craft}</span> : null}
                    </td>
                    <td className="px-3 py-3">
                      <div className="flex flex-wrap gap-1">
                        {artisan.sections.map((section) => {
                          const label = overview.sections.find((candidate) => candidate.key === section.key)?.label ?? section.key;
                          const title = `${label}: ${SQUARE_WORD[section.state]}${section.missing.length ? `. Still to record: ${section.missing.join("; ")}` : ""}`;
                          const look = `block h-5 w-5 rounded-[4px] pointer-coarse:h-7 pointer-coarse:w-7 ${SQUARE[section.state]}`;
                          return section.href ? (
                            <Link key={section.key} href={section.href} title={title} aria-label={title} className={look} />
                          ) : (
                            <button key={section.key} type="button" title={title} aria-label={title} onClick={() => onOpen(artisan.path)} className={look} />
                          );
                        })}
                      </div>
                    </td>
                    <td className="px-3 py-3">
                      <div className="flex items-center gap-2">
                        <ProgressBar percent={completeness(artisan.fields) * 100} className="h-1.5 w-20" aria-hidden />
                        <span className="tabular-nums text-ink-700">
                          {artisan.fields.filled} / {artisan.fields.total}
                        </span>
                      </div>
                    </td>
                    <td className="px-3 py-3 tabular-nums text-ink-700">
                      {artisan.media.files} · {formatBytes(artisan.media.bytes)}
                    </td>
                    <td className="px-3 py-3 tabular-nums text-ink-700">{artisan.transcripts}</td>
                    <td className="px-3 py-3">
                      {artisan.review ? (
                        <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${artisan.review === "APPROVED" ? "bg-success-100 text-success-600" : "bg-amber-100 text-amber-800"}`}>
                          {artisan.review.charAt(0) + artisan.review.slice(1).toLowerCase().replace(/_/g, " ")}
                        </span>
                      ) : (
                        <span className="text-ink-500">-</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button type="button" className="min-h-9 font-semibold text-purple-700 hover:underline dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => onOpen(artisan.path)}>
                        Open
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="border-t border-line-200 px-4 py-2.5 text-xs text-ink-500">Tap a square to open that section. Missing squares list what&apos;s still to record.{overview.truncated ? " Showing the first artisans only." : ""}</p>
        </div>
      ) : null}
    </section>
  );
}
