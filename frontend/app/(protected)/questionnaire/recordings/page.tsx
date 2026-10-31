"use client";

import { RecordingsList } from "@/components/questionnaire/RecordingsList";
import { V2Gate } from "@/components/questionnaire/V2Gate";

/** Every take, searched and filtered by its chips (owner, 31 Oct 2026). */
export default function QuestionnaireRecordingsPage() {
  return (
    <V2Gate>
      <RecordingsList />
    </V2Gate>
  );
}
