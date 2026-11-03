"use client";

import { useEffect, useRef } from "react";

const PREFIX = "field_repo_list:";
const SCROLL_PREFIX = "field_repo_scroll:";

export const LIST_PARAMS = { query: "search", page: "page", workshop: "workshop", craft: "craft", artisan: "artisan", filter: "f_" } as const;

export type ListMemory = {
  query?: string;
  applied?: string;
  page?: number;
  funnel?: { workshopId: string; craftId: string; artisanId: string };
  filters?: Record<string, string>;
  scrollY?: number;
};

export function parseListMemory(raw: string | null | undefined): ListMemory | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  const source = parsed as Record<string, unknown>;
  const memory: ListMemory = {};
  if (typeof source.query === "string") memory.query = source.query;
  if (typeof source.applied === "string") memory.applied = source.applied;
  if (typeof source.page === "number" && Number.isInteger(source.page) && source.page >= 1) memory.page = source.page;
  if (typeof source.scrollY === "number" && Number.isFinite(source.scrollY) && source.scrollY >= 0) memory.scrollY = source.scrollY;
  const funnel = source.funnel as Record<string, unknown> | undefined;
  if (
    funnel &&
    typeof funnel === "object" &&
    typeof funnel.workshopId === "string" &&
    typeof funnel.craftId === "string" &&
    typeof funnel.artisanId === "string"
  ) {
    memory.funnel = { workshopId: funnel.workshopId, craftId: funnel.craftId, artisanId: funnel.artisanId };
  }
  const filters = source.filters;
  if (filters && typeof filters === "object" && !Array.isArray(filters)) {
    const kept: Record<string, string> = {};
    for (const [name, value] of Object.entries(filters as Record<string, unknown>)) {
      if (typeof value === "string") kept[name] = value;
    }
    memory.filters = kept;
  }
  return memory;
}

function isListParam(name: string): boolean {
  return (
    name === LIST_PARAMS.query ||
    name === LIST_PARAMS.page ||
    name === LIST_PARAMS.workshop ||
    name === LIST_PARAMS.craft ||
    name === LIST_PARAMS.artisan ||
    name.startsWith(LIST_PARAMS.filter)
  );
}

export function listMemoryToSearch(memory: Omit<ListMemory, "scrollY">, base: string | URLSearchParams = ""): string {
  const source = new URLSearchParams(base);
  const out = new URLSearchParams();
  for (const [name, value] of source) if (!isListParam(name)) out.append(name, value);
  const query = (memory.applied ?? memory.query ?? "").trim();
  if (query) out.set(LIST_PARAMS.query, query);
  if (memory.page && memory.page > 1) out.set(LIST_PARAMS.page, String(memory.page));
  if (memory.funnel) {
    out.set(LIST_PARAMS.workshop, memory.funnel.workshopId);
    out.set(LIST_PARAMS.craft, memory.funnel.craftId);
    out.set(LIST_PARAMS.artisan, memory.funnel.artisanId);
  }
  for (const [name, value] of Object.entries(memory.filters ?? {})) {
    if (value) out.set(`${LIST_PARAMS.filter}${name}`, value);
  }
  const text = out.toString();
  return text ? `?${text}` : "";
}

export function listMemoryFromSearch(search: string | URLSearchParams): ListMemory | null {
  const params = new URLSearchParams(search);
  let found = false;
  for (const name of params.keys()) if (isListParam(name)) found = true;
  if (!found) return null;
  const memory: ListMemory = { filters: {} };
  const query = params.get(LIST_PARAMS.query);
  memory.query = query ?? "";
  memory.applied = query ?? "";
  const page = Number(params.get(LIST_PARAMS.page) ?? "1");
  memory.page = Number.isInteger(page) && page >= 1 ? page : 1;
  if (params.has(LIST_PARAMS.workshop) || params.has(LIST_PARAMS.craft) || params.has(LIST_PARAMS.artisan)) {
    memory.funnel = {
      workshopId: params.get(LIST_PARAMS.workshop) ?? "",
      craftId: params.get(LIST_PARAMS.craft) ?? "",
      artisanId: params.get(LIST_PARAMS.artisan) ?? ""
    };
  }
  for (const [name, value] of params) {
    if (name.startsWith(LIST_PARAMS.filter) && name.length > LIST_PARAMS.filter.length) memory.filters![name.slice(LIST_PARAMS.filter.length)] = value;
  }
  return memory;
}

function read(key: string): ListMemory | null {
  try {
    return parseListMemory(window.sessionStorage.getItem(PREFIX + key));
  } catch {
    return null;
  }
}

function write(key: string, memory: ListMemory) {
  try {
    window.sessionStorage.setItem(PREFIX + key, JSON.stringify(memory));
  } catch {
  }
}

export function readScroll(url: string): number | null {
  try {
    const raw = window.sessionStorage.getItem(SCROLL_PREFIX + url);
    const value = raw === null ? NaN : Number(raw);
    return Number.isFinite(value) && value >= 0 ? value : null;
  } catch {
    return null;
  }
}

export function writeScroll(url: string, y: number) {
  try {
    window.sessionStorage.setItem(SCROLL_PREFIX + url, String(Math.round(y)));
  } catch {
  }
}

export const RESTORE_HOLD_MS = 4000;

export function restoreScroll(target: number) {
  if (target <= 0 || typeof window === "undefined") return;
  const path = window.location.pathname;
  const started = performance.now();
  let done = false;
  const handover = ["wheel", "touchstart", "keydown", "pointerdown"] as const;
  const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(() => apply());
  function stop() {
    if (done) return;
    done = true;
    for (const name of handover) window.removeEventListener(name, stop);
    observer?.disconnect();
  }
  function apply() {
    if (done) return;
    if (window.location.pathname !== path || performance.now() - started > RESTORE_HOLD_MS) {
      stop();
      return;
    }
    if (Math.abs(window.scrollY - target) > 1) window.scrollTo(0, target);
  }
  for (const name of handover) window.addEventListener(name, stop, { passive: true });
  observer?.observe(document.body);
  const step = () => {
    apply();
    if (!done) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

export const PRESS_QUIET_MS = 1000;

export function watchScroll(save: (y: number) => void, allowed: () => boolean): () => void {
  let frame = 0;
  let quietUntil = 0;
  const onPress = (event: Event) => {
    if (event instanceof KeyboardEvent && event.key !== "Enter") return;
    if (allowed()) save(window.scrollY);
    quietUntil = performance.now() + PRESS_QUIET_MS;
  };
  const onScroll = () => {
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (performance.now() < quietUntil || !allowed()) return;
      save(window.scrollY);
    });
  };
  window.addEventListener("scroll", onScroll, { passive: true });
  document.addEventListener("click", onPress, true);
  document.addEventListener("keydown", onPress, true);
  return () => {
    window.removeEventListener("scroll", onScroll);
    document.removeEventListener("click", onPress, true);
    document.removeEventListener("keydown", onPress, true);
    if (frame) cancelAnimationFrame(frame);
  };
}

export function useUrlScrollMemory(ready: boolean) {
  const home = useRef<string | null>(null);
  const target = useRef<number | null>(null);
  const restored = useRef(false);

  useEffect(() => {
    home.current = window.location.pathname;
    target.current = readScroll(window.location.pathname + window.location.search);
    return watchScroll(
      (y) => writeScroll(window.location.pathname + window.location.search, y),
      () => restored.current && window.location.pathname === home.current
    );
  }, []);

  useEffect(() => {
    if (!ready || restored.current) return;
    restored.current = true;
    if (target.current) restoreScroll(target.current);
  }, [ready]);
}

export function useListMemory(key: string, state: Omit<ListMemory, "scrollY">, ready: boolean) {
  const recalled = useRef<ListMemory | null | undefined>(undefined);
  const latest = useRef<Omit<ListMemory, "scrollY">>({ ...state });
  const home = useRef<string | null>(null);
  const arrival = useRef<string | null>(null);
  const restored = useRef(false);

  function here(): boolean {
    return typeof window !== "undefined" && home.current !== null && window.location.pathname === home.current;
  }

  function recall(): ListMemory | null {
    if (recalled.current === undefined) {
      if (typeof window === "undefined") recalled.current = null;
      else {
        if (arrival.current === null) arrival.current = window.location.pathname + window.location.search;
        const fromUrl = listMemoryFromSearch(window.location.search);
        const stored = read(key);
        recalled.current = fromUrl ? { ...fromUrl, scrollY: readScroll(arrival.current) ?? undefined } : stored;
      }
    }
    return recalled.current;
  }

  useEffect(() => {
    latest.current = { ...state };
    if (recalled.current === undefined || !here()) return;
    const search = listMemoryToSearch(latest.current, window.location.search);
    if (search !== window.location.search) {
      window.history.replaceState(null, "", `${window.location.pathname}${search}${window.location.hash}`);
    }
    write(key, { ...latest.current, scrollY: window.scrollY });
  });

  useEffect(() => {
    home.current = window.location.pathname;
    if (arrival.current === null) arrival.current = window.location.pathname + window.location.search;
    return watchScroll(
      (y) => {
        writeScroll(window.location.pathname + window.location.search, y);
        write(key, { ...latest.current, scrollY: y });
      },
      () => here() && restored.current && recalled.current !== undefined
    );
  }, [key]);

  useEffect(() => {
    if (!ready || restored.current) return;
    restored.current = true;
    const target = recall()?.scrollY ?? 0;
    if (target > 0) restoreScroll(target);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  return { recall };
}
