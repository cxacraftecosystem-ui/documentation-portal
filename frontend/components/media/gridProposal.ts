import { classifyMeasurementFailure, measurementBodyFailure } from "@/lib/measurementFailure";
import type { MeasurementAnalysisResponse, MeasurementMethodMarker } from "@/lib/media";

export type { MeasurementMethodMarker };

export type GridDimension = "length" | "breadth" | "height";

export type GridGroup = "lengthBreadth" | "height";

export type GridReading = {
  dimension: GridDimension;
  value: string;
  label: string;
};

export type GridProposal = {
  group: GridGroup;
  readings: GridReading[];
  marker: MeasurementMethodMarker | null;
  selfReportedConfidence: number | null;
};

export type GridWrite = {
  group: GridGroup;
  readings: GridReading[];
  marker: MeasurementMethodMarker | null;
};

export const GRID_ANALYZING_STATUS = "Analyzing…";

export const GRID_UNREADABLE_STATUS = "Couldn't read a value — enter it manually";

export const GRID_FAILED_STATUS = "Analysis failed — enter it manually";

export const GRID_SECTION_HINT =
  "Place the object on a 1-inch grid sheet. Length and breadth are read from a single top-down " +
  "photo; height needs its own side-on photo. Reading the photo needs a connection, and the " +
  "inches it returns are offered for you to check and accept — nothing is written into a " +
  "field until you press the button.";

export const GRID_GROUP_HINTS: Readonly<Record<GridGroup, string>> = {
  lengthBreadth: "Top-down photo of the object on the grid — offers both length and breadth.",
  height: "Side-on photo of the object against the grid — offers a height."
};

export const GRID_GROUP_LABELS: Readonly<Record<GridGroup, string>> = {
  lengthBreadth: "Length & breadth (one photo)",
  height: "Height (one photo)"
};

export function gridProposalLabel(proposal: GridProposal): string {
  return proposal.readings.map((reading) => reading.label).join(" · ");
}

export function gridReadStatus(proposal: GridProposal): string {
  return `Read ${gridProposalLabel(proposal)} — check it against the object`;
}

export function gridAcceptLabel(proposal: GridProposal): string {
  return `Use ${gridProposalLabel(proposal)}`;
}

export function gridFilledStatus(write: GridWrite): string {
  return `Filled ${write.readings.map((reading) => reading.label).join(" · ")} — still editable`;
}

export const GRID_DISCARDED_STATUS = "Reading discarded — enter the value manually if you need it.";

export function gridFailureStatus(error: unknown): string {
  return classifyMeasurementFailure(error).sentence;
}

const READING_LABEL: Readonly<Record<GridDimension, (value: string) => string>> = {
  length: (value) => `L ${value}"`,
  breadth: (value) => `B ${value}"`,
  height: (value) => `${value} in`
};

export function formatGridInches(raw: unknown): string | null {
  if (raw === null || raw === undefined) return null;
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return null;
  const rounded = value.toFixed(2);
  if (Number(rounded) <= 0) return null;
  return rounded;
}

type AnalysisKey = keyof NonNullable<MeasurementAnalysisResponse["analysis"]>;

const GROUP_READINGS: Readonly<Record<GridGroup, ReadonlyArray<{ dimension: GridDimension; key: AnalysisKey }>>> = {
  lengthBreadth: [
    { dimension: "length", key: "lengthInches" },
    { dimension: "breadth", key: "breadthInches" }
  ],
  height: [{ dimension: "height", key: "valueInches" }]
};

export function readGridAnalysis(
  group: GridGroup,
  response: MeasurementAnalysisResponse
): { proposal: GridProposal | null; status: string } {
  const bodyFailure = measurementBodyFailure(response);
  if (bodyFailure) return { proposal: null, status: bodyFailure.sentence };

  const analysis = response.analysis ?? null;
  const readings: GridReading[] = [];
  for (const { dimension, key } of GROUP_READINGS[group]) {
    const value = formatGridInches(analysis?.[key]);
    if (value) readings.push({ dimension, value, label: READING_LABEL[dimension](value) });
  }
  if (!readings.length) return { proposal: null, status: GRID_UNREADABLE_STATUS };
  const confidence = response.selfReportedConfidence;
  const proposal: GridProposal = {
    group,
    readings,
    marker: response.methodMarker ?? null,
    selfReportedConfidence: typeof confidence === "number" && Number.isFinite(confidence) ? confidence : null
  };
  return { proposal, status: gridReadStatus(proposal) };
}

export type GridState = {
  proposals: Partial<Record<GridGroup, GridProposal>>;
  status: Partial<Record<GridGroup, string>>;
};

export const EMPTY_GRID_STATE: GridState = { proposals: {}, status: {} };

export type GridEvent =
  | { type: "CAPTURE"; group: GridGroup }
  | { type: "ANALYSIS"; group: GridGroup; response: MeasurementAnalysisResponse }
  | { type: "FAILURE"; group: GridGroup; error: unknown }
  | { type: "ACCEPT"; group: GridGroup }
  | { type: "DISCARD"; group: GridGroup; clearStatus?: boolean };

export function gridReduce(state: GridState, event: GridEvent): { state: GridState; write: GridWrite | null } {
  switch (event.type) {
    case "CAPTURE":
      return {
        state: {
          proposals: withoutGroup(state.proposals, event.group),
          status: { ...state.status, [event.group]: GRID_ANALYZING_STATUS }
        },
        write: null
      };
    case "ANALYSIS": {
      const read = readGridAnalysis(event.group, event.response);
      return {
        state: {
          proposals: read.proposal
            ? { ...state.proposals, [event.group]: read.proposal }
            : withoutGroup(state.proposals, event.group),
          status: { ...state.status, [event.group]: read.status }
        },
        write: null
      };
    }
    case "FAILURE":
      return {
        state: {
          proposals: withoutGroup(state.proposals, event.group),
          status: { ...state.status, [event.group]: gridFailureStatus(event.error) }
        },
        write: null
      };
    case "ACCEPT": {
      const proposal = state.proposals[event.group];
      if (!proposal) return { state, write: null };
      const write: GridWrite = { group: proposal.group, readings: proposal.readings, marker: proposal.marker };
      return {
        state: {
          proposals: withoutGroup(state.proposals, event.group),
          status: { ...state.status, [event.group]: gridFilledStatus(write) }
        },
        write
      };
    }
    case "DISCARD":
      return {
        state: {
          proposals: withoutGroup(state.proposals, event.group),
          status: event.clearStatus
            ? withoutGroup(state.status, event.group)
            : { ...state.status, [event.group]: GRID_DISCARDED_STATUS }
        },
        write: null
      };
  }
}

function withoutGroup<T>(map: Partial<Record<GridGroup, T>>, group: GridGroup): Partial<Record<GridGroup, T>> {
  if (!(group in map)) return map;
  const next = { ...map };
  delete next[group];
  return next;
}
