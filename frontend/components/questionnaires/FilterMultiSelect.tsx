"use client";

import { useEffect, useRef, useState } from "react";

import { SearchableMultiSelect, type SelectOption } from "@/components/ui/SearchableSelect";

/**
 * The questionnaire filters' multi-select over THIS portal's primitive (owner, 31 Oct 2026). The
 * shared `FilterBar` hands it `search`, which asks the server as the reader types
 * (`GET /questionnaire/filters?field=…&q=…`), so a long list of artisans is searched whole and
 * never narrowed from a first page. Each portal keeps its own copy of this file because the two
 * portals' select primitives spell a server search differently; the props here are the contract.
 *
 * No chips under the trigger (the bar draws the active filters once) and no "Select all" (a
 * filter says "everything" by choosing nothing).
 */
export type FilterMultiSelectProps = {
  values: string[];
  onChange: (values: string[]) => void;
  /** The options before anything is typed. */
  options: SelectOption[];
  /** The chosen values' own rows, so they keep their labels whatever a search answered. */
  selected: SelectOption[];
  /** The server's answer for a typed term; absent → the options are filtered here. */
  search?: (term: string) => Promise<SelectOption[]>;
  ariaLabel: string;
  placeholder: string;
};

export function FilterMultiSelect({ values, onChange, options, selected, search, ariaLabel, placeholder }: FilterMultiSelectProps) {
  const [term, setTerm] = useState("");
  const [hits, setHits] = useState<SelectOption[] | null>(null);
  const [pending, setPending] = useState(false);
  // Read through a ref: a caller's fresh closure on every render must not restart the search.
  const searchRef = useRef(search);
  useEffect(() => {
    searchRef.current = search;
  }, [search]);
  const searching = Boolean(search);

  useEffect(() => {
    const ask = searchRef.current;
    if (!ask || !term.trim()) {
      setHits(null);
      setPending(false);
      return;
    }
    let cancelled = false;
    setPending(true);
    const timer = window.setTimeout(() => {
      ask(term.trim())
        .then((rows) => !cancelled && setHits(rows))
        .catch(() => !cancelled && setHits([]))
        .finally(() => !cancelled && setPending(false));
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [term, searching]);

  const base = hits ?? options;
  const have = new Set(base.map((o) => o.value));
  const shown = [...selected.filter((o) => !have.has(o.value)), ...base];

  return (
    <SearchableMultiSelect
      values={values}
      onChange={onChange}
      options={shown}
      placeholder={placeholder}
      ariaLabel={ariaLabel}
      searchable
      bulk={false}
      showChips={false}
      confirmOnSelect={false}
      onSearch={search ? setTerm : undefined}
      emptyLabel={pending ? "Searching…" : undefined}
    />
  );
}
