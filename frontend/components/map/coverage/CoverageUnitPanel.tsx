"use client";

/**
 * One district (or state) opened from the map or the table: its counts, and the records behind
 * them, each a link to the record. Names only — the route never sends identity numbers or addresses.
 */

import Link from "next/link";
import { useEffect, useState } from "react";
import { ExternalLink, X } from "lucide-react";

import { fetchUnitRecords } from "@/components/map/coverage/coverageApi";
import { fyLabel, parentNote } from "@/components/map/coverage/coverageMath";
import type {
  CoverageBucket,
  CoverageQuery,
  CoverageUnit,
  UnitRecordsResponse
} from "@/components/map/coverage/coverageTypes";

const BUCKETS: Array<{ key: CoverageBucket; label: string }> = [
  { key: "workshops", label: "Workshops" },
  { key: "artisans", label: "Artisans" },
  { key: "products", label: "Products" },
  { key: "tools", label: "Tools" },
  { key: "media", label: "Media" }
];

export function unitTitle(unit: Pick<CoverageUnit, "state" | "district">): string {
  return unit.district ? `${unit.district}, ${unit.state}` : `${unit.state} (district not recorded)`;
}

export function CoverageUnitPanel({
  unit,
  query,
  parentName,
  onClose
}: {
  unit: CoverageUnit;
  query: CoverageQuery;
  parentName: string | null;
  onClose: () => void;
}) {
  const [data, setData] = useState<UnitRecordsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState<CoverageBucket | null>(null);
  const stateOnly = query.level === "STATE" || unit.districtLgd === null;

  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError(null);
    fetchUnitRecords(unit.stateLgd, query.level === "STATE" ? null : unit.districtLgd, query, {
      signal: controller.signal
    })
      .then(setData)
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Could not load records.");
      });
    return () => controller.abort();
  }, [unit.stateLgd, unit.districtLgd, query]);

  const more = async (bucket: CoverageBucket) => {
    const cursor = data?.buckets[bucket]?.nextCursor;
    if (!data || !cursor) return;
    setLoadingMore(bucket);
    try {
      const next = await fetchUnitRecords(unit.stateLgd, query.level === "STATE" ? null : unit.districtLgd, query, {
        bucket,
        cursor
      });
      const page = next.buckets[bucket];
      if (page) {
        setData((current) => {
          if (!current) return current;
          const existing = current.buckets[bucket];
          return {
            ...current,
            buckets: {
              ...current.buckets,
              [bucket]: { ...page, items: [...(existing?.items ?? []), ...page.items] }
            }
          };
        });
      }
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : "Could not load more.");
    } finally {
      setLoadingMore(null);
    }
  };

  const note = parentNote(parentName);

  return (
    <section className="panel p-4" aria-labelledby="coverage-unit-heading" data-testid="coverage-unit-panel">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id="coverage-unit-heading" className="font-display text-base font-bold text-ink-900">
            {stateOnly && query.level === "STATE" ? unit.state : unitTitle(unit)}
          </h2>
          {note ? <p className="mt-0.5 text-xs text-ink-500">{note}</p> : null}
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-ink-500 hover:bg-purple-50 hover:text-purple-700"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      <dl className="mt-3 grid grid-cols-3 gap-2">
        {[
          ["Workshops", unit.counts.workshops],
          ["Artisans", unit.counts.artisans],
          ["Crafts", unit.counts.crafts],
          ["Products", unit.counts.products],
          ["Tools", unit.counts.tools],
          ["Media", unit.counts.media]
        ].map(([label, value]) => (
          <div key={label} className="rounded-md border border-line-200 bg-surface-50 px-2.5 py-1.5">
            <dt className="text-[11px] font-medium uppercase tracking-wide text-ink-500">{label}</dt>
            <dd className="font-display text-lg font-bold text-ink-900">{value}</dd>
          </div>
        ))}
      </dl>

      {error ? <p className="mt-3 text-xs text-error-600">{error}</p> : null}
      {!data && !error ? <p className="mt-3 text-xs text-ink-500">Loading records…</p> : null}

      {data ? (
        <div className="mt-3 grid gap-3">
          {BUCKETS.map(({ key, label }) => {
            const bucket = data.buckets[key];
            if (!bucket || bucket.total === 0) return null;
            return (
              <div key={key}>
                <h3 className="text-xs font-semibold text-ink-700">
                  {label} <span className="text-ink-500">· {bucket.total}</span>
                </h3>
                <ul className="mt-1 grid gap-0.5">
                  {bucket.items.map((item) => (
                    <li key={item.id} className="flex items-baseline justify-between gap-2 text-sm">
                      <Link href={item.href} className="min-w-0 truncate text-purple-700 hover:underline dark:text-purple-300">
                        {item.title}
                      </Link>
                      {item.fy !== null ? (
                        <span className="shrink-0 text-[11px] text-ink-500">FY {fyLabel(item.fy)}</span>
                      ) : null}
                    </li>
                  ))}
                </ul>
                {bucket.nextCursor ? (
                  <button
                    type="button"
                    onClick={() => more(key)}
                    disabled={loadingMore === key}
                    className="mx-auto mt-1 block text-xs font-semibold text-purple-700 hover:underline disabled:opacity-60 dark:text-purple-300"
                  >
                    {loadingMore === key ? "Loading…" : `Show more (${bucket.total - bucket.items.length} left)`}
                  </button>
                ) : null}
              </div>
            );
          })}
          {BUCKETS.every(({ key }) => !data.buckets[key]?.total) ? (
            <p className="text-xs text-ink-500">No records under these filters.</p>
          ) : null}
          {data.peerHref ? (
            <a href={data.peerHref} className="field-button-secondary mx-auto w-fit" target="_blank" rel="noreferrer">
              Open in Field Repository <ExternalLink className="h-4 w-4" aria-hidden />
            </a>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
