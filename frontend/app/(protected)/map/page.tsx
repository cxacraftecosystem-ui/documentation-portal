"use client";

/**
 * WHERE THE WORK COMES FROM — the repository read as geography.
 *
 * WHY THIS ROUTE EXISTS AT THE TOP LEVEL. There are now three ways to read the whole corpus, and
 * they are peers rather than views of one another: `/search` reads it as a list, `/data` reads it as
 * a folder tree, and this reads it as a place. It could not live under either of the others.
 * `/data` is gated on `require_dataset_downloader`, so nesting the map there would hide it from most
 * of the people it is for; `/search` owns a list whose state is a page of results, and bolting a
 * second mode onto it would make one screen answer two questions. `/map` therefore sits beside them
 * with `/search`'s entitlement — any signed-in user — because it answers the same question under the
 * same rules. It is deliberately NOT in `lib/permissions`' ROUTE_GUARDS: there is nothing to gate at
 * the route, since every pin has already been filtered by `visibility_where` on the server, so a
 * volunteer and a professor open the same page and see different numbers on it.
 *
 * THE TWO LAYERS. Read the header of `backend/app/api/routes/map_points.py` before changing
 * anything here. In short: the GPS fixes say where the recording HAPPENED (one venue, in this
 * corpus), the place names say where the craft is FROM (a dozen towns across eight states), and
 * showing either one alone tells a lie about the other.
 */

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Crosshair, Info, MapPin } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { IndiaMap } from "@/components/map/IndiaMap";
import { MapPlaceList } from "@/components/map/MapPlaceList";
import { MapPointPanel } from "@/components/map/MapPointPanel";
import { fetchMapPoints, type MapQuery } from "@/components/map/mapApi";
import type { MapPointsResponse } from "@/components/map/types";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import {
  EMPTY_SEARCH_FILTERS,
  SearchFilterBar,
  filtersFromSearchParams,
  filtersToLinkParams,
  type SearchFilters
} from "@/components/search/SearchFilters";
import { buildQuery } from "@/lib/api";

const SCOPE_COPY: Record<MapPointsResponse["scope"], string> = {
  all: "Everything in the repository",
  filtered: "The records matching your filters",
  record: "One record, shown in context"
};

export default function MapPage() {
  const searchParams = useSearchParams();

  // The URL SEEDS the state, exactly as it does on the Search page, so a link from a record's
  // "show on map" arrives filtered and focused — and so the filters can then be changed without a
  // navigation throwing the other three away.
  const [q, setQ] = useState(() => searchParams.get("q") ?? "");
  const [filters, setFilters] = useState<SearchFilters>(() =>
    filtersFromSearchParams(new URLSearchParams(searchParams.toString()))
  );
  const [applied, setApplied] = useState<{ q: string; filters: SearchFilters }>(() => ({
    q: searchParams.get("q") ?? "",
    filters: filtersFromSearchParams(new URLSearchParams(searchParams.toString()))
  }));

  const focusType = searchParams.get("focusType") ?? undefined;
  const focusId = searchParams.get("focusId") ?? undefined;

  const [data, setData] = useState<MapPointsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [hoveredKey, setHoveredKey] = useState<string | null>(null);

  const query: MapQuery = useMemo(
    () => ({ q: applied.q, filters: applied.filters, focusType, focusId }),
    [applied, focusType, focusId]
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    fetchMapPoints(query, controller.signal)
      .then((result) => {
        setData(result);
        // Opening straight onto the focused record's point saves a reader hunting for the ring.
        setSelectedKey(result.focus?.pointKeys[0] ?? null);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : "The map could not be loaded.");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [query]);

  const apply = useCallback(() => setApplied({ q, filters }), [q, filters]);

  const points = data?.points ?? [];
  const selected = points.find((point) => point.key === selectedKey) ?? null;
  const focusKeys = data?.focus?.pointKeys ?? [];
  const summary = data?.summary;

  // Media has no place column of its own, so a place filter cannot narrow it — it is the one
  // filter in the shared vocabulary that reaches four buckets out of five. Saying so is the
  // alternative to a capture pin whose count quietly includes media the filter never touched.
  const placeFilterMissesMedia =
    Boolean(applied.filters.place.trim()) &&
    (applied.filters.types.length === 0 || applied.filters.types.includes("media"));

  const handleSelect = useCallback((key: string) => {
    setSelectedKey((current) => (current === key ? null : key));
  }, []);

  return (
    <div>
      <PageHeader
        title="Where the work comes from"
        description="Every documented record placed on the map twice over: where the craft is from, and where the recording was actually made. The two are not the same place, and the map says which is which."
        icon={<MapPin className="h-5 w-5" aria-hidden />}
        actions={
          <Link
            href={`/search${buildQuery({ ...filtersToLinkParams(applied.filters), q: applied.q || undefined })}`}
            className="field-button-secondary"
          >
            Browse as a list
          </Link>
        }
      />

      <div className="mb-4 grid gap-3">
        <SearchInput
          value={q}
          onChange={setQ}
          onSubmit={apply}
          placeholder="Search artisans, products, tools, media…"
        />
        <SearchFilterBar value={filters} onChange={setFilters} className="" />
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={apply} className="field-button">
            Update map
          </button>
          {applied.q || applied.filters.place || applied.filters.range !== "any" || applied.filters.types.length ? (
            <button
              type="button"
              className="text-xs font-semibold text-purple-700 hover:underline"
              onClick={() => {
                setQ("");
                setFilters(EMPTY_SEARCH_FILTERS);
                setApplied({ q: "", filters: EMPTY_SEARCH_FILTERS });
              }}
            >
              Show everything
            </button>
          ) : null}
        </div>
      </div>

      {error ? (
        <EmptyState title="The map could not be loaded" body={error} />
      ) : loading && !data ? (
        <section className="panel p-6 text-sm text-ink-500">Placing the records…</section>
      ) : !data ? null : (
        <>
          {data.focus ? (
            <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-purple-300 bg-purple-50 px-4 py-3">
              <span className="text-sm text-ink-700">
                Showing <span className="font-semibold text-ink-900">{data.focus.title}</span> in
                context
                {data.focus.place ? (
                  <span className="text-ink-500"> — recorded as “{data.focus.place}”</span>
                ) : null}
                .
              </span>
              <Link href="/map" className="text-xs font-semibold text-purple-700 hover:underline">
                Clear
              </Link>
            </div>
          ) : null}

          {/* `items-start` so the map panel is as tall as the map. Stretching it to match the list
              beside it left a column of empty white under the coastline taller than the map itself. */}
          <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
            <section
              aria-label="Map of India showing where the documented records come from"
              className="panel min-w-0 overflow-hidden p-3 sm:p-4 lg:sticky lg:top-24"
            >
              <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-ink-500">
                <span className="inline-flex items-center gap-1.5">
                  <span className="h-3 w-3 rounded-full bg-purple-700" aria-hidden />
                  Where the craft is from
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <span
                    className="h-3 w-3 rounded-full border-[3px] border-purple-700 bg-white"
                    aria-hidden
                  />
                  Where it was recorded
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <span
                    className="h-3 w-3 rounded-full border border-dashed border-purple-300 bg-purple-700/10"
                    aria-hidden
                  />
                  How far off the point could be
                </span>
              </div>

              {points.length === 0 ? (
                <p className="px-2 py-12 text-center text-sm text-ink-500">
                  No records with a place match these filters.
                </p>
              ) : (
                <IndiaMap
                  points={points}
                  focusKeys={focusKeys}
                  selectedKey={selectedKey}
                  hoveredKey={hoveredKey}
                  onSelect={handleSelect}
                  onHover={setHoveredKey}
                />
              )}
            </section>

            {/* `grid-cols-[minmax(0,1fr)]` rather than a bare `grid`. An implicit grid track takes
                its minimum from its items' min-content, and a record title is arbitrary text a
                researcher typed — one long filename with no spaces in the pin panel widened this
                column past its 22rem and pushed the whole page sideways. An explicit zero minimum
                is what stops any future content doing it again. */}
            <div className="grid min-w-0 grid-cols-[minmax(0,1fr)] content-start gap-4">
              {summary ? (
                <section className="panel p-4">
                  <h2 className="font-display text-sm font-bold text-ink-900">
                    {SCOPE_COPY[data.scope]}
                  </h2>
                  <dl className="mt-3 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-3">
                    <Stat label="Records matched" value={summary.records} />
                    <Stat label="Places on the map" value={points.length} />
                    <Stat
                      label="Placed by craft origin"
                      value={summary.originRecords}
                      icon={<MapPin className="h-3.5 w-3.5" aria-hidden />}
                    />
                    <Stat
                      label="Placed by GPS fix"
                      value={summary.captureRecords}
                      icon={<Crosshair className="h-3.5 w-3.5" aria-hidden />}
                    />
                  </dl>
                  <p className="mt-3 flex gap-2 text-[11px] leading-4 text-ink-500">
                    <Info className="mt-px h-3.5 w-3.5 shrink-0" aria-hidden />
                    <span>
                      The two counts overlap and do not add up: one record can be both from Bagru and
                      recorded at a workshop in Kharagpur.
                      {summary.originExcludes.length ? (
                        <>
                          {" "}
                          {/* The bucket names arrive lower-case from the API, and this is the start
                              of a sentence — "media carry no place" read as a broken line. */}
                          <span className="capitalize">{summary.originExcludes.join(", ")}</span>{" "}
                          carry no place of their own, so they only ever appear as a GPS fix.
                        </>
                      ) : null}
                      {summary.captureTruncated ? (
                        <>
                          {" "}
                          More GPS fixes matched than this map will fold; the busiest are shown.
                        </>
                      ) : null}
                    </span>
                  </p>
                  {placeFilterMissesMedia ? (
                    <p className="mt-2 rounded-md bg-amber-100/50 px-2.5 py-2 text-[11px] leading-4 text-amber-800">
                      A place filter cannot narrow media — a photograph has no place of its own — so
                      media here is every file matching your other filters.
                    </p>
                  ) : null}
                </section>
              ) : null}

              {selected ? (
                <MapPointPanel
                  point={selected}
                  query={query}
                  onClose={() => setSelectedKey(null)}
                />
              ) : null}

              <section className="panel p-4">
                <h2 className="font-display text-sm font-bold text-ink-900">
                  Every place, as a list
                </h2>
                <p className="mb-3 mt-1 text-xs text-ink-500">
                  The same information as the map. Choose a place to see the records there.
                </p>
                {points.length === 0 && data.unplaced.length === 0 ? (
                  <p className="text-xs text-ink-500">Nothing to place.</p>
                ) : (
                  <MapPlaceList
                    points={points}
                    unplaced={data.unplaced}
                    selectedKey={selectedKey}
                    focusKeys={focusKeys}
                    onSelect={handleSelect}
                    onHover={setHoveredKey}
                  />
                )}
              </section>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function Stat({
  label,
  value,
  icon
}: {
  label: string;
  value: number;
  icon?: React.ReactNode;
}) {
  return (
    <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
      <dt className="flex items-center gap-1.5 text-[11px] font-medium uppercase tracking-wide text-ink-500">
        {icon}
        {label}
      </dt>
      <dd className="mt-0.5 font-display text-xl font-bold text-ink-900">{value}</dd>
    </div>
  );
}
