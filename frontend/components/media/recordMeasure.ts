/**
 * The reference→value arithmetic behind measuring a product's or a tool's dimension off its own
 * photograph, and the four refusals that arithmetic can produce.
 *
 * ── WHY THIS IS A MODULE AND NOT TWENTY LINES INSIDE THE PANEL ────────────────────────────────
 * There is no React renderer in devDependencies, so a judgement written inside JSX is only ever
 * exercised by somebody looking at a screen — the same split, and the same reason, as
 * `components/forms/measurementMethods.ts` and `components/media/gridProposal.ts`. Everything here is
 * pure and is asserted in `e2e/record-photo-measure-unit.spec.ts` on a laptop with no browser.
 *
 * ── THE ARITHMETIC IS ONE LINE AND THE ROUNDING IS THE HARD PART ──────────────────────────────
 * "The researcher marked across N squares of a 1-inch sheet" is a reference of N inches, and
 * `lib/photoMeasure.ts` does the rest. What this module owns is the far less obvious half: getting
 * the answer into a `Decimal(10, 2)` column, through a `<input type="number" step="0.01">`, without
 * either lying about the precision or being silently refused by the browser.
 *
 * ── THE DEFECT THIS EXISTS TO PREVENT, WHICH IS A BROWSER REFUSAL AND NOT A WRONG NUMBER ──────
 * `roundToUncertainty` quotes a value to the decimal place of its own error bar, which for a
 * carefully zoomed mark on a close-up photograph is routinely THREE or four decimals — "4.213 in".
 * Every dimension box on both record forms is `type="number" step="0.01"`, and neither form carries
 * `noValidate`, so the browser's own constraint validation refuses a value off the step ladder:
 * pressing Save on an accepted 4.213 raises "the two nearest valid values are 4.21 and 4.22" on a box
 * the researcher never typed in, over a number they were told was measured. Underneath it the column
 * is `Decimal(10, 2)` (`ProductDocumentation.lengthInches` and its siblings on `ToolDocumentation`),
 * so even a client that got past the box would have the third decimal dropped by Postgres with nobody
 * told. {@link COLUMN_DECIMALS} is that limit written down once, and {@link proposalFor} rounds to
 * it — DOWNWARD in precision, which is always the safe direction, and says so on screen when it had
 * to. Quoting fewer digits than the measurement earned costs a little accuracy; quoting more claims
 * a certainty nothing supports, and it is the second one this whole feature exists to refuse.
 */

import { convertLength, roundToUncertainty, type LengthUnit } from "@/lib/photoMeasure";

/**
 * How many decimal places a dimension column on a product or tool record can actually hold.
 *
 * READ OFF TWO PLACES THAT MUST AGREE, and neither of them is here: `Decimal(10, 2)` on
 * `ProductDocumentation.lengthInches` / `breadthInches` / `heightInches` and on
 * `ToolDocumentation.height` / `width` / `lengthInches` / `breadthInches` / `heightInches` /
 * `thickness` / `weight` / `radius`, and `step="0.01"` on every one of those boxes in `ProductForm.tsx`
 * / `ToolForm.tsx`. If the column ever widens, this constant and the `step` move together or the box
 * starts refusing values the column would take.
 *
 * Re-check: `grep -n 'Decimal(10, 2)' backend/prisma/schema.prisma`.
 */
export const COLUMN_DECIMALS = 2;

export type GridPitchId = "IN_1" | "CM_1" | "MM_5";

export type GridPitch = { id: GridPitchId; label: string; length: number; unit: LengthUnit };

/**
 * The ruled sheets a researcher in this programme photographs an object on.
 *
 * THE 1-INCH SHEET IS THE PROGRAMME'S OWN and is why this control leads with the grid at all: the
 * measurement sheet handed out with the kit is ruled at one inch, "Document using grid" names it in
 * those words on both clients, and the record columns beside this panel are already spelled
 * `lengthInches`. Marking across five of its squares is a five-inch reference that needs no ruler
 * in the frame, no typing, and no model.
 *
 * THE OTHER TWO ARE HERE BECAUSE A DRAWER HAS MORE THAN ONE SHEET IN IT. Ordinary graph paper is
 * metric, and a centimetre square read as an inch is a record 2.54 times too big — plausible,
 * silent, and printed in a cost sheet. So the pitch is a stated choice rather than an assumption,
 * and the panel echoes the arithmetic ("6 squares × 1 in = 6 in") beside it so a researcher who
 * picked the wrong sheet sees a reference that does not match the paper in front of them.
 */
export const GRID_PITCHES: readonly GridPitch[] = [
  { id: "IN_1", label: "1-inch squares", length: 1, unit: "in" },
  { id: "CM_1", label: "1 cm squares", length: 1, unit: "cm" },
  { id: "MM_5", label: "5 mm squares", length: 5, unit: "mm" }
];

/** The kit's own sheet. Preselected because it is the sheet this programme ships, not a guess. */
export const DEFAULT_GRID_PITCH_ID: GridPitchId = "IN_1";

export function gridPitchById(id: string): GridPitch {
  return GRID_PITCHES.find((pitch) => pitch.id === id) ?? GRID_PITCHES[0];
}

/**
 * A resolved reference: a real length in a real unit, or a sentence saying exactly what is missing.
 *
 * `sentence` is the arithmetic read back — "6 squares × 1 in = 6 in" — and it is not decoration. The
 * one mistake this path can make that nothing downstream could ever catch is counting the squares
 * wrong or picking the wrong sheet, and both of those are visible the moment the multiplication is
 * printed next to the paper it describes.
 */
export type ResolvedReference =
  | { ok: true; length: number; unit: LengthUnit; sentence: string }
  | { ok: false; reason: string };

/** Trim a length to a readable number of digits without inventing precision it does not have. */
function tidy(value: number): string {
  return String(Math.round(value * 1e6) / 1e6);
}

/**
 * "I marked across N squares" → the reference length that is.
 *
 * REFUSALS ARE SENTENCES, NEVER A SILENT NULL, because this is the one input on the panel that a
 * researcher can leave blank without noticing: the marks are on the photograph and look finished, and
 * an empty squares box just means no answer appears. Saying which of the four things is wrong is
 * the difference between a control that is waiting and one that looks broken.
 */
export function gridSpan(squaresText: string, pitch: GridPitch): ResolvedReference {
  const text = squaresText.trim();
  if (!text) {
    return { ok: false, reason: "Say how many grid squares the reference mark spans, and the measurement appears here." };
  }
  const squares = Number(text);
  if (!Number.isFinite(squares)) {
    return { ok: false, reason: `“${text}” is not a number of squares.` };
  }
  if (squares <= 0) {
    return { ok: false, reason: "A reference has to span more than zero squares." };
  }
  const length = squares * pitch.length;
  return {
    ok: true,
    length,
    unit: pitch.unit,
    sentence: `${tidy(squares)} ${squares === 1 ? "square" : "squares"} × ${tidy(pitch.length)} ${pitch.unit} = ${tidy(length)} ${pitch.unit}`
  };
}

/** "That steel rule is 300 mm" → the same shape of answer, for a photograph with no grid in it. */
export function statedLength(lengthText: string, unit: LengthUnit): ResolvedReference {
  const text = lengthText.trim();
  if (!text) {
    return { ok: false, reason: "Type how long the reference really is, and the measurement appears here." };
  }
  const length = Number(text);
  if (!Number.isFinite(length)) {
    return { ok: false, reason: `“${text}” is not a length this can measure against.` };
  }
  if (length <= 0) {
    return { ok: false, reason: "The reference must have a real length greater than zero." };
  }
  return { ok: true, length, unit, sentence: `${tidy(length)} ${unit}` };
}

/**
 * The two edges of a block of grid squares, for the four-corner method.
 *
 * The rectangle's `width` edge is corner 1 → corner 2 and its `height` edge is corner 2 → corner 3;
 * `measureByRectification` says so and getting the pair the wrong way round rectifies a real
 * rectangle that is not the one in the photograph. The panel prints which edge is which under the
 * two boxes for that reason.
 */
export function gridRectangle(
  widthSquaresText: string,
  heightSquaresText: string,
  pitch: GridPitch
): { ok: true; width: number; height: number; unit: LengthUnit; sentence: string } | { ok: false; reason: string } {
  const width = gridSpan(widthSquaresText, pitch);
  const height = gridSpan(heightSquaresText, pitch);
  if (!width.ok) return { ok: false, reason: `Across (corner 1 → 2): ${width.reason}` };
  if (!height.ok) return { ok: false, reason: `Down (corner 2 → 3): ${height.reason}` };
  return {
    ok: true,
    width: width.length,
    height: height.length,
    unit: pitch.unit,
    sentence: `${width.sentence} across × ${height.sentence} down`
  };
}

/**
 * What a measured value becomes when it is offered into a record column.
 *
 * `clamped` is true when the measurement was finer than {@link COLUMN_DECIMALS} and the proposal had
 * to give digits back. The panel prints one sentence when it is, and nothing at all when it is not —
 * a note under every proposal would be noise that trains a reader past the row where it matters.
 */
export type Proposal =
  | { ok: true; text: string; doubt: string; unit: LengthUnit; decimals: number; clamped: boolean }
  | { ok: false; reason: string };

/**
 * Convert a measurement into a target column's unit and round it to a number that column can hold.
 *
 * THREE THINGS HAPPEN HERE AND THE ORDER MATTERS.
 *
 * 1. CONVERT FIRST, ROUND SECOND. `photoMeasure` answers in the reference's own unit — millimetres
 *    for a steel rule, inches for the kit's sheet — and every dimension column on both records is
 *    inches. Rounding before converting would round in the wrong unit and then convert the rounding
 *    error up with everything else.
 *
 * 2. ROUND ONCE, FROM THE ORIGINAL. `roundToUncertainty` is asked for the decimal place the error
 *    bar reaches, and the answer is then clamped to what the column holds — but the VALUE is
 *    re-rounded from the converted number rather than from `roundToUncertainty`'s output. Rounding
 *    4.2149 to three places and then to two gives 4.22; rounding it once gives 4.21. Double
 *    rounding is a defect that only ever shows on the values sitting exactly on a boundary, which
 *    is precisely where nobody looks.
 *
 * 3. A VALUE THAT ROUNDS TO ZERO IS REFUSED, not stored. Zero in a dimension column does not read as
 *    "under five thousandths of an inch"; it reads as a measurement of nothing, and it would be
 *    printed as `0.00` in the report's dimensions cell. A tool's needle thickness is the real case.
 */
export function proposalFor(value: number, uncertainty: number, from: LengthUnit, to: LengthUnit): Proposal {
  const converted = convertLength(value, from, to);
  const convertedDoubt = convertLength(uncertainty, from, to);
  if (converted === null || convertedDoubt === null) {
    return { ok: false, reason: `This cannot convert ${from} to ${to}, so nothing is proposed into that box.` };
  }
  if (!Number.isFinite(converted) || !Number.isFinite(convertedDoubt) || converted <= 0) {
    return { ok: false, reason: "That is not a length this can propose." };
  }

  const natural = roundToUncertainty(converted, convertedDoubt);
  const decimals = Math.min(COLUMN_DECIMALS, natural.decimals);
  const factor = 10 ** decimals;
  const rounded = Math.round(converted * factor) / factor;
  if (rounded <= 0) {
    return {
      ok: false,
      reason: `That measures about ${converted.toPrecision(2)} ${to}, which rounds to zero in a box that stores ${COLUMN_DECIMALS} decimal places. A stored 0 would read as “measured, and it is nothing”, so nothing is proposed.`
    };
  }

  // The doubt is printed at the value's own precision so the two cannot disagree about how many
  // digits are being claimed. Rounding it UP to that place — never down — keeps an error bar that
  // is a little too generous rather than one that is quietly too flattering.
  const doubt = Math.max(convertedDoubt, 0);
  const doubtRounded = Math.ceil(doubt * factor) / factor;

  return {
    ok: true,
    text: rounded.toFixed(decimals),
    doubt: doubtRounded.toFixed(decimals),
    unit: to,
    decimals,
    clamped: natural.decimals > COLUMN_DECIMALS
  };
}
