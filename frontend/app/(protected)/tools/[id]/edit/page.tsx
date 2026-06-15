"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ToolForm } from "@/components/forms/ToolForm";
import { PageHeader } from "@/components/PageHeader";
import { apiFetch } from "@/lib/api";
import type { ToolDocumentation } from "@/lib/types";

export default function EditToolPage() {
  const params = useParams<{ id: string }>();
  const [record, setRecord] = useState<ToolDocumentation | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<ToolDocumentation>(`/tools/${params.id}`)
      .then(setRecord)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load tool"));
  }, [params.id]);

  return (
    <>
      <PageHeader title="Edit Tool Documentation" />
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {record ? <ToolForm initial={record} /> : <div className="text-sm text-neutral-600">Loading...</div>}
    </>
  );
}
