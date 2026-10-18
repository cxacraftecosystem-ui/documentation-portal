/**
 * The coverage routes, typed. Every call goes through `apiFetch`; nothing here reaches a map tile
 * server or any third party — the coverage view draws only bundled geometry.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import { coverageParams } from "@/components/map/coverage/coverageMath";
import type {
  CoverageBucket,
  CoverageQuery,
  CoverageResponse,
  UnitRecordsResponse,
  UnmatchedResponse
} from "@/components/map/coverage/coverageTypes";

export function fetchCoverage(query: CoverageQuery, signal?: AbortSignal) {
  return apiFetch<CoverageResponse>(`/map/coverage${buildQuery(coverageParams(query))}`, { signal });
}

export function fetchUnitRecords(
  stateLgd: number,
  districtLgd: number | null,
  query: CoverageQuery,
  options: { bucket?: CoverageBucket; cursor?: string | null; signal?: AbortSignal } = {}
) {
  const { stateLgd: _scope, ...rest } = query;
  const params = {
    ...coverageParams({ ...rest, stateLgd: null }),
    bucket: options.bucket,
    cursor: options.cursor ?? undefined
  };
  return apiFetch<UnitRecordsResponse>(
    `/map/coverage/units/${stateLgd}/${districtLgd ?? 0}/records${buildQuery(params)}`,
    { signal: options.signal }
  );
}

export function fetchUnmatched(signal?: AbortSignal) {
  return apiFetch<UnmatchedResponse>("/map/coverage/unmatched", { signal });
}

export function createAlias(body: {
  rawState: string | null;
  rawDistrict: string | null;
  stateLgd: number;
  districtLgd: number | null;
}) {
  return apiFetch<{ id: string }>("/map/coverage/aliases", {
    method: "POST",
    body: JSON.stringify(body)
  });
}
