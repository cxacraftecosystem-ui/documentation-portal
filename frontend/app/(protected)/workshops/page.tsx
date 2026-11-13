"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { MapPinned } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { Field, MultiNoteField, Select } from "@/components/FormControls";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { DateRangeField } from "@/components/forms/DateRangeField";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { useListReturn } from "@/components/hooks/useListReturn";
import { CompletenessFilter } from "@/components/records/CompletenessFilter";
import { RowCompletenessRing } from "@/components/records/CompletenessRing";
import { LiveCompleteness } from "@/components/records/LiveCompleteness";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { recordViewHref } from "@/lib/recordView";
import { StatusBadge, statusLabel } from "@/components/StatusBadge";
import { WorkshopMappingPanel } from "@/components/settings/WorkshopMappingPanel";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { countNoun, formatDate, formatDateTime } from "@/lib/format";
import { parseCompletenessFilter, type CompletenessFilter as CompletenessValue } from "@/lib/completenessCore";
import { locationFromForm, requiredText, sameIdSet, useFocusField } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { workshopBody } from "@/lib/recordPayloads";
import { invalidateWorkshopList } from "@/lib/workshopList";
import { canManageWorkshops, hasRank, isAdmin } from "@/lib/permissions";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { Artisan, PageResult, RecordStatus, User, Workshop, WorkshopAssignment } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { ReviewNoteBanner } from "@/components/review/ReviewNoteBanner";

type WorkshopWithCrafts = Workshop & { crafts?: Array<{ craftId?: string; craft?: { id: string } }> };

const ARTISAN_PICKER_MAX_PAGES = 50;
const ARTISAN_PICKER_PARALLEL = 6;

function linkedArtisanIds(workshop: Workshop | null): string[] {
  return workshop?.artisans?.map((item) => item.artisan.id) ?? [];
}

function linkedCraftIds(workshop: Workshop | null): string[] {
  return ((workshop as WorkshopWithCrafts | null)?.crafts ?? [])
    .map((item) => item.craft?.id ?? item.craftId)
    .filter((id): id is string => Boolean(id));
}

const statusOptions: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"];

const MEDIA_SECTION = "workshop-media";
const MEDIA_SECTION_LABEL = "Workshop media";

const WORKSHOP_TYPE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "DESIGN_PROTOTYPE", label: "Design & Prototype Development Workshop" },
  { value: "OTHER", label: "Other workshop" }
];

export default function WorkshopsPage() {
  return (
    <UploadsProvider>
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <WorkshopsPageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

function WorkshopsPageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { canDeleteRecords } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageWorkshops(user);
  const allowAssign = isAdmin(user);
  const canSetStatus = hasRank(user, "PROFESSOR");
  const [data, setData] = useState<PageResult<Workshop> | null>(null);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [completeness, setCompleteness] = useState<CompletenessValue>("");
  const [editing, setEditing] = useState<Workshop | null>(null);
  const [title, setTitle] = useState("");
  const [place, setPlace] = useState("");
  const [formKey, setFormKey] = useState(0);
  const [artisanIds, setArtisanIds] = useState<string[]>([]);
  const [craftIds, setCraftIds] = useState<string[]>([]);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const router = useRouter();
  const [dirty, setDirty] = useState(false);
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const afterSaveRef = useRef<(() => void) | null>(null);
  const skipFirstDebounce = useRef(true);
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
        researchers.length ? Promise.resolve({ items: researchers }) : listResource<User>("/users", { pageSize: 100 })
      ]);
      if (!researchers.length) setResearchers((users as { items: User[] }).items ?? []);
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

  async function load() {
    try {
      setData(
        await listResource<Workshop>("/workshops", {
          search: applied || undefined,
          completeness: completeness || undefined,
          page,
          pageSize: 20
        })
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load workshops");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied, completeness]);

  const artisansRequested = useRef(false);
  useEffect(() => {
    if (!allowManage || artisansRequested.current) return;
    artisansRequested.current = true;
    (async () => {
      try {
        const first = await listResource<Artisan>("/artisans", { page: 1, pageSize: 100 });
        const lastPage = Math.min(first.pages || 1, ARTISAN_PICKER_MAX_PAGES);
        const all: Artisan[] = [...first.items];
        for (let start = 2; start <= lastPage; start += ARTISAN_PICKER_PARALLEL) {
          const pages = Array.from({ length: Math.min(ARTISAN_PICKER_PARALLEL, lastPage - start + 1) }, (_, index) => start + index);
          const results = await Promise.all(pages.map((page) => listResource<Artisan>("/artisans", { page, pageSize: 100 })));
          for (const result of results) all.push(...result.items);
        }
        setArtisans(all);
      } catch {
      }
    })();
  }, [allowManage]);

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

  const listReturn = useListReturn("workshops", { query: applied, page, completeness }, Boolean(data));
  useEffect(() => {
    const note = listReturn.restored;
    if (!note) return;
    if (note.query) {
      skipFirstDebounce.current = true;
      setQuery(note.query);
      setApplied(note.query);
    }
    setPage(note.page);
    setCompleteness(parseCompletenessFilter(note.completeness));
  }, [listReturn.restored]);
  const restoreListScroll = listReturn.restoreScroll;
  useEffect(() => restoreListScroll(Boolean(data)), [data, restoreListScroll]);
  const rememberPlace = () => listReturn.remember({ query: applied, page, completeness });

  useEffect(() => {
    if (!dirty) return;
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  useLeaveGuard(dirty, () => guard(() => router.back()));

  const openThroughGuard = (href: string) => guard(() => router.push(href));

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
    setTitle(next?.title ?? "");
    setPlace(next?.place ?? "");
    setFormKey((key) => key + 1);
  }

  const { loading: deepLinkLoading } = useEditDeepLink<Workshop>({
    endpoint: "/workshops",
    basePath: "/workshops",
    targetRef: formRef,
    onEdit: (record) => guard(() => resetForm(record)),
    onNew: () => guard(() => resetForm(null)),
    onError: setError,
    allowed: allowManage,
    errorMessage: "Unable to load that workshop"
  });

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    try {
      const title = requiredText(form, "title");
      const location = locationFromForm(form);
      const payload = workshopBody(form, {
        canSetStatus,
        artisanIds: !editing || !sameIdSet(artisanIds, linkedArtisanIds(editing)) ? artisanIds : undefined,
        craftIds: !editing || !sameIdSet(craftIds, linkedCraftIds(editing)) ? craftIds : undefined
      });
      const outcome = await saveOrQueue<Workshop>({
        label: `Workshop · ${title || "Untitled"}`,
        endpoint: editing ? `/workshops/${editing.id}` : "/workshops",
        method: editing ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "workshop",
            caption: `Field media for ${title || "workshop"}`,
            location
          }
        ]
      });
      if (outcome.queued) {
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      invalidateWorkshopList();
      if (mediaFiles.length) {
        const { uploaded, failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "workshop",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.title}`,
          location,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
        if (failed.length) {
          setError(
            `Saved, but ${failed.length} of ${countNoun(mediaFiles.length, "file")} didn’t upload: ${failed.map((item) => item.name).join(", ")}. Reopen to retry.`
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
    const ok = await confirm(
      deleteConfirm(
        "Delete this workshop?",
        "This can’t be undone. Its records are kept.",
        undefined
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/workshops/${id}`, { method: "DELETE" });
      invalidateWorkshopList();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete workshop");
    }
  }

  const artisanOptions = [
    ...artisans,
    ...(editing?.artisans ?? []).map((item) => item.artisan).filter((linked) => !artisans.some((a) => a.id === linked.id))
  ].map((artisan) => ({ value: artisan.id, label: [artisan.name, artisan.place].filter(Boolean).join(" · ") }));

  const rows = data ? [...data.items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")) : [];
  useFocusField(Boolean(editing) && !deepLinkLoading);
  const liveCounts = useMemo(
    () => ({
      artisans: artisanIds.length,
      crafts: craftIds.length,
      photos: mediaFiles.filter((file) => file.type.startsWith("image/")).length
    }),
    [artisanIds, craftIds, mediaFiles]
  );

  return (
    <>
      <PageHeader
        title="Workshops"
        description="Field workshops, their dates, places and artisans."
        icon={<MapPinned className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">
          Loading the workshop you asked to edit...
        </div>
      ) : null}

      {allowAssign ? (
        <div className="mb-5">
          <WorkshopMappingPanel />
        </div>
      ) : null}

      {allowManage ? (
      <form
        ref={formRef}
        key={`${editing?.id ?? "new"}-${formKey}`}
        onSubmit={submit}
        onInput={() => setDirty(true)}
        onKeyDown={handleFormEnter}
        className="panel mb-5 grid scroll-mt-28 gap-4 p-4"
      >
        {editing ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
              Editing: {editing.title}
            </span>
          </div>
        ) : null}
        {editing ? <ReviewNoteBanner recordType="workshop" recordId={editing.id} status={editing.status} /> : null}
        <LiveCompleteness kind="workshop" formRef={formRef} stored={editing} counts={liveCounts} />
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
          <DictatedTextInput
            name="title"
            label="Workshop title"
            required
            titleCased
            explainWhenUnavailable={false}
            value={title}
            onChange={(next) => {
              setTitle(next);
              setDirty(true);
            }}
          />
          <DictatedTextInput
            name="place"
            label="Place"
            required
            titleCased
            explainWhenUnavailable={false}
            value={place}
            onChange={(next) => {
              setPlace(next);
              setDirty(true);
            }}
          />
          <div className="md:col-span-2">
            <DateRangeField start={editing?.startDate ?? editing?.date} end={editing?.endDate ?? editing?.date} />
          </div>
          <Field label="Workshop kind">
            <Select
              name="workshopType"
              defaultValue={editing?.workshopType ?? "OTHER"}
              onChange={() => setDirty(true)}
            >
              {WORKSHOP_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Status">
            {canSetStatus ? (
              <Select name="status" defaultValue={editing?.status ?? "APPROVED"} onChange={() => setDirty(true)}>
                {statusOptions.map((status) => (
                  <option key={status} value={status}>{statusLabel(status)}</option>
                ))}
              </Select>
            ) : (
              <div className="py-1.5">
                <StatusBadge status="PENDING" />
              </div>
            )}
          </Field>
          <DictatedTextArea
            name="description"
            label="Description"
            defaultValue={editing?.description ?? ""}
            explainWhenUnavailable={false}
            onDirty={() => setDirty(true)}
          />
          <MultiNoteField defaultValue={editing?.notes ?? ""} />
          <Field label="Linked artisans" fieldKey="artisans">
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
          <div data-field="crafts" className="min-w-0">
            <FieldBlock label="Crafts covered">
              <CraftSelect
                multiple
                ariaLabel="Crafts covered"
                value={craftIds}
                placeholder="Pick the crafts this workshop covered"
                onChange={(next) => {
                  setCraftIds(next);
                  setDirty(true);
                }}
              />
            </FieldBlock>
          </div>
        </div>
        <div data-field="photo">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              setDirty(true);
            }}
            title="Workshop media"
            description="Photos, video, audio and documents."
          />
        </div>
        <UploadProgress progress={uploadProgress} sectionId={MEDIA_SECTION} label={MEDIA_SECTION_LABEL} />
        {editing ? <ExistingMedia linkedRecordType="workshop" linkedRecordId={editing.id} title="Previously uploaded workshop media" /> : null}
        <LocationFields
          initial={editing ? ((editing as Workshop & { location?: LocationInitialValues | null }).location ?? null) : undefined}
          onDirty={() => setDirty(true)}
        />
        <ButtonRow>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : editing ? "Update workshop" : "Create workshop"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => guard(() => resetForm(null))}>
              Cancel edit
            </button>
          ) : null}
        </ButtonRow>
      </form>
      ) : (
        <div className="panel mb-5 p-4 text-sm text-ink-muted">
          Ask an admin for access to add workshops.
        </div>
      )}
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
      <div className="mb-3">
        <CompletenessFilter
          value={completeness}
          onChange={(next) => {
            setCompleteness(next);
            setPage(1);
          }}
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
                  <OpenableRow key={workshop.id} href={recordViewHref("workshop", workshop.id)!} onOpen={rememberPlace} navigate={openThroughGuard}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <RowCompletenessRing block={workshop.completeness} />
                        <RecordTitleLink href={recordViewHref("workshop", workshop.id)!} onOpen={rememberPlace} navigate={openThroughGuard}>
                          {workshop.title}
                        </RecordTitleLink>
                      </div>
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
                            Update
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
                        {canDeleteRecords ? (
                          <button className={rowAction("danger")} onClick={() => remove(workshop.id)}>
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

      {assigning ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setAssigning(null)}>
          <div className="panel w-full max-w-lg p-4" onClick={(e) => e.stopPropagation()}>
            <h2 className="font-display font-bold text-lg text-ink">Assign researchers</h2>
            <p className="mt-1 text-sm text-ink-muted">
              Only these researchers can add to <span className="font-medium">{assigning.title}</span>. Leave empty for everyone.
            </p>
            <div className="mt-3">
              {researchers.length === 0 ? (
                <p className="px-2 py-1 text-sm text-ink-muted">No users to assign.</p>
              ) : (
                <SearchableMultiSelect
                  values={Array.from(assignIds)}
                  onChange={(next) => setAssignIds(new Set(next))}
                  options={researchers.map((r) => ({ value: r.id, label: `${r.name} · ${r.email} · ${r.role}` }))}
                  placeholder="Open to everyone"
                  ariaLabel="Researchers to assign"
                  confirmOnSelect={false}
                  searchable
                />
              )}
            </div>
            <ButtonRow align="end" className="mt-4">
              <button className="field-button-secondary" onClick={() => setAssigning(null)}>
                Cancel
              </button>
              <button className="field-button" disabled={assignBusy} onClick={saveAssignments}>
                {assignBusy ? "Saving…" : `Save (${assignIds.size})`}
              </button>
            </ButtonRow>
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
