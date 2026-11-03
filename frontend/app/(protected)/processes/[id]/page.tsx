"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

export default function ProcessViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="process" id={params.id} />;
}
