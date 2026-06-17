"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Boxes, Plus } from "lucide-react";

import { DownloadCsvButton } from "@/components/DownloadCsvButton";
import { EmptyState } from "@/components/EmptyState";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { PageResult, ProductDocumentation } from "@/lib/types";

export default function ProductsPage() {
  const { adminMode } = useAdminView();
  const [data, setData] = useState<PageResult<ProductDocumentation> | null>(null);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setData(await listResource<ProductDocumentation>("/products", { search, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load products");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function remove(id: string) {
    if (!window.confirm("Delete this product documentation record?")) return;
    await apiFetch(`/products/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <>
      <PageHeader
        title="Product Documentation"
        description="Structured documentation for products, raw materials, pricing, function, demand and media links."
        icon={<Boxes className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <DownloadCsvButton path="/export/products.csv" filename="products.csv" />
            <Link className="field-button" href="/products/new">
              <Plus className="h-4 w-4" aria-hidden />
              New product
            </Link>
          </>
        }
      />
      <form
        className="mb-4 flex flex-col gap-2 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(1);
          load();
        }}
      >
        <input className="field-input" placeholder="Search product, craft, artisan, place, materials or remarks" value={search} onChange={(event) => setSearch(event.target.value)} />
        <button className="field-button-secondary">Search</button>
      </form>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No product records found" body="Create a product record from field observations and link media after upload." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Product</th>
                  <th className="px-4 py-3">Craft</th>
                  <th className="px-4 py-3">Artisan</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Demand</th>
                  <th className="px-4 py-3">Media</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((product) => (
                  <tr key={product.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-900">{product.productName}</div>
                      <div className="text-xs text-neutral-500">{product.localName ?? product.productType}</div>
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{product.craftName}</td>
                    <td className="px-4 py-3 text-neutral-600">{product.artisanName}</td>
                    <td className="px-4 py-3 text-neutral-600">{product.place}</td>
                    <td className="px-4 py-3 text-neutral-600">{product.marketDemand}</td>
                    <td className="px-4 py-3 text-neutral-600">
                      {product.media?.length ? (
                        <div className="grid max-w-[240px] grid-cols-2 gap-2">
                          {product.media.slice(0, 2).map((media) => {
                            const preview = {
                              key: media.id,
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
                    <td className="px-4 py-3 text-neutral-600">{formatDate(product.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <Link className="mr-2 text-sm font-semibold text-field-700" href={`/products/${product.id}/edit`}>
                        Edit
                      </Link>
                      {adminMode ? (
                        <button className="text-sm font-semibold text-red-700" onClick={() => remove(product.id)}>
                          Delete
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </>
  );
}
