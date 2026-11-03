"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

export default function ToolViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="tool" id={params.id} />;
}
