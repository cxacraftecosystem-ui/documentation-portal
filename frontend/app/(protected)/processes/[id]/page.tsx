"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

/** One process, read-only. A click on its row lands here; its Update button opens the edit form. */
export default function ProcessViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="process" id={params.id} />;
}
