"use client";

/**
 * `SearchableSelect` / `SearchableMultiSelect` — the app's one list-picking control.
 *
 * Reported by the user: "make the single select and multi-select drop downs searchable... if they
 * press enter then the first value gets chosen, in multi-select everywhere, also give the option
 * select all."
 *
 * Four decisions carry this file, and none of them are obvious from the code alone.
 *
 * **1. Search is decided by option count, not by the call site.** There are forty-odd selects in
 * this app and asking each one to opt in guarantees the long ones get missed. `SEARCH_THRESHOLD`
 * splits them instead, and the split is real rather than tuned: see the constant.
 *
 * **2. The highlight is derived, never stored raw.** A stored index goes stale the instant the
 * filter changes, and a stale index means Enter commits a row that is not on screen — the exact
 * data-entry bug a searchable select is supposed to prevent. `safeHighlight` re-derives a valid,
 * enabled, rendered index on every render, so there is no window in which the highlight is a lie.
 *
 * **3. "Select all" follows the filter.** With a query active the button acts on the matches and
 * says so ("Select 6 matching"), never on the 74 rows the reader cannot see. Choosing the whole
 * corpus by accident is far more expensive to undo than clearing the box and clicking again.
 *
 * **4. The panel floats.** It is `AnchoredPopover`, the positioner written for the date picker
 * after the same user reported that opening the calendar "pushes the rest down". A dropdown has
 * the identical problem plus a worse one: these lists live inside dialogs and `overflow-hidden`
 * filter panels, where an absolutely positioned menu is sheared off rather than merely misplaced.
 *
 * Focus is deliberately NOT trapped. Tab walks the panel's own controls and then leaves for the
 * next field in the form; Escape closes and puts focus back on the trigger. A picker that swallows
 * the keyboard is worse than the native `<select>` it replaces.
 */

import { Check, ChevronDown, Search } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { focusNextField } from "@/lib/formNav";
import { cn } from "@/lib/utils";

export type SelectOption = { value: string; label: string; disabled?: boolean };

/**
 * At or above this many options the panel grows a search box.
 *
 * The number is not a guess — it is where this app's lists actually divide. Every fixed vocabulary
 * tops out at seven: Yes/No (2), access level (3), gender (4), tradition (4), status (4-5), market
 * demand (5), product type (6), maker (7). Every list backed by records starts at nine: crafts (9),
 * artisans (16), products (18), users (20), tools (74), country dial codes (252). So a single
 * threshold separates a closed vocabulary the reader takes in at a glance from a corpus they have
 * to hunt through, and a four-option enum keeps the plain list it deserves.
 */
const SEARCH_THRESHOLD = 8;

/**
 * Rows rendered at once, past which the list is capped and the footer says so.
 *
 * Cheaper than a virtualiser and better teaching: with 252 dial codes the way to reach Uruguay is
 * to type, not to flick a finger for ten seconds on a rural handset. The cap only limits what is
 * DRAWN — filtering and "select all" both still see every match, so nothing is silently unreachable.
 */
const RENDER_CAP = 80;

/** Selected labels read out in full before the summary switches to a count. */
const SUMMARY_NAMES = 6;

const TRIGGER_CLASS =
  "flex w-full items-center justify-between gap-2 rounded-md border border-line-200 bg-card px-3.5 py-2.5 text-left text-sm text-ink-900 outline-none transition hover:border-purple-300 focus:border-purple-600 focus:ring-4 focus:ring-purple-600/15 disabled:cursor-not-allowed disabled:opacity-60";

/**
 * `!` because `cn` is a plain join, not tailwind-merge: `overflow-y-auto` and `p-3` from
 * AnchoredPopover's own class list would otherwise win on CSS source order regardless of what is
 * appended here. The panel must not scroll as a whole — the search box and the footer stay put
 * while only the list moves — so the override has to actually take.
 */
const PANEL_CLASS = "!overflow-hidden !p-0 flex flex-col";

/** Widest a panel gets, so a full-width field on a laptop does not produce a 1200px list. */
const PANEL_MAX_WIDTH = 520;

/** Diacritics folded so "Jodhpur" is reachable by typing "jodhpur" and "Ahmedābād" by "ahmedabad". */
function fold(text: string) {
  return text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase();
}

/**
 * Matches, ordered so that Enter picks what the reader meant.
 *
 * Plain substring order is not good enough: typing "co" into the tool list would put "Bamboo comb"
 * above "Cotton hank" purely because it was entered first, and Enter would then take the wrong one.
 * Ranking label-prefix above word-prefix above mid-word puts the obvious answer on top, and the
 * sort is stable so equally-good matches keep the caller's ordering.
 */
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

/**
 * Rolling type-ahead buffer (~700ms window, like the native <select>).
 *
 * Only ever used by a list too short to have earned a filter box. Losing it there would be a real
 * regression for the way these forms are actually filled: focus Gender, press "f", get Female,
 * without the control ever opening. A list WITH a filter box has a better answer already, and
 * running both would mean two different things happening for one keystroke.
 */
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

/** Next enabled index walking `delta` (+1/-1) from `current`, wrapping around. */
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

/** A character key rather than a chord or a named key — Space excluded, it toggles the panel. */
function isPrintable(event: React.KeyboardEvent) {
  return event.key.length === 1 && event.key !== " " && !event.ctrlKey && !event.metaKey && !event.altKey;
}

function firstEnabled(options: SelectOption[]) {
  return options.findIndex((option) => !option.disabled);
}

/**
 * The purple ramp is brand colour and deliberately does NOT invert with the theme, so purple-50 is
 * near-white in both modes — as a highlight it painted a white bar across a dark menu, and
 * purple-700 text on it went to near-black on near-black once the bar was darkened. The dark
 * counterparts are the ones ui/calendar uses for its day cells, so a menu and a calendar highlight
 * the thing under the cursor identically.
 */
function optionClass(option: SelectOption, highlighted: boolean, active: boolean) {
  return cn(
    "flex items-center gap-2 px-3.5 py-2 text-sm",
    option.disabled ? "cursor-not-allowed text-ink-300" : "cursor-pointer",
    !option.disabled && highlighted && "bg-purple-50 dark:bg-purple-950",
    active ? "font-medium text-purple-700 dark:text-purple-300" : !option.disabled ? "text-ink-700" : ""
  );
}

/**
 * Keeps the highlighted row on screen.
 *
 * `block: "nearest"` rather than "center" so arrowing down a long list scrolls by one row instead
 * of jumping the viewport around under the reader.
 */
function useScrollHighlightIntoView(open: boolean, highlight: number, baseId: string) {
  useEffect(() => {
    if (!open || highlight < 0) return;
    document.getElementById(`${baseId}-opt-${highlight}`)?.scrollIntoView({ block: "nearest" });
  }, [open, highlight, baseId]);
}

/**
 * Everything the panel needs, derived rather than stored.
 *
 * The one piece of raw state is `highlight`, and even that is passed through `safeHighlight` before
 * anything reads it — see the file header on why a stored index is not trustworthy here.
 */
function useSelectList(
  options: SelectOption[],
  query: string,
  searchable: boolean,
  chosen: ReadonlySet<string>
) {
  const filtered = useMemo(
    () => (searchable ? filterOptions(options, query) : options),
    [options, query, searchable]
  );
  const rendered = useMemo(() => {
    if (filtered.length <= RENDER_CAP) return filtered;
    const window = filtered.slice(0, RENDER_CAP);
    // A cap must never hide what the reader already picked. India is the 100-somethingth of 246
    // dial codes, so the plain first-80 window reopened the country picker with no tick anywhere in
    // it and no hint that the value was real — the control would have been lying about its own
    // state. Anything selected that the window missed is pinned to the top, where the check mark
    // says what it is.
    const missing = filtered.filter((option) => chosen.has(option.value) && !window.includes(option));
    return missing.length ? [...missing, ...window] : window;
  }, [filtered, chosen]);
  return { filtered, rendered, capped: filtered.length - rendered.length };
}

/**
 * Keeps the panel's own keystrokes inside the panel, and why that is not paranoia.
 *
 * A React portal moves the panel out of the DOM but NOT out of the React tree, so synthetic events
 * still bubble to whatever component rendered the select. Every record form in this app is
 * `<form onInput={markDirty} onKeyDown={handleFormEnter}>`, and both would have picked up the
 * filter box:
 *
 * - Typing three letters to find a craft, changing nothing, would arm the unsaved-changes prompt,
 *   so a reader who searched, picked nothing and pressed Escape could not leave the page.
 * - Enter to take the highlighted match would ALSO reach `handleFormEnter`, which sees a plain text
 *   input and advances focus. Worse, `focusNextField` scopes itself with `closest("form")` — null
 *   for a node portalled to `<body>` — so it would fall back to the whole document and throw focus
 *   at an unrelated field on the other side of the page.
 *
 * Nothing outside the panel has any business knowing what was typed into a filter, so the whole
 * class of problem is cut off here rather than patched per form. Escape is unaffected: AnchoredPopover
 * takes it on a window-CAPTURE listener, which runs before any of this.
 */
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

/** Shared search row so the single- and multi-select boxes are the same box. */
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
          className="w-full rounded-sm border border-line-200 bg-card py-1.5 pl-8 pr-2 text-sm text-ink-900 outline-none transition placeholder:text-ink-300 focus:border-purple-600 focus:ring-2 focus:ring-purple-600/15"
        />
      </div>
      {trailing}
    </div>
  );
}

/** The "N of M shown" footer. Only drawn when the cap actually bit. */
function CapNotice({ shown, total }: { shown: number; total: number }) {
  return (
    <p className="shrink-0 border-t border-line-200 bg-surface-50 px-3.5 py-2 text-xs leading-4 text-ink-500">
      Showing the first {shown} of {total}. Keep typing to narrow the list.
    </p>
  );
}

/**
 * Tab out of either end of the panel.
 *
 * NOT a focus trap: the point is to leave, just to leave somewhere useful. Tab past the last
 * control commits and moves to the next field in the form (the panel is portalled to the end of
 * `<body>`, so the browser's own answer would be the URL bar); Shift+Tab off the first control
 * puts focus back on the trigger, where the reader's previous Shift+Tab will behave normally.
 * Everything between the two ends is left entirely to the browser.
 */
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

/**
 * Focus the filter box as it mounts — but only if the keyboard is still where opening left it.
 *
 * Two things make this less trivial than an effect keyed on `open`. The panel is portalled and only
 * mounts once AnchoredPopover has resolved its host, a render later than `open` flipping, so an
 * effect fires before the box exists; attaching the focus to the ref callback is the one moment
 * guaranteed to be after it.
 *
 * The second is the reason for the guard, and it was caught on a real page rather than reasoned
 * about. Options that arrive over the network are not there when the panel opens: on /tools the
 * craft list took 1.8s, so the select opened with zero options, sat BELOW the search threshold, and
 * rendered no filter box at all — then grew one when the response landed. An unconditional focus
 * there yanks the keyboard out of whatever the reader moved on to, seconds after they opened the
 * menu, which on the rural connections this app is built for is the normal case and not the edge
 * one. So the box claims focus only from the trigger it belongs to (or from nothing at all), and
 * otherwise lets the reader be.
 */
function useFilterBoxFocus(triggerRef: React.RefObject<HTMLButtonElement | null>) {
  return useCallback(
    (node: HTMLInputElement | null) => {
      if (!node) return;
      // Deferred a frame rather than focused inline, and this one is load-bearing. Refs attach
      // bottom-up, so at the moment this callback runs AnchoredPopover's own panel ref — held on an
      // ANCESTOR of this input — is still null. Its focus-out guard asks
      // `panelRef.current.contains(target)` to decide whether focus went somewhere legitimate, and
      // against a null panel an in-panel focus is indistinguishable from a click-away: the country
      // picker closed itself in the same tick it opened, every time. A frame later every ref is in
      // place and the guard can answer correctly.
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
  /**
   * `undefined` lets `SEARCH_THRESHOLD` decide, which is what almost every call site should do.
   * Force it on for a list that is short today and long next month, off for one that is a fixed
   * vocabulary however many entries it grows.
   */
  searchable?: boolean;
  /**
   * After a value is picked, close and move focus to the next field. On by default: these forms are
   * filled top-to-bottom in one pass, and leaving focus parked on the trigger makes the next Tab
   * land somewhere the researcher did not expect. Set false for a dropdown that FILTERS the screen
   * it sits on (a list funnel, a taxonomy switcher) rather than filling in a form field — there,
   * jumping focus away from the control you are adjusting is wrong.
   */
  advanceOnSelect?: boolean;
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
  advanceOnSelect = true
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
  const chosen = useMemo(() => new Set(value ? [value] : []), [value]);
  const { filtered, rendered, capped } = useSelectList(options, query, withSearch, chosen);

  // Derived, not stored — the stored index is only ever a hint. See the file header.
  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId);

  const selected = options.find((option) => option.value === value);

  const close = useCallback(() => {
    setOpen(false);
    setQuery("");
  }, []);

  /** Close, then hand the keyboard on. `advanceOnSelect` decides whether "on" means the next field. */
  const closeAndMoveOn = useCallback(
    (advance: boolean) => {
      close();
      // After the state flush, so the panel has unmounted and the walker sees the settled DOM;
      // otherwise the still-open listbox can swallow the focus we are trying to move on.
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
    setQuery("");
    setOpen(true);
    // Indexed against `rendered`, which is what the highlight means everywhere else. Against the
    // raw options array a pinned selection past the cap would highlight whatever happened to sit at
    // that index instead.
    const at = rendered.findIndex((option) => option.value === value);
    setHighlight(at >= 0 && !rendered[at].disabled ? at : firstEnabled(rendered));
  }

  /** Typing in the box always re-aims Enter at the top match. */
  function onQueryChange(next: string) {
    setQuery(next);
    setHighlight(0);
  }

  const onTabForward = useCallback(() => {
    // Type three letters, Tab, carry on: the highlight is what the reader is looking at, so
    // leaving without it would silently discard the choice they had already made visually.
    if (safeHighlight >= 0 && rendered[safeHighlight]) onChange(rendered[safeHighlight].value);
    closeAndMoveOn(true);
  }, [safeHighlight, rendered, onChange, closeAndMoveOn]);

  const onTabBackward = useCallback(() => closeAndMoveOn(false), [closeAndMoveOn]);
  const onPanelKeyDown = useEdgeTab(panelRef, onTabForward, onTabBackward);

  /** Arrow/Enter/Home/End, shared by the trigger (short lists) and the search box (long ones). */
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
        // Guarded on the DERIVED index, so Enter can only ever take a row that is on screen.
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
    // Native-<select> type-ahead, and only where there is no filter box to do the job properly.
    if (!withSearch && isPrintable(event)) {
      event.preventDefault();
      const query = typeahead(event.key);
      const match = options.findIndex((option) => !option.disabled && fold(option.label).startsWith(query));
      if (match < 0) return;
      if (open) setHighlight(match);
      else onChange(options[match].value);
    }
    // Escape is not handled here: AnchoredPopover takes it on a window-capture listener, closes the
    // topmost panel and restores focus to `restoreFocusRef`, which is this trigger.
  }

  const inputRef = useFilterBoxFocus(triggerRef);

  const announcement = withSearch && query.trim()
    ? `${filtered.length} of ${options.length} options match ${query.trim()}`
    : `${options.length} options`;

  return (
    // `min-w-0` is load-bearing, not tidying. A grid or flex item defaults to `min-width: auto`,
    // which refuses to shrink below its content's intrinsic width — so a long option label widened
    // this wrapper past its column and the trigger overlapped the field beside it. The `truncate`
    // on the label inside cannot prevent that on its own: it clips text within a box that has
    // already been allowed to grow. The box has to be allowed to shrink first.
    <div ref={wrapperRef} className={cn("relative min-w-0", className)}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        aria-controls={open ? listboxId : undefined}
        // Only when the trigger itself owns the keyboard; with a search box the input does.
        aria-activedescendant={open && !withSearch ? activeId : undefined}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={onTriggerKeyDown}
        // Marks the trigger as a form field for lib/formNav: without it the walker cannot locate
        // this control, focusNextField() bails, and picking a value leaves focus parked here
        // instead of moving on to the next field.
        data-form-field=""
        data-searchable-select=""
        className={TRIGGER_CLASS}
      >
        <span className={cn("min-w-0 truncate", !selected && "text-ink-300")} title={selected?.label}>
          {selected ? selected.label : placeholder}
        </span>
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
            />
          ) : null}
          <ul
            id={listboxId}
            role="listbox"
            aria-label={ariaLabel ?? "Options"}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {rendered.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-300">{query.trim() ? "No matches" : emptyLabel}</li>
            ) : null}
            {rendered.map((option, index) => {
              const active = option.value === value;
              return (
                <li
                  key={option.value}
                  id={`${baseId}-opt-${index}`}
                  role="option"
                  aria-selected={active}
                  aria-disabled={option.disabled || undefined}
                  title={option.label}
                  onMouseEnter={() => {
                    if (!option.disabled) setHighlight(index);
                  }}
                  // Keeps focus in the search box so the reader can keep typing after a mis-click,
                  // and stops the browser hunting for a focus target as the row is removed.
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
          {capped > 0 ? <CapNotice shown={rendered.length} total={filtered.length} /> : null}
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
  /**
   * Show a Confirm button in the panel once at least one option is ticked; confirming closes and
   * moves to the next field.
   *
   * A multi-select cannot advance on click the way a single-select does — picking one option is
   * usually not the end of the answer — so without an explicit "done" the researcher has to know to
   * click away, and the form gives no signal that the answer was registered. Set false where the
   * control filters a list in place rather than answering a form field.
   */
  confirmOnSelect?: boolean;
  confirmLabel?: string;
};

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
  confirmLabel = "Confirm"
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
  const { filtered, rendered, capped } = useSelectList(options, query, withSearch, chosen);

  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId);

  const close = useCallback(() => {
    setOpen(false);
    setQuery("");
  }, []);

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

  /**
   * What "select all" acts on, and why it is the filtered set rather than every option.
   *
   * The reader typed to narrow the list; acting on the rows they deliberately filtered OUT would
   * be the one outcome they cannot see coming. So the button scopes itself to the matches, says
   * which it did ("Select 6 matching" vs "Select all 74"), and leaves any selection made outside
   * the current filter completely alone — clearing 6 matches never quietly drops a 7th pick that
   * the query happens to hide. The cap on rendered rows is NOT applied here: the label promises
   * every match, so every match is what it takes.
   */
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
    // Appended rather than rebuilt, so the order the reader picked things in survives.
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
        // Ticks and STAYS OPEN — picking one option is rarely the end of a multi-select answer.
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
    // Type-ahead only MOVES the highlight here — a multi-select must never tick a box from a
    // keystroke aimed at finding one.
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

  const chosenLabels = useMemo(
    () => options.filter((option) => chosen.has(option.value)).map((option) => option.label),
    [options, chosen]
  );
  /** The selected set as prose, so a screen reader gets the names and not just "3 selected". */
  const selectionSummary = chosenLabels.length
    ? chosenLabels.length <= SUMMARY_NAMES
      ? `${chosenLabels.length} selected: ${chosenLabels.join(", ")}`
      : `${chosenLabels.length} selected, including ${chosenLabels.slice(0, SUMMARY_NAMES).join(", ")}`
    : "Nothing selected";
  const announcement = `${
    filtering ? `${filtered.length} of ${options.length} options match ${query.trim()}` : `${options.length} options`
  }. ${selectionSummary}.`;

  /**
   * Reached with the mouse, and by Tab — it is the next control after the filter box, so a keyboard
   * reader finds it by walking rather than by knowing. An earlier draft also bound Ctrl/Cmd+A as a
   * shortcut; it was dropped because inside a text box that chord already means "select the text I
   * just typed", and quietly redefining it to "tick 74 rows" is exactly the kind of surprise this
   * control is supposed to avoid.
   */
  const bulkButton =
    bulk.length > 0 ? (
      <button
        type="button"
        onClick={applyBulk}
        onMouseDown={(event) => event.preventDefault()}
        className="shrink-0 whitespace-nowrap rounded-sm border border-line-200 bg-card px-2 py-1.5 text-xs font-medium text-purple-700 outline-none transition hover:border-purple-300 hover:bg-purple-50 focus-visible:border-purple-600 focus-visible:ring-2 focus-visible:ring-purple-600/20 dark:text-purple-300 dark:hover:bg-purple-950"
      >
        {bulkLabel}
      </button>
    ) : null;

  return (
    <div ref={wrapperRef} className={cn("relative min-w-0", className)}>
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
        <span className={cn("min-w-0 truncate", !values.length && "text-ink-300")}>
          {values.length ? `${values.length} selected` : placeholder}
        </span>
        {/* Without an ariaLabel the button's accessible name is its content, so the names go here. */}
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
            // No search box on a short list, but "select all" still earns its place — four crafts
            // is four clicks otherwise.
            <div className="flex shrink-0 justify-end border-b border-line-200 p-2">{bulkButton}</div>
          ) : null}
          <ul
            id={listboxId}
            role="listbox"
            aria-multiselectable
            aria-label={ariaLabel ?? "Options"}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {rendered.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-300">{query.trim() ? "No matches" : emptyLabel}</li>
            ) : null}
            {rendered.map((option, index) => {
              const checked = chosen.has(option.value);
              return (
                <li
                  key={option.value}
                  id={`${baseId}-opt-${index}`}
                  role="option"
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
          {capped > 0 ? <CapNotice shown={rendered.length} total={filtered.length} /> : null}
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
  );
}
