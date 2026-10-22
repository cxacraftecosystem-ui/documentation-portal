"use client";

import type { CoverageGridCell, GridView as Grid } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

/**
 * The mark for a cell (DESIGN §3.2); always paired with a worded label for assistive tech. Typed or
 * recorded, either counts, and recordings are preferred (owner, amendment 2 point 4): a cell covered
 * only by typed answers reads ✎ "Typed", lighter, with "Add a recording?" in its label.
 */
export function cellMark(cell: Pick<CoverageGridCell, "state" | "count" | "partial" | "source" | "addRecording"> | null): { mark: string; label: string; tone: string } {
  if (!cell) return { mark: "○", label: qs("stateNotRecorded"), tone: "text-ink-300" };
  const typedOnly = cell.source === "TYPED" && (cell.state === "PARTIAL" || cell.state === "RECORDED");
  if (typedOnly) {
    const of = cell.partial ? ` ${cell.partial.done}/${cell.partial.of}` : "";
    return { mark: `✎${of}`, label: `${qs("stateTyped")}${cell.addRecording ? ` · ${qs("addRecordingPrompt")}` : ""}`, tone: "text-purple-400" };
  }
  switch (cell.state) {
    case "RECORDED":
      return { mark: cell.count >= 2 ? `●${cell.count}` : "●", label: qs("stateRecorded"), tone: "text-purple-700" };
    case "PARTIAL":
      return { mark: `◐ ${cell.partial ? `${cell.partial.done}/${cell.partial.of}` : ""}`.trim(), label: qs("statePartial"), tone: "text-purple-700" };
    case "NEEDS_REVIEW":
      return { mark: "?", label: qs("stateNeedsReview"), tone: "text-amber-800" };
    case "VIA_SECTION":
      return { mark: "◌", label: qs("stateViaSection"), tone: "text-purple-400" };
    case "UPLOADING":
      return { mark: "◎", label: qs("stateUploading"), tone: "text-purple-600" };
    case "ON_DEVICE":
      return { mark: "◎", label: qs("onThisDevice"), tone: "text-ink-500" };
    case "NEEDS_DECISION":
      return { mark: "⚠", label: qs("needsDecision"), tone: "text-amber-800" };
    case "COMPLETED":
      return { mark: "✓", label: qs("stateMarkedComplete"), tone: "text-success-600" };
    case "NEEDS_REDO":
      return { mark: "↻", label: qs("stateNeedsRedo"), tone: "text-amber-800" };
    default:
      return { mark: "○", label: qs("stateNotRecorded"), tone: "text-ink-300" };
  }
}

/** Sections covered for an artisan out of the instrument's sections (`2/3` on a phone row). */
export function rowSummary(grid: Grid, sectionId: string, artisanIds: string[]): { covered: number; of: number; decision: boolean } {
  const cells = grid.cells.filter((c) => c.targetKey === `S:${sectionId}` && artisanIds.includes(c.artisanId));
  return {
    covered: cells.filter((c) => c.state === "RECORDED" || c.state === "PARTIAL" || c.state === "UPLOADING" || c.state === "ON_DEVICE" || c.state === "COMPLETED").length,
    of: artisanIds.length,
    decision: cells.some((c) => c.state === "NEEDS_DECISION")
  };
}

/**
 * The session coverage grid (DESIGN §4.3): sections down, participants across on a wide screen; on
 * a phone each row is a summary (`2/3 ›`). A cell or row press opens the take sheet.
 */
export function CoverageGrid({
  grid,
  artisans,
  activeSectionId,
  onOpen
}: {
  grid: Grid;
  artisans: { id: string; name: string }[];
  activeSectionId?: string | null;
  onOpen: (sectionId: string, artisanId: string | null) => void;
}) {
  const cellFor = (sectionId: string, artisanId: string) => grid.cells.find((c) => c.targetKey === `S:${sectionId}` && c.artisanId === artisanId) ?? null;
  const ids = artisans.map((a) => a.id);
  return (
    <div data-coverage-grid>
      <table className="hidden w-full text-sm md:table">
        <thead>
          <tr className="text-left text-xs uppercase tracking-wide text-ink-500">
            <th className="py-2 pr-3 font-medium">Section</th>
            {artisans.map((a) => (
              <th key={a.id} className="px-2 py-2 font-medium">
                {a.name.split(" ")[0]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {grid.sections.map((section) => (
            <tr key={section.id} className={`border-t border-line-200 ${activeSectionId === section.id ? "bg-purple-50" : ""}`}>
              <th scope="row" className="py-2 pr-3 text-left font-normal text-ink-900">
                <span className="mr-2 font-semibold">{section.code}</span>
                {section.title}
                {activeSectionId === section.id ? <span className="ml-2 text-xs text-purple-700">← {qs("thisSitting")}</span> : null}
              </th>
              {artisans.map((a) => {
                const m = cellMark(cellFor(section.id, a.id));
                return (
                  <td key={a.id} className="px-2 py-1">
                    <button
                      type="button"
                      className={`min-h-10 min-w-10 rounded-md px-2 font-medium hover:bg-purple-50 ${m.tone}`}
                      aria-label={`${section.code} ${a.name}: ${m.label}`}
                      onClick={() => onOpen(section.id, a.id)}
                    >
                      {m.mark}
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <ul className="grid gap-1 md:hidden">
        {grid.sections.map((section) => {
          const s = rowSummary(grid, section.id, ids);
          return (
            <li key={section.id}>
              <button
                type="button"
                className="flex min-h-11 w-full items-center justify-between rounded-md border border-line-200 bg-card px-3 text-left text-sm"
                onClick={() => onOpen(section.id, null)}
              >
                <span>
                  <span className="mr-2 font-semibold">{section.code}</span>
                  {section.title}
                </span>
                <span className="text-ink-500">
                  {s.covered}/{s.of}
                  {s.decision ? <span className="ml-1 text-amber-800" aria-label={qs("needsDecision")}>⚠</span> : null} ›
                </span>
              </button>
            </li>
          );
        })}
      </ul>
      <p className="mt-2 text-xs text-ink-500" aria-hidden>
        {qs("legend")}
      </p>
    </div>
  );
}
