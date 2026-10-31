"use client";

import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

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
