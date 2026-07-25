"use client";

/**
 * @deprecated The "Record time" card was removed from every record form (timestamps are captured
 * automatically server-side; the interpretation timezone falls back to Asia/Kolkata in
 * `recordedTimezoneFromForm`). This stub renders nothing and only exists so pages that still import
 * it this cycle keep compiling — delete it once no imports remain.
 */
export function RecordedAtField(_props: { value?: string | Date | null; timezone?: string | null }) {
  return null;
}
