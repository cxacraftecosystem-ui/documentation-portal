"use client";

import { ArrowUpRight, ChevronLeft, ChevronRight } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

import { Problem, Spinner } from "@/components/dataExplorer/explorerUi";
import { fetchPeer, PEER_TYPE_LABELS, peerStatusLine, type ExplorerFilters, type PeerPage } from "@/lib/dataExplorer/filters";

function when(value: string | null): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function PortalChip({ label }: { label: string }) {
  return (
    <span className="inline-flex min-h-[22px] shrink-0 items-center rounded-full border border-purple-200 bg-purple-50 px-2.5 text-[11px] font-bold text-purple-800 dark:border-purple-800 dark:bg-purple-950/50 dark:text-purple-200" data-testid="portal-chip">
      {label}
    </span>
  );
}

export function PeerResults({ filters, type, q, title }: { filters: ExplorerFilters; type?: string; q?: string; title?: string }) {
  const [page, setPage] = useState(1);
  const key = JSON.stringify([filters, type ?? "", q ?? ""]);
  const [seenKey, setSeenKey] = useState(key);
  if (seenKey !== key) {
    setSeenKey(key);
    setPage(1);
  }
  const [result, setResult] = useState<{ key: string; page: PeerPage } | null>(null);
  const [error, setError] = useState<{ key: string; message: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const askKey = `${key}:${page}:${attempt}`;

  useEffect(() => {
    const abort = new AbortController();
    const asked = JSON.parse(key) as [ExplorerFilters, string, string];
    fetchPeer({ filters: asked[0], type: asked[1] || undefined, q: asked[2] || undefined, page }, abort.signal)
      .then((answer) => {
        if (!abort.signal.aborted) setResult({ key: askKey, page: answer });
      })
      .catch((err: unknown) => {
        if (!abort.signal.aborted) setError({ key: askKey, message: err instanceof Error && err.message ? err.message : "Other-portal results didn't load." });
      });
    return () => abort.abort();
  }, [key, page, askKey]);

  const current = result?.key === askKey ? result.page : null;
  const failed = error?.key === askKey ? error.message : null;
  const portal = current?.portalLabel ?? "Other portal";
  const line = peerStatusLine(current);

  return (
    <section aria-label={`${portal} results`} className="grid gap-2" data-testid="peer-results">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-bold text-ink-900">{title ?? `From ${portal}`}</h3>
        {current && current.status === "ok" ? <span className="text-xs text-ink-500">{current.total.toLocaleString("en-IN")}</span> : null}
      </div>
      {failed ? (
        <Problem onRetry={() => setAttempt((value) => value + 1)}>{failed}</Problem>
      ) : !current ? (
        <Spinner label={`Asking ${portal}…`} />
      ) : line ? (
        <p className="text-sm text-ink-500" role="status">
          {line}
        </p>
      ) : current.items.length === 0 ? (
        <p className="text-sm text-ink-500" role="status">
          Nothing in {portal} matches.
        </p>
      ) : (
        <>
          <ul className="grid gap-1.5">
            {current.items.map((row) => (
              <li key={`${row.type}:${row.id}`} className="flex min-w-0 items-start gap-3 rounded-md border border-line-200 bg-card px-3 py-2.5" data-testid="peer-row">
                <div className="grid min-w-0 flex-1 gap-0.5">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <Link href={row.href} className="min-w-0 font-medium [overflow-wrap:anywhere] text-ink-900 hover:text-purple-700 hover:underline">
                      {row.title || "Untitled"}
                    </Link>
                    <PortalChip label={row.portalLabel} />
                  </div>
                  <span className="text-xs text-ink-500">{[PEER_TYPE_LABELS[row.type] ?? row.type, row.subtitle, row.place, when(row.recordDate)].filter(Boolean).join(" · ")}</span>
                </div>
                <Link href={row.href} className="inline-flex min-h-9 shrink-0 items-center gap-1 rounded-full border border-line-200 px-3 text-xs font-semibold text-ink-900 hover:border-purple-300 pointer-coarse:min-h-11" aria-label={`Open ${row.title} in ${row.portalLabel}`}>
                  Open
                  <ArrowUpRight className="h-3.5 w-3.5" aria-hidden />
                </Link>
              </li>
            ))}
          </ul>
          {current.pages > 1 ? (
            <nav aria-label={`${portal} pages`} className="flex items-center justify-end gap-2">
              <button type="button" className="field-button-secondary min-h-9 px-3 text-sm pointer-coarse:min-h-11" disabled={page <= 1} onClick={() => setPage((value) => value - 1)}>
                <ChevronLeft className="h-4 w-4" aria-hidden />
                Previous
              </button>
              <span className="text-xs text-ink-500">
                Page {current.page} of {current.pages}
              </span>
              <button type="button" className="field-button-secondary min-h-9 px-3 text-sm pointer-coarse:min-h-11" disabled={page >= current.pages} onClick={() => setPage((value) => value + 1)}>
                Next
                <ChevronRight className="h-4 w-4" aria-hidden />
              </button>
            </nav>
          ) : null}
        </>
      )}
    </section>
  );
}
