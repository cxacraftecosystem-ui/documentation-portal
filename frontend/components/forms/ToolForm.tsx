"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { RecordedAtField } from "@/components/forms/RecordedAtField";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, type GridDimension } from "@/components/media/GridMeasurement";
import { UploadProgress } from "@/components/media/UploadProgress";
import { apiFetch, listResource } from "@/lib/api";
import { locationFromForm, numericValue, parseJsonMetadata, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue } from "@/lib/forms";
import { appendRemarksWithExif, collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import type { Artisan, Craft, ToolDocumentation, Workshop } from "@/lib/types";
import { makerOptions, traditionOptions } from "@/lib/types";

export function ToolForm({ initial }: { initial?: ToolDocumentation }) {
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
  // Grid-measurable dimensions are controlled so the "Document using grid" capture can auto-fill them.
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");
  const [gridFiles, setGridFiles] = useState<Partial<Record<GridDimension, File>>>({});

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

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

  // Task 6: filter the artisan dropdown to the chosen craft (keeping any pre-existing selection).
  const artisansForCraft = craftId
    ? artisans.filter((artisan) => artisan.craftId === craftId || artisan.id === artisanId)
    : artisans;

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
        thickness: numericValue(form, "thickness"),
        weight: numericValue(form, "weight"),
        radius: numericValue(form, "radius"),
        maker: requiredText(form, "maker") || "UNKNOWN",
        traditionType: requiredText(form, "traditionType") || "UNKNOWN",
        replacementCost: numericValue(form, "replacementCost"),
        suggestionsForToolImprovement: textValue(form, "suggestionsForToolImprovement"),
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
      const saved = await apiFetch<ToolDocumentation>(initial ? `/tools/${initial.id}` : "/tools", {
        method: initial ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      // Store each captured grid photo as media linked to the tool (the measured value is already in
      // the field). Best-effort per file so one failure doesn't lose the record.
      for (const [dimension, file] of Object.entries(gridFiles) as [GridDimension, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `${dimension} grid (measurement) for ${saved.toolkitName}`,
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
    <form onSubmit={submit} className="panel grid gap-4 p-4">
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <Field label="Toolkit name" required>
          <TextInput name="toolkitName" required defaultValue={initial?.toolkitName ?? ""} />
        </Field>
        <Field label="Local name">
          <TextInput name="localName" defaultValue={initial?.localName ?? ""} />
        </Field>
        <Field label="English name">
          <TextInput name="englishName" defaultValue={initial?.englishName ?? ""} />
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
                {artisan.name} · {artisan.place}
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
        <Field label="Process used in">
          <TextInput name="processUsedIn" defaultValue={initial?.processUsedIn ?? ""} />
        </Field>
        <Field label="Material">
          <TextInput name="material" defaultValue={initial?.material ?? ""} />
        </Field>
        <Field label="Years in use">
          <TextInput name="yearsInUse" type="number" min={0} defaultValue={initial?.yearsInUse ?? ""} />
        </Field>
        <Field label="Height">
          <TextInput name="height" type="number" step="0.01" value={height} onChange={(event) => setHeight(event.target.value)} />
        </Field>
        <Field label="Width">
          <TextInput name="width" type="number" step="0.01" defaultValue={initial?.width ?? ""} />
        </Field>
        <Field label="Length (inches)">
          <TextInput name="lengthInches" type="number" step="0.01" value={length} onChange={(event) => setLength(event.target.value)} />
        </Field>
        <Field label="Breadth (inches)">
          <TextInput name="breadthInches" type="number" step="0.01" value={breadth} onChange={(event) => setBreadth(event.target.value)} />
        </Field>
        <Field label="Thickness">
          <TextInput name="thickness" type="number" step="0.01" defaultValue={initial?.thickness ?? ""} />
        </Field>
        <Field label="Weight">
          <TextInput name="weight" type="number" step="0.01" defaultValue={initial?.weight ?? ""} />
        </Field>
        <Field label="Radius">
          <TextInput name="radius" type="number" step="0.01" defaultValue={initial?.radius ?? ""} />
        </Field>
        <Field label="Replacement cost">
          <TextInput name="replacementCost" type="number" step="0.01" defaultValue={initial?.replacementCost ?? ""} />
        </Field>
        <Field label="Maker">
          <Select name="maker" defaultValue={initial?.maker ?? "UNKNOWN"}>
            {makerOptions.map((option) => (
              <option key={option}>{option}</option>
            ))}
          </Select>
        </Field>
        <Field label="Traditional or modern">
          <Select name="traditionType" defaultValue={initial?.traditionType ?? "UNKNOWN"}>
            {traditionOptions.map((option) => (
              <option key={option}>{option}</option>
            ))}
          </Select>
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
        dimensions={["length", "breadth", "height"]}
        onValue={(dimension, value) => {
          if (dimension === "length") setLength(value);
          else if (dimension === "breadth") setBreadth(value);
          else setHeight(value);
        }}
        onFilesChange={setGridFiles}
      />
      {initial ? <ExistingMedia linkedRecordType="tool" linkedRecordId={initial.id} /> : null}
      <MediaCaptureField
        files={mediaFiles}
        onFilesChange={setMediaFiles}
        title="Tool media"
        description="Attach or capture tool images, videos, audio notes, and documents. Image EXIF is retained and summarized in remarks."
      />
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Suggestions for tool improvement">
          <TextArea name="suggestionsForToolImprovement" defaultValue={initial?.suggestionsForToolImprovement ?? ""} />
        </Field>
        <Field label="Remarks">
          <TextArea name="remarks" defaultValue={initial?.remarks ?? ""} />
        </Field>
      </div>
      <RecordedAtField value={initial?.recordedAt} timezone={initial?.recordedTimezone} />
      <LocationFields />
      <Field label="Extra metadata JSON">
        <TextArea name="extraMetadata" placeholder='{"edgeCondition":"worn","storage":"wall rack"}' />
      </Field>
      {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
      <div className="flex justify-end gap-2">
        <button type="button" className="field-button-secondary" onClick={() => router.back()}>
          Cancel
        </button>
        <button className="field-button" disabled={saving}>
          {saving ? "Saving..." : "Save tool"}
        </button>
      </div>
    </form>
  );
}
