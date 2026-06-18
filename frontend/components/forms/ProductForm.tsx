"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { RecordedAtField } from "@/components/forms/RecordedAtField";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { UploadProgress } from "@/components/media/UploadProgress";
import { apiFetch, listResource } from "@/lib/api";
import { locationFromForm, numericValue, parseJsonMetadata, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue } from "@/lib/forms";
import { appendRemarksWithExif, collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import type { Artisan, Craft, ProductDocumentation, Workshop } from "@/lib/types";
import { marketDemandOptions, productTypes } from "@/lib/types";

export function ProductForm({ initial }: { initial?: ProductDocumentation }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [craftId, setCraftId] = useState(initial?.craftId ?? searchParams.get("craftId") ?? "");
  const [artisanId, setArtisanId] = useState(initial?.artisanId ?? searchParams.get("artisanId") ?? "");
  // Dimensions are controlled so the "Document using grid" capture can auto-fill them.
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  const [gridFiles, setGridFiles] = useState<GridFiles>({});

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

  // Task 6: once a craft is linked, the artisan dropdown only offers artisans of that craft. The
  // currently-selected artisan is always kept visible even if the data predates the craft link.
  const artisansForCraft = craftId
    ? artisans.filter((artisan) => artisan.craftId === craftId || artisan.id === artisanId)
    : artisans;

  useEffect(() => {
    Promise.all([
      listResource<Artisan>("/artisans", { pageSize: 100 }),
      listResource<Craft>("/crafts", { pageSize: 100 }),
      listResource<Workshop>("/workshops", { pageSize: 100 })
    ])
      .then(([artisanResult, craftResult, workshopResult]) => {
        setArtisans(artisanResult.items);
        setCrafts(craftResult.items);
        setWorkshops(workshopResult.items);
      })
      .catch(() => undefined);
  }, []);

  const prefillArtisanName = searchParams.get("artisanName") ?? "";
  const prefillCraftName = searchParams.get("craftName") ?? "";
  const prefillPlace = searchParams.get("place") ?? "";

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    try {
      const exifItems = await collectExifMetadata([...Object.values(gridFiles), ...mediaFiles].filter(Boolean) as File[]);
      const exifRemark = exifMetadataToRemark(exifItems);
      const parsedMetadata = parseJsonMetadata(form.get("extraMetadata")) ?? {};
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = {
        craftName: requiredText(form, "craftName"),
        place: requiredText(form, "place"),
        artisanName: requiredText(form, "artisanName"),
        productName: requiredText(form, "productName"),
        localName: textValue(form, "localName"),
        productType: requiredText(form, "productType") || "OTHER",
        timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
        size: textValue(form, "size"),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        heightInches: toNum(height),
        costOfMaking: numericValue(form, "costOfMaking"),
        sellingPrice: numericValue(form, "sellingPrice"),
        marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
        rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
        mainToolsUsed: textValue(form, "mainToolsUsed"),
        productFunctionUse: textValue(form, "productFunctionUse"),
        remarks: appendRemarksWithExif(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: artisanId || null,
        craftId: craftId || null,
        workshopId: textValue(form, "workshopId"),
        status: requiredText(form, "status") || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        extraMetadata: exifItems.length ? { ...parsedMetadata, mediaExif: exifItems } : parsedMetadata
      };
      const saved = await apiFetch<ProductDocumentation>(initial ? `/products/${initial.id}` : "/products", {
        method: initial ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      // Store each captured grid photo as media linked to the product (the measured value is already
      // in the field). Best-effort per file so one failure doesn't lose the record.
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
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if a grid photo fails to store */
        }
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "product",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.productName}`,
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
      router.push("/products");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save product record");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  return (
    <form onSubmit={submit} className="panel grid gap-4 p-4">
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <Field label="Product name" required>
          <TextInput name="productName" required defaultValue={initial?.productName ?? ""} />
        </Field>
        <Field label="Local name">
          <TextInput name="localName" defaultValue={initial?.localName ?? ""} />
        </Field>
        <Field label="Product type">
          <Select name="productType" defaultValue={initial?.productType ?? "OTHER"}>
            {productTypes.map((option) => (
              <option key={option}>{option}</option>
            ))}
          </Select>
        </Field>
        <Field label="Craft name" required>
          <TextInput name="craftName" required defaultValue={initial?.craftName ?? prefillCraftName} />
        </Field>
        <Field label="Linked craft">
          <Select
            name="craftId"
            value={craftId}
            onChange={(event) => {
              const next = event.target.value;
              setCraftId(next);
              // Drop the artisan if it no longer belongs to the chosen craft.
              if (next && artisanId && !artisans.some((a) => a.id === artisanId && a.craftId === next)) {
                setArtisanId("");
              }
            }}
          >
            <option value="">Unlinked</option>
            {crafts.map((craft) => (
              <option key={craft.id} value={craft.id}>
                {craft.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Place" required>
          <TextInput name="place" required defaultValue={initial?.place ?? prefillPlace} />
        </Field>
        <Field label="Artisan name" required>
          <TextInput name="artisanName" required defaultValue={initial?.artisanName ?? prefillArtisanName} />
        </Field>
        <Field label="Linked artisan">
          <Select name="artisanId" value={artisanId} onChange={(event) => setArtisanId(event.target.value)} disabled={!craftId}>
            <option value="">{craftId ? "Unlinked" : "Select a linked craft first"}</option>
            {artisansForCraft.map((artisan) => (
              <option key={artisan.id} value={artisan.id}>
                {artisan.name} - {artisan.place}
              </option>
            ))}
          </Select>
          {craftId && artisansForCraft.length === 0 ? (
            <p className="mt-1 text-xs text-ink-muted">No artisans are linked to this craft yet.</p>
          ) : null}
        </Field>
        <Field label="Linked workshop">
          <Select name="workshopId" defaultValue={initial?.workshopId ?? ""}>
            <option value="">Unlinked</option>
            {workshops.map((workshop) => (
              <option key={workshop.id} value={workshop.id}>
                {workshop.title}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Time taken">
          <TextInput name="timeTakenToCompleteProduct" defaultValue={initial?.timeTakenToCompleteProduct ?? ""} />
        </Field>
        <Field label="Size">
          <TextInput name="size" defaultValue={initial?.size ?? ""} />
        </Field>
        <Field label="Length (inches)">
          <TextInput name="lengthInches" type="number" step="0.01" value={length} onChange={(event) => setLength(event.target.value)} />
        </Field>
        <Field label="Breadth (inches)">
          <TextInput name="breadthInches" type="number" step="0.01" value={breadth} onChange={(event) => setBreadth(event.target.value)} />
        </Field>
        <Field label="Height (inches)">
          <TextInput name="heightInches" type="number" step="0.01" value={height} onChange={(event) => setHeight(event.target.value)} />
        </Field>
        <Field label="Market demand">
          <Select name="marketDemand" defaultValue={initial?.marketDemand ?? "UNKNOWN"}>
            {marketDemandOptions.map((option) => (
              <option key={option}>{option}</option>
            ))}
          </Select>
        </Field>
        <Field label="Cost of making">
          <TextInput name="costOfMaking" type="number" step="0.01" defaultValue={initial?.costOfMaking ?? ""} />
        </Field>
        <Field label="Selling price">
          <TextInput name="sellingPrice" type="number" step="0.01" defaultValue={initial?.sellingPrice ?? ""} />
        </Field>
        <Field label="Status">
          <Select name="status" defaultValue={initial?.status ?? "PENDING"}>
            {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
              <option key={status}>{status}</option>
            ))}
          </Select>
        </Field>
      </div>
      <GridMeasurement
        includeHeight
        onLengthBreadth={(l, b) => {
          if (l) setLength(l);
          if (b) setBreadth(b);
        }}
        onHeight={(value) => setHeight(value)}
        onFilesChange={setGridFiles}
      />
      {initial ? <ExistingMedia linkedRecordType="product" linkedRecordId={initial.id} /> : null}
      <MediaCaptureField
        files={mediaFiles}
        onFilesChange={setMediaFiles}
        title="Product media"
        description="Attach or capture product images, videos, audio notes, and documents. Image EXIF is retained and summarized in remarks."
      />
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Raw materials used">
          <TextArea name="rawMaterialsUsed" defaultValue={initial?.rawMaterialsUsed ?? ""} />
        </Field>
        <Field label="Main tools used">
          <TextArea name="mainToolsUsed" defaultValue={initial?.mainToolsUsed ?? ""} />
        </Field>
        <Field label="Function or use">
          <TextArea name="productFunctionUse" defaultValue={initial?.productFunctionUse ?? ""} />
        </Field>
        <Field label="Remarks">
          <TextArea name="remarks" defaultValue={initial?.remarks ?? ""} />
        </Field>
      </div>
      <RecordedAtField value={initial?.recordedAt} timezone={initial?.recordedTimezone} />
      <LocationFields />
      <Field label="Extra metadata JSON">
        <TextArea name="extraMetadata" placeholder='{"motif":"floral","season":"festival"}' />
      </Field>
      {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
      <div className="flex justify-end gap-2">
        <button type="button" className="field-button-secondary" onClick={() => router.back()}>
          Cancel
        </button>
        <button className="field-button" disabled={saving}>
          {saving ? "Saving..." : "Save product"}
        </button>
      </div>
    </form>
  );
}
