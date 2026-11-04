import type { PageResult } from "@/lib/types";

export const LIST_PAGE_CEILING = 100;

export type ListCut = {
  noun: string;
  loaded: number;
  total: number;
};

export function listCut<T>(result: PageResult<T>, noun: string): ListCut | null {
  return cutOf(result.items.length, result.total, noun);
}

export function cutOf(loaded: number, total: number, noun: string): ListCut | null {
  const known = Number.isFinite(total) ? total : loaded;
  if (known <= loaded) return null;
  return { noun, loaded, total: known };
}

export type CutReach = "none" | "pager" | "search";

export function cappedListNotice(cut: ListCut | null, reach: CutReach = "none"): string {
  if (!cut) return "";
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be loaded. Try again.`;
  }
  if (reach === "pager") {
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — use the pager to reach the rest, which are not searched by the box above.`;
  }
  if (reach === "search") {
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — type to search all ${cut.total}.`;
  }
  return `Showing ${cut.loaded} of ${cut.total} ${cut.noun}. To find another, use Browse records from the menu.`;
}

export function mergeById<T extends { id: string }>(previous: readonly T[], incoming: readonly T[]): T[] {
  if (incoming.length === 0) return previous as T[];
  const seen = new Set(previous.map((row) => row.id));
  const added = incoming.filter((row) => !seen.has(row.id));
  return added.length === 0 ? (previous as T[]) : [...previous, ...added];
}
