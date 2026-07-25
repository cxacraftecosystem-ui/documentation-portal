"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Search } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { StatusBadge } from "@/components/StatusBadge";
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
type AppliedFilters = { q: string; place: string };

/** The five result buckets, plus "all". Also the `?type=` vocabulary the dashboard links use. */
const BUCKET_IDS = ["all", "artisans", "workshops", "products", "tools", "media"] as const;
type BucketId = (typeof BUCKET_IDS)[number];

const BUCKET_LABEL: Record<BucketId, string> = {
  all: "Everything",
  artisans: "Artisans",
  workshops: "Workshops",
  products: "Products",
  tools: "Tools",
  media: "Media"
};

/** Chips that switch `?type=` without losing the query already typed into the box. */
function TypeFilter({ active, q }: { active: BucketId; q: string }) {
  return (
    <div className="mb-4 flex flex-wrap gap-1.5" role="group" aria-label="Filter results by record type">
      {BUCKET_IDS.map((id) => {
        const href = id === "all" ? `/search${q ? `?q=${encodeURIComponent(q)}` : ""}` : `/search?type=${id}${q ? `&q=${encodeURIComponent(q)}` : ""}`;
        return (
          <Link
            key={id}
            href={href}
            aria-current={active === id ? "page" : undefined}
            className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
              active === id
                ? "border-purple-700 bg-purple-700 text-white"
                : "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300 hover:text-purple-700"
            }`}
          >
            {BUCKET_LABEL[id]}
          </Link>
        );
      })}
    </div>
  );
}

export default function SearchPage() {
  /**
   * `?type=` narrows the page to ONE bucket, and is how the dashboard's repository totals open.
   *
   * A total is a question — "which 74 tools?" — and the honest answer is the list of those tools,
   * not a page of five headings where four are empty. The filter is applied to the RENDER, not to
   * the request: `GET /search` returns all five buckets in one round trip either way, so filtering
   * here costs nothing and keeps "All results" a click away rather than a second query.
   */
  const searchParams = useSearchParams();
  const typeParam = (searchParams.get("type") || "").toLowerCase();
  const activeType: BucketId = (BUCKET_IDS as readonly string[]).includes(typeParam)
    ? (typeParam as BucketId)
    : "all";

  const [query, setQuery] = useState(searchParams.get("q") ?? "");
  const [place, setPlace] = useState("");
  const [applied, setApplied] = useState<AppliedFilters | null>(null);
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Arriving from a dashboard total (or any /search?type=… link) must SHOW the list, not an empty
  // form: the click already said what it wanted. An empty q is a valid search here — it means
  // "everything of this type" — which is exactly what a total is asking for.
  const arrived = useRef(false);
  useEffect(() => {
    if (arrived.current) return;
    if (!typeParam && !searchParams.get("q")) return;
    arrived.current = true;
    setApplied({ q: (searchParams.get("q") ?? "").trim(), place: "" });
  }, [searchParams, typeParam]);

  // Runs on submit (new `applied` object, even for the same text) and on every page step.
  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    setLoading(true);
    apiFetch<SearchResult>(`/search${buildQuery({ q: applied.q, place: applied.place, page, pageSize: PAGE_SIZE })}`)
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
  }, [applied, page]);

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setApplied({ q: query.trim(), place: place.trim() });
  }

  const show = (id: Exclude<BucketId, "all">) => activeType === "all" || activeType === id;
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
  // /search now returns a real `pageCount` (the page count of its longest bucket), so "Next" is
  // exact. The old heuristic — "some bucket exactly filled the page" — walked one page too far
  // whenever a bucket's total happened to be a multiple of PAGE_SIZE, landing the researcher on an
  // empty page. The fallback keeps the page working against an API that predates the key.
  const hasMore =
    result?.pageCount !== undefined ? page < result.pageCount : buckets.some((bucket) => bucket.length === PAGE_SIZE);

  return (
    <>
      <PageHeader
        title="Search"
        description="Search across artisans, workshops, products, tools and media with shared API filters."
        icon={<Search className="h-5 w-5" aria-hidden />}
      />
      <form onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-[1fr_220px_auto]">
        <input className="field-input" placeholder="Search repository" value={query} onChange={(event) => setQuery(event.target.value)} />
        <input className="field-input" placeholder="Place filter" value={place} onChange={(event) => setPlace(event.target.value)} />
        <button className="field-button" disabled={loading}>
          <Search className="h-4 w-4" aria-hidden />
          {loading ? "Searching..." : "Search"}
        </button>
      </form>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <TypeFilter active={activeType} q={applied?.q ?? query} />

      {result && shown === 0 ? (
        <EmptyState
          title={page > 1 ? "No more results" : "No matching records"}
          body={page > 1 ? "Every result type has run out on this page. Go back to see the earlier matches." : undefined}
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
              subtitle: `${item.place} · ${item.craft?.name ?? "No craft"}`,
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
