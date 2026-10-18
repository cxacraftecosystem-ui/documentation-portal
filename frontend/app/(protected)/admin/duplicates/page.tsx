"use client";

/**
 * /admin/duplicates — likely duplicate artisans, for an admin to merge or set aside.
 *
 * The server finds the pairs (at save time and in a nightly scan) and scores them; nothing is ever
 * merged on its own. Two tabs: the open queue and the pairs an admin said were different people. A
 * pair is compared and merged on `/admin/artisan-merge`, which also lists the merges made and undoes
 * them for 30 days. Admin only (`require_admin` on every route; the
 * `/admin` guard and admin-view chrome cover this page).
 */

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Users } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { RowActions, rowAction } from "@/components/RowActions";
import { useToast } from "@/components/ui/Toast";
import { ApiError } from "@/lib/api";
import {
  BAND_LABEL,
  dismiss,
  listPairs,
  mergeHref,
  reopen,
  type DuplicateBand,
  type DuplicatePair,
  type PairPage
} from "@/lib/duplicates";

type Tab = "OPEN" | "NOT_DUPLICATE";

const TABS: Array<{ id: Tab; label: string }> = [
  { id: "OPEN", label: "Open" },
  { id: "NOT_DUPLICATE", label: "Not duplicates" }
];

const BANDS: DuplicateBand[] = ["VERY_LIKELY", "LIKELY", "POSSIBLE"];

const CHIP_BASE = "rounded-full border px-3 py-1 text-xs font-medium transition-colors";
const CHIP_ON = "border-purple-700 bg-purple-700 text-white";
const CHIP_OFF = "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300 hover:text-purple-700";

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const detail = (error.payload as { detail?: unknown } | undefined)?.detail;
    if (detail && typeof detail === "object" && "message" in detail) return String((detail as { message: unknown }).message);
    if (typeof detail === "string") return detail;
  }
  return fallback;
}

function Score({ value, band }: { value: number; band: DuplicateBand }) {
  const tone =
    band === "VERY_LIKELY"
      ? "border-purple-700 bg-purple-700 text-white"
      : band === "LIKELY"
        ? "border-purple-300 bg-purple-50 text-purple-700"
        : "border-line-200 bg-surface-50 text-ink-700";
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-semibold ${tone}`}>
      {Math.round(value * 100)}%
      <span className="font-normal">· {BAND_LABEL[band]}</span>
    </span>
  );
}

function Side({ side }: { side: DuplicatePair["a"] }) {
  if (!side) return <span className="text-ink-500">—</span>;
  return (
    <div className="min-w-0">
      <Link href={`/artisans/${side.id}`} className="font-medium text-ink-900 hover:text-purple-700">
        {side.name}
      </Link>
      <div className="truncate text-xs text-ink-500">{[side.place, side.district, side.craft].filter(Boolean).join(" · ")}</div>
    </div>
  );
}

export default function DuplicatesPage() {
  const { toast } = useToast();
  const [tab, setTab] = useState<Tab>("OPEN");
  const [band, setBand] = useState<DuplicateBand | null>(null);
  const [page, setPage] = useState(1);
  const [pairs, setPairs] = useState<PairPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(async () => {
    const mine = ++generation.current;
    setError(null);
    try {
      const result = await listPairs({ status: tab, kind: "WITHIN", band, page });
      if (mine === generation.current) setPairs(result);
    } catch (err) {
      if (mine === generation.current) setError(errorText(err, "Couldn’t load the list."));
    }
  }, [tab, band, page]);

  useEffect(() => {
    void load();
  }, [load]);

  async function onReopen(pair: DuplicatePair) {
    setBusy(pair.id);
    try {
      await reopen(pair.id);
      toast({ id: "duplicates", tone: "success", title: "Back in the open queue" });
      await load();
    } catch (err) {
      setError(errorText(err, "Couldn’t reopen this pair."));
    } finally {
      setBusy(null);
    }
  }

  async function onDismiss(pair: DuplicatePair) {
    setBusy(pair.id);
    try {
      await dismiss(pair.id);
      toast({ id: "duplicates", tone: "success", title: "Set aside as different people" });
      await load();
    } catch (err) {
      setError(errorText(err, "Couldn’t set this pair aside."));
    } finally {
      setBusy(null);
    }
  }

  const counts = pairs?.counts;

  return (
    <>
      <PageHeader
        title="Duplicate artisans"
        description="Merge records of one person, or set them aside."
        icon={<Users className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div className="mb-3 flex flex-wrap items-center gap-1.5" role="group" aria-label="Which pairs">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            aria-pressed={tab === item.id}
            className={`${CHIP_BASE} ${tab === item.id ? CHIP_ON : CHIP_OFF}`}
            onClick={() => {
              setTab(item.id);
              setPage(1);
            }}
          >
            {item.label}
          </button>
        ))}
      </div>
      <div className="mb-4 flex flex-wrap items-center gap-1.5" role="group" aria-label="How likely">
        <button
          type="button"
          aria-pressed={band === null}
          className={`${CHIP_BASE} ${band === null ? CHIP_ON : CHIP_OFF}`}
          onClick={() => {
            setBand(null);
            setPage(1);
          }}
        >
          All
        </button>
        {BANDS.map((item) => (
          <button
            key={item}
            type="button"
            aria-pressed={band === item}
            className={`${CHIP_BASE} ${band === item ? CHIP_ON : CHIP_OFF}`}
            onClick={() => {
              setBand(item);
              setPage(1);
            }}
          >
            {BAND_LABEL[item]}
            {counts ? ` · ${counts[item]}` : ""}
          </button>
        ))}
      </div>

      <section className="panel overflow-hidden">
        {!pairs ? (
          <div className="p-4 text-sm text-ink-500">Loading…</div>
        ) : pairs.items.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title={tab === "OPEN" ? "No likely duplicates" : "Nothing set aside"}
              body={tab === "OPEN" ? "New pairs appear as records are saved." : "Pairs marked as different people appear here."}
            />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {pairs.items.map((pair) => (
              <li key={pair.id} className="grid gap-2 px-4 py-3 md:grid-cols-[1fr_1fr_auto] md:items-center">
                <Side side={pair.a} />
                <Side side={pair.b} />
                <div className="flex flex-wrap items-center gap-2 md:justify-end">
                  <Score value={pair.score} band={pair.band} />
                  {pair.creatorSaidDifferent ? (
                    <span className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] text-ink-500">
                      Saved as different
                    </span>
                  ) : null}
                  <RowActions>
                    {tab === "OPEN" ? (
                      <>
                        {mergeHref(pair) ? (
                          <Link href={mergeHref(pair) ?? ""} className={rowAction("edit")}>
                            Compare
                          </Link>
                        ) : null}
                        <button type="button" className={rowAction("neutral")} disabled={busy === pair.id} onClick={() => onDismiss(pair)}>
                          Different people
                        </button>
                      </>
                    ) : (
                      <button type="button" className={rowAction("neutral")} disabled={busy === pair.id} onClick={() => onReopen(pair)}>
                        Reopen
                      </button>
                    )}
                  </RowActions>
                </div>
                <div className="flex flex-wrap gap-1 md:col-span-3">
                  {pair.reasons.map((reason) => (
                    <span key={reason.code} className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] text-ink-700">
                      {reason.label}
                    </span>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        )}
        {pairs && pairs.pages > 1 ? (
          <Pagination page={pairs.page} pages={pairs.pages} total={pairs.total} onPage={setPage} />
        ) : null}
      </section>
    </>
  );
}
