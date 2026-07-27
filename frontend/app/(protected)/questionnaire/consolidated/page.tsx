"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { ChevronRight, Layers } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import { listResource } from "@/lib/api";
import type { Artisan } from "@/lib/types";

/**
 * Picks the artisan whose consolidated questionnaire to read.
 *
 * This index exists so the consolidated view is reachable on its own, without a link having to be
 * threaded through the questionnaire list page. Each artisan gets a stable, citable URL
 * (`/questionnaire/consolidated/<id>`), which is the point: the document is the thing a researcher
 * quotes, so it needs an address.
 */
export default function ConsolidatedIndexPage() {
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");

  useEffect(() => {
    let active = true;
    listResource<Artisan>("/artisans", { pageSize: 100 })
      .then((result) => {
        if (active) setArtisans(result.items);
      })
      .catch((err: unknown) => {
        if (active) setError(err instanceof Error ? err.message : "Unable to load artisans.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return artisans;
    return artisans.filter((artisan) =>
      [artisan.name, artisan.craft?.name, artisan.place].filter(Boolean).join(" ").toLowerCase().includes(needle)
    );
  }, [artisans, query]);

  return (
    <div className="pb-16">
      <PageHeader
        title="Consolidated questionnaire"
        description="An interview is stored once per exact set of artisans, so most artisans' answers are spread across several entries. Pick an artisan to read everything they contributed as one document, with each answer still naming the interview it came from."
        icon={<Layers className="h-5 w-5" aria-hidden />}
      />

      <div className="mb-4 max-w-md">
        <SearchInput value={query} onChange={setQuery} placeholder="Search artisans by name, craft or place" />
      </div>

      {error ? <EmptyState title="Could not load artisans" body={error} /> : null}
      {loading ? <div className="panel p-6 text-sm text-ink-500">Loading artisans…</div> : null}

      {!loading && !error && filtered.length === 0 ? (
        <EmptyState title="No artisans match" body="Try a different name, craft or place." />
      ) : null}

      <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {filtered.map((artisan) => (
          <li key={artisan.id}>
            <Link
              href={`/questionnaire/consolidated/${artisan.id}`}
              className="panel flex items-center justify-between gap-3 p-4 transition hover:border-purple-300 hover:shadow-md"
            >
              <span className="min-w-0">
                <span className="block truncate font-display font-bold text-ink-900">{artisan.name}</span>
                <span className="mt-0.5 block truncate text-xs text-ink-500">
                  {[artisan.craft?.name, artisan.place].filter(Boolean).join(" · ") || "No craft recorded"}
                </span>
              </span>
              <ChevronRight className="h-5 w-5 shrink-0 text-purple-700" aria-hidden />
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
