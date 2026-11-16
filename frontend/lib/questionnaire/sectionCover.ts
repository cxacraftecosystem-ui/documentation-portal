import { qs } from "@/lib/questionnaire/qs";

type CoverSection = { id: string; code: string; title?: string };

export function coverIds(sections: CoverSection[], chosen: readonly string[]): string[] {
  const wanted = new Set(chosen);
  return sections.filter((s) => wanted.has(s.id)).map((s) => s.id);
}

export function coverCodes(sections: CoverSection[], chosen: readonly string[]): string[] {
  const wanted = new Set(chosen);
  return sections.filter((s) => wanted.has(s.id)).map((s) => s.code);
}

export function coverHelper(codes: readonly string[]): string {
  return codes.length ? qs("countsForSections", { codes: codes.join(", ") }) : qs("marksNoSection");
}

export function coverLabel(codes: readonly string[]): string {
  if (!codes.length) return qs("wholeSession");
  return codes.length === 1 ? qs("chipSection", { code: codes[0] }) : qs("sectionsLabel", { codes: codes.join(", ") });
}

export function coverTargets(sections: CoverSection[], chosen: readonly string[]): { sectionId: string }[] {
  return coverIds(sections, chosen).map((sectionId) => ({ sectionId }));
}

export function coverOptions(sections: CoverSection[]): { value: string; label: string }[] {
  return sections.map((s) => ({ value: s.id, label: [qs("chipSection", { code: s.code }), s.title].filter(Boolean).join(" · ") }));
}

export function sectionTargetIds(targets: { sectionId?: string | null; questionLineageId?: string | null }[]): string[] {
  return targets.filter((t) => t.sectionId && !t.questionLineageId).map((t) => t.sectionId as string);
}

export function mayChangeTake(take: { recordedBy: { id: string } }, me: string | null, session: { mayChangeTakes?: boolean }): boolean {
  return Boolean(session.mayChangeTakes) || (me !== null && take.recordedBy.id === me);
}
