"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { apiFetch, listResource } from "@/lib/api";
import { locationFromForm, numericValue, parseJsonMetadata, requiredText, textValue } from "@/lib/forms";
import type { Artisan, Craft, ToolDocumentation, Workshop } from "@/lib/types";
import { makerOptions, traditionOptions } from "@/lib/types";

export function ToolForm({ initial }: { initial?: ToolDocumentation }) {
  const router = useRouter();
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

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

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    try {
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
        height: numericValue(form, "height"),
        width: numericValue(form, "width"),
        thickness: numericValue(form, "thickness"),
        weight: numericValue(form, "weight"),
        radius: numericValue(form, "radius"),
        maker: requiredText(form, "maker") || "UNKNOWN",
        traditionType: requiredText(form, "traditionType") || "UNKNOWN",
        replacementCost: numericValue(form, "replacementCost"),
        suggestionsForToolImprovement: textValue(form, "suggestionsForToolImprovement"),
        remarks: textValue(form, "remarks"),
        artisanId: textValue(form, "artisanId"),
        craftId: textValue(form, "craftId"),
        workshopId: textValue(form, "workshopId"),
        status: requiredText(form, "status") || "PENDING",
        location: locationFromForm(form),
        extraMetadata: parseJsonMetadata(form.get("extraMetadata"))
      };
      await apiFetch(initial ? `/tools/${initial.id}` : "/tools", {
        method: initial ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      router.push("/tools");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save tool record");
    } finally {
      setSaving(false);
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
          <TextInput name="craftName" required defaultValue={initial?.craftName ?? ""} />
        </Field>
        <Field label="Linked craft">
          <Select name="craftId" defaultValue={initial?.craftId ?? ""}>
            <option value="">Unlinked</option>
            {crafts.map((craft) => (
              <option key={craft.id} value={craft.id}>
                {craft.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Place" required>
          <TextInput name="place" required defaultValue={initial?.place ?? ""} />
        </Field>
        <Field label="Artisan name" required>
          <TextInput name="artisanName" required defaultValue={initial?.artisanName ?? ""} />
        </Field>
        <Field label="Linked artisan">
          <Select name="artisanId" defaultValue={initial?.artisanId ?? ""}>
            <option value="">Unlinked</option>
            {artisans.map((artisan) => (
              <option key={artisan.id} value={artisan.id}>
                {artisan.name} · {artisan.place}
              </option>
            ))}
          </Select>
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
          <TextInput name="height" type="number" step="0.01" defaultValue={initial?.height ?? ""} />
        </Field>
        <Field label="Width">
          <TextInput name="width" type="number" step="0.01" defaultValue={initial?.width ?? ""} />
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
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Suggestions for tool improvement">
          <TextArea name="suggestionsForToolImprovement" defaultValue={initial?.suggestionsForToolImprovement ?? ""} />
        </Field>
        <Field label="Remarks">
          <TextArea name="remarks" defaultValue={initial?.remarks ?? ""} />
        </Field>
      </div>
      <LocationFields />
      <Field label="Extra metadata JSON">
        <TextArea name="extraMetadata" placeholder='{"edgeCondition":"worn","storage":"wall rack"}' />
      </Field>
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
