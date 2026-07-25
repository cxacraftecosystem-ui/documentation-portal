"use client";

import { useEffect, useState } from "react";

import { apiFetch } from "@/lib/api";
import {
  reviewEditableFields,
  reviewRecordEndpoint,
  reviewValuesFrom,
  type ReviewField
} from "@/components/review/reviewEditFields";
import { readableError } from "@/components/review/reviewErrors";

/**
 * Fix a pending record's field values from inside the review queue, instead of bouncing it back to
 * its creator for what is often a misspelt village.
 *
 * The Android app has had this since the review card shipped (MainActivity `PendingReviewRow`); this
 * is the same editor for the web, against the same endpoint and with the same field list. Two rules
 * it must keep:
 *
 * 1. An edit is NOT an approval. `POST /review/{type}/{id}/edit` leaves the status alone unless
 *    `approve` is set, so "Save" keeps the record pending and "Save and approve" is the explicit,
 *    separately logged second action for the fix-the-typo-and-sign-it-off case.
 * 2. Only CHANGED keys travel. The server writes a RecordRevision from the payload, so posting an
 *    untouched value would put a no-op line in the record's edit history forever.
 */
export function ReviewEditPanel({
  recordType,
  recordId,
  label,
  onSaved,
  onApproved,
  onClose
}: {
  recordType: string;
  recordId: string;
  label: string;
  /** Saved with the status untouched — the row stays in the queue; the message is the receipt. */
  onSaved: (message: string) => void;
  /** Saved AND approved — the row leaves the queue, so the parent reloads and closes the panel. */
  onApproved: (message: string) => void;
  onClose: () => void;
}) {
  const fields = reviewEditableFields(recordType);
  /** The stored values the diff is taken against; null until the record has loaded. */
  const [baseline, setBaseline] = useState<Record<string, string> | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  // The queue can be long; the record is fetched when the reviewer actually opens the editor rather
  // than a request per row for panels nobody opens.
  useEffect(() => {
    let cancelled = false;
    const endpoint = reviewRecordEndpoint(recordType, recordId);
    if (!endpoint || fields.length === 0) {
      setError(`This build has no review editor for a ${recordType} record.`);
      return;
    }
    (async () => {
      try {
        const record = await apiFetch<unknown>(endpoint);
        if (cancelled) return;
        const loaded = reviewValuesFrom(record, fields);
        setBaseline(loaded);
        setValues(loaded);
      } catch (err) {
        if (!cancelled) setError(readableError(err, `Unable to open ${label} for editing`));
      }
    })();
    return () => {
      cancelled = true;
    };
    // `fields` is derived from recordType and is stable for a given row.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordType, recordId]);

  const changedKeys = baseline
    ? fields.map((field) => field.key).filter((key) => (values[key] ?? "") !== (baseline[key] ?? ""))
    : [];
  /** A column the schema declares `min_length=1` cannot be blanked — catch it before the 422. */
  const blanked = fields.filter((field) => field.required && changedKeys.includes(field.key) && !values[field.key]?.trim());

  async function submit(approve: boolean) {
    if (!baseline || changedKeys.length === 0 || blanked.length > 0) return;
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const changed = Object.fromEntries(changedKeys.map((key) => [key, values[key] ?? ""]));
      await apiFetch(`/review/${recordType}/${recordId}/edit`, {
        method: "POST",
        body: JSON.stringify({ fields: changed, note: note.trim() || null, approve })
      });
      const count = `${changedKeys.length} field${changedKeys.length === 1 ? "" : "s"}`;
      if (approve) {
        onApproved(`Edited ${count} and approved "${label}".`);
        return;
      }
      // Re-read so the baseline matches what the server actually stored — it title-cases the
      // name-like columns, so the box must show the normalised value or the next diff is wrong.
      const endpoint = reviewRecordEndpoint(recordType, recordId);
      if (endpoint) {
        const fresh = reviewValuesFrom(await apiFetch<unknown>(endpoint), fields);
        setBaseline(fresh);
        setValues(fresh);
      }
      setNote("");
      setSaved(`Saved ${count}. This record is still pending — approve it when you are ready.`);
      onSaved(`Edited ${count} on "${label}".`);
    } catch (err) {
      setError(readableError(err, "Unable to save this edit"));
    } finally {
      setBusy(false);
    }
  }

  function renderField(field: ReviewField) {
    const value = values[field.key] ?? "";
    const dirty = baseline ? value !== (baseline[field.key] ?? "") : false;
    const empty = field.required && dirty && !value.trim();
    const inputClass = `field-input ${dirty ? "border-purple-300" : ""}`;
    return (
      <label key={field.key} className="grid gap-1">
        <span className="field-label">
          {field.label}
          {field.required ? " *" : ""}
          {dirty ? <span className="ml-1.5 font-semibold normal-case text-purple-700">changed</span> : null}
        </span>
        {field.multiline ? (
          <textarea
            className={`${inputClass} min-h-20`}
            rows={3}
            value={value}
            disabled={busy}
            onChange={(event) => setValues((prev) => ({ ...prev, [field.key]: event.target.value }))}
          />
        ) : (
          <input
            className={inputClass}
            value={value}
            disabled={busy}
            onChange={(event) => setValues((prev) => ({ ...prev, [field.key]: event.target.value }))}
          />
        )}
        {empty ? <span className="text-xs text-error-600">{field.label} cannot be left empty.</span> : null}
      </label>
    );
  }

  /**
   * Rendered immediately ABOVE the buttons, not at the top of the panel: the editor is a dozen boxes
   * tall, so a reviewer who clicks Save at the bottom of a long record would never see a banner
   * pinned to the top — a rejected 422 would look like a button that simply did nothing.
   */
  const feedback = (
    <>
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {saved ? (
        <div className="rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">{saved}</div>
      ) : null}
    </>
  );

  return (
    <div className="grid gap-3">
      <p className="text-xs text-ink-500">
        {/* The explicit {" "} is load-bearing: the JSX transform drops the leading space of the text
            node that follows an inline element, so without it this reads "does notapprove it". */}
        Fix the values here instead of bouncing the record back. Saving does <strong>not</strong>{" "}
        approve it — the status stays pending and the change is recorded against your name in the edit history. Status,
        workshop, location and linked records are changed from the record&apos;s own edit screen.
      </p>
      {!baseline ? (
        <>
          {feedback}
          {error ? null : <p className="text-sm text-ink-700">Loading {recordType}...</p>}
        </>
      ) : (
        <>
          <div className="grid gap-3 md:grid-cols-2">{fields.map(renderField)}</div>
          <label className="grid gap-1">
            <span className="field-label">Why? (optional, logged with the edit)</span>
            <input
              className="field-input"
              value={note}
              disabled={busy}
              onChange={(event) => setNote(event.target.value)}
            />
          </label>
          {feedback}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="field-button h-9 min-h-0 px-3 text-xs"
              disabled={busy || changedKeys.length === 0 || blanked.length > 0}
              onClick={() => submit(false)}
            >
              {busy
                ? "Saving..."
                : changedKeys.length === 0
                  ? "No changes yet"
                  : `Save ${changedKeys.length} change${changedKeys.length === 1 ? "" : "s"}`}
            </button>
            <button
              type="button"
              className="field-button-secondary h-9 min-h-0 px-3 text-xs"
              disabled={busy || changedKeys.length === 0 || blanked.length > 0}
              onClick={() => submit(true)}
            >
              Save and approve
            </button>
            <button type="button" className="field-button-secondary h-9 min-h-0 px-3 text-xs" disabled={busy} onClick={onClose}>
              Cancel
            </button>
          </div>
        </>
      )}
    </div>
  );
}
