"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

export default function ProductViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="product" id={params.id} />;
}
