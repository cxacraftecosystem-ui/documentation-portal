"use client";

import { useParams } from "next/navigation";

import { WorkshopRecordings } from "@/components/questionnaire/WorkshopRecordings";
import { RecordView } from "@/components/records/RecordView";

export default function WorkshopViewPage() {
  const params = useParams<{ id: string }>();
  return (
    <>
      <RecordView type="workshop" id={params.id} />
      <WorkshopRecordings workshopId={params.id} />
    </>
  );
}
