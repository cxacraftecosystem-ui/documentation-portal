"use client";

import { useParams } from "next/navigation";

import { useV2Redirect } from "@/components/questionnaire/V2Redirect";
import { RecordView } from "@/components/records/RecordView";

/** One interview, read-only. A click on its row lands here; its Update button opens the edit form. */
export default function InterviewViewPage() {
  const params = useParams<{ id: string }>();
  // Questionnaire v2: an interview is a session (the shim keeps the id), so the old URL lands there.
  const redirecting = useV2Redirect(`/questionnaire/sessions/${encodeURIComponent(params.id)}`);
  if (redirecting) return null;
  return <RecordView type="questionnaire" id={params.id} />;
}
