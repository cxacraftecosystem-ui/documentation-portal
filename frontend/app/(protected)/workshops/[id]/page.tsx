"use client";

import { useParams } from "next/navigation";

import { WorkshopRecordings } from "@/components/questionnaire/WorkshopRecordings";
import { RecordView } from "@/components/records/RecordView";

/**
 * One workshop, read-only. A click on its row lands here; its Update button opens the edit form.
 * Below it, the workshop's latest questionnaire takes with their chips (owner, 31 Oct 2026).
 */
export default function WorkshopViewPage() {
  const params = useParams<{ id: string }>();
  return (
    <>
      <RecordView type="workshop" id={params.id} />
      <WorkshopRecordings workshopId={params.id} />
    </>
  );
}
