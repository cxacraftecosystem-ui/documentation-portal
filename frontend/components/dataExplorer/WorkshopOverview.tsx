"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";

import { LoadFailed, Skeleton } from "@/components/dataExplorer/explorerUi";
import { fetchOverview } from "@/lib/dataExplorer/api";
import type { CoverageState, FolderVisit, WorkshopOverview as Overview } from "@/lib/dataExplorer/contract";
import { formatBytes } from "@/lib/dataExplorer/plan";
import { completeness, percentLabel, reviewDetail, reviewFraction, reviewTone, reviewWord, sortArtisans, transcriptDetail, type OverviewSort, type ReviewTone } from "@/lib/dataExplorer/explorerView";
import type { QueryParams } from "@/lib/dataExplorer/wire";

const SQUARE: Record<CoverageState, string> = {
  complete: "bg-purple-700 dark:bg-purple-400",
  partial: "bg-progress-track",
  missing: "border border-red-300 bg-error-100 dark:border-[#b91c1c] dark:bg-[#4a1717]"
};

const REVIEW_INK: Record<ReviewTone, string> = { done: "text-success-600 dark:text-green-300", waiting: "text-amber-800 dark:text-amber-300", none: "text-ink-500" };

const SQUARE_WORD: Record<CoverageState, string> = { complete: "complete", partial: "partly recorded", missing: "missing" };

const SORTS: { value: OverviewSort; label: string; short: string }[] = [
  { value: "least", label: "Least complete first", short: "least complete" },
  { value: "most", label: "Most complete first", short: "most complete" },
  { value: "name", label: "By name", short: "by name" }
];

function barTone(share: number): string {
  if (share >= 1) return "bg-success-600 dark:bg-green-400";
  if (share < 0.5) return "bg-red-700 dark:bg-red-400";
  if (share < 0.8) return "bg-purple-500";
  return "bg-purple-700 dark:bg-purple-300";
}

function Card({ label, big, small, fresh = false }: { label: string; big: string; small: React.ReactNode; fresh?: boolean }) {
  return (
    <div className={`grid content-start rounded-lg border p-4 ${fresh ? "border-[#d6b6fb] bg-purple-100 text-purple-800 dark:border-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "border-line-200 bg-card text-ink-900"}`}>
      <span className={`text-xs ${fresh ? "" : "text-ink-500"}`}>{label}</span>
      <span className="font-display text-[28px] leading-[34px] font-extrabold tabular-nums">{big}</span>
      <span className={`text-xs ${fresh ? "" : "text-ink-500"}`}>{small}</span>
    </div>
  );
}

function SortMenu({ value, onChange }: { value: OverviewSort; onChange: (value: OverviewSort) => void }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) setOpen(false);
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("pointerdown", away);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("pointerdown", away);
      window.removeEventListener("keydown", key);
    };
  }, [open]);
  const current = SORTS.find((sort) => sort.value === value) ?? SORTS[0];
  return (
    <div className="relative" ref={box}>
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`Sort artisans: ${current.short}`}
        onClick={() => setOpen((shown) => !shown)}
        className="min-h-8 rounded-md border border-line-200 bg-card px-3.5 text-[13px] text-ink-900 hover:border-purple-300 pointer-coarse:min-h-11"
      >
        Sort: {current.short} ▾
      </button>
      {open ? (
        <ul role="listbox" aria-label="Sort artisans" className="absolute right-0 top-10 z-20 grid w-52 gap-0.5 rounded-md border border-line-200 bg-card p-1 shadow-md">
          {SORTS.map((sort) => (
            <li
              key={sort.value}
              role="option"
              aria-selected={sort.value === value}
              tabIndex={0}
              onClick={() => {
                onChange(sort.value);
                setOpen(false);
              }}
              onKeyDown={(event) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                onChange(sort.value);
                setOpen(false);
              }}
              className={`flex min-h-10 cursor-pointer items-center rounded-sm px-3 text-sm ${sort.value === value ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-900 hover:bg-purple-50 dark:hover:bg-purple-950/40"}`}
            >
              {sort.label}
            </li>
          ))}
        </ul>
      ) : null}
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
      <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-5" aria-hidden>
        {[0, 1, 2, 3, 4].map((tile) => (
          <Skeleton key={tile} className="h-[98px]" />
        ))}
      </div>
    );
  }
  const totals = overview.totals;
  const fresh = visit && visit.previousVisitAt ? visit.newFiles : null;
  const sectionsLabel = overview.sections.length > 1 ? `Sections ${overview.sections[0].label.charAt(0)}–${overview.sections[overview.sections.length - 1].label.charAt(0)}` : "Sections";
  const lettered = overview.sections.every((section) => /^[A-Z]$/.test(section.label.charAt(0))) && overview.sections.length <= 26;
  return (
    <section aria-label="Workshop overview" className="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-[18px]" data-testid="workshop-overview">
      <div className="grid grid-cols-2 gap-3.5 sm:grid-cols-3 lg:grid-cols-5">
        <Card label="Files" big={totals.files.toLocaleString("en-IN")} small={formatBytes(totals.bytes)} />
        <Card label="Recorded fields" big={percentLabel(totals.fields)} small={`${totals.fields.filled.toLocaleString("en-IN")} of ${totals.fields.total.toLocaleString("en-IN")}`} />
        <Card label="Transcripts" big={totals.transcripts.total.toLocaleString("en-IN")} small={transcriptDetail(totals.transcripts)} />
        <Card label="Review" big={reviewFraction(totals.review)} small={reviewDetail(totals.review)} />
        <Card
          fresh={fresh !== null}
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
                    <button type="button" className="font-semibold underline underline-offset-2" onClick={onShowNew}>
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
        <>
          <div className="panel min-w-0 overflow-hidden shadow-none">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line-200 px-4 py-3.5">
              <h3 className="text-base font-bold text-ink-900">Artisans · what&apos;s been captured</h3>
              <div className="flex flex-wrap items-center gap-3 text-xs text-ink-500">
                <span className="flex items-center gap-1" aria-hidden>
                  <span className={`h-3 w-3 rounded-full ${SQUARE.complete}`} />
                  Complete
                </span>
                <span className="flex items-center gap-1" aria-hidden>
                  <span className={`h-3 w-3 rounded-full ${SQUARE.partial}`} />
                  Partial
                </span>
                <span className="flex items-center gap-1" aria-hidden>
                  <span className={`h-3 w-3 rounded-full ${SQUARE.missing}`} />
                  Missing
                </span>
                <SortMenu value={sort} onChange={setSort} />
              </div>
            </div>
            <div className="overflow-x-auto [contain:inline-size]">
              <table className="w-full text-left text-[13px]">
                <thead className="text-xs text-ink-500">
                  <tr className="border-b border-line-200">
                    <th scope="col" className="px-3 py-2.5 font-medium">
                      Artisan
                    </th>
                    <th scope="col" className="px-3 py-2.5 font-medium">
                      {lettered ? sectionsLabel : "Sections"}
                    </th>
                    <th scope="col" className="px-3 py-2.5 font-medium">
                      Fields
                    </th>
                    <th scope="col" className="px-3 py-2.5 font-medium">
                      Media
                    </th>
                    <th scope="col" className="px-3 py-2.5 font-medium">
                      Transcripts
                    </th>
                    <th scope="col" className="px-3 py-2.5 font-medium">
                      Review
                    </th>
                    <th scope="col" className="px-3 py-2.5">
                      <span className="sr-only">Open</span>
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line-200">
                  {artisans.map((artisan) => {
                    const share = completeness(artisan.fields);
                    return (
                      <tr key={artisan.id} className="align-middle">
                        <td className="px-3 py-2.5">
                          <strong className="block font-semibold text-ink-900">{artisan.name}</strong>
                          {artisan.craft ? <span className="text-xs text-ink-500">{artisan.craft}</span> : null}
                        </td>
                        <td className="px-3 py-2.5">
                          <div className="flex gap-[3px]">
                            {artisan.sections.map((section) => {
                              const label = overview.sections.find((candidate) => candidate.key === section.key)?.label ?? section.key;
                              const title = `${label}: ${SQUARE_WORD[section.state]}${section.missing.length ? `. Still to record: ${section.missing.join("; ")}` : ""}`;
                              const look = `block h-[22px] w-[22px] shrink-0 rounded-[6px] pointer-coarse:h-7 pointer-coarse:w-7 ${SQUARE[section.state]}`;
                              return section.href ? (
                                <Link key={section.key} href={section.href} title={title} aria-label={title} className={look} />
                              ) : (
                                <button key={section.key} type="button" title={title} aria-label={title} onClick={() => onOpen(artisan.path)} className={look} />
                              );
                            })}
                          </div>
                        </td>
                        <td className="px-3 py-2.5">
                          <div className="flex items-center gap-2">
                            <span className="block h-1.5 w-[120px] overflow-hidden rounded-full bg-surface-200" aria-hidden>
                              <span className={`block h-full ${barTone(share)}`} style={{ width: `${Math.round(share * 100)}%` }} />
                            </span>
                            <span className="tabular-nums text-ink-900">
                              {artisan.fields.filled} / {artisan.fields.total}
                            </span>
                          </div>
                        </td>
                        <td className="px-3 py-2.5 tabular-nums text-ink-900">
                          {artisan.media.files} · {formatBytes(artisan.media.bytes)}
                        </td>
                        <td className="px-3 py-2.5 tabular-nums text-ink-900" title={transcriptDetail(artisan.transcripts)}>
                          {artisan.transcripts.total.toLocaleString("en-IN")}
                        </td>
                        <td className="px-3 py-2.5" title={reviewDetail(artisan.review)} data-review="">
                          {artisan.review.total > 0 ? (
                            <>
                              <span className={`block font-semibold ${REVIEW_INK[reviewTone(artisan.review)]}`}>{reviewWord(artisan.review)}</span>
                              <span className="block text-xs tabular-nums text-ink-500">{reviewFraction(artisan.review)}</span>
                            </>
                          ) : (
                            <span className="text-ink-500">-</span>
                          )}
                        </td>
                        <td className="px-3 py-2.5">
                          <button type="button" className="font-semibold text-purple-700 underline underline-offset-2 dark:text-purple-300 pointer-coarse:min-h-11" onClick={() => onOpen(artisan.path)}>
                            Open
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
          <p className="text-xs text-ink-500">
            Tap a square to jump to that section. Missing squares list what&apos;s still to record, so the team knows before leaving the village.{overview.truncated ? " Showing the first artisans only." : ""}
          </p>
        </>
      ) : null}
    </section>
  );
}
