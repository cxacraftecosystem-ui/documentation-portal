"use client";

import { CalendarClock } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { formatDate } from "@/lib/format";

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
      dismissOnBackdrop
      initialFocusRef={cancelRef}
      zIndex={105}
      title="Late submission"
      description={
        <>
          <span className="font-medium text-ink-900">{name}</span>
          {ended}.{" "}
          {needsAdminApproval
            ? "It will be saved as late and needs admin approval."
            : "It will be saved as late."}
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
        Or pick a workshop that’s still running.
      </p>
    </FieldDialog>
  );
}
