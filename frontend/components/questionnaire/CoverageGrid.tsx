"use client";

import type { CoverageGridCell, GridView as Grid } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

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

export function rowSummary(grid: Grid, sectionId: string, artisanIds: string[]): { covered: number; of: number; decision: boolean } {
  const cells = grid.cells.filter((c) => c.targetKey === `S:${sectionId}` && artisanIds.includes(c.artisanId));
  return {
    covered: cells.filter((c) => c.state === "RECORDED" || c.state === "PARTIAL" || c.state === "UPLOADING" || c.state === "ON_DEVICE" || c.state === "COMPLETED").length,
    of: artisanIds.length,
    decision: cells.some((c) => c.state === "NEEDS_DECISION")
  };
}

export function cellHasRecordings(cell: Pick<CoverageGridCell, "count" | "source" | "recordings"> | null): boolean {
  if (!cell) return false;
  if (cell.recordings) return cell.recordings.some((r) => Boolean(r.sessionId));
  return cell.count > 0 && cell.source !== "TYPED";
}

export function ArtisanName({ name }: { name: string }) {
  return (
    <span
      tabIndex={0}
      className="group/name relative inline-flex max-w-[6.5rem] rounded-sm align-middle outline-offset-2"
      aria-label={name}
      data-artisan-name={name}
    >
      <span className="truncate" aria-hidden>
        {name}
      </span>
      <span
        aria-hidden
        className="pointer-events-none invisible absolute -left-2 -top-1.5 z-20 whitespace-nowrap rounded-md border border-line-200 bg-card px-2 py-1 text-base font-semibold normal-case tracking-normal text-ink-900 opacity-0 shadow-md transition-opacity group-hover/name:visible group-hover/name:opacity-100 group-focus/name:visible group-focus/name:opacity-100"
        data-artisan-full-name
      >
        {name}
      </span>
    </span>
  );
}

export function CoverageGrid({
  grid,
  artisans,
  activeSectionId,
  onOpen,
  onOpenRecordings
}: {
  grid: Grid;
  artisans: { id: string; name: string }[];
  activeSectionId?: string | null;
  onOpen?: (sectionId: string, artisanId: string | null) => void;
  onOpenRecordings?: (sectionId: string, artisanId: string) => void;
}) {
  const cellFor = (sectionId: string, artisanId: string) => grid.cells.find((c) => c.targetKey === `S:${sectionId}` && c.artisanId === artisanId) ?? null;
  const ids = artisans.map((a) => a.id);
  const recordings = Boolean(onOpenRecordings);
  return (
    <div data-coverage-grid={recordings ? "recordings" : "takes"}>
      <table className={`${recordings ? "table" : "hidden md:table"} w-full text-sm`}>
        <thead>
          <tr className="text-left text-xs uppercase tracking-wide text-ink-500">
            <th className={`py-2 pr-3 font-medium ${recordings ? "sticky left-0 z-10 bg-card" : ""}`}>{qs("colSection")}</th>
            {artisans.map((a) => (
              <th key={a.id} className="px-2 py-2 font-medium" data-artisan-column={a.id}>
                <ArtisanName name={a.name} />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {grid.sections.map((section) => (
            <tr key={section.id} className={`border-t border-line-200 ${activeSectionId === section.id ? "bg-purple-50" : ""}`}>
              <th scope="row" className={`py-2 pr-3 text-left font-normal text-ink-900 ${recordings ? "sticky left-0 z-10 bg-card" : ""}`}>
                <span className="mr-2 font-semibold">{section.code}</span>
                {section.title}
                {activeSectionId === section.id ? <span className="ml-2 text-xs text-purple-700">← {qs("thisSitting")}</span> : null}
              </th>
              {artisans.map((a) => {
                const cell = cellFor(section.id, a.id);
                const m = cellMark(cell);
                const label = `${section.code} ${a.name}: ${m.label}`;
                if (recordings && !cellHasRecordings(cell))
                  return (
                    <td key={a.id} className="px-2 py-1">
                      <span role="img" className={`inline-grid min-h-10 min-w-10 place-items-center px-2 font-medium ${m.tone}`} aria-label={label} data-coverage-cell={`${section.id}:${a.id}`}>
                        {m.mark}
                      </span>
                    </td>
                  );
                return (
                  <td key={a.id} className="px-2 py-1">
                    <button
                      type="button"
                      className={`min-h-10 min-w-10 rounded-md px-2 font-medium hover:bg-purple-50 dark:hover:bg-purple-950/40 ${m.tone}`}
                      aria-label={label}
                      onClick={() => (onOpenRecordings ? onOpenRecordings(section.id, a.id) : onOpen?.(section.id, a.id))}
                      data-coverage-cell={`${section.id}:${a.id}`}
                      data-opens={recordings ? "recordings" : "take"}
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
      <ul className={recordings ? "hidden" : "grid gap-1 md:hidden"}>
        {grid.sections.map((section) => {
          const s = rowSummary(grid, section.id, ids);
          return (
            <li key={section.id}>
              <button
                type="button"
                className="flex min-h-11 w-full items-center justify-between rounded-md border border-line-200 bg-card px-3 text-left text-sm"
                onClick={() => onOpen?.(section.id, null)}
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
