"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { SEARCH_DEBOUNCE_MS, shouldSearchServer } from "@/components/forms/RecordSwitcher";
import { listResource } from "@/lib/api";
import { sharedListResource } from "@/lib/sharedList";

export type ServerSearchList<T> = {
  items: T[];
  cut: ListCut | null;
  state: "pending" | "loaded" | "unavailable";
  searching: boolean;
  setQuery: (query: string) => void;
};

export function useServerSearchList<T extends { id: string }>(
  endpoint: string | null,
  noun: string,
  params: Record<string, string | number | undefined | null> = {}
): ServerSearchList<T> {
  const [items, setItems] = useState<T[]>([]);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [state, setState] = useState<"pending" | "loaded" | "unavailable">("pending");
  const [query, setQueryState] = useState("");
  const [searching, setSearching] = useState(false);
  const generation = useRef(0);
  const paramKey = JSON.stringify(params);
  const stableParams = useMemo(() => JSON.parse(paramKey) as Record<string, string | number | undefined | null>, [paramKey]);

  useEffect(() => {
    if (!endpoint) {
      setItems([]);
      setCut(null);
      setState("pending");
      return;
    }
    const mine = (generation.current += 1);
    setState("pending");
    sharedListResource<T>(endpoint, { ...stableParams, pageSize: LIST_PAGE_CEILING })
      .then((page) => {
        if (mine !== generation.current) return;
        setItems(page.items);
        setCut(listCut(page, noun));
        setState("loaded");
      })
      .catch(() => {
        if (mine !== generation.current) return;
        setState("unavailable");
      });
  }, [endpoint, noun, stableParams]);

  useEffect(() => {
    if (!endpoint || !shouldSearchServer(cut, query)) {
      setSearching(false);
      return;
    }
    let cancelled = false;
    setSearching(true);
    const timer = window.setTimeout(() => {
      listResource<T>(endpoint, { ...stableParams, search: query.trim(), pageSize: LIST_PAGE_CEILING })
        .then((page) => {
          if (!cancelled) setItems((current) => mergeById(current, page.items));
        })
        .catch(() => undefined)
        .finally(() => {
          if (!cancelled) setSearching(false);
        });
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [endpoint, stableParams, cut, query]);

  const setQuery = useCallback((next: string) => setQueryState(next), []);
  return { items, cut, state, searching, setQuery };
}
