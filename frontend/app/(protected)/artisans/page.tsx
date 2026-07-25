"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Boxes, ClipboardList, Hammer, Plus, Users } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue, type FunnelWorkshop } from "@/components/FunnelFilters";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Artisan, PageResult } from "@/lib/types";

export default function ArtisansPage() {
  const { adminMode } = useAdminView();
  const confirm = useConfirm();
  const [data, setData] = useState<PageResult<Artisan> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelWorkshop, setFunnelWorkshop] = useState<FunnelWorkshop | null>(null);
  const [funnelReady, setFunnelReady] = useState(false);
  const [selectedArtisan, setSelectedArtisan] = useState<Artisan | null>(null);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);

  async function load() {
    try {
      // /artisans supports craftId but not workshopId — the workshop filter narrows rows client-side below.
      setData(
        await listResource<Artisan>("/artisans", {
          search: applied || undefined,
          craftId: funnel.craftId || undefined,
          page,
          pageSize: 20
        })
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load artisans");
    }
  }

  // Waits for the funnel's initial onChange (default = most recent workshop) before the first fetch.
  useEffect(() => {
    if (!funnelReady) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, applied, funnel]);

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

  function onFunnelChange(next: FunnelValue, workshop: FunnelWorkshop | null) {
    setFunnel(next);
    setFunnelWorkshop(workshop);
    setPage(1);
    setFunnelReady(true);
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this artisan record?",
        "This permanently deletes the record. This action cannot be undone.",
        "Media, questionnaire answers and comments attached to this artisan go with it."
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

  function artisanEntryHref(path: string, artisan: Artisan) {
    const params = new URLSearchParams({
      artisanId: artisan.id,
      artisanName: artisan.name,
      place: artisan.place
    });
    if (artisan.craftId) params.set("craftId", artisan.craftId);
    if (artisan.craft?.name) params.set("craftName", artisan.craft.name);
    return `${path}?${params.toString()}`;
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
        description="Create, search and maintain artisan profiles with craft, place and contact metadata."
        icon={<Users className="h-5 w-5" aria-hidden />}
        actions={
          <Link className="field-button" href="/artisans/new">
            <Plus className="h-4 w-4" aria-hidden />
            New artisan
          </Link>
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
      <FunnelFilters value={funnel} onChange={onFunnelChange} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {selectedArtisan ? (
        <section className="mb-5 grid gap-3 md:grid-cols-3">
          {[
            { href: artisanEntryHref("/tools/new", selectedArtisan), title: "Make a tool entry", body: "Document a tool used by this artisan.", icon: Hammer },
            { href: artisanEntryHref("/products/new", selectedArtisan), title: "Make a product entry", body: "Record an object, product or sample.", icon: Boxes },
            { href: artisanEntryHref("/questionnaire", selectedArtisan), title: "Start questionnaire", body: "Open the interview with RESP prefilled.", icon: ClipboardList }
          ].map((item) => (
            <Link key={item.href} href={item.href} className="panel group flex min-h-32 items-start gap-3 p-4 transition hover:-translate-y-0.5 hover:shadow-panel">
              <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-field-200 text-field-700">
                <item.icon className="h-5 w-5" aria-hidden />
              </span>
              <span>
                <span className="block font-display font-bold text-xl text-ink">{item.title}</span>
                <span className="mt-1 block text-sm leading-6 text-ink-muted">{item.body}</span>
                <span className="mt-3 block text-xs font-semibold uppercase text-field-700">{selectedArtisan.name}</span>
              </span>
            </Link>
          ))}
        </section>
      ) : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No artisans found" body="Add an artisan profile before linking product, tool or workshop records." />
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
                  <tr key={artisan.id} className="cursor-pointer hover:bg-field-100" onClick={() => setSelectedArtisan(artisan)}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-ink-900">{artisan.name}</span>
                        {/* Identity numbers are regulated data and never printed in a list: the chip
                            only answers "is this artisan's card on file?", which is what a
                            researcher scanning for gaps actually needs to know. */}
                        {artisan.pehchanCardNumber ? (
                          <span
                            className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-semibold text-field-700"
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
                        <Link
                          className={rowAction("edit")}
                          href={`/artisans/${artisan.id}/edit`}
                          onClick={(event) => event.stopPropagation()}
                        >
                          Edit
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
                  </tr>
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
