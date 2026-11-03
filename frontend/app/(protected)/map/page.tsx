"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Crosshair, Info, MapPin } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { useRevealRow } from "@/components/hooks/useRevealRow";
import { IndiaMap } from "@/components/map/IndiaMap";
import { MapPlaceList } from "@/components/map/MapPlaceList";
import { MapPointPanel } from "@/components/map/MapPointPanel";
import { CoverageView } from "@/components/map/coverage/CoverageView";
import { fetchMapPoints, type MapQuery } from "@/components/map/mapApi";
import {
  ADMIN_LEVELS,
  type AdminLevel,
  type MapPointChild,
  type MapPointsResponse
} from "@/components/map/types";
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
import { ButtonRow } from "@/components/ui/ButtonRow";

const SCOPE_COPY: Record<MapPointsResponse["scope"], string> = {
  all: "All records",
  filtered: "The records matching your filters",
  record: "One record, shown in context"
};

const LEVEL_COPY: Record<AdminLevel, { label: string; hint: string }> = {
  NATION: {
    label: "Nation",
    hint: "All records as one point."
  },
  STATE: {
    label: "State",
    hint: "One point per state."
  },
  DISTRICT: {
    label: "District",
    hint: "One point per district."
  }
};

function ViewSwitch({ view }: { view: "places" | "coverage" }) {
  const options: Array<{ value: "places" | "coverage"; label: string; href: string }> = [
    { value: "places", label: "Places", href: "/map" },
    { value: "coverage", label: "Coverage", href: "/map?view=coverage" }
  ];
  return (
    <div
      role="group"
      aria-label="Map view"
      className="mb-4 inline-flex w-fit max-w-full overflow-hidden rounded-md border border-line-200 bg-card"
    >
      {options.map((option) => (
        <Link
          key={option.value}
          href={option.href}
          aria-current={view === option.value ? "page" : undefined}
          className={`px-4 py-2 text-sm font-medium transition-colors ${
            view === option.value
              ? "bg-purple-700 text-white"
              : "text-ink-700 hover:bg-purple-50 hover:text-purple-700"
          }`}
        >
          {option.label}
        </Link>
      ))}
    </div>
  );
}

export default function MapPage() {
  const searchParams = useSearchParams();
  if (searchParams.get("view") === "coverage") {
    return (
      <div>
        <PageHeader
          title="Where the work comes from"
          description="Which districts the records reach, and which they do not yet."
          icon={<MapPin className="h-5 w-5" aria-hidden />}
        />
        <ViewSwitch view="coverage" />
        <CoverageView />
      </div>
    );
  }
  return <PlacesView viewSwitch={<ViewSwitch view="places" />} />;
}

function PlacesView({ viewSwitch }: { viewSwitch: React.ReactNode }) {
  const searchParams = useSearchParams();

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

  const [expandedKeys, setExpandedKeys] = useState<ReadonlySet<string>>(() => new Set());

  const [pendingReveal, setPendingReveal] = useState<{ key: string; nonce: number } | null>(null);
  const requestReveal = useCallback((key: string) => {
    setPendingReveal((current) => ({ key, nonce: (current?.nonce ?? 0) + 1 }));
  }, []);

  const [drillTarget, setDrillTarget] = useState<{ key: string; level: AdminLevel } | null>(null);

  const { registerRow, containerRef: cardsRef, reveal, flashKey } = useRevealRow();

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
    if (scope.settling) return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    fetchMapPoints(query, controller.signal)
      .then((result) => {
        setData(result);
        setSelectedKey(result.focus?.pointKeys[0] ?? null);
        setExpandedKeys(new Set());
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

  const points = useMemo(() => data?.points ?? [], [data]);
  const selected = points.find((point) => point.key === selectedKey) ?? null;
  const focusKeys = data?.focus?.pointKeys ?? [];
  const summary = data?.summary;

  const placeFilterMissesMedia =
    Boolean(applied.filters.place.trim()) &&
    (applied.filters.types.length === 0 || applied.filters.types.includes("media"));

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

  const handleSelect = useCallback(
    (key: string, source: "map" | "list" = "map") => {
      const wasSelected = selectedKey === key;
      setSelectedKey(wasSelected ? null : key);
      if (wasSelected) return;
      const point = points.find((candidate) => candidate.key === key);
      if (point?.children?.length) {
        setExpandedKeys((current) => new Set(current).add(key));
      }
      if (source === "map") requestReveal(key);
    },
    [points, selectedKey, requestReveal]
  );

  const toggleExpanded = useCallback((key: string) => {
    setExpandedKeys((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const handleDrillDown = useCallback((child: MapPointChild) => {
    const target = child.level;
    if (!target) return;
    setDrillTarget({ key: child.key, level: target });
    setLevel(target);
  }, []);

  useEffect(() => {
    if (!drillTarget) return;
    if (level !== drillTarget.level || error) {
      setDrillTarget(null);
      return;
    }
    if (!data || (data.level ?? level) !== drillTarget.level) return;
    setDrillTarget(null);
    if (!(data.points ?? []).some((point) => point.key === drillTarget.key)) return;
    setSelectedKey(drillTarget.key);
    requestReveal(drillTarget.key);
  }, [drillTarget, data, error, level, requestReveal]);

  useEffect(() => {
    if (!pendingReveal) return;
    reveal(pendingReveal.key);
    setPendingReveal(null);
  }, [pendingReveal, reveal]);

  return (
    <div>
      <PageHeader
        title="Where the work comes from"
        description="Where each craft is from, and where it was recorded."
        icon={<MapPin className="h-5 w-5" aria-hidden />}
        actions={
          <Link
            href={`/search${buildQuery({
              ...filtersToLinkParams(applied.filters),
              q: applied.q || undefined,
              workshopIds: scope.queryValue
            })}`}
            className="field-button-secondary"
          >
            Browse as a list
          </Link>
        }
      />

      {viewSwitch}

      <div className="mb-4 grid gap-3">
        <SearchInput
          value={q}
          onChange={setQ}
          onSubmit={apply}
          placeholder="Search artisans, products, tools, media…"
        />
        <SearchFilterBar value={filters} onChange={setFilters} className="" />

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

        <ButtonRow align="start">
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
              className="text-xs font-semibold text-purple-700 dark:text-purple-300 hover:underline"
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
        </ButtonRow>
      </div>

      {error ? (
        <EmptyState title="The map could not be loaded" body={error} />
      ) : loading && !data ? (
        <section className="panel p-6 text-sm text-ink-500">Placing the records…</section>
      ) : !data ? null : (
        <>
          {data.focus ? (
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
                    Outside your filters — choose <span className="font-semibold">All records</span>.
                  </span>
                ) : null}
              </span>
              <Link href="/map" className="text-xs font-semibold text-purple-700 hover:underline">
                Clear
              </Link>
            </div>
          ) : null}

          <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,23rem)]">
            <section
              aria-label="Map of India showing where the documented records come from"
              tabIndex={0}
              className="panel min-w-0 overflow-hidden p-3 sm:p-4 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain"
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
                <span className="ml-auto inline-flex items-center gap-1.5 text-ink-500">
                  Outline: international border
                </span>
              </div>

              {points.length === 0 ? (
                <p className="px-2 py-12 text-center text-sm text-ink-500">
                  {scope.workshopIds.length
                    ? "No mapped records in these workshops."
                    : "No mapped records match."}
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

              {points.length ? (
                <p className="mt-2 text-[11px] leading-4 text-ink-500">
                  Choose a pin to find it in the list.
                </p>
              ) : null}
            </section>

            <div
              ref={cardsRef as React.RefObject<HTMLDivElement>}
              tabIndex={0}
              role="region"
              aria-label="Places and records"
              className="grid min-w-0 grid-cols-[minmax(0,1fr)] content-start gap-4 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain lg:pr-1"
            >
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
                      A record can count in both.
                      {summary.originExcludes.length ? (
                        <>
                          {" "}
                          <span className="capitalize">{summary.originExcludes.join(", ")}</span>{" "}
                          are shown where captured.
                        </>
                      ) : null}
                      {summary.captureTruncated ? (
                        <>
                          {" "}
                          Only the busiest GPS fixes are shown.
                        </>
                      ) : null}
                      {summary.clusterKilometres ? (
                        <> GPS fixes within {summary.clusterKilometres} km are drawn as one pin.</>
                      ) : null}
                    </span>
                  </p>

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
                          Records without a district sit at the state capital.
                        </p>
                      ) : null}
                      {typeof summary.anchoredDistricts === "number" ? (
                        <p className="mt-1 text-[11px] leading-4 text-ink-500">
                          The map can place {summary.anchoredDistricts}{" "}
                          {summary.anchoredDistricts === 1 ? "district" : "districts"} from the{" "}
                          {summary.anchorPins ?? 0} {(summary.anchorPins ?? 0) === 1 ? "pin" : "pins"} people have dropped.
                        </p>
                      ) : null}
                    </div>
                  ) : null}

                  {(data.level ?? level) === "DISTRICT" && borrowedOutlines.length ? (
                    <p className="mt-2 rounded-md border border-line-200 bg-surface-50 px-2.5 py-2 text-[11px] leading-4 text-ink-500">
                      {borrowedOutlines.length === 1
                        ? `${borrowedOutlines[0]} has no boundary data;`
                        : `${borrowedOutlines.length} districts have no boundary data;`}{" "}
                      the parent district or state is drawn instead.
                    </p>
                  ) : null}

                  {placeFilterMissesMedia ? (
                    <p className="mt-2 rounded-md bg-amber-100/50 px-2.5 py-2 text-[11px] leading-4 text-amber-800">
                      Place filters don’t apply to media.
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
                  Numbered to match the pins. Choose a place to see its records
                  {data.childLevel
                    ? `, or open a row for the ${data.childLevel.toLowerCase()}s inside it`
                    : ""}
                  .
                </p>
                {points.length === 0 && data.unplaced.length === 0 ? (
                  <p className="text-xs text-ink-500">Nothing to place.</p>
                ) : (
                  <MapPlaceList
                    points={points}
                    unplaced={data.unplaced}
                    selectedKey={selectedKey}
                    focusKeys={focusKeys}
                    hoveredKey={hoveredKey}
                    childLevel={data.childLevel ?? null}
                    expandedKeys={expandedKeys}
                    flashKey={flashKey}
                    registerRow={registerRow}
                    onSelect={(key) => handleSelect(key, "list")}
                    onHover={setHoveredKey}
                    onToggleExpanded={toggleExpanded}
                    onDrillDown={handleDrillDown}
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
