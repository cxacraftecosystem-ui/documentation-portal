"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { listMemoryFromSearch, listMemoryToSearch, readScroll, restoreScroll as restoreListScroll, watchScroll, writeScroll } from "@/components/hooks/useListMemory";
import { rememberListReturn, takeListReturn, type ListReturn } from "@/lib/listReturn";

export type ListPlace = Omit<ListReturn, "savedAt" | "scrollY">;

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
    restoreListScroll(target);
  }, []);

  return { restored, remember, restoredFunnel, restoreScroll };
}
