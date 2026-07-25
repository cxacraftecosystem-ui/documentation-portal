"use client";

import { Check, ChevronDown } from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import { focusNextField } from "@/lib/formNav";
import { cn } from "@/lib/utils";

export type DropdownOption = { value: string; label: string; disabled?: boolean };

function useOutsideClose(ref: React.RefObject<HTMLElement | null>, onClose: () => void) {
  useEffect(() => {
    function handle(event: MouseEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) onClose();
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("mousedown", handle);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", handle);
      document.removeEventListener("keydown", onKey);
    };
  }, [ref, onClose]);
}

const triggerClass =
  "flex w-full items-center justify-between gap-2 rounded-md border border-line-200 bg-card px-3.5 py-2.5 text-left text-sm text-ink-900 outline-none transition hover:border-purple-300 focus:border-purple-600 focus:ring-4 focus:ring-purple-600/15 disabled:cursor-not-allowed disabled:opacity-60";
const menuClass =
  "absolute z-50 mt-1 max-h-72 w-full min-w-full overflow-auto rounded-md border border-line-200 bg-card py-1 shadow-md";

/** Next enabled option index walking `delta` (+1/-1) from `current`, wrapping around. */
function moveHighlight(options: DropdownOption[], current: number, delta: number) {
  const n = options.length;
  if (!n) return -1;
  let i = current < 0 || current >= n ? (delta > 0 ? -1 : n) : current;
  for (let step = 0; step < n; step++) {
    i = (i + delta + n) % n;
    if (!options[i].disabled) return i;
  }
  return -1;
}

/** Rolling type-ahead buffer (~700ms window, like the native <select>). */
function useTypeahead() {
  const state = useRef({ text: "", at: 0 });
  function push(char: string) {
    const now = Date.now();
    if (now - state.current.at > 700) state.current.text = "";
    state.current.at = now;
    state.current.text += char.toLowerCase();
    return state.current.text;
  }
  function active() {
    return state.current.text.length > 0 && Date.now() - state.current.at <= 700;
  }
  return { push, active };
}

function useScrollHighlightIntoView(open: boolean, highlight: number, baseId: string) {
  useEffect(() => {
    if (!open || highlight < 0) return;
    document.getElementById(`${baseId}-opt-${highlight}`)?.scrollIntoView({ block: "nearest" });
  }, [open, highlight, baseId]);
}

function optionClass(option: DropdownOption, highlighted: boolean, active: boolean) {
  return cn(
    "flex items-center gap-2 px-3.5 py-2 text-sm",
    option.disabled ? "cursor-not-allowed text-ink-300" : "cursor-pointer",
    !option.disabled && highlighted && "bg-purple-50",
    active ? "font-medium text-purple-700" : !option.disabled ? "text-ink-700" : ""
  );
}

/** Themed single-select dropdown — the app's replacement for the plain browser <select>. */
export function Dropdown({
  value,
  onChange,
  options,
  placeholder = "Select",
  disabled,
  className,
  ariaLabel,
  advanceOnSelect = true
}: {
  value: string;
  onChange: (value: string) => void;
  options: DropdownOption[];
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  /**
   * After a value is picked, close and move focus to the next field. On by default: these forms are
   * filled top-to-bottom in one pass, and leaving focus parked on the trigger makes the next Tab
   * land somewhere the researcher did not expect. Set false for a dropdown that FILTERS the screen
   * it sits on (a list funnel, a taxonomy switcher) rather than filling in a form field — there,
   * jumping focus away from the control you are adjusting is wrong.
   */
  advanceOnSelect?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(-1);
  const ref = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const typeahead = useTypeahead();
  useOutsideClose(ref, () => setOpen(false));
  useScrollHighlightIntoView(open, highlight, baseId);

  const selectedIndex = options.findIndex((option) => option.value === value);
  const selected = selectedIndex >= 0 ? options[selectedIndex] : undefined;

  function openMenu() {
    setOpen(true);
    setHighlight(selectedIndex >= 0 && !options[selectedIndex].disabled ? selectedIndex : moveHighlight(options, -1, 1));
  }

  function choose(index: number) {
    const option = options[index];
    if (!option || option.disabled) return;
    onChange(option.value);
    setOpen(false);
    if (!advanceOnSelect) return;
    // After the state flush, so the menu has unmounted and the walker sees the settled DOM;
    // otherwise the still-open listbox can swallow the focus we are trying to move on.
    requestAnimationFrame(() => {
      if (!focusNextField(ref.current)) ref.current?.querySelector("button")?.focus();
    });
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    const isChar = event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey;
    if (isChar && (event.key !== " " || typeahead.active())) {
      event.preventDefault();
      const query = typeahead.push(event.key);
      const match = options.findIndex((option) => !option.disabled && option.label.toLowerCase().startsWith(query));
      if (match >= 0) {
        if (open) setHighlight(match);
        else onChange(options[match].value); // native-select behavior while closed
      }
      return;
    }
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) setHighlight((h) => moveHighlight(options, h, 1));
        else openMenu();
        break;
      case "ArrowUp":
        event.preventDefault();
        if (open) setHighlight((h) => moveHighlight(options, h, -1));
        else openMenu();
        break;
      case "Home":
        if (open) {
          event.preventDefault();
          setHighlight(moveHighlight(options, -1, 1));
        }
        break;
      case "End":
        if (open) {
          event.preventDefault();
          setHighlight(moveHighlight(options, -1, -1));
        }
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        if (open) choose(highlight);
        else openMenu();
        break;
      case "Escape":
        if (open) {
          event.preventDefault();
          setOpen(false);
        }
        break;
      case "Tab":
        setOpen(false);
        break;
    }
  }

  return (
    <div ref={ref} className={`relative ${className ?? ""}`}>
      <button
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        aria-controls={open ? `${baseId}-listbox` : undefined}
        aria-activedescendant={open && highlight >= 0 ? `${baseId}-opt-${highlight}` : undefined}
        onClick={() => (open ? setOpen(false) : openMenu())}
        onKeyDown={onTriggerKeyDown}
        // Marks the trigger as a form field for lib/formNav: without it the walker cannot locate this
        // control, focusNextField() bails, and picking a value leaves focus parked on the trigger
        // instead of moving on to the next field.
        data-form-field=""
        className={triggerClass}
      >
        <span className={cn("min-w-0 truncate", !selected && "text-ink-300")} title={selected?.label}>
          {selected ? selected.label : placeholder}
        </span>
        <ChevronDown className={`h-4 w-4 shrink-0 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <ul id={`${baseId}-listbox`} role="listbox" className={menuClass}>
          {options.length === 0 ? <li className="px-3.5 py-2 text-sm text-ink-300">No options</li> : null}
          {options.map((option, index) => {
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
                onMouseDown={(event) => event.preventDefault()}
                // preventDefault is load-bearing, not decoration: `FormControls.Field` wraps every
                // field in a <label>, and a click on a non-interactive descendant of a label is
                // FORWARDED by the browser to the label's first labelable control — here the trigger
                // <button>. That second click ran `onClick` again and re-opened the menu the moment
                // it closed. Cancelling the click suppresses the label's activation behaviour.
                onClick={(event) => {
                  event.preventDefault();
                  choose(index);
                }}
                className={optionClass(option, index === highlight, active)}
              >
                <span className="min-w-0 flex-1 truncate">{option.label}</span>
                {active ? <Check className="h-4 w-4 shrink-0 text-purple-700" aria-hidden /> : null}
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}

/** Themed multi-select dropdown with checkboxes (e.g. pick several crafts / artisans at once). */
export function MultiSelectDropdown({
  values,
  onChange,
  options,
  placeholder = "Select",
  emptyLabel = "No options",
  disabled,
  className,
  confirmOnSelect = true,
  confirmLabel = "Confirm"
}: {
  values: string[];
  onChange: (values: string[]) => void;
  options: DropdownOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  /**
   * Show a Confirm button in the menu once at least one option is ticked; confirming closes the
   * menu and moves to the next field.
   *
   * A multi-select cannot advance on click the way a single-select does — picking one option is
   * usually not the end of the answer — so without an explicit "done" the researcher has to know to
   * click away, and the form gives no signal that the answer was registered. Set false where the
   * control filters a list in place rather than answering a form field.
   */
  confirmOnSelect?: boolean;
  confirmLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(-1);
  const ref = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const typeahead = useTypeahead();
  useOutsideClose(ref, () => setOpen(false));
  useScrollHighlightIntoView(open, highlight, baseId);

  const selected = useMemo(() => new Set(values), [values]);

  function toggle(index: number) {
    const option = options[index];
    if (!option || option.disabled) return;
    const next = new Set(selected);
    if (next.has(option.value)) next.delete(option.value);
    else next.add(option.value);
    onChange(Array.from(next));
  }

  function openMenu() {
    setOpen(true);
    setHighlight(moveHighlight(options, -1, 1));
  }

  /** Close the menu and move on — the multi-select equivalent of picking a single value. */
  function confirmSelection() {
    setOpen(false);
    requestAnimationFrame(() => {
      if (!focusNextField(ref.current)) ref.current?.querySelector("button")?.focus();
    });
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    const isChar = event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey;
    if (isChar && (event.key !== " " || typeahead.active())) {
      event.preventDefault();
      const query = typeahead.push(event.key);
      const match = options.findIndex((option) => !option.disabled && option.label.toLowerCase().startsWith(query));
      if (match >= 0) {
        if (!open) setOpen(true);
        setHighlight(match);
      }
      return;
    }
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) setHighlight((h) => moveHighlight(options, h, 1));
        else openMenu();
        break;
      case "ArrowUp":
        event.preventDefault();
        if (open) setHighlight((h) => moveHighlight(options, h, -1));
        else openMenu();
        break;
      case "Home":
        if (open) {
          event.preventDefault();
          setHighlight(moveHighlight(options, -1, 1));
        }
        break;
      case "End":
        if (open) {
          event.preventDefault();
          setHighlight(moveHighlight(options, -1, -1));
        }
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        if (open) toggle(highlight); // stays open so several options can be picked
        else openMenu();
        break;
      case "Escape":
        if (open) {
          event.preventDefault();
          setOpen(false);
        }
        break;
      case "Tab":
        setOpen(false);
        break;
    }
  }

  return (
    <div ref={ref} className={`relative ${className ?? ""}`}>
      <button
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? `${baseId}-listbox` : undefined}
        aria-activedescendant={open && highlight >= 0 ? `${baseId}-opt-${highlight}` : undefined}
        onClick={() => (open ? setOpen(false) : openMenu())}
        onKeyDown={onTriggerKeyDown}
        // See the single-select trigger: lib/formNav only walks controls carrying this attribute, so
        // without it confirmSelection() cannot find the next field and focus stays on this trigger.
        data-form-field=""
        className={triggerClass}
      >
        <span className={cn("min-w-0 truncate", !values.length && "text-ink-300")}>
          {values.length ? `${values.length} selected` : placeholder}
        </span>
        <ChevronDown className={`h-4 w-4 shrink-0 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <ul id={`${baseId}-listbox`} role="listbox" aria-multiselectable className={menuClass}>
          {options.length === 0 ? <li className="px-3.5 py-2 text-sm text-ink-300">{emptyLabel}</li> : null}
          {options.map((option, index) => {
            const checked = selected.has(option.value);
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
                // See the single-select above: without this the enclosing <label> forwards the click
                // to the trigger button, which CLOSED the menu after every single tick — so only one
                // option could ever be picked per opening and the Confirm button never appeared.
                onClick={(event) => {
                  event.preventDefault();
                  toggle(index);
                }}
                className={optionClass(option, index === highlight, checked)}
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
          {confirmOnSelect && values.length > 0 ? (
            // Inside the listbox but outside the option list, and role="none" so it is not
            // announced as a selectable option to a screen reader.
            <li role="none" className="sticky bottom-0 border-t border-line-200 bg-card p-2">
              <button
                type="button"
                className="field-button w-full py-1.5 text-xs"
                onClick={confirmSelection}
                onMouseDown={(event) => event.preventDefault()}
              >
                {confirmLabel} ({values.length})
              </button>
            </li>
          ) : null}
        </ul>
      ) : null}
    </div>
  );
}

/**
 * Text-input-filtered dropdown: type to narrow the options, pick with mouse or arrows+Enter.
 * When `name` is set a hidden input submits the selected VALUE with the surrounding form.
 */
export function ComboBox({
  options,
  value,
  onChange,
  placeholder = "Select or type to search",
  name
}: {
  options: { value: string; label: string }[];
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  name?: string;
}) {
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(-1);
  const ref = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const baseId = useId();
  useScrollHighlightIntoView(open, highlight, baseId);

  const selected = options.find((option) => option.value === value);
  const [text, setText] = useState(selected?.label ?? "");

  // Keep the visible text in sync when the value changes from outside (not mid-typing).
  const selectedLabel = selected?.label ?? "";
  useEffect(() => {
    if (document.activeElement !== inputRef.current) setText(selectedLabel);
  }, [value, selectedLabel]);

  // Showing the full label of the current pick should not filter the list down to itself.
  const filterText = selected && text === selected.label ? "" : text.trim().toLowerCase();
  const filtered = useMemo(
    () => (filterText ? options.filter((option) => option.label.toLowerCase().includes(filterText)) : options),
    [options, filterText]
  );

  function pick(option: { value: string; label: string }) {
    onChange(option.value);
    setText(option.label);
    setOpen(false);
  }

  /** Close without an explicit pick: commit an exact label match, clear on empty, else revert. */
  function settle() {
    const trimmed = text.trim();
    if (!trimmed) {
      if (value) onChange("");
      setText("");
      return;
    }
    const exact = options.find((option) => option.label.toLowerCase() === trimmed.toLowerCase());
    if (exact) {
      if (exact.value !== value) onChange(exact.value);
      setText(exact.label);
      return;
    }
    setText(selectedLabel);
  }

  // No dependency array: re-registered every render so `open`/`settle` are never stale.
  useEffect(() => {
    function handle(event: MouseEvent) {
      if (!ref.current || ref.current.contains(event.target as Node)) return;
      if (open) {
        setOpen(false);
        settle();
      }
    }
    document.addEventListener("mousedown", handle);
    return () => document.removeEventListener("mousedown", handle);
  });

  function openList() {
    setOpen(true);
    const index = filtered.findIndex((option) => option.value === value);
    setHighlight(index >= 0 ? index : filtered.length ? 0 : -1);
  }

  function onKeyDown(event: React.KeyboardEvent) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) setHighlight((h) => moveHighlight(filtered, h, 1));
        else openList();
        break;
      case "ArrowUp":
        event.preventDefault();
        if (open) setHighlight((h) => moveHighlight(filtered, h, -1));
        else openList();
        break;
      case "Enter":
        if (open) {
          event.preventDefault();
          const choice = highlight >= 0 && highlight < filtered.length ? filtered[highlight] : filtered.length === 1 ? filtered[0] : undefined;
          if (choice) pick(choice);
          else {
            setOpen(false);
            settle();
          }
        }
        break;
      case "Escape":
        if (open) {
          event.preventDefault();
          event.stopPropagation();
          setOpen(false);
          setText(selectedLabel);
        }
        break;
      case "Tab":
        if (open) {
          setOpen(false);
          settle();
        }
        break;
    }
  }

  return (
    <div ref={ref} className="relative">
      {name ? <input type="hidden" name={name} value={value} /> : null}
      <input
        ref={inputRef}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-controls={open ? `${baseId}-listbox` : undefined}
        aria-autocomplete="list"
        aria-activedescendant={open && highlight >= 0 ? `${baseId}-opt-${highlight}` : undefined}
        value={text}
        placeholder={placeholder}
        onFocus={openList}
        onClick={() => {
          if (!open) openList();
        }}
        onChange={(event) => {
          setText(event.target.value);
          if (!open) setOpen(true);
          setHighlight(0);
        }}
        onKeyDown={onKeyDown}
        className="field-input pr-9"
      />
      <ChevronDown
        className={`pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`}
        aria-hidden
      />
      {open ? (
        <ul id={`${baseId}-listbox`} role="listbox" className={menuClass}>
          {filtered.length === 0 ? <li className="px-3.5 py-2 text-sm text-ink-300">No matches</li> : null}
          {filtered.map((option, index) => {
            const active = option.value === value;
            return (
              <li
                key={option.value}
                id={`${baseId}-opt-${index}`}
                role="option"
                aria-selected={active}
                title={option.label}
                onMouseEnter={() => setHighlight(index)}
                onMouseDown={(event) => event.preventDefault()}
                // Same label-forwarding guard as the two dropdowns above; here the label's control is
                // the search <input>, whose onFocus re-opened the list straight after picking.
                onClick={(event) => {
                  event.preventDefault();
                  pick(option);
                }}
                className={optionClass(option, index === highlight, active)}
              >
                <span className="min-w-0 flex-1 truncate">{option.label}</span>
                {active ? <Check className="h-4 w-4 shrink-0 text-purple-700" aria-hidden /> : null}
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}
