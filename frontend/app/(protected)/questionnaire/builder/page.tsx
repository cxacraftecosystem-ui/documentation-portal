"use client";

import QuestionnairePage from "@/app/(protected)/questionnaire/page";

/**
 * The instrument builder (DESIGN §4.1). Until the builder is split out of the capture page it is
 * that page reached at this path, which skips the v2 sessions list (`/questionnaire` shows the list
 * when `GET /questionnaire/config` says v2 is on).
 */
export default function QuestionnaireBuilderPage() {
  return <QuestionnairePage />;
}
