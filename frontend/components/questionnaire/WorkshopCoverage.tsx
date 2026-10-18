"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { CoverageGrid } from "@/components/questionnaire/CoverageGrid";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { apiFetch, listResource } from "@/lib/api";
import { questionnaireApi, toGridView, type CoverageGrid as Grid } from "@/lib/questionnaireApi";
import { qs } from "@/lib/strings/qs";
import type { Questionnaire, Workshop } from "@/lib/types";

/** Workshop coverage (DESIGN §4.1): sections recorded per artisan for one instrument and workshop. */
export function WorkshopCoverage() {
  const router = useRouter();
  const [instruments, setInstruments] = useState<Questionnaire[]>([]);
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [questionnaireId, setQuestionnaireId] = useState("");
  const [workshopId, setWorkshopId] = useState("");
  const [grid, setGrid] = useState<Grid | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void Promise.all([apiFetch<Questionnaire[]>("/questionnaires"), listResource<Workshop>("/workshops", { pageSize: 100 })])
      .then(([q, w]) => {
        setInstruments(q);
        setWorkshops(w.items ?? []);
        setQuestionnaireId((v) => v || q.find((i) => i.isDefault)?.id || q[0]?.id || "");
        setWorkshopId((v) => v || w.items?.[0]?.id || "");
      })
      .catch(() => setError(qs("listLoadError")));
  }, []);

  useEffect(() => {
    if (!questionnaireId || !workshopId) return;
    let cancelled = false;
    setGrid(null);
    void questionnaireApi
      .coverage({ questionnaireId, workshopId })
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
        <SearchableSelect value={workshopId} onChange={setWorkshopId} options={workshops.map((w) => ({ value: w.id, label: w.title }))} ariaLabel={qs("fieldWorkshop")} advanceOnSelect={false} searchable />
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
