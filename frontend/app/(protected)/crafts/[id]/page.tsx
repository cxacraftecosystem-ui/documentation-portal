"use client";

import { useParams } from "next/navigation";

import { RecordView } from "@/components/records/RecordView";
import { GlossaryView } from "@/components/transcript/GlossaryView";

/** One craft, read-only, and its glossary. A click on its row lands here; its Update button opens the edit form. */
export default function CraftViewPage() {
  const params = useParams<{ id: string }>();
  return (
    <>
      <RecordView type="craft" id={params.id} />
      {/* What the craft's recordings taught (shared transcript tree). */}
      <div className="mt-5">
        <GlossaryView craftId={params.id} mediaHref={(mediaId) => `/media/${encodeURIComponent(mediaId)}`} />
      </div>
    </>
  );
}
