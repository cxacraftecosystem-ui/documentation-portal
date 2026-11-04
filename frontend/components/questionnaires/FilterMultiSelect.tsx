"use client";

import { useEffect, useRef, useState } from "react";

import { SearchableMultiSelect, type SelectOption } from "@/components/ui/SearchableSelect";

export type FilterMultiSelectProps = {
  values: string[];
  onChange: (values: string[]) => void;
  options: SelectOption[];
  selected: SelectOption[];
  search?: (term: string) => Promise<SelectOption[]>;
  ariaLabel: string;
  placeholder: string;
};

export function FilterMultiSelect({ values, onChange, options, selected, search, ariaLabel, placeholder }: FilterMultiSelectProps) {
  const [term, setTerm] = useState("");
  const [hits, setHits] = useState<SelectOption[] | null>(null);
  const [pending, setPending] = useState(false);
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
