"use client";

import { useId, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { DraftOfferCard, useFormDraft } from "@/components/forms/FormDraft";
import { cmTextFromInches, inchesTextFromCm, propagate } from "@/components/forms/dimensionUnits";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import {
  forgetAcceptance,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance,
  type AcceptedMeasurements
} from "@/components/forms/measurementMethods";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { CardCodeLookup } from "@/components/artisans/CardCodeLookup";
import {
  artisanForCard,
  craftNameFor,
  craftsChangeClearsArtisans,
  craftsKey,
  sortArtisansByCraft,
  useCraftAndArtisanOptions
} from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { LiveCompleteness, photosPicked } from "@/components/records/LiveCompleteness";
import { GridMeasurement, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { RecordPhotoMeasure, type MeasureColumn } from "@/components/media/RecordPhotoMeasure";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { statusLabel } from "@/components/StatusBadge";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import {
  locationFromForm,
  recordedAtFromForm,
  recordedTimezoneFromForm,
  sameIdSet,
  useFocusField,
  useUnsavedChanges
} from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { toolBody } from "@/lib/recordPayloads";
import { canManageCrafts, hasRank } from "@/lib/permissions";
import type { Artisan, RecordStatus, ToolDocumentation } from "@/lib/types";
import { MATERIAL_FAMILY_OPTIONS, TRADITION_OPTIONS, traditionChoice } from "@/lib/standardValues";
import { makerOptions } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { CarryForwardCards } from "@/components/CarryForwardCards";
import { artisanStatedSource } from "@/components/forms/statedPrefill";
import { failedFilesOf, RetryFailedUploads, type FailedUpload } from "@/components/media/RetryFailedUploads";
import { countNoun } from "@/lib/format";
import { recordingFilename, splitExtension } from "@/lib/naming";
import { mediaKindWord } from "@/lib/media";

function stageStepFile(file: File, index: number, toolName: string | null | undefined): File {
  const name = recordingFilename("Tool", splitExtension(file.name)[1], {
    capturedAt: new Date(file.lastModified || Date.now()),
    subject: toolName,
    what: `Step-${index + 1}-${mediaKindWord(file)}`,
    sequence: "01"
  });
  return new File([file], name, { type: file.type, lastModified: file.lastModified });
}

function artisanOptionLabel(artisan: Artisan, craftName: string) {
  const name = artisan.name?.trim() || "Unnamed artisan";
  return [craftName.trim(), name, artisan.place?.trim()].filter(Boolean).join(" · ");
}

function storedCraftIds(tool?: ToolDocumentation): string[] {
  const linked = (tool?.craftLinks ?? []).map((link) => link.craftId).filter(Boolean);
  const head = tool?.craftId ?? "";
  if (!head) return linked;
  return [head, ...linked.filter((id) => id !== head)];
}

function storedArtisanIds(tool?: ToolDocumentation): string[] {
  const linked = (tool?.artisanLinks ?? []).map((link) => link.artisanId).filter(Boolean);
  const head = tool?.artisanId ?? "";
  if (!head) return linked;
  return [head, ...linked.filter((id) => id !== head)];
}

const MEASURE_COLUMNS: MeasureColumn[] = [
  { key: "lengthInches", label: "Length (inches)", unit: "in" },
  { key: "breadthInches", label: "Breadth (inches)", unit: "in" },
  { key: "heightInches", label: "Height (inches)", unit: "in" }
];

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
            <option key={status} value={status}>{statusLabel(status)}</option>
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
        title="A reviewer sets the status."
      >
        {text}
      </span>
    </div>
  );
}

type ToolRetry = { files: File[]; upload: (files: File[]) => Promise<{ failed: FailedUpload[] }> };

const TOOL_DRAFT_FORM_FIELDS = ["yearsInUse", "thickness", "weight", "radius", "maker", "traditionType", "materialFamily", "replacementCost", "suggestionsForToolImprovement", "remarks"];

export function ToolForm({ initial }: { initial?: ToolDocumentation }) {
  const router = useRouter();
  const [run, setRun] = useState(0);
  const [saved, setSaved] = useState<ToolDocumentation | null>(null);
  const [retry, setRetry] = useState<ToolRetry | null>(null);
  const leaveForList = () => {
    router.push("/tools");
    router.refresh();
  };
  if (saved && !initial) {
    return (
      <div className="grid gap-6">
        {retry ? <RetryFailedUploads files={retry.files} upload={retry.upload} onDone={() => setRetry(null)} /> : null}
        <div className="panel p-4">
          <p className="text-sm font-medium text-ink">Saved &ldquo;{saved.toolkitName}&rdquo;.</p>
          <ButtonRow align="start" className="mt-3">
            <button
              type="button"
              className="field-button"
              onClick={() => {
                setSaved(null);
                setRetry(null);
                setRun((value) => value + 1);
              }}
            >
              Add another tool
            </button>
            <button type="button" className="field-button-secondary" onClick={leaveForList}>
              Back to tools
            </button>
          </ButtonRow>
        </div>
        <CarryForwardCards
          heading="Next"
          context={{
            artisanId: saved.artisanId ?? null,
            artisanName: saved.artisanName,
            place: saved.place,
            craftId: saved.craftId ?? null,
            craftName: saved.craftName,
            toolId: saved.id,
            toolName: saved.toolkitName,
            workshopId: saved.workshopId ?? null
          }}
        />
      </div>
    );
  }
  return (
    <>
      {retry && initial ? (
        <RetryFailedUploads
          files={retry.files}
          upload={retry.upload}
          onDone={() => {
            setRetry(null);
            leaveForList();
          }}
        />
      ) : null}
      <ToolFormBody
        key={run}
        initial={initial}
        onSaved={(record, failedRetry) => {
          setRetry(failedRetry);
          if (initial) {
            if (!failedRetry) leaveForList();
            return;
          }
          setSaved(record);
          if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        }}
      />
    </>
  );
}

function ToolFormBody({
  initial,
  onSaved
}: {
  initial?: ToolDocumentation;
  onSaved: (saved: ToolDocumentation, retry: ToolRetry | null) => void;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const formRef = useRef<HTMLFormElement>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [craftIds, setCraftIds] = useState<string[]>(() => {
    const seeded = storedCraftIds(initial);
    if (seeded.length) return seeded;
    const carried = searchParams.get("craftId");
    return carried ? [carried] : [];
  });
  const [artisanIds, setArtisanIds] = useState<string[]>(() => {
    const seeded = storedArtisanIds(initial);
    if (seeded.length) return seeded;
    const carried = searchParams.get("artisanId");
    return carried ? [carried] : [];
  });
  const craftId = craftIds[0] ?? "";
  const artisanId = artisanIds[0] ?? "";
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(initial?.artisanName ?? searchParams.get("artisanName") ?? "");
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  const [toolkitName, setToolkitName] = useState(initial?.toolkitName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [englishName, setEnglishName] = useState(initial?.englishName ?? "");
  const mirrorArmed = useRef(
    initial ? (initial.englishName ?? "").trim() === "" || (initial.englishName ?? "") === (initial.toolkitName ?? "") : true
  );
  function applyToolkitName(next: string, { user }: { user: boolean }) {
    setToolkitName(next);
    if (user) markDirty();
    if (mirrorArmed.current) setEnglishName(next);
  }
  const [processUsedIn, setProcessUsedIn] = useState(initial?.processUsedIn ?? "");
  const [material, setMaterial] = useState(initial?.material ?? "");
  const [stageFiles, setStageFiles] = useState<File[]>([]);
  useFocusField();
  const completenessValues = useMemo(() => ({ artisanId: artisanId || null }), [artisanId]);
  const completenessCounts = useMemo(
    () => ({ photos: photosPicked(mediaFiles) + photosPicked(stageFiles) }),
    [mediaFiles, stageFiles]
  );
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");
  const [width, setWidth] = useState(initial?.width != null ? String(initial.width) : "");
  const [heightInches, setHeightInches] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  const [accepted, setAccepted] = useState<AcceptedMeasurements>(NO_ACCEPTED_MEASUREMENTS);
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  const [measurePhoto, setMeasurePhoto] = useState<{ file: File; isGrid: boolean } | null>(null);
  const heightHelpId = useId();
  const {
    artisans,
    crafts,
    referenceState,
    craftArtisanCut,
    artisansLoadedForCrafts
  } = useCraftAndArtisanOptions({ craftIds, artisanIds });
  const craftOptions = crafts;
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  const initialLocation = initial
    ? ((initial as ToolDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });
  const [restored, setRestored] = useState<Record<string, string>>({});
  const [formKey, setFormKey] = useState(0);
  const [draftTick, setDraftTick] = useState(0);
  const draft = useFormDraft({
    kind: "tool",
    recordId: initial?.id ?? null,
    parentId: initial ? null : searchParams.get("artisanId"),
    parentLabel: initial ? null : searchParams.get("artisanName"),
    dirty,
    label: toolkitName.trim(),
    fields: {
      toolkitName,
      localName,
      englishName,
      craftName,
      artisanName,
      place,
      processUsedIn,
      material,
      length,
      breadth,
      height,
      width,
      heightInches,
      craftIds,
      artisanIds,
      mediaFiles,
      workshopId: workshop.workshopId,
      tick: draftTick
    },
    collect: () => {
      const form = formRef.current ? new FormData(formRef.current) : null;
      const out: Record<string, string> = {};
      for (const name of TOOL_DRAFT_FORM_FIELDS) {
        const value = form?.get(name);
        if (typeof value === "string") out[`form.${name}`] = value;
      }
      return out;
    },
    onRestore: (fields) => {
      const text = (key: string) => (typeof fields[key] === "string" ? (fields[key] as string) : null);
      const setters: Array<[string, (value: string) => void]> = [
        ["toolkitName", setToolkitName],
        ["localName", setLocalName],
        ["englishName", setEnglishName],
        ["craftName", setCraftName],
        ["artisanName", setArtisanName],
        ["place", setPlace],
        ["processUsedIn", setProcessUsedIn],
        ["material", setMaterial],
        ["length", setLength],
        ["breadth", setBreadth],
        ["height", setHeight],
        ["width", setWidth],
        ["heightInches", setHeightInches]
      ];
      for (const [key, set] of setters) {
        const value = text(key);
        if (value !== null) set(value);
      }
      const ids = (key: string) =>
        Array.isArray(fields[key]) ? (fields[key] as unknown[]).filter((id): id is string => typeof id === "string") : null;
      const restoredCrafts = ids("craftIds");
      if (restoredCrafts) setCraftIds(restoredCrafts);
      const restoredArtisans = ids("artisanIds");
      if (restoredArtisans) setArtisanIds(restoredArtisans);
      if (Array.isArray(fields.mediaFiles)) setMediaFiles(fields.mediaFiles as File[]);
      const workshopId = text("workshopId");
      if (workshopId) workshop.setWorkshopId(workshopId);
      const seeded: Record<string, string> = {};
      for (const [key, value] of Object.entries(fields)) {
        if (key.startsWith("form.") && typeof value === "string") seeded[key.slice(5)] = value;
      }
      setRestored(seeded);
      setFormKey((key) => key + 1);
    }
  });

  const typeInches =
    (set: (value: string) => void, key: string, setPartner?: (value: string) => void) =>
    (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
      if (setPartner) propagate(event.target.value, cmTextFromInches, setPartner);
    };

  const typeCm =
    (set: (value: string) => void, setPartner: (value: string) => void) =>
    (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      propagate(event.target.value, inchesTextFromCm, setPartner);
    };

  const carry = useCarryContext({
    enabled: !isEdit,
    scopes: [carryScope("artisan", referenceState, artisans), carryScope("craft", referenceState, craftOptions)],
    applies: ["craft", "artisan", "workshop"],
    onApply: (context) => {
      if (context.craftId) setCraftIds([context.craftId]);
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanIds([context.artisanId]);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  function clearCarriedContext() {
    carry.change();
    setCraftIds([]);
    setCraftName("");
    setArtisanIds([]);
    setArtisanName("");
    setPlace("");
  }

  const selectedCrafts = useMemo(
    () => craftOptions.filter((craft) => craftIds.includes(craft.id)),
    [craftOptions, craftIds]
  );
  const artisansForCrafts = useMemo(() => {
    const offered = craftIds.length
      ? artisans.filter((artisan) => (artisan.craftId && craftIds.includes(artisan.craftId)) || artisanIds.includes(artisan.id))
      : artisans.filter((artisan) => artisanIds.includes(artisan.id));
    return sortArtisansByCraft(offered, selectedCrafts);
  }, [artisans, artisanIds, craftIds, selectedCrafts]);

  function joinCraftNames(ids: readonly string[]) {
    return ids
      .map((id) => craftOptions.find((craft) => craft.id === id)?.name)
      .filter(Boolean)
      .join(", ");
  }

  function syncArtisanColumns(next: readonly string[], previous: readonly string[]) {
    const head = next[0] ?? "";
    if (!head || head === (previous[0] ?? "")) return;
    const first = artisans.find((artisan) => artisan.id === head);
    if (!first) return;
    setArtisanName(first.name);
    setPlace(first.place);
  }

  function onCraftsChanged(next: string[]) {
    const removed = craftIds.filter((id) => !next.includes(id));
    if (removed.length) {
      const dropped = craftsChangeClearsArtisans({
        nextCraftIds: next,
        removedCraftIds: removed,
        artisanIds,
        artisans
      });
      if (dropped.length) {
        const kept = artisanIds.filter((id) => !dropped.includes(id));
        setArtisanIds(kept);
        syncArtisanColumns(kept, artisanIds);
      }
    }
    setCraftIds(next);
    const joined = joinCraftNames(next);
    if (joined) setCraftName(joined);
    markDirty();
  }

  function handleBack() {
    if (dirty) setBackPromptOpen(true);
    else router.back();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    try {
      const exifItems = await collectExifMetadata(
        [...Object.values(gridFiles), measurePhoto?.file, ...stageFiles, ...mediaFiles].filter(Boolean) as File[]
      );
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const craftLinksChanged = !initial || !sameIdSet(craftIds, storedCraftIds(initial));
      const artisanLinksChanged = !initial || !sameIdSet(artisanIds, storedArtisanIds(initial));
      const payload = toolBody(form, {
        height,
        width,
        length,
        breadth,
        heightInches,
        accepted,
        artisanId,
        craftId,
        craftIds,
        artisanIds,
        craftLinksChanged,
        artisanLinksChanged,
        workshopId: workshop.workshopId,
        initialStatus: initial?.status,
        exifItems,
        exifRemark
      });
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
          })),
          ...(measurePhoto
            ? [
                {
                  files: [measurePhoto.file],
                  linkedRecordType: "tool",
                  caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${payload.toolkitName || "tool"}`,
                  location,
                  recordedAt,
                  recordedTimezone,
                }
              ]
            : []),
          ...stageFiles.map((file, index) => ({
            files: [stageStepFile(file, index, payload.toolkitName)],
            linkedRecordType: "tool",
            caption: `Process stage step ${index + 1} for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
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
      const sitting = {
        artisanId,
        artisanName: payload.artisanName,
        place: payload.place,
        craftId,
        craftName: craftOptions.find((craft) => craft.id === craftId)?.name ?? craftName,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      if (outcome.queued) {
        carry.prune("tool");
        carry.remember(sitting);
        draft.clear();
        resetDirty();
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        setSaving(false);
        return;
      }
      const saved = outcome.saved;
      carry.remember({ ...sitting, toolId: saved.id, toolName: saved.toolkitName });
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
          });
        } catch {
        }
      }
      if (measurePhoto) {
        try {
          await uploadMediaFile({
            file: measurePhoto.file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
          });
        } catch {
        }
      }
      const stageFailed: string[] = [];
      for (const [index, file] of stageFiles.entries()) {
        try {
          await uploadMediaFile({
            file: stageStepFile(file, index, saved.toolkitName),
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
          `Saved, but ${countNoun(stageFailed.length, "stage file")} didn’t upload: ${stageFailed.join(", ")}. Reopen to retry.`
        );
        setSaving(false);
        return;
      }
      if (mediaFiles.length) {
        const uploadTo = (files: File[]) =>
          uploadMediaBatch({
            files,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Field media for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
            onProgress: setUploadProgress
          });
        const { failed } = await uploadTo(mediaFiles);
        setUploadProgress(null);
        if (failed.length) {
          draft.clear();
          resetDirty();
          onSaved(saved, { files: failedFilesOf(mediaFiles, failed), upload: uploadTo });
          setSaving(false);
          return;
        }
      }
      draft.clear();
      resetDirty();
      onSaved(saved, null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save tool record");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  return (
    <>
      <form
        key={formKey}
        ref={formRef}
        onSubmit={submit}
        onInput={() => {
          markDirty();
          setDraftTick((tick) => tick + 1);
        }}
        onKeyDown={handleFormEnter}
        className="panel grid gap-4 p-4"
      >
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <DraftOfferCard draft={draft} />
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        <LiveCompleteness kind="tool" formRef={formRef} stored={initial} values={completenessValues} counts={completenessCounts} />
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          <DictatedTextInput
            name="toolkitName"
            label="Toolkit name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={toolkitName}
            onChange={(next) => applyToolkitName(next, { user: true })}
          />
          <div className="min-w-0" data-field="localName">
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
          </div>
          <DictatedTextInput
            name="englishName"
            label="English name"
            titleCased
            explainWhenUnavailable={false}
            value={englishName}
            onChange={(next) => {
              setEnglishName(next);
              mirrorArmed.current = false;
              markDirty();
            }}
          />
          <div className="min-w-0 md:col-span-2" data-field="craft">
            <FieldBlock label="Linked crafts" required>
              <CraftSelect
                multiple
                name="craftIds"
                ariaLabel="Linked crafts"
                required
                value={craftIds}
                canAddCraft={canManageCrafts(user)}
                onChange={(next) => onCraftsChanged(next)}
              />
            </FieldBlock>
          </div>
          <div className="min-w-0" data-field="artisan">
          <Field label="Linked artisans (fills artisan + place)">
            <MultiSelectDropdown
              values={artisanIds}
              onChange={(next) => {
                const previous = artisanIds;
                setArtisanIds(next);
                syncArtisanColumns(next, previous);
                const first = next.length ? artisans.find((artisan) => artisan.id === next[0]) : undefined;
                if (first) {
                  carry.remember(
                    {
                      artisanId: first.id,
                      artisanName: first.name,
                      place: first.place,
                      craftId,
                      craftName: craftOptions.find((craft) => craft.id === craftId)?.name ?? craftName
                    },
                    { explicit: true }
                  );
                }
                markDirty();
              }}
              searchable
              disabled={craftIds.length === 0}
              placeholder={craftIds.length ? "Select artisans" : "Select a linked craft first"}
              emptyLabel={craftIds.length ? "No artisans for these crafts" : "Select a linked craft first"}
              options={artisansForCrafts.map((artisan) => ({
                value: artisan.id,
                label: artisanOptionLabel(artisan, craftNameFor(artisan, selectedCrafts))
              }))}
            />
            {craftIds.length > 0 && artisansLoadedForCrafts === craftsKey(craftIds) && artisansForCrafts.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">
                No artisans are linked to {craftIds.length === 1 ? "this craft" : "these crafts"} yet.
              </p>
            ) : null}
            <CappedListNotice cuts={[craftIds.length ? craftArtisanCut : null]} />
          </Field>
          <CardCodeLookup
            compact
            actionLabel="Add"
            onFound={(found) => {
              void artisanForCard(found.id)
                .then((artisan) => {
                  if (artisan.craftId && !craftIds.includes(artisan.craftId)) setCraftIds([...craftIds, artisan.craftId]);
                  if (!artisanIds.includes(artisan.id)) {
                    const next = [...artisanIds, artisan.id];
                    setArtisanIds(next);
                    if (next.length === 1) {
                      setArtisanName(artisan.name);
                      setPlace(artisan.place);
                    }
                  }
                  markDirty();
                })
                .catch((err) => setError(err instanceof Error ? err.message : "Couldn't add that artisan."));
            }}
          />
          </div>
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
          <div className="min-w-0" data-field="material">
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
          </div>
          <Field label="Material type">
            <Select name="materialFamily" defaultValue={restored.materialFamily ?? initial?.materialFamily ?? ""} onChange={markDirty}>
              <option value="">Select material type</option>
              {MATERIAL_FAMILY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Years in use">
            <TextInput name="yearsInUse" type="number" min={0} defaultValue={restored.yearsInUse ?? initial?.yearsInUse ?? ""} />
          </Field>
          <Field label="Height (cm)">
            <TextInput
              name="height"
              type="number"
              step="0.01"
              min={0}
              aria-describedby={heightHelpId}
              value={height}
              onChange={typeCm(setHeight, setHeightInches)}
            />
          </Field>
          <Field label="Width (cm)">
            <TextInput
              name="width"
              type="number"
              step="0.01"
              min={0}
              value={width}
              onChange={typeCm(setWidth, setBreadth)}
            />
          </Field>
          <Field label="Length (inches)" fieldKey="dimensions">
            <TextInput
              name="lengthInches"
              type="number"
              step="0.01"
              min={0}
              value={length}
              onChange={typeInches(setLength, "lengthInches")}
            />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput
              name="breadthInches"
              type="number"
              step="0.01"
              min={0}
              value={breadth}
              onChange={typeInches(setBreadth, "breadthInches", setWidth)}
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
              onChange={typeInches(setHeightInches, "heightInches", setHeight)}
            />
          </Field>
          <p id={heightHelpId} className="text-xs leading-5 text-ink-500 md:col-span-2 xl:col-span-3">
            <strong className="font-semibold">Height (cm)</strong> and{" "}
            <strong className="font-semibold">Height (inches)</strong> fill each other (1&nbsp;inch = 2.54&nbsp;cm), as do{" "}
            <strong className="font-semibold">Width (cm)</strong> and{" "}
            <strong className="font-semibold">Breadth (inches)</strong>;{" "}
            <strong className="font-semibold">Length (inches)</strong> has no centimetre box. Older records
            may have two numbers that disagree.
          </p>
          <Field label="Thickness">
            <TextInput name="thickness" type="number" step="0.01" min={0} defaultValue={restored.thickness ?? initial?.thickness ?? ""} />
          </Field>
          <Field label="Weight">
            <TextInput name="weight" type="number" step="0.01" min={0} defaultValue={restored.weight ?? initial?.weight ?? ""} />
          </Field>
          <Field label="Radius">
            <TextInput name="radius" type="number" step="0.01" min={0} defaultValue={restored.radius ?? initial?.radius ?? ""} />
          </Field>
        </div>
        <RecordPhotoMeasure
          columns={MEASURE_COLUMNS}
          values={{ lengthInches: length, breadthInches: breadth, heightInches }}
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            else if (key === "breadthInches") {
              setBreadth(text);
              propagate(text, cmTextFromInches, setWidth);
            }
            else if (key === "heightInches") {
              setHeightInches(text);
              propagate(text, cmTextFromInches, setHeight);
            }
            setAccepted((current) => rememberAcceptance(current, key, text, method));
            markDirty();
          }}
          onPhotoChange={(photo) => {
            setMeasurePhoto(photo);
            if (photo) markDirty();
          }}
        />
        <section className="grid gap-2 rounded-lg border border-line-200 bg-card p-4">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-ink-900">Estimate with AI</h3>
            <span className="rounded-full border border-amber-500 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
              Needs a connection
            </span>
          </div>
          <p className="text-xs leading-5 text-ink-500">
            An <strong>estimate</strong>, not a measurement. Use the panel above when a grid or ruler is visible.
          </p>
          <GridMeasurement
            includeHeight
            onLengthBreadth={(l, b, method) => {
              if (l) {
                setLength(l);
                setAccepted((current) => rememberAcceptance(current, "lengthInches", l, method));
              }
              if (b) {
                setBreadth(b);
                propagate(b, cmTextFromInches, setWidth);
                setAccepted((current) => rememberAcceptance(current, "breadthInches", b, method));
              }
              markDirty();
            }}
            onHeight={(value, method) => {
              setHeightInches(value);
              propagate(value, cmTextFromInches, setHeight);
              setAccepted((current) => rememberAcceptance(current, "heightInches", value, method));
              markDirty();
            }}
            onFilesChange={(files) => {
              setGridFiles(files);
              markDirty();
            }}
          />
        </section>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Field label="Maker" fieldKey="maker">
            <Select name="maker" defaultValue={restored.maker ?? initial?.maker ?? "UNKNOWN"} onChange={markDirty}>
              {makerOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Tradition type" fieldKey="traditionType">
            <Select name="traditionType" defaultValue={traditionChoice(restored.traditionType ?? initial?.traditionType)} onChange={markDirty}>
              <option value="">Select tradition type</option>
              {TRADITION_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Replacement cost" fieldKey="replacementCost">
            <TextInput name="replacementCost" type="number" step="0.01" min={0} defaultValue={restored.replacementCost ?? initial?.replacementCost ?? ""} />
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          <RichTextField
            name="suggestionsForToolImprovement"
            label="Suggestions for improvement"
            defaultValue={restored.suggestionsForToolImprovement ?? initial?.suggestionsForToolImprovement ?? ""}
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="remarks"
            label="Remarks"
            defaultValue={restored.remarks ?? initial?.remarks ?? ""}
            className="md:col-span-2"
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
          description="One photo per step, in order."
        />
        <div className="grid gap-4" data-field="photo">
          {initial ? <ExistingMedia linkedRecordType="tool" linkedRecordId={initial.id} /> : null}
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              markDirty();
            }}
            title="Tool media"
            description="Photos, video, audio and documents."
          />
        </div>
        <LocationFields
          initial={initialLocation}
          onDirty={markDirty}
          prefillFrom={initial ? null : artisanStatedSource(artisans.find((artisan) => artisan.id === artisanIds[0]))}
        />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        <ButtonRow align="end">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : initial ? "Update tool" : "Save tool"}
          </button>
        </ButtonRow>
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
