"use client";

import { CalendarClock } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { formatDate } from "@/lib/format";

/**
 * Confirmation shown when a record is about to be saved into a workshop that has already ended.
 * Mirrors the Android `LateSubmissionDialog` in `MainActivity` — same title, same two choices, same
 * two-tier wording.
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
 * One z-layer above the ordinary dialog layer (105 vs 100, still below the toast layer at 110):
 * `UnsavedChangesDialog` stays mounted while its own "Save" runs the submit that opens this dialog
 * (crafts page, ProcessForm), and an equal z-index would let it cover "Submit anyway".
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
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const name = workshopTitle?.trim() || "This workshop";
  const ended = endDate ? ` ended on ${formatDate(endDate)}` : " has already ended";

  return (
    <FieldDialog
      open={open}
      onClose={onCancel}
      role="alertdialog"
      tone="warning"
      icon={<CalendarClock className="h-4 w-4" aria-hidden />}
      busy={saving}
      // Nothing is destroyed by backing out, so the backdrop is allowed to be the way out.
      dismissOnBackdrop
      initialFocusRef={cancelRef}
      zIndex={105}
      title="Late submission"
      description={
        <>
          <span className="font-medium text-ink-900">{name}</span>
          {ended}.{" "}
          {needsAdminApproval
            ? "Your entry will still be saved, but it is recorded as a late submission: it stays Pending until an admin or master admin approves it — a professor cannot approve it for you."
            : "Your entry will still be saved, and it is recorded as a late submission."}
        </>
      }
      footer={
        <>
          <button type="button" ref={cancelRef} className="field-button-secondary" disabled={saving} onClick={onCancel}>
            Go back
          </button>
          <button type="button" className="field-button" disabled={saving} onClick={onConfirm}>
            {saving ? "Saving…" : "Submit anyway"}
          </button>
        </>
      }
    >
      <p className="mt-2 text-sm leading-6 text-ink-500">
        Pick a different workshop above if this record belongs to one that is still running.
      </p>
    </FieldDialog>
  );
}
