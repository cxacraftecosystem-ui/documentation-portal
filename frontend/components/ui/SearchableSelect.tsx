"use client";

import { Check, ChevronDown, Search, X } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { focusNextField } from "@/lib/formNav";
import { cn } from "@/lib/utils";

export type SelectOption = { value: string; label: string; disabled?: boolean };

const SEARCH_THRESHOLD = 8;

const VIRTUALIZE_AT = 200;

const OVERSCAN = 8;

const ROW_HEIGHT_GUESS = 36;

const SUMMARY_NAMES = 6;

const TRIGGER_CLASS =
  "flex w-full items-center justify-between gap-2 rounded-md border border-line-200 bg-card px-3.5 py-2.5 text-left text-sm text-ink-900 outline-hidden transition hover:border-purple-300 focus:border-purple-600 focus:ring-4 focus:ring-purple-600/15 disabled:cursor-not-allowed disabled:opacity-60";

const PANEL_CLASS = "overflow-hidden! p-0! flex flex-col";

const PANEL_MAX_WIDTH = 520;

function fold(text: string) {
  return text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase();
}

function filterOptions(options: SelectOption[], query: string): SelectOption[] {
  const needle = fold(query.trim());
  if (!needle) return options;
  const ranked: Array<{ option: SelectOption; rank: number; at: number }> = [];
  options.forEach((option, at) => {
    const hay = fold(option.label);
    const index = hay.indexOf(needle);
    if (index < 0) return;
    const wordStart = index === 0 || /[\s\-–—\/(,.·]/.test(hay[index - 1]);
    ranked.push({ option, rank: index === 0 ? 0 : wordStart ? 1 : 2, at });
  });
  ranked.sort((a, b) => a.rank - b.rank || a.at - b.at);
  return ranked.map((entry) => entry.option);
}

function useTypeahead() {
  const state = useRef({ text: "", at: 0 });
  return useCallback((char: string) => {
    const now = Date.now();
    if (now - state.current.at > 700) state.current.text = "";
    state.current.at = now;
    state.current.text += char.toLowerCase();
    return state.current.text;
  }, []);
}

function stepHighlight(options: SelectOption[], current: number, delta: number) {
  const n = options.length;
  if (!n) return -1;
  let i = current < 0 || current >= n ? (delta > 0 ? -1 : n) : current;
  for (let step = 0; step < n; step++) {
    i = (i + delta + n) % n;
    if (!options[i].disabled) return i;
  }
  return -1;
}

function isPrintable(event: React.KeyboardEvent) {
  return event.key.length === 1 && event.key !== " " && !event.ctrlKey && !event.metaKey && !event.altKey;
}

function firstEnabled(options: SelectOption[]) {
  return options.findIndex((option) => !option.disabled);
}

function optionClass(option: SelectOption, highlighted: boolean, active: boolean) {
  return cn(
    "flex items-center gap-2 px-3.5 py-2 text-sm",
    option.disabled ? "cursor-not-allowed text-ink-300" : "cursor-pointer",
    !option.disabled && highlighted && "bg-purple-50 dark:bg-purple-950",
    active ? "font-medium text-purple-700 dark:text-purple-300" : !option.disabled ? "text-ink-700" : ""
  );
}

function useScrollHighlightIntoView(
  open: boolean,
  highlight: number,
  baseId: string,
  virtual: boolean,
  scrollToIndex: (index: number) => void
) {
  useEffect(() => {
    if (!open || highlight < 0) return;
    if (virtual) scrollToIndex(highlight);
    else document.getElementById(`${baseId}-opt-${highlight}`)?.scrollIntoView({ block: "nearest" });
  }, [open, highlight, baseId, virtual, scrollToIndex]);
}

type VirtualWindow = {
  virtual: boolean;
  start: number;
  end: number;
  padTop: number;
  padBottom: number;
  listRef: (node: HTMLUListElement | null) => void;
  onScroll: (event: React.UIEvent<HTMLUListElement>) => void;
  scrollToIndex: (index: number) => void;
};

function useVirtualWindow(count: number): VirtualWindow {
  const virtual = count > VIRTUALIZE_AT;
  const [node, setNode] = useState<HTMLUListElement | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewport, setViewport] = useState(288);
  const [rowHeight, setRowHeight] = useState(ROW_HEIGHT_GUESS);

  useEffect(() => {
    if (!node || !virtual) return;
    setViewport(node.clientHeight || 288);
    const row = node.querySelector<HTMLElement>('[role="option"]');
    if (row && row.offsetHeight > 0) setRowHeight(row.offsetHeight);
  }, [node, virtual, count]);

  const nodeRef = useRef<HTMLUListElement | null>(null);
  const listRef = useCallback((next: HTMLUListElement | null) => {
    nodeRef.current = next;
    setNode(next);
  }, []);
  const onScroll = useCallback((event: React.UIEvent<HTMLUListElement>) => {
    setScrollTop(event.currentTarget.scrollTop);
  }, []);
  const scrollToIndex = useCallback(
    (index: number) => {
      const list = nodeRef.current;
      if (!list) return;
      const top = index * rowHeight;
      if (top < list.scrollTop) list.scrollTop = top;
      else if (top + rowHeight > list.scrollTop + list.clientHeight) list.scrollTop = top + rowHeight - list.clientHeight;
      setScrollTop(list.scrollTop);
    },
    [rowHeight]
  );

  if (!virtual) {
    return { virtual, start: 0, end: count, padTop: 0, padBottom: 0, listRef, onScroll, scrollToIndex };
  }
  const start = Math.max(0, Math.floor(scrollTop / rowHeight) - OVERSCAN);
  const end = Math.min(count, Math.ceil((scrollTop + viewport) / rowHeight) + OVERSCAN);
  return {
    virtual,
    start,
    end,
    padTop: start * rowHeight,
    padBottom: (count - end) * rowHeight,
    listRef,
    onScroll,
    scrollToIndex
  };
}

function useSelectList(options: SelectOption[], query: string, searchable: boolean) {
  const filtered = useMemo(
    () => (searchable ? filterOptions(options, query) : options),
    [options, query, searchable]
  );
  return { filtered, rendered: filtered };
}

function containEvents(onKeyDown: (event: React.KeyboardEvent) => void) {
  return {
    onKeyDown: (event: React.KeyboardEvent) => {
      onKeyDown(event);
      event.stopPropagation();
    },
    onInput: (event: React.FormEvent) => event.stopPropagation(),
    onChange: (event: React.FormEvent) => event.stopPropagation()
  };
}

function SearchRow({
  inputRef,
  inputId,
  listboxId,
  activeId,
  query,
  onQueryChange,
  onKeyDown,
  placeholder,
  label,
  trailing
}: {
  inputRef: (node: HTMLInputElement | null) => void;
  inputId: string;
  listboxId: string;
  activeId?: string;
  query: string;
  onQueryChange: (value: string) => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  placeholder: string;
  label: string;
  trailing?: React.ReactNode;
}) {
  return (
    <div className="flex shrink-0 items-center gap-2 border-b border-line-200 p-2">
      <div className="relative min-w-0 flex-1">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-500" aria-hidden />
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          role="combobox"
          aria-expanded
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          aria-label={label}
          autoComplete="off"
          spellCheck={false}
          value={query}
          placeholder={placeholder}
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={onKeyDown}
          className="w-full rounded-sm border border-line-200 bg-card py-1.5 pl-8 pr-2 text-sm text-ink-900 outline-hidden transition placeholder:text-ink-300 focus:border-purple-600 focus:ring-2 focus:ring-purple-600/15"
        />
      </div>
      {trailing}
    </div>
  );
}

function useEdgeTab(
  panelRef: React.RefObject<HTMLDivElement | null>,
  onTabForward: () => void,
  onTabBackward: () => void
) {
  return useCallback(
    (event: React.KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const panel = panelRef.current;
      if (!panel) return;
      const stops = Array.from(
        panel.querySelectorAll<HTMLElement>("input:not([disabled]),button:not([disabled])")
      );
      const index = stops.indexOf(document.activeElement as HTMLElement);
      if (index < 0) return;
      if (!event.shiftKey && index === stops.length - 1) {
        event.preventDefault();
        onTabForward();
      } else if (event.shiftKey && index === 0) {
        event.preventDefault();
        onTabBackward();
      }
    },
    [panelRef, onTabForward, onTabBackward]
  );
}

function useFilterBoxFocus(triggerRef: React.RefObject<HTMLButtonElement | null>) {
  return useCallback(
    (node: HTMLInputElement | null) => {
      if (!node) return;
      requestAnimationFrame(() => {
        if (!node.isConnected) return;
        const active = document.activeElement;
        if (active && active !== document.body && active !== triggerRef.current) return;
        node.focus({ preventScroll: true });
      });
    },
    [triggerRef]
  );
}

export type SearchableSelectProps = {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  searchable?: boolean;
  advanceOnSelect?: boolean;
  onSearch?: (query: string) => void;
  clearable?: boolean;
  clearLabel?: string;
  id?: string;
  describedBy?: string;
};

export function SearchableSelect({
  value,
  onChange,
  options,
  placeholder = "Select",
  emptyLabel = "No options",
  disabled,
  className,
  ariaLabel,
  searchable,
  advanceOnSelect = true,
  onSearch,
  clearable = false,
  clearLabel,
  id,
  describedBy
}: SearchableSelectProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const typeahead = useTypeahead();

  const withSearch = searchable ?? options.length >= SEARCH_THRESHOLD;
  const { filtered, rendered } = useSelectList(options, query, withSearch);
  const { virtual, start, end, padTop, padBottom, listRef, onScroll, scrollToIndex } = useVirtualWindow(rendered.length);

  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId, virtual, scrollToIndex);

  const selected = options.find((option) => option.value === value);

  const searchRef = useRef(onSearch);
  useEffect(() => {
    searchRef.current = onSearch;
  });

  const applyQuery = useCallback((next: string) => {
    setQuery(next);
    searchRef.current?.(next);
  }, []);

  const close = useCallback(() => {
    setOpen(false);
    applyQuery("");
  }, [applyQuery]);

  const closeAndMoveOn = useCallback(
    (advance: boolean) => {
      close();
      requestAnimationFrame(() => {
        if (advance && focusNextField(wrapperRef.current)) return;
        triggerRef.current?.focus({ preventScroll: true });
      });
    },
    [close]
  );

  function choose(index: number) {
    const option = rendered[index];
    if (!option || option.disabled) return;
    onChange(option.value);
    closeAndMoveOn(advanceOnSelect);
  }

  function openPanel() {
    applyQuery("");
    setOpen(true);
    const at = rendered.findIndex((option) => option.value === value);
    setHighlight(at >= 0 && !rendered[at].disabled ? at : firstEnabled(rendered));
  }

  function onQueryChange(next: string) {
    applyQuery(next);
    setHighlight(0);
  }

  const onTabForward = useCallback(() => {
    if (safeHighlight >= 0 && rendered[safeHighlight]) onChange(rendered[safeHighlight].value);
    closeAndMoveOn(true);
  }, [safeHighlight, rendered, onChange, closeAndMoveOn]);

  const onTabBackward = useCallback(() => closeAndMoveOn(false), [closeAndMoveOn]);
  const onPanelKeyDown = useEdgeTab(panelRef, onTabForward, onTabBackward);

  function navigate(event: React.KeyboardEvent) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, 1));
        else openPanel();
        return true;
      case "ArrowUp":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, -1));
        else openPanel();
        return true;
      case "Home":
        if (!open) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, 1));
        return true;
      case "End":
        if (!open) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, -1));
        return true;
      case "Enter":
        if (!open) return false;
        event.preventDefault();
        if (safeHighlight >= 0) choose(safeHighlight);
        return true;
      default:
        return false;
    }
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    if (navigate(event)) return;
    if (event.key === " ") {
      event.preventDefault();
      if (open) {
        if (safeHighlight >= 0) choose(safeHighlight);
      } else openPanel();
      return;
    }
    if (event.key === "Tab" && open) close();
    if (!withSearch && isPrintable(event)) {
      event.preventDefault();
      const query = typeahead(event.key);
      const match = options.findIndex((option) => !option.disabled && fold(option.label).startsWith(query));
      if (match < 0) return;
      if (open) setHighlight(match);
      else onChange(options[match].value);
    }
  }

  const inputRef = useFilterBoxFocus(triggerRef);

  const announcement = withSearch && query.trim()
    ? `${filtered.length} of ${options.length} options match ${query.trim()}`
    : `${options.length} options`;

  return (
    <div ref={wrapperRef} className={cn("relative min-w-0", className)}>
      <button
        ref={triggerRef}
        id={id}
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        aria-describedby={describedBy}
        aria-controls={open ? listboxId : undefined}
        aria-activedescendant={open && !withSearch ? activeId : undefined}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={onTriggerKeyDown}
        data-form-field=""
        data-searchable-select=""
        className={cn(TRIGGER_CLASS, clearable && selected && !disabled && "pr-11")}
      >
        <span className={cn("min-w-0 truncate", !selected && "text-ink-500")} title={selected?.label}>
          {selected ? selected.label : placeholder}
        </span>
        <ChevronDown
          className={cn("h-4 w-4 shrink-0 text-ink-500 transition-transform", open && "rotate-180")}
          aria-hidden
        />
      </button>
      {clearable && selected && !disabled ? (
        <button
          type="button"
          aria-label={clearLabel ?? (ariaLabel ? `Clear ${ariaLabel}` : "Clear selection")}
          title="Clear"
          onClick={() => {
            onChange("");
            triggerRef.current?.focus({ preventScroll: true });
          }}
          className="absolute right-8 top-1/2 grid h-7 w-7 -translate-y-1/2 place-items-center rounded-full text-ink-500 transition hover:bg-surface-50 hover:text-ink-900 pointer-coarse:h-10 pointer-coarse:w-10"
        >
          <X className="h-3.5 w-3.5" aria-hidden />
        </button>
      ) : null}

      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={wrapperRef}
        restoreFocusRef={triggerRef}
        label={ariaLabel ? `${ariaLabel} options` : "Options"}
        offset={4}
        matchAnchorWidth
        maxWidth={PANEL_MAX_WIDTH}
        className={PANEL_CLASS}
      >
        <div ref={panelRef} {...containEvents(onPanelKeyDown)} className="flex min-h-0 flex-col">
          {withSearch ? (
            <SearchRow
              inputRef={inputRef}
              inputId={`${baseId}-search`}
              listboxId={listboxId}
              activeId={activeId}
              query={query}
              onQueryChange={onQueryChange}
              onKeyDown={navigate}
              placeholder="Type to filter"
              label={ariaLabel ? `Filter ${ariaLabel}` : "Filter options"}
            />
          ) : null}
          <ul
            ref={listRef}
            id={listboxId}
            role="listbox"
            aria-label={ariaLabel ?? "Options"}
            onScroll={onScroll}
            style={virtual ? { paddingTop: padTop, paddingBottom: padBottom } : undefined}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {rendered.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">{query.trim() ? "No matches" : emptyLabel}</li>
            ) : null}
            {rendered.slice(start, end).map((option, offset) => {
              const index = start + offset;
              const active = option.value === value;
              return (
                <li
                  key={option.value}
                  id={`${baseId}-opt-${index}`}
                  role="option"
                  aria-setsize={virtual ? rendered.length : undefined}
                  aria-posinset={virtual ? index + 1 : undefined}
                  aria-selected={active}
                  aria-disabled={option.disabled || undefined}
                  title={option.label}
                  onMouseEnter={() => {
                    if (!option.disabled) setHighlight(index);
                  }}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => choose(index)}
                  className={optionClass(option, index === safeHighlight, active)}
                >
                  <span className="min-w-0 flex-1 truncate">{option.label}</span>
                  {active ? <Check className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden /> : null}
                </li>
              );
            })}
          </ul>
          <p className="sr-only" role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
      </AnchoredPopover>
    </div>
  );
}

export type SearchableMultiSelectProps = {
  values: string[];
  onChange: (values: string[]) => void;
  options: SelectOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  searchable?: boolean;
  confirmOnSelect?: boolean;
  confirmLabel?: string;
  showChips?: boolean;
  bulk?: boolean;
  onSearch?: (query: string) => void;
};

const CHIP_LIMIT = 12;

export function SearchableMultiSelect({
  values,
  onChange,
  options,
  placeholder = "Select",
  emptyLabel = "No options",
  disabled,
  className,
  ariaLabel,
  searchable,
  confirmOnSelect = true,
  confirmLabel = "Confirm",
  showChips = true,
  bulk: bulkOn = true,
  onSearch
}: SearchableMultiSelectProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const typeahead = useTypeahead();

  const withSearch = searchable ?? options.length >= SEARCH_THRESHOLD;
  const chosen = useMemo(() => new Set(values), [values]);
  const { filtered, rendered } = useSelectList(options, query, withSearch);
  const { virtual, start, end, padTop, padBottom, listRef, onScroll, scrollToIndex } = useVirtualWindow(rendered.length);

  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId, virtual, scrollToIndex);

  const close = useCallback(() => {
    setOpen(false);
    setQuery("");
    onSearch?.("");
  }, [onSearch]);

  const closeAndMoveOn = useCallback(
    (advance: boolean) => {
      close();
      requestAnimationFrame(() => {
        if (advance && focusNextField(wrapperRef.current)) return;
        triggerRef.current?.focus({ preventScroll: true });
      });
    },
    [close]
  );

  function toggle(index: number) {
    const option = rendered[index];
    if (!option || option.disabled) return;
    if (chosen.has(option.value)) onChange(values.filter((v) => v !== option.value));
    else onChange([...values, option.value]);
  }

  const bulk = useMemo(() => filtered.filter((option) => !option.disabled), [filtered]);
  const allChosen = bulk.length > 0 && bulk.every((option) => chosen.has(option.value));
  const filtering = withSearch && query.trim().length > 0;
  const bulkLabel = allChosen
    ? filtering
      ? `Clear ${bulk.length} matching`
      : `Clear all ${bulk.length}`
    : filtering
      ? `Select ${bulk.length} matching`
      : `Select all ${bulk.length}`;

  function applyBulk() {
    if (allChosen) {
      const drop = new Set(bulk.map((option) => option.value));
      onChange(values.filter((v) => !drop.has(v)));
      return;
    }
    const have = new Set(values);
    onChange([...values, ...bulk.map((option) => option.value).filter((v) => !have.has(v))]);
  }

  function openPanel() {
    setQuery("");
    setOpen(true);
    setHighlight(firstEnabled(rendered));
  }

  function onQueryChange(next: string) {
    setQuery(next);
    setHighlight(0);
    onSearch?.(next);
  }

  const onTabForward = useCallback(() => closeAndMoveOn(true), [closeAndMoveOn]);
  const onTabBackward = useCallback(() => closeAndMoveOn(false), [closeAndMoveOn]);
  const onPanelKeyDown = useEdgeTab(panelRef, onTabForward, onTabBackward);

  function navigate(event: React.KeyboardEvent) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, 1));
        else openPanel();
        return true;
      case "ArrowUp":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, -1));
        else openPanel();
        return true;
      case "Home":
        if (!open) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, 1));
        return true;
      case "End":
        if (!open) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, -1));
        return true;
      case "Enter":
        if (!open) return false;
        event.preventDefault();
        if (safeHighlight >= 0) toggle(safeHighlight);
        return true;
      default:
        return false;
    }
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    if (navigate(event)) return;
    if (event.key === " ") {
      event.preventDefault();
      if (open) {
        if (safeHighlight >= 0) toggle(safeHighlight);
      } else openPanel();
      return;
    }
    if (event.key === "Tab" && open) close();
    if (!withSearch && isPrintable(event)) {
      event.preventDefault();
      const query = typeahead(event.key);
      const match = options.findIndex((option) => !option.disabled && fold(option.label).startsWith(query));
      if (match < 0) return;
      if (!open) setOpen(true);
      setHighlight(match);
    }
  }

  const inputRef = useFilterBoxFocus(triggerRef);

  const chosenOptions = useMemo(() => {
    const byValue = new Map(options.map((option) => [option.value, option]));
    return values.map((v) => byValue.get(v)).filter((option): option is SelectOption => Boolean(option));
  }, [options, values]);
  const chosenLabels = useMemo(() => chosenOptions.map((option) => option.label), [chosenOptions]);
  const selectionSummary = chosenLabels.length
    ? chosenLabels.length <= SUMMARY_NAMES
      ? `${chosenLabels.length} selected: ${chosenLabels.join(", ")}`
      : `${chosenLabels.length} selected, including ${chosenLabels.slice(0, SUMMARY_NAMES).join(", ")}`
    : "Nothing selected";
  const announcement = `${
    filtering ? `${filtered.length} of ${options.length} options match ${query.trim()}` : `${options.length} options`
  }. ${selectionSummary}.`;

  const bulkButton =
    bulkOn && bulk.length > 0 ? (
      <button
        type="button"
        onClick={applyBulk}
        onMouseDown={(event) => event.preventDefault()}
        className="shrink-0 whitespace-nowrap rounded-sm border border-line-200 bg-card px-2 py-1.5 text-xs font-medium text-purple-700 outline-hidden transition hover:border-purple-300 hover:bg-purple-50 focus-visible:border-purple-600 focus-visible:ring-2 focus-visible:ring-purple-600/20 dark:text-purple-300 dark:hover:bg-purple-950"
      >
        {bulkLabel}
      </button>
    ) : null;

  return (
    <div className={cn("min-w-0", className)}>
    <div ref={wrapperRef} className="relative min-w-0">
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel ? `${ariaLabel}. ${selectionSummary}` : undefined}
        aria-controls={open ? listboxId : undefined}
        aria-activedescendant={open && !withSearch ? activeId : undefined}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={onTriggerKeyDown}
        data-form-field=""
        data-searchable-select=""
        className={TRIGGER_CLASS}
      >
        <span className={cn("min-w-0 truncate", !values.length && "text-ink-500")}>
          {values.length ? `${values.length} selected` : placeholder}
        </span>
        {ariaLabel ? null : <span className="sr-only">{selectionSummary}</span>}
        <ChevronDown
          className={cn("h-4 w-4 shrink-0 text-ink-500 transition-transform", open && "rotate-180")}
          aria-hidden
        />
      </button>

      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={wrapperRef}
        restoreFocusRef={triggerRef}
        label={ariaLabel ? `${ariaLabel} options` : "Options"}
        offset={4}
        matchAnchorWidth
        maxWidth={PANEL_MAX_WIDTH}
        className={PANEL_CLASS}
      >
        <div ref={panelRef} {...containEvents(onPanelKeyDown)} className="flex min-h-0 flex-col">
          {withSearch ? (
            <SearchRow
              inputRef={inputRef}
              inputId={`${baseId}-search`}
              listboxId={listboxId}
              activeId={activeId}
              query={query}
              onQueryChange={onQueryChange}
              onKeyDown={navigate}
              placeholder="Type to filter"
              label={ariaLabel ? `Filter ${ariaLabel}` : "Filter options"}
              trailing={bulkButton}
            />
          ) : bulkButton ? (
            <div className="flex shrink-0 justify-end border-b border-line-200 p-2">{bulkButton}</div>
          ) : null}
          <ul
            ref={listRef}
            id={listboxId}
            role="listbox"
            aria-multiselectable
            aria-label={ariaLabel ?? "Options"}
            onScroll={onScroll}
            style={virtual ? { paddingTop: padTop, paddingBottom: padBottom } : undefined}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {rendered.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">{query.trim() ? "No matches" : emptyLabel}</li>
            ) : null}
            {rendered.slice(start, end).map((option, offset) => {
              const index = start + offset;
              const checked = chosen.has(option.value);
              return (
                <li
                  key={option.value}
                  id={`${baseId}-opt-${index}`}
                  role="option"
                  aria-setsize={virtual ? rendered.length : undefined}
                  aria-posinset={virtual ? index + 1 : undefined}
                  aria-selected={checked}
                  aria-disabled={option.disabled || undefined}
                  title={option.label}
                  onMouseEnter={() => {
                    if (!option.disabled) setHighlight(index);
                  }}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => toggle(index)}
                  className={optionClass(option, index === safeHighlight, checked)}
                >
                  <span
                    aria-hidden
                    className={cn(
                      "grid h-4 w-4 shrink-0 place-items-center rounded border transition",
                      checked ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card"
                    )}
                  >
                    {checked ? <Check className="h-3 w-3" /> : null}
                  </span>
                  <span className="min-w-0 flex-1 truncate">{option.label}</span>
                </li>
              );
            })}
          </ul>
          {confirmOnSelect && values.length > 0 ? (
            <div className="shrink-0 border-t border-line-200 bg-card p-2">
              <button
                type="button"
                className="field-button w-full py-1.5 text-xs"
                onClick={() => closeAndMoveOn(true)}
                onMouseDown={(event) => event.preventDefault()}
              >
                {confirmLabel} ({values.length})
              </button>
            </div>
          ) : null}
          <p className="sr-only" role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
      </AnchoredPopover>
    </div>
      {showChips && chosenOptions.length > 0 ? (
        <ul className="mt-2 flex flex-wrap gap-1.5" aria-label={ariaLabel ? `${ariaLabel}: chosen` : "Chosen"}>
          {chosenOptions.slice(0, CHIP_LIMIT).map((option) => (
            <li
              key={option.value}
              className="inline-flex max-w-full items-center gap-1 rounded-full border border-purple-200 bg-purple-50 py-0.5 pl-2.5 pr-0.5 text-xs font-medium text-purple-800 dark:border-purple-700 dark:bg-purple-900 dark:text-purple-100"
            >
              <span className="min-w-0 truncate" title={option.label}>
                {option.label}
              </span>
              {disabled ? null : (
                <button
                  type="button"
                  aria-label={`Remove ${option.label}`}
                  onClick={() => onChange(values.filter((v) => v !== option.value))}
                  className="grid h-6 w-6 shrink-0 place-items-center rounded-full transition hover:bg-purple-100 dark:hover:bg-purple-800 pointer-coarse:h-9 pointer-coarse:w-9"
                >
                  <X className="h-3 w-3" aria-hidden />
                </button>
              )}
            </li>
          ))}
          {chosenOptions.length > CHIP_LIMIT ? (
            <li className="inline-flex items-center px-1 text-xs text-ink-500">+{chosenOptions.length - CHIP_LIMIT} more</li>
          ) : null}
        </ul>
      ) : null}
    </div>
  );
}
