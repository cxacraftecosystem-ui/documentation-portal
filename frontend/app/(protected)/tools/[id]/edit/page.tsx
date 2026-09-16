"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ToolForm } from "@/components/forms/ToolForm";
import { RecordSwitcher } from "@/components/forms/RecordSwitcher";
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
      <PageHeader title="Edit Tool Documentation" />
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
          <RecordSwitcher kind="tool" currentId={record.id} currentWorkshopId={record.workshopId} />
          {/*
            `key` IS THE FORM'S RE-KEY, AND IT IS LOAD-BEARING RATHER THAN A HINT TO REACT.

            Every value on `ToolForm` is SEEDED ONCE, at construction, from `initial` — thirty-odd
            `useState(initial?.x)` calls, plus the mirror latch that decides whether "English name"
            follows "Toolkit name" and the two multi-selects seeded from the record's own links. The
            picker above navigates to another tool's edit URL, and this page keeps the previous
            record on screen until the new one arrives rather than blanking to `Loading…`. Without a
            key React would reuse the instance: the form would hold the OLD record's answers while
            `initial.id` — and therefore the PATCH's target — was already the new one, so a Save
            would write one tool's contents over another's, under a 200, with nothing on screen
            saying so. Keyed on the id, the switch remounts and every seed is re-read.
          */}
          <ToolForm key={record.id} initial={record} />
          <FieldProvenance extraMetadata={record.extraMetadata} title="Tool field contributions" />
        </div>
      ) : (
        <div className="text-sm text-neutral-600">Loading...</div>
      )}
    </>
  );
}
