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
 * the route, since reading the repository is open to every signed-in account.
 *
 * THE TWO LAYERS. Read the header of `backend/app/api/routes/map_points.py` before changing anything
 * here. In short: the GPS fixes say where the recording HAPPENED (one venue, in this corpus) and the
 * addresses say where the craft is FROM (a dozen districts across eight states), and showing either
 * one alone tells a lie about the other.
 *
 * NOTHING ON THIS MAP IS POPULATED BY HAND ANY MORE. The ORIGIN layer is built from the structured
 * address every record already carries — state, district, and the pin a researcher drops on the
 * subject's own place — so documenting a craft somewhere new plots it without a code change. The old
 * thirteen-town lookup table survives only as the last fallback for legacy rows that never had
 * anything but prose. See `services/geography` for the ladder and for why district positions are
 * learned from real pins rather than tabulated.
 *
 * THE THREE CONTROLS, and what each one is:
 *   * the filters — WHICH RECORDS. Shared verbatim with `/search`.
 *   * the workshops — WHICH RECORDS, again, from the same shared vocabulary.
 *   * the detail level — HOW THE SAME RECORDS ARE GROUPED. Nation / state / district. It is a view
 *     setting, not a filter, and it never changes a total.
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
import { ADMIN_LEVELS, type AdminLevel, type MapPointsResponse } from "@/components/map/types";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import {
  EMPTY_SEARCH_FILTERS,
  SearchFilterBar,
  filtersFromSearchParams,
  filtersToLinkParams,
  type SearchFilters
} from "@/components/search/SearchFilters";
import {
  useWorkshopScope,
  WorkshopScopeSelect,
  workshopScopeFromSearchParams
} from "@/components/WorkshopScopeSelect";
import { apiFetch, buildQuery } from "@/lib/api";

const SCOPE_COPY: Record<MapPointsResponse["scope"], string> = {
  all: "Everything in the repository",
  filtered: "The records matching your filters",
  record: "One record, shown in context"
};

/**
 * The detail control's wording. Each level says what it GROUPS BY and which borders it draws, because
 * "state" alone would leave a reader guessing whether it filters or aggregates.
 *
 * WHAT IS DRAWN. The outline is the international border — the official Government of India depiction,
 * shipped with the app (`components/map/indiaGeometry`) and verified point-in-polygon. State and
 * district borders are real geometry too, derived by `scripts/build_boundaries.py` from a published
 * district dataset and CLIPPED to that outline, so the national boundary is byte-identical at all three
 * levels rather than three slightly different edges from three datasets that disagree by ~2 km.
 *
 * The one real gap, and it is stated on screen rather than hidden: 43 of the 795 districts have no
 * border of their own in the published boundary data. Most were notified after it was published; three
 * of Delhi's thirteen are names the source does not use (it carries Shahdara, which LGD does not). Their
 * records still plot — the pin comes from the address, not from the border — and they sit inside the
 * parent district each was carved from, or their state where the parentage is not on record. See
 * `frontend/public/boundaries/manifest.json` and `services/district_lineage`.
 */
const LEVEL_COPY: Record<AdminLevel, { label: string; hint: string }> = {
  NATION: {
    label: "Nation",
    hint: "Every placed record as one point, inside the international border."
  },
  STATE: {
    label: "State",
    hint: "One point per state, with state borders drawn."
  },
  DISTRICT: {
    label: "District",
    hint: "One point per district, with district borders drawn — the finest unit an address can name."
  }
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

  /**
   * The workshop scope, seeded from the URL and otherwise defaulting to the most recent workshop —
   * the same control, the same default and the same wire format as the questionnaire's completion
   * matrix and the consolidated questionnaire, so a researcher moving between the three screens is
   * always looking at the same set of records.
   *
   * It is applied IMMEDIATELY rather than waiting for "Update map", unlike the text and filter
   * controls beside it. Those are typed and want a submit; this is a picker, and a picker that needed
   * a second button press to take effect reads as broken.
   */
  const scope = useWorkshopScope({
    initialWorkshopIds: workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()))
  });

  const [level, setLevel] = useState<AdminLevel>(() => {
    const requested = (searchParams.get("level") ?? "").toUpperCase() as AdminLevel;
    return (ADMIN_LEVELS as readonly string[]).includes(requested) ? requested : "DISTRICT";
  });

  const [data, setData] = useState<MapPointsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [hoveredKey, setHoveredKey] = useState<string | null>(null);

  /**
   * Which districts a borderless district borrows its outline from — 43 of the 795 have no border of
   * their own in the published boundary data.
   *
   * Fetched from the API rather than hard-coded, for the same reason the state list is: two
   * hand-copied tables of 43 rows is how the web and Android come to disagree about the same 43
   * districts. A failure is silent — this drives one explanatory sentence, and a map that cannot
   * explain a coarse outline is still a map.
   */
  const [lineage, setLineage] = useState<{ parents: Record<string, string[]>; stateFallback: string[] }>({
    parents: {},
    stateFallback: []
  });
  useEffect(() => {
    let live = true;
    apiFetch<{ parents: Record<string, string[]>; stateFallback?: string[] }>(
      "/reference/district-lineage"
    )
      .then((payload) => {
        if (live) {
          setLineage({ parents: payload.parents ?? {}, stateFallback: payload.stateFallback ?? [] });
        }
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);

  const query: MapQuery = useMemo(
    () => ({
      q: applied.q,
      filters: applied.filters,
      workshopIds: scope.queryValue,
      level,
      focusType,
      focusId
    }),
    [applied, scope.queryValue, level, focusType, focusId]
  );

  useEffect(() => {
    // Hold the first request until the workshop picker has settled on its default. Firing early would
    // draw the whole repository for a moment and then replace it with the scoped answer — two requests,
    // and a visible flash of the wrong map.
    if (scope.settling) return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    fetchMapPoints(query, controller.signal)
      .then((result) => {
        setData(result);
        // Opening straight onto the focused record's point saves a reader hunting for the ring.
        // Changing the level re-keys every pin, so the previous selection is dropped rather than left
        // pointing at a key that no longer exists.
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
  }, [query, scope.settling]);

  const apply = useCallback(() => setApplied({ q, filters }), [q, filters]);

  // Memoised because `borrowedOutlines` depends on it: `data?.points ?? []` mints a fresh empty array
  // on every render while data is loading, which would re-run that memo forever.
  const points = useMemo(() => data?.points ?? [], [data]);
  const selected = points.find((point) => point.key === selectedKey) ?? null;
  const focusKeys = data?.focus?.pointKeys ?? [];
  const summary = data?.summary;

  // Media has no place column of its own, so a place filter cannot narrow it — it is the one
  // filter in the shared vocabulary that reaches four buckets out of five. Saying so is the
  // alternative to a capture pin whose count quietly includes media the filter never touched.
  const placeFilterMissesMedia =
    Boolean(applied.filters.place.trim()) &&
    (applied.filters.types.length === 0 || applied.filters.types.includes("media"));

  /**
   * The districts ON THIS MAP that are borrowing a parent's outline.
   *
   * Derived from the drawn points rather than from the 43-row table, so the notice below appears only
   * when it is about something the reader can see. `point.state`/`point.district` are the canonical
   * names the lineage is keyed by, which is exactly why the server returns them.
   */
  const borrowedOutlines = useMemo(() => {
    const fallback = new Set(lineage.stateFallback);
    return points
      .filter((point) => {
        if (!point.district) return false;
        const key = `${point.state}|${point.district}`;
        return Boolean(lineage.parents[key]) || fallback.has(key);
      })
      .map((point) => point.label);
  }, [points, lineage]);

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
            href={`/search${buildQuery({
              ...filtersToLinkParams(applied.filters),
              q: applied.q || undefined,
              // The workshop scope travels with the link, so "browse as a list" lists the SAME records
              // the map is drawing rather than silently widening to the whole repository.
              workshopIds: scope.queryValue
            })}`}
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

        {/* The workshop scope and the detail level, side by side: WHICH records, and HOW they group. */}
        <div className="grid gap-4 rounded-md border border-line-200 bg-surface-50 p-3 md:grid-cols-2">
          <WorkshopScopeSelect scope={scope} label="Workshops on this map" />
          <div className="grid min-w-0 content-start gap-1.5">
            <span className="field-label">Detail</span>
            <div
              role="group"
              aria-label="How closely to group the records"
              className="inline-flex w-fit max-w-full overflow-hidden rounded-md border border-line-200 bg-card"
            >
              {ADMIN_LEVELS.map((option) => (
                <button
                  key={option}
                  type="button"
                  aria-pressed={level === option}
                  onClick={() => setLevel(option)}
                  className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                    level === option
                      ? "bg-purple-700 text-white"
                      : "text-ink-700 hover:bg-purple-50 hover:text-purple-700"
                  }`}
                >
                  {LEVEL_COPY[option].label}
                </button>
              ))}
            </div>
            <p className="text-[11px] leading-4 text-ink-500">{LEVEL_COPY[level].hint}</p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={apply} className="field-button">
            Update map
          </button>
          {applied.q ||
          applied.filters.place ||
          applied.filters.range !== "any" ||
          applied.filters.types.length ||
          scope.workshopIds.length ||
          level !== "DISTRICT" ? (
            <button
              type="button"
              className="text-xs font-semibold text-purple-700 hover:underline"
              onClick={() => {
                setQ("");
                setFilters(EMPTY_SEARCH_FILTERS);
                setApplied({ q: "", filters: EMPTY_SEARCH_FILTERS });
                scope.setWorkshopIds([]);
                setLevel("DISTRICT");
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
            // `inScope === false` is a real and reachable state, not an edge case: the map opens scoped
            // to the most recent workshop, so "show this record on the map" from anything older lands
            // here. It used to 404 the whole request; now the record is named, the reason is stated, and
            // the way out is one click.
            <div
              className={`mb-4 flex flex-wrap items-center gap-2 rounded-lg border px-4 py-3 ${
                data.focus.inScope === false
                  ? "border-amber-500/40 bg-amber-100"
                  : "border-purple-300 bg-purple-50"
              }`}
            >
              <span className="text-sm text-ink-700">
                Showing <span className="font-semibold text-ink-900">{data.focus.title}</span> in
                context
                {data.focus.place ? (
                  <span className="text-ink-500"> — recorded as “{data.focus.place}”</span>
                ) : null}
                .
                {data.focus.inScope === false ? (
                  <span className="text-amber-800">
                    {" "}
                    It falls outside your current filters, so no pin is ringed. Choose{" "}
                    <span className="font-semibold">All records</span> above to bring it in.
                  </span>
                ) : null}
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
                {level !== "NATION" ? (
                  <span className="inline-flex items-center gap-1.5">
                    <span className="h-px w-4 bg-ink-500" aria-hidden />
                    State borders
                  </span>
                ) : null}
                {level === "DISTRICT" ? (
                  <span className="inline-flex items-center gap-1.5">
                    <span className="h-px w-4 bg-ink-300" aria-hidden />
                    District borders
                  </span>
                ) : null}
                <span className="ml-auto inline-flex items-center gap-1.5 text-ink-300">
                  Outline: international border
                </span>
              </div>

              {points.length === 0 ? (
                <p className="px-2 py-12 text-center text-sm text-ink-500">
                  {scope.workshopIds.length
                    ? "No records with a mapped address in the chosen workshops. Widen the workshop scope, or choose All records."
                    : "No records with a mapped address match these filters."}
                </p>
              ) : (
                <IndiaMap
                  points={points}
                  level={data.level ?? level}
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
                  <p className="mt-0.5 text-xs text-ink-500">
                    Grouped by {LEVEL_COPY[data.level ?? level].label.toLowerCase()}. {scope.summary}
                  </p>
                  <dl className="mt-3 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-3">
                    <Stat label="Records matched" value={summary.records} />
                    <Stat
                      label={
                        (data.level ?? level) === "DISTRICT"
                          ? "Districts on the map"
                          : (data.level ?? level) === "STATE"
                            ? "States on the map"
                            : "Points on the map"
                      }
                      value={points.length}
                    />
                    <Stat
                      label="Placed by address"
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
                          carry no place column of their own, so they are placed only by the address on
                          the location they were captured at.
                        </>
                      ) : null}
                      {summary.captureTruncated ? (
                        <>
                          {" "}
                          More GPS fixes matched than this map will fold; the busiest are shown.
                        </>
                      ) : null}
                      {summary.clusterKilometres ? (
                        <> GPS fixes within {summary.clusterKilometres} km are drawn as one pin.</>
                      ) : null}
                    </span>
                  </p>

                  {/* HOW GOOD THE ADDRESSES ARE. The map is built from these fields now, so a coarse
                      pin is usually a district nobody filled in rather than a fault in the map — and
                      that is only actionable if the numbers are on screen. */}
                  {summary.address && summary.address.locations > 0 ? (
                    <div className="mt-3 rounded-md border border-line-200 bg-surface-50 px-2.5 py-2">
                      <p className="text-[10px] font-medium uppercase tracking-wide text-ink-500">
                        Address detail on the {summary.address.locations}{" "}
                        {summary.address.locations === 1 ? "location" : "locations"} in scope
                      </p>
                      <p className="mt-1 text-[11px] leading-4 text-ink-700">
                        {summary.address.withState} with a state · {summary.address.withDistrict} with a
                        district · {summary.address.withPincode} with a pincode ·{" "}
                        {summary.address.withSubjectPin} with a pin on the subject&rsquo;s place.
                      </p>
                      {summary.address.withDistrict < summary.address.locations ? (
                        <p className="mt-1 text-[11px] leading-4 text-ink-500">
                          Records with no district are drawn at their state&rsquo;s seat and say so.
                          Filling in the district on the record moves the pin.
                        </p>
                      ) : null}
                      {typeof summary.anchoredDistricts === "number" ? (
                        <p className="mt-1 text-[11px] leading-4 text-ink-500">
                          The map knows a position for {summary.anchoredDistricts}{" "}
                          {summary.anchoredDistricts === 1 ? "district" : "districts"}, learned from{" "}
                          {summary.anchorPins ?? 0} dropped {(summary.anchorPins ?? 0) === 1 ? "pin" : "pins"}.
                        </p>
                      ) : null}
                    </div>
                  ) : null}

                  {/* WHY A DISTRICT MIGHT HAVE NO OUTLINE OF ITS OWN. Shown only at DISTRICT level,
                      where it is the only level it can matter, and only when a district in view is
                      actually affected — a standing caveat about 43 districts nobody is looking at is
                      noise, and noise is what teaches a reader to skip the notices that do matter. */}
                  {(data.level ?? level) === "DISTRICT" && borrowedOutlines.length ? (
                    <p className="mt-2 rounded-md border border-line-200 bg-surface-50 px-2.5 py-2 text-[11px] leading-4 text-ink-500">
                      {borrowedOutlines.length === 1
                        ? `${borrowedOutlines[0]} has no outline of its own in the published boundary data.`
                        : `${borrowedOutlines.length} districts here have no outline of their own in the published boundary data.`}{" "}
                      The pins are placed from the address as usual. The border drawn around them is the
                      parent district each was carved from — or, where the parentage is not on record,
                      their state.
                    </p>
                  ) : null}

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
                  The same information as the map, at the same detail level. Choose a place to see the
                  records there.
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
