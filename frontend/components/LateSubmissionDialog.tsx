"use client";

import { useEffect, useId } from "react";

import { formatDate } from "@/lib/format";

/**
 * Confirmation shown when a record is about to be saved into a workshop that has already ended.
 *
 * The backend accepts a late submission but pins it to PENDING and stamps it
 * `extraMetadata.workshopSubmission.needsAdminApproval`, after which ONLY an admin or master admin
 * may approve it (see `services/workshop_access` and the review routes). That is a real consequence
 * for the researcher — their entry cannot be approved by the professor who normally reviews them —
 * so it is stated up front rather than discovered in the review queue.
 *
 * Admins are the approval authority, so a late submission of theirs is never flagged: they get the
 * shorter wording (`needsAdminApproval` false) and no promise of somebody else's approval.
 *
 * Styling mirrors UnsavedChangesDialog: same overlay, same panel, same button row, Escape cancels —
 * but one z-layer higher (105 vs 100, still below the toast layer at 110). UnsavedChangesDialog stays
 * mounted while its own "Save" runs the submit that opens this dialog (crafts page, ProcessForm), and
 * it sits later in the DOM, so an equal z-index would let it cover "Submit anyway".
 */
export function LateSubmissionDialog({
  open,
  workshopTitle,
  endDate,
  needsAdminApproval,
  saving,
  onConfirm,
  onCancel
}: {
  open: boolean;
  workshopTitle?: string | null;
  endDate?: string | null;
  needsAdminApproval: boolean;
  saving?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();

  useEffect(() => {
    if (!open) return;
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onCancel();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onCancel]);

  if (!open) return null;

  const name = workshopTitle?.trim() || "This workshop";
  const ended = endDate ? ` ended on ${formatDate(endDate)}` : " has already ended";

  return (
    <div
      className="fixed inset-0 z-[105] grid place-items-center bg-ink-900/40 p-4"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !saving) onCancel();
      }}
    >
      <div role="dialog" aria-modal="true" aria-labelledby={titleId} className="panel w-full max-w-md p-5 shadow-lg">
        <h2 id={titleId} className="display-title text-lg">
          Late submission
        </h2>
        <p className="mt-2 text-sm leading-6 text-ink-700">
          <span className="font-medium text-ink-900">{name}</span>
          {ended}.{" "}
          {needsAdminApproval
            ? "Your entry will still be saved, but it is recorded as a late submission: it stays Pending until an admin or master admin approves it — a professor cannot approve it for you."
            : "Your entry will still be saved, and it is recorded as a late submission."}
        </p>
        <p className="mt-2 text-sm leading-6 text-ink-500">
          Pick a different workshop above if this record belongs to one that is still running.
        </p>
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <button type="button" className="field-button-secondary" disabled={saving} onClick={onCancel}>
            Go back
          </button>
          <button type="button" className="field-button" disabled={saving} onClick={onConfirm}>
            {saving ? "Saving…" : "Submit anyway"}
          </button>
        </div>
      </div>
    </div>
  );
}
