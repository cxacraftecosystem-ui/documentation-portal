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

/**
 * DO THESE TWO ID LISTS NAME THE SAME SET? The question a form asks before it sends a relation list.
 *
 * ORDER-INSENSITIVE ON PURPOSE: a multi-select hands back the order the researcher ticked boxes in,
 * while the stored list comes back in the join table's own order, so comparing SEQUENCES would
 * report "changed" for a roster nobody touched — which is the exact refusal the diff exists to
 * avoid. Both inputs are duplicate-free where this is used (every join table here is unique on the
 * pair and no picker can tick a row twice), so a length check plus membership is a true set
 * comparison.
 *
 * ── WHY A RELATION LIST IS SENT ONLY WHEN IT CHANGED ──────────────────────────────────────────
 * Two routes re-check every roster they are SENT. `assert_can_contribute_relation(..., populated =
 * link_count > 0, ...)` refuses the send outright — 403, naming the field — when the caller is
 * neither an admin nor the record's creator and holds no EDIT grant, EVEN IF the list they sent is
 * identical to the one already stored. So a form that sent its pickers unconditionally answered a
 * contributor who had corrected a typo in some unrelated box with *"Only the original contributor or
 * an admin can change populated relation: craftIds"*, about a picker they never opened. An omitted
 * key is the schema's own "leave this relation alone" (`list[str] | None = None`), and `[]` still
 * means "no links" — an emptied picker still DIFFERS from the stored list, so the clearing send
 * survives this diff untouched.
 *
 * It also closes a race the unconditional send could not: between mount and Save somebody else may
 * have added a link, and re-sending the list this form seeded at mount would delete their row under
 * a 200. Nothing is sent, so nothing of theirs is replaced.
 *
 * Callers: `app/(protected)/workshops/page.tsx` (artisans + crafts) and `forms/ToolForm.tsx`
 * (crafts + artisans). Android's `WorkshopForm` diffs the same two rosters the same way.
 */
export function sameIdSet(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const seen = new Set(a);
  return b.every((id) => seen.has(id));
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

// ── Jumping to a field (record completeness, 2026-10-18) ─────────────────────────────────────────

/** The `focus=` key in this page's URL, or null. */
export function focusKeyFromSearch(search: string): string | null {
  const value = new URLSearchParams(search).get("focus");
  return value && /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(value) ? value : null;
}

/**
 * Bring the field a completeness rule names into view, put the caret in it, and flash it once — the
 * `.fr-flash-row` outline (§10.2), whose static half survives reduced motion. Answers whether an
 * anchor was found.
 *
 * SELF-CONTAINED ON PURPOSE: nothing outside this function body is referenced, so
 * `e2e/record-completeness-unit.spec.ts` can run its source in a real page.
 */
export function focusField(key: string, root: ParentNode = document): boolean {
  // The first real control inside the anchor — never a mirror twin (`tabindex="-1"`, §12.2).
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

/**
 * Honour `?focus=<key>` once the form it names is on screen. The key is read on arrival (an edit
 * deep link strips its own `?edit=` from the URL, and the focus key with it), and applied after the
 * first paint that has `ready` true — the inline forms are seeded asynchronously.
 */
export function useFocusField(ready = true) {
  const pending = useRef<string | null | undefined>(undefined);
  useEffect(() => {
    if (pending.current === undefined) pending.current = focusKeyFromSearch(window.location.search);
    if (!ready || !pending.current) return;
    const key = pending.current;
    let frame = window.requestAnimationFrame(() => {
      // Two frames: a remounted form lays out, then the edit-only blocks appear.
      frame = window.requestAnimationFrame(() => {
        if (focusField(key)) pending.current = null;
      });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [ready]);
}
