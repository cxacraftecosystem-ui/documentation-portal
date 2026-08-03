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
import { ProcessForm, type ProcessRecord } from "@/components/forms/ProcessForm";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { canCreateRecords } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";

function ProcessesPageInner() {
  const confirm = useConfirm();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { adminMode } = useAdminView();
  const { user } = useAuth();
  /**
   * Documenting a process needs `require_record_creator`, the same as an artisan, product or tool.
   * Those three sit behind ROUTE_GUARDS on /artisans/new and friends; this form is a QUERY parameter
   * on a list route, and ROUTE_GUARDS matches the pathname alone, so the central table could never
   * see it — a crowdsource volunteer filled the whole form in and met the 403 at Save. The gate is
   * therefore here, in the only place that can read `?new=1`.
   */
  const creator = canCreateRecords(user);
  const isNew = searchParams.get("new") === "1" && creator;

  const [data, setData] = useState<PageResult<ProcessRecord> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);

  // Editing loads the full hydrated record (steps + media) into the form.
  const [editing, setEditing] = useState<ProcessRecord | null>(null);
  const [editLoadingId, setEditLoadingId] = useState<string | null>(null);

  // Drop the parameter as well as the form, so a refresh or a shared link does not keep asking for a
  // page this account cannot have. The list underneath is open to everyone, which is where they land.
  useEffect(() => {
    if (!creator && searchParams.get("new") === "1") router.replace("/processes");
  }, [creator, router, searchParams]);
  const [collabId, setCollabId] = useState<string | null>(null);

  // Cascading funnel filters: Workshop -> Craft -> Artisan, the shared component every list uses.
  // /processes accepts workshopId, craftId and artisanId directly (craft/artisan resolve through
  // the process's parent product), so the funnel filters server-side like products and tools.
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
          page: pageToLoad,
          pageSize: 20
        })
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load processes");
    }
  }

  // Waits for the funnel's initial onChange (default = the most recent workshop) before the first fetch.
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

  function onFunnelChange(next: FunnelValue, _workshop: FunnelWorkshop | null) {
    setFunnel(next);
    setPage(1);
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

  /**
   * `/processes?edit=<id>` — the link the dashboard's Recent submissions rows and the View Data
   * browser have always produced for a process. Nothing on this page read it, so both landed on the
   * plain list and the researcher had to find the row again themselves.
   *
   * `onNew` is deliberately NOT passed: `?new=1` is a render mode this page derives on every render
   * (`isNew` above), not a one-shot intent, and a hook that consumed and stripped it would close the
   * create form the moment it opened. No `targetRef` either — the edit view REPLACES the list, so
   * the top of the document is the form, which is where `openEdit` already scrolls.
   */
  const { loading: deepLinkLoading } = useEditDeepLink<ProcessRecord>({
    endpoint: "/processes",
    basePath: "/processes",
    onEdit: setEditing,
    onError: setError,
    // PATCH /processes/{id} is gated by `get_current_user` and decides field by field inside
    // `guard_record_edit`, exactly like the row Edit button below, which is offered to everyone.
    allowed: true,
    errorMessage: "Unable to load this process"
  });

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this process record?",
        "This permanently deletes the record. This action cannot be undone.",
        "Every stage, its photos and its videos are deleted along with the process."
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
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
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

  // /processes?new=1 — ONLY the create form, no list.
  if (isNew) {
    return (
      <>
        <PageHeader
          title="Document process"
          description="Step-by-step documentation of how a product is made, with each step in order."
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

  // Edit view: the record loaded into the same form, saved via PATCH. The form carries the process's
  // own attached media (pre-process clips and per-step captures) with per-file delete.
  if (editing) {
    return (
      <>
        <PageHeader
          title="Edit process"
          description="Update the process record; existing step media is preserved unless you remove it."
          icon={<GitBranch className="h-5 w-5" aria-hidden />}
        />
        {banners}
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

  // /processes — list/update view only.
  return (
    <>
      <PageHeader
        title="Document process"
        description="Step-by-step documentation of how a product is made, with each step in order."
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
      <FunnelFilters value={funnel} onChange={onFunnelChange} showArtisan />

      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-500">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            {filtersActive ? (
              <EmptyState title="No processes match these filters" body="Widen the workshop, craft, or artisan filters above to see more records." />
            ) : (
              <EmptyState title="No processes documented yet" body="Document how a product is made, step by step, from the New process form." />
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
                  <tr key={process.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{process.name}</div>
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
                          {editLoadingId === process.id ? "Loading..." : "Edit"}
                        </button>
                        <button className={rowAction("neutral")} onClick={() => setCollabId(process.id)} type="button">
                          <MessageSquare className="h-3.5 w-3.5" aria-hidden />
                          Discuss
                        </button>
                        {adminMode ? (
                          <button className={rowAction("danger")} onClick={() => remove(process.id)} type="button">
                            <Trash2 className="h-3.5 w-3.5" aria-hidden />
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
      <CollabDialog recordType="process" recordId={collabId} onClose={() => setCollabId(null)} />
    </>
  );
}

export default function ProcessesPage() {
  // Next 16: useSearchParams must sit inside a Suspense boundary.
  return (
    <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
      <ProcessesPageInner />
    </Suspense>
  );
}
