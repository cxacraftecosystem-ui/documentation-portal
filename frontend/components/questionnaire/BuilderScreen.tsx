"use client";

import { ClipboardList } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { QuestionnaireAdminEditor } from "@/components/questionnaire/InstrumentBuilder";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { apiFetch, buildQuery } from "@/lib/api";
import { canManageQuestionnaire } from "@/lib/permissions";
import { qs } from "@/lib/strings/qs";
import type { Questionnaire, QuestionnaireSection } from "@/lib/types";

/**
 * `/questionnaire/builder` (DESIGN §4.1, audit H15): the instrument builder and nothing else. It used
 * to be the legacy capture page reached at another path, so under v2 a researcher pressing "Builder"
 * landed in the old interview form. Managers only (`canManageQuestionnaire`, the server's own rule
 * for section and question writes); the page header carries the one Back control.
 */
export function BuilderScreen() {
  const { user } = useAuth();
  const allowed = canManageQuestionnaire(user);
  const [instruments, setInstruments] = useState<Questionnaire[]>([]);
  const [questionnaireId, setQuestionnaireId] = useState<string | null>(null);
  const [sections, setSections] = useState<QuestionnaireSection[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(async (instrumentId: string | null) => {
    const mine = (generation.current += 1);
    try {
      const [list, rows] = await Promise.all([
        apiFetch<Questionnaire[]>("/questionnaires"),
        apiFetch<QuestionnaireSection[]>(`/questionnaire/sections${buildQuery({ questionnaireId: instrumentId ?? undefined })}`)
      ]);
      if (mine !== generation.current) return;
      setInstruments(list);
      setQuestionnaireId(instrumentId ?? rows[0]?.questionnaireId ?? list.find((i) => i.isDefault)?.id ?? list[0]?.id ?? null);
      setSections(rows);
      setError(null);
    } catch (err) {
      if (mine === generation.current) setError(err instanceof Error ? err.message : qs("recordLoadError"));
    }
  }, []);

  useEffect(() => {
    if (allowed) void load(null);
  }, [allowed, load]);

  const header = <PageHeader title={qs("builder")} icon={<ClipboardList className="h-5 w-5" aria-hidden />} />;
  if (!allowed) {
    return (
      <>
        {header}
        <RestrictedPanel title={qs("builderLocked")} body={qs("builderLockedBody")} />
      </>
    );
  }

  return (
    <>
      {header}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <div className="panel mb-4 grid gap-1 p-4" data-builder-instrument>
        <span className="field-label">{qs("fieldInstrument")}</span>
        <SearchableSelect
          value={questionnaireId ?? ""}
          onChange={(value) => void load(value || null)}
          options={instruments.map((i) => ({ value: i.id, label: i.title }))}
          searchable
          advanceOnSelect={false}
          ariaLabel={qs("fieldInstrument")}
        />
      </div>
      {sections === null ? (
        <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>
      ) : (
        <QuestionnaireAdminEditor sections={sections} questionnaireId={questionnaireId} onChanged={() => load(questionnaireId)} defaultOpen />
      )}
    </>
  );
}
