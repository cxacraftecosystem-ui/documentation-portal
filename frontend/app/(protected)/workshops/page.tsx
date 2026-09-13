"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { MapPinned } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { Field, MultiNoteField, Select } from "@/components/FormControls";
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
import { StatusBadge } from "@/components/StatusBadge";
import { WorkshopMappingPanel } from "@/components/settings/WorkshopMappingPanel";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { locationFromForm, requiredText, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
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

/**
 * Do these two id lists name the same set? ORDER-INSENSITIVE on purpose: the multi-selects hand back
 * the order the researcher ticked boxes in, while the stored list comes back in the join table's own
 * order, so comparing sequences would report "changed" for a roster nobody touched — which is the
 * exact 403 the diff in `submit` exists to avoid. Both inputs are already duplicate-free (the join
 * table is unique on the pair; the pickers cannot tick a row twice), so a length check plus
 * membership is a true set comparison here.
 */
function sameIdSet(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const seen = new Set(a);
  return b.every((id) => seen.has(id));
}

const statusOptions: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"];

/** Section id the workshop media batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "workshop-media";
const MEDIA_SECTION_LABEL = "Workshop media";

/**
 * WHAT KIND OF WORKSHOP A `Workshop` ROW RECORDS — the two tokens, and the words for them.
 *
 * A Design & Prototype Development Workshop and an ordinary documentation visit are both `Workshop`
 * rows and, until migration 20260913120300, nothing told them apart. `OTHER` is the default and is
 * what every row recorded before that column implicitly was.
 *
 * WHY THIS PRODUCT OFFERS A TOKEN IT HAS NO SCREEN FOR. There is no DesignWorkshop table in this
 * repository and this control does not add one. The two products coordinate their record
 * vocabularies on purpose — the same argument that pins `experienceYears` to 0..90 here because the
 * sibling registry uses that bound — so a workshop recorded here that the sibling later adopts must
 * be able to carry the same mark. **A column no client can set is a column that can never hold
 * `DESIGN_PROTOTYPE`**: the wire half (schemas, the `GET /workshops?workshopType=` filter,
 * `lib/types.ts`, Android's `ApiModels.kt`) shipped with the migration and this control is what
 * makes it reachable.
 *
 * THE LABELS ARE THE SIBLING'S, VERBATIM (`Design & Prototype Development Workshop` / `Other
 * workshop`, frontend/lib/types.ts:339-342 over there, and its Android
 * `MainActivity.kt:5832`). The stored value is a Postgres enum and the label is a sentence:
 * "DESIGN_PROTOTYPE" is not something to print at a researcher, and two products printing two
 * different sentences for one enum value is how one decision comes to be described two ways.
 *
 * DECLARED HERE RATHER THAN IN `lib/types.ts`, which is where the sibling keeps its copy and where
 * this one belongs: that file was outside this change's reach. Moving it is a one-line export and
 * is named in the handoff — until then this page is the only consumer, so there is exactly one copy
 * either way.
 *
 * THE ORDER IS DESIGN_PROTOTYPE FIRST AND `OTHER` DEFAULTED. A researcher on this screen is
 * recording an ordinary visit far more often than not, so the DEFAULT is the honest one; the order
 * is the sibling's, so the two pickers read the same way round.
 */
const WORKSHOP_TYPE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "DESIGN_PROTOTYPE", label: "Design & Prototype Development Workshop" },
  { value: "OTHER", label: "Other workshop" }
];

export default function WorkshopsPage() {
  return (
    <UploadsProvider>
      {/* Next 16: `useEditDeepLink` reads useSearchParams, which must sit inside a Suspense boundary. */}
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <WorkshopsPageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

/**
 * ── DICTATION ON THE WORKSHOP FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ─────
 *
 * DICTATED: Workshop title · Place · Description · (and Village, inside the location card, which owns
 * its own decision).
 *
 * NOT DICTATED, one line each:
 *
 *  - **Workshop duration** — dates. A recogniser hands back "the fourth of March", and both readings
 *    of an ambiguous spoken date are BOTH valid dates, so a mis-transcribed one is a defect nothing
 *    reports.
 *  - **Status, Workshop kind** — closed vocabularies, and below professor there is no status control
 *    at all. "Workshop kind" is two options; there is no free text to speak and nothing a recogniser
 *    could add but a mis-hearing.
 *  - **Linked artisans, Crafts covered** — record pickers behind a themed multi-select.
 *  - **Workshop media** — a file picker.
 *
 * **Notes** — `MultiNoteField` — is a free-prose box and now has a microphone PER ROW, exactly as
 * `ProcessForm`'s `MultiNoteInput` does. It was the one gap on this form for as long as
 * `components/FormControls.tsx` was outside the reach of the sweep that dictated everything else;
 * the record-parity sweep closed it there, so every free-prose box on this screen has a microphone
 * and the sentence below still speaks for all of them.
 *
 * `titleCased` ON TITLE AND PLACE. Both `Workshop.title` and `Workshop.place` are in the API's
 * title-cased set (`backend/app/services/records.py:347-348`), so the box says what will actually be
 * stored. The sibling application omits `titleCased` on both of these boxes while applying it to the
 * same two columns on its questionnaire screen — it is inconsistent with itself, and the backend is
 * the authority.
 *
 * THE FORM KEY IS A BUG FIX. See `resetForm`: `editing?.id ?? "new"` does not change between two
 * consecutive NEW workshops, so the form was never rebuilt and the self-controlled description box
 * would have carried the previous workshop's text into the next one.
 *
 * ONE SENTENCE FOR THE WHOLE FORM: every control passes `explainWhenUnavailable={false}` and
 * `DictationUnavailableNotice` sits once at the top of the field grid. The sibling application ships
 * this same screen with the flags and NO notice, which on Firefox is three microphones vanishing with
 * nothing anywhere saying why. That line is not optional.
 */
function WorkshopsPageBody() {
  const confirm = useConfirm();
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
  /*
    THE TWO DICTATED ONE-LINE BOXES, AND THE KEY THAT CLEARS THE THIRD.

    `DictatedTextInput` is controlled by its caller, so `title` and `place` live here and are seeded
    by `resetForm`. The description is a `DictatedTextArea`, which owns its own value and re-seeds
    only on REMOUNT — so `formKey` is what clears it. Both exist because `formElement.reset()` (in
    `submit`) rewrites the DOM and tells React nothing: neither kind of box is reachable from it.
  */
  const [title, setTitle] = useState("");
  const [place, setPlace] = useState("");
  const [formKey, setFormKey] = useState(0);
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
  const router = useRouter();
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

  // Soft navigation via the round back control in the page header — the page's only back control —
  // parks through the same mechanism, so Discard performs the navigation that was asked for.
  useLeaveGuard(dirty, () => guard(() => router.back()));

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
    // The controlled, dictated boxes. `""` and not `undefined` — a controlled input handed undefined
    // switches to uncontrolled, React warns, and the box keeps the last record's text.
    setTitle(next?.title ?? "");
    setPlace(next?.place ?? "");
    // ── AND THE FORM IS REMOUNTED, WHICH IS A BUG FIX AND NOT A TIDY-UP ─────────────────────
    // The `<form>` was keyed `editing?.id ?? "new"` alone, so saving a NEW workshop did not change
    // the key and the form was not rebuilt: `formElement.reset()` was the whole of the clearing, and
    // that reaches uncontrolled DOM inputs and nothing else. The description box — now a
    // `DictatedTextArea`, which owns its value — would have kept the workshop just saved. Identical
    // trap and identical fix on `/crafts`; nobody had noticed it here either.
    setFormKey((key) => key + 1);
  }

  // `/workshops?edit=<id>` loads that workshop into the form below; `/workshops?new=1` opens a blank
  // one. Both are how the View Data browser, a search result, the dashboard tiles and Recent
  // submissions reach this page — before this they all linked to the bare `/workshops`, so
  // "Update workshop" landed on the create form with none of the record's fields in it.
  //
  // The fetched record goes through the SAME `resetForm` a row click uses, which is what re-seeds the
  // two link pickers: `artisanIds` and `craftIds` are React state rather than form controls, so a
  // deep link that only set `editing` would have shown the workshop's title and place with its
  // artisan and craft rosters silently empty — and saving would then have cleared them.
  const { loading: deepLinkLoading } = useEditDeepLink<Workshop>({
    endpoint: "/workshops",
    basePath: "/workshops",
    targetRef: formRef,
    // Through `guard`, exactly as the row Edit button below goes through it — see the same note on
    // /crafts for why seeding directly would silently discard work typed while the fetch was in
    // flight.
    onEdit: (record) => guard(() => resetForm(record)),
    onNew: () => guard(() => resetForm(null)),
    onError: setError,
    allowed: allowManage,
    errorMessage: "Unable to load that workshop"
  });

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    try {
      const title = requiredText(form, "title");
      const location = locationFromForm(form);
      const payload: Record<string, unknown> = {
        title,
        /*
          SENT ON BOTH CREATE AND UPDATE, AND NEVER OMITTED.

          `WorkshopUpdate.workshopType` is `str | None = None` and the route dumps with
          `exclude_unset=True`, so an omitted key means "leave the stored kind alone" — which sounds
          like the safe default and is the wrong one HERE, because this picker is always on screen
          holding a value the editor can see. Omitting it would mean a researcher who switched the
          dropdown to "Other workshop" and pressed Update got a 200 and no change, with the picker
          still reading Other until the page was reloaded.

          `requiredText` and not `textValue`: the picker has no empty row, so a null here could only
          mean the control failed to mount, and sending null would be a 422 from a non-nullable
          create field rather than a silent miss.
        */
        workshopType: requiredText(form, "workshopType") || "OTHER",
        date: requiredText(form, "date"),
        startDate: requiredText(form, "startDate"),
        endDate: requiredText(form, "endDate"),
        place: requiredText(form, "place"),
        description: textValue(form, "description"),
        notes: textValue(form, "notes"),
        // Below professor the backend forces PENDING on create and drops status changes on update.
        status: canSetStatus ? requiredText(form, "status") : "PENDING",
        location
      };
      /*
       * THE TWO ROSTERS ARE SENT ONLY WHEN THEY CHANGED, and only on an edit. This mirrors Android's
       * WorkshopForm, which diffs them the same way, and it is a permission fix rather than a saving
       * of bytes.
       *
       * `PATCH /workshops/{id}` re-checks each roster it is SENT: when the caller is neither an
       * admin nor the workshop's creator and lacks EDIT-level access to it,
       * `assert_can_contribute_relation(..., populated=link_count > 0, ...)` refuses any send at all
       * against an already-populated roster. Sending both unconditionally therefore meant a
       * professor who opened someone else's workshop, corrected a typo in its PLACE and pressed
       * Update was answered "Only the original contributor or an admin can change populated
       * relation: artisanIds" — a refusal about two pickers they had not touched, on a save the
       * server would otherwise have accepted. Omitting an unchanged roster leaves `artisanIds` unset
       * in the payload, which is exactly what the WorkshopUpdate schema's `list[str] | None = None`
       * means: do not touch this relation.
       *
       * On CREATE both are always sent — there is no stored roster to compare against, and the
       * create path has no such check.
       */
      if (!editing || !sameIdSet(artisanIds, linkedArtisanIds(editing))) payload.artisanIds = artisanIds;
      if (!editing || !sameIdSet(craftIds, linkedCraftIds(editing))) payload.craftIds = craftIds;
      // Offline this queues to the outbox with its media rather than failing at the Save button.
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
        // OutboxBanner at the top of the page names the entry and says where it lives.
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
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
    const ok = await confirm(
      deleteConfirm(
        "Delete this workshop?",
        "This permanently deletes the workshop. This action cannot be undone.",
        "Researcher assignments to it go too. Records documented during the workshop are kept."
      )
    );
    if (!ok) return;
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
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">
          Loading the workshop you asked to edit...
        </div>
      ) : null}

      {/* WHY THIS SITS ON THE WORKSHOPS PAGE and not in Settings. What it fixes is a workshop's own
          contents — "this workshop looks empty" — so the person who notices the symptom is already here,
          looking at the workshop. Admin-only because it writes to hundreds of rows at once; the endpoints
          are gated the same way, so the card is not the security boundary. */}
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
        // scroll-mt-28 clears the island nav when `?edit=` scrolls this form into view, and it is
        // load-bearing here in a way it is not on /crafts: an admin has the workshop-mapping panel
        // sitting above this form, so there is real content to scroll past.
        className="panel mb-5 grid scroll-mt-28 gap-4 p-4"
      >
        {/* WHICH workshop is in the form — see the same chip on /crafts for why the button label is
            not enough on its own when the researcher arrived here from another page entirely. */}
        {editing ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
              Editing: {editing.title}
            </span>
          </div>
        ) : null}
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          {/*
            THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
            Every dictated box below passes `explainWhenUnavailable={false}`; without this line, on
            Firefox they would simply vanish and nothing anywhere would say why. `md:col-span-2
            lg:col-span-4` because this is a child of the four-column field grid.
          */}
          <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
          {/* `name` IS LOAD-BEARING ON BOTH OF THESE. `submit` reads them with
              `requiredText(form, "title")` and `requiredText(form, "place")` out of a `FormData`, so
              a dropped attribute is a save that refuses with a message about an empty box that is
              plainly not empty. `setDirty(true)` BY HAND because a dictated phrase is a React state
              write and fires no native `input` event for the form's `onInput` to catch. */}
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
          {/*
            WHAT KIND OF WORKSHOP THIS IS — see `WORKSHOP_TYPE_OPTIONS` above for the two tokens, the
            wording and why this product carries a kind it has no screen for.

            `onChange={() => setDirty(true)}` BY HAND, like every other themed control on this form:
            a `Select` renders a `<button>` and fires no native input event, so the form's
            `onInput={markDirty}` never sees it and a workshop whose only edit was its KIND would
            leave the page without the unsaved-changes guard saying a word.

            NO PERMISSION BRANCH, unlike Status beside it. `WorkshopCreate`/`WorkshopUpdate` accept
            this from anybody the route already lets write the workshop — there is no rank rule to
            mirror, and inventing one here would be a frontend guard with no server behind it.
          */}
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
                  <option key={status}>{status}</option>
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
        {/*
          One form serves create AND edit here (the `key` above remounts it), so the stored location
          has to be handed over on the edit pass. Without it the card reads `initial === undefined`,
          treats an edit as a new record, and auto-captures — writing wherever the researcher happens
          to be sitting over the coordinates of a workshop that was documented somewhere else.
        */}
        <LocationFields
          initial={editing ? ((editing as Workshop & { location?: LocationInitialValues | null }).location ?? null) : undefined}
          onDirty={() => setDirty(true)}
        />
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
