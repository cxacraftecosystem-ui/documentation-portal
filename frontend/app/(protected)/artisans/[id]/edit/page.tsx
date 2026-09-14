"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ArtisanForm } from "@/components/forms/ArtisanForm";
import { ArtisanQuestionnairePanel } from "@/components/ArtisanQuestionnairePanel";
import { RecordSwitcher } from "@/components/forms/RecordSwitcher";
import { FieldProvenance } from "@/components/FieldProvenance";
import { PageHeader } from "@/components/PageHeader";
import { apiFetch } from "@/lib/api";
import type { Artisan } from "@/lib/types";

export default function EditArtisanPage() {
  const params = useParams<{ id: string }>();
  const [record, setRecord] = useState<Artisan | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<Artisan>(`/artisans/${params.id}`)
      .then(setRecord)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load artisan"));
  }, [params.id]);

  return (
    <>
      <PageHeader title="Edit Artisan" />
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
          <RecordSwitcher kind="artisan" currentId={record.id} currentWorkshopId={record.workshopId} />
          <ArtisanForm initial={record} />
          <ArtisanQuestionnairePanel artisanId={record.id} />
          <FieldProvenance extraMetadata={record.extraMetadata} title="Artisan field contributions" />
        </div>
      ) : (
        <div className="text-sm text-ink-700">Loading...</div>
      )}
    </>
  );
}
