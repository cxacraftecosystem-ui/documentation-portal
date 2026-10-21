"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { SEARCH_DEBOUNCE_MS, shouldSearchServer } from "@/components/forms/RecordSwitcher";
import { listResource } from "@/lib/api";
import { sharedListResource } from "@/lib/sharedList";

/**
 * A11 — A RECORD PICKER THAT SEARCHES THE SERVER WHEN ITS LIST IS CUT.
 *
 * The process form's and the media page's pickers loaded the newest hundred rows and filtered them
 * in the browser, so a record past row one hundred answered "No matches". This holds one page, says
 * when that page is not the whole table (`cut`), and — only then, and only for a non-blank query —
 * re-asks the list route with its own `search=` after the shared 350ms debounce
 * (`shouldSearchServer`, the rule `RecordSwitcher` already keeps). Rows found by a search are merged
 * into what is held, so the picked row stays drawable after the query is cleared. Offline, the held
 * rows stay and filter locally.
 *
 * Wire `setQuery` to the picker's `onSearch`.
 */
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
  // Params arrive as a fresh object every render; the request keys off their text.
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
    // Shared with any other picker asking for the same page in the same moment (`lib/sharedList`).
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
