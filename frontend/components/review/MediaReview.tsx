"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import {
  fetchReviewReasons,
  fileRefusalLabel,
  refusalOutcome,
  refusalReady,
  rejectMedia,
  type ReviewReason
} from "@/lib/review";

type MediaReviewState = {
  /** The files this reader may reject one by one (`GET /review/{type}/{id}/media`). */
  rejectable: ReadonlySet<string>;
  onRejected: (message: string) => void;
};

const MediaReviewContext = createContext<MediaReviewState | null>(null);

/**
 * A record's read-only page hands its file cards the reviewer's one decision on a single file.
 *
 * Approving a record approves its pending files with it (owner decision 2026-10-18), so a reviewer
 * who disagrees about ONE photograph rejects that file here — before the record is decided or after
 * it was approved. The cards render nothing without a provider, which is every page but the view.
 */
export function MediaReviewProvider({ value, children }: { value: MediaReviewState | null; children: ReactNode }) {
  return <MediaReviewContext.Provider value={value}>{children}</MediaReviewContext.Provider>;
}

/** The Reject action on one file card, with the same reasons a record is rejected with. */
export function MediaRejectAction({ mediaId, label }: { mediaId: string; label: string }) {
  const review = useContext(MediaReviewContext);
  const confirm = useConfirm();
  const [open, setOpen] = useState(false);
  const [reasons, setReasons] = useState<ReviewReason[]>([]);
  const [reasonCode, setReasonCode] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const allowed = Boolean(review?.rejectable.has(mediaId));

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    fetchReviewReasons()
      .then((list) => {
        if (!cancelled) setReasons(list);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [open]);

  if (!review || !allowed) return null;
  const reason = reasons.find((r) => r.code === reasonCode) ?? null;
  const ready = reasons.length === 0 || refusalReady(reason, note);

  async function submit() {
    if (!review) return;
    if (refusalOutcome(reason) === "REJECTED") {
      const ok = await confirm({
        title: "Reject this file?",
        body: <span className="font-medium text-ink-900">{label}</span>,
        note: "Its record keeps its own decision.",
        tone: "danger",
        confirmLabel: "Reject file"
      });
      if (!ok) return;
    }
    setBusy(true);
    setError(null);
    try {
      const answer = await rejectMedia(mediaId, note.trim(), reason);
      setOpen(false);
      setNote("");
      setReasonCode("");
      review.onRejected(answer?.status === "NEEDS_REVISION" ? "File sent back for revision." : "File rejected.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "That file wasn't rejected.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-2 grid gap-2" data-row-ignore>
      {open ? (
        <div className="grid gap-2 rounded-md bg-surface-50 p-3">
          {reasons.length > 0 ? (
            <div className="grid gap-1">
              <span className="field-label">Reason</span>
              <SearchableSelect
                ariaLabel="Reason"
                value={reasonCode}
                onChange={setReasonCode}
                advanceOnSelect={false}
                placeholder="Pick a reason"
                options={reasons.map((r) => ({ value: r.code, label: r.label }))}
              />
            </div>
          ) : null}
          <label className="field-label" htmlFor={`media-reject-note-${mediaId}`}>
            {reason?.needsNote ? "Note for the uploader (required)" : "Note for the uploader"}
          </label>
          <textarea
            id={`media-reject-note-${mediaId}`}
            className="field-input"
            rows={2}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
          {error ? <p className="text-xs text-red-700 dark:text-red-300">{error}</p> : null}
          <ButtonRow size="sm" align="start">
            <button
              type="button"
              className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
              disabled={busy || !ready}
              onClick={submit}
            >
              {busy ? "Saving…" : fileRefusalLabel(reason)}
            </button>
            <button
              type="button"
              className="field-button-secondary h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
              disabled={busy}
              onClick={() => setOpen(false)}
            >
              Cancel
            </button>
          </ButtonRow>
        </div>
      ) : (
        <ButtonRow size="sm" align="start">
          <button
            type="button"
            className="field-danger h-9 min-h-0 rounded-md px-3 text-xs pointer-coarse:h-11"
            onClick={() => {
              setReasonCode("");
              setOpen(true);
            }}
          >
            Reject
          </button>
        </ButtonRow>
      )}
    </div>
  );
}
