"use client";

import { RecordingsList } from "@/components/questionnaire/RecordingsList";
import { V2Gate } from "@/components/questionnaire/V2Gate";

export default function QuestionnaireRecordingsPage() {
  return (
    <V2Gate>
      <RecordingsList />
    </V2Gate>
  );
}
