"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Hammer, Plus } from "lucide-react";

import { DownloadCsvButton } from "@/components/DownloadCsvButton";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { isAdmin } from "@/lib/permissions";
import type { PageResult, ToolDocumentation } from "@/lib/types";

export default function ToolsPage() {
  const { user } = useAuth();
  const [data, setData] = useState<PageResult<ToolDocumentation> | null>(null);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setData(await listResource<ToolDocumentation>("/tools", { search, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load tools");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function remove(id: string) {
    if (!window.confirm("Delete this tool documentation record?")) return;
    await apiFetch(`/tools/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <>
      <PageHeader
        title="Tool Documentation"
        description="Document tools, dimensions, materials, maker type, tradition type, replacement cost and improvement notes."
        icon={<Hammer className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <DownloadCsvButton path="/export/tools.csv" filename="tools.csv" />
            <Link className="field-button" href="/tools/new">
              <Plus className="h-4 w-4" aria-hidden />
              New tool
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
        <input className="field-input" placeholder="Search tool, craft, artisan, place, material or remarks" value={search} onChange={(event) => setSearch(event.target.value)} />
        <button className="field-button-secondary">Search</button>
      </form>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No tool records found" body="Create a tool record and attach field media from the media page." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Tool</th>
                  <th className="px-4 py-3">Craft</th>
                  <th className="px-4 py-3">Artisan</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Material</th>
                  <th className="px-4 py-3">Media</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((tool) => (
                  <tr key={tool.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-900">{tool.toolkitName}</div>
                      <div className="text-xs text-neutral-500">{tool.englishName ?? tool.localName ?? tool.traditionType}</div>
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{tool.craftName}</td>
                    <td className="px-4 py-3 text-neutral-600">{tool.artisanName}</td>
                    <td className="px-4 py-3 text-neutral-600">{tool.place}</td>
                    <td className="px-4 py-3 text-neutral-600">{tool.material ?? "-"}</td>
                    <td className="px-4 py-3 text-neutral-600">{tool.media?.length ?? 0}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={tool.status} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDate(tool.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <Link className="mr-2 text-sm font-semibold text-field-700" href={`/tools/${tool.id}/edit`}>
                        Edit
                      </Link>
                      {isAdmin(user) ? (
                        <button className="text-sm font-semibold text-red-700" onClick={() => remove(tool.id)}>
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
    </>
  );
}
