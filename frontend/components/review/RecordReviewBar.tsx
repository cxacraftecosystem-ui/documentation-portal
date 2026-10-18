"use client";

import { useEffect, useState } from "react";
import { ClipboardCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { apiFetch } from "@/lib/api";
import { canReview as mayReview } from "@/lib/permissions";
import {
  alsoApprovesLine,
  approvedReceipt,
  decisionBody,
  fetchLatestReview,
  fetchReviewReasons,
  refusalButtonLabel,
  refusalOutcome,
  refusalReady,
  reviseReasons,
  type ReviewReason
} from "@/lib/review";

type Panel = "revise" | "reject" | null;

/**
 * Approve, send back or reject a PENDING record from its own read-only page (A13).
 *
 * A reviewer who opened a record to read it all used to have to go back to the queue and find the
 * row again to decide. The bar is drawn only when the server says this reader may review this record
 * (`canReview` on `GET /review/{type}/{id}/latest`), so it never offers a decision the API refuses.
 *
 * Approving also approves the record's files that are still pending; with any, Approve asks first
 * and says how many (`approvesWith`, from `GET /review/{type}/{id}/media`).
 */
export function RecordReviewBar({
  recordType,
  recordId,
  status,
  approvesWith = 0,
  onDecided
}: {
  recordType: string;
  recordId: string;
  status: string | null | undefined;
  approvesWith?: number;
  onDecided: (message: string) => void;
}) {
  const confirm = useConfirm();
  const { user } = useAuth();
  // Only an account with review access asks: anyone else would be refused (403) on every view.
  const reviewer = mayReview(user);
  const [canReview, setCanReview] = useState(false);
  const [late, setLate] = useState(false);
  const [panel, setPanel] = useState<Panel>(null);
  const [note, setNote] = useState("");
  const [reasons, setReasons] = useState<ReviewReason[]>([]);
  const [reasonCode, setReasonCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = status === "PENDING";

  useEffect(() => {
    if (!pending || !reviewer) {
      setCanReview(false);
      return;
    }
    let cancelled = false;
    fetchLatestReview(recordType, recordId)
      .then((answer) => {
        if (cancelled) return;
        setCanReview(answer.canReview);
        setLate(answer.needsAdminApproval);
      })
      .catch(() => {
        if (!cancelled) setCanReview(false);
      });
    fetchReviewReasons()
      .then((list) => {
        if (!cancelled) setReasons(list);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [recordType, recordId, pending, reviewer]);

  if (!pending || !canReview) return null;
  const reason = reasons.find((r) => r.code === reasonCode) ?? null;

  async function decide(action: "approve" | "revise" | "reject") {
    const notes = note.trim();
    if (action === "reject" && refusalOutcome(reason) === "REJECTED") {
      const ok = await confirm({
        title: "Reject this record?",
        body: "It shows as rejected to the contributor until they fix it.",
        tone: "danger",
        confirmLabel: "Reject record"
      });
      if (!ok) return;
    }
    const files = alsoApprovesLine(approvesWith);
    if (action === "approve" && files) {
      const ok = await confirm({ title: "Approve this record?", body: files, confirmLabel: "Approve" });
      if (!ok) return;
    }
    setBusy(true);
    setError(null);
    try {
      // A reason on "Send for revision" is optional and only a fixable one is offered.
      const sent = action === "approve" ? null : action === "revise" ? (reason?.fixable ? reason : null) : reason;
      const body = decisionBody(notes, sent);
      const answer = await apiFetch<{ mediaApproved?: number }>(
        `/review/${encodeURIComponent(recordType)}/${encodeURIComponent(recordId)}/${action}`,
        { method: "POST", body: JSON.stringify(body) }
      );
      setPanel(null);
      setNote("");
      onDecided(
        action === "approve"
          ? approvedReceipt(answer?.mediaApproved)
          : action === "revise" || refusalOutcome(reason) === "NEEDS_REVISION"
            ? "Sent back for revision."
            : "Rejected."
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "That review action didn't go through.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel grid gap-3 p-4" aria-label="Review">
      <div className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <ClipboardCheck className="h-4 w-4 text-purple-700" aria-hidden />
        Waiting for your review
      </div>
      {late ? <p className="text-xs text-amber-800">Submitted outside workshop dates — admins only can approve.</p> : null}
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <ButtonRow
        size="sm"
        align="start"
        destructive={
          <button
            type="button"
            className="field-danger h-9 min-h-0 rounded-md px-3 text-xs pointer-coarse:h-11"
            onClick={() => {
              setReasonCode("");
              setPanel(panel === "reject" ? null : "reject");
            }}
            disabled={busy}
          >
            Reject
          </button>
        }
      >
        <button type="button" className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11" onClick={() => decide("approve")} disabled={busy}>
          {busy && panel === null ? "Saving…" : "Approve"}
        </button>
        <button
          type="button"
          className="field-button-secondary h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
          onClick={() => {
            setReasonCode("");
            setPanel(panel === "revise" ? null : "revise");
          }}
          disabled={busy}
        >
          Send for revision
        </button>
      </ButtonRow>
      {panel ? (
        <div className="grid gap-2 rounded-md bg-surface-50 p-3">
          {panel === "reject" && reasons.length > 0 ? (
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
              {reason?.fixable ? <p className="text-xs text-ink-500">This sends it back to be fixed.</p> : null}
            </div>
          ) : null}
          {panel === "revise" && reviseReasons(reasons).length > 0 ? (
            <div className="grid gap-1">
              <span className="field-label">Reason (optional)</span>
              <SearchableSelect
                ariaLabel="Reason"
                value={reasonCode}
                onChange={setReasonCode}
                advanceOnSelect={false}
                placeholder="Pick a reason"
                clearable
                clearLabel="No reason"
                options={reviseReasons(reasons).map((r) => ({ value: r.code, label: r.label }))}
              />
            </div>
          ) : null}
          <label className="field-label" htmlFor={`record-review-note-${recordId}`}>
            {panel === "revise" ? "What must the contributor change? (required)" : "Note for the contributor"}
          </label>
          <textarea
            id={`record-review-note-${recordId}`}
            className="field-input"
            rows={3}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
          <ButtonRow size="sm" align="start">
            <button
              type="button"
              className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
              disabled={
                busy ||
                (panel === "revise" && !note.trim()) ||
                (panel === "reject" && reasons.length > 0 && !refusalReady(reason, note))
              }
              onClick={() => decide(panel)}
            >
              {busy ? "Saving…" : panel === "revise" ? "Send for revision" : refusalButtonLabel(reason)}
            </button>
            <button
              type="button"
              className="field-button-secondary h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
              onClick={() => setPanel(null)}
              disabled={busy}
            >
              Cancel
            </button>
          </ButtonRow>
        </div>
      ) : null}
    </section>
  );
}
