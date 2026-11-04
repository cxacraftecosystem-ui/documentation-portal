import { useCallback, useEffect, useRef, useState } from "react";

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
    capturedAt: textValue(form, "capturedAt") || undefined,
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

export function sameIdSet(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const seen = new Set(a);
  return b.every((id) => seen.has(id));
}

export function parseJsonMetadata(raw: FormDataEntryValue | null) {
  if (typeof raw !== "string" || !raw.trim()) return undefined;
  return JSON.parse(raw);
}

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

export function focusKeyFromSearch(search: string): string | null {
  const value = new URLSearchParams(search).get("focus");
  return value && /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(value) ? value : null;
}

export function focusField(key: string, root: ParentNode = document): boolean {
  const target =
    'input:not([type="hidden"]):not([tabindex="-1"]):not([disabled]), textarea:not([tabindex="-1"]):not([disabled]), button:not([disabled]):not([tabindex="-1"]), [contenteditable="true"], [tabindex]:not([tabindex="-1"])';
  const anchor = root.querySelector<HTMLElement>(`[data-field="${key.replace(/["\\]/g, "")}"]`);
  if (!anchor) return false;
  const reduce =
    document.documentElement.dataset.reducedMotion === "true" ||
    (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false);
  anchor.scrollIntoView({ block: "center", behavior: reduce ? "auto" : "smooth" });
  const control = anchor.matches(target) ? anchor : anchor.querySelector<HTMLElement>(target);
  control?.focus({ preventScroll: true });
  anchor.classList.add("fr-flash-row");
  anchor.dataset.flash = "true";
  window.setTimeout(() => {
    delete anchor.dataset.flash;
    anchor.classList.remove("fr-flash-row");
  }, 2000);
  return true;
}

export function useFocusField(ready = true) {
  const pending = useRef<string | null | undefined>(undefined);
  useEffect(() => {
    if (pending.current === undefined) pending.current = focusKeyFromSearch(window.location.search);
    if (!ready || !pending.current) return;
    const key = pending.current;
    let frame = window.requestAnimationFrame(() => {
      frame = window.requestAnimationFrame(() => {
        if (focusField(key)) pending.current = null;
      });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [ready]);
}
