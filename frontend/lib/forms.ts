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
    placeName: textValue(form, "placeName") || undefined
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
