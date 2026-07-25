"use client";

import { CollabPanel } from "@/components/CollabPanel";

/**
 * The modal every list page opens from its row "Discuss" button: comments plus (for the record's
 * owner and admins) its edit history, for one record. Rendered only while `recordId` is set, so a
 * page can pass its `collabId` state straight through.
 *
 * Kept here rather than repeated per page so Discuss looks and behaves the same on artisans,
 * products, tools, workshops and processes.
 */
export function CollabDialog({
  recordType,
  recordId,
  onClose
}: {
  recordType: string;
  recordId: string | null;
  onClose: () => void;
}) {
  if (!recordId) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="panel max-h-[85vh] w-full max-w-lg overflow-y-auto p-4" onClick={(event) => event.stopPropagation()}>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-display font-bold text-lg text-ink">Comments &amp; edit history</h2>
          <button className="text-sm text-ink-muted" onClick={onClose}>
            Close
          </button>
        </div>
        <CollabPanel recordType={recordType} recordId={recordId} />
      </div>
    </div>
  );
}
