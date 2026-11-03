"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ToolForm } from "@/components/forms/ToolForm";
import { RecordSwitcher } from "@/components/forms/RecordSwitcher";
import { ReviewNoteBanner } from "@/components/review/ReviewNoteBanner";
import { FieldProvenance } from "@/components/FieldProvenance";
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
      <PageHeader title="Update Tool Documentation" />
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {record ? (
        <div className="grid gap-6">
          <ReviewNoteBanner recordType="tool" recordId={record.id} status={record.status} />
          <RecordSwitcher kind="tool" currentId={record.id} currentWorkshopId={record.workshopId} />
          <ToolForm key={record.id} initial={record} />
          <FieldProvenance extraMetadata={record.extraMetadata} title="Tool field contributions" />
        </div>
      ) : (
        <div className="text-sm text-neutral-600">Loading...</div>
      )}
    </>
  );
}
