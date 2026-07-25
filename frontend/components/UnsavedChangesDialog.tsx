"use client";

import { FilePenLine } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";

/**
 * Unsaved-changes prompt (Android parity, `MainActivity` `pendingExit`): shown when the user tries to
 * leave a form that still has unsaved work. "Save" runs the form's own validated save — a missing
 * required field keeps them on the form with the field highlighted — "Discard" leaves and drops the
 * in-progress data, "Keep editing" stays.
 *
 * Three ways forward, so there is no corner X (`showClose={false}`): an X would have to silently mean
 * one of the three, and the user cannot tell which. Escape and a backdrop click both mean "Keep
 * editing", the only one of the three that loses nothing.
 *
 * Built on `FieldDialog` rather than its own overlay, which is what gives it the focus trap, focus
 * restoration back to the control the user was leaving from, and the shared ARIA wiring.
 */
export function UnsavedChangesDialog({
  open,
  saving,
  onKeepEditing,
  onDiscard,
  onSave
}: {
  open: boolean;
  saving?: boolean;
  onKeepEditing: () => void;
  onDiscard: () => void;
  onSave: () => void;
}) {
  const keepEditingRef = useRef<HTMLButtonElement | null>(null);

  return (
    <FieldDialog
      open={open}
      onClose={onKeepEditing}
      role="alertdialog"
      tone="warning"
      icon={<FilePenLine className="h-4 w-4" aria-hidden />}
      busy={saving}
      showClose={false}
      // A backdrop click is the same reflex as Escape, and here it costs nothing: it keeps editing.
      dismissOnBackdrop
      initialFocusRef={keepEditingRef}
      title="Unsaved changes"
      description="You have unsaved changes, including any recordings or media you just captured. Save them before leaving, or discard them?"
      footer={
        <>
          <button
            type="button"
            ref={keepEditingRef}
            className="field-button-secondary"
            disabled={saving}
            onClick={onKeepEditing}
          >
            Keep editing
          </button>
          <button type="button" className="field-danger" disabled={saving} onClick={onDiscard}>
            Discard
          </button>
          <button type="button" className="field-button" disabled={saving} onClick={onSave}>
            {saving ? "Saving…" : "Save"}
          </button>
        </>
      }
    />
  );
}
