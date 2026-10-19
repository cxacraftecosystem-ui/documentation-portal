import { apiFetch, buildQuery } from "@/lib/api";
import type { GridQuestion } from "@/lib/questionnaireApi";
import type { QuestionnaireSection } from "@/lib/types";

type V2Question = { id: string; prompt: string; sortOrder: number; isActive: boolean; lineageId?: string | null; displayCode?: string | null };

/**
 * The instrument's questions per section, for the workspace's section body and for mapping a
 * question take to its section. Read from `GET /questionnaire/sections`; under v2 each question also
 * carries its `lineageId` and `displayCode` (BACKEND M1), and older servers fall back to the id and
 * the section code plus position.
 */
export async function loadInstrumentQuestions(questionnaireId: string): Promise<{ sections: QuestionnaireSection[]; byId: Record<string, GridQuestion[]> }> {
  const sections = await apiFetch<QuestionnaireSection[]>(`/questionnaire/sections${buildQuery({ questionnaireId })}`);
  const byId: Record<string, GridQuestion[]> = {};
  for (const s of sections) {
    byId[s.id] = (s.questions as V2Question[])
      .filter((q) => q.isActive)
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((q, i) => ({ lineageId: q.lineageId ?? q.id, displayCode: q.displayCode ?? `${s.code}${i + 1}`, prompt: q.prompt }));
  }
  return { sections, byId };
}
