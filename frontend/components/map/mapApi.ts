import { apiFetch, buildQuery } from "@/lib/api";
import { searchFilterParams, type SearchFilters } from "@/components/search/SearchFilters";
import type { AdminLevel, MapPointsResponse, PointRecordsResponse } from "@/components/map/types";

export type MapQuery = {
  q?: string;
  filters: SearchFilters;
  workshopIds?: string;
  level?: AdminLevel;
  focusType?: string;
  focusId?: string;
};

function params({ q, filters, workshopIds, level, focusType, focusId }: MapQuery) {
  return {
    q: q?.trim() || undefined,
    ...searchFilterParams(filters),
    workshopIds: workshopIds || undefined,
    level: level || undefined,
    focusType: focusType || undefined,
    focusId: focusId || undefined
  };
}

export function fetchMapPoints(query: MapQuery, signal?: AbortSignal) {
  return apiFetch<MapPointsResponse>(`/map/points${buildQuery(params(query))}`, { signal });
}

export function fetchPointRecords(pointKey: string, query: MapQuery, signal?: AbortSignal) {
  const { focusType: _focusType, focusId: _focusId, ...rest } = params(query);
  return apiFetch<PointRecordsResponse>(
    `/map/points/${encodeURIComponent(pointKey)}/records${buildQuery(rest)}`,
    { signal }
  );
}
