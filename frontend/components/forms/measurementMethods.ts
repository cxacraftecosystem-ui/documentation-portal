import type { MeasurementMethodMarker } from "@/lib/media";

export const DIMENSION_FIELDS = ["lengthInches", "breadthInches", "heightInches"] as const;

export type DimensionField = (typeof DIMENSION_FIELDS)[number];

export function isDimensionField(key: string): key is DimensionField {
  return (DIMENSION_FIELDS as readonly string[]).includes(key);
}

export type Acceptance = { value: string; marker: MeasurementMethodMarker };

export type AcceptedMeasurements = Partial<Record<DimensionField, Acceptance>>;

export const NO_ACCEPTED_MEASUREMENTS: AcceptedMeasurements = Object.freeze({});

function isStorableNumber(text: string): boolean {
  return text.trim() !== "" && Number.isFinite(Number(text));
}

export function rememberAcceptance(
  accepted: AcceptedMeasurements,
  key: string,
  value: string,
  marker: MeasurementMethodMarker | null | undefined
): AcceptedMeasurements {
  if (!isDimensionField(key)) return accepted;
  if (!marker || !isStorableNumber(value)) return forgetAcceptance(accepted, key);
  return { ...accepted, [key]: { value, marker } };
}

export function forgetAcceptance(accepted: AcceptedMeasurements, key: string): AcceptedMeasurements {
  if (!isDimensionField(key) || accepted[key] === undefined) return accepted;
  const next = { ...accepted };
  delete next[key];
  return next;
}

export function measurementMethodsFor(
  accepted: AcceptedMeasurements,
  values: Record<string, string>
): Record<string, MeasurementMethodMarker> | undefined {
  const out: Record<string, MeasurementMethodMarker> = {};
  for (const key of DIMENSION_FIELDS) {
    const acceptance = accepted[key];
    if (!acceptance) continue;
    const current = values[key];
    if (typeof current !== "string" || !isStorableNumber(current)) continue;
    if (current !== acceptance.value) continue;
    out[key] = acceptance.marker;
  }
  return Object.keys(out).length ? out : undefined;
}
