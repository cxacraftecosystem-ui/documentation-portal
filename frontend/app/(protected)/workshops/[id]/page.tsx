"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

/** One workshop, read-only. A click on its row lands here; its Update button opens the edit form. */
export default function WorkshopViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="workshop" id={params.id} />;
}
