"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { rememberListReturn, takeListReturn, type ListReturn } from "@/lib/listReturn";

/**
 * The list half of "Back returns to the same place" — see `lib/listReturn.ts` for why.
 *
 * - `restored` is the note this mount took, or null. It arrives in an EFFECT, never during render:
 *   the server renders the defaults, and reading storage in a state initialiser would hand the client
 *   a first render that disagrees with that HTML.
 * - `restoredFunnel()` is read by `FunnelFilters` AFTER its own lists load, which is always after this
 *   effect has run, so the filter comes back as it was instead of jumping to the newest workshop.
 * - `remember(state)` is called by a row the moment it opens a record.
 * - `restoreScroll(ready)` puts the page back where it was once the rows are on screen. Scrolling any
 *   earlier scrolls a page that is still one "Loading…" line tall.
 */
export function useListReturn(key: string) {
  const [restored, setRestored] = useState<ListReturn | null>(null);
  const funnelRef = useRef<ListReturn["funnel"]>(null);
  const scrollPending = useRef<number | null>(null);

  useEffect(() => {
    const note = takeListReturn(key);
    if (!note) return;
    funnelRef.current = note.funnel ?? null;
    scrollPending.current = note.scrollY > 0 ? note.scrollY : null;
    setRestored(note);
  }, [key]);

  const remember = useCallback(
    (state: Omit<ListReturn, "savedAt" | "scrollY">) => {
      rememberListReturn(key, { ...state, scrollY: typeof window === "undefined" ? 0 : window.scrollY });
    },
    [key]
  );

  const restoredFunnel = useCallback(() => {
    const funnel = funnelRef.current;
    funnelRef.current = null;
    return funnel ?? null;
  }, []);

  const restoreScroll = useCallback((ready: boolean) => {
    if (!ready || scrollPending.current === null) return;
    const target = scrollPending.current;
    scrollPending.current = null;
    // One frame, so the rows that just arrived have their final height.
    window.requestAnimationFrame(() => window.scrollTo({ top: target }));
  }, []);

  return { restored, remember, restoredFunnel, restoreScroll };
}
