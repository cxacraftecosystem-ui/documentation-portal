"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { CoverageGrid } from "@/components/questionnaire/CoverageGrid";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { questionnaireApi, toGridView, type CoverageGrid as Grid, type CoverageRecording } from "@/lib/questionnaire/api";
import { durationLabel, shortDate } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";
import { coverageScope, listForms, listWorkshops, WORKSHOP_LABEL, type PortalForm, type PortalWorkshop } from "@/lib/questionnairePortal";

/** A take a dot can open: the cell's own listing, or (from an older server) a coverage check's take. */
export type CellTake = Pick<CoverageRecording, "recordingId" | "sessionId" | "capturedAt" | "durationMs" | "recordedBy">;

/** Where a take is read: its session's read view, scrolled to it with its transcript open. */
export function takeHref(take: Pick<CellTake, "recordingId" | "sessionId">): string {
  return `/questionnaire/sessions/${encodeURIComponent(take.sessionId ?? "")}/view?recording=${encodeURIComponent(take.recordingId)}`;
}

/** A take in the chooser: when, who, how long (owner, 1 Nov 2026). */
export function takeChoiceLabel(take: Pick<CellTake, "capturedAt" | "recordedBy" | "durationMs">): string {
  return [shortDate(take.capturedAt), take.recordedBy?.name ?? "", durationLabel(take.durationMs)].filter(Boolean).join(" · ");
}

/**
 * Workshop coverage (DESIGN §4.1): sections recorded per artisan for one instrument and workshop.
 * A dot opens that artisan × section's recording: one opens directly, several open a short
 * chooser (oldest first, by date, researcher and duration); a cell with no recording is not a
 * button at all. The cell lists its takes (0.0.6); a grid from an older server is asked through
 * the coverage check instead.
 */
export function WorkshopCoverage() {
  const router = useRouter();
  const [instruments, setInstruments] = useState<PortalForm[]>([]);
  const [workshops, setWorkshops] = useState<PortalWorkshop[]>([]);
  const [questionnaireId, setQuestionnaireId] = useState("");
  const [workshopId, setWorkshopId] = useState("");
  const [grid, setGrid] = useState<Grid | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [choosing, setChoosing] = useState<{ title: string; takes: CellTake[] } | null>(null);
  const [opening, setOpening] = useState<string | null>(null);

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

  const view = useMemo(() => (grid ? toGridView(grid) : null), [grid]);

  async function takesOf(sectionId: string, artisanId: string): Promise<CellTake[]> {
    const cell = view?.cells.find((c) => c.targetKey === `S:${sectionId}` && c.artisanId === artisanId);
    if (cell?.recordings) return cell.recordings.filter((r) => Boolean(r.sessionId));
    const answer = await questionnaireApi.checkCoverage({ questionnaireId, artisanIds: [artisanId], targets: [{ sectionId }] });
    const target = answer.targets.find((t) => t.targetKey === `S:${sectionId}`) ?? answer.targets[0];
    return (target?.perArtisan.find((p) => p.artisanId === artisanId)?.takes ?? [])
      .map((t) => ({ recordingId: t.recordingId, sessionId: t.session.id, capturedAt: t.capturedAt, durationMs: t.durationMs, recordedBy: t.recordedBy }))
      .sort((a, b) => a.capturedAt.localeCompare(b.capturedAt));
  }

  async function openRecordings(sectionId: string, artisanId: string) {
    const key = `${sectionId}:${artisanId}`;
    if (opening) return;
    setOpening(key);
    setError(null);
    try {
      const takes = await takesOf(sectionId, artisanId);
      if (takes.length === 1) router.push(takeHref(takes[0]));
      else if (takes.length > 1) {
        const section = grid?.sections.find((s) => s.sectionId === sectionId);
        const name = grid?.artisans.find((a) => a.artisanId === artisanId)?.name ?? "";
        setChoosing({ title: [section ? `${section.code} ${section.title}` : "", name].filter(Boolean).join(" · "), takes });
      }
    } catch {
      setError(qs("takesLoadError"));
    } finally {
      setOpening(null);
    }
  }

  return (
    <div className="grid gap-4">
      <div className="panel grid gap-3 p-4 md:grid-cols-2">
        <SearchableSelect value={questionnaireId} onChange={setQuestionnaireId} options={instruments.map((i) => ({ value: i.id, label: i.title }))} ariaLabel={qs("fieldInstrument")} advanceOnSelect={false} searchable />
        <SearchableSelect value={workshopId} onChange={setWorkshopId} options={workshops.map((w) => ({ value: w.id, label: w.title }))} ariaLabel={WORKSHOP_LABEL} advanceOnSelect={false} searchable />
      </div>
      {error ? <p className="text-sm text-error-600" role="alert">{error}</p> : null}
      {grid ? (
        <div className="panel overflow-x-auto p-4">
          <CoverageGrid
            grid={view ?? toGridView(grid)}
            artisans={(grid.artisans ?? []).map((a) => ({ id: a.artisanId, name: a.name }))}
            onOpenRecordings={(sectionId, artisanId) => void openRecordings(sectionId, artisanId)}
          />
        </div>
      ) : null}
      <FieldDialog open={choosing !== null} onClose={() => setChoosing(null)} title={qs("chooseRecording")} description={choosing?.title}>
        <ul className="grid gap-2" data-recording-chooser>
          {(choosing?.takes ?? []).map((take) => (
            <li key={take.recordingId}>
              <button
                type="button"
                className="flex min-h-11 w-full items-center justify-between gap-3 rounded-md border border-line-200 bg-card px-3 py-2 text-left text-sm text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40"
                onClick={() => {
                  setChoosing(null);
                  router.push(takeHref(take));
                }}
                data-recording-choice={take.recordingId}
              >
                <span>{takeChoiceLabel(take)}</span>
                <span aria-hidden className="text-ink-500">
                  ›
                </span>
              </button>
            </li>
          ))}
        </ul>
      </FieldDialog>
    </div>
  );
}
