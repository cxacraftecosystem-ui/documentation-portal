"use client";

import { Check, ChevronDown } from "lucide-react";
import { useEffect, useRef, useState } from "react";

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
  "flex w-full items-center justify-between gap-2 rounded-md border border-[#e6dfd8] bg-field-50 px-3.5 py-2.5 text-left text-sm text-ink outline-none transition hover:bg-field-100 focus:border-field-500 focus:ring-4 focus:ring-field-500/15 disabled:cursor-not-allowed disabled:opacity-60";
const menuClass =
  "absolute z-50 mt-1 max-h-72 w-full min-w-full overflow-auto rounded-md border border-[#e6dfd8] bg-field-50 py-1 shadow-xl ring-1 ring-black/5";

/** Themed single-select dropdown — the app's replacement for the plain browser <select>. */
export function Dropdown({
  value,
  onChange,
  options,
  placeholder = "Select",
  disabled,
  className,
  ariaLabel
}: {
  value: string;
  onChange: (value: string) => void;
  options: DropdownOption[];
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useOutsideClose(ref, () => setOpen(false));
  const selected = options.find((option) => option.value === value);

  return (
    <div ref={ref} className={`relative ${className ?? ""}`}>
      <button
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => setOpen((prev) => !prev)}
        className={triggerClass}
      >
        <span className={`truncate ${selected ? "" : "text-ink-soft"}`}>{selected ? selected.label : placeholder}</span>
        <ChevronDown className={`h-4 w-4 shrink-0 text-ink-muted transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <ul role="listbox" className={menuClass}>
          {options.length === 0 ? <li className="px-3.5 py-2 text-sm text-ink-soft">No options</li> : null}
          {options.map((option) => {
            const active = option.value === value;
            return (
              <li
                key={option.value}
                role="option"
                aria-selected={active}
                aria-disabled={option.disabled}
                onClick={() => {
                  if (option.disabled) return;
                  onChange(option.value);
                  setOpen(false);
                }}
                className={`flex items-center justify-between gap-2 px-3.5 py-2 text-sm ${
                  option.disabled ? "cursor-not-allowed text-ink-soft" : "cursor-pointer text-ink hover:bg-field-100"
                } ${active ? "bg-field-100 font-medium" : ""}`}
              >
                <span className="truncate">{option.label}</span>
                {active ? <Check className="h-4 w-4 shrink-0 text-field-600" aria-hidden /> : null}
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
  className
}: {
  values: string[];
  onChange: (values: string[]) => void;
  options: DropdownOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useOutsideClose(ref, () => setOpen(false));
  const selected = new Set(values);

  function toggle(value: string) {
    const next = new Set(selected);
    if (next.has(value)) next.delete(value);
    else next.add(value);
    onChange(Array.from(next));
  }

  return (
    <div ref={ref} className={`relative ${className ?? ""}`}>
      <button
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
        className={triggerClass}
      >
        <span className={`truncate ${values.length ? "" : "text-ink-soft"}`}>
          {values.length ? `${values.length} selected` : placeholder}
        </span>
        <ChevronDown className={`h-4 w-4 shrink-0 text-ink-muted transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <ul role="listbox" aria-multiselectable className={menuClass}>
          {options.length === 0 ? <li className="px-3.5 py-2 text-sm text-ink-soft">{emptyLabel}</li> : null}
          {options.map((option) => {
            const checked = selected.has(option.value);
            return (
              <li
                key={option.value}
                role="option"
                aria-selected={checked}
                onClick={() => {
                  if (option.disabled) return;
                  toggle(option.value);
                }}
                className={`flex items-center gap-2 px-3.5 py-2 text-sm ${
                  option.disabled ? "cursor-not-allowed text-ink-soft" : "cursor-pointer text-ink hover:bg-field-100"
                } ${checked ? "bg-field-100" : ""}`}
              >
                <input type="checkbox" readOnly checked={checked} className="pointer-events-none accent-field-600" />
                <span className="truncate">{option.label}</span>
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}
