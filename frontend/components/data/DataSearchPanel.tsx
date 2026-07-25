"use client";

/**
 * Search, at the top of View Data.
 *
 * The Android app puts a search box on its data screen because that is how a researcher actually
 * arrives: they know the artisan's name, not which of nine craft folders they are filed under.
 * Walking the tree is the browsing path; this is the finding path, and until now the web only had
 * the first one here — the search page was somewhere else entirely, and it did not hand you back
 * into the folder you wanted.
 *
 * Every hit therefore does two things. "Open" goes to the record's own page (the same destination
 * the list pages use). "Show in folders" resolves the record to its place in the tree and moves the
 * browser below to it, which is the whole point of having search on this page rather than its own.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { Search, X, Loader2, FolderTree, ExternalLink } from "lucide-react";
import Link from "next/link";

import { apiFetch, buildQuery } from "@/lib/api";
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";

type SearchResult = {
  artisans: Artisan[];
  workshops: Workshop[];
  products: ProductDocumentation[];
  tools: ToolDocumentation[];
  media: MediaFile[];
  total?: number;
};

/** Where a hit lives in the tree, resolved server-side (`GET /data/locate`). */
type LocateResult = { path: string | null };

type Row = {
  key: string;
  kind: "Artisan" | "Workshop" | "Product" | "Tool" | "Media";
  title: string;
  subtitle: string;
  href: string | null;
  external?: boolean;
  /** (recordType, id) the tree can resolve; null for rows the tree does not file individually. */
  locate: { type: string; id: string } | null;
};

const EMPTY: Row[] = [];

function rowsFrom(result: SearchResult): Row[] {
  const rows: Row[] = [];
  for (const a of result.artisans ?? []) {
    rows.push({
      key: `artisan-${a.id}`,
      kind: "Artisan",
      title: a.name,
      subtitle: [a.craft?.name, a.place].filter(Boolean).join(" · "),
      href: `/artisans/${a.id}/edit`,
      locate: { type: "artisan", id: a.id }
    });
  }
  for (const p of result.products ?? []) {
    rows.push({
      key: `product-${p.id}`,
      kind: "Product",
      title: p.productName,
      subtitle: p.artisanName ?? "",
      href: `/products/${p.id}/edit`,
      locate: { type: "product", id: p.id }
    });
  }
  for (const t of result.tools ?? []) {
    rows.push({
      key: `tool-${t.id}`,
      kind: "Tool",
      title: t.toolkitName,
      subtitle: t.artisanName ?? "",
      href: `/tools/${t.id}/edit`,
      locate: { type: "tool", id: t.id }
    });
  }
  for (const w of result.workshops ?? []) {
    rows.push({
      key: `workshop-${w.id}`,
      kind: "Workshop",
      title: w.title,
      subtitle: [w.place, w.date ? new Date(w.date).toLocaleDateString() : ""].filter(Boolean).join(" · "),
      href: "/workshops",
      locate: { type: "workshop", id: w.id }
    });
  }
  for (const m of result.media ?? []) {
    rows.push({
      key: `media-${m.id}`,
      kind: "Media",
      title: m.caption || m.originalFilename,
      subtitle: [m.mediaType, m.uploadedBy?.name].filter(Boolean).join(" · "),
      href: m.url ?? null,
      external: true,
      locate: { type: "media", id: m.id }
    });
  }
  return rows;
}

const KIND_CLASS: Record<Row["kind"], string> = {
  Artisan: "bg-purple-100 text-purple-800",
  Product: "bg-success-100 text-success-600",
  Tool: "bg-amber-100 text-amber-800",
  Workshop: "bg-purple-50 text-purple-700",
  Media: "bg-surface-50 text-ink-700"
};

export function DataSearchPanel({ onReveal }: { onReveal: (path: string) => void }) {
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState<Row[]>(EMPTY);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealing, setRevealing] = useState<string | null>(null);
  // Every keystroke would be a request; a request per pause is one per word typed.
  const timer = useRef<number | null>(null);
  // Late responses from a query the user has already changed must not overwrite the current rows.
  const generation = useRef(0);

  const run = useCallback(async (text: string) => {
    const q = text.trim();
    const mine = ++generation.current;
    if (q.length < 2) {
      setRows(EMPTY);
      setTotal(0);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await apiFetch<SearchResult>(`/search${buildQuery({ q, page: 1, pageSize: 8 })}`);
      if (mine !== generation.current) return;
      setRows(rowsFrom(result));
      setTotal(result.total ?? 0);
    } catch (err) {
      if (mine !== generation.current) return;
      setError(err instanceof Error ? err.message : "Search failed");
      setRows(EMPTY);
    } finally {
      if (mine === generation.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => run(query), 300);
    return () => {
      if (timer.current) window.clearTimeout(timer.current);
    };
  }, [query, run]);

  async function reveal(row: Row) {
    if (!row.locate) return;
    setRevealing(row.key);
    try {
      const found = await apiFetch<LocateResult>(`/data/locate${buildQuery(row.locate)}`);
      if (found.path) onReveal(found.path);
      else setError(`"${row.title}" is not filed under any workshop yet, so it has no folder to open.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not locate that record in the tree");
    } finally {
      setRevealing(null);
    }
  }

  return (
    <section className="panel mb-4 grid gap-3 p-4">
      <div>
        <h2 className="font-display text-base font-bold text-ink-900">Search the repository</h2>
        <p className="mt-0.5 text-xs text-ink-500">
          Artisans, products, tools, workshops and media by name, place or caption. Open a hit, or show it where it
          sits in the folders below.
        </p>
      </div>

      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-300" aria-hidden />
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search by name, place or caption"
          aria-label="Search the repository"
          className="field-input w-full pl-9 pr-9"
        />
        {query ? (
          <button
            type="button"
            onClick={() => setQuery("")}
            aria-label="Clear search"
            className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded text-ink-500 hover:bg-purple-50 hover:text-purple-700"
          >
            <X className="h-3.5 w-3.5" aria-hidden />
          </button>
        ) : null}
      </div>

      {error ? <p className="text-xs text-error-600">{error}</p> : null}

      {loading ? (
        <p className="flex items-center gap-2 text-xs text-ink-500">
          <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
          Searching…
        </p>
      ) : null}

      {!loading && query.trim().length >= 2 && rows.length === 0 && !error ? (
        <p className="text-xs text-ink-500">Nothing matches “{query.trim()}”.</p>
      ) : null}

      {rows.length ? (
        <>
          <ul className="grid gap-1.5">
            {rows.map((row) => (
              <li
                key={row.key}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
              >
                <div className="flex min-w-0 items-center gap-2">
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${KIND_CLASS[row.kind]}`}>
                    {row.kind}
                  </span>
                  <span className="truncate text-sm font-medium text-ink-900">{row.title}</span>
                  {row.subtitle ? <span className="truncate text-xs text-ink-500">{row.subtitle}</span> : null}
                </div>
                <div className="flex shrink-0 items-center gap-3">
                  <button
                    type="button"
                    className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700 disabled:opacity-50"
                    disabled={revealing === row.key}
                    onClick={() => reveal(row)}
                  >
                    <FolderTree className="h-3.5 w-3.5" aria-hidden />
                    {revealing === row.key ? "Locating…" : "Show in folders"}
                  </button>
                  {row.href ? (
                    row.external ? (
                      <a
                        href={row.href}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700"
                      >
                        <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                        Open
                      </a>
                    ) : (
                      <Link href={row.href} className="text-xs font-semibold text-purple-700">
                        Open
                      </Link>
                    )
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
          {total > rows.length ? (
            <p className="text-xs text-ink-500">
              Showing {rows.length} of {total}.{" "}
              <Link href={`/search?q=${encodeURIComponent(query.trim())}`} className="font-semibold text-purple-700">
                See all results
              </Link>
            </p>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
