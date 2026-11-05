"use client";

import { ExternalLink, FolderOpen, Loader2, Play, Search, X } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";

import type { PreviewTarget } from "@/components/dataExplorer/DataExplorer";
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
  safeOpenUrl,
  type Portal,
  type UnifiedItem,
  type UnifiedResponse,
  type UnifiedType
} from "@/lib/unifiedSearch";
import { activeFilters, clock, EMPTY_STATE, flattenGroups, legacyRows, portalCounts, rangeBounds, type DateRange, type LegacyResult, type Row, type Scope, type SearchState } from "@/lib/dataSearch";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

export const PAGE_STEP = 20;
const FIRST_LIMIT = 5;
const OPTION_PAGE = 80;

type Answer = { key: string; data: UnifiedResponse | null; rows: Row[]; legacy: boolean; error: string | null };

const RANGE_OPTIONS: { value: DateRange; label: string }[] = [
  { value: "any", label: "Any date" },
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
  { value: "90d", label: "Last 90 days" },
  { value: "year", label: "This year" }
];

const TILE: Record<string, { tag: string; tone: string }> = {
  transcripts: { tag: "AUD", tone: "bg-amber-100 text-amber-800" },
  media: { tag: "FILE", tone: "bg-purple-100 text-purple-800" },
  tools: { tag: "TOOL", tone: "bg-purple-50 text-purple-800" },
  processes: { tag: "PROC", tone: "bg-purple-100 text-purple-800" },
  artisans: { tag: "ART", tone: "bg-purple-50 text-purple-800" },
  products: { tag: "PROD", tone: "bg-purple-100 text-purple-800" },
  workshops: { tag: "WS", tone: "bg-purple-50 text-purple-800" },
  crafts: { tag: "CRAFT", tone: "bg-purple-100 text-purple-800" },
  interviews: { tag: "INT", tone: "bg-amber-100 text-amber-800" },
  designWorkshops: { tag: "DW", tone: "bg-purple-100 text-purple-800" },
  reports: { tag: "DOC", tone: "bg-purple-50 text-purple-800" },
  clusters: { tag: "CL", tone: "bg-purple-50 text-purple-800" }
};

const PORTAL_BADGE: Record<Portal, string> = {
  designer: "bg-purple-100 text-purple-800 dark:bg-purple-950/60 dark:text-purple-200",
  fieldRepository: "bg-[#ccfbf1] text-[#115e59] dark:bg-[#134e4a] dark:text-[#99f6e4]"
};

const PORTAL_SHORT: Record<Portal, string> = { designer: "Design Workshop", fieldRepository: "Field Repository" };

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

function Snippet({ text, ranges }: { text: string; ranges?: number[][] }) {
  return (
    <p className="text-sm text-ink-700">
      {highlightSegments(text, ranges).map((segment, index) =>
        segment.hit ? (
          <mark key={index} className="rounded-sm bg-purple-100 px-0.5 text-ink-900 dark:bg-purple-900/60">
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
          <mark key={index} className="rounded-sm bg-purple-100 px-0.5 text-ink-900 dark:bg-purple-900/60">
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

export function DataSearchPanel({ onReveal, onPreview }: { onReveal: (path: string) => void; onPreview: (target: PreviewTarget) => void }) {
  const [draft, setDraft] = useState("");
  const [state, setState] = useState<SearchState>(EMPTY_STATE);
  const [focus, setFocus] = useState<UnifiedType | null>(null);
  const [limit, setLimit] = useState(FIRST_LIMIT);
  const [pages, setPages] = useState(1);
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [extra, setExtra] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [locating, setLocating] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

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
    const timer = window.setTimeout(() => setState((previous) => (previous.q === draft ? previous : { ...previous, q: draft })), 300);
    return () => window.clearTimeout(timer);
  }, [draft]);

  const q = state.q.trim();
  const types = useMemo(() => (state.transcriptOnly ? (["transcripts"] as UnifiedType[]) : state.types), [state.transcriptOnly, state.types]);
  const key = JSON.stringify([q, state.scope, state.workshopIds, state.artisanId, state.craftId, types, state.range, focus, limit, attempt]);

  useEffect(() => {
    if (q.length < 2) {
      setAnswer(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setExtra([]);
    setPages(1);
    const query = {
      q,
      scope: state.scope,
      types: types.length ? types : undefined,
      type: focus ?? undefined,
      page: focus ? 1 : undefined,
      limit: focus ? PAGE_STEP : limit,
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
      dateFrom: query.dateFrom
    })}`;
    fetchUnified({ q }, () => apiFetch<UnifiedResponse>(path))
      .then(async (outcome) => {
        if (cancelled) return;
        if (outcome.kind === "unified") {
          setAnswer({ key, data: outcome.data, rows: flattenGroups(outcome.data, focus), legacy: false, error: null });
          return;
        }
        const legacy = await apiFetch<LegacyResult>(`/search${buildQuery({ q, page: 1, pageSize: focus ? PAGE_STEP : 8, craftId: state.craftId || undefined, artisanId: state.artisanId || undefined, ...rangeBounds(state.range) })}`);
        if (!cancelled) setAnswer({ key, data: null, rows: legacyRows(legacy), legacy: true, error: null });
      })
      .catch((error) => {
        if (!cancelled) setAnswer({ key, data: null, rows: [], legacy: false, error: error instanceof Error && error.message ? error.message : "Search didn't answer." });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [key, q, state.scope, state.workshopIds, state.artisanId, state.craftId, state.range, types, focus, limit]);

  const more = useCallback(async () => {
    if (!focus || !answer?.data) return;
    const next = pages + 1;
    setLoading(true);
    try {
      const path = `/search/unified${buildQuery({ q, type: focus, page: next, limit: PAGE_STEP, scope: state.scope === "local" ? "local" : undefined, craftId: state.craftId || undefined, workshopIds: state.workshopIds.join(",") || undefined, artisanIds: state.artisanId || undefined, ...rangeBounds(state.range) })}`;
      const data = await apiFetch<UnifiedResponse>(path);
      setExtra((previous) => [...previous, ...flattenGroups(data, focus)]);
      setPages(next);
    } catch {
      setNotice("More results didn't load. Try again.");
    } finally {
      setLoading(false);
    }
  }, [focus, answer, pages, q, state]);

  const reveal = async (row: Row) => {
    const type = LOCATE_TYPE[row.type];
    if (!type) return;
    setLocating(`${row.portal}:${row.id}`);
    setNotice(null);
    try {
      const found = await apiFetch<{ path: string | null }>(`/data/locate${buildQuery({ type, id: row.id })}`);
      if (found.path) onReveal(found.path);
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
  const rows = [...(shown?.rows ?? []), ...extra];
  const total = data ? (focus ? (data.groups.find((group) => group.type === focus)?.total ?? 0) : data.groups.reduce((sum, group) => sum + group.total, 0)) : rows.length;
  const remote = data?.remote;
  const canBoth = Boolean(remote && remote.status !== "off" && remote.status !== "notPermitted");
  const filters = activeFilters(state);
  const counts = data && state.scope === "all" ? portalCounts(data) : null;
  const showMore = data && !shown?.legacy && (focus ? rows.length < total : limit < PAGE_STEP && data.groups.some((group) => group.total > group.items.length));

  return (
    <section role="search" aria-label="Search all data" className="grid gap-3">
      <form onSubmit={submit} className="panel grid gap-2.5 p-3">
        <div className="flex gap-2.5">
          <label className="relative flex min-h-12 flex-1 items-center">
            <Search className="pointer-events-none absolute left-3.5 h-4.5 w-4.5 text-ink-500" aria-hidden />
            <input
              ref={inputRef}
              type="search"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="Search artisans, tools, crafts, recordings and transcripts"
              aria-label="Search all data"
              className="field-input min-h-12 pl-10 pr-10 text-[15px]"
            />
            {draft ? (
              <button type="button" aria-label="Clear search" className="absolute right-2 grid h-8 w-8 place-items-center rounded-sm text-ink-500 hover:bg-purple-50" onClick={() => setDraft("")}>
                <X className="h-4 w-4" aria-hidden />
              </button>
            ) : (
              <span className="pointer-events-none absolute right-3 rounded-[6px] border border-line-200 px-1.5 text-xs text-ink-300 max-sm:hidden" aria-hidden>
                /
              </span>
            )}
          </label>
          <button type="submit" className="field-button min-h-12 px-5 max-sm:hidden">
            Search
          </button>
          <button
            type="button"
            className="field-button-secondary min-h-12 px-3 sm:hidden"
            aria-expanded={filtersOpen}
            aria-controls="data-search-filters"
            onClick={() => setFiltersOpen((value) => !value)}
          >
            {activeFilters(state) ? `Filters · ${activeFilters(state)}` : "Filters"}
          </button>
        </div>
        <div id="data-search-filters" className={`flex-wrap items-center gap-2 ${filtersOpen ? "flex" : "hidden sm:flex"}`}>
          {canBoth ? (
            <div role="radiogroup" aria-label="Where" className="flex rounded-full border border-line-200 bg-surface-50 p-0.5">
              {(["local", "all"] as Scope[]).map((scope) => (
                <button
                  key={scope}
                  type="button"
                  role="radio"
                  aria-checked={state.scope === scope}
                  onClick={() => setState((previous) => ({ ...previous, scope }))}
                  className={`min-h-8 rounded-full px-3 text-xs font-semibold pointer-coarse:min-h-11 ${state.scope === scope ? "bg-purple-700 text-white" : "text-ink-700"}`}
                >
                  {scope === "local" ? "This portal" : "Both portals"}
                </button>
              ))}
            </div>
          ) : null}
          <span className="text-xs text-ink-500">Filter</span>
          <div className="w-44">
            <SearchableMultiSelect ariaLabel="Workshop" placeholder="Workshop" options={workshops.options} values={state.workshopIds} onChange={(workshopIds) => setState((previous) => ({ ...previous, workshopIds }))} searchable onSearch={workshops.onSearch} showChips={false} bulk={false} />
          </div>
          <div className="w-44">
            <SearchableSelect ariaLabel="Artisan" placeholder="Artisan" options={artisans.options} value={state.artisanId} onChange={(artisanId) => setState((previous) => ({ ...previous, artisanId }))} searchable onSearch={artisans.onSearch} clearable advanceOnSelect={false} />
          </div>
          <div className="w-40">
            <SearchableSelect ariaLabel="Craft" placeholder="Craft" options={crafts.options} value={state.craftId} onChange={(craftId) => setState((previous) => ({ ...previous, craftId }))} searchable onSearch={crafts.onSearch} clearable advanceOnSelect={false} />
          </div>
          <div className="w-44">
            <SearchableMultiSelect
              ariaLabel="Record type"
              placeholder="Record type"
              options={offered.map((type) => ({ value: type, label: UNIFIED_LABELS[type] }))}
              values={state.types}
              onChange={(values) => setState((previous) => ({ ...previous, types: values as UnifiedType[] }))}
              showChips={false}
              bulk={false}
            />
          </div>
          <div className="w-36">
            <SearchableSelect ariaLabel="Date" options={RANGE_OPTIONS} value={state.range} onChange={(range) => setState((previous) => ({ ...previous, range: range as DateRange }))} advanceOnSelect={false} />
          </div>
          <button
            type="button"
            aria-pressed={state.transcriptOnly}
            onClick={() => setState((previous) => ({ ...previous, transcriptOnly: !previous.transcriptOnly }))}
            className={`min-h-10 rounded-full px-3.5 text-sm pointer-coarse:min-h-11 ${state.transcriptOnly ? "border border-purple-300 bg-purple-100 font-semibold text-purple-800" : "border border-line-200 bg-card text-ink-700"}`}
          >
            Has transcript
          </button>
          {filters ? (
            <button type="button" className="min-h-10 px-2 text-sm font-semibold text-purple-700 hover:underline dark:text-purple-300" onClick={() => setState({ ...EMPTY_STATE, q: state.q, scope: state.scope })}>
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
            <div className="panel grid justify-items-center gap-2 px-4 py-8 text-center">
              <span className="grid h-12 w-12 place-items-center rounded-full bg-purple-100 text-purple-700" aria-hidden>
                <Search className="h-5 w-5" />
              </span>
              <h3 className="font-display text-base font-bold text-ink-900">No matches for “{q}”</h3>
              {data && data.suggestion ? (
                <p className="text-sm text-ink-500">
                  Did you mean{" "}
                  <button
                    type="button"
                    className="font-semibold text-purple-700 hover:underline dark:text-purple-300"
                    onClick={() => {
                      const suggestion = data.suggestion ?? "";
                      setDraft(suggestion);
                      setState((previous) => ({ ...previous, q: suggestion }));
                    }}
                  >
                    {data.suggestion}
                  </button>
                  ?
                </p>
              ) : null}
              <ActionGroup>
                {filters ? (
                  <button type="button" className="field-button-secondary" onClick={() => setState({ ...EMPTY_STATE, q: state.q, scope: state.scope })}>
                    Clear {filters} {filters === 1 ? "filter" : "filters"}
                  </button>
                ) : null}
                {canBoth && state.scope === "local" ? (
                  <button type="button" className="field-button-secondary" onClick={() => setState((previous) => ({ ...previous, scope: "all" }))}>
                    Search both portals
                  </button>
                ) : null}
              </ActionGroup>
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <span className="mr-1 text-ink-500">
                  {total.toLocaleString("en-IN")} {total === 1 ? "result" : "results"}
                </span>
                {counts ? (
                  <>
                    <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${PORTAL_BADGE.designer}`}>{PORTAL_SHORT.designer} {counts.designer}</span>
                    <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${PORTAL_BADGE.fieldRepository}`}>{PORTAL_SHORT.fieldRepository} {counts.fieldRepository}</span>
                  </>
                ) : null}
                {data ? (
                  <div role="group" aria-label="Result types" className="flex flex-wrap gap-1.5">
                    <button type="button" aria-pressed={!focus} onClick={() => setFocus(null)} className={`min-h-8 rounded-full px-3 text-xs font-semibold pointer-coarse:min-h-11 ${!focus ? "bg-ink-900 text-card" : "border border-line-200 bg-card text-ink-700"}`}>
                      All {data.groups.reduce((sum, group) => sum + group.total, 0)}
                    </button>
                    {data.groups.map((group) => (
                      <button
                        key={group.type}
                        type="button"
                        aria-pressed={focus === group.type}
                        onClick={() => setFocus(group.type)}
                        className={`min-h-8 rounded-full px-3 text-xs font-semibold pointer-coarse:min-h-11 ${focus === group.type ? "bg-ink-900 text-card" : "border border-line-200 bg-card text-ink-700"}`}
                      >
                        {group.label} {group.total}
                      </button>
                    ))}
                  </div>
                ) : null}
              </div>
              {remote && state.scope === "all" && remote.status === "unavailable" ? <p className="text-sm text-ink-500">{remote.label} results aren&apos;t available right now.</p> : null}
              <ul className="grid gap-2.5" aria-label="Results">
                {rows.map((row) => {
                  const tile = TILE[row.type] ?? { tag: "REC", tone: "bg-purple-50 text-purple-800" };
                  const external = !row.local;
                  const openUrl = external ? safeOpenUrl(row.openUrl) : null;
                  const both = Boolean(row.alsoIn);
                  const time = typeof row.startMs === "number" ? clock(row.startMs) : null;
                  return (
                    <li key={`${row.portal}:${row.type}:${row.id}`} className="panel flex flex-wrap gap-3.5 p-4 sm:flex-nowrap">
                      <span className={`grid h-11 w-11 shrink-0 place-items-center rounded-md text-[11px] font-bold ${row.type === "transcripts" && row.mediaType === "VIDEO" ? "bg-purple-100 text-purple-800" : tile.tone}`} aria-hidden>
                        {row.type === "transcripts" && row.mediaType === "VIDEO" ? "VID" : tile.tag}
                      </span>
                      <div className="grid min-w-0 flex-1 gap-1">
                        <div className="flex flex-wrap items-center gap-2">
                          {state.scope === "all" || both || external ? (
                            both ? (
                              <span className="flex gap-1">
                                <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${PORTAL_BADGE.designer}`}>DW</span>
                                <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${PORTAL_BADGE.fieldRepository}`}>FR</span>
                              </span>
                            ) : (
                              <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${PORTAL_BADGE[row.portal]}`}>{PORTAL_SHORT[row.portal]}</span>
                            )
                          ) : null}
                          {external && openUrl ? (
                            <a href={openUrl} target="_blank" rel="noreferrer" className="font-semibold text-ink-900 hover:text-purple-700 hover:underline">
                              <TitleMarked title={row.title} q={q} />
                            </a>
                          ) : (
                            <Link href={external ? row.href : row.href} className="font-semibold text-ink-900 hover:text-purple-700 hover:underline">
                              <TitleMarked title={row.title} q={q} />
                            </Link>
                          )}
                          <span className="text-xs text-ink-500">
                            {[UNIFIED_LABELS[row.type]?.replace(/s$/, ""), both ? "in both" : null, time].filter(Boolean).join(" · ")}
                          </span>
                        </div>
                        {row.snippet ? <Snippet text={row.snippet} ranges={row.highlights} /> : null}
                        <p className="text-xs text-ink-500">
                          {both ? `Same person in both portals${row.alsoIn?.subtitle ? ` · ${row.alsoIn.subtitle}` : ""}` : [row.subtitle, row.place].filter(Boolean).join(" · ")}
                        </p>
                      </div>
                      <ActionGroup align="end" inline className="max-sm:w-full">
                        {row.type === "transcripts" && row.local && time ? (
                          <button type="button" className="field-button-secondary min-h-9 px-3 text-sm" onClick={() => onPreview({ mediaId: row.id, name: row.title, mediaType: row.mediaType ?? null, startMs: row.startMs ?? 0, find: q })}>
                            <Play className="h-3.5 w-3.5" aria-hidden />
                            Play from {time}
                          </button>
                        ) : null}
                        {row.local && LOCATE_TYPE[row.type] ? (
                          <button type="button" className="field-button-secondary min-h-9 px-3 text-sm" disabled={locating === `${row.portal}:${row.id}`} onClick={() => void reveal(row)}>
                            <FolderOpen className="h-3.5 w-3.5" aria-hidden />
                            {locating === `${row.portal}:${row.id}` ? "Finding…" : "Open in Data"}
                          </button>
                        ) : null}
                        {external && openUrl ? (
                          <a href={openUrl} target="_blank" rel="noreferrer" className="field-button-secondary min-h-9 px-3 text-sm">
                            Open in {PORTAL_LABELS[row.portal] ?? "the other portal"}
                            <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                          </a>
                        ) : null}
                        {both && row.alsoIn && safeOpenUrl(row.alsoIn.openUrl) ? (
                          <a href={safeOpenUrl(row.alsoIn.openUrl)!} target="_blank" rel="noreferrer" className="field-button-secondary min-h-9 px-3 text-sm">
                            Open in {PORTAL_LABELS[row.alsoIn.portal] ?? "the other portal"}
                            <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                          </a>
                        ) : null}
                      </ActionGroup>
                    </li>
                  );
                })}
              </ul>
              {showMore ? (
                <ActionGroup>
                  <button type="button" className="field-button-secondary min-h-11" disabled={loading} onClick={() => (focus ? void more() : setLimit(PAGE_STEP))}>
                    {loading ? "Loading…" : "Show 20 more"}
                  </button>
                </ActionGroup>
              ) : null}
              {state.scope === "all" && canBoth ? <p className="text-xs text-ink-500">Opening a {PORTAL_LABELS.designer} result signs you in there if you have access.</p> : null}
            </>
          )}
        </div>
      ) : null}
    </section>
  );
}
