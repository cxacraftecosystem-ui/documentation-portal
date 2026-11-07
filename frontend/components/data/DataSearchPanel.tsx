"use client";

import { Loader2, MoreVertical, Search, X } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";

import type { PreviewTarget, SearchAsk } from "@/components/dataExplorer/DataExplorer";
import { LoadFailed } from "@/components/dataExplorer/explorerUi";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { SearchableMultiSelect, SearchableSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import { apiFetch, buildQuery, listResource } from "@/lib/api";
import type { Artisan, Craft, Workshop } from "@/lib/types";
import {
  PORTAL_LABELS,
  UNIFIED_LABELS,
  UNIFIED_TYPES,
  fetchUnified,
  highlightSegments,
  peerSearchOffered,
  safeOpenUrl,
  type Portal,
  type UnifiedItem,
  type UnifiedResponse,
  type UnifiedType
} from "@/lib/unifiedSearch";
import { locateRecord } from "@/components/dataExplorer/paletteSearch";
import { SortMenu, useRememberedSort } from "@/components/dataExplorer/SortMenu";
import { effectiveSort, readSort, type SortChoice } from "@/lib/dataExplorer/sorting";
import { OpenableBlock } from "@/components/records/OpenableRow";
import { useAuth } from "@/components/AuthProvider";
import { activeFilters, ALL_LIMIT, clock, EMPTY_STATE, flattenGroups, hasMore, LEGACY_PAGE, legacyRows, legacyTotals, mergeRows, moreLabel, offersOpenInData, portalCounts, rangeBounds, rowKey, sameSearch, SEARCH_SORT_LIST, searchFromParams, searchSort, searchSortKeys, searchSortLevel, searchSortParam, searchToParams, sections, showAllLabel, TYPE_LIMIT, unifiedTotals, type DateRange, type GroupTotal, type LegacyResult, type Row, type Scope, type SearchState } from "@/lib/dataSearch";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

export const PAGE_STEP = TYPE_LIMIT;
const OPTION_PAGE = 80;

type Answer = { key: string; data: UnifiedResponse | null; groups: GroupTotal[]; rows: Row[]; legacy: boolean; error: string | null; page: number; more: boolean };

const RANGE_OPTIONS: { value: DateRange; label: string }[] = [
  { value: "any", label: "Any date" },
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
  { value: "90d", label: "Last 90 days" },
  { value: "year", label: "This year" }
];

const TILE_AUDIO = "bg-amber-100 text-amber-800";
const TILE_RECORD = "bg-purple-100 text-purple-800 dark:bg-purple-950/60 dark:text-purple-200";

const TILE: Record<string, { tag: string; tone: string }> = {
  transcripts: { tag: "AUD", tone: TILE_AUDIO },
  media: { tag: "FILE", tone: TILE_RECORD },
  tools: { tag: "TOOL", tone: TILE_RECORD },
  processes: { tag: "PROC", tone: TILE_RECORD },
  artisans: { tag: "ART", tone: TILE_RECORD },
  products: { tag: "PROD", tone: TILE_RECORD },
  workshops: { tag: "WS", tone: TILE_RECORD },
  crafts: { tag: "CRAFT", tone: TILE_RECORD },
  interviews: { tag: "INT", tone: TILE_AUDIO },
  designWorkshops: { tag: "DW", tone: TILE_RECORD },
  reports: { tag: "DOC", tone: TILE_RECORD },
  clusters: { tag: "CL", tone: TILE_RECORD }
};

const PILL =
  "[&_[data-searchable-select]]:min-h-9 [&_[data-searchable-select]]:gap-1.5 [&_[data-searchable-select]]:rounded-full [&_[data-searchable-select]]:py-0 [&_[data-searchable-select]]:pl-3 [&_[data-searchable-select]]:text-[13px] [&_[data-searchable-select]>span]:text-current lg:[&_[data-searchable-select]]:min-h-[34px] pointer-coarse:[&_[data-searchable-select]]:min-h-11";
const PILL_IDLE = "[&_[data-searchable-select]]:pr-3 [&_[data-searchable-select]]:text-ink-700";
const PILL_SET =
  "[&_[data-searchable-select]]:border-[#d6b6fb] [&_[data-searchable-select]]:bg-purple-100 [&_[data-searchable-select]]:font-semibold [&_[data-searchable-select]]:text-purple-800 dark:[&_[data-searchable-select]]:border-purple-700 dark:[&_[data-searchable-select]]:bg-purple-950/60 dark:[&_[data-searchable-select]]:text-purple-200";
const PILL_MULTI_SET = "[&_[data-searchable-select]]:pr-3";

const TOGGLE_PILL_IDLE =
  "inline-flex min-h-9 items-center rounded-full border border-line-200 bg-card px-3 text-[13px] text-ink-700 transition hover:border-purple-300 lg:min-h-[34px] pointer-coarse:min-h-11";
const TOGGLE_PILL_SET =
  "inline-flex min-h-9 items-center gap-1.5 rounded-full border border-[#d6b6fb] bg-purple-100 px-3 text-[13px] font-semibold text-purple-800 transition dark:border-purple-700 dark:bg-purple-950/60 dark:text-purple-200 lg:min-h-[34px] pointer-coarse:min-h-11";

const CARD_BUTTON =
  "inline-flex min-h-9 items-center justify-center rounded-sm border border-line-200 bg-card px-3 text-[13px] text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-60 dark:hover:border-purple-500 dark:hover:bg-purple-950/40 max-sm:min-h-11 max-sm:rounded-md pointer-coarse:min-h-11";

const QUIET_BUTTON =
  "inline-flex min-h-11 items-center justify-center rounded-md border border-line-200 bg-card px-5 text-sm text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-60 dark:hover:border-purple-500 dark:hover:bg-purple-950/40";

const MARK = "rounded-[4px] bg-purple-100 px-0.5 text-purple-800 dark:bg-purple-900/60 dark:text-purple-100";

type Band = "phone" | "tablet" | "desktop";

function useBand(): Band {
  const [band, setBand] = useState<Band>("desktop");
  useEffect(() => {
    const phone = window.matchMedia("(max-width: 639.98px)");
    const tablet = window.matchMedia("(max-width: 1023.98px)");
    const read = () => setBand(phone.matches ? "phone" : tablet.matches ? "tablet" : "desktop");
    read();
    phone.addEventListener("change", read);
    tablet.addEventListener("change", read);
    return () => {
      phone.removeEventListener("change", read);
      tablet.removeEventListener("change", read);
    };
  }, []);
  return band;
}

const PLACEHOLDER: Record<Band, string> = {
  desktop: "Search artisans, tools, crafts, recordings and transcripts",
  tablet: "Search artisans, tools, recordings…",
  phone: "Search everything"
};

const PORTAL_BADGE: Record<Portal, string> = {
  designer: "bg-[#f3e8ff] text-[#5f2491] dark:bg-purple-950/60 dark:text-purple-200",
  fieldRepository: "bg-[#ccfbf1] text-[#115e59] dark:bg-[#134e4a] dark:text-[#99f6e4]"
};

const PORTAL_SHORT: Record<Portal, string> = { designer: "Design Workshop", fieldRepository: "Field Repository" };

function PortalPill({ portal, label, small = false }: { portal: Portal; label: string; small?: boolean }) {
  return (
    <span className={`inline-flex min-h-[22px] items-center gap-1.5 rounded-full px-2.5 font-bold ${small ? "text-[11px]" : "text-xs"} ${PORTAL_BADGE[portal]}`}>
      <span className="h-[7px] w-[7px] rounded-full bg-current" aria-hidden />
      {label}
    </span>
  );
}

const LOCATE_TYPE: Partial<Record<UnifiedType, string>> = {
  artisans: "artisan",
  workshops: "workshop",
  products: "product",
  tools: "tool",
  processes: "process",
  crafts: "craft",
  interviews: "interview",
  designWorkshops: "designWorkshop",
  media: "media",
  transcripts: "media"
};

function ResultCard({ href, external, className, children }: { href: string | null | undefined; external: boolean; className: string; children: React.ReactNode }) {
  if (!href) return <li className={className}>{children}</li>;
  return (
    <li>
      <OpenableBlock href={href} external={external} className={className}>
        {children}
      </OpenableBlock>
    </li>
  );
}

function Snippet({ text, ranges }: { text: string; ranges?: number[][] }) {
  return (
    <p className="text-sm text-ink-700">
      {highlightSegments(text, ranges).map((segment, index) =>
        segment.hit ? (
          <mark key={index} className={MARK}>
            {segment.text}
          </mark>
        ) : (
          <span key={index}>{segment.text}</span>
        )
      )}
    </p>
  );
}

function TitleMarked({ title, q }: { title: string; q: string }) {
  const words = q.toLowerCase().split(/\s+/).filter((word) => word.length >= 2);
  const ranges: number[][] = [];
  const lower = title.toLowerCase();
  for (const word of words) {
    let at = lower.indexOf(word);
    while (at >= 0) {
      ranges.push([at, at + word.length]);
      at = lower.indexOf(word, at + word.length);
    }
  }
  return (
    <>
      {highlightSegments(title, ranges).map((segment, index) =>
        segment.hit ? (
          <mark key={index} className={MARK}>
            {segment.text}
          </mark>
        ) : (
          <span key={index}>{segment.text}</span>
        )
      )}
    </>
  );
}

function useOptions(endpoint: string, map: (row: never) => SelectOption) {
  const [term, setTerm] = useState("");
  const [options, setOptions] = useState<SelectOption[]>([]);
  const [pending, setPending] = useState(false);
  const [truncated, setTruncated] = useState(false);
  const mapRef = useRef(map);
  useEffect(() => {
    mapRef.current = map;
  });
  useEffect(() => {
    let cancelled = false;
    setPending(true);
    const timer = window.setTimeout(() => {
      listResource<never>(endpoint, { page: 1, pageSize: OPTION_PAGE, search: term || undefined })
        .then((page) => {
          if (cancelled) return;
          setOptions(page.items.map((row) => mapRef.current(row)));
          setTruncated(page.total > page.items.length);
        })
        .catch(() => {
          if (!cancelled) setOptions([]);
        })
        .finally(() => {
          if (!cancelled) setPending(false);
        });
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [endpoint, term]);
  return { options, onSearch: setTerm, pending, truncated };
}

function suggestionCount(total: number | undefined): string {
  if (!total || total < 1) return "";
  return total === 1 ? " 1 result." : ` ${total.toLocaleString("en-IN")} results.`;
}

export function DataSearchPanel({ onReveal, onPreview, ask = null }: { onReveal: (path: string) => void; onPreview: (target: PreviewTarget) => void; ask?: SearchAsk | null }) {
  const { user } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const [fromUrl] = useState(() => searchFromParams(params));
  const [draft, setDraft] = useState(fromUrl.state.q);
  const [state, setState] = useState<SearchState>(fromUrl.state);
  const [focus, setFocus] = useState<UnifiedType | null>(fromUrl.focus);
  const lastSearch = useRef(searchToParams(fromUrl.state, fromUrl.focus, ""));
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [loading, setLoading] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [locating, setLocating] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const band = useBand();
  const viewer = user?.id ? String(user.id) : "";
  const sortLevel = searchSortLevel(focus);
  const [rememberedSort, rememberSort] = useRememberedSort(viewer, sortLevel, SEARCH_SORT_LIST);
  const remembered = useMemo(() => rememberedSort ?? readSort(viewer, sortLevel), [rememberedSort, viewer, sortLevel]);
  const sortKeys = searchSortKeys(answer?.data);
  const sort = effectiveSort(state.sort ?? remembered, sortKeys, SEARCH_SORT_LIST);
  const sortText = searchSortParam(sort);

  const chooseSort = (choice: SortChoice) => {
    rememberSort(choice);
    setState((previous) => ({ ...previous, sort: searchSort(choice) }));
  };

  const chooseFocus = (next: UnifiedType | null) => {
    setFocus(next);
    setState((previous) => (previous.sort ? { ...previous, sort: null } : previous));
  };

  const workshops = useOptions("/workshops", (row: Workshop) => ({ value: row.id, label: workshopDisplayTitle(row) }));
  const artisans = useOptions("/artisans", (row: Artisan) => ({ value: row.id, label: row.name, hint: row.place ?? undefined }));
  const crafts = useOptions("/crafts", (row: Craft) => ({ value: row.id, label: row.name, hint: row.place ?? undefined }));
  const offered = UNIFIED_TYPES;

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (target && (target.closest("input, textarea, select, [contenteditable='true']") || target.isContentEditable)) return;
      event.preventDefault();
      inputRef.current?.focus();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (!ask) return;
    setDraft(ask.q);
    setFocus(null);
    setState((previous) => ({ ...previous, q: ask.q, sort: null }));
    inputRef.current?.focus();
  }, [ask]);

  useEffect(() => {
    const timer = window.setTimeout(() => setState((previous) => (previous.q === draft ? previous : { ...previous, q: draft })), 300);
    return () => window.clearTimeout(timer);
  }, [draft]);

  useEffect(() => {
    lastSearch.current = searchToParams(state, focus, "");
    const current = window.location.search.replace(/^\?/, "");
    const next = searchToParams(state, focus, current);
    if (next === current) return;
    router.replace(`${pathname}${next ? `?${next}` : ""}`, { scroll: false });
  }, [state, focus, pathname, router]);

  const paramsText = params.toString();
  useEffect(() => {
    const wanted = searchFromParams(new URLSearchParams(paramsText));
    const key = searchToParams(wanted.state, wanted.focus, "");
    if (key === lastSearch.current) return;
    lastSearch.current = key;
    setState((previous) => (sameSearch({ state: previous, focus: null }, { state: wanted.state, focus: null }) ? previous : wanted.state));
    setFocus((previous) => (previous === wanted.focus ? previous : wanted.focus));
    setDraft((previous) => (previous.trim() === wanted.state.q.trim() ? previous : wanted.state.q));
  }, [paramsText]);

  const q = state.q.trim();
  const types = useMemo(() => (state.transcriptOnly ? (["transcripts"] as UnifiedType[]) : state.types), [state.transcriptOnly, state.types]);
  const key = JSON.stringify([q, state.scope, state.workshopIds, state.artisanId, state.craftId, types, state.range, focus, sortText ?? "", attempt]);

  useEffect(() => {
    if (q.length < 2) {
      setAnswer(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    const query = {
      q,
      scope: state.scope,
      types: types.length ? types : undefined,
      type: focus ?? undefined,
      page: 1,
      limit: focus ? TYPE_LIMIT : ALL_LIMIT,
      craftId: state.craftId || undefined,
      workshopIds: state.workshopIds.length ? state.workshopIds.join(",") : undefined,
      ...rangeBounds(state.range)
    };
    const path = `/search/unified${buildQuery({
      q: query.q,
      types: query.types?.join(","),
      scope: query.scope === "local" ? "local" : undefined,
      limit: query.limit,
      type: query.type,
      page: query.page,
      craftId: query.craftId,
      workshopIds: query.workshopIds,
      artisanIds: state.artisanId || undefined,
      dateFrom: query.dateFrom,
      sort: sortText
    })}`;
    fetchUnified({ q }, () => apiFetch<UnifiedResponse>(path))
      .then(async (outcome) => {
        if (cancelled) return;
        if (outcome.kind === "unified") {
          const groups = unifiedTotals(outcome.data);
          const rows = mergeRows([], flattenGroups(outcome.data, focus));
          setAnswer({ key, data: outcome.data, groups, rows, legacy: false, error: null, page: 1, more: hasMore(focus, groups, rows, true) });
          return;
        }
        const legacy = await apiFetch<LegacyResult>(`/search${buildQuery({ q, page: 1, pageSize: LEGACY_PAGE, types: focus ?? undefined, craftId: state.craftId || undefined, artisanId: state.artisanId || undefined, ...rangeBounds(state.range) })}`);
        if (cancelled) return;
        const groups = legacyTotals(legacy);
        const rows = mergeRows([], legacyRows(legacy));
        setAnswer({ key, data: null, groups, rows, legacy: true, error: null, page: 1, more: hasMore(focus, groups, rows, true) });
      })
      .catch((error) => {
        if (!cancelled) setAnswer({ key, data: null, groups: [], rows: [], legacy: false, error: error instanceof Error && error.message ? error.message : "Search didn't answer.", page: 1, more: false });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [key, q, state.scope, state.workshopIds, state.artisanId, state.craftId, state.range, types, focus, sortText]);

  const more = useCallback(async () => {
    if (!answer || answer.error || !answer.more) return;
    const next = answer.page + 1;
    setLoading(true);
    try {
      let fresh: Row[];
      if (answer.legacy) {
        const legacy = await apiFetch<LegacyResult>(`/search${buildQuery({ q, page: next, pageSize: LEGACY_PAGE, types: focus ?? undefined, craftId: state.craftId || undefined, artisanId: state.artisanId || undefined, ...rangeBounds(state.range) })}`);
        fresh = legacyRows(legacy);
      } else {
        const path = `/search/unified${buildQuery({ q, type: focus ?? undefined, types: !focus && types.length ? types.join(",") : undefined, page: next, limit: focus ? TYPE_LIMIT : ALL_LIMIT, scope: state.scope === "local" ? "local" : undefined, craftId: state.craftId || undefined, workshopIds: state.workshopIds.join(",") || undefined, artisanIds: state.artisanId || undefined, ...rangeBounds(state.range), sort: sortText })}`;
        const data = await apiFetch<UnifiedResponse>(path);
        fresh = flattenGroups(data, focus);
      }
      setAnswer((previous) => {
        if (!previous || previous.key !== answer.key) return previous;
        const rows = mergeRows(previous.rows, fresh);
        return { ...previous, rows, page: next, more: hasMore(focus, previous.groups, rows, rows.length > previous.rows.length) };
      });
    } catch {
      setNotice("More results didn't load. Try again.");
    } finally {
      setLoading(false);
    }
  }, [focus, answer, q, state, types, sortText]);

  const reveal = async (row: Row) => {
    const type = LOCATE_TYPE[row.type];
    if (!type) return;
    setLocating(`${row.portal}:${row.id}`);
    setNotice(null);
    try {
      const found = await locateRecord(type, row.id);
      if (found.path) onReveal(found.path);
      else if (found.recordHref) router.push(found.recordHref);
      else setNotice(`“${row.title}” isn't filed in a folder yet.`);
    } catch (error) {
      setNotice(error instanceof Error && error.message ? error.message : "That record couldn't be found in the folders.");
    } finally {
      setLocating(null);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setState((previous) => ({ ...previous, q: draft }));
  };

  const shown = answer && answer.key === key ? answer : answer;
  const data = shown?.data ?? null;
  const rows = shown?.rows ?? [];
  const groups = shown?.groups ?? [];
  const total = groups.length ? (focus ? (groups.find((group) => group.type === focus)?.total ?? rows.length) : groups.reduce((sum, group) => sum + group.total, 0)) : rows.length;
  const remote = data?.remote;
  const canBoth = peerSearchOffered(remote);
  const filters = activeFilters(state);
  const counts = data && state.scope === "all" ? portalCounts(data) : null;
  const showMore = Boolean(shown && !shown.error && shown.more);
  const grouped = focus ? [{ group: null as GroupTotal | null, rows }] : sections(groups, rows);

  const bothOnPhone = state.scope === "all";
  const pillFor = (set: boolean, multi = false) => `min-w-0 max-w-56 ${PILL} ${set ? `${PILL_SET} ${multi ? PILL_MULTI_SET : ""}` : PILL_IDLE}`;

  return (
    <section role="search" aria-label="Search all data" className="grid gap-3">
      <form onSubmit={submit} className="grid gap-2.5 lg:rounded-lg lg:border lg:border-line-200 lg:bg-card lg:p-3 lg:shadow-sm">
        <div className="flex gap-2.5">
          <label className="relative flex min-h-12 flex-1 items-center">
            <Search className="pointer-events-none absolute left-3.5 h-4.5 w-4.5 text-ink-500" aria-hidden />
            <input
              ref={inputRef}
              type="search"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder={PLACEHOLDER[band]}
              aria-label="Search all data"
              className={`field-input min-h-12 pl-10 text-[15px] max-sm:pr-[5.5rem] sm:pr-10 [&::-webkit-search-cancel-button]:appearance-none [&::-webkit-search-decoration]:appearance-none ${bothOnPhone ? "max-sm:min-h-[52px] max-sm:rounded-[14px] max-sm:border-[#9148d2]" : ""}`}
            />
            {draft ? (
              <button type="button" aria-label="Clear search" className="absolute right-2 grid h-8 w-8 place-items-center rounded-full bg-surface-50 text-ink-900 hover:bg-purple-50 max-sm:right-12 dark:hover:bg-purple-950/40"
                onClick={() => {
                  setDraft("");
                  setState((previous) => ({ ...previous, q: "" }));
                  inputRef.current?.focus();
                }}
              >
                <X className="h-4 w-4" aria-hidden />
              </button>
            ) : (
              <span className="pointer-events-none absolute right-3 rounded-[6px] border border-line-200 px-1.5 py-0.5 text-xs text-ink-300 max-lg:hidden" aria-hidden>
                /
              </span>
            )}
            <button
              type="button"
              className="absolute right-1.5 grid h-9 w-9 place-items-center rounded-sm border border-line-200 bg-card text-ink-700 hover:bg-purple-50 sm:hidden dark:hover:bg-purple-950/40"
              aria-label={filters ? `Filters, ${filters} on` : "Filters"}
              aria-expanded={filtersOpen}
              aria-controls="data-search-filters"
              onClick={() => setFiltersOpen((value) => !value)}
            >
              <MoreVertical className="h-4 w-4" aria-hidden />
            </button>
          </label>
          <button type="submit" className="field-button min-h-12 px-5 font-semibold max-lg:hidden">
            Search
          </button>
        </div>
        <div id="data-search-filters" className={`flex-wrap items-center gap-2 ${filtersOpen ? "flex" : "hidden sm:flex"}`}>
          {canBoth ? (
            <div role="radiogroup" aria-label="Where" className="flex rounded-full border border-line-200 bg-surface-50 p-[3px] max-sm:h-12 max-sm:w-full max-sm:rounded-md max-sm:bg-card max-sm:p-1">
              {(["local", "all"] as Scope[]).map((scope) => (
                <button
                  key={scope}
                  type="button"
                  role="radio"
                  aria-checked={state.scope === scope}
                  onClick={() => setState((previous) => ({ ...previous, scope }))}
                  className={`min-h-8 rounded-full px-3 text-xs pointer-coarse:min-h-11 max-sm:flex-1 max-sm:rounded-[10px] max-sm:text-[15px] ${state.scope === scope ? "bg-purple-700 font-semibold text-white" : "text-ink-700"}`}
                >
                  <span className="sm:hidden">{scope === "local" ? "This app" : "Both portals"}</span>
                  <span className="max-sm:hidden">{scope === "local" ? "This portal" : "Both portals"}</span>
                </button>
              ))}
            </div>
          ) : (
            <span className="text-xs text-ink-500 max-lg:hidden">Filter</span>
          )}
          <div className={pillFor(state.workshopIds.length > 0, true)}>
            <SearchableMultiSelect ariaLabel="Workshop" placeholder="Workshop" options={workshops.options} values={state.workshopIds} onChange={(workshopIds) => setState((previous) => ({ ...previous, workshopIds }))} searchable onSearch={workshops.onSearch} showChips={false} bulk={false} />
          </div>
          <div className={pillFor(Boolean(state.artisanId))}>
            <SearchableSelect ariaLabel="Artisan" placeholder="Artisan" options={artisans.options} value={state.artisanId} onChange={(artisanId) => setState((previous) => ({ ...previous, artisanId }))} searchable onSearch={artisans.onSearch} clearable advanceOnSelect={false} />
          </div>
          <div className={pillFor(Boolean(state.craftId))}>
            <SearchableSelect ariaLabel="Craft" placeholder="Craft" options={crafts.options} value={state.craftId} onChange={(craftId) => setState((previous) => ({ ...previous, craftId }))} searchable onSearch={crafts.onSearch} clearable advanceOnSelect={false} />
          </div>
          <div className={pillFor(state.types.length > 0, true)}>
            <SearchableMultiSelect
              ariaLabel="Record type"
              placeholder={band === "tablet" ? "Type" : "Record type"}
              options={offered.map((type) => ({ value: type, label: UNIFIED_LABELS[type] }))}
              values={state.types}
              onChange={(values) => setState((previous) => ({ ...previous, types: values as UnifiedType[] }))}
              showChips={false}
              bulk={false}
            />
          </div>
          <div className={pillFor(state.range !== "any")}>
            <SearchableSelect
              ariaLabel="Date"
              placeholder="Date"
              options={RANGE_OPTIONS.filter((option) => option.value !== "any")}
              value={state.range === "any" ? "" : state.range}
              onChange={(range) => setState((previous) => ({ ...previous, range: (range || "any") as DateRange }))}
              clearable
              advanceOnSelect={false}
            />
          </div>
          <button
            type="button"
            aria-pressed={state.transcriptOnly}
            onClick={() => setState((previous) => ({ ...previous, transcriptOnly: !previous.transcriptOnly }))}
            className={state.transcriptOnly ? TOGGLE_PILL_SET : TOGGLE_PILL_IDLE}
          >
            Has transcript
          </button>
          {filters ? (
            <button type="button" className="min-h-9 px-2 text-[13px] font-semibold text-purple-700 hover:underline dark:text-purple-300" onClick={() => setState({ ...EMPTY_STATE, q: state.q, scope: state.scope, sort: state.sort })}>
              Clear {filters} {filters === 1 ? "filter" : "filters"}
            </button>
          ) : null}
        </div>
      </form>

      {notice ? <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">{notice}</p> : null}

      {q.length >= 2 ? (
        <div className="grid gap-3" aria-busy={loading || undefined} data-testid="search-results">
          {shown?.error ? (
            <LoadFailed title="Search didn't answer" onRetry={() => setAttempt((value) => value + 1)}>
              {shown.error}
            </LoadFailed>
          ) : !shown ? (
            <p className="flex items-center gap-2 text-sm text-ink-500" role="status">
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
              Searching…
            </p>
          ) : rows.length === 0 ? (
            <div className="grid justify-items-center gap-3 rounded-[20px] border border-line-200 bg-card p-6 text-center">
              <span className="grid h-14 w-14 place-items-center rounded-[16px] bg-surface-50 text-ink-500" aria-hidden>
                <Search className="h-6 w-6" />
              </span>
              <h3 className="font-display text-lg font-extrabold text-ink-900">No matches for “{q}”</h3>
              {data && data.suggestion ? (
                <p className="text-sm text-ink-700">
                  Did you mean{" "}
                  <button
                    type="button"
                    className="font-semibold text-purple-700 underline dark:text-purple-300"
                    onClick={() => {
                      const suggestion = data.suggestion ?? "";
                      setDraft(suggestion);
                      setState((previous) => ({ ...previous, q: suggestion }));
                    }}
                  >
                    {data.suggestion}
                  </button>
                  ?{suggestionCount(data.suggestionTotal)}
                </p>
              ) : null}
              <ActionGroup>
                {filters ? (
                  <button type="button" className={`${QUIET_BUTTON} px-4`} onClick={() => setState({ ...EMPTY_STATE, q: state.q, scope: state.scope, sort: state.sort })}>
                    Clear {filters} {filters === 1 ? "filter" : "filters"}
                  </button>
                ) : null}
                {canBoth && state.scope === "local" ? (
                  <button type="button" className={`${QUIET_BUTTON} px-4`} onClick={() => setState((previous) => ({ ...previous, scope: "all" }))}>
                    Search both portals
                  </button>
                ) : null}
              </ActionGroup>
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2 text-[13px]">
                <span className="mr-1 text-ink-500">
                  {total.toLocaleString("en-IN")} {total === 1 ? "result" : "results"}
                </span>
                {counts ? (
                  <>
                    <PortalPill portal="designer" label={`${PORTAL_SHORT.designer} ${counts.designer}`} />
                    <PortalPill portal="fieldRepository" label={`${PORTAL_SHORT.fieldRepository} ${counts.fieldRepository}`} />
                  </>
                ) : null}
                {groups.length ? (
                  <div role="group" aria-label="Result types" className="flex flex-wrap gap-1.5">
                    <button type="button" aria-pressed={!focus} onClick={() => chooseFocus(null)} className={`min-h-[34px] rounded-full px-3 text-[13px] pointer-coarse:min-h-11 ${!focus ? "bg-ink-900 font-semibold text-card" : "border border-line-200 bg-card text-ink-900"}`}>
                      All {groups.reduce((sum, group) => sum + group.total, 0)}
                    </button>
                    {groups.map((group) => (
                      <button
                        key={group.type}
                        type="button"
                        aria-pressed={focus === group.type}
                        onClick={() => chooseFocus(group.type)}
                        className={`min-h-[34px] rounded-full px-3 text-[13px] pointer-coarse:min-h-11 ${focus === group.type ? "bg-ink-900 font-semibold text-card" : "border border-line-200 bg-card text-ink-900"}`}
                      >
                        {group.label} {group.total}
                      </button>
                    ))}
                  </div>
                ) : null}
                <SortMenu value={sort} keys={sortKeys} onChange={chooseSort} className="ml-auto" />
              </div>
              {remote && state.scope === "all" && remote.status === "unavailable" ? <p className="text-sm text-ink-500">{remote.label} results aren&apos;t available right now.</p> : null}
              {grouped.map((section) => (
              <div key={section.group?.type ?? "all"} className="grid gap-2.5" data-testid={section.group ? `search-group-${section.group.type}` : undefined}>
              {section.group ? (
                <h3 className="flex items-baseline justify-between gap-2 pt-1 font-display text-sm font-bold text-ink-900">
                  <span>{section.group.label}</span>
                  <span className="text-xs font-semibold text-ink-500">{section.rows.length < section.group.total ? `${section.rows.length} of ${section.group.total.toLocaleString("en-IN")}` : section.group.total.toLocaleString("en-IN")}</span>
                </h3>
              ) : null}
              <ul className="grid gap-2.5" aria-label={section.group ? section.group.label : "Results"}>
                {section.rows.map((row) => {
                  const tile = TILE[row.type] ?? { tag: "REC", tone: TILE_RECORD };
                  const external = !row.local;
                  const openUrl = external ? safeOpenUrl(row.openUrl) : null;
                  const both = Boolean(row.alsoIn);
                  const time = typeof row.startMs === "number" ? clock(row.startMs) : null;
                  return (
                    <ResultCard key={rowKey(row)} href={external ? openUrl : row.href} external={external} className={`flex flex-wrap gap-3.5 rounded-lg border border-line-200 bg-card p-4 max-sm:p-3.5 sm:flex-nowrap ${both ? "border-dashed" : ""}`}>
                      <span className={`grid h-11 w-11 shrink-0 place-items-center rounded-md text-[11px] font-bold max-sm:hidden ${row.type === "transcripts" && row.mediaType === "VIDEO" ? TILE_RECORD : tile.tone}`} aria-hidden>
                        {row.type === "transcripts" && row.mediaType === "VIDEO" ? "VID" : tile.tag}
                      </span>
                      <div className="grid min-w-0 flex-1 gap-1.5">
                        <div className="flex flex-wrap items-center gap-2">
                          {state.scope === "all" || both || external ? (
                            both ? (
                              <span className="flex gap-1">
                                <PortalPill portal="designer" label="DW" small />
                                <PortalPill portal="fieldRepository" label="FR" small />
                              </span>
                            ) : (
                              <PortalPill portal={row.portal} label={PORTAL_SHORT[row.portal]} small />
                            )
                          ) : null}
                          {external && openUrl ? (
                            <a href={openUrl} target="_blank" rel="noreferrer" className="text-[15px] font-semibold text-purple-700 no-underline hover:underline dark:text-purple-300">
                              <TitleMarked title={row.title} q={q} />
                            </a>
                          ) : (
                            <Link href={external ? row.href : row.href} className="text-[15px] font-semibold text-purple-700 no-underline hover:underline dark:text-purple-300">
                              <TitleMarked title={row.title} q={q} />
                            </Link>
                          )}
                          <span className="text-xs text-ink-500 max-sm:ml-auto">
                            {[UNIFIED_LABELS[row.type]?.replace(/s$/, ""), both ? "in both" : null, time].filter(Boolean).join(" · ")}
                          </span>
                        </div>
                        {row.snippet ? <Snippet text={row.snippet} ranges={row.highlights} /> : null}
                        <p className="text-xs text-ink-500">
                          {both ? `Same person in both portals${row.alsoIn?.subtitle ? ` · ${row.alsoIn.subtitle}` : ""}` : [row.subtitle, row.place].filter(Boolean).join(" · ")}
                        </p>
                      </div>
                      <ActionGroup align="end" inline className="self-start max-sm:w-full">
                        {row.type === "transcripts" && row.local && time ? (
                          <button type="button" className={CARD_BUTTON} onClick={() => onPreview({ mediaId: row.id, name: row.title, mediaType: row.mediaType ?? null, startMs: row.startMs ?? 0, find: q })}>
                            Play from {time}
                          </button>
                        ) : null}
                        {offersOpenInData(row, LOCATE_TYPE[row.type]) ? (
                          <button type="button" className={CARD_BUTTON} disabled={locating === `${row.portal}:${row.id}`} onClick={() => void reveal(row)}>
                            {locating === `${row.portal}:${row.id}` ? "Finding…" : "Open in Data"}
                          </button>
                        ) : null}
                        {external && openUrl ? (
                          <a href={openUrl} target="_blank" rel="noreferrer" className={CARD_BUTTON}>
                            Open in {PORTAL_LABELS[row.portal] ?? "the other portal"} ↗
                          </a>
                        ) : null}
                        {both && row.alsoIn && safeOpenUrl(row.alsoIn.openUrl) ? (
                          <a href={safeOpenUrl(row.alsoIn.openUrl)!} target="_blank" rel="noreferrer" className={CARD_BUTTON}>
                            Open in {PORTAL_LABELS[row.alsoIn.portal] ?? "the other portal"} ↗
                          </a>
                        ) : null}
                      </ActionGroup>
                    </ResultCard>
                  );
                })}
              </ul>
              {section.group && section.rows.length < section.group.total ? (
                <ActionGroup>
                  <button type="button" className={QUIET_BUTTON} data-testid={`search-all-${section.group.type}`} onClick={() => chooseFocus(section.group!.type)}>
                    {showAllLabel(section.group)}
                  </button>
                </ActionGroup>
              ) : null}
              </div>
              ))}
              {showMore ? (
                <ActionGroup>
                  <button type="button" className={QUIET_BUTTON} data-testid="search-more" disabled={loading} onClick={() => void more()}>
                    {loading ? "Loading…" : moreLabel(focus, groups, rows)}
                  </button>
                </ActionGroup>
              ) : null}
              {state.scope === "all" && canBoth ? <p className="text-center text-xs text-ink-500">Opening a {PORTAL_LABELS.designer} result signs you in there if you have access.</p> : null}
            </>
          )}
        </div>
      ) : null}
    </section>
  );
}
