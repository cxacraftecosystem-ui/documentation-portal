"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ProductForm } from "@/components/forms/ProductForm";
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
      {record ? <ProductForm initial={record} /> : <div className="text-sm text-neutral-600">Loading...</div>}
    </>
  );
}
