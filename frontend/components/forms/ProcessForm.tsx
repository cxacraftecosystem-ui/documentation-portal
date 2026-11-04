"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Images, ListRestart, Lock, Plus } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { Field, Select, TextInput } from "@/components/FormControls";
import { FieldProvenance } from "@/components/FieldProvenance";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { exifTimeToMs, inCaptureOrder, templateStepsFrom, unnamedStepCount } from "@/components/forms/processSteps";
import { useServerSearchList } from "@/components/forms/useServerSearchList";
import { DraftOfferCard, useFormDraft } from "@/components/forms/FormDraft";
import { ComboBox } from "@/components/ui/Dropdown";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { StatusBadge } from "@/components/StatusBadge";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch, listResource } from "@/lib/api";
import { handleFormEnter } from "@/lib/formNav";
import { describePreProcess, describeProcessStep, extractImageExifMetadata, renameMediaFile, uploadMediaFile } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { Artisan, ExtraMetadata, MediaFile, ProductDocumentation, RecordStatus, User, Workshop } from "@/lib/types";
import { useConfirm } from "@/components/dialogs";
import { Toggle } from "@/components/ui/Toggle";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { ReviewNoteBanner } from "@/components/review/ReviewNoteBanner";
import { Badge } from "@/components/ui/Badge";
import { countNoun } from "@/lib/format";

export type ProcessStepRecord = {
  id: string;
  name: string;
  stepType: string;
  sortOrder?: number;
  notes?: string | null;
  media?: MediaFile[];
};

export type ProcessRecord = {
  id: string;
  name: string;
  productId: string;
  preProcessAvailable?: boolean;
  notes?: string | null;
  status: RecordStatus | string;
  steps?: ProcessStepRecord[];
  media?: MediaFile[];
  product?: ProductDocumentation | null;
  workshopId?: string | null;
  workshop?: Workshop | null;
  extraMetadata?: ExtraMetadata | null;
  createdAt: string;
  createdById?: string;
  createdBy?: User;
  clientKey?: string | null;
};

const STATUS_OPTIONS = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];

type StepUi = {
  key: string;
  serverId?: string;
  name: string;
  stepType: string;
  recordAdditional: boolean;
  notes: string;
  files: File[];
  existingMedia: MediaFile[];
  nameError?: string | null;
  template?: boolean;
};

function stepTypeLabel(stepType: string): string {
  return stepType === "SEQUENTIAL" ? "Sequential" : "Group of activities";
}

function makeKey(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function SavedMediaList({
  items,
  onRemoved,
  onError
}: {
  items: MediaFile[];
  onRemoved: (id: string) => void;
  onError: (message: string) => void;
}) {
  const [active, setActive] = useState<PreviewMedia | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const confirm = useConfirm();
  if (!items.length) return null;

  async function remove(media: MediaFile) {
    const ok = await confirm({
      title: "Remove this file?",
      body: `Remove "${media.caption || media.originalFilename}"?`,
      note: "This can’t be undone.",
      confirmLabel: "Remove file",
      tone: "danger"
    });
    if (!ok) return;
    setRemovingId(media.id);
    try {
      await apiFetch(`/media/${media.id}`, { method: "DELETE" });
      onRemoved(media.id);
    } catch (err) {
      onError(err instanceof Error ? err.message : "Unable to remove media");
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div className="grid gap-2">
      <p className="text-xs text-ink-500">Already attached:</p>
      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((media) => {
          const preview: PreviewMedia = {
            key: media.id,
            id: media.id,
            name: media.originalFilename,
            mediaType: media.mediaType,
            mimeType: media.mimeType,
            sizeBytes: media.sizeBytes,
            url: media.url,
            caption: media.caption,
            transcriptStatus: media.transcriptStatus,
            transcriptText: media.transcriptText,
            transcriptError: media.transcriptError
          };
          return (
            <MediaPreviewTile
              key={media.id}
              item={preview}
              onOpen={() => setActive(preview)}
              onRemove={removingId === media.id ? undefined : () => remove(media)}
              removeLabel="Remove"
            />
          );
        })}
      </div>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </div>
  );
}

function splitNotes(value: string): string[] {
  return value
    .split(/\n\s*\n/)
    .map((note) => note.trim())
    .filter(Boolean);
}

function MultiNoteInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  const [rows, setRows] = useState<string[]>(() => {
    const split = splitNotes(value);
    return split.length ? split : [""];
  });

  function emit(updated: string[]) {
    const next = updated.length ? updated : [""];
    setRows(next);
    onChange(
      next
        .map((note) => note.trim())
        .filter(Boolean)
        .join("\n\n")
    );
  }

  return (
    <div className="grid gap-2">
      <span className="text-sm font-semibold text-ink-900">{label}</span>
      {rows.map((note, index) => (
        <div key={index} className="grid gap-1">
          <div className="flex items-start gap-2">
            <textarea
              className="field-input min-h-16 flex-1"
              rows={2}
              placeholder={rows.length > 1 ? `Note ${index + 1}` : "Note"}
              value={note}
              onChange={(event) => emit(rows.map((n, i) => (i === index ? event.target.value : n)))}
            />
            {rows.length > 1 ? (
              <button
                type="button"
                aria-label="Remove note"
                className="field-button-secondary h-9 min-h-0 pointer-coarse:h-11 shrink-0 px-2.5"
                onClick={() => emit(rows.filter((_, i) => i !== index))}
              >
                ✕
              </button>
            ) : null}
          </div>
          <OnDeviceDictationButton
            fieldLabel={rows.length > 1 ? `${label}, note ${index + 1}` : label}
            explainWhenUnavailable={false}
            onCommit={(phrase) => {
              emit(rows.map((n, i) => (i === index ? appendDictatedPhrase(note, phrase) : n)));
            }}
          />
        </div>
      ))}
      <button type="button" className="field-button-secondary h-9 min-h-0 pointer-coarse:h-11 justify-self-start px-3 text-xs" onClick={() => emit([...rows, ""])}>
        <Plus className="h-3.5 w-3.5" aria-hidden />
        Add note
      </button>
    </div>
  );
}

export function ProcessForm({
  initial,
  onDone,
  onCancel
}: {
  initial?: ProcessRecord;
  onDone: () => void;
  onCancel: () => void;
}) {
  const { user } = useAuth();
  const isEdit = Boolean(initial);
  const canPickStatus = hasRank(user, "PROFESSOR");
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });

  const [name, setName] = useState(initial?.name ?? "");
  const [artisanId, setArtisanId] = useState(initial?.product?.artisanId ?? "");
  const [productId, setProductId] = useState(initial?.productId ?? "");
  const [preProcessAvailable, setPreProcessAvailable] = useState(initial?.preProcessAvailable ?? false);
  const [notes, setNotes] = useState(initial?.notes ?? "");
  const [status, setStatus] = useState<string>(initial?.status ?? (canPickStatus ? "APPROVED" : "PENDING"));
  const [steps, setSteps] = useState<StepUi[]>(() =>
    (initial?.steps ?? []).map((step) => ({
      key: makeKey(),
      serverId: step.id,
      name: step.name,
      stepType: step.stepType,
      recordAdditional: Boolean(step.notes && step.notes.trim()),
      notes: step.notes ?? "",
      files: [],
      existingMedia: step.media ?? []
    }))
  );
  const [preFiles, setPreFiles] = useState<File[]>([]);
  const [existingPreMedia, setExistingPreMedia] = useState<MediaFile[]>(initial?.media ?? []);

  const artisanList = useServerSearchList<Artisan>("/artisans", "artisans");
  const artisans = artisanList.items;
  const artisanListState: CarryScopeState = artisanList.state;
  const [artisanProducts, setArtisanProducts] = useState<ProductDocumentation[]>([]);
  const artisanCut = artisanList.cut;
  const [productCut, setProductCut] = useState<ListCut | null>(null);
  const [productsLoading, setProductsLoading] = useState(false);
  const [productLoadError, setProductLoadError] = useState<string | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [artisanError, setArtisanError] = useState<string | null>(null);
  const [productError, setProductError] = useState<string | null>(null);
  const [stepsError, setStepsError] = useState<string | null>(null);
  const [preMediaError, setPreMediaError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadNote, setUploadNote] = useState<string | null>(null);
  const [addMenu, setAddMenu] = useState(false);
  const [guardOpen, setGuardOpen] = useState(false);

  const statusChoices = initial?.status && !STATUS_OPTIONS.includes(String(initial.status)) ? [String(initial.status), ...STATUS_OPTIONS] : STATUS_OPTIONS;

  const offPageArtisan = useRecordOffPage<Artisan>("/artisans", artisanId, artisans);
  const artisanOptions = useMemo(
    () => (offPageArtisan ? mergeById(artisans, [offPageArtisan]) : artisans),
    [artisans, offPageArtisan]
  );

  const carriedProductRef = useRef<string | null>(null);

  const carry = useCarryContext({
    enabled: !isEdit,
    scopes: [carryScope("artisan", artisanListState, artisanOptions)],
    applies: ["artisan", "product", "workshop"],
    onApply: (context) => {
      if (context.artisanId) setArtisanId(context.artisanId);
      if (context.productId) carriedProductRef.current = context.productId;
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  const pruneCarried = carry.prune;
  function clearCarriedContext() {
    carry.change();
    carriedProductRef.current = null;
    setArtisanId("");
    setProductId("");
  }

  useEffect(() => {
    let cancelled = false;
    if (!artisanId) {
      setArtisanProducts([]);
      setProductsLoading(false);
      setProductLoadError(null);
      return;
    }
    setProductsLoading(true);
    setProductLoadError(null);
    const artisanName = artisanOptions.find((artisan) => artisan.id === artisanId)?.name?.trim();
    listResource<ProductDocumentation>("/products", { artisanId, artisanName, pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setArtisanProducts(result.items);
        setProductCut(listCut(result, "products for this artisan"));
        setProductsLoading(false);
        const carried = carriedProductRef.current;
        if (carried) {
          carriedProductRef.current = null;
          if (result.items.some((product) => product.id === carried)) {
            setProductId(carried);
            return;
          }
          pruneCarried("product");
        }
        setProductId((current) => (current && result.items.every((product) => product.id !== current) ? "" : current));
      })
      .catch((err) => {
        if (cancelled) return;
        setArtisanProducts([]);
        setProductsLoading(false);
        setProductLoadError(
          `Couldn’t load products: ${err instanceof Error ? err.message : "network error"}. Pick the artisan again.`
        );
      });
    return () => {
      cancelled = true;
    };
  }, [artisanId, artisanOptions, pruneCarried]);

  const carriedArtisanId = carry.applied?.context.artisanId ?? null;
  const carriedProductId = carry.applied?.context.productId ?? null;
  const signature = JSON.stringify({
    name,
    artisanId: artisanId && artisanId === carriedArtisanId ? "" : artisanId,
    productId: productId && productId === carriedProductId ? "" : productId,
    workshopId: workshop.touched ? workshop.workshopId : "",
    status,
    notes,
    preProcessAvailable,
    pre: preFiles.length,
    steps: steps.map((step) => ({
      id: step.serverId ?? null,
      name: step.name,
      type: step.stepType,
      notes: step.recordAdditional ? step.notes : "",
      files: step.files.length
    }))
  });
  const [initialSignature] = useState(signature);
  const dirty = !saving && signature !== initialSignature;

  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

  function updateStep(key: string, patch: Partial<StepUi>) {
    setSteps((current) => current.map((step) => (step.key === key ? { ...step, ...patch } : step)));
  }

  function addStep(stepType: string) {
    setSteps((current) => [
      ...current,
      { key: makeKey(), name: "", stepType, recordAdditional: false, notes: "", files: [], existingMedia: [] }
    ]);
    setAddMenu(false);
  }

  async function addStepsFromFiles(list: FileList | null) {
    const files = Array.from(list ?? []);
    if (!files.length) return;
    const capturedAt = await Promise.all(
      files.map(async (file) => {
        const exif = await extractImageExifMetadata(file);
        return exifTimeToMs(exif?.DateTimeOriginal ?? exif?.CreateDate ?? null);
      })
    );
    const ordered = inCaptureOrder(files, capturedAt);
    setSteps((current) => [
      ...current,
      ...ordered.map((file) => ({
        key: makeKey(),
        name: "",
        stepType: "SEQUENTIAL",
        recordAdditional: false,
        notes: "",
        files: [file],
        existingMedia: []
      }))
    ]);
  }

  const productCraftId = artisanProducts.find((product) => product.id === productId)?.craftId ?? null;
  const [templateBusy, setTemplateBusy] = useState(false);
  const [templateNote, setTemplateNote] = useState<string | null>(null);
  async function startFromLastProcess() {
    if (!productCraftId) return;
    setTemplateBusy(true);
    setTemplateNote(null);
    try {
      const page = await listResource<ProcessRecord>("/processes", { craftId: productCraftId, pageSize: 5 });
      const last = page.items.find((row) => row.id !== initial?.id && templateStepsFrom(row.steps).length > 0);
      const template = templateStepsFrom(last?.steps);
      if (!template.length) {
        setTemplateNote("No earlier process for this craft.");
        return;
      }
      setSteps((current) => [
        ...current,
        ...template.map((step) => ({
          key: makeKey(),
          name: step.name,
          stepType: step.stepType,
          recordAdditional: false,
          notes: "",
          files: [],
          existingMedia: [],
          template: true
        }))
      ]);
    } catch (err) {
      setTemplateNote(err instanceof Error ? err.message : "Couldn't load the last process.");
    } finally {
      setTemplateBusy(false);
    }
  }
  const unnamed = unnamedStepCount(steps);

  useLeaveGuard(dirty, () => setGuardOpen(true));

  const [notesSeed, setNotesSeed] = useState<{ value: string; nonce: number }>({ value: initial?.notes ?? "", nonce: 0 });
  const draft = useFormDraft({
    kind: "process",
    recordId: initial?.id ?? null,
    dirty,
    label: name.trim(),
    fields: {
      name,
      artisanId,
      productId,
      notes,
      status,
      preProcessAvailable,
      preFiles,
      workshopId: workshop.workshopId,
      steps: steps.map((step) => ({ ...step, existingMedia: [] }))
    },
    onRestore: (fields) => {
      if (typeof fields.name === "string") setName(fields.name);
      if (typeof fields.artisanId === "string") setArtisanId(fields.artisanId);
      if (typeof fields.productId === "string") {
        carriedProductRef.current = fields.productId;
        setProductId(fields.productId);
      }
      if (typeof fields.notes === "string") {
        setNotes(fields.notes);
        setNotesSeed((current) => ({ value: fields.notes as string, nonce: current.nonce + 1 }));
      }
      if (typeof fields.status === "string") setStatus(fields.status);
      if (typeof fields.preProcessAvailable === "boolean") setPreProcessAvailable(fields.preProcessAvailable);
      if (Array.isArray(fields.preFiles)) setPreFiles(fields.preFiles as File[]);
      if (typeof fields.workshopId === "string" && fields.workshopId) workshop.setWorkshopId(fields.workshopId);
      if (Array.isArray(fields.steps)) {
        const stored = new Map(steps.map((step) => [step.serverId, step.existingMedia]));
        setSteps(
          (fields.steps as StepUi[]).map((step) => ({
            ...step,
            key: makeKey(),
            existingMedia: step.serverId ? (stored.get(step.serverId) ?? []) : []
          }))
        );
      }
    }
  });

  function requestCancel() {
    if (dirty) setGuardOpen(true);
    else onCancel();
  }

  async function submit(): Promise<void> {
    setError(null);
    setNameError(null);
    setArtisanError(null);
    setProductError(null);
    setStepsError(null);
    setPreMediaError(null);

    let blocked = false;
    let focusId: string | null = null;
    if (!name.trim()) {
      setNameError("This field cannot be empty");
      blocked = true;
      focusId = focusId ?? "process-name";
    }
    if (!artisanId) {
      setArtisanError("Please select an artisan");
      blocked = true;
    }
    if (!productId) {
      setProductError("Please select a product");
      blocked = true;
    }
    if (preProcessAvailable && preFiles.length === 0 && existingPreMedia.length === 0) {
      setPreMediaError("Attach the pre-process media or uncheck the box");
      blocked = true;
    }
    if (steps.length === 0) {
      setStepsError("Add at least one step");
      blocked = true;
    }
    if (blocked) {
      setError("Please fill the required fields highlighted above.");
      setGuardOpen(false);
      if (focusId) document.getElementById(focusId)?.focus();
      return;
    }

    if (!(await workshop.confirmSubmission())) {
      setGuardOpen(false);
      return;
    }

    setSaving(true);
    try {
      const trimmedName = name.trim();
      const payload = {
        name: trimmedName,
        productId,
        workshopId: workshop.workshopId || null,
        preProcessAvailable,
        notes: notes.trim() || null,
        status,
        steps: steps.map((step, index) => ({
          id: step.serverId,
          name: step.name.trim(),
          stepType: step.stepType,
          sortOrder: index + 1,
          notes: step.recordAdditional ? step.notes.trim() || null : null
        })),
        ...(isEdit ? {} : { recordedAt: new Date().toISOString() })
      };
      const outcome = await saveOrQueue<ProcessRecord>({
        label: `Process · ${trimmedName || "Untitled"}`,
        endpoint: isEdit ? `/processes/${initial!.id}` : "/processes",
        method: isEdit ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: preProcessAvailable
              ? preFiles.map((file, index) =>
                  renameMediaFile(file, {
                    recordType: "Process",
                    recordName: trimmedName,
                    descriptor: describePreProcess(file, existingPreMedia.length + index + 1)
                  })
                )
              : [],
            linkedRecordType: "process",
            caption: `Pre-process media for ${trimmedName}`
          },
          ...steps.map((step, index) => ({
            files: step.files.map((file, fileIndex) =>
              renameMediaFile(file, {
                recordType: "Process",
                recordName: trimmedName,
                descriptor: describeProcessStep({
                  stepNumber: index + 1,
                  stepName: step.name.trim(),
                  subject: file,
                  index: step.existingMedia.length + fileIndex + 1
                })
              })
            ),
            linkedRecordType: "processstep",
            caption: `Process step ${step.name}`,
            stepIndex: index
          }))
        ]
      });
      const savedArtisan = artisanOptions.find((a) => a.id === artisanId);
      const savedProduct = artisanProducts.find((product) => product.id === productId);
      carry.remember({
        artisanId,
        artisanName: savedArtisan?.name ?? null,
        place: savedArtisan?.place ?? null,
        craftId: savedArtisan?.craftId ?? null,
        productId,
        productName: savedProduct?.productName ?? null,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      });
      draft.clear();
      if (outcome.queued) {
        setGuardOpen(false);
        setSaving(false);
        onDone();
        return;
      }
      const saved = outcome.saved;

      const failures: string[] = [];
      const totalUploads = (preProcessAvailable ? preFiles.length : 0) + steps.reduce((sum, step) => sum + step.files.length, 0);
      let uploadCount = 0;
      const note = () => {
        uploadCount += 1;
        if (totalUploads) setUploadNote(`Uploading media ${uploadCount} of ${totalUploads}…`);
      };

      if (preProcessAvailable) {
        for (let index = 0; index < preFiles.length; index += 1) {
          note();
          try {
            await uploadMediaFile({
              file: renameMediaFile(preFiles[index], {
                recordType: "Process",
                recordName: saved.name || trimmedName,
                descriptor: describePreProcess(preFiles[index], existingPreMedia.length + index + 1)
              }),
              linkedRecordType: "process",
              linkedRecordId: saved.id,
              caption: `Pre-process media for ${trimmedName}`
            });
          } catch {
            failures.push(preFiles[index].name);
          }
        }
      }
      const savedSteps = saved.steps ?? [];
      for (let index = 0; index < savedSteps.length; index += 1) {
        const serverStep = savedSteps[index];
        const local = steps[index];
        if (!local) continue;
        for (let fileIndex = 0; fileIndex < local.files.length; fileIndex += 1) {
          note();
          try {
            await uploadMediaFile({
              file: renameMediaFile(local.files[fileIndex], {
                recordType: "Process",
                recordName: saved.name || trimmedName,
                descriptor: describeProcessStep({
                  stepNumber: serverStep.sortOrder ?? index + 1,
                  stepName: serverStep.name,
                  subject: local.files[fileIndex],
                  index: local.existingMedia.length + fileIndex + 1
                })
              }),
              linkedRecordType: "processstep",
              linkedRecordId: serverStep.id,
              caption: `Process step ${serverStep.name}`
            });
          } catch {
            failures.push(local.files[fileIndex].name);
          }
        }
      }
      setUploadNote(null);
      if (failures.length) {
        setError(
          `Saved, but ${countNoun(failures.length, "file")} didn’t upload: ${failures.join(", ")}. Reopen to retry.`
        );
        setGuardOpen(false);
        return;
      }
      setGuardOpen(false);
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save process");
      setGuardOpen(false);
    } finally {
      setSaving(false);
      setUploadNote(null);
    }
  }

  const productPlaceholder = !artisanId
    ? "Select an artisan first"
    : productsLoading
      ? "Loading products…"
      : artisanProducts.length === 0
        ? "No products for this artisan"
        : "Select the product this process makes";

  return (
    <form
      className="panel grid gap-4 p-5"
      noValidate
      onKeyDown={handleFormEnter}
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {initial?.id ? <ReviewNoteBanner recordType="process" recordId={initial.id} status={initial.status} /> : null}
      <div>
        <h2 className="font-display text-lg font-bold text-ink-900">{isEdit ? "Update process" : "Document process"}</h2>
        <p className="mt-1 text-xs text-ink-500">
          Capture how a product is made, step by step. Each process is tied to a product; multiple people can document the same
          product&apos;s processes.
        </p>
      </div>
      {initial ? <FieldProvenance extraMetadata={initial.extraMetadata} /> : null}
      <DraftOfferCard draft={draft} />
      {error ? <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div> : null}
      <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />

      <DictationUnavailableNotice />

      <WorkshopSelect state={workshop} saving={saving} />

      <div>
        <DictatedTextInput
          id="process-name"
          label="Name of the process"
          required
          titleCased
          explainWhenUnavailable={false}
          value={name}
          aria-invalid={!!nameError}
          aria-describedby={nameError ? "process-name-error" : undefined}
          onChange={(next) => setName(next)}
        />
        {nameError ? <p id="process-name-error" className="mt-1 text-xs text-error-600">{nameError}</p> : null}
      </div>

      <div>
        <Field label="Artisan" required>
          <ComboBox
            value={artisanId}
            options={artisanOptions.map((artisan) => ({
              value: artisan.id,
              label: artisan.place?.trim() ? `${artisan.name} · ${artisan.place}` : artisan.name
            }))}
            placeholder="Select the artisan"
            emptyLabel={artisanListState === "pending" ? "Loading…" : "No artisans yet"}
            onSearch={artisanList.setQuery}
            onChange={(picked) => {
              if (picked !== artisanId) {
                setArtisanId(picked);
                setProductId("");
                const artisan = artisanOptions.find((a) => a.id === picked);
                if (artisan) {
                  carry.remember({ artisanId: artisan.id, artisanName: artisan.name, place: artisan.place }, { explicit: true });
                }
              }
            }}
          />
          {artisanList.searching ? <p className="mt-1 text-xs text-ink-500">Searching all artisans…</p> : null}
          <CappedListNotice cuts={[artisanCut]} />
        </Field>
        {artisanError ? <p className="mt-1 text-xs text-error-600">{artisanError}</p> : null}
      </div>

      <div>
        <Field label="Product" required>
          <Select
            value={productId}
            onChange={(event) => {
              const picked = event.target.value;
              setProductId(picked);
              const product = artisanProducts.find((candidate) => candidate.id === picked);
              if (!product) return;
              pruneCarried("product");
              const artisan = artisanOptions.find((candidate) => candidate.id === artisanId);
              carry.remember({
                artisanId,
                artisanName: artisan?.name ?? null,
                place: artisan?.place ?? null,
                craftId: artisan?.craftId ?? null,
                productId: product.id,
                productName: product.productName
              });
            }}
            disabled={!artisanId || productsLoading || artisanProducts.length === 0}
          >
            <option value="">{productPlaceholder}</option>
            {artisanProducts.map((product) => (
              <option key={product.id} value={product.id}>
                {product.productName}
              </option>
            ))}
          </Select>
        </Field>
        {productsLoading ? <p className="mt-1 text-xs text-ink-500">Loading this artisan&apos;s products…</p> : null}
        {!productsLoading && productLoadError ? <p className="mt-1 text-xs text-error-600">{productLoadError}</p> : null}
        {!productsLoading && !productLoadError && artisanId && artisanProducts.length === 0 ? (
          <p className="mt-1 text-xs text-ink-500">No products found for this artisan yet. Create a product for them first, then return here.</p>
        ) : null}
        {!productsLoading && !productLoadError && artisanId && artisanProducts.length > 0 ? (
          <p className="mt-1 text-xs text-ink-500">{countNoun(artisanProducts.length, "product")} available for this artisan.</p>
        ) : null}
        <CappedListNotice cuts={[artisanId ? productCut : null]} />
        {productError ? <p className="mt-1 text-xs text-error-600">{productError}</p> : null}
      </div>

      <RichTextField
        key={notesSeed.nonce}
        name="notes"
        label="What happens in this process"
        defaultValue={notesSeed.value}
        helper="The sequence in your own words."
        onValueChange={setNotes}
        explainWhenUnavailable={false}
      />

      <Toggle
        className="sm:max-w-md"
        label="Pre-processes available"
        checked={preProcessAvailable}
        onChange={setPreProcessAvailable}
      />
      {preProcessAvailable ? (
        <div className="grid gap-2">
          <p className="text-xs text-ink-500">Attach the pre-process media (required).</p>
          <SavedMediaList
            items={existingPreMedia}
            onRemoved={(id) => setExistingPreMedia((current) => current.filter((media) => media.id !== id))}
            onError={setError}
          />
          <p className="text-xs font-medium text-amber-800 dark:text-amber-200">🎥 Video is the preferred format here — capture the action as it happens.</p>
          <MediaCaptureField
            files={preFiles}
            onFilesChange={setPreFiles}
            title="Attach media"
            description="Photos, video, audio and files link to this record automatically."
          />
          {preMediaError ? <p className="text-xs text-error-600">{preMediaError}</p> : null}
        </div>
      ) : null}

      <div className="border-t border-line-200 pt-4">
        <h3 className="font-display font-bold text-ink-900">Steps</h3>
        {stepsError ? <p className="mt-1 text-xs text-error-600">{stepsError}</p> : null}

        <div className="mt-3 grid gap-3">
          {steps.map((step, index) => (
            <div key={step.key} className="grid gap-3 rounded-xl border border-line-200 bg-surface-50 p-4">
              <div className="flex items-center justify-between gap-2">
                <span className="flex flex-wrap items-center gap-2 text-sm font-semibold text-ink-900">
                  <span>
                    Step {index + 1} · {stepTypeLabel(step.stepType)}
                  </span>
                  {!step.name.trim() ? (
                    <Badge tone="attention">Unnamed step</Badge>
                  ) : step.template ? (
                    <Badge>From last process</Badge>
                  ) : null}
                </span>
                <button
                  type="button"
                  className="text-sm font-semibold text-error-600"
                  onClick={() => setSteps((current) => current.filter((s) => s.key !== step.key))}
                >
                  Remove
                </button>
              </div>
              <div>
                <DictatedTextInput
                  id={`step-name-${step.key}`}
                  label="Name of the step"
                  placeholder="Add a name when you can"
                  explainWhenUnavailable={false}
                  value={step.name}
                  aria-invalid={!!step.nameError}
                  aria-describedby={step.nameError ? `step-name-${step.key}-error` : undefined}
                  onChange={(next) => updateStep(step.key, { name: next, template: false })}
                />
                {step.nameError ? (
                  <p id={`step-name-${step.key}-error`} className="mt-1 text-xs text-error-600">
                    {step.nameError}
                  </p>
                ) : null}
              </div>
              <SavedMediaList
                items={step.existingMedia}
                onRemoved={(id) => updateStep(step.key, { existingMedia: step.existingMedia.filter((media) => media.id !== id) })}
                onError={setError}
              />
              <Toggle
                className="sm:max-w-md"
                label="Record additional information"
                checked={step.recordAdditional}
                onChange={(next) => updateStep(step.key, { recordAdditional: next, ...(next ? {} : { notes: "" }) })}
              />
              {step.recordAdditional ? (
                <MultiNoteInput
                  key={step.key}
                  label="Additional context for this step"
                  value={step.notes}
                  onChange={(value) => updateStep(step.key, { notes: value })}
                />
              ) : null}
              <p className="text-xs font-medium text-amber-800 dark:text-amber-200">🎥 Video is the preferred format here — capture the action as it happens.</p>
              <MediaCaptureField
                files={step.files}
                onFilesChange={(files) => updateStep(step.key, { files })}
                title="Attach media"
                description="Photos, video, audio and files link to this record automatically."
              />
            </div>
          ))}
        </div>

        {unnamed > 0 ? (
          <p className="mt-3 text-xs font-medium text-amber-800 dark:text-amber-200">
            {unnamed} step{unnamed === 1 ? "" : "s"} still need{unnamed === 1 ? "s" : ""} a name.
          </p>
        ) : null}
        <ButtonRow align="start" className="mt-3">
          <label className="field-button-secondary cursor-pointer">
            <Images className="h-4 w-4" aria-hidden />
            Steps from photos
            <input
              className="hidden"
              type="file"
              accept="image/*,video/*"
              multiple
              onChange={(event) => {
                void addStepsFromFiles(event.target.files);
                event.target.value = "";
              }}
            />
          </label>
          {steps.length === 0 && productCraftId ? (
            <button type="button" className="field-button-secondary" onClick={startFromLastProcess} disabled={templateBusy}>
              <ListRestart className="h-4 w-4" aria-hidden />
              {templateBusy ? "Loading…" : "Start from last process"}
            </button>
          ) : null}
        </ButtonRow>
        {templateNote ? <p className="mt-1 text-xs text-ink-500">{templateNote}</p> : null}
        <div className="relative mt-3">
          <button type="button" className="field-button-secondary w-full" onClick={() => setAddMenu((value) => !value)}>
            <Plus className="h-4 w-4" aria-hidden />
            Add Another Step
          </button>
          {addMenu ? (
            <div className="absolute left-0 right-0 z-20 mt-1 rounded-md border border-line-200 bg-card p-1 shadow-md">
              <button
                type="button"
                className="block w-full rounded px-3 py-2 text-left text-sm text-ink-900 hover:bg-purple-50"
                onClick={() => addStep("SEQUENTIAL")}
              >
                Sequential
              </button>
              <button
                type="button"
                className="block w-full rounded px-3 py-2 text-left text-sm text-ink-900 hover:bg-purple-50"
                onClick={() => addStep("GROUP")}
              >
                Group of activities
              </button>
            </div>
          ) : null}
        </div>
      </div>

      {canPickStatus ? (
        <Field label="Status">
          <Select value={status} onChange={(event) => setStatus(event.target.value)}>
            {statusChoices.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </Select>
        </Field>
      ) : (
        <div>
          <span className="field-label">Status</span>
          <div className="mt-1.5 flex items-center gap-2">
            {isEdit ? <StatusBadge status={String(initial?.status ?? "PENDING")} /> : <StatusBadge status="PENDING" />}
            <Lock className="h-3.5 w-3.5 text-ink-500" aria-hidden />
          </div>
          <p className="mt-1 text-xs text-ink-500">New records await review; a professor or admin sets the final status.</p>
        </div>
      )}

      <div className="rounded-xl bg-[#2A2520] p-3 text-xs text-[#E0C9B0]">
        Document each process yourself, even if a colleague has — every perspective is kept.
      </div>

      {uploadNote ? <p className="text-xs text-ink-500">{uploadNote}</p> : null}
      <ButtonRow align="end">
        <button type="button" className="field-button-secondary" onClick={requestCancel}>
          Cancel
        </button>
        <button className="field-button" disabled={saving} type="submit">
          {saving ? "Saving..." : isEdit ? "Update process" : "Save process"}
        </button>
      </ButtonRow>

      <UnsavedChangesDialog
        open={guardOpen}
        saving={saving}
        onKeepEditing={() => setGuardOpen(false)}
        onDiscard={() => {
          setGuardOpen(false);
          onCancel();
        }}
        onSave={() => void submit()}
      />
    </form>
  );
}
