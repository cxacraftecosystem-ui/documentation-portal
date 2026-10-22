"use client";

import { BuilderScreen } from "@/components/questionnaires/BuilderScreen";

/**
 * The instrument builder (DESIGN §4.1). It renders the builder alone, for managers, with the page
 * header's Back (audit H15): it no longer reopens the legacy capture page under v2.
 */
export default function QuestionnaireBuilderPage() {
  return <BuilderScreen />;
}
