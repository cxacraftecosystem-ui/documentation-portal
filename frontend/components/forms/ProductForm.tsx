"use client";

import { useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { CarryForwardCards } from "@/components/CarryForwardCards";
import { artisanStatedSource } from "@/components/forms/statedPrefill";
import { useServerSearchList } from "@/components/forms/useServerSearchList";
import { DraftOfferCard, useFormDraft } from "@/components/forms/FormDraft";
import { failedFilesOf, RetryFailedUploads } from "@/components/media/RetryFailedUploads";
import { ComboBox } from "@/components/ui/Dropdown";
import { Toggle } from "@/components/ui/Toggle";
import { mergeById } from "@/components/data/cappedList";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import {
  forgetAcceptance,
  measurementMethodsFor,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance,
  type AcceptedMeasurements
} from "@/components/forms/measurementMethods";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { CardCodeLookup } from "@/components/artisans/CardCodeLookup";
import { artisanForCard, craftChangeClearsArtisan, useCraftAndArtisanOptions } from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { LiveCompleteness, photosPicked } from "@/components/records/LiveCompleteness";
import { GridMeasurement, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { RecordPhotoMeasure, type MeasureColumn } from "@/components/media/RecordPhotoMeasure";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { statusLabel } from "@/components/StatusBadge";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import {
  locationFromForm,
  numericValue,
  recordedAtFromForm,
  recordedTimezoneFromForm,
  requiredText,
  textValue,
  useFocusField,
  useUnsavedChanges
} from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { canManageCrafts, hasRank } from "@/lib/permissions";
import type { Artisan, ProductDocumentation, RecordStatus } from "@/lib/types";
import { marketDemandOptions, productTypes } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { countNoun } from "@/lib/format";

export function artisanPickerLabel(artisan: Artisan) {
  return [artisan.name?.trim() || "Unnamed artisan", artisan.craft?.name?.trim(), artisan.place?.trim()]
    .filter(Boolean)
    .join(" · ");
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
        title="Submitted for review — a reviewer sets the final status."
      >
        {text}
      </span>
    </div>
  );
}

export function ProductForm({ initial }: { initial?: ProductDocumentation }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const formRef = useRef<HTMLFormElement>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  useFocusField();
  const completenessCounts = useMemo(() => ({ photos: photosPicked(mediaFiles) }), [mediaFiles]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [craftId, setCraftId] = useState(initial?.craftId ?? searchParams.get("craftId") ?? "");
  const [artisanId, setArtisanId] = useState(initial?.artisanId ?? searchParams.get("artisanId") ?? "");
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(initial?.artisanName ?? searchParams.get("artisanName") ?? "");
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  const [offRecord, setOffRecord] = useState(() =>
    initial ? !initial.artisanId : !searchParams.get("artisanId") && Boolean(searchParams.get("artisanName"))
  );
  const artisanSearch = useServerSearchList<Artisan>("/artisans", "artisans");
  const [savedProduct, setSavedProduct] = useState<ProductDocumentation | null>(null);
  const [formKey, setFormKey] = useState(0);
  const [retry, setRetry] = useState<{ files: File[]; upload: (files: File[]) => Promise<{ failed: Array<{ name: string; error?: string }> }> } | null>(null);
  const [productName, setProductName] = useState(initial?.productName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [timeTaken, setTimeTaken] = useState(initial?.timeTakenToCompleteProduct ?? "");
  const [size, setSize] = useState(initial?.size ?? "");
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  const [accepted, setAccepted] = useState<AcceptedMeasurements>(NO_ACCEPTED_MEASUREMENTS);
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  const [measurePhoto, setMeasurePhoto] = useState<{ file: File; isGrid: boolean } | null>(null);
  const craftIdList = useMemo(() => (craftId ? [craftId] : []), [craftId]);
  const artisanIdList = useMemo(() => (artisanId ? [artisanId] : []), [artisanId]);
  const {
    artisans,
    crafts,
    referenceState
  } = useCraftAndArtisanOptions({ craftIds: craftIdList, artisanIds: artisanIdList });
  const pickerArtisans = useMemo(() => mergeById(artisanSearch.items, artisans), [artisanSearch.items, artisans]);
  const pickedArtisan = pickerArtisans.find((artisan) => artisan.id === artisanId) ?? null;
  const craftOptions = crafts;
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  const initialLocation = initial
    ? ((initial as ProductDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  const [restored, setRestored] = useState<Record<string, string>>({});
  const [draftTick, setDraftTick] = useState(0);
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

  const draft = useFormDraft({
    kind: "product",
    recordId: initial?.id ?? null,
    parentId: initial ? null : searchParams.get("artisanId"),
    parentLabel: initial ? null : searchParams.get("artisanName"),
    dirty,
    label: productName.trim(),
    fields: {
      productName,
      localName,
      timeTaken,
      size,
      length,
      breadth,
      height,
      craftId,
      artisanId,
      craftName,
      artisanName,
      place,
      offRecord,
      mediaFiles,
      workshopId: workshop.workshopId,
      tick: draftTick
    },
    collect: () => {
      const form = formRef.current ? new FormData(formRef.current) : null;
      const out: Record<string, string> = {};
      for (const name of ["productType", "costOfMaking", "sellingPrice", "marketDemand", "rawMaterialsUsed", "mainToolsUsed", "productFunctionUse", "remarks"]) {
        const value = form?.get(name);
        if (typeof value === "string") out[`form.${name}`] = value;
      }
      return out;
    },
    onRestore: (fields) => {
      const text = (key: string) => (typeof fields[key] === "string" ? (fields[key] as string) : null);
      const setters: Array<[string, (value: string) => void]> = [
        ["productName", setProductName],
        ["localName", setLocalName],
        ["timeTaken", setTimeTaken],
        ["size", setSize],
        ["length", setLength],
        ["breadth", setBreadth],
        ["height", setHeight],
        ["craftId", setCraftId],
        ["artisanId", setArtisanId],
        ["craftName", setCraftName],
        ["artisanName", setArtisanName],
        ["place", setPlace]
      ];
      for (const [key, set] of setters) {
        const value = text(key);
        if (value !== null) set(value);
      }
      if (typeof fields.offRecord === "boolean") setOffRecord(fields.offRecord);
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

  const typeInto =
    (set: (value: string) => void, key: string) => (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
    };


  const carry = useCarryContext({
    enabled: !isEdit,
    scopes: [carryScope("artisan", referenceState, pickerArtisans), carryScope("craft", referenceState, craftOptions)],
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

  function clearCarriedContext() {
    carry.change();
    setCraftId("");
    setCraftName("");
    setArtisanId("");
    setArtisanName("");
    setPlace("");
  }

  function handleBack() {
    if (dirty) setBackPromptOpen(true);
    else router.back();
  }

  function pickArtisan(nextId: string) {
    setArtisanId(nextId);
    const artisan = pickerArtisans.find((candidate) => candidate.id === nextId);
    if (artisan) {
      setArtisanName(artisan.name);
      setPlace(artisan.place);
      if (artisan.craftId) setCraftId(artisan.craftId);
      if (artisan.craft?.name) setCraftName(artisan.craft.name);
      carry.remember(
        {
          artisanId: artisan.id,
          artisanName: artisan.name,
          place: artisan.place,
          craftId: artisan.craftId ?? craftId,
          craftName: artisan.craft?.name ?? craftName
        },
        { explicit: true }
      );
    }
    markDirty();
  }

  function startAnother() {
    setSavedProduct(null);
    setRetry(null);
    setMediaFiles([]);
    setProductName("");
    setLocalName("");
    setTimeTaken("");
    setSize("");
    setLength("");
    setBreadth("");
    setHeight("");
    setAccepted(NO_ACCEPTED_MEASUREMENTS);
    setGridFiles({});
    setMeasurePhoto(null);
    setRestored({});
    setError(null);
    resetDirty();
    setFormKey((key) => key + 1);
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function finishSave(saved: ProductDocumentation, addAnother: boolean) {
    resetDirty();
    if (initial) {
      router.push("/products");
      router.refresh();
      return;
    }
    if (addAnother) {
      startAnother();
      return;
    }
    setSavedProduct(saved);
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const submitter = (event.nativeEvent as SubmitEvent).submitter as HTMLElement | null;
    const addAnother = submitter?.dataset.intent === "add-another";
    if (!offRecord && !artisanId) {
      setError("Pick the artisan, or switch on “Artisan not on record”.");
      return;
    }
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    try {
      const exifItems = await collectExifMetadata(
        [...Object.values(gridFiles), measurePhoto?.file, ...mediaFiles].filter(Boolean) as File[]
      );
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const names = { place: place.trim(), artisanName: artisanName.trim() };
      const sentNames = initial ? Object.fromEntries(Object.entries(names).filter(([, value]) => value)) : names;
      const payload = {
        ...sentNames,
        productName: requiredText(form, "productName"),
        localName: textValue(form, "localName"),
        productType: requiredText(form, "productType") || "OTHER",
        timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
        size: textValue(form, "size"),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        heightInches: toNum(height),
        measurementMethods: measurementMethodsFor(accepted, {
          lengthInches: length,
          breadthInches: breadth,
          heightInches: height
        }),
        costOfMaking: numericValue(form, "costOfMaking"),
        sellingPrice: numericValue(form, "sellingPrice"),
        marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
        rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
        mainToolsUsed: textValue(form, "mainToolsUsed"),
        productFunctionUse: textValue(form, "productFunctionUse"),
        remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: offRecord ? null : artisanId || null,
        craftId: craftId || null,
        workshopId: workshop.workshopId || null,
        status: requiredText(form, "status") || initial?.status || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        extraMetadata: exifItems.length ? { mediaExif: exifItems } : {}
      };
      const outcome = await saveOrQueue<ProductDocumentation>({
        label: `Product · ${payload.productName || "Untitled"}`,
        endpoint: initial ? `/products/${initial.id}` : "/products",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "product",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.productName || "product"}`,
            location,
            recordedAt,
            recordedTimezone,
          })),
          ...(measurePhoto
            ? [
                {
                  files: [measurePhoto.file],
                  linkedRecordType: "product",
                  caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${payload.productName || "product"}`,
                  location,
                  recordedAt,
                  recordedTimezone,
                }
              ]
            : []),
          {
            files: mediaFiles,
            linkedRecordType: "product",
            caption: `Field media for ${payload.productName || "product"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      const sitting = {
        artisanId,
        artisanName: names.artisanName,
        place: names.place,
        craftId,
        craftName: craftName.trim(),
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      draft.clear();
      if (outcome.queued) {
        carry.prune("product");
        carry.remember(sitting);
        resetDirty();
        if (addAnother) startAnother();
        else if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        setSaving(false);
        return;
      }
      const saved = outcome.saved;
      carry.remember({ ...sitting, productId: saved.id, productName: saved.productName });
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.productName}`,
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
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${saved.productName}`,
            location,
            recordedAt,
            recordedTimezone,
          });
        } catch {
        }
      }
      if (mediaFiles.length) {
        const uploadTo = (files: File[]) =>
          uploadMediaBatch({
            files,
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `Field media for ${saved.productName}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
            onProgress: setUploadProgress
          });
        const { failed } = await uploadTo(mediaFiles);
        setUploadProgress(null);
        if (failed.length) {
          resetDirty();
          setRetry({
            files: failedFilesOf(mediaFiles, failed),
            upload: async (files) => {
              const result = await uploadTo(files);
              setUploadProgress(null);
              return result;
            }
          });
          setSavedProduct(saved);
          setSaving(false);
          return;
        }
      }
      finishSave(saved, addAnother);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save product record");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  if (savedProduct && !initial) {
    return (
      <div className="grid gap-6">
        {retry ? <RetryFailedUploads files={retry.files} upload={retry.upload} onDone={() => setRetry(null)} /> : null}
        <div className="panel p-4">
          <p className="text-sm font-medium text-ink">Saved &ldquo;{savedProduct.productName}&rdquo;.</p>
          <ButtonRow align="start" className="mt-3">
            <button type="button" className="field-button" onClick={startAnother}>
              Add another product
            </button>
            <button type="button" className="field-button-secondary" onClick={() => { router.push("/products"); router.refresh(); }}>
              Back to products
            </button>
          </ButtonRow>
        </div>
        <CarryForwardCards
          heading="Next for this product"
          context={{
            artisanId: savedProduct.artisanId ?? artisanId,
            artisanName: savedProduct.artisanName,
            place: savedProduct.place,
            craftId: savedProduct.craftId ?? craftId,
            craftName: savedProduct.craftName,
            productId: savedProduct.id,
            productName: savedProduct.productName,
            workshopId: workshop.workshopId,
            workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
          }}
        />
      </div>
    );
  }

  return (
    <>
      {retry && initial ? <RetryFailedUploads files={retry.files} upload={retry.upload} onDone={() => finishSave(initial, false)} /> : null}
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
        <LiveCompleteness kind="product" formRef={formRef} stored={initial} counts={completenessCounts} />
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          <DictatedTextInput
            name="productName"
            label="Product name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={productName}
            onChange={(next) => {
              setProductName(next);
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
          <Field label="Product type">
            <Select name="productType" defaultValue={restored.productType ?? initial?.productType ?? "OTHER"} onChange={markDirty}>
              {productTypes.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          {offRecord ? null : (
            <div className="grid content-start gap-1 md:col-span-2">
              <Field label="Artisan" required fieldKey="artisan">
                <ComboBox
                  value={artisanId}
                  options={pickerArtisans.map((artisan) => ({ value: artisan.id, label: artisanPickerLabel(artisan) }))}
                  placeholder="Search by name or place"
                  emptyLabel={referenceState === "pending" ? "Loading…" : "No artisans yet"}
                  onSearch={artisanSearch.setQuery}
                  onChange={pickArtisan}
                />
              </Field>
              {pickedArtisan ? <p className="text-xs text-ink-500">{artisanPickerLabel(pickedArtisan)}</p> : null}
              {artisanSearch.searching ? <p className="text-xs text-ink-500">Searching all artisans…</p> : null}
              <CappedListNotice cuts={[artisanSearch.cut]} />
              <CardCodeLookup
                compact
                actionLabel="Link"
                onFound={(found) => {
                  void artisanForCard(found.id)
                    .then((artisan) => {
                      if (artisan.craftId) {
                        setCraftId(artisan.craftId);
                        if (artisan.craft?.name) setCraftName(artisan.craft.name);
                      }
                      setArtisanId(artisan.id);
                      setArtisanName(artisan.name);
                      setPlace(artisan.place);
                      carry.remember(
                        {
                          artisanId: artisan.id,
                          artisanName: artisan.name,
                          place: artisan.place,
                          craftId: artisan.craftId ?? craftId,
                          craftName: artisan.craft?.name ?? craftName
                        },
                        { explicit: true }
                      );
                      markDirty();
                    })
                    .catch((err) => setError(err instanceof Error ? err.message : "Couldn't link that artisan."));
                }}
              />
            </div>
          )}
          {!offRecord && pickedArtisan && !pickedArtisan.craftId ? (
            <FieldBlock label="Craft" required>
              <CraftSelect
                name="craftId"
                ariaLabel="Craft"
                required
                value={craftId}
                fallbackLabels={initial?.craftId && initial.craftName ? { [initial.craftId]: initial.craftName } : undefined}
                canAddCraft={canManageCrafts(user)}
                onChange={(next, craft) => {
                  setCraftId(next);
                  setCraftName(craft?.name ?? "");
                  if (craftChangeClearsArtisan({ nextCraftId: next, artisanId, artisans })) {
                    setArtisanId("");
                  }
                  markDirty();
                }}
              />
            </FieldBlock>
          ) : null}
          {!offRecord && pickedArtisan && !pickedArtisan.place?.trim() ? (
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
          ) : null}
          <Toggle
            className="self-end"
            label="Artisan not on record"
            checked={offRecord}
            onChange={(next) => {
              setOffRecord(next);
              if (next) setArtisanId("");
              markDirty();
            }}
          />
          {offRecord ? (
            <>
              <FieldBlock label="Craft" required>
                <CraftSelect
                  name="craftId"
                  ariaLabel="Craft"
                  required
                  value={craftId}
                  fallbackLabels={initial?.craftId && initial.craftName ? { [initial.craftId]: initial.craftName } : undefined}
                  canAddCraft={canManageCrafts(user)}
                  onChange={(next, craft) => {
                    setCraftId(next);
                    setCraftName(craft?.name ?? "");
                    if (craftChangeClearsArtisan({ nextCraftId: next, artisanId, artisans })) {
                      setArtisanId("");
                    }
                    markDirty();
                  }}
                />
              </FieldBlock>
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
            </>
          ) : null}
          <div className="min-w-0" data-field="timeTaken">
            <DictatedTextInput
              name="timeTakenToCompleteProduct"
              label="Time taken to complete"
              explainWhenUnavailable={false}
              value={timeTaken}
              onChange={(next) => {
                setTimeTaken(next);
                markDirty();
              }}
            />
          </div>
          <DictatedTextInput
            name="size"
            label="Size"
            explainWhenUnavailable={false}
            value={size}
            onChange={(next) => {
              setSize(next);
              markDirty();
            }}
          />
          <Field label="Length (inches)" fieldKey="dimensions">
            <TextInput
              name="lengthInches"
              type="number"
              step="0.01"
              min={0}
              value={length}
              onChange={typeInto(setLength, "lengthInches")}
            />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput
              name="breadthInches"
              type="number"
              step="0.01"
              min={0}
              value={breadth}
              onChange={typeInto(setBreadth, "breadthInches")}
            />
          </Field>
          <Field label="Height (inches)">
            <TextInput
              name="heightInches"
              type="number"
              step="0.01"
              min={0}
              value={height}
              onChange={typeInto(setHeight, "heightInches")}
            />
          </Field>
        </div>
        <RecordPhotoMeasure
          columns={MEASURE_COLUMNS}
          values={{ lengthInches: length, breadthInches: breadth, heightInches: height }}
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            else if (key === "breadthInches") setBreadth(text);
            else if (key === "heightInches") setHeight(text);
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
                setAccepted((current) => rememberAcceptance(current, "breadthInches", b, method));
              }
              markDirty();
            }}
            onHeight={(value, method) => {
              setHeight(value);
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
          <Field label="Cost of making" fieldKey="costOfMaking">
            <TextInput name="costOfMaking" type="number" step="0.01" min={0} defaultValue={restored.costOfMaking ?? initial?.costOfMaking ?? ""} />
          </Field>
          <Field label="Selling price" fieldKey="sellingPrice">
            <TextInput name="sellingPrice" type="number" step="0.01" min={0} defaultValue={restored.sellingPrice ?? initial?.sellingPrice ?? ""} />
          </Field>
          <Field label="Market demand" fieldKey="marketDemand">
            <Select name="marketDemand" defaultValue={restored.marketDemand ?? initial?.marketDemand ?? "UNKNOWN"} onChange={markDirty}>
              {marketDemandOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          <RichTextField
            name="rawMaterialsUsed"
            label="Raw materials used"
            defaultValue={restored.rawMaterialsUsed ?? initial?.rawMaterialsUsed ?? ""}
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="mainToolsUsed"
            label="Main tools used"
            defaultValue={restored.mainToolsUsed ?? initial?.mainToolsUsed ?? ""}
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="productFunctionUse"
            label="Function or use"
            defaultValue={restored.productFunctionUse ?? initial?.productFunctionUse ?? ""}
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
        <div className="grid gap-4" data-field="photo">
          {initial ? <ExistingMedia linkedRecordType="product" linkedRecordId={initial.id} /> : null}
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              markDirty();
            }}
            title="Product media"
            description="Photos, video, audio and documents."
          />
        </div>
        <LocationFields
          initial={initialLocation}
          onDirty={markDirty}
          prefillFrom={initial || offRecord ? null : artisanStatedSource(pickedArtisan)}
        />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        <ButtonRow align="end">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          {initial ? null : (
            <button className="field-button-secondary" data-intent="add-another" disabled={saving}>
              Save and add another
            </button>
          )}
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : initial ? "Update product" : "Save product"}
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
