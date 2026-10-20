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

/** Section id the craft media batch publishes under, so the page-level tray can aggregate it. */
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
      {/* Next 16: `useEditDeepLink` reads useSearchParams, which must sit inside a Suspense boundary. */}
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <CraftsPageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

/**
 * ── DICTATION ON THE CRAFT FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ────────
 *
 * DICTATED: Craft name · Local name · Description.
 *
 * PLACE LEFT THE FORM on 2026-10-20, as it had on the designer portal: a craft's whereabouts are its
 * two state lists. `Craft.place` stays a column (old records hold it, and the view still reads it); the
 * form simply never sends it, so an edit leaves a stored value as it was.
 *
 * NOT DICTATED: **Workshop** (a record picker behind a themed dropdown — nothing free to speak),
 * **Category / Sub-category**, **States** and **GI status** (closed vocabularies from the master list)
 * and **Craft media** (a file picker).
 *
 * CATEGORY LEFT THIS LIST on 2026-10-20: it is Category → Sub-category from the master list now.
 *
 * THE SEARCH BOX IS NOT PART OF THIS FORM AND GETS NOTHING. It is not an answer being stored; it
 * re-runs a query on every keystroke, so a microphone there would fire a debounced fetch per phrase.
 * If searching by voice is wanted it belongs in `SearchInput` once, for every list screen.
 *
 * `titleCased` ON THE NAME IS NEW HERE. `Craft.name` goes through
 * `clean_data` on write (`records.py:341`, `:348`), so a researcher who typed "bagru block printing"
 * saved and watched the record silently become something else — the exact defect `TitleCasedInput`
 * was written for, on the one record form that had never been given it. Description is a
 * `DictatedTextArea` and deliberately NOT a `RichTextField`: pointing an editor here would make
 * `Craft.description` another `String?` column capable of holding `{"blocks":…}`, which
 * `record_fields.cell()` prints verbatim into a CSV. That is a storage decision across three
 * languages and it belongs with the other seven prose columns or nowhere.
 *
 * ONE SENTENCE FOR THE WHOLE FORM: every control passes `explainWhenUnavailable={false}` and
 * `DictationUnavailableNotice` sits once at the top of the field grid.
 */
function CraftsPageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageCrafts(user);
  /** Content admins: Admin and Master Admin (Ministry Admin where the role exists). */
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
  /*
    THE FOUR DICTATED ONE-LINE BOXES, AND THE KEY THAT CLEARS THE FIFTH.

    `DictatedTextInput` is controlled by its caller — it has one mode, for the reason written out in
    that file — so the four names below live here and are seeded by `resetForm`. The description is a
    `DictatedTextArea`, which owns its own value and re-seeds only on REMOUNT, so it is `formKey` that
    clears it. Both mechanisms exist because `formElement.reset()` rewrites the DOM and tells React
    nothing: neither kind of box is reachable from it.
  */
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
  // Unsaved-changes guard: dirty is set by any form input / media change; confirmAction holds the
  // navigation the user asked for while the dialog decides its fate.
  const router = useRouter();
  const [dirty, setDirty] = useState(false);
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  /** A8: the craft just created, for the "Add an artisan" next step. */
  const [savedCraft, setSavedCraft] = useState<Craft | null>(null);
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

  /** The master list: the table, its filters and every picker read the same catalogue. */
  const craftCatalogue = useCraftCatalogue();
  const catalogue = craftCatalogue.catalogue;
  const refreshCatalogue = craftCatalogue.reload;
  /** Records per craft, for everyone: each `GET /crafts` row carries its `referenceCount`. */
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

  /** After any write the catalogue's version moved, so every picker re-reads it. */
  function load() {
    invalidateCraftCatalogue();
    refreshCatalogue();
    void loadCounts();
  }

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
  /** "Category" and "Sub-category" of a catalogue row, for the table. */
  const pathOf = useCallback(
    (subCategoryId: string | null) => {
      if (!catalogue || !subCategoryId) return null;
      const index = craftIndex(catalogue);
      const sub = index.subCategoryById.get(subCategoryId);
      return sub ? { sub: sub.name, category: index.categoryById.get(sub.categoryId)?.name ?? "" } : null;
    },
    [catalogue]
  );

  /** The Crafts tab's rows: the catalogue, searched and filtered here, in list order. */
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

  /** `/crafts?new=<name>`: "Add it to the list" from a craft picker opens the form with that name. */
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

  /** Update from a row: the catalogue row is the list's; the form needs the whole record. */
  async function openForEdit(id: string) {
    try {
      const record = await apiFetch<Craft>(`/crafts/${id}`);
      guard(() => resetForm(record));
      requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start" }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load that craft");
    }
  }

  /*
   * BACK FROM A CRAFT'S VIEW PUTS THE LIST BACK AS IT WAS — search, page and scroll. A row click
   * opens the read-only view (`/crafts/[id]`); see `lib/listReturn.ts`.
   */
  const listReturn = useListReturn("crafts");
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

  // Soft navigation via the round back control in the page header — the page's only back control —
  // parks through the same mechanism, so Discard performs the navigation that was asked for.
  useLeaveGuard(dirty, () => guard(() => router.back()));

  /**
   * Opening a row's read-only view leaves this page, and with it the inline form above the list —
   * so it asks first, exactly as the switcher and the deep link do.
   */
  const openThroughGuard = (href: string) => guard(() => router.push(href));

  /** Run `action` now, or park it behind the unsaved-changes dialog when the form is dirty. */
  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  function resetForm(next: Craft | null) {
    setEditing(next);
    setMediaFiles([]);
    setDirty(false);
    // The four dictated boxes are page state, so they are seeded here rather than by a `defaultValue`
    // the remount below would re-read. `next` is the record being loaded, or null for a blank form.
    setName(next?.name ?? "");
    setLocalName(next?.localName ?? "");
    setSubCategoryId(next?.subCategoryId ?? "");
    setStateOfOrigin(next?.stateOfOrigin ?? []);
    setStateOfPractice(next?.stateOfPractice ?? []);
    setGiStatus(next?.giStatus ?? "NON_GI");
    setFormOpen(Boolean(next));
    // ── AND THE FORM IS REMOUNTED, WHICH IS A BUG FIX AND NOT A TIDY-UP ─────────────────────
    // The `<form>` was keyed `editing?.id ?? "new"` alone, so creating a craft did not change the key
    // and the form was NOT rebuilt: `formElement.reset()` on the saved branch of `submit` was the
    // whole of the clearing, and that reaches uncontrolled DOM inputs and nothing else. The
    // description box — now a `DictatedTextArea`, which owns its value — would paint empty while
    // still holding the saved text, and one keystroke would bring the previous description back into
    // the next craft. The QUEUED branch is worse: it calls `resetForm(null)` and never calls
    // `formElement.reset()` at all, and offline is the path nobody tests.
    // Bumping a counter into the key is the instrument `ArtisanForm` uses (`formKey`) for the same
    // reason: remounting is what clears the state living inside the field components.
    setFormKey((key) => key + 1);
  }

  // `/crafts?edit=<id>` loads that craft into the form below; `/crafts?new=1` opens a blank one.
  // Both are how the View Data browser, the dashboard tiles and Recent submissions reach this page —
  // before this they all linked to the bare `/crafts`, so "Update craft" landed on the create form.
  const { loading: deepLinkLoading } = useEditDeepLink<Craft>({
    endpoint: "/crafts",
    basePath: "/crafts",
    targetRef: formRef,
    // Through `guard`, exactly as the row Edit button below goes through it: the form stays typeable
    // while the fetch is in flight, and `resetForm` remounts it (`key={editing?.id}`) and clears
    // `dirty` — so seeding directly would throw away anything typed in those seconds with nothing
    // asked and nothing said. Dirty means the unsaved-changes dialog decides, as everywhere else.
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
        subCategoryId: subCategoryId || null,
        stateOfOrigin,
        stateOfPractice,
        giStatus,
        workshopId: workshop.workshopId || null,
        description
      };
      if (exifItems.length) payload.extraMetadata = { mediaExif: exifItems };
      // Offline this queues to the outbox with its media rather than failing at the Save button.
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
        // OutboxBanner at the top of the page names the entry and says where it lives.
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
        // The uploaded files surface twice: as chips under this section and in the page-level tray.
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
      // A8: a NEW craft is followed by its artisans, so offer that as the next step.
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
      // 409 CRAFT_IN_USE: "Used by {n} records. Merge it into another craft instead."
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
        <CraftCategoriesTab catalogue={catalogue} canEdit={contentAdmin} canDelete={contentAdmin && adminMode} onChanged={load} />
      ) : tab === "categories" ? (
        <div className="panel p-4 text-sm text-ink-500">Loading…</div>
      ) : null}
      {tab === "review" && contentAdmin ? (
        <div className="grid gap-5">
          {catalogue ? <CraftReviewQueue catalogue={catalogue} onChanged={load} /> : null}
          {/* Craft codes: the shared code both portals carry. */}
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
      {/*
        OUTSIDE THE `<form>`, AND ONLY WHILE A CRAFT IS LOADED INTO IT.
        
        Outside, because this form is `onInput={() => setDirty(true)}` and the picker's search box is
        an `<input>`. `SearchableSelect` does stop that event at the panel (see `containEvents`, which
        exists for exactly this class of problem — a React portal moves the DOM but not the tree), but
        relying on a sibling component's event containment to keep this page's unsaved-changes prompt
        from arming on a search nobody committed is a dependency at a distance. Rendered as a sibling
        it cannot arise.

        Only while editing, because in create mode this page is a blank form over a list that has its
        own search box and its own pager. A second record picker there would be a third way to reach
        the same rows, defaulted to a workshop the list is not filtered by.
      */}
      {allowManage && editing ? (
        <RecordSwitcher
          kind="craft"
          currentId={editing.id}
          currentWorkshopId={editing.workshopId}
          className="panel mb-5 grid gap-3 p-4 md:grid-cols-2"
          // THROUGH `guard`, exactly as the row Edit button and the deep link's own `onEdit` go
          // through it. That is what makes Discard land on the craft that was just PICKED: `guard`
          // parks this navigation until the dialog is answered and then runs it, rather than letting
          // the form's interceptor substitute its own `router.back()`. `onDiscard` clears `dirty`
          // first, so the `?edit=` that lands a moment later does not raise the prompt a second time.
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
        // scroll-mt-28 clears the island nav when `?edit=` scrolls this form into view (§AppShell pt-24).
        className="panel mb-5 grid scroll-mt-28 gap-3 p-4 md:grid-cols-2 lg:grid-cols-4"
      >
        {/* WHICH craft is in the form. Arriving from ?edit= drops the researcher straight into a
            populated form part-way down the page, and "Update craft" on the button is too far away
            (and too generic) to answer "update WHICH one" on its own. */}
        {editing ? (
          <div className="flex flex-wrap items-center gap-2 md:col-span-2 lg:col-span-4">
            <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
              Editing: {editing.name}
            </span>
          </div>
        ) : null}
        {/* The workshop leads every other dropdown: it is the context the record belongs to. */}
        <WorkshopSelect state={workshop} onDirty={() => setDirty(true)} saving={saving} />
        {/*
          THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
          Every dictated box below passes `explainWhenUnavailable={false}`, because on Firefox the
          same honest paragraph printed five times down one form is a block of grey text nobody reads.

          `md:col-span-2 lg:col-span-4` because THIS form's root element IS the field grid: without
          the span the sentence would be one cell of four and would wrap to five lines beside the
          workshop picker.
        */}
        <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
        {/* `setDirty(true)` BY HAND in every `onChange`: the form's `onInput` catches typing, but a
            dictated phrase is a React state write and fires no native `input` event, so a researcher
            who only ever spoke would be told there was nothing to lose on the way out. */}
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
        {/* NOT title-cased: `localName` is Devanagari or Gujarati, where capitalising means nothing. */}
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
        {/* THE WRAPPER `<div>` STAYS, and it is not decoration. `DictatedTextArea`'s `className` prop
            lands on its `<textarea>`, not on its wrapper, so passing `md:col-span-2 lg:col-span-4`
            to the component compiles, renders, and silently leaves the box one cell wide in a
            four-column grid. The span has to be on an element that IS the grid item. */}
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
        {/* Editing an existing craft: everything already attached to it, with playback and per-file
            delete — the same surface the artisan/product/tool/workshop forms carry. */}
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
      {/* Same provenance block the artisan/product/tool/workshop edit surfaces carry, for the craft
          being edited. empty:hidden — FieldProvenance renders nothing without provenance access. */}
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
                                contentAdmin && adminMode ? (
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
      {/* The hand-rolled overlay this replaces was pre-FieldDialog legacy: no focus trap, no Escape,
          no focus restoration and no scroll lock. /workshops already used the shared component. */}
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
