/**
 * WHAT A GRID PHOTOGRAPH SAID, HELD UNTIL SOMEBODY ACCEPTS IT — the rules half of
 * `components/media/GridMeasurement.tsx`, with no React in it.
 *
 * ── THE DEFECT THIS ENDS, AND IT IS ALREADY IN THE DATABASE ─────────────────────────────────────
 *
 * `GridMeasurement.tsx` called `onLengthBreadth(...)` / `onHeight(...)` straight out of the
 * `analyzeMeasurementImage` success block. So a vision model's estimate of a craft object
 * photographed on a sheet of graph paper landed in `ProductForm`'s and `ToolForm`'s `lengthInches` /
 * `breadthInches` / `heightInches` state with nobody's consent, and `records.merge_field_provenance`
 * then stamped every changed non-empty field with the `{by, byName, at}` of the account that pressed
 * Save — those columns are not in its `PROVENANCE_SKIP_FIELDS`. The row did not merely fail to say a
 * machine produced the number: **it positively asserted that a NAMED HUMAN had measured it**, and
 * that dimension is printed as a documented measurement by `services/record_fields.py`, read by
 * somebody costing a production run, and carried into the report a Development Commissioner's office
 * receives.
 *
 * ── THE RULE ────────────────────────────────────────────────────────────────────────────────────
 *
 *     A MACHINE-PRODUCED VALUE IS A PROPOSAL ON SCREEN UNTIL A PERSON ACCEPTS IT.
 *
 * Acceptance IS the signature. Once a person has pressed the button, `{by, byName, at}` is a true
 * sentence about the row again — which is why the remedy is a button rather than stripping the human
 * stamp off a machine-filled dimension, and why {@link GridProposal} carries the method marker
 * through the accept instead of dropping it.
 *
 * **IT IS UNCONDITIONAL.** The wire carries a `requiresAcceptance` flag and nothing here reads it.
 * A server that forgot to set it, or one that predates it, must not be able to turn a proposal back
 * into an auto-fill — the rule is this client's, not the deployment's.
 *
 * ── WHY THE RULES LIVE HERE AND NOT IN THE COMPONENT ────────────────────────────────────────────
 *
 * **This repository has no React renderer in its devDependencies** — Playwright is the whole of it —
 * so a judgement written inside JSX is only ever exercised by somebody looking at a screen. "Nothing
 * is written until the button is pressed" is precisely the judgement that must not be checked that
 * way, because the failing state looks identical to the working one (a number in a box) until a
 * ministry reads the document. {@link gridReduce} is the component's whole state machine, it returns
 * the write as a VALUE rather than performing it, and `e2e/grid-measurement-proposal-unit.spec.ts`
 * drives it: every event but `ACCEPT` must answer `write: null`.
 */

import { classifyMeasurementFailure, measurementBodyFailure } from "@/lib/measurementFailure";
import type { MeasurementAnalysisResponse, MeasurementMethodMarker } from "@/lib/media";

export type { MeasurementMethodMarker };

/** A dimension the grid control can offer. */
export type GridDimension = "length" | "breadth" | "height";

/**
 * The two photographs, and therefore the two independent offers.
 *
 * `lengthBreadth` is ONE photograph that yields TWO dimensions, which is why a proposal holds a list
 * of readings rather than a single value: a top-down shot from which the model read a length and no
 * breadth is an ordinary outcome, and it must be acceptable for the half it did read.
 */
export type GridGroup = "lengthBreadth" | "height";

/** One dimension a photograph offered, already in the exact form it would be written. */
export type GridReading = {
  dimension: GridDimension;
  /** The string that goes into the form's number box — see {@link formatGridInches} for the rounding. */
  value: string;
  /** How this one reading is printed. Android's spelling. */
  label: string;
};

/**
 * A reading that is on screen and in NOTHING else.
 *
 * Nothing in this type is in form state. It exists exactly as long as the researcher has not decided:
 * it is created when an analysis returns a usable number, and it is destroyed by accepting it, by
 * discarding it, by re-capturing the photograph behind it, and by unchecking the dimension.
 */
export type GridProposal = {
  group: GridGroup;
  readings: GridReading[];
  /**
   * WHAT PRODUCED THE NUMBER, TRAVELLING WITH THE NUMBER, so the accept can send it back.
   *
   * `POST /media/analyze-measurement` answers with `methodMarker` beside the analysis and a client
   * echoes it back verbatim on the save; the server writes the method BESIDE `{by, byName, at}`, and
   * the row then reads *a vision model estimated this, and this person accepted it into the record at
   * that moment*. Null where the server did not send one — an older deployment — and a save carrying
   * no marker is recorded as `UNRECORDED`, which is honest, distinguishable, and never the false
   * human claim. **It must never be defaulted to TYPED.**
   */
  marker: MeasurementMethodMarker | null;
  /**
   * The model's own confidence on the 0–1 scale the prompt asked for, or null.
   *
   * SELF-REPORTED AND UNCALIBRATED. Nothing in this repository has ever checked it against a tape
   * measure, which is why the wire key is `selfReportedConfidence` rather than `confidence` and why
   * `confidenceIsCalibrated: false` rides beside it. It is shown to the researcher — better seen than
   * not, when they are deciding whether to trust a proposal — and **it must never gate anything**.
   */
  selfReportedConfidence: number | null;
};

/** What {@link gridReduce} hands back on an ACCEPT, and the only thing in this module that is a write. */
export type GridWrite = {
  group: GridGroup;
  readings: GridReading[];
  marker: MeasurementMethodMarker | null;
};

/* ────────────────────────────────────────────────────────────────────────────
 * The sentences — Android's, verbatim, except where Android has none
 * ──────────────────────────────────────────────────────────────────────────── */

/** While the photograph is with the model. */
export const GRID_ANALYZING_STATUS = "Analyzing…";

/**
 * The model answered and there was no usable number in the answer.
 *
 * NOT an error, and worded so: the provider was reached, it looked, and the grid was not readable.
 * Re-photographing in better light is the right next move, and typing the number is always available.
 */
export const GRID_UNREADABLE_STATUS = "Couldn't read a value — enter it manually";

/**
 * The last resort for a failure this client cannot classify. Android's sentence, and the record of
 * what the handset still collapses every failure into.
 *
 * NO LONGER REACHABLE FROM {@link gridFailureStatus}: `lib/measurementFailure.ts` distinguishes seven
 * causes, because which failure this was decides what the researcher should do next and the server
 * pays to keep them apart. This is the one place the web deliberately says MORE than the handset, and
 * the divergence is one to close on Android — the sentences it lacks are for states it has no string
 * for at all, so copying is impossible.
 */
export const GRID_FAILED_STATUS = "Analysis failed — enter it manually";

/**
 * The hint under the whole section, and the reason it is one sentence longer than it used to be.
 *
 * IT USED TO PROMISE A FILL: *"The measured inches auto-fill the matching field(s) (still
 * editable)"*. A hint that describes a write the control no longer makes reads as a broken control
 * rather than a deliberate one, so the copy moves with the behaviour in the same edit.
 *
 * AND IT NOW SAYS THAT IT NEEDS A CONNECTION, which is the half this client was missing outright.
 * `POST /media/analyze-measurement` is network-only: no queue, no outbox, no retry — the staged
 * journal covers the photo UPLOAD and not the analysis — so in a courtyard with no signal this
 * control fails every time, and until this sentence existed the failure was a surprise at the end
 * rather than a fact before the capture. The on-device geometry path (`lib/photoMeasure.ts`, surfaced
 * by `components/media/RecordPhotoMeasure.tsx`) is the one that needs nothing, and it is the default
 * on both record forms.
 */
export const GRID_SECTION_HINT =
  "Place the object on a 1-inch grid sheet. Length and breadth are read from a single top-down " +
  "photo; height needs its own side-on photo. Reading the photo needs a connection, and the " +
  "inches it returns are offered for you to check and accept — nothing is written into a " +
  "field until you press the button.";

/** Per-group hints. They say "offers" rather than "fills" for the reason above. */
export const GRID_GROUP_HINTS: Readonly<Record<GridGroup, string>> = {
  lengthBreadth: "Top-down photo of the object on the grid — offers both length and breadth.",
  height: "Side-on photo of the object against the grid — offers a height."
};

/** Per-group checkbox labels. Android's. */
export const GRID_GROUP_LABELS: Readonly<Record<GridGroup, string>> = {
  lengthBreadth: "Length & breadth (one photo)",
  height: "Height (one photo)"
};

/** How the whole offer is printed. */
export function gridProposalLabel(proposal: GridProposal): string {
  return proposal.readings.map((reading) => reading.label).join(" · ");
}

/** The status line while an offer stands. */
export function gridReadStatus(proposal: GridProposal): string {
  return `Read ${gridProposalLabel(proposal)} — check it against the object`;
}

/**
 * The button, and it NAMES THE FIGURE IT WILL WRITE.
 *
 * The accept is not a bare "Use this": a researcher who has stopped reading the status line still
 * cannot accept a number without seeing it. It is also why {@link formatGridInches} rounds before
 * this string is built rather than after — a button that said `3.50` while the box received `3.4967`
 * would be a lie in the one place this feature asks to be trusted.
 */
export function gridAcceptLabel(proposal: GridProposal): string {
  return `Use ${gridProposalLabel(proposal)}`;
}

/** The status line after acceptance. It says the value is still the researcher's to change. */
export function gridFilledStatus(write: GridWrite): string {
  return `Filled ${write.readings.map((reading) => reading.label).join(" · ")} — still editable`;
}

/** After a refused reading. The web's own — Android discards through the photograph's own cross. */
export const GRID_DISCARDED_STATUS = "Reading discarded — enter the value manually if you need it.";

/**
 * WHY A FAILED READ IS SEVEN SENTENCES AND NOT ONE — and why the deciding is not done here.
 *
 * The server distinguishes these deliberately and this client used to throw the distinction away in a
 * bare `catch`. An unconfigured provider answering `200` with `available: false` is something no
 * client can tell from "the grid was unreadable" — so a researcher re-photographs an object in better
 * light for ever while the real answer is that nobody has configured a vision provider.
 *
 * THE OBVIOUS IMPLEMENTATION IS WRONG IN A WAY THAT ONLY SHOWS ON A REAL DEPLOYMENT. Reading
 * `ApiError.message` is right whenever the server put words in the reply and wrong whenever it did
 * not: `apiFetch` builds that message as `describeApiDetail(detail, response.statusText || "The
 * server refused the request (HTTP ${status}).")`, and `statusText` is EMPTY over HTTP/2, which every
 * deployed request is. So a body-less 503 from a gateway in a deploy window would reach the screen as
 * the literal string "The server refused the request (HTTP 503)."
 *
 * SO THE CLASSIFICATION LIVES IN `lib/measurementFailure.ts` AND THIS IS A READING OF IT. That module
 * asks `serverSentence` — the response BODY — rather than `ApiError.message`, which is what makes
 * "quote the server" true rather than usually-true; and its `MeasurementRemedy` deliberately has no
 * "take another photograph" value, so none of the sentences it can produce can blame a picture.
 */
export function gridFailureStatus(error: unknown): string {
  return classifyMeasurementFailure(error).sentence;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading the wire
 * ──────────────────────────────────────────────────────────────────────────── */

/** How a dimension is printed. Length and breadth carry the inch mark; height says "in". Android's, both. */
const READING_LABEL: Readonly<Record<GridDimension, (value: string) => string>> = {
  length: (value) => `L ${value}"`,
  breadth: (value) => `B ${value}"`,
  height: (value) => `${value} in`
};

/**
 * THE NUMBER OF DECIMALS IS NOT COSMETIC HERE, AND IT IS THE BOX THAT DECIDES IT.
 *
 * `ProductForm` and `ToolForm` declare every dimension as
 * `<TextInput type="number" min={0} step="0.01" />`. A value with more than two decimals in a box
 * with `step="0.01"` is a `stepMismatch`: the form has no `noValidate`, so the browser's own
 * constraint validation refuses the submit and puts a bubble on that field. The old code wrote
 * `String(length)` — so a model answering `3.4967` filled the box with a value the form could not
 * then be saved with, and the researcher was left with a validation bubble on a field they had never
 * touched. Rounding here is what makes the two decimals the button prints and the two decimals the
 * box accepts the same two decimals.
 *
 * Nothing is lost by it: two decimals of an inch is a quarter of a millimetre, read by a vision model
 * off a sheet of 1-inch squares. `lib/photoMeasure.ts`'s `roundToUncertainty` is the honest treatment
 * where a real error bar exists — this estimate has no error bar, only a self-reported confidence,
 * which is exactly why the two must not be made to look alike.
 *
 * Returns null for anything that is not a positive number, INCLUDING a positive number that rounds to
 * zero: `0.004` is not a dimension of anything, and writing `0.00` into a documented measurement
 * would be a confident answer about nothing.
 */
export function formatGridInches(raw: unknown): string | null {
  if (raw === null || raw === undefined) return null;
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return null;
  const rounded = value.toFixed(2);
  if (Number(rounded) <= 0) return null;
  return rounded;
}

/** The keys the analysis block can carry, named off the wire type so a typo cannot read as "unreadable". */
type AnalysisKey = keyof NonNullable<MeasurementAnalysisResponse["analysis"]>;

/** Which dimensions each photograph can offer, and the response key each is read from. */
const GROUP_READINGS: Readonly<Record<GridGroup, ReadonlyArray<{ dimension: GridDimension; key: AnalysisKey }>>> = {
  lengthBreadth: [
    { dimension: "length", key: "lengthInches" },
    { dimension: "breadth", key: "breadthInches" }
  ],
  height: [{ dimension: "height", key: "valueInches" }]
};

/**
 * Turn one `POST /media/analyze-measurement` answer into an offer, or into a sentence saying why
 * there is none. **It cannot produce a write** — that is the whole point of the split — and it is the
 * only place the wire is read.
 */
export function readGridAnalysis(
  group: GridGroup,
  response: MeasurementAnalysisResponse
): { proposal: GridProposal | null; status: string } {
  /*
    A 200 IS NOT NECESSARILY AN ANSWER, AND THE TWO THAT ARE NOT ARE ASKED ABOUT FIRST.

    `available: false` is the route's older shape for "nobody configured a provider", kept because a
    web build outlives a backend deploy and the message it carries is the one naming what is missing.

    `status: "FAILED"` is the server catching an exception FROM THE PROVIDER (a rate limit, a 500
    upstream, a DNS failure on the server's side) and returning `available: true`, `analysis: null`
    and a sentence naming the fault. It is a 200 on purpose, because the server is working exactly as
    designed.

    UNTIL THIS CALL EXISTED THE SECOND ONE WAS SHOWN AS AN UNREADABLE GRID. `analysis` is null on that
    path, so the loop below finds no readings and falls to `GRID_UNREADABLE_STATUS` — "Couldn't read a
    value — enter it manually" — while the server is holding a sentence saying the provider had
    rate-limited. That is the same defect as "unconfigured wearing unreadable's clothes", reached
    through the other door, and it is why the question is asked BEFORE the numbers rather than after.

    `lib/measurementFailure.ts` owns both sentences and prefers the server's own words wherever the
    reply carried any. There is deliberately no `|| GRID_UNREADABLE_STATUS` fallback behind it: an old
    server that sent no message would otherwise say "couldn't read a value" about a provider nobody
    configured.
  */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The state machine the component renders
 * ──────────────────────────────────────────────────────────────────────────── */

export type GridState = {
  /** The offers currently on screen. A group is absent when it has nothing on offer. */
  proposals: Partial<Record<GridGroup, GridProposal>>;
  /** The line under each group's capture control. */
  status: Partial<Record<GridGroup, string>>;
};

export const EMPTY_GRID_STATE: GridState = { proposals: {}, status: {} };

export type GridEvent =
  /** A photograph was chosen for this group and is on its way to the model. */
  | { type: "CAPTURE"; group: GridGroup }
  /** The model answered. */
  | { type: "ANALYSIS"; group: GridGroup; response: MeasurementAnalysisResponse }
  /** The call threw. */
  | { type: "FAILURE"; group: GridGroup; error: unknown }
  /** The researcher pressed the button that names the figure. */
  | { type: "ACCEPT"; group: GridGroup }
  /** The researcher refused the reading, or unchecked the dimension, or replaced the photograph. */
  | { type: "DISCARD"; group: GridGroup; clearStatus?: boolean };

/**
 * The component's whole decision, as a function of state and one event.
 *
 * IT RETURNS THE WRITE RATHER THAN PERFORMING ONE, which is what makes the rule testable without a
 * browser: a caller can drive a whole capture → analysis → accept sequence and assert that `write` is
 * null on every step but the last. `GridMeasurement.tsx` is a `useState` over this and calls
 * `onLengthBreadth` / `onHeight` only when `write` comes back non-null — there is no other path from
 * this module into form state, and a second one would be the defect returning.
 */
export function gridReduce(state: GridState, event: GridEvent): { state: GridState; write: GridWrite | null } {
  switch (event.type) {
    case "CAPTURE":
      // A RE-CAPTURE RETRACTS THE PREVIOUS OFFER BEFORE IT ASKS FOR A NEW ONE. Leaving the old
      // reading on screen while the new photograph is analysed would put an accept button under a
      // figure that belongs to a photograph the researcher has just replaced.
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
      // Nothing on offer, nothing written. An accept with no proposal behind it is a double press or
      // a stale render, and it must not resurrect a figure from anywhere.
      if (!proposal) return { state, write: null };
      const write: GridWrite = { group: proposal.group, readings: proposal.readings, marker: proposal.marker };
      return {
        state: {
          // THE OFFER IS SPENT. It leaves the proposal list the instant it is taken, so the button
          // cannot be pressed twice and the card cannot go on standing over a field that now holds
          // the value — at which point the field, not the card, is the record of it.
          proposals: withoutGroup(state.proposals, event.group),
          status: { ...state.status, [event.group]: gridFilledStatus(write) }
        },
        write
      };
    }
    case "DISCARD":
      // THE OFFER DIES WITH THE PHOTOGRAPH IT WAS READ OFF. An accept button surviving the discard
      // would write a figure whose evidence the researcher has just deleted, and nothing on the
      // record would afterwards say where the number came from.
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

/** Drop one group's entry without mutating the object the previous render is still holding. */
function withoutGroup<T>(map: Partial<Record<GridGroup, T>>, group: GridGroup): Partial<Record<GridGroup, T>> {
  if (!(group in map)) return map;
  const next = { ...map };
  delete next[group];
  return next;
}
