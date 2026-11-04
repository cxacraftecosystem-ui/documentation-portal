"use client";

import { RecordingSearchGroup } from "@/components/questionnaire/RecordingSearchGroup";
import { useEffect, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Search } from "lucide-react";

import { CardCodeLookup } from "@/components/artisans/CardCodeLookup";
import { EmptyState } from "@/components/EmptyState";
import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { OpenableBlock } from "@/components/records/OpenableRow";
import { RowActions, rowAction } from "@/components/RowActions";
import {
  EMPTY_SEARCH_FILTERS,
  RECORD_TYPES,
  SearchFilterBar,
  filtersFromSearchParams,
  filtersToLinkParams,
  searchFilterParams,
  typeVisible,
  type RecordType,
  type SearchFilters
} from "@/components/search/SearchFilters";
import { StatusBadge } from "@/components/StatusBadge";
import {
  useWorkshopScope,
  WorkshopScopeSelect,
  workshopScopeFromSearchParams
} from "@/components/WorkshopScopeSelect";
import { apiFetch, buildQuery } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { canUpdateRecord, recordUpdateHref, recordViewHref } from "@/lib/recordView";
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { UnifiedResults } from "@/components/search/UnifiedResults";
import { fetchUnified, UNIFIED_TYPES, unifiedApplies, type UnifiedResponse, type UnifiedType } from "@/lib/unifiedSearch";
import { useUrlScrollMemory } from "@/components/hooks/useListMemory";

type SearchResult = {
  artisans: Artisan[];
  workshops: Workshop[];
  products: ProductDocumentation[];
  tools: ToolDocumentation[];
  media: MediaFile[];
  totals?: { artisans: number; workshops: number; products: number; tools: number; media: number };
  total?: number;
  pageCount?: number;
};

type ResultItem = {
  id: string;
  title: string;
  subtitle: string;
  status: string;
  date: string;
  href: string;
  external?: boolean;
  actionLabel?: string;
  updateHref?: string | null;
};

const PAGE_SIZE = 20;

type AppliedFilters = { q: string; filters: SearchFilters };

type SearchAnswer = { unified: UnifiedResponse | null; legacy: SearchResult | null };

export default function SearchPage() {
  const { user } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();

  const [query, setQuery] = useState(searchParams.get("q") ?? "");
  const [filters, setFilters] = useState<SearchFilters>(EMPTY_SEARCH_FILTERS);
  const [applied, setApplied] = useState<AppliedFilters | null>(null);
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unified, setUnified] = useState<UnifiedResponse | null>(null);
  const [unifiedFocus, setUnifiedFocus] = useState<{ type: UnifiedType; page: number } | null>(null);

  const scope = useWorkshopScope({
    defaultToMostRecent: false,
    initialWorkshopIds: workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()))
  });

  const arrived = useRef(false);
  useEffect(() => {
    if (arrived.current) return;
    const seeded = filtersFromSearchParams(new URLSearchParams(searchParams.toString()));
    const q = (searchParams.get("q") ?? "").trim();
    const scoped = workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()));
    if (!q && !seeded.types.length && !seeded.place && seeded.range === "any" && !scoped.length) return;
    arrived.current = true;
    setFilters(seeded);
    const seededPage = Number(searchParams.get("page") ?? "1");
    if (Number.isInteger(seededPage) && seededPage > 1) setPage(seededPage);
    const [focusType, focusPage] = (searchParams.get("focus") ?? "").split(":");
    if ((UNIFIED_TYPES as readonly string[]).includes(focusType)) {
      setUnifiedFocus({ type: focusType as UnifiedType, page: Math.max(1, Number(focusPage) || 1) });
    }
    setApplied({ q, filters: seeded });
  }, [searchParams]);

  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    setLoading(true);
    const params = searchFilterParams(applied.filters);
    const unifiedQuestion = unifiedApplies(applied.q)
      ? fetchUnified({
          q: applied.q,
          types: params.types ? params.types.split(",") : undefined,
          type: unifiedFocus?.type,
          page: unifiedFocus?.page,
          limit: unifiedFocus ? PAGE_SIZE : undefined,
          place: params.place,
          craftId: params.craftId,
          dateFrom: params.dateFrom,
          dateTo: params.dateTo,
          workshopIds: scope.queryValue
        })
      : Promise.resolve({ kind: "legacy" as const });
    unifiedQuestion
      .then((outcome): Promise<SearchAnswer> | SearchAnswer => {
        if (outcome.kind === "unified") return { unified: outcome.data, legacy: null };
        return apiFetch<SearchResult>(
          `/search${buildQuery({
            q: applied.q,
            ...params,
            workshopIds: scope.queryValue,
            page,
            pageSize: PAGE_SIZE
          })}`
        ).then((legacy): SearchAnswer => ({ unified: null, legacy }));
      })
      .then((data: SearchAnswer) => {
        if (cancelled) return;
        setUnified(data.unified);
        setResult(data.legacy);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Search failed");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [applied, page, scope.queryValue, unifiedFocus]);

  useEffect(() => {
    if (applied || !scope.workshopIds.length) return;
    setApplied({ q: "", filters: EMPTY_SEARCH_FILTERS });
  }, [applied, scope.workshopIds.length]);

  useEffect(() => {
    if (!applied) return;
    arrived.current = true;
    window.history.replaceState(
      null,
      "",
      `/search${buildQuery({
        q: applied.q,
        ...filtersToLinkParams(applied.filters),
        workshopIds: scope.queryValue,
        page: page > 1 ? page : undefined,
        focus: unifiedFocus ? `${unifiedFocus.type}:${unifiedFocus.page}` : undefined
      })}`
    );
  }, [applied, scope.queryValue, page, unifiedFocus]);

  useUrlScrollMemory(Boolean(result || unified));

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setUnifiedFocus(null);
    setApplied({ q: query.trim(), filters });
  }

  function changeFilters(next: SearchFilters) {
    setFilters(next);
    setPage(1);
    setUnifiedFocus(null);
    setApplied((current) => ({ q: current?.q ?? "", filters: next }));
  }

  const show = (id: RecordType) => typeVisible(filters, id);
  const buckets = result
    ? [
        show("artisans") ? result.artisans : [],
        show("workshops") ? result.workshops : [],
        show("products") ? result.products : [],
        show("tools") ? result.tools : [],
        show("media") ? result.media : []
      ]
    : [];
  const shown = buckets.reduce((sum, bucket) => sum + bucket.length, 0);
  const selectedTotals = result?.totals ? RECORD_TYPES.filter(show).map((type) => result.totals![type]) : [];
  const pageCount = selectedTotals.length
    ? Math.max(1, Math.ceil(Math.max(...selectedTotals) / PAGE_SIZE))
    : result?.pageCount;
  const hasMore = pageCount !== undefined ? page < pageCount : buckets.some((bucket) => bucket.length === PAGE_SIZE);

  return (
    <>
      <PageHeader
        title="Search"
        description="Search every record."
        icon={<Search className="h-5 w-5" aria-hidden />}
      />
      <CardCodeLookup
        onFound={(artisan) => router.push(recordViewHref("artisan", artisan.id)!)}
        onOpenReplaced={(artisanId) => router.push(recordViewHref("artisan", artisanId)!)}
      />
      <form onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-[1fr_220px_auto]">
        <input className="field-input" placeholder="Search records" value={query} onChange={(event) => setQuery(event.target.value)} />
        <input
          className="field-input"
          placeholder="Place filter"
          value={filters.place}
          onChange={(event) => setFilters({ ...filters, place: event.target.value })}
        />
        <button className="field-button" disabled={loading}>
          <Search className="h-4 w-4" aria-hidden />
          {loading ? "Searching..." : "Search"}
        </button>
      </form>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <SearchFilterBar value={filters} onChange={changeFilters} showPlace={false} showCraft />

      <div className="mb-4 max-w-xl">
        <WorkshopScopeSelect
          scope={{
            ...scope,
            setWorkshopIds: (next: string[]) => {
              setPage(1);
              setUnifiedFocus(null);
              scope.setWorkshopIds(next);
            }
          }}
          label="Workshops"
        />
      </div>

      {unified ? (
        <UnifiedResults data={unified} focus={unifiedFocus} pageSize={PAGE_SIZE} loading={loading} onFocus={setUnifiedFocus} />
      ) : null}
      {result && shown === 0 ? (
        <EmptyState
          title={page > 1 ? "No more results" : "No matching records"}
          body={
            scope.workshopIds.length
              ? "Nothing in these workshops."
              : page > 1
                ? "Go back for earlier results."
                : undefined
          }
        />
      ) : null}
      {result ? (
        <div className="grid gap-5">
          {show("artisans") ? (
          <ResultSection
            title="Artisans"
            items={result.artisans.map((item) => ({
              id: item.id,
              title: item.name,
              subtitle: [item.place, item.craft?.name].filter(Boolean).join(" · "),
              status: item.status,
              date: item.createdAt,
              href: recordViewHref("artisan", item.id)!,
              updateHref: canUpdateRecord(user, "artisan") ? recordUpdateHref("artisan", item.id) : null
            }))}
          />
          ) : null}
          {show("workshops") ? (
          <ResultSection
            title="Workshops"
            items={result.workshops.map((item) => ({
              id: item.id,
              title: item.title,
              subtitle: item.place,
              status: item.status,
              date: item.date,
              href: recordViewHref("workshop", item.id)!,
              updateHref: canUpdateRecord(user, "workshop") ? recordUpdateHref("workshop", item.id) : null
            }))}
          />
          ) : null}
          {show("products") ? (
          <ResultSection
            title="Products"
            items={result.products.map((item) => ({
              id: item.id,
              title: item.productName,
              subtitle: `${item.craftName} · ${item.artisanName} · ${item.place}`,
              status: item.status,
              date: item.createdAt,
              href: recordViewHref("product", item.id)!,
              updateHref: canUpdateRecord(user, "product") ? recordUpdateHref("product", item.id) : null
            }))}
          />
          ) : null}
          {show("tools") ? (
          <ResultSection
            title="Tools"
            items={result.tools.map((item) => ({
              id: item.id,
              title: item.toolkitName,
              subtitle: `${item.craftName} · ${item.artisanName} · ${item.place}`,
              status: item.status,
              date: item.createdAt,
              href: recordViewHref("tool", item.id)!,
              updateHref: canUpdateRecord(user, "tool") ? recordUpdateHref("tool", item.id) : null
            }))}
          />
          ) : null}
          {show("media") ? (
          <ResultSection
            title="Media"
            items={result.media.map((item) => ({
              id: item.id,
              title: item.caption?.trim() || item.originalFilename,
              subtitle: `${item.mediaType} · ${item.mimeType}`,
              status: item.status,
              date: item.createdAt,
              href: item.url ?? "/media",
              external: Boolean(item.url),
              actionLabel: item.url ? "Open file" : "Open in Media"
            }))}
          />
          ) : null}
          <SearchPager page={page} shown={shown} hasMore={hasMore} loading={loading} onPage={setPage} />
        </div>
      ) : null}
      {applied?.q ? (
        <div className="mt-5">
          <RecordingSearchGroup q={applied.q} />
        </div>
      ) : null}
    </>
  );
}

function ResultLink({ item, className, children }: { item: ResultItem; className: string; children: ReactNode }) {
  if (item.external) {
    return (
      <a className={className} href={item.href} target="_blank" rel="noreferrer">
        {children}
      </a>
    );
  }
  return (
    <Link className={className} href={item.href}>
      {children}
    </Link>
  );
}

function ResultSection({ title, items }: { title: string; items: ResultItem[] }) {
  if (items.length === 0) return null;
  return (
    <section className="panel overflow-hidden">
      <div className="border-b border-line-200 px-4 py-3">
        <h2 className="font-semibold text-ink-900">{title}</h2>
      </div>
      <div className="divide-y divide-line-200">
        {items.map((item) => (
          <OpenableBlock
            key={item.id}
            href={item.href}
            external={item.external}
            className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
          >
            <div className="min-w-0">
              <ResultLink item={item} className="font-medium text-ink-900 hover:text-purple-700 hover:underline">
                {item.title}
              </ResultLink>
              <div className="text-sm text-ink-700">{item.subtitle}</div>
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <StatusBadge status={item.status} />
              <span className="text-sm text-ink-700">{formatDateTime(item.date)}</span>
              <RowActions>
                {item.updateHref ? (
                  <Link className={rowAction("edit")} href={item.updateHref}>
                    Update
                  </Link>
                ) : item.external || item.actionLabel ? (
                  <ResultLink item={item} className={rowAction("edit")}>
                    {item.actionLabel ?? "Open"}
                  </ResultLink>
                ) : null}
              </RowActions>
            </div>
          </OpenableBlock>
        ))}
      </div>
    </section>
  );
}

function SearchPager({
  page,
  shown,
  hasMore,
  loading,
  onPage
}: {
  page: number;
  shown: number;
  hasMore: boolean;
  loading: boolean;
  onPage: (page: number) => void;
}) {
  return (
    <div className="panel flex flex-col gap-2 px-4 py-3 text-sm text-ink-700 sm:flex-row sm:items-center sm:justify-between">
      <span>
        Page {page} · {shown} result{shown === 1 ? "" : "s"} on this page · every result type pages together
      </span>
      <ButtonRow>
        <button className="field-button-secondary" disabled={loading || page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button className="field-button-secondary" disabled={loading || !hasMore} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </ButtonRow>
    </div>
  );
}
