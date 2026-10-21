/**
 * `listResource` with in-flight de-duplication: callers asking for the SAME list (same path, same
 * query) while one request is out share its answer. Nothing is kept once it settles, so the data is
 * exactly as fresh as a plain `listResource` call. The record forms mount two artisan pickers in one
 * commit (`useServerSearchList` and `useCraftAndArtisanOptions`), which both read the same page.
 */

import { buildQuery, listResource } from "@/lib/api";
import type { PageResult } from "@/lib/types";

const inflight = new Map<string, Promise<unknown>>();

export function sharedListResource<T>(
  path: string,
  params: Record<string, string | number | undefined | null>,
  fetchList: (path: string, params: Record<string, string | number | undefined | null>) => Promise<PageResult<T>> = listResource
): Promise<PageResult<T>> {
  const key = `${path}${buildQuery(params)}`;
  const existing = inflight.get(key) as Promise<PageResult<T>> | undefined;
  if (existing) return existing;
  const request = fetchList(path, params).finally(() => {
    if (inflight.get(key) === request) inflight.delete(key);
  });
  inflight.set(key, request);
  return request;
}
