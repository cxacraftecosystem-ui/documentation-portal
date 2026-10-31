"use client";

import { useEffect, useRef } from "react";

/**
 * A list page's search, page, filters and scroll position, so that Back from a record's read-only
 * page lands exactly where the reader left the list: the same page, the same filters, the same row
 * on screen — never the first record of page 1 (owner, 1 Nov 2026).
 *
 * TWO HOMES, BOTH KEYED BY WHERE THE READER WAS.
 * - THE URL. The page, the query and the filters are written into the address bar (merged with
 *   whatever else is there: `?edit`, `?new`, `?return` are never touched) with
 *   `history.replaceState`, so the history entry Back returns to already says which page and which
 *   filters it showed, a reload keeps them, and a copied link reproduces the list. `recall()` reads
 *   the URL first.
 * - SESSION STORAGE. The scroll position is stored under the list's full URL (path + query), so
 *   Back to page 3 of one filter and Back to page 1 of another each land on their own row. The
 *   last state per list is also kept under the list's key, for arriving from the menu with a bare
 *   URL in the same tab. Session, not local: this is "where was I in this tab", not a preference.
 *   Every access is try/catch-wrapped — storage throws outright when blocked.
 *
 * URL parameters (`LIST_PARAMS`): `search` the applied search, `page` (only past 1), `workshop` / `craft`
 * / `artisan` the funnel (always all three, an empty value meaning "all"), and `f_<name>` for each
 * of a page's other filters that is set.
 */

const PREFIX = "field_repo_list:";
const SCROLL_PREFIX = "field_repo_scroll:";

export const LIST_PARAMS = { query: "search", page: "page", workshop: "workshop", craft: "craft", artisan: "artisan", filter: "f_" } as const;

/** What one list remembers. Every field is optional: a page stores what it has. */
export type ListMemory = {
  query?: string;
  applied?: string;
  page?: number;
  funnel?: { workshopId: string; craftId: string; artisanId: string };
  /** A page's other filters (a status, a kind, a toggle), by name. Strings only, so the shape stays checkable. */
  filters?: Record<string, string>;
  scrollY?: number;
};

/**
 * Reads a stored value back, accepting only the shape it wrote. A tampered or older entry yields
 * null rather than a half-valid filter. Pure, so it is tested without a browser.
 */
export function parseListMemory(raw: string | null | undefined): ListMemory | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  const source = parsed as Record<string, unknown>;
  const memory: ListMemory = {};
  if (typeof source.query === "string") memory.query = source.query;
  if (typeof source.applied === "string") memory.applied = source.applied;
  if (typeof source.page === "number" && Number.isInteger(source.page) && source.page >= 1) memory.page = source.page;
  if (typeof source.scrollY === "number" && Number.isFinite(source.scrollY) && source.scrollY >= 0) memory.scrollY = source.scrollY;
  const funnel = source.funnel as Record<string, unknown> | undefined;
  if (
    funnel &&
    typeof funnel === "object" &&
    typeof funnel.workshopId === "string" &&
    typeof funnel.craftId === "string" &&
    typeof funnel.artisanId === "string"
  ) {
    memory.funnel = { workshopId: funnel.workshopId, craftId: funnel.craftId, artisanId: funnel.artisanId };
  }
  const filters = source.filters;
  if (filters && typeof filters === "object" && !Array.isArray(filters)) {
    const kept: Record<string, string> = {};
    for (const [name, value] of Object.entries(filters as Record<string, unknown>)) {
      if (typeof value === "string") kept[name] = value;
    }
    memory.filters = kept;
  }
  return memory;
}

function isListParam(name: string): boolean {
  return (
    name === LIST_PARAMS.query ||
    name === LIST_PARAMS.page ||
    name === LIST_PARAMS.workshop ||
    name === LIST_PARAMS.craft ||
    name === LIST_PARAMS.artisan ||
    name.startsWith(LIST_PARAMS.filter)
  );
}

/**
 * The list's state written into a query string: every list parameter of `base` replaced, every
 * other parameter kept in its place. Pure.
 */
export function listMemoryToSearch(memory: Omit<ListMemory, "scrollY">, base: string | URLSearchParams = ""): string {
  const source = new URLSearchParams(base);
  const out = new URLSearchParams();
  for (const [name, value] of source) if (!isListParam(name)) out.append(name, value);
  const query = (memory.applied ?? memory.query ?? "").trim();
  if (query) out.set(LIST_PARAMS.query, query);
  if (memory.page && memory.page > 1) out.set(LIST_PARAMS.page, String(memory.page));
  if (memory.funnel) {
    out.set(LIST_PARAMS.workshop, memory.funnel.workshopId);
    out.set(LIST_PARAMS.craft, memory.funnel.craftId);
    out.set(LIST_PARAMS.artisan, memory.funnel.artisanId);
  }
  for (const [name, value] of Object.entries(memory.filters ?? {})) {
    if (value) out.set(`${LIST_PARAMS.filter}${name}`, value);
  }
  const text = out.toString();
  return text ? `?${text}` : "";
}

/**
 * A list's state read from a query string, or null when it carries no list parameter at all (a
 * bare URL: the page's own defaults, or the tab's last visit, decide). Pure.
 */
export function listMemoryFromSearch(search: string | URLSearchParams): ListMemory | null {
  const params = new URLSearchParams(search);
  let found = false;
  for (const name of params.keys()) if (isListParam(name)) found = true;
  if (!found) return null;
  const memory: ListMemory = { filters: {} };
  const query = params.get(LIST_PARAMS.query);
  memory.query = query ?? "";
  memory.applied = query ?? "";
  const page = Number(params.get(LIST_PARAMS.page) ?? "1");
  memory.page = Number.isInteger(page) && page >= 1 ? page : 1;
  if (params.has(LIST_PARAMS.workshop) || params.has(LIST_PARAMS.craft) || params.has(LIST_PARAMS.artisan)) {
    memory.funnel = {
      workshopId: params.get(LIST_PARAMS.workshop) ?? "",
      craftId: params.get(LIST_PARAMS.craft) ?? "",
      artisanId: params.get(LIST_PARAMS.artisan) ?? ""
    };
  }
  for (const [name, value] of params) {
    if (name.startsWith(LIST_PARAMS.filter) && name.length > LIST_PARAMS.filter.length) memory.filters![name.slice(LIST_PARAMS.filter.length)] = value;
  }
  return memory;
}

function read(key: string): ListMemory | null {
  try {
    return parseListMemory(window.sessionStorage.getItem(PREFIX + key));
  } catch {
    return null;
  }
}

function write(key: string, memory: ListMemory) {
  try {
    window.sessionStorage.setItem(PREFIX + key, JSON.stringify(memory));
  } catch {
    // Storage blocked or full — the list simply starts fresh next time.
  }
}

/** The scroll position stored for one URL (path + query), or null. */
export function readScroll(url: string): number | null {
  try {
    const raw = window.sessionStorage.getItem(SCROLL_PREFIX + url);
    const value = raw === null ? NaN : Number(raw);
    return Number.isFinite(value) && value >= 0 ? value : null;
  } catch {
    return null;
  }
}

export function writeScroll(url: string, y: number) {
  try {
    window.sessionStorage.setItem(SCROLL_PREFIX + url, String(Math.round(y)));
  } catch {
    // Storage blocked — Back lands at the top of the right page instead.
  }
}

/**
 * Scroll to `target` once the page is tall enough to reach it — rows, images and late panels can
 * still be growing the page for a moment after the rows arrive — giving up after about a second.
 */
export function restoreScroll(target: number) {
  if (target <= 0) return;
  let frames = 0;
  const step = () => {
    window.scrollTo(0, target);
    frames += 1;
    if (Math.abs(window.scrollY - target) > 2 && frames < 60) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

/**
 * Keeps the scroll position of the page at its current URL, and puts it back once `ready` (the
 * rows are drawn). For a list whose page and filters already live in the URL (the search page, the
 * questionnaire lists).
 */
export function useUrlScrollMemory(ready: boolean) {
  const home = useRef<string | null>(null);
  const restored = useRef(false);

  useEffect(() => {
    home.current = window.location.pathname;
    let frame = 0;
    const onScroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (window.location.pathname !== home.current) return;
        writeScroll(window.location.pathname + window.location.search, window.scrollY);
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);

  useEffect(() => {
    if (!ready || restored.current) return;
    restored.current = true;
    const target = readScroll(window.location.pathname + window.location.search);
    if (target) restoreScroll(target);
  }, [ready]);
}

/**
 * `recall()` reads the URL (else the tab's last visit to this list) the first time it is called and
 * returns that same answer for the life of the page — call it from an effect or a handler, never
 * during render: the server render has no URL state or storage, and a value read while rendering
 * would differ between the two. A page applies it at the moment it first knows its own defaults
 * (the funnel's first `onChange`, or its first load) and then hands its state through `state` every
 * render; from then on the address bar follows it. `ready` says the rows are on screen, the only
 * moment a scroll position means anything.
 */
export function useListMemory(key: string, state: Omit<ListMemory, "scrollY">, ready: boolean) {
  const recalled = useRef<ListMemory | null | undefined>(undefined);
  const latest = useRef<Omit<ListMemory, "scrollY">>({ ...state });
  /** The list's own path, and the URL the reader arrived on (the key its scroll was stored under). */
  const home = useRef<string | null>(null);
  const arrival = useRef<string | null>(null);
  const restored = useRef(false);

  function here(): boolean {
    return typeof window !== "undefined" && home.current !== null && window.location.pathname === home.current;
  }

  function recall(): ListMemory | null {
    if (recalled.current === undefined) {
      if (typeof window === "undefined") recalled.current = null;
      else {
        if (arrival.current === null) arrival.current = window.location.pathname + window.location.search;
        const fromUrl = listMemoryFromSearch(window.location.search);
        const stored = read(key);
        recalled.current = fromUrl ? { ...fromUrl, scrollY: readScroll(arrival.current) ?? undefined } : stored;
      }
    }
    return recalled.current;
  }

  // Kept current every render. The first write waits for `recall()` — writing before it would
  // replace the place being restored, in the address bar and in storage alike.
  useEffect(() => {
    latest.current = { ...state };
    if (recalled.current === undefined || !here()) return;
    const search = listMemoryToSearch(latest.current, window.location.search);
    if (search !== window.location.search) {
      window.history.replaceState(null, "", `${window.location.pathname}${search}${window.location.hash}`);
    }
    write(key, { ...latest.current, scrollY: window.scrollY });
  });

  useEffect(() => {
    home.current = window.location.pathname;
    if (arrival.current === null) arrival.current = window.location.pathname + window.location.search;
    let frame = 0;
    const onScroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        // A navigation to a record scrolls the NEW page; that is not this list's position.
        if (!here() || recalled.current === undefined) return;
        writeScroll(window.location.pathname + window.location.search, window.scrollY);
        write(key, { ...latest.current, scrollY: window.scrollY });
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, [key]);

  // Once, after the first page of rows is drawn: put the reader back where they were.
  useEffect(() => {
    if (!ready || restored.current) return;
    restored.current = true;
    const target = recall()?.scrollY ?? 0;
    if (target > 0) restoreScroll(target);
    // `recall` is stable in effect: it reads refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  return { recall };
}
