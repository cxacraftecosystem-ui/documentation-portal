export type SortKey = "relevance" | "name" | "recorded" | "added" | "modified" | "size" | "duration" | "type" | "workshop" | "artisan" | "craft" | "status" | "folder";

export type SortDir = "asc" | "desc";

export type SortChoice = { key: SortKey; dir: SortDir };

export type SortList = "tree" | "transcripts-list" | "browse" | "browse-media" | "search-unified";

export const SORT_KEYS: readonly SortKey[] = ["relevance", "name", "recorded", "added", "modified", "size", "duration", "type", "workshop", "artisan", "craft", "status", "folder"];

export const SORT_LABEL: Record<SortKey, string> = {
  relevance: "Best match",
  name: "Name",
  recorded: "Date recorded",
  added: "Date added",
  modified: "Last modified",
  size: "Size",
  duration: "Duration",
  type: "Type",
  workshop: "Workshop",
  artisan: "Artisan",
  craft: "Craft",
  status: "Review status",
  folder: "Folder"
};

const ALPHA = { asc: "A–Z", desc: "Z–A" };
const TIME = { desc: "Newest", asc: "Oldest" };

export const SORT_DIRECTIONS: Record<SortKey, Partial<Record<SortDir, string>>> = {
  relevance: { desc: "Best first" },
  name: ALPHA,
  recorded: TIME,
  added: TIME,
  modified: TIME,
  size: { desc: "Largest", asc: "Smallest" },
  duration: { desc: "Longest", asc: "Shortest" },
  type: ALPHA,
  workshop: ALPHA,
  artisan: ALPHA,
  craft: ALPHA,
  status: ALPHA,
  folder: ALPHA
};

export const DEFAULT_DIR: Record<SortKey, SortDir> = {
  relevance: "desc",
  name: "asc",
  recorded: "desc",
  added: "desc",
  modified: "desc",
  size: "desc",
  duration: "desc",
  type: "asc",
  workshop: "asc",
  artisan: "asc",
  craft: "asc",
  status: "asc",
  folder: "asc"
};

export const LIST_DEFAULT: Record<SortList, SortChoice> = {
  tree: { key: "name", dir: "asc" },
  "transcripts-list": { key: "folder", dir: "asc" },
  browse: { key: "added", dir: "desc" },
  "browse-media": { key: "added", dir: "desc" },
  "search-unified": { key: "relevance", dir: "desc" }
};

export function isSortKey(value: unknown): value is SortKey {
  return typeof value === "string" && (SORT_KEYS as readonly string[]).includes(value);
}

export function directionsOf(key: SortKey): SortDir[] {
  const words = SORT_DIRECTIONS[key];
  return (["asc", "desc"] as SortDir[]).filter((dir) => Boolean(words[dir])).sort((a, b) => (a === DEFAULT_DIR[key] ? -1 : b === DEFAULT_DIR[key] ? 1 : 0));
}

export function parseSort(raw: unknown): SortChoice | null {
  if (raw && typeof raw === "object") {
    const value = raw as { key?: unknown; dir?: unknown };
    if (isSortKey(value.key) && (value.dir === "asc" || value.dir === "desc") && SORT_DIRECTIONS[value.key][value.dir]) return { key: value.key, dir: value.dir };
    return null;
  }
  if (typeof raw !== "string") return null;
  const [key, dir] = raw.split(":");
  if (!isSortKey(key) || (dir !== "asc" && dir !== "desc") || !SORT_DIRECTIONS[key][dir]) return null;
  return { key, dir };
}

export function sortText(choice: SortChoice): string {
  return `${choice.key}:${choice.dir}`;
}

export function sameSort(a: SortChoice | null, b: SortChoice | null): boolean {
  return Boolean(a && b && a.key === b.key && a.dir === b.dir);
}

export function sortParam(choice: SortChoice | null, list: SortList): string | undefined {
  if (!choice || sameSort(choice, LIST_DEFAULT[list])) return undefined;
  return sortText(choice);
}

export function sortWords(choice: SortChoice): string {
  return `${SORT_LABEL[choice.key]} · ${SORT_DIRECTIONS[choice.key][choice.dir] ?? ""}`.trim();
}

export function availableKeys(sorts: readonly unknown[] | null | undefined): SortKey[] {
  if (!Array.isArray(sorts)) return [];
  const known = new Set(sorts.filter(isSortKey));
  return SORT_KEYS.filter((key) => known.has(key));
}

export function effectiveSort(remembered: SortChoice | null, sorts: readonly unknown[] | null | undefined, list: SortList): SortChoice {
  const keys = availableKeys(sorts);
  if (remembered && (keys.length === 0 || keys.includes(remembered.key))) return remembered;
  return LIST_DEFAULT[list];
}

export function withKey(current: SortChoice, key: SortKey): SortChoice {
  if (current.key === key) return current;
  return { key, dir: DEFAULT_DIR[key] };
}

export function treeLevel(path: string): string {
  const parts = path.split("/").filter(Boolean);
  return `tree:${parts[0] ?? "root"}:${parts.length}`;
}

export function sortStorageKey(viewer: string, level: string): string {
  return `dataExplorer.sort.${viewer || "anon"}.${level}`;
}

export function readSort(viewer: string, level: string): SortChoice | null {
  try {
    if (typeof window === "undefined") return null;
    return parseSort(window.localStorage.getItem(sortStorageKey(viewer, level)));
  } catch {
    return null;
  }
}

export function writeSort(viewer: string, level: string, choice: SortChoice | null, list: SortList): void {
  try {
    if (typeof window === "undefined") return;
    const key = sortStorageKey(viewer, level);
    if (!choice || sameSort(choice, LIST_DEFAULT[list])) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, sortText(choice));
  } catch {
    return;
  }
}
