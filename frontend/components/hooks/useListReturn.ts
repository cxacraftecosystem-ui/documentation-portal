"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { listMemoryFromSearch, listMemoryToSearch, readScroll, restoreScroll as restoreListScroll, watchScroll, writeScroll } from "@/components/hooks/useListMemory";
import { rememberListReturn, takeListReturn, type ListReturn } from "@/lib/listReturn";

/** What a list is showing right now, handed in every render so the address bar can follow it. */
export type ListPlace = Omit<ListReturn, "savedAt" | "scrollY">;

/** A note built from the list parameters in the URL (`useListMemory`'s names), or null. */
function noteFromUrl(): ListReturn | null {
  const memory = listMemoryFromSearch(window.location.search);
  if (!memory) return null;
  const completeness = memory.filters?.completeness;
  return {
    query: memory.applied ?? memory.query ?? "",
    page: memory.page ?? 1,
    funnel: memory.funnel ?? null,
    completeness: completeness === "incomplete" || completeness === "required" ? completeness : undefined,
    scrollY: readScroll(window.location.pathname + window.location.search) ?? 0,
    savedAt: Date.now()
  };
}

/**
 * The list half of "Back returns to the same place" (owner, 1 Nov 2026: the exact page, filters
 * and scroll, never the first record of page 1).
 *
 * - THE URL FIRST. Once the rows are drawn, the list's search, page, funnel and completeness filter
 *   are written into the address bar (`useListMemory`'s parameter names, merged with every other
 *   parameter), so the history entry Back returns to says what it showed. On arrival a URL that
 *   carries them wins; a bare URL falls back to the note `remember` left in this tab.
 * - THE SCROLL is stored under the list's full URL as the reader scrolls, and put back once the rows
 *   are on screen (`restoreScroll(ready)`): scrolling earlier scrolls a page one "Loading…" line tall.
 * - `restored` arrives in an EFFECT, never during render (the server renders the defaults).
 * - `restoredFunnel()` is read by `FunnelFilters` after its own lists load, so the filter comes back
 *   as it was instead of jumping to the newest workshop.
 * - `remember(state)` is called by a row the moment it opens a record.
 */
export function useListReturn(key: string, current?: ListPlace, ready = false) {
  const [restored, setRestored] = useState<ListReturn | null>(null);
  const funnelRef = useRef<ListReturn["funnel"]>(null);
  const scrollPending = useRef<number | null>(null);
  const home = useRef<string | null>(null);
  const latest = useRef<ListPlace | undefined>(current);
  const tracking = useRef(false);

  useEffect(() => {
    home.current = window.location.pathname;
    const fromUrl = noteFromUrl();
    const stored = takeListReturn(key);
    const note = fromUrl ?? stored;
    if (!note) return;
    funnelRef.current = note.funnel ?? null;
    scrollPending.current = note.scrollY > 0 ? note.scrollY : null;
    setRestored(note);
  }, [key]);

  // From the first drawn rows on, the address bar and the tab's note follow the list.
  useEffect(() => {
    latest.current = current;
    if (ready) tracking.current = true;
    if (!current || !tracking.current || home.current === null || window.location.pathname !== home.current) return;
    const search = listMemoryToSearch(
      { applied: current.query, page: current.page, funnel: current.funnel ?? undefined, filters: current.completeness ? { completeness: current.completeness } : {} },
      window.location.search
    );
    if (search !== window.location.search) window.history.replaceState(null, "", `${window.location.pathname}${search}${window.location.hash}`);
  });

  // As the reader scrolls, and at every press (`watchScroll`): never a position the browser clamped
  // the window to while the next page laid out, nor one from before the rows were drawn.
  useEffect(
    () =>
      watchScroll(
        (y) => {
          writeScroll(window.location.pathname + window.location.search, y);
          if (latest.current) rememberListReturn(key, { ...latest.current, scrollY: y });
        },
        () => tracking.current && home.current !== null && window.location.pathname === home.current
      ),
    [key]
  );

  const remember = useCallback(
    (state: ListPlace) => {
      rememberListReturn(key, { ...state, scrollY: typeof window === "undefined" ? 0 : window.scrollY });
      if (typeof window !== "undefined" && home.current === window.location.pathname) {
        writeScroll(window.location.pathname + window.location.search, window.scrollY);
      }
    },
    [key]
  );

  const restoredFunnel = useCallback(() => {
    const funnel = funnelRef.current;
    funnelRef.current = null;
    return funnel ?? null;
  }, []);

  const restoreScroll = useCallback((rowsReady: boolean) => {
    if (!rowsReady || scrollPending.current === null) return;
    const target = scrollPending.current;
    scrollPending.current = null;
    // Held while the page finishes laying out, let go when the reader takes over (`restoreScroll`).
    restoreListScroll(target);
  }, []);

  return { restored, remember, restoredFunnel, restoreScroll };
}
