"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { GitBranch, MessageSquare, Pencil, Plus, Trash2 } from "lucide-react";

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
import { useAuth } from "@/components/AuthProvider";
import { useListReturn } from "@/components/hooks/useListReturn";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { recordViewHref } from "@/lib/recordView";
import { ProcessForm, type ProcessRecord } from "@/components/forms/ProcessForm";
import { RecordSwitcher } from "@/components/forms/RecordSwitcher";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { canCreateRecords } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";
import { RecordStatusFilter, statusFilterParam } from "@/components/review/RecordStatusFilter";

function ProcessesPageInner() {
  const confirm = useConfirm();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { canDeleteRecords } = useAdminView();
  const { user } = useAuth();
  const creator = canCreateRecords(user);
  const isNew = searchParams.get("new") === "1" && creator;

  const [data, setData] = useState<PageResult<ProcessRecord> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [statusChoice, setStatusChoice] = useState("ALL");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);

  const [editing, setEditing] = useState<ProcessRecord | null>(null);
  const [editLoadingId, setEditLoadingId] = useState<string | null>(null);

  useEffect(() => {
    if (!creator && searchParams.get("new") === "1") router.replace("/processes");
  }, [creator, router, searchParams]);
  const [collabId, setCollabId] = useState<string | null>(null);

  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelReady, setFunnelReady] = useState(false);

  async function load(pageToLoad = page) {
    try {
      setData(
        await listResource<ProcessRecord>("/processes", {
          search: applied || undefined,
          workshopId: funnel.workshopId || undefined,
          craftId: funnel.craftId || undefined,
          artisanId: funnel.artisanId || undefined,
          statusFilter: statusFilterParam(statusChoice),
          page: pageToLoad,
          pageSize: 20
        })
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load processes");
    }
  }

  useEffect(() => {
    if (!funnelReady) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, applied, funnel, statusChoice]);

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

  const listReturn = useListReturn("processes", { query: applied, page, funnel }, Boolean(data));
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

  async function openEdit(id: string) {
    setError(null);
    setSuccess(null);
    setEditLoadingId(id);
    try {
      setEditing(await apiFetch<ProcessRecord>(`/processes/${id}`));
      window.scrollTo({ top: 0 });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load this process");
    } finally {
      setEditLoadingId(null);
    }
  }

  const { loading: deepLinkLoading } = useEditDeepLink<ProcessRecord>({
    endpoint: "/processes",
    basePath: "/processes",
    onEdit: setEditing,
    onError: setError,
    allowed: true,
    errorMessage: "Unable to load this process"
  });

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this process record?",
        "Deletes the process with its steps and media. This can’t be undone.",
        undefined
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/processes/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete process record");
    }
  }

  const banners = (
    <>
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
      ) : null}
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">
          Loading the process you asked to edit...
        </div>
      ) : null}
      {success ? (
        <div className="mb-4 rounded-md border border-emerald-200 bg-success-100 px-3 py-2 text-sm text-success-600">{success}</div>
      ) : null}
    </>
  );

  if (isNew) {
    return (
      <>
        <PageHeader
          title="Document process"
          description="How a product is made, step by step."
          icon={<GitBranch className="h-5 w-5" aria-hidden />}
        />
        {banners}
        <ProcessForm
          onDone={() => {
            router.replace("/processes");
            setSuccess("Process documented.");
            setPage(1);
            load(1);
          }}
          onCancel={() => router.replace("/processes")}
        />
      </>
    );
  }

  if (editing) {
    return (
      <>
        <PageHeader
          title="Update process"
          description="Existing step media is kept."
          icon={<GitBranch className="h-5 w-5" aria-hidden />}
        />
        {banners}
        <RecordSwitcher
          kind="process"
          currentId={editing.id}
          currentWorkshopId={editing.workshopId}
          className="panel mb-5 grid gap-3 p-4 md:grid-cols-2"
        />
        <ProcessForm
          initial={editing}
          onDone={() => {
            setEditing(null);
            setSuccess("Process updated.");
            load();
          }}
          onCancel={() => setEditing(null)}
        />
      </>
    );
  }

  const filtersActive = Boolean(applied || funnel.workshopId || funnel.craftId || funnel.artisanId);

  return (
    <>
      <PageHeader
        title="Document process"
        description="How a product is made, step by step."
        icon={<GitBranch className="h-5 w-5" aria-hidden />}
        actions={
          creator ? (
            <button className="field-button" onClick={() => router.push("/processes?new=1")} type="button">
              <Plus className="h-4 w-4" aria-hidden />
              New process
            </button>
          ) : undefined
        }
      />
      {banners}

      <div className="mb-3">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search process title or notes"
        />
      </div>
      <FunnelFilters value={funnel} onChange={onFunnelChange} showArtisan restore={listReturn.restoredFunnel} />
      <div className="mb-3">
        <RecordStatusFilter
          value={statusChoice}
          onChange={(value) => {
            setStatusChoice(value || "ALL");
            setPage(1);
          }}
        />
      </div>

      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-500">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            {filtersActive ? (
              <EmptyState title="No processes match these filters" body="Try widening the filters." />
            ) : (
              <EmptyState title="No processes documented yet" body="Add the first process." />
            )}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[880px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Title</ResizableTh>
                  <ResizableTh>Product</ResizableTh>
                  <ResizableTh>Artisan</ResizableTh>
                  <ResizableTh>Steps</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((process) => (
                  <OpenableRow key={process.id} href={recordViewHref("process", process.id)!} onOpen={rememberPlace}>
                    <td className="px-4 py-3">
                      <RecordTitleLink href={recordViewHref("process", process.id)!} onOpen={rememberPlace}>
                        {process.name}
                      </RecordTitleLink>
                      {process.notes ? <div className="max-w-xs truncate text-xs text-ink-500">{process.notes}</div> : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{process.product?.productName ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{process.product?.artisanName ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{process.steps?.length ?? 0}</td>
                    <td className="px-4 py-3 text-ink-700">{formatDate(process.createdAt)}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={process.status} />
                    </td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <button
                          className={rowAction("edit")}
                          disabled={editLoadingId === process.id}
                          onClick={() => openEdit(process.id)}
                          type="button"
                        >
                          <Pencil className="h-3.5 w-3.5" aria-hidden />
                          {editLoadingId === process.id ? "Loading..." : "Update"}
                        </button>
                        <button className={rowAction("neutral")} onClick={() => setCollabId(process.id)} type="button">
                          <MessageSquare className="h-3.5 w-3.5" aria-hidden />
                          Discuss
                        </button>
                        {canDeleteRecords ? (
                          <button className={rowAction("danger")} onClick={() => remove(process.id)} type="button">
                            <Trash2 className="h-3.5 w-3.5" aria-hidden />
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
      <CollabDialog recordType="process" recordId={collabId} onClose={() => setCollabId(null)} />
    </>
  );
}

export default function ProcessesPage() {
  return (
    <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
      <ProcessesPageInner />
    </Suspense>
  );
}
