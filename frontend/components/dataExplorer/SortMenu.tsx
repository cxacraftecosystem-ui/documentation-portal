"use client";

import { ArrowDownUp, Check } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { directionsOf, readSort, SORT_DIRECTIONS, SORT_LABEL, sortWords, withKey, writeSort, type SortChoice, type SortKey, type SortList } from "@/lib/dataExplorer/sorting";

export function useRememberedSort(viewer: string, level: string, list: SortList): [SortChoice | null, (choice: SortChoice) => void] {
  const place = `${viewer}\u0000${level}`;
  const [stored, setStored] = useState<{ place: string; choice: SortChoice | null }>(() => ({ place, choice: readSort(viewer, level) }));
  let current = stored;
  if (stored.place !== place) {
    current = { place, choice: readSort(viewer, level) };
    setStored(current);
  }
  const remember = useCallback(
    (choice: SortChoice) => {
      writeSort(viewer, level, choice, list);
      setStored({ place, choice });
    },
    [viewer, level, list, place]
  );
  return [current.choice, remember];
}

const ROW = "flex min-h-10 w-full items-center gap-2 rounded-sm px-3 text-left text-sm transition pointer-coarse:min-h-11";

export function SortMenu({
  value,
  keys,
  onChange,
  label = "Sort",
  variant = "toolbar",
  align = "right",
  className = ""
}: {
  value: SortChoice;
  keys: readonly SortKey[];
  onChange: (choice: SortChoice) => void;
  label?: string;
  variant?: "toolbar" | "pill";
  align?: "left" | "right";
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) setOpen(false);
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("pointerdown", away);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("pointerdown", away);
      window.removeEventListener("keydown", key);
    };
  }, [open]);
  if (keys.length < 2) return null;
  const words = sortWords(value);
  return (
    <div className={`relative ${className}`} ref={box} data-testid="sort-menu">
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`${label}: ${words}`}
        title={`${label}: ${words}`}
        onClick={() => setOpen((shown) => !shown)}
        className={`inline-flex items-center justify-center gap-1.5 border text-[13px] transition ${variant === "pill" ? "min-h-10 rounded-full px-3.5 pointer-coarse:min-h-11" : "h-9 min-w-9 rounded-sm px-2.5 pointer-coarse:h-11 pointer-coarse:min-w-11"} ${open ? "border-purple-300 bg-purple-100 text-purple-800 dark:border-purple-700 dark:bg-purple-950/60 dark:text-purple-200" : `border-line-200 bg-card hover:border-purple-300 ${variant === "pill" ? "text-ink-900" : "text-ink-700"}`}`}
        data-testid="sort-button"
      >
        <ArrowDownUp className="h-4 w-4 shrink-0" aria-hidden />
        <span className={`max-w-[11rem] truncate ${variant === "pill" ? "" : "max-md:hidden"}`}>{words}</span>
      </button>
      {open ? (
        <div role="dialog" aria-label={label} className={`absolute ${align === "left" ? "left-0" : "right-0"} top-[calc(100%+4px)] z-30 grid w-60 gap-1 rounded-md border border-line-200 bg-card p-1 shadow-md`} data-testid="sort-panel">
          <p className="px-3 pt-2 pb-1 text-xs font-semibold tracking-[0.04em] text-ink-500 uppercase">Sort by</p>
          <div role="radiogroup" aria-label="Sort by" className="grid max-h-72 gap-0.5 overflow-y-auto">
            {keys.map((key) => {
              const on = key === value.key;
              return (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={on}
                  onClick={() => onChange(withKey(value, key))}
                  className={`${ROW} ${on ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-900 hover:bg-purple-50 dark:hover:bg-purple-950/40"}`}
                >
                  <span className="min-w-0 flex-1 truncate">{SORT_LABEL[key]}</span>
                  {on ? <Check className="h-4 w-4 shrink-0" aria-hidden /> : null}
                </button>
              );
            })}
          </div>
          {directionsOf(value.key).length > 1 ? (
            <div role="radiogroup" aria-label="Order" className="grid grid-cols-2 gap-1 border-t border-line-200 p-1 pt-2">
              {directionsOf(value.key).map((dir) => {
                const on = dir === value.dir;
                return (
                  <button
                    key={dir}
                    type="button"
                    role="radio"
                    aria-checked={on}
                    onClick={() => onChange({ key: value.key, dir })}
                    className={`min-h-9 rounded-sm px-2 text-[13px] transition pointer-coarse:min-h-11 ${on ? "bg-purple-700 font-semibold text-white dark:bg-purple-300 dark:text-purple-950" : "border border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40"}`}
                  >
                    {SORT_DIRECTIONS[value.key][dir]}
                  </button>
                );
              })}
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
