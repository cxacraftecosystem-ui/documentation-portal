"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

/** One product, read-only. A click on its row lands here; its Update button opens the edit form. */
export default function ProductViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="product" id={params.id} />;
}
