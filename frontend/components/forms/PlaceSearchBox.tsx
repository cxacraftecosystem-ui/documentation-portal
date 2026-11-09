"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { LoaderCircle, Search } from "lucide-react";

import {
  MIN_QUERY_LENGTH,
  describeSearchFailure,
  placeSearchAvailable,
  searchPlaces,
  type PlaceHit
} from "@/lib/placeSearch";

const DEBOUNCE_MS = 350;

type SearchState = "idle" | "short" | "searching" | "done" | "failed";

export function PlaceSearchBox({
  getProximity,
  onChoose,
  label = "Search for a place",
  hint = "Searching only moves the map. Nothing is filled in until you place the pin yourself.",
  disabled
}: {
  getProximity?: () => { lon: number; lat: number } | null;
  onChoose: (hit: PlaceHit) => void;
  label?: string;
  hint?: string;
  disabled?: boolean;
}) {
  const baseId = useId();
  const inputId = `${baseId}-input`;
  const listboxId = `${baseId}-listbox`;

  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<PlaceHit[]>([]);
  const [state, setState] = useState<SearchState>("idle");
  const [problem, setProblem] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);

  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const request = useRef<AbortController | null>(null);

  const proximityRef = useRef(getProximity);
  const chooseRef = useRef(onChoose);
  useEffect(() => {
    proximityRef.current = getProximity;
    chooseRef.current = onChoose;
  }, [getProximity, onChoose]);

  useEffect(() => {
    const wanted = query.trim();
    request.current?.abort();

    if (wanted.length < MIN_QUERY_LENGTH) {
      setHits([]);
      setProblem(null);
      setState(wanted ? "short" : "idle");
      return;
    }

    setState("searching");
    const timer = window.setTimeout(() => {
      const controller = new AbortController();
      request.current = controller;
      searchPlaces(wanted, { signal: controller.signal, proximity: proximityRef.current?.() ?? null })
        .then((found) => {
          if (controller.signal.aborted) return;
          setHits(found);
          setProblem(null);
          setState("done");
          setHighlight(0);
          setOpen(true);
        })
        .catch((error) => {
          if (controller.signal.aborted) return;
          setHits([]);
          setProblem(describeSearchFailure(error));
          setState("failed");
          setOpen(true);
        });
    }, DEBOUNCE_MS);

    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => () => request.current?.abort(), []);

  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onPointer);
    return () => document.removeEventListener("mousedown", onPointer);
  }, [open]);

  const safeHighlight = highlight >= 0 && highlight < hits.length ? highlight : 0;

  const choose = useCallback((hit: PlaceHit) => {
    chooseRef.current(hit);
    setOpen(false);
  }, []);

  const status =
    state === "short"
      ? `Type at least ${MIN_QUERY_LENGTH} letters to search.`
      : state === "searching"
        ? "Searching…"
        : state === "failed"
          ? (problem ?? "Place search did not respond. Try again.")
          : state === "done"
            ? hits.length
              ? `${hits.length} place${hits.length === 1 ? "" : "s"} found. Use the arrow keys to review them.`
              : `No place in India is called “${query.trim()}”. Check the spelling, try the nearest town, or place the pin by hand.`
            : "";

  if (!placeSearchAvailable()) {
    return (
      <p className="text-xs leading-5 text-ink-500">
        Place search isn&apos;t available right now. Move the map or type the coordinates.
      </p>
    );
  }

  return (
    <div
      ref={wrapperRef}
      className="relative grid gap-1"
      onInput={(event) => event.stopPropagation()}
    >
      <label htmlFor={inputId} className="field-label">
        {label}
      </label>

      <div className="relative">
        <Search
          className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500"
          aria-hidden
        />
        <input
          id={inputId}
          className="field-input pl-9 pr-9"
          type="text"
          role="combobox"
          autoComplete="off"
          aria-expanded={open}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-describedby={`${baseId}-hint`}
          aria-activedescendant={open && hits.length ? `${baseId}-opt-${safeHighlight}` : undefined}
          placeholder="Village, town or district"
          value={query}
          disabled={disabled}
          onChange={(event) => {
            setQuery(event.target.value);
            setHighlight(0);
            setOpen(true);
          }}
          onKeyDown={(event) => {
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setOpen(true);
              setHighlight((current) => Math.min(current + 1, Math.max(0, hits.length - 1)));
            } else if (event.key === "ArrowUp") {
              event.preventDefault();
              setHighlight((current) => Math.max(current - 1, 0));
            } else if (event.key === "Enter") {
              event.preventDefault();
              event.stopPropagation();
              const hit = hits[safeHighlight];
              if (open && hit) choose(hit);
            } else if (event.key === "Escape") {
              if (open) {
                event.preventDefault();
                event.stopPropagation();
                setOpen(false);
              }
            }
          }}
        />
        {state === "searching" ? (
          <LoaderCircle
            className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-ink-500"
            aria-hidden
          />
        ) : null}
      </div>

      <p id={`${baseId}-hint`} className="text-xs leading-5 text-ink-500">
        {hint}
      </p>

      <span className="sr-only" role="status" aria-live="polite">
        {open ? status : ""}
      </span>

      {open && state !== "idle" ? (
        <div className="absolute left-0 right-0 top-full z-10 mt-1 overflow-hidden rounded-md border border-line-200 bg-card shadow-panel">
          <ul id={listboxId} role="listbox" aria-label={label} className="max-h-72 overflow-y-auto">
            {hits.length ? (
              hits.map((hit, index) => (
                <li key={hit.id} id={`${baseId}-opt-${index}`} role="option" aria-selected={index === safeHighlight}>
                  <button
                    type="button"
                    className={`flex w-full flex-col items-start gap-0.5 px-3 py-2 text-left transition ${
                      index === safeHighlight ? "bg-purple-50" : "hover:bg-surface-50"
                    }`}
                    onMouseEnter={() => setHighlight(index)}
                    onClick={() => choose(hit)}
                  >
                    <span className="w-full truncate text-sm text-ink-900">{hit.name}</span>
                    {hit.context ? (
                      <span className="w-full truncate text-xs text-ink-500">{hit.context}</span>
                    ) : null}
                  </button>
                </li>
              ))
            ) : (
              <li
                data-place-search-status
                className={`px-3 py-3 text-sm ${state === "failed" ? "font-medium text-error-600" : "text-ink-500"}`}
              >
                {status}
              </li>
            )}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
