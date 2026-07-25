/**
 * The dialog family. Everything modal in the web app comes from here so that focus handling,
 * Escape, backdrop rules, ARIA and the destructive tone are decided once rather than per page.
 *
 * - `FieldDialog` — the primitive. Build new dialogs on it; do not hand-roll another overlay.
 * - `ConfirmProvider` / `useConfirm` — the promise-based replacement for `window.confirm()`.
 * - `AppUpdateWatcher` — non-dismissable "Update required" when the tab's build has gone stale.
 * - `OfflineWatcher` — the offline notice that stands in for Android's offline outbox.
 *
 * The three purpose-built dialogs that predate this folder — `UnsavedChangesDialog`,
 * `LateSubmissionDialog` and `DuplicateArtisanDialog` — are also built on `FieldDialog` and are
 * re-exported here so a page has one import site for all of them.
 */

export { DANGER_BUTTON_CLASS, FieldDialog, type DialogTone, type FieldDialogProps } from "@/components/dialogs/FieldDialog";
export { ConfirmDialog, ConfirmProvider, deleteConfirm, useConfirm, type ConfirmOptions } from "@/components/dialogs/ConfirmDialog";
export { AppUpdateDialog, AppUpdateWatcher } from "@/components/dialogs/AppUpdateDialog";
export { OfflineDialog, OfflineWatcher } from "@/components/dialogs/OfflineDialog";
export { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
export { LateSubmissionDialog } from "@/components/LateSubmissionDialog";
export { DuplicateArtisanDialog } from "@/components/forms/DuplicateArtisanDialog";
