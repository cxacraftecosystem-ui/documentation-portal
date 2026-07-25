"use client";

import { useEffect, useState } from "react";
import { Lock, Plus } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { Field, Select, TextInput } from "@/components/FormControls";
import { FieldProvenance } from "@/components/FieldProvenance";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { TitleCasedInput } from "@/components/forms/TitleCasedInput";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { StatusBadge } from "@/components/StatusBadge";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { apiFetch, listResource } from "@/lib/api";
import { handleFormEnter } from "@/lib/formNav";
import { inferMediaType, uploadMediaFile } from "@/lib/media";
import { hasRank } from "@/lib/permissions";
import type { Artisan, ExtraMetadata, MediaFile, ProductDocumentation, RecordStatus, User, Workshop } from "@/lib/types";
import { useConfirm } from "@/components/dialogs";

// ---------------------------------------------------------------------------
// Types for the /processes endpoints (hydrated detail: media on the process =
// pre-process clips, media on each step = that step's captures).
// ---------------------------------------------------------------------------

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
  // The workshop this process was documented at. The API also resolves a process through its parent
  // product's workshop, so an older process can be null here and still belong to a workshop.
  workshopId?: string | null;
  workshop?: Workshop | null;
  extraMetadata?: ExtraMetadata | null;
  createdAt: string;
  createdById?: string;
  createdBy?: User;
};

const STATUS_OPTIONS = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];

/** Mutable UI holder for one step: name, fixed type, optional notes, and its own media. */
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
};

function stepTypeLabel(stepType: string): string {
  return stepType === "SEQUENTIAL" ? "Sequential" : "Group of activities";
}

/** Per-file nomenclature segment for a step's media: 1A/1B… for sequential, 1-G1/1-G2… for groups. */
function processStepSegment(stepNumber: number, stepType: string, fileIndex: number): string {
  return stepType === "SEQUENTIAL"
    ? `STEP_${stepNumber}${String.fromCharCode(65 + (fileIndex % 26))}`
    : `STEP_${stepNumber}_G${fileIndex + 1}`;
}

function makeKey(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

// --- Android FieldRepository.mediaFilename parity: RECORDTYPE_Name_SEGMENT_TYPE_index_timestamp ---

function safeToken(value: string): string {
  const cleaned = value
    .trim()
    .replace(/\s+/g, "")
    .replace(/[^A-Za-z0-9]/g, "")
    .slice(0, 60);
  return cleaned || "Record";
}

function timestampToken(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(now.getDate())}${pad(now.getMonth() + 1)}${now.getFullYear()}${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

function typeCodeFor(file: File): string {
  const mediaType = inferMediaType(file);
  if (mediaType === "IMAGE") return "IMG";
  if (mediaType === "AUDIO") return "AUD";
  if (mediaType === "VIDEO") return "VID";
  return "DOC";
}

/** Rename a file to the field nomenclature so stored objects match the Android app's naming. */
function nomenclatureFile(recordType: string, recordName: string, segment: string, file: File, index: number): File {
  const dot = file.name.lastIndexOf(".");
  const extension = dot > 0 ? file.name.slice(dot + 1) : "";
  const base = [recordType.toUpperCase(), safeToken(recordName), segment, typeCodeFor(file), String(index), timestampToken()].join("_");
  const name = extension ? `${base}.${extension}` : base;
  return new File([file], name, { type: file.type, lastModified: file.lastModified });
}

// ---------------------------------------------------------------------------
// Saved media list ("Already attached:") with per-item delete — Android parity.
// ---------------------------------------------------------------------------

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
  // After the hooks: an empty list renders nothing at all (no "Already attached:" heading).
  if (!items.length) return null;

  async function remove(media: MediaFile) {
    const ok = await confirm({
      title: "Remove this file?",
      body: `"${media.caption || media.originalFilename}" will be removed from this step.`,
      note: "The file is permanently deleted from storage. This cannot be undone.",
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

// ---------------------------------------------------------------------------
// Controlled multi-note input (Android MultiNoteInput): notes stored \n\n-joined.
// Give it a fresh React `key` to reset its internal rows.
// ---------------------------------------------------------------------------

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
        <div key={index} className="flex items-start gap-2">
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
              className="field-button-secondary h-9 min-h-0 shrink-0 px-2.5"
              onClick={() => emit(rows.filter((_, i) => i !== index))}
            >
              ✕
            </button>
          ) : null}
        </div>
      ))}
      <button type="button" className="field-button-secondary h-9 min-h-0 justify-self-start px-3 text-xs" onClick={() => emit([...rows, ""])}>
        <Plus className="h-3.5 w-3.5" aria-hidden />
        Add note
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// The process form (create + edit) — Android ProcessForm parity.
// ---------------------------------------------------------------------------

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
  // The workshop this process was documented at: shared picker, shared most-recent defaulting, and
  // the late-submission gate (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });

  const [name, setName] = useState(initial?.name ?? "");
  const [artisanId, setArtisanId] = useState(initial?.product?.artisanId ?? "");
  const [productId, setProductId] = useState(initial?.productId ?? "");
  const [preProcessAvailable, setPreProcessAvailable] = useState(initial?.preProcessAvailable ?? false);
  // Android parity: the whole-process notes field has no input in the form; it is carried through
  // unchanged on edit so nothing typed elsewhere is lost.
  const notes = initial?.notes ?? "";
  // Status policy: professor+ defaults to APPROVED on create and may pick any status; below
  // professor the status is forced to PENDING (locked chip below) and the server enforces it too.
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

  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [artisanProducts, setArtisanProducts] = useState<ProductDocumentation[]>([]);
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

  useEffect(() => {
    listResource<Artisan>("/artisans", { pageSize: 100 })
      .then((result) => setArtisans(result.items))
      .catch(() => undefined);
  }, []);

  // Products belong to an artisan, so the product list is scoped to the chosen artisan. The server
  // OR-matches FK-linked products plus products carrying the artisan's typed name (no FK), so the
  // dropdown never hides a product that genuinely belongs to the artisan.
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
    const artisanName = artisans.find((artisan) => artisan.id === artisanId)?.name?.trim();
    listResource<ProductDocumentation>("/products", { artisanId, artisanName, pageSize: 100 })
      .then((result) => {
        if (cancelled) return;
        setArtisanProducts(result.items);
        setProductsLoading(false);
        // Keep a valid selection: clear product if it is no longer offered for this artisan.
        setProductId((current) => (current && result.items.every((product) => product.id !== current) ? "" : current));
      })
      .catch((err) => {
        if (cancelled) return;
        setArtisanProducts([]);
        setProductsLoading(false);
        setProductLoadError(
          `Couldn't load this artisan's products: ${err instanceof Error ? err.message : "network error"}. Tap the artisan again to retry.`
        );
      });
    return () => {
      cancelled = true;
    };
  }, [artisanId, artisans]);

  // Unsaved-changes guard: signature of every user-editable field + pending file counts. The
  // workshop only counts once the USER has picked one — the create form's automatic most-recent
  // default lands asynchronously and must not make an untouched form look dirty.
  const signature = JSON.stringify({
    name,
    artisanId,
    productId,
    workshopId: workshop.touched ? workshop.workshopId : "",
    status,
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
  // Captured once on first render (state initializer), so it is safe to read while rendering.
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
    setSteps((current) =>
      current.map((step) => ({ ...step, nameError: step.name.trim() ? null : "This field cannot be empty" }))
    );
    steps.forEach((step) => {
      if (!step.name.trim()) {
        blocked = true;
        focusId = focusId ?? `step-name-${step.key}`;
      }
    });
    if (blocked) {
      setError("Please fill the required fields highlighted above.");
      setGuardOpen(false);
      if (focusId) document.getElementById(focusId)?.focus();
      return;
    }

    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
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
        // Unauthorized status changes are silently dropped server-side.
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
      const saved = await apiFetch<ProcessRecord>(isEdit ? `/processes/${initial!.id}` : "/processes", {
        method: isEdit ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });

      // Upload media after the record exists: pre-process clips link to the process itself,
      // each step's files link to that step (linkedRecordType "processstep" + the step id),
      // with the Android nomenclature (PRE / STEP_1A / STEP_1_G1 …) preserved per file.
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
              file: nomenclatureFile("process", trimmedName, "PRE", preFiles[index], index + 1),
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
          const segment = processStepSegment(index + 1, serverStep.stepType, fileIndex);
          try {
            await uploadMediaFile({
              file: nomenclatureFile("processstep", trimmedName, segment, local.files[fileIndex], fileIndex + 1),
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
          `The process was saved, but ${failures.length} media file(s) failed to upload: ${failures.join(", ")}. Re-open the process from the list to retry those files.`
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
      onKeyDown={handleFormEnter}
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <div>
        <h2 className="font-display text-lg font-bold text-ink-900">{isEdit ? "Edit process" : "Document process"}</h2>
        <p className="mt-1 text-xs text-ink-500">
          Capture how a product is made, step by step. Each process is tied to a product; multiple people can document the same
          product&apos;s processes.
        </p>
      </div>
      {initial ? <FieldProvenance extraMetadata={initial.extraMetadata} /> : null}
      {error ? <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div> : null}

      {/* Android parity (ProcessForm): the workshop opens the form, because it is the context
          every other answer belongs to — not merely the first dropdown. */}
      <WorkshopSelect state={workshop} saving={saving} />

      <div>
        <Field label="Name of the process" required>
          {/* `name` is one of the API's title-cased columns, so the box says what will actually be
              stored (Android parity — see components/forms/TitleCasedInput). */}
          <TitleCasedInput
            id="process-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </Field>
        {nameError ? <p className="mt-1 text-xs text-error-600">{nameError}</p> : null}
      </div>

      <div>
        <Field label="Artisan" required>
          <Select
            value={artisanId}
            onChange={(event) => {
              const picked = event.target.value;
              if (picked !== artisanId) {
                setArtisanId(picked);
                setProductId("");
              }
            }}
          >
            <option value="">Select the artisan</option>
            {artisans.map((artisan) => (
              <option key={artisan.id} value={artisan.id}>
                {artisan.name} · {artisan.place}
              </option>
            ))}
          </Select>
        </Field>
        {artisanError ? <p className="mt-1 text-xs text-error-600">{artisanError}</p> : null}
      </div>

      <div>
        <Field label="Product" required>
          <Select
            value={productId}
            onChange={(event) => setProductId(event.target.value)}
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
          <p className="mt-1 text-xs text-ink-500">{artisanProducts.length} product(s) available for this artisan.</p>
        ) : null}
        {productError ? <p className="mt-1 text-xs text-error-600">{productError}</p> : null}
      </div>

      <label className="flex items-center gap-2 text-sm text-ink-900">
        <input
          type="checkbox"
          className="h-4 w-4 accent-purple-700"
          checked={preProcessAvailable}
          onChange={(event) => setPreProcessAvailable(event.target.checked)}
        />
        Pre-processes available
      </label>
      {preProcessAvailable ? (
        <div className="grid gap-2">
          <p className="text-xs text-ink-500">Attach the pre-process media (required).</p>
          <SavedMediaList
            items={existingPreMedia}
            onRemoved={(id) => setExistingPreMedia((current) => current.filter((media) => media.id !== id))}
            onError={setError}
          />
          <p className="text-xs font-medium text-amber-800">🎥 Video is the preferred format here — capture the action as it happens.</p>
          <MediaCaptureField
            files={preFiles}
            onFilesChange={setPreFiles}
            title="Attach media"
            description="Photos, video, audio and files link to this record automatically. Audio is queued for transcription after upload."
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
                <span className="text-sm font-semibold text-ink-900">
                  Step {index + 1} · {stepTypeLabel(step.stepType)}
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
                <Field label="Name of the step" required>
                  <input
                    className="field-input"
                    id={`step-name-${step.key}`}
                    value={step.name}
                    onChange={(event) => updateStep(step.key, { name: event.target.value })}
                  />
                </Field>
                {step.nameError ? <p className="mt-1 text-xs text-error-600">{step.nameError}</p> : null}
              </div>
              <SavedMediaList
                items={step.existingMedia}
                onRemoved={(id) => updateStep(step.key, { existingMedia: step.existingMedia.filter((media) => media.id !== id) })}
                onError={setError}
              />
              <label className="flex items-center gap-2 text-sm text-ink-900">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-purple-700"
                  checked={step.recordAdditional}
                  onChange={(event) => updateStep(step.key, { recordAdditional: event.target.checked, ...(event.target.checked ? {} : { notes: "" }) })}
                />
                Record additional information
              </label>
              {step.recordAdditional ? (
                <MultiNoteInput
                  key={step.key}
                  label="Additional context for this step"
                  value={step.notes}
                  onChange={(value) => updateStep(step.key, { notes: value })}
                />
              ) : null}
              <p className="text-xs font-medium text-amber-800">🎥 Video is the preferred format here — capture the action as it happens.</p>
              <MediaCaptureField
                files={step.files}
                onFilesChange={(files) => updateStep(step.key, { files })}
                title="Attach media"
                description="Photos, video, audio and files link to this record automatically. Audio is queued for transcription after upload."
              />
            </div>
          ))}
        </div>

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
        Note: Different users may contribute to processes created by others. Even when documenting the same process, it is recommended
        that each researcher documents it individually, so that different perspectives on the same process are preserved.
      </div>

      <div className="flex items-center justify-end gap-2">
        {uploadNote ? <span className="mr-auto text-xs text-ink-500">{uploadNote}</span> : null}
        <button type="button" className="field-button-secondary" onClick={requestCancel}>
          Cancel
        </button>
        <button className="field-button" disabled={saving} type="submit">
          {saving ? "Saving..." : isEdit ? "Update process" : "Save process"}
        </button>
      </div>

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
