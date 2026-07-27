import { useCallback, useEffect, useState } from "react";

import { numberOrNull } from "@/lib/format";

export function textValue(form: FormData, key: string) {
  const value = form.get(key);
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : null;
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
    placeName: textValue(form, "placeName") || undefined,
    // When the device produced the fix. Provenance that cannot say WHEN is half a record: the whole
    // point of separating it from the stated address is that a reader can judge it, and a
    // coordinate with no timestamp cannot be judged at all.
    capturedAt: textValue(form, "capturedAt") || undefined,
    // The STATED address — see LocationPayload for why these are not the same kind of thing as the
    // coordinates above. They are columns on Location like every field here, and LocationFields
    // renders them on all six forms. They were read only by the artisan form, which merged them in
    // by hand: the other five suggested them from the map, validated them, and then dropped them
    // here at save. Worse on an edit — a save writes a BRAND NEW Location row (attach_location in
    // backend/app/services/records.py), so a stored state/pincode was replaced by nothing. Read them
    // in the one place every form already goes through, rather than in five more call sites that
    // would each have to remember.
    state: textValue(form, "state") || undefined,
    district: textValue(form, "district") || undefined,
    village: textValue(form, "village") || undefined,
    pincode: textValue(form, "pincode") || undefined,
    subjectLatitude: optionalNumberPayload(form, "subjectLatitude"),
    subjectLongitude: optionalNumberPayload(form, "subjectLongitude")
  };
}

export function recordedAtFromForm(form: FormData) {
  const raw = textValue(form, "recordedAt");
  if (!raw || typeof raw !== "string") return undefined;
  const parsed = new Date(raw);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

export function recordedTimezoneFromForm(form: FormData) {
  return textValue(form, "recordedTimezone") || "Asia/Kolkata";
}

export function parseJsonMetadata(raw: FormDataEntryValue | null) {
  if (typeof raw !== "string" || !raw.trim()) return undefined;
  return JSON.parse(raw);
}

/**
 * Dirty-state tracker for record forms. Call `markDirty` on any user change (the forms wire it to
 * the form's onInput plus every themed-dropdown onChange and media picker); while dirty the browser
 * warns before a full page unload. In-app navigation is guarded separately by each form's Back
 * button + UnsavedChangesDialog. `resetDirty` after a successful save.
 */
export function useUnsavedChanges() {
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const markDirty = useCallback(() => setDirty(true), []);
  const resetDirty = useCallback(() => setDirty(false), []);
  return { dirty, markDirty, resetDirty };
}
