import { questionnaireApi, type FormQuestion, type FormStructure, type GridQuestion } from "@/lib/questionnaire/api";

export type InstrumentSection = { id: string; code: string; title: string; sortOrder: number; questions: FormQuestion[] };

export type InstrumentQuestion = GridQuestion & { required: boolean; helpText: string | null; artisanField: string | null };

export async function loadInstrumentQuestions(
  questionnaireId: string
): Promise<{ sections: InstrumentSection[]; byId: Record<string, InstrumentQuestion[]>; structure: FormStructure }> {
  const structure = await questionnaireApi.formStructure(questionnaireId);
  const byId: Record<string, InstrumentQuestion[]> = {};
  for (const s of structure.sections) {
    byId[s.id] = s.questions
      .filter((q) => q.isActive)
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((q, i) => ({
        lineageId: q.lineageId ?? q.id,
        displayCode: q.displayCode ?? `${s.code}${i + 1}`,
        prompt: q.prompt,
        required: q.isRequired,
        helpText: q.helpText,
        artisanField: q.artisanField
      }));
  }
  return { sections: structure.sections, byId, structure };
}

export function requiredUnanswered(
  byId: Record<string, InstrumentQuestion[]>,
  answers: { questionLineageId?: string; text: string; artisanId?: string | null; respondentId?: string | null }[],
  subjectKeys: string[],
  recordings: { removedAt?: string | null; targets: { sectionId: string | null; questionLineageId: string | null }[]; subjects: { artisanId: string; role: string; subjectKey?: string }[] }[] = []
): number {
  const covered = new Set(
    answers.filter((a) => a.text.trim()).map((a) => `${a.artisanId ? `A:${a.artisanId}` : `R:${a.respondentId ?? ""}`}|${a.questionLineageId ?? ""}`)
  );
  for (const r of recordings) {
    if (r.removedAt) continue;
    const keys = r.subjects.filter((x) => x.role === "ANSWERED").map((x) => x.subjectKey ?? `A:${x.artisanId}`);
    for (const t of r.targets) {
      const lineages = t.questionLineageId ? [t.questionLineageId] : t.sectionId ? (byId[t.sectionId] ?? []).map((q) => q.lineageId) : [];
      for (const k of keys) for (const l of lineages) covered.add(`${k}|${l}`);
    }
  }
  let missing = 0;
  for (const questions of Object.values(byId)) {
    for (const q of questions) {
      if (!q.required) continue;
      if (subjectKeys.some((k) => !covered.has(`${k}|${q.lineageId}`))) missing += 1;
    }
  }
  return missing;
}
