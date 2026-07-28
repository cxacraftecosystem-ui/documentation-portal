"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Search } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
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
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";

type SearchResult = {
  artisans: Artisan[];
  workshops: Workshop[];
  products: ProductDocumentation[];
  tools: ToolDocumentation[];
  media: MediaFile[];
  /** Rows matching the query in each bucket, ignoring the page window. */
  totals?: { artisans: number; workshops: number; products: number; tools: number; media: number };
  /** Every bucket added together — what "N results" means to a researcher. */
  total?: number;
  /** Pages needed by the LONGEST bucket, since one pager walks all five at once. */
  pageCount?: number;
};

/**
 * One row in a result bucket. `href` is the SAME destination the matching list page uses, so a hit
 * here opens the record instead of dead-ending: artisans, products and tools have `[id]/edit`
 * routes; workshops are edited inline on the workshops list, and a media file opens the object
 * itself (`external`), because neither has a per-record route to link to.
 */
type ResultItem = {
  id: string;
  title: string;
  subtitle: string;
  status: string;
  date: string;
  href: string;
  /** true when `href` leaves the app (an S3 object), so it opens in a new tab. */
  external?: boolean;
  /** Row-action label; defaults to "Open". */
  actionLabel?: string;
};

/**
 * Rows per bucket per page. `GET /search` caps pageSize at 50 and applies ONE shared skip/take to
 * all five buckets, so this is the page size of every bucket at once.
 */
const PAGE_SIZE = 20;

/** Filters as they were when Search was pressed — the pager must not drift with the live inputs. */
type AppliedFilters = { q: string; filters: SearchFilters };

export default function SearchPage() {
  /**
   * The URL SEEDS the filters, it does not own them.
   *
   * `?type=` narrows the page to one bucket and is how the dashboard's repository totals open — a
   * total is a question ("which 74 tools?") and the honest answer is the list of those tools, not a
   * page of five headings where four are empty. Reading it once on arrival keeps every link that
   * exists today working, while leaving the filters themselves in React state: they now include a
   * place, a date range and a multi-select, and if a chip click were a navigation it would remount
   * the page and quietly throw the other three away.
   *
   * The type filter is also still applied to the RENDER, not only to the request, so it keeps
   * working against an API that does not know `types` yet.
   */
  const searchParams = useSearchParams();

  const [query, setQuery] = useState(searchParams.get("q") ?? "");
  const [filters, setFilters] = useState<SearchFilters>(EMPTY_SEARCH_FILTERS);
  const [applied, setApplied] = useState<AppliedFilters | null>(null);
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * THE WORKSHOP SCOPE, and why this page has to have it.
   *
   * The map links here — "Browse as a list" — carrying its own workshop scope, and the two screens
   * share one filter vocabulary precisely so that link means the same set of records on both sides. A
   * search page that read the `workshopIds` in the URL and then ignored it would answer a scoped
   * question with the whole repository, silently, which is worse than not offering the link.
   *
   * `defaultToMostRecent` is FALSE here. `/search` is the general way in to the corpus and its default
   * has always been "everything"; quietly narrowing it to last week's workshop would change what every
   * existing bookmark to this page means. The map and the completion matrix default the other way
   * because they are read DURING a workshop.
   */
  const scope = useWorkshopScope({
    defaultToMostRecent: false,
    initialWorkshopIds: workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()))
  });

  // Arriving from a dashboard total (or any /search?type=… link, including the map's "Browse as a
  // list") must SHOW the list, not an empty form: the click already said what it wanted. An empty q is
  // a valid search here — it means "everything of this type" — which is exactly what a total is asking
  // for, and what a bare workshop scope is asking for too.
  const arrived = useRef(false);
  useEffect(() => {
    if (arrived.current) return;
    const seeded = filtersFromSearchParams(new URLSearchParams(searchParams.toString()));
    const q = (searchParams.get("q") ?? "").trim();
    const scoped = workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()));
    if (!q && !seeded.types.length && !seeded.place && seeded.range === "any" && !scoped.length) return;
    arrived.current = true;
    setFilters(seeded);
    setApplied({ q, filters: seeded });
  }, [searchParams]);

  // Runs on submit (new `applied` object, even for the same text), on every filter change, on every
  // workshop-scope change and on every page step. Every active filter goes into ONE query —
  // buildQuery drops the keys that resolved to nothing, so an untouched filter costs nothing.
  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    setLoading(true);
    apiFetch<SearchResult>(
      `/search${buildQuery({
        q: applied.q,
        ...searchFilterParams(applied.filters),
        workshopIds: scope.queryValue,
        page,
        pageSize: PAGE_SIZE
      })}`
    )
      .then((data) => {
        if (cancelled) return;
        setResult(data);
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
  }, [applied, page, scope.queryValue]);

  /**
   * A WORKSHOP PICK IS A SEARCH IN ITS OWN RIGHT, and without this it was a control that did nothing.
   *
   * Everything on this page hangs off `applied`, which starts null and is only set by the arrival
   * effect, Search, or a filter chip. So on a bare `/search` — no query, no type, no place — choosing a
   * workshop left `applied` null, both effects returned at their guard, and no request was made: the
   * picker moved, the list did not, and nothing said why. "Everything from this workshop" is a complete
   * question with an empty text box, exactly as "everything of this type" is, so it has to be able to
   * start a search on its own.
   */
  useEffect(() => {
    if (applied || !scope.workshopIds.length) return;
    setApplied({ q: "", filters: EMPTY_SEARCH_FILTERS });
  }, [applied, scope.workshopIds.length]);

  // The chips used to be links, so a narrowed search was a URL you could send someone or reload.
  // They are buttons now — a navigation would remount the page and take the other filters with it —
  // so the address bar is written back by hand instead. history.replaceState rather than the router:
  // this records where the researcher already is, it does not navigate anywhere.
  //
  // The workshop scope is written back too, so the link a researcher copies out of the address bar
  // reproduces the list they are actually looking at — and can be pasted into /map, which reads the
  // same key.
  useEffect(() => {
    if (!applied) return;
    // FROM THE FIRST WRITE-BACK ON, THE ADDRESS BAR IS THIS PAGE'S OUTPUT, not its input. The arrival
    // effect is a one-shot seed guarded by `arrived`, and it now also seeds from `workshopIds` — so
    // without claiming the shot here, this write could hand the seeder a URL the page itself had just
    // written and have it re-seed the filters from a value the user had since changed.
    arrived.current = true;
    window.history.replaceState(
      null,
      "",
      `/search${buildQuery({
        q: applied.q,
        ...filtersToLinkParams(applied.filters),
        workshopIds: scope.queryValue
      })}`
    );
  }, [applied, scope.queryValue]);

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setApplied({ q: query.trim(), filters });
  }

  /**
   * A chip, a date or a type tick re-runs at once — it narrows the question already asked rather
   * than asking a new one, so it carries the query text that is IN EFFECT rather than whatever is
   * half-typed in the box. That is the same bargain the box has always made on this page: text is
   * applied when Search is pressed, everything else the moment it changes.
   */
  function changeFilters(next: SearchFilters) {
    setFilters(next);
    setPage(1);
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
  // /search returns a real `pageCount` (the page count of its longest bucket), so "Next" is exact.
  // The old heuristic — "some bucket exactly filled the page" — walked one page too far whenever a
  // bucket's total happened to be a multiple of PAGE_SIZE, landing the researcher on an empty page.
  //
  // Recomputed from the SELECTED buckets' totals when they are there, because `pageCount` is the
  // longest of all five: an API that ignored `types` would otherwise offer pages that exist only in
  // a bucket this search is not showing. The two fallbacks keep the page working against an API
  // that predates either key.
  const selectedTotals = result?.totals ? RECORD_TYPES.filter(show).map((type) => result.totals![type]) : [];
  const pageCount = selectedTotals.length
    ? Math.max(1, Math.ceil(Math.max(...selectedTotals) / PAGE_SIZE))
    : result?.pageCount;
  const hasMore = pageCount !== undefined ? page < pageCount : buckets.some((bucket) => bucket.length === PAGE_SIZE);

  return (
    <>
      <PageHeader
        title="Search"
        description="Search across artisans, workshops, products, tools and media with shared API filters."
        icon={<Search className="h-5 w-5" aria-hidden />}
      />
      <form onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-[1fr_220px_auto]">
        <input className="field-input" placeholder="Search repository" value={query} onChange={(event) => setQuery(event.target.value)} />
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
      {/*
        Place stays in the form row where it has always been, and the advanced panel therefore does
        not repeat it: one value with two boxes on screen at once is the same confusion the chips and
        the multi-select are carefully avoiding. It is applied with the text, on Search, because it
        is typed — the panel's filters are clicked, so they apply immediately.
      */}
      <SearchFilterBar value={filters} onChange={changeFilters} showPlace={false} />

      {/* The workshop scope applies IMMEDIATELY, like the panel filters and unlike the typed boxes
          above: it is a picker, and a picker that needed a second button press to take effect reads
          as broken. */}
      <div className="mb-4 max-w-xl">
        {/* THE PAGE GOES BACK TO 1, as it does for `submit` and every filter chip. The workshop scope
            was the one filter on this page that left pagination standing, so narrowing from four pages
            of results to one, while sitting on page 4, produced five empty buckets and an
            "No more results" message — for a scope that plainly has records in it. `setPage` and
            `setWorkshopIds` are batched in one handler, so exactly one request goes out. */}
        <WorkshopScopeSelect
          scope={{
            ...scope,
            setWorkshopIds: (next: string[]) => {
              setPage(1);
              scope.setWorkshopIds(next);
            }
          }}
          label="Workshops"
        />
      </div>

      {result && shown === 0 ? (
        <EmptyState
          title={page > 1 ? "No more results" : "No matching records"}
          body={
            scope.workshopIds.length
              ? "Nothing matches inside the chosen workshops. Widen the workshop scope, or choose All records."
              : page > 1
                ? "Every result type has run out on this page. Go back to see the earlier matches."
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
              // Place alone unless the craft really came back. `GET /search` reads artisans with no
              // `include`, so `craft` is never populated here and the old "No craft" fallback
              // labelled EVERY artisan hit as craftless — a statement about the API's include list
              // dressed up as a fact about the artisan. The join is kept for the day the endpoint
              // does include it.
              subtitle: [item.place, item.craft?.name].filter(Boolean).join(" · "),
              status: item.status,
              date: item.createdAt,
              href: `/artisans/${item.id}/edit`
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
              // Workshops are created and edited inline on their list page — there is no /workshops/[id].
              href: "/workshops",
              actionLabel: "Open in Workshops"
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
              href: `/products/${item.id}/edit`
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
              href: `/tools/${item.id}/edit`
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
              // A media file has no detail page: play/open the object when it has a URL, otherwise
              // fall back to the Miscellaneous Media list.
              href: item.url ?? "/media",
              external: Boolean(item.url),
              actionLabel: item.url ? "Open file" : "Open in Media"
            }))}
          />
          ) : null}
          <SearchPager page={page} shown={shown} hasMore={hasMore} loading={loading} onPage={setPage} />
        </div>
      ) : null}
    </>
  );
}

/** A result title / row action: an in-app `<Link>`, or a new-tab `<a>` for an S3 object. */
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
          <div key={item.id} className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
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
                <ResultLink item={item} className={rowAction("edit")}>
                  {item.actionLabel ?? "Open"}
                </ResultLink>
              </RowActions>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

/**
 * Prev/Next footer for the search results, styled like the shared `<Pagination>` but NOT it: that
 * component prints "Page x of y · n records", and `GET /search` returns neither a page count nor a
 * total — it pages all five buckets with one shared skip/take. So this pager states only what the
 * contract really knows: the page number, how many rows this page holds, and whether another page
 * may exist (some bucket came back full).
 */
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
      <div className="flex gap-2">
        <button className="field-button-secondary" disabled={loading || page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button className="field-button-secondary" disabled={loading || !hasMore} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
