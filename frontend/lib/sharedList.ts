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
