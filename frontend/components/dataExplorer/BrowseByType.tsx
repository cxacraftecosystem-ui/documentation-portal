"use client";

import { ChevronLeft, ChevronRight, Pencil, Search } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type MouseEvent, type ReactNode } from "react";

import { LINK_BUTTON, Problem, Spinner } from "@/components/dataExplorer/explorerUi";
import { EmptyState } from "@/components/EmptyState";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { fetchBrowse, unsupported } from "@/lib/dataExplorer/api";
import type { BrowsePage } from "@/lib/dataExplorer/contract";

export type BrowseType = {
  value: string;
  label: string;
  viewHref: ((id: string) => string | null) | null;
  updateHref: ((id: string) => string | null) | null;
  legacy: (page: number, q: string, workshopIds: string | undefined) => Promise<BrowsePage>;
  scoped?: boolean;
};

export function browseCutLine(page: BrowsePage, noun: string): string | null {
  if (!page.truncated && page.total <= page.items.length) return null;
  const first = (page.page - 1) * page.pageSize + 1;
  const last = first + page.items.length - 1;
  return `Showing ${first.toLocaleString("en-IN")}–${last.toLocaleString("en-IN")} of ${page.total.toLocaleString("en-IN")} ${noun}.`;
}

export function formatWhen(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

const INTERACTIVE = "a, button, input, select, textarea, label, summary, [role='button'], [data-row-ignore]";

export function BrowseByType({
  types,
  type,
  page,
  q,
  workshopIds,
  scopeControl,
  onChange
}: {
  types: BrowseType[];
  type: string;
  page: number;
  q: string;
  workshopIds?: string;
  scopeControl?: ReactNode;
  onChange: (next: { type: string; page: number; q: string }) => void;
}) {
  const router = useRouter();
  const def = types.find((candidate) => candidate.value === type) ?? null;
  const [draft, setDraft] = useState(q);
  const [seenQ, setSeenQ] = useState(q);
  if (seenQ !== q) {
    setSeenQ(q);
    setDraft(q);
  }
  const [result, setResult] = useState<{ key: string; page: BrowsePage } | null>(null);
  const [error, setError] = useState<{ key: string; message: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const key = JSON.stringify([type, page, q, workshopIds ?? "", attempt]);

  useEffect(() => {
    const timer = setTimeout(() => {
      if (draft !== q) onChange({ type, page: 1, q: draft });
    }, 350);
    return () => clearTimeout(timer);
  }, [draft, q, type, onChange]);

  const defRef = useRef(def);
  useEffect(() => {
    defRef.current = def;
  });
  const typeKnown = Boolean(def);

  useEffect(() => {
    const chosen = defRef.current;
    if (!typeKnown || !chosen) return;
    let cancelled = false;
    const scopeIds = chosen.scoped ? workshopIds : undefined;
    const ask = async () => {
      try {
        return await fetchBrowse({ type: chosen.value, page, q, workshopIds: scopeIds });
      } catch (err) {
        if (!unsupported(err)) throw err;
        return chosen.legacy(page, q, scopeIds);
      }
    };
    ask()
      .then((loaded) => {
        if (!cancelled) setResult({ key, page: loaded });
      })
      .catch((err) => {
        if (!cancelled) setError({ key, message: err instanceof Error && err.message ? err.message : "This list didn't load." });
      });
    return () => {
      cancelled = true;
    };
  }, [typeKnown, page, q, workshopIds, key]);

  const current = result?.key === key ? result.page : null;
  const failed = error?.key === key ? error.message : null;
  const noun = def?.label.toLowerCase() ?? "records";
  const cut = current ? browseCutLine(current, noun) : null;

  const openRow = (event: MouseEvent<HTMLTableRowElement>, href: string | null) => {
    if (!href) return;
    const target = event.target as HTMLElement;
    if (target.closest(INTERACTIVE)) return;
    if (window.getSelection()?.toString()) return;
    if (event.metaKey || event.ctrlKey || event.button === 1) window.open(href, "_blank", "noopener");
    else router.push(href);
  };

  return (
    <section className="panel grid gap-3 p-4" aria-label="Records by type">
      <div className="flex flex-wrap items-end gap-3">
        <div className="grid min-w-48 flex-1 gap-1">
          <span className="field-label">Browse by type</span>
          <SearchableSelect
            value={type}
            onChange={(value) => onChange({ type: value, page: 1, q: "" })}
            options={types.map((entry) => ({ value: entry.value, label: entry.label }))}
            placeholder="Choose a type"
            ariaLabel="Browse by type"
            advanceOnSelect={false}
          />
        </div>
        {def ? (
          <label className="relative grid min-w-56 flex-1 gap-1">
            <span className="field-label">Find</span>
            <Search className="pointer-events-none absolute bottom-3 left-3 h-4 w-4 text-ink-300" aria-hidden />
            <input
              type="search"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder={`Search all ${noun}`}
              aria-label={`Search all ${noun}`}
              className="field-input pl-9"
            />
          </label>
        ) : null}
      </div>
      {def?.scoped ? scopeControl : null}
      {!def ? null : failed ? (
        <Problem onRetry={() => setAttempt((value) => value + 1)}>{failed}</Problem>
      ) : !current ? (
        <Spinner label={`Loading ${noun}…`} />
      ) : current.items.length === 0 ? (
        <EmptyState title={q ? `No ${noun} match “${q}”` : `No ${noun} yet`} />
      ) : (
        <>
          <div className="max-h-96 overflow-auto rounded-md border border-line-200" tabIndex={0} role="region" aria-label={`${def.label} list`}>
            <table className="w-full min-w-[560px] text-left text-sm">
              <thead className="sticky top-0 z-10 bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th scope="col" className="px-4 py-2.5">Name</th>
                  <th scope="col" className="px-4 py-2.5">Date</th>
                  <th scope="col" className="px-4 py-2.5 text-right">Files</th>
                  <th scope="col" className="px-4 py-2.5 text-right">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {current.items.map((row) => {
                  const view = def.viewHref ? def.viewHref(row.id) : null;
                  const update = def.updateHref ? def.updateHref(row.id) : null;
                  return (
                    <tr key={row.id} className={view ? "cursor-pointer hover:bg-purple-50/40" : undefined} onClick={(event) => openRow(event, view)}>
                      <td className="px-4 py-2.5">
                        {view ? (
                          <Link href={view} className="font-medium text-ink-900 hover:text-purple-700 hover:underline">
                            {row.name}
                          </Link>
                        ) : (
                          <span className="font-medium text-ink-900">{row.name}</span>
                        )}
                        {row.subtitle ? <span className="block text-xs text-ink-500">{row.subtitle}</span> : null}
                      </td>
                      <td className="px-4 py-2.5 text-ink-700">{formatWhen(row.createdAt)}</td>
                      <td className="px-4 py-2.5 text-right text-ink-700">{typeof row.mediaCount === "number" ? row.mediaCount : "-"}</td>
                      <td className="px-4 py-2.5 text-right">
                        {update ? (
                          <Link href={update} className={LINK_BUTTON}>
                            <Pencil className="h-3.5 w-3.5" aria-hidden />
                            Update
                          </Link>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs text-ink-500" role="status">
              {cut ?? `${current.total.toLocaleString("en-IN")} ${noun}`}
            </p>
            {current.pages > 1 ? (
              <nav aria-label={`${def.label} pages`} className="flex items-center gap-2">
                <button
                  type="button"
                  className="field-button-secondary min-h-9 px-3 text-sm pointer-coarse:min-h-11"
                  disabled={current.page <= 1}
                  onClick={() => onChange({ type, page: current.page - 1, q })}
                >
                  <ChevronLeft className="h-4 w-4" aria-hidden />
                  Previous
                </button>
                <span className="text-xs text-ink-500" aria-current="page">
                  Page {current.page} of {current.pages}
                </span>
                <button
                  type="button"
                  className="field-button-secondary min-h-9 px-3 text-sm pointer-coarse:min-h-11"
                  disabled={current.page >= current.pages}
                  onClick={() => onChange({ type, page: current.page + 1, q })}
                >
                  Next
                  <ChevronRight className="h-4 w-4" aria-hidden />
                </button>
              </nav>
            ) : null}
          </div>
        </>
      )}
    </section>
  );
}
