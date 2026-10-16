"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

/** One craft, read-only. A click on its row lands here; its Update button opens the edit form. */
export default function CraftViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="craft" id={params.id} />;
}
