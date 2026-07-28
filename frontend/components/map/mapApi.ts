/**
 * The two map reads, and the one place that turns filters into query keys for them.
 *
 * `searchFilterParams` is imported from the Search page's own filter module rather than reimagined
 * here — that is the whole point of requirement "one filter vocabulary". A place, a date range and
 * a set of record types mean exactly what they mean on `/search`, are spelled the same way on the
 * wire, and are answered by the same `build_record_wheres` on the server. The map cannot drift into
 * a private dialect because it has no code with which to do so.
 *
 * The workshop scope joins that vocabulary on the same terms: one `workshopIds` key, produced by the
 * shared `useWorkshopScope`, parsed by the shared `record_filters.resolve_workshop_ids`. The `level`
 * key is the one thing here the search box has no equivalent of, because it is a VIEW setting rather
 * than a filter — it changes how the same records are grouped, never which records they are.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import { searchFilterParams, type SearchFilters } from "@/components/search/SearchFilters";
import type { AdminLevel, MapPointsResponse, PointRecordsResponse } from "@/components/map/types";

export type MapQuery = {
  q?: string;
  filters: SearchFilters;
  /** Comma-joined workshop ids; undefined scopes to every workshop. */
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
  // The focus parameters are deliberately dropped: they say which pin to HIGHLIGHT, and sending
  // them to a route that lists a pin's contents would look like a filter that narrows it.
  //
  // `level` is deliberately KEPT. A point key names an administrative unit, and which records sit in
  // that unit is decided by the filters plus the level — so a panel that dropped the level would open
  // the wrong window for a capture pin and, at NATION level, list the wrong set entirely.
  const { focusType: _focusType, focusId: _focusId, ...rest } = params(query);
  return apiFetch<PointRecordsResponse>(
    `/map/points/${encodeURIComponent(pointKey)}/records${buildQuery(rest)}`,
    { signal }
  );
}
