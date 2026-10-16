"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Boxes, Plus } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { DownloadCsvButton } from "@/components/DownloadCsvButton";
import { useAuth } from "@/components/AuthProvider";
import { canCreateRecords, canDownloadDataset } from "@/lib/permissions";
import { EmptyState } from "@/components/EmptyState";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue, type FunnelWorkshop } from "@/components/FunnelFilters";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useListReturn } from "@/components/hooks/useListReturn";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { recordUpdateHref, recordViewHref } from "@/lib/recordView";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { PageResult, ProductDocumentation } from "@/lib/types";

export default function ProductsPage() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const { adminMode } = useAdminView();
  const [data, setData] = useState<PageResult<ProductDocumentation> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelReady, setFunnelReady] = useState(false);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);

  async function load() {
    try {
      // /products supports all three funnel params directly.
      setData(
        await listResource<ProductDocumentation>("/products", {
          search: applied || undefined,
          workshopId: funnel.workshopId || undefined,
          craftId: funnel.craftId || undefined,
          artisanId: funnel.artisanId || undefined,
          page,
          pageSize: 20
        })
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load products");
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

  /*
   * BACK FROM A RECORD PUTS THE LIST BACK AS IT WAS. A row click opens the product's read-only view,
   * so the search, page, funnel and scroll position are written down as it opens (`rememberPlace`)
   * and restored here on the way back — see `lib/listReturn.ts` and the artisans list, which carries
   * the same block.
   */
  const listReturn = useListReturn("products");
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
  }, [listReturn.restored]);
  const restoreListScroll = listReturn.restoreScroll;
  useEffect(() => restoreListScroll(Boolean(data)), [data, restoreListScroll]);
  const rememberPlace = () => listReturn.remember({ query: applied, page, funnel });

  function onFunnelChange(next: FunnelValue, _workshop: FunnelWorkshop | null) {
    setFunnel(next);
    setPage(restoredPage.current ?? 1);
    restoredPage.current = null;
    setFunnelReady(true);
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this product record?",
        "This permanently deletes the record. This action cannot be undone.",
        "Photos and files documenting this product are deleted with it."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/products/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete product record");
    }
  }

  return (
    <>
      <PageHeader
        title="Product Documentation"
        description="Structured documentation for products, raw materials, pricing, function, demand and media links."
        icon={<Boxes className="h-5 w-5" aria-hidden />}
        actions={
          <>
            {canDownloadDataset(user) ? <DownloadCsvButton path="/export/products.csv" filename="products.csv" /> : null}
            {/* Gated, not merely locked: an ungated "New …" invited every tier to press a
                button that lands on the route guard's refusal. Below researcher the honest UI is
                no button, matching `require_record_creator` on the server. */}
            {canCreateRecords(user) ? (
              <Link className="field-button" href="/products/new">
                <Plus className="h-4 w-4" aria-hidden />
                New product
              </Link>
            ) : null}
          </>
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
          placeholder="Search product, craft, artisan, place, materials or remarks"
        />
      </div>
      <FunnelFilters value={funnel} onChange={onFunnelChange} showArtisan restore={listReturn.restoredFunnel} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No product records found" body="Create a product record from field observations and link media after upload." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Product</ResizableTh>
                  <ResizableTh>Craft</ResizableTh>
                  <ResizableTh>Artisan</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Demand</ResizableTh>
                  <ResizableTh>Media</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((product) => (
                  <OpenableRow key={product.id} href={recordViewHref("product", product.id)!} onOpen={rememberPlace}>
                    <td className="px-4 py-3">
                      <RecordTitleLink href={recordViewHref("product", product.id)!} onOpen={rememberPlace}>
                        {product.productName}
                      </RecordTitleLink>
                      <div className="text-xs text-ink-500">{product.localName ?? product.productType}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">{product.craftName}</td>
                    <td className="px-4 py-3 text-ink-700">{product.artisanName}</td>
                    <td className="px-4 py-3 text-ink-700">{product.place}</td>
                    <td className="px-4 py-3 text-ink-700">{product.marketDemand}</td>
                    <td className="px-4 py-3 text-ink-700">
                      {product.media?.length ? (
                        <div className="grid max-w-[240px] grid-cols-2 gap-2">
                          {product.media.slice(0, 2).map((media) => {
                            const preview = {
                              key: media.id,
                              id: media.id,
                              name: media.originalFilename,
                              mediaType: media.mediaType,
                              mimeType: media.mimeType,
                              sizeBytes: media.sizeBytes,
                              url: media.url,
                              caption: media.caption,
                              transcriptStatus: media.transcriptStatus,
                              transcriptText: media.transcriptText,
                              transcriptError: media.transcriptError
                            };
                            return <MediaPreviewTile key={media.id} item={preview} onOpen={() => setActivePreview(preview)} />;
                          })}
                          {product.media.length > 2 ? <span className="text-xs font-semibold text-ink-muted">+{product.media.length - 2} more</span> : null}
                        </div>
                      ) : (
                        "0"
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={product.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDate(product.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <Link className={rowAction("edit")} href={recordUpdateHref("product", product.id)!} onClick={rememberPlace}>
                          Update
                        </Link>
                        <button className={rowAction("neutral")} onClick={() => setCollabId(product.id)}>
                          Discuss
                        </button>
                        {adminMode ? (
                          <button className={rowAction("danger")} onClick={() => remove(product.id)}>
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
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
      <CollabDialog recordType="product" recordId={collabId} onClose={() => setCollabId(null)} />
    </>
  );
}
