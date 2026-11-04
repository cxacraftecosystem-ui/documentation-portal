"use client";

import { useRef, useState } from "react";
import { Check, X } from "lucide-react";

import {
  EMPTY_GRID_STATE,
  GRID_GROUP_HINTS,
  GRID_GROUP_LABELS,
  GRID_SECTION_HINT,
  gridAcceptLabel,
  gridProposalLabel,
  gridReduce,
  type GridEvent,
  type GridGroup,
  type GridState,
  type MeasurementMethodMarker
} from "@/components/media/gridProposal";
import { analyzeMeasurementImage } from "@/lib/media";
import { Toggle } from "@/components/ui/Toggle";
import { ButtonRow } from "@/components/ui/ButtonRow";

export type { GridDimension, GridGroup } from "@/components/media/gridProposal";
export type GridFiles = Partial<Record<GridGroup, File>>;

export function GridMeasurement({
  includeHeight = true,
  onLengthBreadth,
  onHeight,
  onFilesChange
}: {
  includeHeight?: boolean;
  onLengthBreadth: (length: string | null, breadth: string | null, method?: MeasurementMethodMarker | null) => void;
  onHeight: (inches: string, method?: MeasurementMethodMarker | null) => void;
  onFilesChange: (files: GridFiles) => void;
}) {
  const [enabled, setEnabled] = useState<Set<GridGroup>>(new Set());
  const [files, setFiles] = useState<GridFiles>({});
  const [grid, setGrid] = useState<GridState>(EMPTY_GRID_STATE);

  const pending = useRef<Partial<Record<GridGroup, number>>>({});

  function abandon(group: GridGroup): number {
    const next = (pending.current[group] ?? 0) + 1;
    pending.current[group] = next;
    return next;
  }

  function dispatch(event: Exclude<GridEvent, { type: "ACCEPT" }>) {
    setGrid((current) => gridReduce(current, event).state);
  }

  function toggle(group: GridGroup, on: boolean) {
    setEnabled((prev) => {
      const next = new Set(prev);
      if (on) next.add(group);
      else next.delete(group);
      return next;
    });
    if (!on) {
      if (files[group]) {
        const nextFiles = { ...files };
        delete nextFiles[group];
        setFiles(nextFiles);
        onFilesChange(nextFiles);
      }
      abandon(group);
      dispatch({ type: "DISCARD", group, clearStatus: true });
    }
  }

  async function pick(group: GridGroup, file: File | null) {
    if (!file) return;
    const nextFiles = { ...files, [group]: file };
    setFiles(nextFiles);
    onFilesChange(nextFiles);
    const token = abandon(group);
    dispatch({ type: "CAPTURE", group });
    try {
      const response = await analyzeMeasurementImage(file, group === "height" ? "height" : undefined);
      if (pending.current[group] !== token) return;
      dispatch({ type: "ANALYSIS", group, response });
    } catch (error) {
      if (pending.current[group] !== token) return;
      dispatch({ type: "FAILURE", group, error });
    }
  }

  function accept(group: GridGroup) {
    const { state, write } = gridReduce(grid, { type: "ACCEPT", group });
    setGrid(state);
    if (!write) return;
    if (write.group === "lengthBreadth") {
      const length = write.readings.find((reading) => reading.dimension === "length")?.value ?? null;
      const breadth = write.readings.find((reading) => reading.dimension === "breadth")?.value ?? null;
      onLengthBreadth(length, breadth, write.marker);
    } else {
      const height = write.readings.find((reading) => reading.dimension === "height")?.value;
      if (height) onHeight(height, write.marker);
    }
  }

  function renderGroup(group: GridGroup) {
    const proposal = grid.proposals[group];
    const status = grid.status[group];
    return (
      <div className="grid gap-2">
        <div className="sm:max-w-md">
          {/* eslint-disable-next-line react-hooks/refs */}
          <Toggle label={GRID_GROUP_LABELS[group]} checked={enabled.has(group)} onChange={(next) => toggle(group, next)} />
        </div>
        {enabled.has(group) ? (
          <div className="grid gap-1 pl-6">
            <p className="text-xs text-ink-muted">{GRID_GROUP_HINTS[group]}</p>
            <input
              className="field-input"
              type="file"
              accept="image/*"
              capture="environment"
              onChange={(event) => pick(group, event.target.files?.[0] ?? null)}
            />
            {status ? (
              <p className="text-xs text-ink-muted" aria-live="polite">
                {status}
              </p>
            ) : null}
            {proposal ? (
              <div className="mt-1 grid gap-2 rounded-md border border-purple-300 bg-card p-3">
                <p className="field-label">Read from the photo — not filled in yet</p>
                <p className="font-display text-xl font-bold text-ink-900">{gridProposalLabel(proposal)}</p>
                <p className="text-xs leading-5 text-ink-500">
                  AI estimate from the grid. Check it before accepting.
                  {proposal.selfReportedConfidence !== null ? (
                    <>
                      {" "}
                      The model rated itself {Math.round(proposal.selfReportedConfidence * 100)}% sure, which is its own
                      claim about itself and has never been checked against a tape measure.
                    </>
                  ) : null}
                </p>
                <ButtonRow align="start">
                  <button type="button" className="field-button" onClick={() => accept(group)}>
                    <Check className="h-4 w-4" aria-hidden />
                    {gridAcceptLabel(proposal)}
                  </button>
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={() => dispatch({ type: "DISCARD", group })}
                  >
                    <X className="h-4 w-4" aria-hidden />
                    Discard this reading
                  </button>
                </ButtonRow>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-field-100 p-4">
      <div>
        <h3 className="font-display font-bold text-lg text-ink">Document using grid</h3>
        <p className="mt-1 text-sm text-ink-muted">{GRID_SECTION_HINT}</p>
      </div>
      {renderGroup("lengthBreadth")}
      {includeHeight ? renderGroup("height") : null}
    </section>
  );
}
