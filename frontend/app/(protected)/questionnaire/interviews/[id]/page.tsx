"use client";

import { useParams } from "next/navigation";

import { useInterviewRoute } from "@/components/questionnaires/InterviewRoute";
import { RecordView } from "@/components/records/RecordView";

export default function InterviewViewPage() {
  const params = useParams<{ id: string }>();
  const id = decodeURIComponent(params.id);
  const route = useInterviewRoute(id);
  if (route === "resolving") return <div className="panel p-4 text-sm text-ink-500" data-interview-resolving>Opening the interview…</div>;
  return <RecordView type="questionnaire" id={id} />;
}
