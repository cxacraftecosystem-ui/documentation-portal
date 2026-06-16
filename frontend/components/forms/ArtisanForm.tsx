"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { RecordedAtField } from "@/components/forms/RecordedAtField";
import { apiFetch, listResource } from "@/lib/api";
import { locationFromForm, parseJsonMetadata, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue } from "@/lib/forms";
import { appendRemarksWithExif, collectExifMetadata, exifMetadataToRemark, uploadMediaBatch } from "@/lib/media";
import type { Artisan, Craft } from "@/lib/types";

export function ArtisanForm({ initial }: { initial?: Artisan }) {
  const router = useRouter();
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    listResource<Craft>("/crafts", { pageSize: 100 })
      .then((result) => setCrafts(result.items))
      .catch(() => setCrafts([]));
  }, []);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const parsedMetadata = parseJsonMetadata(form.get("extraMetadata")) ?? {};
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        gender: textValue(form, "gender"),
        phone: textValue(form, "phone"),
        email: textValue(form, "email"),
        place: requiredText(form, "place"),
        address: textValue(form, "address"),
        notes: appendRemarksWithExif(textValue(form, "notes") as string | null, exifRemark),
        craftId: textValue(form, "craftId"),
        status: requiredText(form, "status") || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        extraMetadata: exifItems.length ? { ...parsedMetadata, mediaExif: exifItems } : parsedMetadata
      };
      const saved = await apiFetch<Artisan>(initial ? `/artisans/${initial.id}` : "/artisans", {
        method: initial ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      if (mediaFiles.length) {
        await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "artisan",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.name}`,
          location,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
        });
      }
      router.push("/artisans");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save artisan");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="panel grid gap-4 p-4">
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Artisan name" required>
          <TextInput name="name" required defaultValue={initial?.name ?? ""} />
        </Field>
        <Field label="Local name">
          <TextInput name="localName" defaultValue={initial?.localName ?? ""} />
        </Field>
        <Field label="Craft" required>
          <Select name="craftId" required defaultValue={initial?.craftId ?? ""}>
            <option value="">Select craft</option>
            {crafts.map((craft) => (
              <option value={craft.id} key={craft.id}>
                {craft.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Place" required>
          <TextInput name="place" required defaultValue={initial?.place ?? ""} />
        </Field>
        <Field label="Gender">
          <TextInput name="gender" defaultValue={initial?.gender ?? ""} />
        </Field>
        <Field label="Phone">
          <TextInput name="phone" defaultValue={initial?.phone ?? ""} />
        </Field>
        <Field label="Email">
          <TextInput name="email" type="email" defaultValue={initial?.email ?? ""} />
        </Field>
        <Field label="Status">
          <Select name="status" defaultValue={initial?.status ?? "PENDING"}>
            {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
              <option key={status}>{status}</option>
            ))}
          </Select>
        </Field>
        <Field label="Address">
          <TextArea name="address" defaultValue={initial?.address ?? ""} />
        </Field>
        <Field label="Notes">
          <TextArea name="notes" defaultValue={initial?.notes ?? ""} />
        </Field>
      </div>
      <MediaCaptureField
        files={mediaFiles}
        onFilesChange={setMediaFiles}
        title="Artisan media"
        description="Attach or capture artisan images, audio introductions, videos, and documents. Image EXIF is retained and summarized in notes."
      />
      <RecordedAtField value={initial?.recordedAt} timezone={initial?.recordedTimezone} />
      <LocationFields />
      <Field label="Extra metadata JSON">
        <TextArea name="extraMetadata" placeholder='{"language":"Hindi","cluster":"..." }' />
      </Field>
      <div className="flex justify-end gap-2">
        <button type="button" className="field-button-secondary" onClick={() => router.back()}>
          Cancel
        </button>
        <button className="field-button" disabled={saving}>
          {saving ? "Saving..." : "Save artisan"}
        </button>
      </div>
    </form>
  );
}
