import { convertLength, roundToUncertainty, type LengthUnit } from "@/lib/photoMeasure";

export const COLUMN_DECIMALS = 2;

export type GridPitchId = "IN_1" | "CM_1" | "MM_5";

export type GridPitch = { id: GridPitchId; label: string; length: number; unit: LengthUnit };

export const GRID_PITCHES: readonly GridPitch[] = [
  { id: "IN_1", label: "1-inch squares", length: 1, unit: "in" },
  { id: "CM_1", label: "1 cm squares", length: 1, unit: "cm" },
  { id: "MM_5", label: "5 mm squares", length: 5, unit: "mm" }
];

export const DEFAULT_GRID_PITCH_ID: GridPitchId = "IN_1";

export function gridPitchById(id: string): GridPitch {
  return GRID_PITCHES.find((pitch) => pitch.id === id) ?? GRID_PITCHES[0];
}

export type ResolvedReference =
  | { ok: true; length: number; unit: LengthUnit; sentence: string }
  | { ok: false; reason: string };

function tidy(value: number): string {
  return String(Math.round(value * 1e6) / 1e6);
}

export function gridSpan(squaresText: string, pitch: GridPitch): ResolvedReference {
  const text = squaresText.trim();
  if (!text) {
    return { ok: false, reason: "Enter how many squares the reference spans." };
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

export function statedLength(lengthText: string, unit: LengthUnit): ResolvedReference {
  const text = lengthText.trim();
  if (!text) {
    return { ok: false, reason: "Enter the reference length." };
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

export type Proposal =
  | { ok: true; text: string; doubt: string; unit: LengthUnit; decimals: number; clamped: boolean }
  | { ok: false; reason: string };

export function proposalFor(value: number, uncertainty: number, from: LengthUnit, to: LengthUnit): Proposal {
  const converted = convertLength(value, from, to);
  const convertedDoubt = convertLength(uncertainty, from, to);
  if (converted === null || convertedDoubt === null) {
    return { ok: false, reason: `Can’t convert ${from} to ${to}.` };
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
      reason: `Too small for this field (about ${converted.toPrecision(2)} ${to}).`
    };
  }

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
