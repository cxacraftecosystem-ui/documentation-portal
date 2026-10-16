"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";

/** One interview, read-only. A click on its row lands here; its Update button opens the edit form. */
export default function InterviewViewPage() {
  const params = useParams<{ id: string }>();
  return <RecordView type="questionnaire" id={params.id} />;
}
