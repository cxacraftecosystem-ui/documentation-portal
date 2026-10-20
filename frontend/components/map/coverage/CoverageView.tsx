"use client";

/**
 * COVERAGE — which districts the documented work reaches, and which it has not reached yet.
 *
 * The Places view plots records where they are; this view colours each district by how many
 * workshops, artisans or records it holds, lists the shared crafts nobody has documented and the
 * districts with no workshop, and opens any district's records. Every filter lives in the URL
 * (`?view=coverage&craftCode=..&fy=..`), so a view can be linked and Back restores it.
 *
 * The district polygons are fetched only here (`shapeGeometry.loadShapes`); nothing in this folder
 * imports a mapping library or calls a tile server. The figures come from `GET /map/coverage`,
 * which folds the same records `/map/points` reads under the same visibility rule.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { fetchCoverage } from "@/components/map/coverage/coverageApi";
import { CoverageFilters } from "@/components/map/coverage/CoverageFilters";
import { CoverageGapsPanel } from "@/components/map/coverage/CoverageGapsPanel";
import { CoverageLegend } from "@/components/map/coverage/CoverageLegend";
import { CoverageMap } from "@/components/map/coverage/CoverageMap";
import {
  coverageHref,
  coverageQueryFromParams,
  EMPTY_COVERAGE_QUERY,
  METRICS,
  metricFromParams,
  polygonFills,
  quantileBreaks,
  registerFromCoverage,
  type RegisterState
} from "@/components/map/coverage/coverageMath";
import { CoverageTable, unitKey } from "@/components/map/coverage/CoverageTable";
import type {
  CoverageMetric,
  CoverageQuery,
  CoverageResponse,
  CoverageUnit
} from "@/components/map/coverage/coverageTypes";
import { CoverageUnitPanel } from "@/components/map/coverage/CoverageUnitPanel";
import { UnmatchedPlacesPanel } from "@/components/map/coverage/UnmatchedPlacesPanel";
import { loadShapes, type DistrictShape } from "@/components/map/shapeGeometry";
import { isAdmin } from "@/lib/permissions";

export const DEPICTION_CAPTION =
  "External boundaries of India as depicted by Survey of India. District boundaries are indicative.";

const ZERO_COUNTS = { workshops: 0, artisans: 0, crafts: 0, products: 0, tools: 0, media: 0 };

function isWholeRegister(body: CoverageResponse): boolean {
  return body.filters.level === "DISTRICT" && body.filters.stateLgd === null;
}

export function CoverageView() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const admin = isAdmin(user) && adminMode;

  const query = useMemo(
    () => coverageQueryFromParams(new URLSearchParams(searchParams.toString())),
    [searchParams]
  );
  const metric = metricFromParams(new URLSearchParams(searchParams.toString()));

  const [data, setData] = useState<CoverageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [shapes, setShapes] = useState<DistrictShape[] | null>(null);
  const [shapesError, setShapesError] = useState(false);
  const [register, setRegister] = useState<RegisterState[]>([]);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [placeholder, setPlaceholder] = useState<CoverageUnit | null>(null);
  const [reload, setReload] = useState(0);

  const navigate = useCallback(
    (next: CoverageQuery, nextMetric: CoverageMetric = metric) => {
      router.replace(coverageHref(next, nextMetric), { scroll: false });
    },
    [router, metric]
  );

  useEffect(() => {
    let live = true;
    loadShapes()
      .then((rows) => {
        if (live) setShapes(rows);
      })
      .catch(() => {
        if (live) setShapesError(true);
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    fetchCoverage(query, controller.signal)
      .then((body) => {
        setData(body);
        if (isWholeRegister(body)) setRegister(registerFromCoverage(body));
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Coverage could not be loaded.");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [query, reload]);

  // The state picker and the alias pickers need the whole register; a narrowed first view asks once.
  useEffect(() => {
    if (register.length || !data || isWholeRegister(data)) return;
    const controller = new AbortController();
    fetchCoverage(EMPTY_COVERAGE_QUERY, controller.signal)
      .then((body) => setRegister(registerFromCoverage(body)))
      .catch(() => {});
    return () => controller.abort();
  }, [register.length, data]);

  useEffect(() => {
    setSelectedKey(null);
    setPlaceholder(null);
  }, [query]);

  const units = useMemo(() => data?.units ?? [], [data]);
  const level = data?.filters.level ?? query.level;

  const names = useMemo(() => {
    const map = new Map<number, string>();
    for (const state of register) for (const district of state.districts) map.set(district.lgd, district.name);
    for (const row of data?.gaps.districtsWithoutWorkshop.byState ?? []) {
      for (const district of row.without) if (district.lgd !== null) map.set(district.lgd, district.name);
    }
    for (const unit of units) if (unit.districtLgd !== null && unit.district) map.set(unit.districtLgd, unit.district);
    return map;
  }, [register, data, units]);

  const stateNames = useMemo(() => {
    const map = new Map<number, string>();
    for (const state of register) map.set(state.lgd, state.name);
    for (const unit of units) map.set(unit.stateLgd, unit.state);
    return map;
  }, [register, units]);

  const fills = useMemo(() => polygonFills(units, level, metric, shapes ?? []), [units, metric, level, shapes]);

  const breaks = useMemo(() => quantileBreaks([...fills.values()].map((fill) => fill.value)), [fills]);

  const noWorkshop = useMemo(() => {
    const set = new Set<number>();
    if (!data) return set;
    if (level === "STATE") {
      const states = new Set(data.gaps.statesWithoutWorkshop.map((state) => state.lgd));
      for (const shape of shapes ?? []) if (states.has(shape.stateLgd)) set.add(shape.lgd);
      return set;
    }
    for (const row of data.gaps.districtsWithoutWorkshop.byState) {
      for (const district of row.without) if (district.lgd !== null) set.add(district.lgd);
    }
    return set;
  }, [data, level, shapes]);

  const selected = useMemo(() => {
    if (!selectedKey) return null;
    return units.find((unit) => unitKey(unit) === selectedKey) ?? (placeholder && unitKey(placeholder) === selectedKey ? placeholder : null);
  }, [units, selectedKey, placeholder]);

  const selectPolygon = useCallback(
    (lgd: number) => {
      const shape = shapes?.find((row) => row.lgd === lgd);
      if (!shape) return;
      if (level === "STATE") {
        const unit = units.find((row) => row.stateLgd === shape.stateLgd);
        const chosen: CoverageUnit = unit ?? {
          stateLgd: shape.stateLgd,
          districtLgd: null,
          state: stateNames.get(shape.stateLgd) ?? "",
          district: null,
          polygon: "STATE",
          parentLgd: null,
          counts: ZERO_COUNTS,
          peer: null,
          craftCodes: [],
          clusters: []
        };
        setPlaceholder(unit ? null : chosen);
        setSelectedKey(unitKey(chosen));
        return;
      }
      const unit = units.find((row) => row.districtLgd === lgd) ?? units.find((row) => row.parentLgd === lgd);
      const chosen: CoverageUnit = unit ?? {
        stateLgd: shape.stateLgd,
        districtLgd: lgd,
        state: stateNames.get(shape.stateLgd) ?? "",
        district: names.get(lgd) ?? null,
        polygon: "OWN",
        parentLgd: null,
        counts: ZERO_COUNTS,
        peer: null,
        craftCodes: [],
        clusters: []
      };
      setPlaceholder(unit ? null : chosen);
      setSelectedKey(unitKey(chosen));
    },
    [shapes, units, level, names, stateNames]
  );

  const showParent = useMemo(() => [...fills.values()].some((fill) => fill.hostsChild), [fills]);
  const metricLabel = METRICS.find((option) => option.value === metric)?.label ?? "Workshops";

  return (
    <div className="grid gap-4">
      <CoverageFilters
        query={query}
        metric={metric}
        catalogue={data?.catalogue ?? null}
        states={register}
        onChange={(next) => navigate(next)}
        onMetric={(next) => navigate(query, next)}
      />

      {error ? (
        <EmptyState title="Coverage could not be loaded" body={error} />
      ) : loading && !data ? (
        <section className="panel p-6 text-sm text-ink-500">Counting the records…</section>
      ) : !data ? null : (
        <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,23rem)]">
          <section
            aria-label="Coverage map of India"
            className="panel min-w-0 overflow-hidden p-3 sm:p-4 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain"
          >
            <div className="mb-3">
              <CoverageLegend breaks={breaks} metricLabel={metricLabel} showParent={showParent} />
            </div>
            {shapes ? (
              <CoverageMap
                shapes={shapes}
                fills={fills}
                breaks={breaks}
                noWorkshop={noWorkshop}
                markers={data.markers}
                selectedLgd={selected?.districtLgd ?? null}
                stateLgd={query.stateLgd}
                names={names}
                onSelectPolygon={selectPolygon}
              />
            ) : (
              <p className="px-2 py-12 text-center text-sm text-ink-500">
                {shapesError ? "District shapes could not be loaded. The table lists every figure." : "Drawing districts…"}
              </p>
            )}
            <p className="mt-2 text-[11px] leading-4 text-ink-500" data-testid="coverage-caption">
              {DEPICTION_CAPTION}
            </p>
            <div className="mt-1 grid gap-0.5 text-[11px] leading-4 text-ink-500">
              {data.truncated ? <p>Showing the first 20,000 records of each kind.</p> : null}
              {data.markersTruncated ? <p>Showing 500 workshop markers.</p> : null}
              {data.unmatched.count ? (
                <p>
                  {data.unmatched.count} {data.unmatched.count === 1 ? "record has" : "records have"} a place no
                  district matches.
                </p>
              ) : null}
            </div>
          </section>

          <div className="grid min-w-0 grid-cols-[minmax(0,1fr)] content-start gap-4 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain lg:pr-1">
            {selected ? (
              <CoverageUnitPanel
                unit={selected}
                query={query}
                parentName={selected.polygon === "PARENT" ? names.get(selected.parentLgd ?? -1) ?? null : null}
                onClose={() => setSelectedKey(null)}
              />
            ) : null}
            <CoverageGapsPanel gaps={data.gaps} />
            <section className="panel p-4" aria-labelledby="coverage-table-heading">
              <h2 id="coverage-table-heading" className="mb-2 font-display text-sm font-bold text-ink-900">
                Every place with a record
              </h2>
              <CoverageTable
                units={units}
                level={level}
                selectedKey={selectedKey}
                onSelect={(unit) => {
                  setPlaceholder(null);
                  setSelectedKey(unitKey(unit));
                }}
              />
            </section>
            {admin ? <UnmatchedPlacesPanel register={register} onChanged={() => setReload((value) => value + 1)} /> : null}
          </div>
        </div>
      )}
    </div>
  );
}
