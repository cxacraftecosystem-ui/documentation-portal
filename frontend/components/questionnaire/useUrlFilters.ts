"use client";

import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { rememberFilterLabel } from "@/components/questionnaire/FilterBar";
import type { ChipFilter, DisplayChip } from "@/lib/questionnaire/chips";
import { applyChip, cleanFilters, FILTER_KEYS, filtersFromQuery, filtersToQuery, type Filters } from "@/lib/questionnaire/filters";

/**
 * The filters of a list page, kept in its URL (owner, 31 Oct 2026): a refresh, Back, a shared link
 * and a return from a record all land on the same filtered list. The query is the canonical one
 * (`filtersToQuery`), so the URL reads exactly as the API call it makes. Any other parameter the
 * page keeps (a tab, `edit=`) is left as it is.
 *
 * Read from `window.location` after mount, not `useSearchParams`, so no page needs a Suspense
 * boundary for it; `ready` is false until then, and a page waits for it before its first fetch.
 */
export function useUrlFilters(): { filters: Filters; setFilters: (next: Filters) => void; ready: boolean } {
  const router = useRouter();
  const pathname = usePathname();
  const [filters, setState] = useState<Filters>({});
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const read = () => setState(filtersFromQuery(window.location.search));
    read();
    setReady(true);
    window.addEventListener("popstate", read);
    return () => window.removeEventListener("popstate", read);
  }, []);

  const setFilters = useCallback(
    (next: Filters) => {
      const clean = cleanFilters(next);
      setState(clean);
      const params = new URLSearchParams(window.location.search);
      for (const key of FILTER_KEYS) params.delete(key);
      const rest = params.toString();
      const query = [filtersToQuery(clean), rest].filter(Boolean).join("&");
      router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
    },
    [router, pathname]
  );

  return { filters, setFilters, ready };
}

/** Where a chip tapped outside a list leads: the recordings list, filtered by it. */
export const RECORDINGS_HREF = "/questionnaire/recordings";

export function chipHref(filter: ChipFilter): string {
  const query = filtersToQuery(applyChip({}, filter));
  return query ? `${RECORDINGS_HREF}?${query}` : RECORDINGS_HREF;
}

/** The `onChip` of a screen that shows takes but lists nothing to filter (a take sheet, a record). */
export function useChipNavigator(): (filter: ChipFilter, chip?: DisplayChip) => void {
  const router = useRouter();
  return useCallback(
    (filter: ChipFilter, chip?: DisplayChip) => {
      if (chip) rememberFilterLabel(filter, chip.label);
      router.push(chipHref(filter));
    },
    [router]
  );
}

const PLACE_PREFIX = "qv2_list_place:";

/** Where a list was under one URL: how many "Show more" pages it had open, and the scroll. */
export type ListPlace = { pages: number; scrollY: number };

/** A stored place read back, accepting only the shape written. Pure. */
export function parseListPlace(raw: string | null | undefined): ListPlace | null {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<ListPlace>;
    const pages = Number(value?.pages);
    const scrollY = Number(value?.scrollY);
    if (!Number.isInteger(pages) || pages < 1 || !Number.isFinite(scrollY) || scrollY < 0) return null;
    return { pages: Math.min(pages, 50), scrollY };
  } catch {
    return null;
  }
}

function readPlace(url: string): ListPlace | null {
  try {
    return parseListPlace(window.sessionStorage.getItem(PLACE_PREFIX + url));
  } catch {
    return null;
  }
}

function writePlace(url: string, place: ListPlace) {
  try {
    window.sessionStorage.setItem(PLACE_PREFIX + url, JSON.stringify({ pages: place.pages, scrollY: Math.round(place.scrollY) }));
  } catch {
    // Storage blocked — Back lands at the top of the right list instead.
  }
}

/**
 * Scroll to `y` and hold it for a few seconds while the page finishes laying out (panels above a
 * list can still grow it after the rows arrive), letting go the moment the reader takes over — a
 * wheel, a touch, a key, a press — or the page navigates away. The portals' `restoreScroll` rule;
 * this tree may not import it.
 */
function holdScroll(y: number) {
  const path = window.location.pathname;
  const started = performance.now();
  let done = false;
  const handover = ["wheel", "touchstart", "keydown", "pointerdown"] as const;
  const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(() => apply());
  function stop() {
    if (done) return;
    done = true;
    for (const name of handover) window.removeEventListener(name, stop);
    observer?.disconnect();
  }
  function apply() {
    if (done) return;
    if (window.location.pathname !== path || performance.now() - started > 4000) {
      stop();
      return;
    }
    if (Math.abs(window.scrollY - y) > 1) window.scrollTo(0, y);
  }
  for (const name of handover) window.addEventListener(name, stop, { passive: true });
  observer?.observe(document.body);
  const step = () => {
    apply();
    if (!done) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

/**
 * Back from a record lands where the reader was in a list (owner, 1 Nov 2026): the filters are
 * already in the URL (`useUrlFilters`); this keeps, under that exact URL, how many "Show more"
 * pages were open and the scroll position, and puts both back on return.
 *
 * `pages` is how many pages the list holds now, `ready` that its rows are drawn, `exhausted` that
 * there is no further page. While `wantsMore(pages)` is true the list loads its next page itself;
 * once it has as many as before (or no more exist), the scroll is restored, once.
 */
export function useListPlace(pages: number, ready: boolean, exhausted: boolean): { wantsMore: (loaded: number) => boolean } {
  const home = useRef<string | null>(null);
  const wanted = useRef<ListPlace | null>(null);
  const restored = useRef(false);
  const latestPages = useRef(pages);

  useEffect(() => {
    home.current = window.location.pathname;
    wanted.current = readPlace(window.location.pathname + window.location.search);
    let frame = 0;
    let quietUntil = 0;
    const allowed = () => restored.current && window.location.pathname === home.current;
    const save = () => writePlace(window.location.pathname + window.location.search, { pages: latestPages.current, scrollY: window.scrollY });
    // A press (click or Enter, capture phase) is the position Back returns to; for a second after it
    // scroll events are the next page laying out under the same window, not the reader.
    const onPress = (event: Event) => {
      if (event instanceof KeyboardEvent && event.key !== "Enter") return;
      if (allowed()) save();
      quietUntil = performance.now() + 1000;
    };
    const onScroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (performance.now() < quietUntil || !allowed()) return;
        save();
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    document.addEventListener("click", onPress, true);
    document.addEventListener("keydown", onPress, true);
    return () => {
      window.removeEventListener("scroll", onScroll);
      document.removeEventListener("click", onPress, true);
      document.removeEventListener("keydown", onPress, true);
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);

  useEffect(() => {
    latestPages.current = pages;
    if (!ready || home.current === null || window.location.pathname !== home.current) return;
    if (!restored.current) {
      const target = wanted.current;
      if (target && pages < target.pages && !exhausted) return;
      restored.current = true;
      if (target && target.scrollY > 0) {
        holdScroll(target.scrollY);
        return;
      }
    }
    writePlace(window.location.pathname + window.location.search, { pages, scrollY: window.scrollY });
  }, [pages, ready, exhausted]);

  const wantsMore = useCallback((loaded: number) => !restored.current && wanted.current !== null && loaded < wanted.current.pages, []);
  return { wantsMore };
}
