"use client";

import { useState } from "react";
import { Search } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
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
};

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [place, setPlace] = useState("");
  const [result, setResult] = useState<SearchResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function search(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      setResult(await apiFetch<SearchResult>(`/search${buildQuery({ q: query, place, pageSize: 20 })}`));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Search failed");
    }
  }

  const total =
    (result?.artisans.length ?? 0) +
    (result?.workshops.length ?? 0) +
    (result?.products.length ?? 0) +
    (result?.tools.length ?? 0) +
    (result?.media.length ?? 0);

  return (
    <>
      <PageHeader
        title="Search"
        description="Search across artisans, workshops, products, tools and media with shared API filters."
        icon={<Search className="h-5 w-5" aria-hidden />}
      />
      <form onSubmit={search} className="panel mb-5 grid gap-3 p-4 md:grid-cols-[1fr_220px_auto]">
        <input className="field-input" placeholder="Search repository" value={query} onChange={(event) => setQuery(event.target.value)} />
        <input className="field-input" placeholder="Place filter" value={place} onChange={(event) => setPlace(event.target.value)} />
        <button className="field-button">
          <Search className="h-4 w-4" aria-hidden />
          Search
        </button>
      </form>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {result && total === 0 ? <EmptyState title="No matching records" /> : null}
      {result ? (
        <div className="grid gap-5">
          <ResultSection
            title="Artisans"
            items={result.artisans.map((item) => ({ id: item.id, title: item.name, subtitle: `${item.place} · ${item.craft?.name ?? "No craft"}`, status: item.status, date: item.createdAt }))}
          />
          <ResultSection
            title="Workshops"
            items={result.workshops.map((item) => ({ id: item.id, title: item.title, subtitle: item.place, status: item.status, date: item.date }))}
          />
          <ResultSection
            title="Products"
            items={result.products.map((item) => ({ id: item.id, title: item.productName, subtitle: `${item.craftName} · ${item.artisanName} · ${item.place}`, status: item.status, date: item.createdAt }))}
          />
          <ResultSection
            title="Tools"
            items={result.tools.map((item) => ({ id: item.id, title: item.toolkitName, subtitle: `${item.craftName} · ${item.artisanName} · ${item.place}`, status: item.status, date: item.createdAt }))}
          />
          <ResultSection
            title="Media"
            items={result.media.map((item) => ({ id: item.id, title: item.originalFilename, subtitle: `${item.mediaType} · ${item.mimeType}`, status: item.status, date: item.createdAt }))}
          />
        </div>
      ) : null}
    </>
  );
}

function ResultSection({
  title,
  items
}: {
  title: string;
  items: Array<{ id: string; title: string; subtitle: string; status: string; date: string }>;
}) {
  if (items.length === 0) return null;
  return (
    <section className="panel overflow-hidden">
      <div className="border-b border-neutral-200 px-4 py-3">
        <h2 className="font-semibold text-neutral-950">{title}</h2>
      </div>
      <div className="divide-y divide-neutral-200">
        {items.map((item) => (
          <div key={item.id} className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div className="font-medium text-neutral-900">{item.title}</div>
              <div className="text-sm text-neutral-600">{item.subtitle}</div>
              <div className="text-xs text-neutral-500">{item.id}</div>
            </div>
            <div className="flex items-center gap-3">
              <StatusBadge status={item.status} />
              <span className="text-sm text-neutral-600">{formatDateTime(item.date)}</span>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
