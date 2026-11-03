"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

export default function ArtisanViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="artisan" id={params.id} />;
}
