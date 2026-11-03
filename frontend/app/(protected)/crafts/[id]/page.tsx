"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";
import { GlossaryView } from "@/components/transcript/GlossaryView";

export default function CraftViewPage() {
  const params = useParams<{ id: string }>();
  return (
    <>
      <RecordView type="craft" id={params.id} />
      <div className="mt-5">
        <GlossaryView craftId={params.id} mediaHref={(mediaId) => `/media/${encodeURIComponent(mediaId)}`} />
      </div>
    </>
  );
}
