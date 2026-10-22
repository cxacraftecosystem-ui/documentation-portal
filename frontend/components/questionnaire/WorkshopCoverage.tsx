"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { CoverageGrid } from "@/components/questionnaire/CoverageGrid";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { questionnaireApi, toGridView, type CoverageGrid as Grid } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";
import { coverageScope, listForms, listWorkshops, WORKSHOP_LABEL, type PortalForm, type PortalWorkshop } from "@/lib/questionnairePortal";

/** Workshop coverage (DESIGN §4.1): sections recorded per artisan for one instrument and workshop. */
export function WorkshopCoverage() {
  const router = useRouter();
  const [instruments, setInstruments] = useState<PortalForm[]>([]);
  const [workshops, setWorkshops] = useState<PortalWorkshop[]>([]);
  const [questionnaireId, setQuestionnaireId] = useState("");
  const [workshopId, setWorkshopId] = useState("");
  const [grid, setGrid] = useState<Grid | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void Promise.all([listForms(), listWorkshops()])
      .then(([q, w]) => {
        setInstruments(q);
        setWorkshops(w);
        setQuestionnaireId((v) => v || q.find((i) => i.isDefault)?.id || q[0]?.id || "");
        setWorkshopId((v) => v || w[0]?.id || "");
      })
      .catch(() => setError(qs("listLoadError")));
  }, []);

  useEffect(() => {
    if (!questionnaireId || !workshopId) return;
    let cancelled = false;
    setGrid(null);
    void coverageScope(workshopId)
      .then((scope) => questionnaireApi.coverage({ questionnaireId, ...scope }))
      .then((g) => {
        if (!cancelled) setGrid(g);
      })
      .catch(() => {
        if (!cancelled) setError(qs("listLoadError"));
      });
    return () => {
      cancelled = true;
    };
  }, [questionnaireId, workshopId]);

  return (
    <div className="grid gap-4">
      <div className="panel grid gap-3 p-4 md:grid-cols-2">
        <SearchableSelect value={questionnaireId} onChange={setQuestionnaireId} options={instruments.map((i) => ({ value: i.id, label: i.title }))} ariaLabel={qs("fieldInstrument")} advanceOnSelect={false} searchable />
        <SearchableSelect value={workshopId} onChange={setWorkshopId} options={workshops.map((w) => ({ value: w.id, label: w.title }))} ariaLabel={WORKSHOP_LABEL} advanceOnSelect={false} searchable />
      </div>
      {error ? <p className="text-sm text-error-600">{error}</p> : null}
      {grid ? (
        <div className="panel overflow-x-auto p-4">
          <CoverageGrid grid={toGridView(grid)} artisans={(grid.artisans ?? []).map((a) => ({ id: a.artisanId, name: a.name }))} onOpen={(_section, artisanId) => artisanId && router.push(`/artisans/${encodeURIComponent(artisanId)}?tab=questionnaire`)} />
        </div>
      ) : null}
    </div>
  );
}
