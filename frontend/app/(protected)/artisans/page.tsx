"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Plus, Users } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue, type FunnelWorkshop } from "@/components/FunnelFilters";
import { PageHeader } from "@/components/PageHeader";
import { CompletenessFilter } from "@/components/records/CompletenessFilter";
import { RowCompletenessRing } from "@/components/records/CompletenessRing";
import { useAuth } from "@/components/AuthProvider";
import { canCreateRecords } from "@/lib/permissions";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useListReturn } from "@/components/hooks/useListReturn";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { apiFetch, listResource } from "@/lib/api";
import { Toggle } from "@/components/ui/Toggle";
import { parseCompletenessFilter, type CompletenessFilter as CompletenessValue } from "@/lib/completenessCore";
import { formatDate } from "@/lib/format";
import { recordUpdateHref, recordViewHref } from "@/lib/recordView";
import type { Artisan, PageResult } from "@/lib/types";
import { RecordStatusFilter, statusFilterParam } from "@/components/review/RecordStatusFilter";

export default function ArtisansPage() {
  const { adminMode } = useAdminView();
  const confirm = useConfirm();
  const [data, setData] = useState<PageResult<Artisan> | null>(null);
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [statusChoice, setStatusChoice] = useState("ALL");
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelWorkshop, setFunnelWorkshop] = useState<FunnelWorkshop | null>(null);
  const [funnelReady, setFunnelReady] = useState(false);
  const [completeness, setCompleteness] = useState<CompletenessValue>("");
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** C18: only the artisans who did not share an Aadhaar — the follow-up list. */
  const [followUp, setFollowUp] = useState(false);
  const skipFirstDebounce = useRef(true);
  /**
   * Which fetch is the current one. Search is debounced and the funnel and pager fire the same
   * effect, so several requests are routinely in flight at once — and without this the answer to an
   * abandoned question could land last and win, showing results for a filter the researcher had
   * already moved on from. Counted rather than aborted because `listResource` takes no signal, and
   * ignoring a late answer is the part that matters.
   */
  const currentLoad = useRef(0);

  async function load() {
    const generation = (currentLoad.current += 1);
    try {
      // /artisans supports craftId but not workshopId — the workshop filter narrows rows client-side below.
      const result = await listResource<Artisan>("/artisans", {
        search: applied || undefined,
        craftId: funnel.craftId || undefined,
        statusFilter: statusFilterParam(statusChoice),
        aadhaarNotShared: followUp ? "true" : undefined,
        completeness: completeness || undefined,
        page,
        pageSize: 20
      });
      if (generation !== currentLoad.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (generation !== currentLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load artisans");
    }
  }

  // Waits for the funnel's initial onChange (default = most recent workshop) before the first fetch.
  useEffect(() => {
    if (!funnelReady) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, applied, funnel, statusChoice, followUp, completeness]);

  // Live search: debounce typing by 350ms; Enter applies immediately via onSubmit.
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);

  /*
   * BACK FROM A RECORD PUTS THE LIST BACK AS IT WAS. A row click now opens the artisan's view, so the
   * search, the page, the funnel and the scroll position are written down as it opens
   * (`rememberPlace`) and restored here on the way back — see `lib/listReturn.ts`. The funnel comes
   * back through `FunnelFilters`' own `restore`, and its first `onChange` keeps the restored page
   * instead of resetting to page one.
   */
  const listReturn = useListReturn("artisans");
  const restoredPage = useRef<number | null>(null);
  useEffect(() => {
    const note = listReturn.restored;
    if (!note) return;
    if (note.query) {
      skipFirstDebounce.current = true;
      setQuery(note.query);
      setApplied(note.query);
    }
    restoredPage.current = note.page;
    setPage(note.page);
    setCompleteness(parseCompletenessFilter(note.completeness));
  }, [listReturn.restored]);
  const restoreListScroll = listReturn.restoreScroll;
  useEffect(() => restoreListScroll(Boolean(data)), [data, restoreListScroll]);
  const rememberPlace = () => listReturn.remember({ query: applied, page, funnel, completeness });

  function onFunnelChange(next: FunnelValue, workshop: FunnelWorkshop | null) {
    setFunnel(next);
    setFunnelWorkshop(workshop);
    setPage(restoredPage.current ?? 1);
    restoredPage.current = null;
    setFunnelReady(true);
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this artisan record?",
        "Deletes the artisan with their media, answers and comments. This can’t be undone.",
        undefined
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/artisans/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete artisan");
    }
  }

  // The workshop filter is applied client-side (no workshopId param on /artisans): keep only the
  // artisans linked to the selected workshop when that link data is available.
  const workshopArtisanIds = funnelWorkshop?.artisans?.length
    ? new Set(funnelWorkshop.artisans.map((link) => link.artisan.id))
    : null;
  const rows = data ? (workshopArtisanIds ? data.items.filter((artisan) => workshopArtisanIds.has(artisan.id)) : data.items) : [];

  return (
    <>
      <PageHeader
        title="Artisans"
        description="Artisan profiles."
        icon={<Users className="h-5 w-5" aria-hidden />}
        // Gated, not merely locked: an ungated "New …" invited every tier to press a button that
        // lands on the route guard's refusal. Below researcher the honest UI is no button, matching
        // `require_record_creator` on the server.
        actions={
          canCreateRecords(user) ? (
            <Link className="field-button" href="/artisans/new">
              <Plus className="h-4 w-4" aria-hidden />
              New artisan
            </Link>
          ) : null
        }
      />
      <div className="mb-3">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search by name, craft, place or notes"
        />
      </div>
      <FunnelFilters value={funnel} onChange={onFunnelChange} restore={listReturn.restoredFunnel} />
      <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-start">
        <RecordStatusFilter
          value={statusChoice}
          onChange={(value) => {
            setStatusChoice(value || "ALL");
            setPage(1);
          }}
        />
        <CompletenessFilter
          value={completeness}
          onChange={(next) => {
            setCompleteness(next);
            setPage(1);
          }}
        />
        <Toggle
          className="sm:max-w-md"
          label="Aadhaar not shared"
          description="Artisans flagged for an Aadhaar follow-up."
          checked={followUp}
          onChange={(next) => {
            setFollowUp(next);
            setPage(1);
          }}
        />
      </div>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No artisans found" body="Add the first artisan." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Name</ResizableTh>
                  <ResizableTh>Craft</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Contact</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((artisan) => (
                  <OpenableRow key={artisan.id} href={recordViewHref("artisan", artisan.id)!} onOpen={rememberPlace}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <RowCompletenessRing block={artisan.completeness} />
                        <RecordTitleLink href={recordViewHref("artisan", artisan.id)!} onOpen={rememberPlace}>
                          {artisan.name}
                        </RecordTitleLink>
                        {/* Identity numbers are regulated data and never printed in a list: the chip
                            only answers "is this artisan's card on file?", which is what a
                            researcher scanning for gaps actually needs to know. */}
                        {artisan.pehchanCardNumber ? (
                          <span
                            className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-semibold text-field-700 dark:text-purple-300"
                            title="Artisan Pehchan Card number on file"
                          >
                            Pehchan
                          </span>
                        ) : null}
                      </div>
                      <div className="text-xs text-ink-500">{artisan.localName ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">{artisan.craft?.name ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{artisan.place}</td>
                    <td className="px-4 py-3 text-ink-700">{artisan.phone || artisan.email || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={artisan.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDate(artisan.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <Link className={rowAction("edit")} href={recordUpdateHref("artisan", artisan.id)!} onClick={rememberPlace}>
                          Update
                        </Link>
                        <button
                          className={rowAction("neutral")}
                          onClick={(event) => {
                            event.stopPropagation();
                            setCollabId(artisan.id);
                          }}
                        >
                          Discuss
                        </button>
                        {adminMode ? (
                          <button
                            className={rowAction("danger")}
                            onClick={(event) => {
                              event.stopPropagation();
                              remove(artisan.id);
                            }}
                          >
                            Delete
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </OpenableRow>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      <CollabDialog recordType="artisan" recordId={collabId} onClose={() => setCollabId(null)} />
    </>
  );
}
