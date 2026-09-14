"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ProductForm } from "@/components/forms/ProductForm";
import { RecordSwitcher } from "@/components/forms/RecordSwitcher";
import { FieldProvenance } from "@/components/FieldProvenance";
import { PageHeader } from "@/components/PageHeader";
import { apiFetch } from "@/lib/api";
import type { ProductDocumentation } from "@/lib/types";

export default function EditProductPage() {
  const params = useParams<{ id: string }>();
  const [record, setRecord] = useState<ProductDocumentation | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<ProductDocumentation>(`/products/${params.id}`)
      .then(setRecord)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load product"));
  }, [params.id]);

  return (
    <>
      <PageHeader title="Edit Product Documentation" />
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {record ? (
        <div className="grid gap-6">
          {/*
            THE PICKER SITS ABOVE THE FORM, NOT BESIDE THE PAGE TITLE, and inside the `record` branch.
            Both placements are deliberate. Above the form, because the form is what it changes and a
            control that reloads the page underneath it belongs at the top of what it reloads. Inside
            the branch, because it takes `currentId` and `currentWorkshopId` off the loaded record —
            drawn over a null record it would have neither, and the "show this record's own workshop"
            shortcut would blink into existence a moment after the rest of the page had settled.
          */}
          <RecordSwitcher kind="product" currentId={record.id} currentWorkshopId={record.workshopId} />
          <ProductForm initial={record} />
          <FieldProvenance extraMetadata={record.extraMetadata} title="Product field contributions" />
        </div>
      ) : (
        <div className="text-sm text-ink-700">Loading...</div>
      )}
    </>
  );
}
