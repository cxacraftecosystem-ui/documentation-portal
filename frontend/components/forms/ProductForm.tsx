"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { apiFetch, listResource } from "@/lib/api";
import { locationFromForm, numericValue, parseJsonMetadata, requiredText, textValue } from "@/lib/forms";
import type { Artisan, Craft, ProductDocumentation, Workshop } from "@/lib/types";
import { marketDemandOptions, productTypes } from "@/lib/types";

export function ProductForm({ initial }: { initial?: ProductDocumentation }) {
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
        productName: requiredText(form, "productName"),
        localName: textValue(form, "localName"),
        productType: requiredText(form, "productType") || "OTHER",
        timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
        size: textValue(form, "size"),
        costOfMaking: numericValue(form, "costOfMaking"),
        sellingPrice: numericValue(form, "sellingPrice"),
        marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
        rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
        mainToolsUsed: textValue(form, "mainToolsUsed"),
        productFunctionUse: textValue(form, "productFunctionUse"),
        remarks: textValue(form, "remarks"),
        artisanId: textValue(form, "artisanId"),
        craftId: textValue(form, "craftId"),
        workshopId: textValue(form, "workshopId"),
        status: requiredText(form, "status") || "PENDING",
        location: locationFromForm(form),
        extraMetadata: parseJsonMetadata(form.get("extraMetadata"))
      };
      await apiFetch(initial ? `/products/${initial.id}` : "/products", {
        method: initial ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      router.push("/products");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save product record");
    } finally {
      setSaving(false);
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
        <Field label="Time taken">
          <TextInput name="timeTakenToCompleteProduct" defaultValue={initial?.timeTakenToCompleteProduct ?? ""} />
        </Field>
        <Field label="Size">
          <TextInput name="size" defaultValue={initial?.size ?? ""} />
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
      <LocationFields />
      <Field label="Extra metadata JSON">
        <TextArea name="extraMetadata" placeholder='{"motif":"floral","season":"festival"}' />
      </Field>
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
