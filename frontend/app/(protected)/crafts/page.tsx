"use client";

import { useEffect, useRef, useState } from "react";
import { Landmark } from "lucide-react";

import { CollabPanel } from "@/components/CollabPanel";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { requiredText, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { canManageCrafts } from "@/lib/permissions";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { Craft, PageResult } from "@/lib/types";

/** Section id the craft media batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "craft-media";
const MEDIA_SECTION_LABEL = "Craft media";

export default function CraftsPage() {
  return (
    <UploadsProvider>
      <CraftsPageBody />
      <UploadTray />
    </UploadsProvider>
  );
}

function CraftsPageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageCrafts(user);
  const [data, setData] = useState<PageResult<Craft> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Craft | null>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  // Unsaved-changes guard: dirty is set by any form input / media change; confirmAction holds the
  // navigation the user asked for while the dialog decides its fate.
  const [dirty, setDirty] = useState(false);
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const afterSaveRef = useRef<(() => void) | null>(null);
  const skipFirstDebounce = useRef(true);
  // The workshop this craft was documented at. One inline form edits every craft here, so `resetKey`
  // re-seeds the picker whenever a different record is loaded into it (see forms/WorkshopSelect).
  const workshop = useWorkshopSelection({
    initialWorkshopId: editing?.workshopId,
    isEdit: Boolean(editing),
    resetKey: editing?.id ?? null
  });

  async function load() {
    try {
      setData(await listResource<Craft>("/crafts", { search: applied || undefined, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load crafts");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied]);

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

  // Warn on hard navigation (close tab / reload) while the craft form has unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  /** Run `action` now, or park it behind the unsaved-changes dialog when the form is dirty. */
  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  function resetForm(next: Craft | null) {
    setEditing(next);
    setMediaFiles([]);
    setDirty(false);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) {
      afterSaveRef.current = null;
      return;
    }
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const description = [textValue(form, "description"), exifRemark].filter(Boolean).join("\n\n") || null;
      // extraMetadata stays programmatic (EXIF etc.) — the raw JSON textarea is gone for good.
      const payload: Record<string, unknown> = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        category: textValue(form, "category"),
        place: textValue(form, "place"),
        workshopId: workshop.workshopId || null,
        description
      };
      if (exifItems.length) payload.extraMetadata = { mediaExif: exifItems };
      const saved = await apiFetch<Craft>(editing ? `/crafts/${editing.id}` : "/crafts", {
        method: editing ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      if (mediaFiles.length) {
        const { uploaded, failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "craft",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.name}`,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        // The uploaded files surface twice: as chips under this section and in the page-level tray.
        addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
        if (failed.length) {
          setError(
            `${failed.length} of ${mediaFiles.length} file(s) failed to upload: ${failed.map((item) => item.name).join(", ")}. ` +
              "The craft was saved; re-open it to retry those files."
          );
          setSaving(false);
          return;
        }
      }
      resetForm(null);
      setConfirmAction(null);
      formElement.reset();
      load();
      const after = afterSaveRef.current;
      afterSaveRef.current = null;
      after?.();
    } catch (err) {
      afterSaveRef.current = null;
      setConfirmAction(null);
      setError(err instanceof Error ? err.message : "Unable to save craft");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this craft?",
        "This permanently deletes the craft. This action cannot be undone.",
        "Artisans, products and tools that reference it keep their craft name as text — they are not deleted and they are not left blank."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/crafts/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete craft");
    }
  }

  // Most recent first, regardless of the API's name ordering.
  const rows = data ? [...data.items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")) : [];

  return (
    <>
      <PageHeader title="Crafts" description="Maintain craft vocabulary used to link artisans, products and tools." icon={<Landmark className="h-5 w-5" aria-hidden />} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {allowManage ? (
      <form
        ref={formRef}
        key={editing?.id ?? "new"}
        onSubmit={submit}
        onInput={() => setDirty(true)}
        onKeyDown={handleFormEnter}
        className="panel mb-5 grid gap-3 p-4 md:grid-cols-2 lg:grid-cols-4"
      >
        {/* The workshop leads every other dropdown: it is the context the record belongs to. */}
        <WorkshopSelect state={workshop} onDirty={() => setDirty(true)} saving={saving} />
        <Field label="Craft name" required>
          <TextInput name="name" required defaultValue={editing?.name ?? ""} />
        </Field>
        <Field label="Local name">
          <TextInput name="localName" defaultValue={editing?.localName ?? ""} />
        </Field>
        <Field label="Category">
          <TextInput name="category" defaultValue={editing?.category ?? ""} />
        </Field>
        <Field label="Place">
          <TextInput name="place" defaultValue={editing?.place ?? ""} />
        </Field>
        <div className="md:col-span-2 lg:col-span-4">
          <Field label="Description">
            <TextArea name="description" defaultValue={editing?.description ?? ""} />
          </Field>
        </div>
        <div className="md:col-span-2 lg:col-span-4">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              setDirty(true);
            }}
            title="Craft media"
            description="Attach or capture craft reference images, audio notes, video, and documents."
          />
        </div>
        <UploadProgress
          progress={uploadProgress}
          sectionId={MEDIA_SECTION}
          label={MEDIA_SECTION_LABEL}
          className="md:col-span-2 lg:col-span-4"
        />
        {/* Editing an existing craft: everything already attached to it, with playback and per-file
            delete — the same surface the artisan/product/tool/workshop forms carry. */}
        {editing ? (
          <div className="md:col-span-2 lg:col-span-4">
            <ExistingMedia linkedRecordType="craft" linkedRecordId={editing.id} title="Previously uploaded craft media" />
          </div>
        ) : null}
        <div className="flex gap-2 md:col-span-2 lg:col-span-4">
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : editing ? "Update craft" : "Create craft"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => guard(() => resetForm(null))}>
              Cancel edit
            </button>
          ) : null}
        </div>
      </form>
      ) : (
        <div className="panel mb-5 p-4 text-sm text-ink-muted">
          Browse the craft vocabulary below. Ask the master admin for craft creation access to add or edit crafts.
        </div>
      )}
      {/* Same provenance block the artisan/product/tool/workshop edit surfaces carry, for the craft
          being edited. empty:hidden — FieldProvenance renders nothing without provenance access. */}
      {editing ? (
        <div className="mb-5 empty:hidden">
          <FieldProvenance extraMetadata={editing.extraMetadata} title="Craft field contributions" />
        </div>
      ) : null}
      <div className="mb-4">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search crafts, categories or descriptions"
        />
      </div>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No crafts found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Name</ResizableTh>
                  <ResizableTh>Local name</ResizableTh>
                  <ResizableTh>Category</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Description</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((craft) => (
                  <tr key={craft.id}>
                    <td className="px-4 py-3 font-medium text-ink-900">{craft.name}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.localName ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.category ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.place ?? "-"}</td>
                    <td className="max-w-md px-4 py-3 text-ink-700">{craft.description ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.createdAt ? formatDate(craft.createdAt) : "-"}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        {allowManage ? (
                          <button className={rowAction("edit")} onClick={() => guard(() => resetForm(craft))}>
                            Edit
                          </button>
                        ) : null}
                        <button className={rowAction("neutral")} onClick={() => setCollabId(craft.id)}>
                          Discuss
                        </button>
                        {adminMode ? (
                          <button className={rowAction("danger")} onClick={() => remove(craft.id)}>
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
      {collabId ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setCollabId(null)}>
          <div className="panel max-h-[85vh] w-full max-w-lg overflow-y-auto p-4" onClick={(e) => e.stopPropagation()}>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="font-display font-bold text-lg text-ink">Comments &amp; edit history</h2>
              <button className="text-sm text-ink-muted" onClick={() => setCollabId(null)}>
                Close
              </button>
            </div>
            <CollabPanel recordType="craft" recordId={collabId} />
          </div>
        </div>
      ) : null}
      <UnsavedChangesDialog
        open={confirmAction !== null}
        saving={saving}
        onKeepEditing={() => setConfirmAction(null)}
        onDiscard={() => {
          const action = confirmAction;
          setConfirmAction(null);
          setDirty(false);
          action?.();
        }}
        onSave={() => {
          afterSaveRef.current = confirmAction;
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}
