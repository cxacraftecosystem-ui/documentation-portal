"use client";

import { useParams } from "next/navigation";

import { useV2Redirect } from "@/components/questionnaires/V2Redirect";
import { RecordView } from "@/components/records/RecordView";

export default function InterviewViewPage() {
  const params = useParams<{ id: string }>();
  const redirecting = useV2Redirect(`/questionnaire/sessions/${encodeURIComponent(params.id)}`);
  if (redirecting) return null;
  return <RecordView type="questionnaire" id={params.id} />;
}
