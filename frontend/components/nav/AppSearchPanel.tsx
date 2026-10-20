"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ArrowLeft, History, Search, X, type LucideIcon } from "lucide-react";

import {
  PORTAL_LABELS,
  RECENT_MAX,
  UNIFIED_GROUP_CAP,
  UNIFIED_MIN_CHARS,
  appSearchScore,
  fetchUnified,
  pushRecentSearch,
  type UnifiedGroup
} from "@/lib/unifiedSearch";
import { cn } from "@/lib/utils";

/** A screen the reader may open — the menu's own visible destinations. */
export type AppSearchScreen = { label: string; href: string; icon: LucideIcon };

/** How long typing must pause before the server is asked. Android's `APP_SEARCH_DEBOUNCE_MS`. */
export const APP_SEARCH_DEBOUNCE_MS = 300;
const RECENT_KEY = "app_search_recent";
const OFFLINE_LINE = "Offline — showing what's on this device.";

function readRecents(): string[] {
  try {
    const raw = window.localStorage.getItem(RECENT_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string").slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

function writeRecents(recents: string[]) {
  try {
    window.localStorage.setItem(RECENT_KEY, JSON.stringify(recents.slice(0, RECENT_MAX)));
  } catch {
    /* storage blocked: recents simply are not kept */
  }
}

/** The screens that answer `query`, best first, at most five. Android's SCREENS group. */
export function matchScreens(screens: AppSearchScreen[], query: string): AppSearchScreen[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const seen = new Set<string>();
  return screens
    .filter((screen) => {
      if (seen.has(screen.href) || !screen.label.toLowerCase().includes(q)) return false;
      seen.add(screen.href);
      return true;
    })
    .map((screen, index) => ({ screen, index, score: appSearchScore(screen.label, q) }))
    .sort((a, b) => a.score - b.score || a.index - b.index)
    .slice(0, UNIFIED_GROUP_CAP)
    .map(({ screen }) => screen);
}

type RemoteState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "done"; groups: UnifiedGroup[] }
  | { kind: "legacy" }
  | { kind: "offline" }
  | { kind: "failed" };

/**
 * THE SEARCH PANEL — the header turned into a search field, with the results under it. A port of
 * Android's `AppSearchPanel` over `lib/unifiedSearch.ts`.
 *
 * Screens answer from one character; records are asked of the server from two, after a 300 ms
 * pause, five per group in the server's group order. Enter opens the full `/search` page with the
 * query. Up to eight recent searches are kept in this browser only. Escape or Back closes it.
 *
 * Shared byte-identical by the designer portal and the documentation portal.
 */
export function AppSearchPanel({
  open,
  appName,
  screens,
  onClose
}: {
  open: boolean;
  appName: string;
  screens: AppSearchScreen[];
  onClose: () => void;
}) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [remote, setRemote] = useState<RemoteState>({ kind: "idle" });
  const [recents, setRecents] = useState<string[]>([]);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const closeRef = useRef(onClose);
  useEffect(() => {
    closeRef.current = onClose;
  });

  useEffect(() => {
    if (!open) return;
    const returnTarget = document.activeElement as HTMLElement | null;
    setRecents(readRecents());
    const frame = requestAnimationFrame(() => inputRef.current?.focus());
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeRef.current();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("keydown", onKeyDown);
      if (returnTarget && document.contains(returnTarget)) returnTarget.focus();
    };
  }, [open]);

  useEffect(() => {
    if (!open) {
      setQuery("");
      setRemote({ kind: "idle" });
    }
  }, [open]);

  const trimmed = query.trim();
  useEffect(() => {
    if (!open || trimmed.length < UNIFIED_MIN_CHARS) {
      setRemote({ kind: "idle" });
      return;
    }
    if (typeof navigator !== "undefined" && navigator.onLine === false) {
      setRemote({ kind: "offline" });
      return;
    }
    let cancelled = false;
    setRemote({ kind: "loading" });
    const timer = window.setTimeout(() => {
      fetchUnified({ q: trimmed, limit: UNIFIED_GROUP_CAP })
        .then((outcome) => {
          if (cancelled) return;
          setRemote(outcome.kind === "unified" ? { kind: "done", groups: outcome.data.groups } : { kind: "legacy" });
        })
        .catch((error: unknown) => {
          if (cancelled) return;
          const offline = (typeof navigator !== "undefined" && navigator.onLine === false) || error instanceof TypeError;
          setRemote({ kind: offline ? "offline" : "failed" });
        });
    }, APP_SEARCH_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [open, trimmed]);

  const screenHits = useMemo(() => matchScreens(screens, trimmed), [screens, trimmed]);

  if (!open) return null;

  function remember(text: string) {
    const next = pushRecentSearch(readRecents(), text);
    writeRecents(next);
    setRecents(next);
  }

  function openFullSearch(text: string) {
    const q = text.trim();
    if (!q) return;
    remember(q);
    onClose();
    router.push(`/search?q=${encodeURIComponent(q)}`);
  }

  const groups = remote.kind === "done" ? remote.groups : [];
  const nothing =
    trimmed.length >= UNIFIED_MIN_CHARS && remote.kind === "done" && groups.length === 0 && screenHits.length === 0;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Search"
      data-testid="app-search-panel"
      className="fixed inset-0 z-90 flex flex-col overflow-y-auto overscroll-contain bg-bg-0 px-3 pb-[calc(1.5rem+env(safe-area-inset-bottom,0px))] pt-3"
    >
      <form
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          openFullSearch(query);
        }}
        className="sticky top-0 z-10 flex min-h-[60px] items-center gap-1 rounded-[28px] border border-line-200 bg-card px-1.5 shadow-island"
      >
        <button
          type="button"
          onClick={onClose}
          aria-label="Close search"
          className="inline-flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-ink-700 transition hover:bg-surface-50"
        >
          <ArrowLeft className="h-5 w-5" aria-hidden />
        </button>
        <input
          ref={inputRef}
          type="search"
          enterKeyHint="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={`Search ${appName}`}
          aria-label={`Search ${appName}`}
          className="min-w-0 flex-1 bg-transparent py-3 text-base text-ink-900 outline-hidden placeholder:text-ink-300 [&::-webkit-search-cancel-button]:hidden"
        />
        {query ? (
          <button
            type="button"
            onClick={() => {
              setQuery("");
              inputRef.current?.focus();
            }}
            aria-label="Clear"
            className="inline-flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-ink-500 transition hover:bg-surface-50"
          >
            <X className="h-5 w-5" aria-hidden />
          </button>
        ) : null}
      </form>

      <div className="mx-auto mt-3 w-full max-w-3xl">
        {trimmed.length === 0 ? (
          recents.length > 0 ? (
            <section aria-labelledby="app-search-recent" className="panel p-2">
              <div className="flex items-center justify-between px-2 py-1">
                <h2 id="app-search-recent" className="field-label">
                  Recent
                </h2>
                <button
                  type="button"
                  onClick={() => {
                    writeRecents([]);
                    setRecents([]);
                  }}
                  className="min-h-11 rounded-full px-3 text-sm font-medium text-purple-700 hover:bg-purple-50"
                >
                  Clear
                </button>
              </div>
              <ul>
                {recents.map((recent) => (
                  <li key={recent}>
                    <button
                      type="button"
                      onClick={() => setQuery(recent)}
                      className="flex min-h-12 w-full items-center gap-3 rounded-lg px-3 text-left text-sm text-ink-700 hover:bg-surface-50"
                    >
                      <History className="h-4 w-4 shrink-0 text-ink-300" aria-hidden />
                      <span className="truncate">{recent}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ) : null
        ) : (
          <div className="grid gap-3">
            {remote.kind === "offline" ? <p className="px-2 text-sm text-ink-500">{OFFLINE_LINE}</p> : null}
            {remote.kind === "failed" ? <p className="px-2 text-sm text-ink-500">Records couldn&apos;t be searched. Try again.</p> : null}
            {screenHits.length > 0 ? (
              <ResultGroup title="Screens">
                {screenHits.map((screen) => (
                  <ResultLink
                    key={screen.href}
                    href={screen.href}
                    title={screen.label}
                    icon={screen.icon}
                    onOpen={() => {
                      remember(trimmed);
                      onClose();
                    }}
                  />
                ))}
              </ResultGroup>
            ) : null}
            {groups.map((group) => (
              <ResultGroup key={group.type} title={group.label}>
                {group.items.slice(0, UNIFIED_GROUP_CAP).map((item) => (
                  <ResultLink
                    key={`${item.portal}:${item.type}:${item.id}`}
                    href={item.href}
                    title={item.title}
                    subtitle={item.subtitle ?? item.place ?? null}
                    chip={item.local ? null : PORTAL_LABELS[item.portal] ?? null}
                    onOpen={() => {
                      remember(trimmed);
                      onClose();
                    }}
                  />
                ))}
              </ResultGroup>
            ))}
            {remote.kind === "loading" ? <p className="px-2 text-sm text-ink-500" aria-live="polite">Searching…</p> : null}
            {nothing ? <p className="px-2 text-sm text-ink-500">Nothing matches “{trimmed}”.</p> : null}
            {trimmed.length >= UNIFIED_MIN_CHARS ? (
              <button
                type="button"
                onClick={() => openFullSearch(trimmed)}
                className="flex min-h-12 w-full items-center gap-3 rounded-lg px-3 text-left text-sm font-medium text-purple-700 hover:bg-purple-50"
              >
                <Search className="h-4 w-4 shrink-0" aria-hidden />
                See all results for “{trimmed}”
              </button>
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
}

function ResultGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="panel p-2">
      <h2 className="field-label px-2 py-1">{title}</h2>
      <ul>{children}</ul>
    </section>
  );
}

function ResultLink({
  href,
  title,
  subtitle,
  icon: Icon,
  chip,
  onOpen
}: {
  href: string;
  title: string;
  subtitle?: string | null;
  icon?: LucideIcon;
  chip?: string | null;
  onOpen: () => void;
}) {
  return (
    <li>
      <Link
        href={href}
        onClick={onOpen}
        className={cn("flex min-h-12 items-center gap-3 rounded-lg px-3 py-1.5 text-sm hover:bg-surface-50")}
      >
        {Icon ? <Icon className="h-4 w-4 shrink-0 text-ink-300" aria-hidden /> : null}
        <span className="grid min-w-0 flex-1">
          <span className="truncate font-medium text-ink-900">{title}</span>
          {subtitle ? <span className="truncate text-xs text-ink-500">{subtitle}</span> : null}
        </span>
        {chip ? (
          <span className="shrink-0 rounded-full border border-line-200 px-2 py-0.5 text-[11px] font-medium text-ink-500">{chip}</span>
        ) : null}
      </Link>
    </li>
  );
}
