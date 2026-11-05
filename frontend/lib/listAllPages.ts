import { listResource } from "@/lib/api";
import type { PageResult } from "@/lib/types";

export const ALL_PAGES_SIZE = 100;

export async function listAllPages<T extends { id: string }>(
  path: string,
  params: Record<string, string | number | undefined | null>,
  fetchPage: (path: string, params: Record<string, string | number | undefined | null>) => Promise<PageResult<T>> = listResource
): Promise<PageResult<T>> {
  const pageSize = Number(params.pageSize ?? ALL_PAGES_SIZE) || ALL_PAGES_SIZE;
  const items: T[] = [];
  const seen = new Set<string>();
  let total = 0;
  for (let page = 1; ; page += 1) {
    const answer = await fetchPage(path, { ...params, page, pageSize });
    const rows = Array.isArray(answer?.items) ? answer.items : [];
    total = typeof answer?.total === "number" ? answer.total : total;
    let fresh = 0;
    for (const row of rows) {
      if (seen.has(row.id)) continue;
      seen.add(row.id);
      items.push(row);
      fresh += 1;
    }
    if (!fresh || rows.length < pageSize || items.length >= total) break;
  }
  return { items, total: Math.max(total, items.length), page: 1, pageSize: items.length, pages: 1 };
}
