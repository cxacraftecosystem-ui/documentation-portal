"use client";

import { useId, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { mergeById } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { craftChangeClearsArtisan, useCraftAndArtisanOptions, useRecordOffPage } from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import { locationFromForm, numericValue, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { Artisan, Craft, RecordStatus, ToolDocumentation } from "@/lib/types";
import { makerOptions, traditionOptions } from "@/lib/types";

/** Dropdown label for a linked artisan: always "Name · Place" (name alone if no place), never ids. */
function artisanOptionLabel(artisan: Artisan) {
  const name = artisan.name?.trim() || "Unnamed artisan";
  // "·" (middle dot), not "•" — Android joins every record label with the middle dot, and the
  // process form already does; using both marks in one tool form reads as two conventions.
  return artisan.place?.trim() ? `${name} · ${artisan.place.trim()}` : name;
}

/**
 * Status policy (backend-enforced; the UI mirrors it): professor+ may pick any status and new
 * records default to APPROVED; everyone below sees a locked chip — creations are forced to PENDING
 * and unauthorized status changes are silently dropped server-side on update.
 */
function StatusField({
  canSetStatus,
  initialStatus,
  onDirty
}: {
  canSetStatus: boolean;
  initialStatus?: RecordStatus;
  onDirty?: () => void;
}) {
  if (canSetStatus) {
    const options: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];
    if (initialStatus === "NEEDS_REVISION") options.push("NEEDS_REVISION");
    return (
      <Field label="Status">
        <Select name="status" defaultValue={initialStatus ?? "APPROVED"} onChange={onDirty}>
          {options.map((status) => (
            <option key={status}>{status}</option>
          ))}
        </Select>
      </Field>
    );
  }
  const text = initialStatus ? initialStatus.charAt(0) + initialStatus.slice(1).toLowerCase().replace(/_/g, " ") : "Pending";
  return (
    <div className="grid content-start gap-1">
      <span className="field-label">Status</span>
      <span
        className="inline-flex h-10 w-fit items-center rounded-full border border-line-200 bg-surface-50 px-4 text-sm font-medium text-ink"
        title="Submitted for review — a reviewer sets the final status."
      >
        {text}
      </span>
    </div>
  );
}

/**
 * ── DICTATION ON THIS FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ──────────────
 *
 * The rule: a free-text box HAS a microphone unless there is a reason it must not, and the reason is
 * written down here so that a later reader can tell a decision from an oversight. One-line boxes use
 * `DictatedTextInput`; the two narrative ones use `RichTextField`, whose editor carries the
 * microphone at the caret so a phrase lands inside the document rather than on the end of it.
 *
 * DICTATED: Toolkit name · Local name · English name · Craft name · Artisan name · Place · Process
 * used in · Material · Suggestions for improvement · Remarks · (and Village, inside the location
 * card, which owns its own decision).
 *
 * MATERIAL IS DICTATED AND THE MEASUREMENTS ARE NOT, which is the one line of this register a
 * reviewer will stop at. "Mango wood with an iron collar" is free prose — neither a measurement nor a
 * vocabulary — and it is the answer most likely to be given while holding the tool.
 *
 * NOT DICTATED, one line each, and each is a rule rather than a preference:
 *
 *  - **Workshop, Linked craft, Linked artisan, Maker, Tradition type, Status** — closed vocabularies
 *    and record pickers behind a themed dropdown. There is no free text to speak.
 *  - **Years in use, Height, Height (inches), Width, Length, Breadth, Thickness, Weight, Radius,
 *    Replacement cost** — `type="number"` boxes. A recogniser spells digits out in words ("thirty"),
 *    which a native number input DISCARDS silently: the box is empty after a spoken answer with
 *    nothing saying why. Length, Breadth and Height (inches) carry a second reason — the
 *    grid-measurement capture PROPOSES all three and a person accepts, so a spoken fourth route
 *    would record an acceptance for a reading nobody can re-derive.
 *  - **Document using grid, Process stages media, Tool media** — file pickers and capture cards.
 *
 * NOT title-cased, deliberately, though they are dictated: **Local name** (Devanagari or Gujarati,
 * where capitalising means nothing), **Process used in** and **Material** — all three are absent
 * from the API's title-cased set (`backend/app/services/records.py:339-354`), so a "Will be saved
 * as …" hint on any of them would promise a normalisation that never happens.
 *
 * ONE SENTENCE FOR THE WHOLE FORM. Every control above passes `explainWhenUnavailable={false}` and
 * `DictationUnavailableNotice` sits once at the top. Eleven microphones down one form is eleven
 * copies of the Firefox paragraph, which is how a true sentence becomes wallpaper.
 */
export function ToolForm({ initial }: { initial?: ToolDocumentation }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const formRef = useRef<HTMLFormElement>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [craftId, setCraftId] = useState(initial?.craftId ?? searchParams.get("craftId") ?? "");
  const [artisanId, setArtisanId] = useState(initial?.artisanId ?? searchParams.get("artisanId") ?? "");
  // Android parity: picking a linked craft fills the craft name; picking a linked artisan fills the
  // artisan name + place — so these three are controlled.
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(initial?.artisanName ?? searchParams.get("artisanName") ?? "");
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  /*
    HOISTED FOR THE MICROPHONE. These five were uncontrolled `defaultValue` boxes; `DictatedTextInput`
    is controlled by its caller and has exactly one mode, for the reason written out in that file (a
    self-controlled box repaints stale text on a form cleared by `formElement.reset()`). This form
    clears by NAVIGATING AWAY, so the trap does not bite here — but one contract for the control
    across the app is worth more than a second mode on this one screen.
  */
  const [toolkitName, setToolkitName] = useState(initial?.toolkitName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [englishName, setEnglishName] = useState(initial?.englishName ?? "");
  const [processUsedIn, setProcessUsedIn] = useState(initial?.processUsedIn ?? "");
  const [material, setMaterial] = useState(initial?.material ?? "");
  // Android parity: ordered "Process stages" captures, archived as STAGE_STEP_1, STAGE_STEP_2, …
  const [stageFiles, setStageFiles] = useState<File[]>([]);
  // Grid-measurable dimensions are controlled so the "Document using grid" capture can auto-fill them.
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");
  /*
    THE THIRD MEASUREMENT THE GRID PANEL PROPOSES, AND THE COLUMN IT SHOULD ALWAYS HAVE FILLED.

    `height` above is the OLD unit-less column. It is kept rather than merged because rows already
    hold values in it and nothing in the database can say what unit those are in — see
    `backend/app/schemas/records.py` at `ToolCreate.heightInches` and migration 20260913120100.

    The defect this box closes is not a missing field, it is a SILENT one: `GridMeasurement`'s
    `onHeight` returns a reading in INCHES and the only box it could reach was the unit-less one, so
    every grid-measured tool height in this repository was stored with no recoverable unit, under a
    200, with the number looking perfectly right on screen. `onHeight` below now writes THIS state
    and the unit-less box is typed by hand or not at all.
  */
  const [heightInches, setHeightInches] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  /*
    ONE SENTENCE, TWO BOXES, AND IT IS REFERENCED BY BOTH.

    Two boxes both labelled with the word "Height" on one form is a question a researcher cannot
    answer from the labels, and the honest answer is not short enough to fit in a label. So it is a
    real paragraph under them, and `aria-describedby` on BOTH inputs — not one — because a reader
    who tabs into either one has exactly the same question. `useId` rather than a literal so the
    attribute cannot collide if this form is ever mounted twice on a page.

    It lives OUTSIDE the two `Field`s deliberately: `Field` is a `<label>`, and a `<p>` is not
    phrasing content, so nesting it there is invalid markup AND folds the whole sentence into each
    box's accessible name — a screen reader would read the paragraph twice before saying "Height".
  */
  const heightHelpId = useId();
  /**
   * The craft and artisan dropdowns' contents, and what they are NOT showing.
   *
   * Shared with ProductForm, which asks the identical question and had the identical defect in it —
   * see `forms/recordPickers` for the three requests this makes and for the 100-row ceiling that
   * made the second and third ones necessary. `referenceState` still means what it did ("can I see
   * this artisan?" and "is there any signal?" are different answers, and `useCarryContext` treats
   * them differently); it lives in the hook only because it is settled by the same load.
   */
  const {
    artisans,
    crafts,
    referenceState,
    craftCut,
    craftArtisanCut,
    artisansLoadedForCraft
  } = useCraftAndArtisanOptions({ craftId, artisanId });
  /**
   * THIS TOOL'S OWN CRAFT IS ALWAYS AN OPTION, wherever it sorts.
   *
   * The hook above does the by-id rescue for the ARTISAN and for nobody else. `GET /crafts` is
   * clamped to 100 rows and ordered NAME ASCENDING (deliberately — `routes/crafts.py:82-87`), so the
   * cut is stable and always falls in the same place: every tool of a craft whose name sorts past it
   * opens with its craft dropdown reading "Unlinked / type below" beside a REQUIRED "Craft name" box
   * holding the right name. The stored link is intact and would be saved untouched — but the form
   * says it is not, and the obvious repair for a craft that looks unlinked is to pick one, which is
   * the single action that really does rewrite the link.
   *
   * Identical to ProductForm's, by the same hook. Do not write a variant of it.
   */
  const offPageCraft = useRecordOffPage<Craft>("/crafts", craftId, crafts);
  const craftOptions = useMemo(() => (offPageCraft ? mergeById(crafts, [offPageCraft]) : crafts), [crafts, offPageCraft]);
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  // Hands the prompt to the round back control in the page header, which is now the only back
  // control on the page.
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  // The API includes the record's stored location (not yet in the TS type); pass it so the edit
  // form pre-fills coordinates instead of auto-capturing the editor's current position.
  const initialLocation = initial
    ? ((initial as ToolDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  // The workshop this tool was documented at: shared picker, shared most-recent defaulting, and the
  // late-submission gate (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

  // Offer the sitting this researcher was last working in, however they got here — the query string
  // only survives a click straight through from the save screen (lib/carryContext). The TOOL in the
  // bag is this form's own subject and is never applied here; a product or process in it belongs to
  // other forms and is left alone rather than dropped, so they still have it.
  const carry = useCarryContext({
    enabled: !isEdit,
    // Both dropdowns are built from exactly these two lists, so "absent from the list" is both
    // "you can no longer reach it" and "this form could not show it" — one check answers both.
    // `craftOptions`, not `crafts`: a carried craft that is merely off the picker's first page IS
    // reachable — the by-id lookup fetched it — and pruning it would drop a perfectly good link from
    // the bag for the same "absent from page one" reason the artisan side already corrects.
    scopes: [carryScope("artisan", referenceState, artisans), carryScope("craft", referenceState, craftOptions)],
    // This form has no product, tool or process field, so it neither fills those in nor lets the
    // banner claim it did — they stay in the bag for the forms that do.
    applies: ["craft", "artisan", "workshop"],
    onApply: (context) => {
      if (context.craftId) setCraftId(context.craftId);
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanId(context.artisanId);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  /** "Change": drop every carried value so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setCraftId("");
    setCraftName("");
    setArtisanId("");
    setArtisanName("");
    setPlace("");
  }

  // Task 6: filter the artisan dropdown to the chosen craft (keeping any pre-existing selection).
  const artisansForCraft = craftId
    ? artisans.filter((artisan) => artisan.craftId === craftId || artisan.id === artisanId)
    : artisans;

  function handleBack() {
    if (dirty) setBackPromptOpen(true);
    else router.back();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read the form synchronously: React nulls event.currentTarget across the await below.
    const form = new FormData(event.currentTarget);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    try {
      const exifItems = await collectExifMetadata([...Object.values(gridFiles), ...stageFiles, ...mediaFiles].filter(Boolean) as File[]);
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = {
        craftName: requiredText(form, "craftName"),
        place: requiredText(form, "place"),
        artisanName: requiredText(form, "artisanName"),
        toolkitName: requiredText(form, "toolkitName"),
        localName: textValue(form, "localName"),
        englishName: textValue(form, "englishName"),
        processUsedIn: textValue(form, "processUsedIn"),
        material: textValue(form, "material"),
        yearsInUse: numericValue(form, "yearsInUse"),
        height: toNum(height),
        width: numericValue(form, "width"),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        // The unit-bearing height. `toNum` and not `numericValue` for the same reason as the three
        // above it: the box is controlled so the grid panel can fill it, and a controlled input's
        // value still reaches FormData — but reading it from state is the single source and cannot
        // disagree with what is on screen.
        heightInches: toNum(heightInches),
        thickness: numericValue(form, "thickness"),
        weight: numericValue(form, "weight"),
        radius: numericValue(form, "radius"),
        maker: requiredText(form, "maker") || "UNKNOWN",
        traditionType: requiredText(form, "traditionType") || "UNKNOWN",
        replacementCost: numericValue(form, "replacementCost"),
        suggestionsForToolImprovement: textValue(form, "suggestionsForToolImprovement"),
        // `appendStoredParagraph` and NOT `appendRemarksWithExif`: remarks is a rich-text editor
        // now, so this column may hold a JSON document, and concatenating the EXIF summary onto the
        // end of a JSON string produces a value that is neither valid JSON nor readable prose. The
        // helper appends INTO the document when there is one and is byte-for-byte the old behaviour
        // when there is not.
        remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: artisanId || null,
        craftId: craftId || null,
        workshopId: workshop.workshopId || null,
        // Below professor no status control is rendered: create submits PENDING, edit resubmits the
        // current status (the backend drops unauthorized changes either way).
        status: requiredText(form, "status") || initial?.status || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        // extraMetadata stays programmatic (EXIF etc.) — the raw JSON textarea was removed.
        extraMetadata: exifItems.length ? { mediaExif: exifItems } : {}
      };
      // Offline this queues instead of failing. Three groups, three batches: the measurement grids,
      // the numbered process-stage captures and the general field media each keep their own caption,
      // because the caption is the only thing that says which photo is which.
      const outcome = await saveOrQueue<ToolDocumentation>({
        label: `Tool · ${payload.toolkitName || "Untitled"}`,
        endpoint: initial ? `/tools/${initial.id}` : "/tools",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "tool",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          })),
          ...stageFiles.map((file, index) => ({
            files: [new File([file], `STAGE_STEP_${index + 1}_${file.name}`, { type: file.type, lastModified: file.lastModified })],
            linkedRecordType: "tool",
            caption: `Process stage step ${index + 1} for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          })),
          {
            files: mediaFiles,
            linkedRecordType: "tool",
            caption: `Field media for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      // Bank the sitting the moment the record is accepted, so the next form opened from the
      // dashboard already knows where the researcher is.
      const sitting = {
        artisanId,
        artisanName: payload.artisanName,
        place: payload.place,
        craftId,
        craftName: payload.craftName,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      if (outcome.queued) {
        // Offline is the normal case, but a queued tool has no id yet, so nothing can be assigned to
        // it. Whatever tool was in the bag is dropped rather than left to stand in for the one just
        // recorded — an old tool offered under a new one's name is a wrong link.
        carry.prune("tool");
        carry.remember(sitting);
        // OutboxBanner at the top of the page names the entry and says where it lives.
        resetDirty();
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        setSaving(false);
        return;
      }
      const saved = outcome.saved;
      // The tool itself now joins the bag, so "assign this tool to more artisans" opens with the
      // tool already picked instead of hunting it out of a dropdown of seventy.
      carry.remember({ ...sitting, toolId: saved.id, toolName: saved.toolkitName });
      // Store each captured grid photo as media linked to the tool (the measured value is already in
      // the field). Best-effort per file so one failure doesn't lose the record.
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if a grid photo fails to store */
        }
      }
      // Android parity: each process-stage capture is stored as a numbered step (STAGE_STEP_n).
      const stageFailed: string[] = [];
      for (const [index, file] of stageFiles.entries()) {
        try {
          await uploadMediaFile({
            file: new File([file], `STAGE_STEP_${index + 1}_${file.name}`, { type: file.type, lastModified: file.lastModified }),
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Process stage step ${index + 1} for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone
          });
        } catch {
          stageFailed.push(file.name);
        }
      }
      if (stageFailed.length) {
        setError(
          `${stageFailed.length} process stage file(s) failed to upload: ${stageFailed.join(", ")}. ` +
            "The tool record was saved; re-open it to retry those files."
        );
        setSaving(false);
        return;
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "tool",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.toolkitName}`,
          location,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        if (failed.length) {
          setError(`${failed.length} of ${mediaFiles.length} file(s) failed to upload: ${failed.map((f) => f.name).join(", ")}. The record was saved; re-open it to retry those files.`);
          setSaving(false);
          return;
        }
      }
      resetDirty();
      router.push("/tools");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save tool record");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  return (
    <>
      <form ref={formRef} onSubmit={submit} onInput={markDirty} onKeyDown={handleFormEnter} className="panel grid gap-4 p-4">
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        {/*
          THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
          Every dictated control below passes `explainWhenUnavailable={false}`, including the two
          rich-text editors, because on Firefox the same honest paragraph printed eleven times down
          one form is a block of grey text nobody reads. This form carries more microphones than any
          other screen in the app, which is exactly why it may not repeat itself.

          ABOVE the grid and not inside it: the grid is up to three columns, so a paragraph mounted
          as one of its children would be a column-wide sliver beside the workshop picker.
        */}
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {/* Android parity (ToolForm): the workshop opens the form, because it is the context
              every other answer belongs to — not merely the first dropdown. */}
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          {/* Toolkit/English/craft/artisan names and place are title-cased by the API on write
              (`backend/app/services/records.py:339-354`), so the box says what will actually be
              stored — `titleCased` mounts `TitleCasedInput` itself inside the dictated box, never a
              copy of its hint. Local name is NOT: it is Devanagari/Gujarati, where capitalising means
              nothing. `markDirty()` BY HAND in every `onChange` below: a dictated phrase is a React
              state write and fires no native `input` event for the form's `onInput` to catch. */}
          <DictatedTextInput
            name="toolkitName"
            label="Toolkit name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={toolkitName}
            onChange={(next) => {
              setToolkitName(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="localName"
            label="Local name"
            explainWhenUnavailable={false}
            value={localName}
            onChange={(next) => {
              setLocalName(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="englishName"
            label="English name"
            titleCased
            explainWhenUnavailable={false}
            value={englishName}
            onChange={(next) => {
              setEnglishName(next);
              markDirty();
            }}
          />
          <Field label="Linked craft (fills craft name)">
            <Select
              name="craftId"
              value={craftId}
              onChange={(event) => {
                const next = event.target.value;
                setCraftId(next);
                const craft = craftOptions.find((c) => c.id === next);
                if (craft) setCraftName(craft.name);
                // Drop the artisan ONLY when this form actually knows they practise a different
                // craft — never merely because it cannot see them. The distinction, and the silent
                // link deletion that made it necessary, are argued in `forms/recordPickers`.
                if (craftChangeClearsArtisan({ nextCraftId: next, artisanId, artisans })) {
                  setArtisanId("");
                }
                markDirty();
              }}
            >
              {/* "Unlinked" must mean unlinked. It is the placeholder a browser falls back to when
                  `value` matches no <option>, so it doubled as "linked to a craft that is not on
                  page one" until `craftOptions` carried that craft — see `offPageCraft` above. */}
              <option value="">Unlinked / type below</option>
              {craftOptions.map((craft) => (
                <option key={craft.id} value={craft.id}>
                  {craft.name}
                </option>
              ))}
            </Select>
            <CappedListNotice cuts={[craftCut]} />
          </Field>
          <DictatedTextInput
            name="craftName"
            label="Craft name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={craftName}
            onChange={(next) => {
              setCraftName(next);
              markDirty();
            }}
          />
          <Field label="Linked artisan (fills artisan + place)">
            <Select
              name="artisanId"
              value={artisanId}
              onChange={(event) => {
                const next = event.target.value;
                setArtisanId(next);
                const artisan = artisans.find((a) => a.id === next);
                if (artisan) {
                  setArtisanName(artisan.name);
                  setPlace(artisan.place);
                  // An explicit pick replaces the remembered context and retires the banner: from
                  // here on the artisan on screen is the researcher's own choice, not a suggestion.
                  carry.remember(
                    { artisanId: artisan.id, artisanName: artisan.name, place: artisan.place, craftId, craftName },
                    { explicit: true }
                  );
                }
                markDirty();
              }}
              disabled={!craftId}
            >
              <option value="">{craftId ? "Unlinked / type below" : "Select a linked craft first"}</option>
              {artisansForCraft.map((artisan) => (
                <option key={artisan.id} value={artisan.id}>
                  {artisanOptionLabel(artisan)}
                </option>
              ))}
            </Select>
            {/* A claim about the REPOSITORY, so it waits for the repository's answer about THIS
                craft. Printed off a stale roster it said "no artisans are linked to this craft yet"
                over a craft with a dozen of them — the silent-emptiness failure in one sentence, and
                the reason `artisansLoadedForCraft` records WHICH craft the loaded rows are for
                rather than a bare boolean. */}
            {craftId && artisansLoadedForCraft === craftId && artisansForCraft.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">No artisans are linked to this craft yet.</p>
            ) : null}
            <CappedListNotice cuts={[craftId ? craftArtisanCut : null]} />
          </Field>
          <DictatedTextInput
            name="artisanName"
            label="Artisan name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={artisanName}
            onChange={(next) => {
              setArtisanName(next);
              markDirty();
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
              markDirty();
            }}
          />
          {/* STILL A SINGLE-LINE BOX, and still disagreeing with the review registry — see the note
              above the two editors below. A microphone changes what the box can DO, not what the
              field IS. */}
          <DictatedTextInput
            name="processUsedIn"
            label="Process used in"
            explainWhenUnavailable={false}
            value={processUsedIn}
            onChange={(next) => {
              setProcessUsedIn(next);
              markDirty();
            }}
          />
          {/* Free prose — "mango wood with an iron collar" — neither a measurement nor a vocabulary,
              and the answer most likely to be given while holding the tool. */}
          <DictatedTextInput
            name="material"
            label="Material"
            explainWhenUnavailable={false}
            value={material}
            onChange={(next) => {
              setMaterial(next);
              markDirty();
            }}
          />
          <Field label="Years in use">
            <TextInput name="yearsInUse" type="number" min={0} defaultValue={initial?.yearsInUse ?? ""} />
          </Field>
          {/*
            `min={0}` ON EVERY MEASUREMENT AND PRICE ON THIS FORM, AND IT IS HALF OF A PAIR.

            Every one of these boxes accepted a negative and stored it. A negative length is not a
            measurement, and the sibling application's workshop registry declares the fields these
            are carried into as non-negative — so this product was accepting a quantity that product
            would refuse on a row it filled in FROM here. The server half landed with it (`ge=0`
            across `ToolCreate`/`ToolUpdate`, `backend/app/schemas/records.py:572-592`), and the two
            are deliberately not interchangeable: `min` refuses the value IN THE BOX, by name, before
            a request is made, while `ge=0` refuses it for every client that is not this one.

            IT IS `min`, NOT A PATTERN OR A CHECK IN `submit`. A native number input with `min={0}`
            blocks the submit and names the field; a JS check would have to invent its own error
            surface, and this form's error treatments are chosen by meaning — "you typed a negative
            into a box that is on screen" is a field-level refusal, which is exactly what the browser
            already draws.

            A BEHAVIOUR CHANGE ON EDIT, AND KNOWINGLY SO: this form posts the WHOLE payload back on
            an edit, so a row that already holds a negative will refuse every save until the number
            is corrected — including a save that was only fixing the village name. The audit query
            that finds those rows is written out beside the server bound.
          */}
          <Field label="Height">
            <TextInput
              name="height"
              type="number"
              step="0.01"
              min={0}
              aria-describedby={heightHelpId}
              value={height}
              onChange={(event) => setHeight(event.target.value)}
            />
          </Field>
          <Field label="Width">
            <TextInput name="width" type="number" step="0.01" min={0} defaultValue={initial?.width ?? ""} />
          </Field>
          <Field label="Length (inches)">
            <TextInput
              name="lengthInches"
              type="number"
              step="0.01"
              min={0}
              value={length}
              onChange={(event) => setLength(event.target.value)}
            />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput
              name="breadthInches"
              type="number"
              step="0.01"
              min={0}
              value={breadth}
              onChange={(event) => setBreadth(event.target.value)}
            />
          </Field>
          <Field label="Height (inches)">
            <TextInput
              name="heightInches"
              type="number"
              step="0.01"
              min={0}
              aria-describedby={heightHelpId}
              value={heightInches}
              onChange={(event) => setHeightInches(event.target.value)}
            />
          </Field>
          {/*
            THE DISAMBIGUATION, SPANNING THE ROW SO IT SITS UNDER BOTH BOXES IT DESCRIBES.

            Named by `aria-describedby` from "Height" and from "Height (inches)" — see `heightHelpId`
            above for why it is a paragraph outside both `Field`s rather than a hint inside either.
            It says which box the grid panel fills, because that is the question a researcher who has
            just pressed "Document using grid" is actually asking, and it says what the unit-less one
            is for without calling it deprecated: rows hold real values in it and somebody has to be
            able to correct one.
          */}
          <p id={heightHelpId} className="text-xs leading-5 text-ink-500 md:col-span-2 xl:col-span-3">
            Two height boxes, on purpose. <strong className="font-semibold">Height (inches)</strong> is the one
            to fill in: it is the height the grid-measurement panel below writes, and the only one whose unit
            the record can state. <strong className="font-semibold">Height</strong> is the older box, kept
            because tools already hold values in it and nothing recorded what unit those were measured in —
            leave it empty unless you are correcting one of those.
          </p>
          <Field label="Thickness">
            <TextInput name="thickness" type="number" step="0.01" min={0} defaultValue={initial?.thickness ?? ""} />
          </Field>
          <Field label="Weight">
            <TextInput name="weight" type="number" step="0.01" min={0} defaultValue={initial?.weight ?? ""} />
          </Field>
          <Field label="Radius">
            <TextInput name="radius" type="number" step="0.01" min={0} defaultValue={initial?.radius ?? ""} />
          </Field>
        </div>
        <GridMeasurement
          includeHeight
          onLengthBreadth={(l, b) => {
            if (l) setLength(l);
            if (b) setBreadth(b);
            markDirty();
          }}
          /*
            THE READING IS IN INCHES, SO IT GOES IN THE BOX THAT SAYS INCHES.

            This wrote `setHeight` — the unit-less column — until the record-parity sweep, which is
            how every grid-measured tool height in this repository came to be stored with no
            recoverable unit. Nothing reported it: the number was right, the box was filled, the save
            returned 200, and only the UNIT was lost. See `heightInches` above and migration
            20260913120100. ProductForm's identical panel has always written its own `heightInches`;
            this is the two forms agreeing again.
          */
          onHeight={(value) => {
            setHeightInches(value);
            markDirty();
          }}
          onFilesChange={(files) => {
            setGridFiles(files);
            markDirty();
          }}
        />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Field label="Maker">
            <Select name="maker" defaultValue={initial?.maker ?? "UNKNOWN"} onChange={markDirty}>
              {makerOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Tradition type">
            <Select name="traditionType" defaultValue={initial?.traditionType ?? "UNKNOWN"} onChange={markDirty}>
              {traditionOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Replacement cost">
            <TextInput name="replacementCost" type="number" step="0.01" min={0} defaultValue={initial?.replacementCost ?? ""} />
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {/*
            THE TWO NARRATIVE BOXES ON THIS FORM. Both were already `<TextArea>` (`min-h-24`, no
            length cap) and both hold prose a researcher would rather speak than thumb in. `remarks`
            is also searched by a raw `contains` at `tools.py:99`, which is the reason
            `RichTextField` keeps writing plain prose until something is actually formatted.

            `processUsedIn` above is still a SINGLE-LINE box even though the review registry
            (`components/review/reviewEditFields.ts`) marks it `multiline: true` and the CSV exports
            it as "Usage". It now carries a microphone (line 611) — that changes what the
            box can DO, not what the field IS, so the disagreement is untouched and still open.
            Recorded here so the next person does not read either half as an oversight. The old
            version of this paragraph cited "line 487" for that box, which had drifted by seven lines
            before this edit and is the standing proof that a `file:line` inside a `.tsx` is checked
            by nothing: `docs/tools/check-docs.mjs` verifies citations in `.md` files only.
          */}
          <RichTextField
            name="suggestionsForToolImprovement"
            label="Suggestions for improvement"
            defaultValue={initial?.suggestionsForToolImprovement ?? ""}
            className="md:col-span-2"
            // Said once at the top of this form by `DictationUnavailableNotice`.
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="remarks"
            label="Remarks"
            defaultValue={initial?.remarks ?? ""}
            className="md:col-span-2"
            // Said once at the top of this form by `DictationUnavailableNotice`.
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        <MediaCaptureField
          files={stageFiles}
          onFilesChange={(files) => {
            setStageFiles(files);
            markDirty();
          }}
          title="Process stages"
          description="Document each step of making or using this tool. Captures are archived in order as STAGE_STEP_1, STAGE_STEP_2, …"
        />
        {initial ? <ExistingMedia linkedRecordType="tool" linkedRecordId={initial.id} /> : null}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            markDirty();
          }}
          title="Tool media"
          description="Attach or capture tool images, videos, audio notes, and documents. Image EXIF is retained and summarized in remarks."
        />
        <LocationFields initial={initialLocation} onDirty={markDirty} />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : initial ? "Update tool" : "Save tool"}
          </button>
        </div>
      </form>
      <UnsavedChangesDialog
        open={backPromptOpen}
        saving={saving}
        onKeepEditing={() => setBackPromptOpen(false)}
        onDiscard={() => {
          setBackPromptOpen(false);
          resetDirty();
          router.back();
        }}
        onSave={() => {
          setBackPromptOpen(false);
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}
