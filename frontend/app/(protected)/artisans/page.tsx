"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Plus, Users } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Artisan, PageResult } from "@/lib/types";

export default function ArtisansPage() {
  const [data, setData] = useState<PageResult<Artisan> | null>(null);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setData(await listResource<Artisan>("/artisans", { search, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load artisans");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function remove(id: string) {
    if (!window.confirm("Delete this artisan record?")) return;
    await apiFetch(`/artisans/${id}`, { method: "DELETE" });
    load();
  }

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
      <form
        className="mb-4 flex flex-col gap-2 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(1);
          load();
        }}
      >
        <input className="field-input" placeholder="Search by name, craft, place or notes" value={search} onChange={(event) => setSearch(event.target.value)} />
        <button className="field-button-secondary">Search</button>
      </form>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No artisans found" body="Add an artisan profile before linking product, tool or workshop records." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Name</th>
                  <th className="px-4 py-3">Craft</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Contact</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((artisan) => (
                  <tr key={artisan.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-900">{artisan.name}</div>
                      <div className="text-xs text-neutral-500">{artisan.localName ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{artisan.craft?.name ?? "-"}</td>
                    <td className="px-4 py-3 text-neutral-600">{artisan.place}</td>
                    <td className="px-4 py-3 text-neutral-600">{artisan.phone || artisan.email || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={artisan.status} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDate(artisan.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <Link className="mr-2 text-sm font-semibold text-field-700" href={`/artisans/${artisan.id}/edit`}>
                        Edit
                      </Link>
                      <button className="text-sm font-semibold text-red-700" onClick={() => remove(artisan.id)}>
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
    </>
  );
}
