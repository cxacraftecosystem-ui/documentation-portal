"use client";

import { useEffect, useRef, useState } from "react";
import { MapPinned } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { Field, MultiNoteField, Select, TextArea, TextInput } from "@/components/FormControls";
import { DateRangeField } from "@/components/forms/DateRangeField";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { locationFromForm, requiredText, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { canManageWorkshops, hasRank, isAdmin } from "@/lib/permissions";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { Artisan, Craft, PageResult, RecordStatus, User, Workshop, WorkshopAssignment } from "@/lib/types";

// The API includes the workshop's linked crafts (not yet in the Workshop TS type); used to pre-fill
// the "Crafts covered" selection when editing.
type WorkshopWithCrafts = Workshop & { crafts?: Array<{ craftId?: string; craft?: { id: string } }> };

/** The artisan ids a workshop is already linked to, for pre-filling the picker on edit. */
function linkedArtisanIds(workshop: Workshop | null): string[] {
  return workshop?.artisans?.map((item) => item.artisan.id) ?? [];
}

/** The craft ids a workshop already covers. The API returns either a nested craft or a bare id. */
function linkedCraftIds(workshop: Workshop | null): string[] {
  return ((workshop as WorkshopWithCrafts | null)?.crafts ?? [])
    .map((item) => item.craft?.id ?? item.craftId)
    .filter((id): id is string => Boolean(id));
}

const statusOptions: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"];

/** Section id the workshop media batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "workshop-media";
const MEDIA_SECTION_LABEL = "Workshop media";

export default function WorkshopsPage() {
  return (
    <UploadsProvider>
      <WorkshopsPageBody />
      <UploadTray />
    </UploadsProvider>
  );
}

function WorkshopsPageBody() {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageWorkshops(user);
  const allowAssign = isAdmin(user);
  // Status policy (mirrors the backend): professor+ may pick any status (default APPROVED on
  // create); everyone below sees a locked Pending chip and the server forces/keeps the status.
  const canSetStatus = hasRank(user, "PROFESSOR");
  const [data, setData] = useState<PageResult<Workshop> | null>(null);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Workshop | null>(null);
  // Both link pickers are themed MultiSelectDropdowns, which are React state rather than form
  // controls, so the selections live here and are read straight out of state at submit time. They
  // are re-seeded in `resetForm` — the one place a different workshop is ever loaded into the form.
  const [artisanIds, setArtisanIds] = useState<string[]>([]);
  const [craftIds, setCraftIds] = useState<string[]>([]);
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
  // Assignment manager (admin only).
  const [assigning, setAssigning] = useState<Workshop | null>(null);
  const [researchers, setResearchers] = useState<User[]>([]);
  const [assignIds, setAssignIds] = useState<Set<string>>(new Set());
  const [assignBusy, setAssignBusy] = useState(false);

  async function openAssign(workshop: Workshop) {
    setAssigning(workshop);
    setError(null);
    try {
      const [assignments, users] = await Promise.all([
        apiFetch<WorkshopAssignment[]>(`/workshops/${workshop.id}/assignments`),
        // 100 is the server's cap (`pageSize: int = Query(20, ge=1, le=100)`); asking for 200 was a
        // 422, which left `researchers` empty and the dialog permanently showing "No users to assign".
        researchers.length ? Promise.resolve({ items: researchers }) : listResource<User>("/users", { pageSize: 100 })
      ]);
      if (!researchers.length) setResearchers((users as { items: User[] }).items ?? []);
      // GRANTED only. The endpoint returns every row on the workshop — pending requests, denials and
      // revocations too — so taking every userId pre-ticked people who had been refused or removed,
      // and saving the dialog (a whole-set PUT) silently granted them access again.
      setAssignIds(new Set(assignments.filter((a) => a.status === "GRANTED").map((a) => a.userId)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load assignments");
    }
  }

  async function saveAssignments() {
    if (!assigning) return;
    setAssignBusy(true);
    try {
      await apiFetch(`/workshops/${assigning.id}/assignments`, { method: "PUT", body: JSON.stringify({ userIds: Array.from(assignIds) }) });
      setAssigning(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save assignments");
    } finally {
      setAssignBusy(false);
    }
  }

  function toggleAssign(id: string) {
    setAssignIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function load() {
    try {
      setData(await listResource<Workshop>("/workshops", { search: applied || undefined, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load workshops");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied]);

  // The linked-artisan / craft pickers only need loading once.
  useEffect(() => {
    (async () => {
      try {
        const [artisanResult, craftResult] = await Promise.all([
          listResource<Artisan>("/artisans", { pageSize: 100 }),
          listResource<Craft>("/crafts", { pageSize: 100 })
        ]);
        setArtisans(artisanResult.items);
        setCrafts(craftResult.items);
      } catch {
        // The pickers degrade to empty lists; the workshop list still loads.
      }
    })();
  }, []);

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

  // Warn on hard navigation (close tab / reload) while the workshop form has unsaved edits.
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

  function resetForm(next: Workshop | null) {
    setEditing(next);
    setArtisanIds(linkedArtisanIds(next));
    setCraftIds(linkedCraftIds(next));
    setMediaFiles([]);
    setDirty(false);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    try {
      const payload = {
        title: requiredText(form, "title"),
        date: requiredText(form, "date"),
        startDate: requiredText(form, "startDate"),
        endDate: requiredText(form, "endDate"),
        place: requiredText(form, "place"),
        description: textValue(form, "description"),
        notes: textValue(form, "notes"),
        // Below professor the backend forces PENDING on create and drops status changes on update.
        status: canSetStatus ? requiredText(form, "status") : "PENDING",
        artisanIds,
        craftIds,
        location: locationFromForm(form)
      };
      const saved = await apiFetch<Workshop>(editing ? `/workshops/${editing.id}` : "/workshops", {
        method: editing ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      if (mediaFiles.length) {
        const { uploaded, failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "workshop",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.title}`,
          location: payload.location,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        // The uploaded files surface twice: as chips under this section and in the page-level tray.
        addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
        if (failed.length) {
          setError(
            `${failed.length} of ${mediaFiles.length} file(s) failed to upload: ${failed.map((item) => item.name).join(", ")}. ` +
              "The workshop was saved; re-open it to retry those files."
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
      setError(err instanceof Error ? err.message : "Unable to save workshop");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  async function remove(id: string) {
    if (!window.confirm("Delete this workshop?")) return;
    try {
      await apiFetch(`/workshops/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete workshop");
    }
  }

  const artisanOptions = artisans.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }));
  const craftOptions = crafts.map((craft) => ({
    value: craft.id,
    label: craft.place ? `${craft.name} · ${craft.place}` : craft.name
  }));

  // The API already returns workshops createdAt-descending; re-sorting keeps the guarantee local
  // so the list stays newest-first even if a caller ever changes the server ordering.
  const rows = data ? [...data.items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")) : [];

  return (
    <>
      <PageHeader
        title="Workshops"
        description="Create field workshop records, link artisans and store date, place, notes and GPS context."
        icon={<MapPinned className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {allowManage ? (
      <form
        ref={formRef}
        key={editing?.id ?? "new"}
        onSubmit={submit}
        onInput={() => setDirty(true)}
        onKeyDown={handleFormEnter}
        className="panel mb-5 grid gap-4 p-4"
      >
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="Workshop title" required>
            <TextInput name="title" required defaultValue={editing?.title ?? ""} />
          </Field>
          <Field label="Place" required>
            <TextInput name="place" required defaultValue={editing?.place ?? ""} />
          </Field>
          <div className="md:col-span-2">
            <DateRangeField start={editing?.startDate ?? editing?.date} end={editing?.endDate ?? editing?.date} />
          </div>
          <Field label="Status">
            {canSetStatus ? (
              <Select name="status" defaultValue={editing?.status ?? "APPROVED"} onChange={() => setDirty(true)}>
                {statusOptions.map((status) => (
                  <option key={status}>{status}</option>
                ))}
              </Select>
            ) : (
              <div className="py-1.5">
                <StatusBadge status="PENDING" />
              </div>
            )}
          </Field>
          <Field label="Description">
            <TextArea name="description" defaultValue={editing?.description ?? ""} />
          </Field>
          <MultiNoteField defaultValue={editing?.notes ?? ""} />
          {/* Both link pickers use the same themed multi-select as every other one in the app; the
              selections are React state, not FormData (see the note on `artisanIds` above). */}
          <Field label="Linked artisans">
            <MultiSelectDropdown
              values={artisanIds}
              onChange={(next) => {
                setArtisanIds(next);
                setDirty(true);
              }}
              options={artisanOptions}
              placeholder="Link the artisans who took part"
              emptyLabel="No artisans recorded yet"
              confirmLabel="Link artisans"
            />
          </Field>
          <Field label="Crafts covered">
            {crafts.length === 0 ? (
              <p className="rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm text-ink-muted">
                No crafts available yet. Create a craft first.
              </p>
            ) : (
              <MultiSelectDropdown
                values={craftIds}
                onChange={(next) => {
                  setCraftIds(next);
                  setDirty(true);
                }}
                options={craftOptions}
                placeholder="Pick the crafts this workshop covered"
                emptyLabel="No crafts available yet"
                confirmLabel="Add crafts"
              />
            )}
          </Field>
        </div>
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            setDirty(true);
          }}
          title="Workshop media"
          description="Attach workshop images, videos, audio notes, attendance references, and documents."
        />
        <UploadProgress progress={uploadProgress} sectionId={MEDIA_SECTION} label={MEDIA_SECTION_LABEL} />
        {/* Editing an existing workshop: everything already attached to it, with per-file delete. */}
        {editing ? <ExistingMedia linkedRecordType="workshop" linkedRecordId={editing.id} title="Previously uploaded workshop media" /> : null}
        <LocationFields />
        <div className="flex gap-2">
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : editing ? "Update workshop" : "Create workshop"}
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
          Browse workshops below. Ask the master admin for workshop creation access to add or edit workshops.
        </div>
      )}
      {/* Same provenance block the artisan/product/tool edit surfaces carry, for the workshop being
          edited. empty:hidden — FieldProvenance renders nothing without provenance access. */}
      {editing ? (
        <div className="mb-5 empty:hidden">
          <FieldProvenance extraMetadata={editing.extraMetadata} title="Workshop field contributions" />
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
          placeholder="Search workshops by title, place or description"
        />
      </div>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No workshops found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Workshop</ResizableTh>
                  <ResizableTh>Date</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Artisans</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((workshop) => (
                  <tr key={workshop.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{workshop.title}</div>
                      <div className="text-xs text-ink-500">{workshop.description ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {formatDateTime(workshop.startDate ?? workshop.date)}
                      {workshop.endDate ? <span className="block text-xs text-ink-500">to {formatDateTime(workshop.endDate)}</span> : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{workshop.place}</td>
                    <td className="px-4 py-3 text-ink-700">{workshop.artisans?.map((item) => item.artisan.name).join(", ") || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={workshop.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDate(workshop.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        {allowManage ? (
                          <button className={rowAction("edit")} onClick={() => guard(() => resetForm(workshop))}>
                            Edit
                          </button>
                        ) : null}
                        <button className={rowAction("neutral")} onClick={() => setCollabId(workshop.id)}>
                          Discuss
                        </button>
                        {allowAssign ? (
                          <button className={rowAction("neutral")} onClick={() => openAssign(workshop)}>
                            Assign
                          </button>
                        ) : null}
                        {adminMode ? (
                          <button className={rowAction("danger")} onClick={() => remove(workshop.id)}>
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

      {assigning ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setAssigning(null)}>
          <div className="panel w-full max-w-lg p-4" onClick={(e) => e.stopPropagation()}>
            <h2 className="font-display font-bold text-lg text-ink">Assign researchers</h2>
            <p className="mt-1 text-sm text-ink-muted">
              Only assigned researchers can create entries for <span className="font-medium">{assigning.title}</span>. Submissions outside the
              workshop dates are flagged for admin approval. Leave empty to keep the workshop open to everyone.
            </p>
            <div className="mt-3 grid max-h-72 gap-1 overflow-y-auto rounded-md border border-line-200 bg-field-50 p-2">
              {researchers.length === 0 ? (
                <p className="px-2 py-1 text-sm text-ink-muted">No users to assign.</p>
              ) : (
                researchers.map((r) => (
                  <label key={r.id} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                    <input type="checkbox" checked={assignIds.has(r.id)} onChange={() => toggleAssign(r.id)} />
                    <span className="min-w-0 flex-1 truncate text-sm text-ink">
                      {r.name} <span className="text-ink-muted">· {r.email}</span>
                    </span>
                    <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs text-ink-muted">{r.role}</span>
                  </label>
                ))
              )}
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button className="field-button-secondary" onClick={() => setAssigning(null)}>
                Cancel
              </button>
              <button className="field-button" disabled={assignBusy} onClick={saveAssignments}>
                {assignBusy ? "Saving…" : `Save (${assignIds.size})`}
              </button>
            </div>
          </div>
        </div>
      ) : null}
      <CollabDialog recordType="workshop" recordId={collabId} onClose={() => setCollabId(null)} />
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
