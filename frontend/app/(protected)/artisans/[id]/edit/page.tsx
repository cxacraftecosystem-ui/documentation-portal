"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ArtisanForm } from "@/components/forms/ArtisanForm";
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
      {record ? <ArtisanForm initial={record} /> : <div className="text-sm text-neutral-600">Loading...</div>}
    </>
  );
}
