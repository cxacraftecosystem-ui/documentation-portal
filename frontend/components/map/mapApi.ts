/**
 * The two map reads, and the one place that turns filters into query keys for them.
 *
 * `searchFilterParams` is imported from the Search page's own filter module rather than reimagined
 * here — that is the whole point of requirement "one filter vocabulary". A place, a date range and
 * a set of record types mean exactly what they mean on `/search`, are spelled the same way on the
 * wire, and are answered by the same `build_record_wheres` on the server. The map cannot drift into
 * a private dialect because it has no code with which to do so.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import { searchFilterParams, type SearchFilters } from "@/components/search/SearchFilters";
import type { MapPointsResponse, PointRecordsResponse } from "@/components/map/types";

export type MapQuery = {
  q?: string;
  filters: SearchFilters;
  focusType?: string;
  focusId?: string;
};

function params({ q, filters, focusType, focusId }: MapQuery) {
  return {
    q: q?.trim() || undefined,
    ...searchFilterParams(filters),
    focusType: focusType || undefined,
    focusId: focusId || undefined
  };
}

export function fetchMapPoints(query: MapQuery, signal?: AbortSignal) {
  return apiFetch<MapPointsResponse>(`/map/points${buildQuery(params(query))}`, { signal });
}

export function fetchPointRecords(pointKey: string, query: MapQuery, signal?: AbortSignal) {
  // The focus parameters are deliberately dropped: they say which pin to HIGHLIGHT, and sending
  // them to a route that lists a pin's contents would look like a filter that narrows it.
  const { focusType: _focusType, focusId: _focusId, ...rest } = params(query);
  return apiFetch<PointRecordsResponse>(
    `/map/points/${encodeURIComponent(pointKey)}/records${buildQuery(rest)}`,
    { signal }
  );
}
