import { blankToNull, numberOrNull } from "@/lib/format";

export function textValue(form: FormData, key: string) {
  return blankToNull(form.get(key));
}

export function requiredText(form: FormData, key: string) {
  const value = textValue(form, key);
  return typeof value === "string" ? value : "";
}

export function numericValue(form: FormData, key: string) {
  return numberOrNull(form.get(key));
}

export function optionalNumberPayload(form: FormData, key: string) {
  const value = numericValue(form, key);
  return value === null ? undefined : value;
}

export function locationFromForm(form: FormData) {
  const latitude = numericValue(form, "latitude");
  const longitude = numericValue(form, "longitude");
  if (latitude === null || longitude === null) return undefined;
  return {
    latitude,
    longitude,
    altitude: optionalNumberPayload(form, "altitude"),
    accuracy: optionalNumberPayload(form, "accuracy"),
    address: textValue(form, "locationAddress") || undefined,
    placeName: textValue(form, "placeName") || undefined
  };
}

export function parseJsonMetadata(raw: FormDataEntryValue | null) {
  if (typeof raw !== "string" || !raw.trim()) return undefined;
  return JSON.parse(raw);
}
