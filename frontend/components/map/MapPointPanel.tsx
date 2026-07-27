"use client";

/**
 * What is behind one pin, and the way out of the map into the records.
 *
 * A map you cannot leave is an ornament, so every row here is a link to the record itself, at the
 * same destination the search results use. The records are fetched only when a pin is opened —
 * `/map/points` carries counts, not contents, because the aggregate is what draws the picture and
 * the contents are wanted for exactly one pin at a time.
 */

import Link from "next/link";
import { useEffect, useState } from "react";
import { ArrowUpRight, X } from "lucide-react";

import { fetchPointRecords, type MapQuery } from "@/components/map/mapApi";
import { RECORD_HREF, type MapPoint, type PointRecord } from "@/components/map/types";
import { StatusBadge } from "@/components/StatusBadge";
import { filtersToLinkParams } from "@/components/search/SearchFilters";
import { buildQuery } from "@/lib/api";

export function MapPointPanel({
  point,
  query,
  onClose
}: {
  point: MapPoint;
  query: MapQuery;
  onClose: () => void;
}) {
  const [items, setItems] = useState<PointRecord[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setItems(null);
    setError(null);
    fetchPointRecords(point.key, query, controller.signal)
      .then((result) => {
        setItems(result.items);
        setTruncated(result.truncated);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : "These records could not be loaded.");
      });
    return () => controller.abort();
    // `query` is rebuilt on every render of the parent, so depending on the object itself would
    // refetch forever. The two things that actually change the answer are the pin and the filters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [point.key, JSON.stringify(query.filters), query.q]);

  // A place resolves from several spellings, so "everything here" is best asked of Browse records
  // using the place the researcher would recognise rather than a key only this map understands.
  const browseHref = `/search${buildQuery({
    ...filtersToLinkParams(query.filters),
    place: point.layer === "ORIGIN" ? (point.spellings?.[0] ?? point.label) : undefined
  })}`;

  return (
    <section
      aria-labelledby="map-point-heading"
      className="min-w-0 overflow-hidden rounded-lg border border-line-200 bg-white p-4 shadow-sm"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id="map-point-heading" className="font-display text-base font-bold text-ink-900">
            {point.label}
          </h2>
          <p className="mt-0.5 text-xs text-ink-500">{point.region}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label={`Close ${point.label}`}
          className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-line-200 text-ink-500 transition-colors hover:border-purple-300 hover:text-purple-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      {error ? (
        <p className="mt-3 rounded-md bg-error-100 px-3 py-2 text-xs text-error-600">{error}</p>
      ) : items === null ? (
        <p className="mt-3 text-xs text-ink-500">Loading the records here…</p>
      ) : items.length === 0 ? (
        <p className="mt-3 text-xs text-ink-500">No records to list at this point.</p>
      ) : (
        <>
          <ul className="mt-3 grid grid-cols-[minmax(0,1fr)] gap-1">
            {items.map((item) => (
              <li key={`${item.type}-${item.id}`} className="min-w-0">
                <Link
                  href={RECORD_HREF[item.type](item.id)}
                  className="flex items-center gap-2 rounded-md px-2 py-1.5 text-xs transition-colors hover:bg-purple-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
                >
                  <span className="w-16 shrink-0 text-[10px] font-medium uppercase tracking-wide text-ink-500">
                    {item.type}
                  </span>
                  <span className="min-w-0 flex-1 truncate font-medium text-ink-900">
                    {item.title}
                  </span>
                  {item.status ? (
                    <span className="shrink-0 whitespace-nowrap">
                      <StatusBadge status={item.status} />
                    </span>
                  ) : null}
                  <ArrowUpRight className="h-3.5 w-3.5 shrink-0 text-ink-300" aria-hidden />
                </Link>
              </li>
            ))}
          </ul>
          {truncated ? (
            <p className="mt-2 text-[11px] text-ink-500">
              Showing the most recent of each type. Open Browse records for the full list.
            </p>
          ) : null}
        </>
      )}

      <Link
        href={browseHref}
        className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-purple-700 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
      >
        Open these in Browse records
        <ArrowUpRight className="h-3.5 w-3.5" aria-hidden />
      </Link>
    </section>
  );
}
