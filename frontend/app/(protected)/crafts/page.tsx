"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Landmark, Plus } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { RecordSwitcher } from "@/components/forms/RecordSwitcher";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
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
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { recordViewHref } from "@/lib/recordView";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { countNoun } from "@/lib/format";
import { requiredText, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { canManageCrafts } from "@/lib/permissions";
import { UnmappedCraftsPanel } from "@/components/crafts/UnmappedCraftsPanel";
import { CraftReviewQueue } from "@/components/crafts/CraftReviewQueue";
import { CraftCategoriesTab } from "@/components/crafts/CraftCategoriesTab";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { CraftSubCategorySelect } from "@/components/crafts/CraftTaxonomyFields";
import { CRAFT_STATE_OPTIONS, GI_LABEL, canAdministerCraftList } from "@/components/crafts/craftAdmin";
import {
  CRAFT_OFFLINE_FIRST_RUN,
  craftIndex,
  invalidateCraftCatalogue,
  searchCraftCatalogue,
  useCraftCatalogue,
  type GiStatus
} from "@/lib/craftCatalogue";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { Craft } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import Link from "next/link";
import { UserPlus } from "lucide-react";
import { carryContextToParams } from "@/lib/carryContext";

const MEDIA_SECTION = "craft-media";
const MEDIA_SECTION_LABEL = "Craft media";

type CraftsTab = "crafts" | "categories" | "review";
const CRAFTS_TABS: { id: CraftsTab; label: string }[] = [
  { id: "crafts", label: "Crafts" },
  { id: "categories", label: "Categories" },
  { id: "review", label: "Needs review" }
];
const CRAFTS_PAGE_SIZE = 50;

export default function CraftsPage() {
  return (
    <UploadsProvider>
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <CraftsPageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

function CraftsPageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode, canDeleteRecords } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageCrafts(user);
  const contentAdmin = canAdministerCraftList(user);
  const searchParams = useSearchParams();
  const [tab, setTab] = useState<CraftsTab>(() => {
    const asked = searchParams.get("tab");
    return asked === "categories" || (asked === "review" && contentAdmin) ? asked : "crafts";
  });
  const [formOpen, setFormOpen] = useState(false);
  const [filterCategory, setFilterCategory] = useState("");
  const [filterSub, setFilterSub] = useState("");
  const [filterGi, setFilterGi] = useState("");
  const [filterState, setFilterState] = useState("");
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Craft | null>(null);
  const [name, setName] = useState("");
  const [localName, setLocalName] = useState("");
  const [subCategoryId, setSubCategoryId] = useState("");
  const [stateOfOrigin, setStateOfOrigin] = useState<string[]>([]);
  const [stateOfPractice, setStateOfPractice] = useState<string[]>([]);
  const [giStatus, setGiStatus] = useState<GiStatus>("NON_GI");
  const [formKey, setFormKey] = useState(0);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const router = useRouter();
  const [dirty, setDirty] = useState(false);
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const [savedCraft, setSavedCraft] = useState<Craft | null>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const afterSaveRef = useRef<(() => void) | null>(null);
  const skipFirstDebounce = useRef(true);
  const workshop = useWorkshopSelection({
    initialWorkshopId: editing?.workshopId,
    isEdit: Boolean(editing),
    resetKey: editing?.id ?? null
  });

  const craftCatalogue = useCraftCatalogue();
  const catalogue = craftCatalogue.catalogue;
  const refreshCatalogue = craftCatalogue.reload;
  const [counts, setCounts] = useState<Map<string, number> | null>(null);
  const loadCounts = useCallback(async () => {
    try {
      const page = await apiFetch<{ items?: { id: string; referenceCount?: number }[] }>("/crafts?pageSize=500");
      setCounts(new Map((page.items ?? []).map((craft) => [craft.id, craft.referenceCount ?? 0])));
    } catch {
      setCounts(null);
    }
  }, []);
  useEffect(() => {
    void loadCounts();
  }, [loadCounts]);

  function load() {
    invalidateCraftCatalogue();
    refreshCatalogue();
    void loadCounts();
  }

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

  const categoryOptions = useMemo(
    () => (catalogue?.categories ?? []).map((category) => ({ value: category.id, label: category.name })),
    [catalogue]
  );
  const subOptionsFor = useCallback(
    (category: string) =>
      (catalogue?.categories ?? [])
        .filter((row) => !category || row.id === category)
        .flatMap((row) => row.subCategories.map((sub) => ({ value: sub.id, label: category ? sub.name : `${row.name} › ${sub.name}` }))),
    [catalogue]
  );
  const stateOptions = useMemo(() => CRAFT_STATE_OPTIONS.map((state) => ({ value: state, label: state })), []);
  const pathOf = useCallback(
    (subCategoryId: string | null) => {
      if (!catalogue || !subCategoryId) return null;
      const index = craftIndex(catalogue);
      const sub = index.subCategoryById.get(subCategoryId);
      return sub ? { sub: sub.name, category: index.categoryById.get(sub.categoryId)?.name ?? "" } : null;
    },
    [catalogue]
  );

  const filtered = useMemo(() => {
    const subIds = filterCategory
      ? new Set((catalogue?.categories.find((row) => row.id === filterCategory)?.subCategories ?? []).map((sub) => sub.id))
      : null;
    return (catalogue ? searchCraftCatalogue(catalogue, applied) : [])
      .filter(
        (craft) =>
          (!subIds || subIds.has(craft.subCategoryId ?? "")) &&
          (!filterSub || craft.subCategoryId === filterSub) &&
          (!filterGi || craft.giStatus === filterGi) &&
          (!filterState || craft.stateOfOrigin.includes(filterState) || craft.stateOfPractice.includes(filterState))
      );
  }, [catalogue, applied, filterCategory, filterSub, filterGi, filterState]);
  const pages = Math.max(1, Math.ceil(filtered.length / CRAFTS_PAGE_SIZE));
  const shownPage = Math.min(page, pages);
  const rows = filtered.slice((shownPage - 1) * CRAFTS_PAGE_SIZE, shownPage * CRAFTS_PAGE_SIZE);

  const askedName = searchParams.get("new");
  useEffect(() => {
    if (!askedName || askedName === "1" || !allowManage) return;
    setTab("crafts");
    guard(() => {
      resetForm(null);
      setName(askedName);
      setFormOpen(true);
      requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start" }));
    });
    const next = new URLSearchParams(searchParams.toString());
    next.delete("new");
    const rest = next.toString();
    router.replace(rest ? `/crafts?${rest}` : "/crafts", { scroll: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [askedName, allowManage]);

  async function openForEdit(id: string) {
    try {
      const record = await apiFetch<Craft>(`/crafts/${id}`);
      guard(() => resetForm(record));
      requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start" }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load that craft");
    }
  }

  const listReturn = useListReturn("crafts", { query: applied, page }, Boolean(catalogue));
  useEffect(() => {
    const note = listReturn.restored;
    if (!note) return;
    if (note.query) {
      skipFirstDebounce.current = true;
      setQuery(note.query);
      setApplied(note.query);
    }
    setPage(note.page);
  }, [listReturn.restored]);
  const restoreListScroll = listReturn.restoreScroll;
  useEffect(() => restoreListScroll(Boolean(catalogue)), [catalogue, restoreListScroll]);
  const rememberPlace = () => listReturn.remember({ query: applied, page });

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

  function resetForm(next: Craft | null) {
    setEditing(next);
    setMediaFiles([]);
    setDirty(false);
    setName(next?.name ?? "");
    setLocalName(next?.localName ?? "");
    setSubCategoryId(next?.subCategoryId ?? "");
    setStateOfOrigin(next?.stateOfOrigin ?? []);
    setStateOfPractice(next?.stateOfPractice ?? []);
    setGiStatus(next?.giStatus ?? "NON_GI");
    setFormOpen(Boolean(next));
    setFormKey((key) => key + 1);
  }

  const { loading: deepLinkLoading } = useEditDeepLink<Craft>({
    endpoint: "/crafts",
    basePath: "/crafts",
    targetRef: formRef,
    onEdit: (record) => guard(() => resetForm(record)),
    onNew: () =>
      guard(() => {
        resetForm(null);
        setFormOpen(true);
      }),
    onError: setError,
    allowed: allowManage,
    errorMessage: "Unable to load that craft"
  });

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    if (!(await workshop.confirmSubmission())) {
      afterSaveRef.current = null;
      return;
    }
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const description = [textValue(form, "description"), exifRemark].filter(Boolean).join("\n\n") || null;
      const payload: Record<string, unknown> = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        subCategoryId: subCategoryId || null,
        stateOfOrigin,
        stateOfPractice,
        giStatus,
        workshopId: workshop.workshopId || null,
        description
      };
      if (exifItems.length) payload.extraMetadata = { mediaExif: exifItems };
      const outcome = await saveOrQueue<Craft>({
        label: `Craft · ${payload.name || "Untitled"}`,
        endpoint: editing ? `/crafts/${editing.id}` : "/crafts",
        method: editing ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "craft",
            caption: `Field media for ${payload.name || "craft"}`,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      if (outcome.queued) {
        resetForm(null);
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
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
        addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
        if (failed.length) {
          setError(
            `${failed.length} of ${countNoun(mediaFiles.length, "file")} failed to upload: ${failed.map((item) => item.name).join(", ")}. ` +
              "The craft was saved; re-open it to retry those files."
          );
          setSaving(false);
          return;
        }
      }
      setSavedCraft(editing ? null : saved);
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
    const ok = await confirm(deleteConfirm("Delete this craft?", "This can’t be undone.", undefined));
    if (!ok) return;
    try {
      await apiFetch(`/crafts/${id}`, { method: "DELETE" });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete craft");
    }
  }

  return (
    <>
      <PageHeader
        title="Crafts"
        description={
          catalogue
            ? `${countNoun(catalogue.crafts.length, "craft")} in ${countNoun(catalogue.categories.length, "category", "categories")}.`
            : "The master list of crafts."
        }
        icon={<Landmark className="h-5 w-5" aria-hidden />}
        actions={
          allowManage && tab === "crafts" && !formOpen && !editing ? (
            <button type="button" className="field-button" onClick={() => guard(() => setFormOpen(true))}>
              <Plus className="h-4 w-4" aria-hidden />
              Add craft
            </button>
          ) : null
        }
      />
      <div role="tablist" aria-label="Crafts views" className="mb-5 flex flex-wrap gap-2">
        {CRAFTS_TABS.filter((entry) => entry.id !== "review" || contentAdmin).map((entry) => (
          <button
            key={entry.id}
            type="button"
            role="tab"
            aria-selected={tab === entry.id}
            className={
              tab === entry.id
                ? "rounded-full bg-purple-700 px-4 py-1.5 text-sm font-medium text-white pointer-coarse:min-h-11"
                : "rounded-full border border-line-200 bg-card px-4 py-1.5 text-sm font-medium text-ink-700 hover:border-purple-300 pointer-coarse:min-h-11"
            }
            onClick={() => guard(() => setTab(entry.id))}
          >
            {entry.label}
          </button>
        ))}
      </div>
      {tab === "categories" && catalogue ? (
        <CraftCategoriesTab catalogue={catalogue} canEdit={contentAdmin} canDelete={canDeleteRecords} onChanged={load} />
      ) : tab === "categories" ? (
        <div className="panel p-4 text-sm text-ink-500">Loading…</div>
      ) : null}
      {tab === "review" && contentAdmin ? (
        <div className="grid gap-5">
          {catalogue ? <CraftReviewQueue catalogue={catalogue} onChanged={load} /> : null}
          {adminMode ? <UnmappedCraftsPanel /> : null}
        </div>
      ) : null}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {savedCraft ? (
        <div className="panel mb-5 flex flex-wrap items-center justify-between gap-3 p-4">
          <p className="text-sm font-medium text-ink">Saved &ldquo;{savedCraft.name}&rdquo;.</p>
          <ButtonRow align="end">
            <Link
              className="field-button"
              href={`/artisans/new?${carryContextToParams({
                craftId: savedCraft.id,
                craftName: savedCraft.name,
                workshopId: savedCraft.workshopId ?? null
              }).toString()}`}
            >
              <UserPlus className="h-4 w-4" aria-hidden />
              Add an artisan
            </Link>
            <button type="button" className="field-button-secondary" onClick={() => setSavedCraft(null)}>
              Done
            </button>
          </ButtonRow>
        </div>
      ) : null}
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">
          Loading the craft you asked to edit...
        </div>
      ) : null}
      {allowManage && editing ? (
        <RecordSwitcher
          kind="craft"
          currentId={editing.id}
          currentWorkshopId={editing.workshopId}
          className="panel mb-5 grid gap-3 p-4 md:grid-cols-2"
          onNavigate={(href) => guard(() => router.push(href))}
        />
      ) : null}
      {tab !== "crafts" ? null : allowManage && (formOpen || editing) ? (
      <form
        ref={formRef}
        key={`${editing?.id ?? "new"}-${formKey}`}
        onSubmit={submit}
        onInput={() => setDirty(true)}
        onKeyDown={handleFormEnter}
        className="panel mb-5 grid scroll-mt-28 gap-3 p-4 md:grid-cols-2 lg:grid-cols-4"
      >
        {editing ? (
          <div className="flex flex-wrap items-center gap-2 md:col-span-2 lg:col-span-4">
            <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
              Editing: {editing.name}
            </span>
          </div>
        ) : null}
        <WorkshopSelect state={workshop} onDirty={() => setDirty(true)} saving={saving} />
        <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
        <DictatedTextInput
          name="name"
          label="Craft name"
          required
          titleCased
          explainWhenUnavailable={false}
          value={name}
          onChange={(next) => {
            setName(next);
            setDirty(true);
          }}
        />
        <DictatedTextInput
          name="localName"
          label="Local name"
          explainWhenUnavailable={false}
          value={localName}
          onChange={(next) => {
            setLocalName(next);
            setDirty(true);
          }}
        />
        <div className="contents" data-field="subCategory">
          {catalogue ? (
            <CraftSubCategorySelect
              catalogue={catalogue}
              name="subCategoryId"
              required
              value={subCategoryId}
              onChange={(next) => {
                setSubCategoryId(next);
                setDirty(true);
              }}
            />
          ) : (
            <p className="text-sm text-ink-500">{craftCatalogue.loading ? "Loading…" : CRAFT_OFFLINE_FIRST_RUN}</p>
          )}
        </div>
        <div className="grid min-w-0 gap-1">
          <span className="field-label">State of origin</span>
          <MultiSelectDropdown
            ariaLabel="State of origin"
            values={stateOfOrigin}
            searchable
            placeholder="Choose states"
            options={stateOptions}
            onChange={(next) => {
              setStateOfOrigin(next);
              setDirty(true);
            }}
          />
        </div>
        <div className="grid min-w-0 gap-1">
          <span className="field-label">State of practice</span>
          <MultiSelectDropdown
            ariaLabel="State of practice"
            values={stateOfPractice}
            searchable
            placeholder="Choose states"
            options={stateOptions}
            onChange={(next) => {
              setStateOfPractice(next);
              setDirty(true);
            }}
          />
        </div>
        <div className="grid min-w-0 content-start gap-1">
          <span className="field-label">GI status</span>
          <SegmentedChoice
            label="GI status"
            value={giStatus}
            options={[
              { value: "GI", label: GI_LABEL.GI },
              { value: "NON_GI", label: GI_LABEL.NON_GI }
            ]}
            onChange={(next) => {
              setGiStatus(next as GiStatus);
              setDirty(true);
            }}
          />
        </div>
        <div className="md:col-span-2 lg:col-span-4">
          <DictatedTextArea
            name="description"
            label="Description"
            defaultValue={editing?.description ?? ""}
            explainWhenUnavailable={false}
            onDirty={() => setDirty(true)}
          />
        </div>
        <div className="md:col-span-2 lg:col-span-4">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              setDirty(true);
            }}
            title="Craft media"
            description="Photos, audio, video and documents."
          />
        </div>
        <UploadProgress
          progress={uploadProgress}
          sectionId={MEDIA_SECTION}
          label={MEDIA_SECTION_LABEL}
          className="md:col-span-2 lg:col-span-4"
        />
        {editing ? (
          <div className="md:col-span-2 lg:col-span-4">
            <ExistingMedia linkedRecordType="craft" linkedRecordId={editing.id} title="Previously uploaded craft media" />
          </div>
        ) : null}
        <ButtonRow className="md:col-span-2 lg:col-span-4">
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : editing ? "Update craft" : "Create craft"}
          </button>
          <button type="button" className="field-button-secondary" onClick={() => guard(() => resetForm(null))}>
            {editing ? "Cancel edit" : "Cancel"}
          </button>
        </ButtonRow>
      </form>
      ) : tab === "crafts" && !allowManage ? (
        <div className="panel mb-5 p-4 text-sm text-ink-muted">
          Ask an admin for access to add crafts.
        </div>
      ) : null}
      {editing ? (
        <div className="mb-5 empty:hidden">
          <FieldProvenance extraMetadata={editing.extraMetadata} title="Craft field contributions" />
        </div>
      ) : null}
      {tab === "crafts" ? (
        <>
          <div className="mb-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-5">
            <div className="sm:col-span-2 lg:col-span-1">
              <SearchInput
                value={query}
                onChange={setQuery}
                onSubmit={() => {
                  setApplied(query);
                  setPage(1);
                }}
                placeholder="Search crafts"
              />
            </div>
            <Dropdown
              ariaLabel="Filter by category"
              value={filterCategory}
              advanceOnSelect={false}
              onChange={(next) => {
                setFilterCategory(next);
                setFilterSub("");
                setPage(1);
              }}
              options={[{ value: "", label: "All categories" }, ...categoryOptions]}
            />
            <Dropdown
              ariaLabel="Filter by sub-category"
              value={filterSub}
              advanceOnSelect={false}
              searchable
              onChange={(next) => {
                setFilterSub(next);
                setPage(1);
              }}
              options={[{ value: "", label: "All sub-categories" }, ...subOptionsFor(filterCategory)]}
            />
            <Dropdown
              ariaLabel="Filter by GI status"
              value={filterGi}
              advanceOnSelect={false}
              onChange={(next) => {
                setFilterGi(next);
                setPage(1);
              }}
              options={[
                { value: "", label: "GI and Non GI" },
                { value: "GI", label: GI_LABEL.GI },
                { value: "NON_GI", label: GI_LABEL.NON_GI }
              ]}
            />
            <Dropdown
              ariaLabel="Filter by state"
              value={filterState}
              advanceOnSelect={false}
              searchable
              onChange={(next) => {
                setFilterState(next);
                setPage(1);
              }}
              options={[{ value: "", label: "All states" }, ...stateOptions]}
            />
          </div>
          <section className="panel overflow-hidden">
            {!catalogue ? (
              <div className="p-4 text-sm text-ink-700">
                {craftCatalogue.loading ? "Loading..." : CRAFT_OFFLINE_FIRST_RUN}
              </div>
            ) : rows.length === 0 ? (
              <div className="p-4">
                <EmptyState title="No crafts found" />
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[1100px] text-left text-sm">
                  <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                    <tr>
                      <ResizableTh>Serial</ResizableTh>
                      <ResizableTh>Craft</ResizableTh>
                      <ResizableTh>Sub-category</ResizableTh>
                      <ResizableTh>Category</ResizableTh>
                      <ResizableTh>GI</ResizableTh>
                      <ResizableTh>State of origin</ResizableTh>
                      <ResizableTh>State of practice</ResizableTh>
                      {counts ? <ResizableTh>Records</ResizableTh> : null}
                      <ResizableTh className="text-right">Actions</ResizableTh>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line-200">
                    {rows.map((craft) => {
                      const path = pathOf(craft.subCategoryId);
                      return (
                        <OpenableRow key={craft.id} href={recordViewHref("craft", craft.id)!} onOpen={rememberPlace} navigate={openThroughGuard}>
                          <td className="px-4 py-3 tabular-nums text-ink-700">{craft.sourceSerial ?? "–"}</td>
                          <td className="px-4 py-3">
                            <RecordTitleLink href={recordViewHref("craft", craft.id)!} onOpen={rememberPlace} navigate={openThroughGuard}>
                              {craft.name}
                            </RecordTitleLink>
                          </td>
                          <td className="px-4 py-3 text-ink-700">{path?.sub || "–"}</td>
                          <td className="px-4 py-3 text-ink-700">{path?.category || "–"}</td>
                          <td className="px-4 py-3 text-ink-700">{craft.giStatus ? GI_LABEL[craft.giStatus] : "–"}</td>
                          <td className="max-w-xs px-4 py-3 text-ink-700">{craft.stateOfOrigin.join(", ") || "–"}</td>
                          <td className="max-w-xs px-4 py-3 text-ink-700">{craft.stateOfPractice.join(", ") || "–"}</td>
                          {counts ? <td className="px-4 py-3 tabular-nums text-ink-700">{counts.get(craft.id) ?? 0}</td> : null}
                          <td className="px-4 py-3 text-right">
                            <RowActions
                              destructive={
                                canDeleteRecords ? (
                                  <button className={rowAction("danger")} data-danger onClick={() => remove(craft.id)}>
                                    Delete
                                  </button>
                                ) : undefined
                              }
                            >
                              {allowManage ? (
                                <button className={rowAction("edit")} onClick={() => void openForEdit(craft.id)}>
                                  Update
                                </button>
                              ) : null}
                              <button className={rowAction("neutral")} onClick={() => setCollabId(craft.id)}>
                                Discuss
                              </button>
                            </RowActions>
                          </td>
                        </OpenableRow>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
            {catalogue ? <Pagination page={shownPage} pages={pages} total={filtered.length} onPage={setPage} /> : null}
          </section>
        </>
      ) : null}
      <CollabDialog recordType="craft" recordId={collabId} onClose={() => setCollabId(null)} />
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
