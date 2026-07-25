"use client";

import { useEffect, useId } from "react";

/**
 * Unsaved-changes prompt (Android parity): shown when the user tries to leave a form that
 * still has unsaved work. "Save" runs the form's own validated save, "Discard" leaves and
 * drops the in-progress data, "Keep editing" stays on the form.
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
  const titleId = useId();

  useEffect(() => {
    if (!open) return;
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onKeepEditing();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onKeepEditing]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[100] grid place-items-center bg-ink-900/40 p-4"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !saving) onKeepEditing();
      }}
    >
      <div role="dialog" aria-modal="true" aria-labelledby={titleId} className="panel w-full max-w-md p-5 shadow-lg">
        <h2 id={titleId} className="display-title text-lg">
          Unsaved changes
        </h2>
        <p className="mt-2 text-sm leading-6 text-ink-700">
          You have unsaved changes, including any recordings or media you just captured. Save them before leaving, or
          discard them?
        </p>
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <button type="button" className="field-button-secondary" disabled={saving} onClick={onKeepEditing}>
            Keep editing
          </button>
          <button type="button" className="field-danger" disabled={saving} onClick={onDiscard}>
            Discard
          </button>
          <button type="button" className="field-button" disabled={saving} onClick={onSave}>
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}
