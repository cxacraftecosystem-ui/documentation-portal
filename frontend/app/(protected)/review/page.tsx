"use client";

import { Fragment, useEffect, useState } from "react";
import { ClipboardCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { ReviewEditPanel } from "@/components/review/ReviewEditPanel";
import { reviewEditableFields } from "@/components/review/reviewEditFields";
import { RowActions, rowAction } from "@/components/RowActions";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { isAdmin, roleLabel } from "@/lib/permissions";

/**
 * One row of the review queue. The backend only returns records whose creator ranks strictly
 * below the viewer (master admin sees everyone), each annotated with its creator.
 *
 * `needsAdminApproval` is the flag the API actually sends for a record submitted outside its
 * workshop's dates (see review.list_pending_reviews); `outOfWindow` is accepted too because the
 * workshop-access service uses that name on other payloads.
 */
type PendingItem = {
  recordType: string;
  id: string;
  label: string;
  place?: string | null;
  status?: string | null;
  createdAt?: string | null;
  createdBy?: { id?: string; name?: string | null; role?: string | null } | null;
  needsAdminApproval?: boolean;
  outOfWindow?: boolean;
};

/** The queue arrives whole (no server paging), so the table pages client-side like every other list. */
const PAGE_SIZE = 20;

type DecisionAction = "approve" | "revise" | "reject";
/** "edit" opens the field editor instead of a note box — the fourth reviewer action (Android parity). */
type PanelAction = DecisionAction | "edit";

const ACTION_COPY: Record<DecisionAction, { noteLabel: string; confirmLabel: string }> = {
  approve: { noteLabel: "Approval note (optional)", confirmLabel: "Confirm approve" },
  revise: { noteLabel: "What must the contributor change? (required)", confirmLabel: "Send for revision" },
  reject: { noteLabel: "Rejection note (optional)", confirmLabel: "Confirm reject" }
};

export default function ReviewPage() {
  const { user } = useAuth();
  const [items, setItems] = useState<PendingItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  // The inline panel currently open: which row, and which of the four actions.
  const [active, setActive] = useState<{ key: string; action: PanelAction } | null>(null);
  const [note, setNote] = useState("");
  const [submitting, setSubmitting] = useState(false);
  /** Receipt for an edit that left the record pending — the row does not move, so say what happened. */
  const [info, setInfo] = useState<string | null>(null);
  const viewerIsAdmin = isAdmin(user);

  async function load() {
    try {
      const result = await apiFetch<{ items: PendingItem[]; total: number }>("/review/pending");
      setItems(result.items);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load review queue");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  // Approving the last row of the final page must not strand the viewer on an empty page.
  const pages = Math.max(1, Math.ceil(items.length / PAGE_SIZE));
  useEffect(() => {
    if (page > pages) setPage(pages);
  }, [page, pages]);
  const visible = items.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  function rowKey(item: PendingItem) {
    return `${item.recordType}-${item.id}`;
  }

  function toggleAction(item: PendingItem, action: PanelAction) {
    const key = rowKey(item);
    setNote("");
    setInfo(null);
    setActive((current) => (current && current.key === key && current.action === action ? null : { key, action }));
  }

  /**
   * A record submitted outside its workshop's dates is admin-only for EVERY review action, the edit
   * included (review.edit_reviewed_record 403s a non-admin) — so the button is not offered, and the
   * row says why rather than leaving the reviewer to discover a 403.
   */
  function editLocked(item: PendingItem) {
    return Boolean(item.needsAdminApproval || item.outOfWindow) && !viewerIsAdmin;
  }

  async function submitDecision(item: PendingItem, action: DecisionAction) {
    const notes = note.trim();
    // "Send for revision" is meaningless without comments — the button stays disabled, but guard anyway.
    if (action === "revise" && !notes) return;
    if (action === "reject" && !window.confirm(`Reject "${item.label}"? The contributor will see this record as rejected.`)) {
      return;
    }
    setSubmitting(true);
    try {
      await apiFetch(`/review/${item.recordType}/${item.id}/${action}`, {
        method: "POST",
        body: JSON.stringify({ notes: notes || null })
      });
      setActive(null);
      setNote("");
      setError(null);
      setInfo(null);
      await load();
    } catch (err) {
      const verb = action === "revise" ? "send this record for revision" : `${action} this record`;
      setError(err instanceof Error ? err.message : `Unable to ${verb}`);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Review"
        description="Approve, fix in place with Edit, send for revision, or reject pending submissions from contributors you may review."
        icon={<ClipboardCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {info ? (
        <div className="mb-4 rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">{info}</div>
      ) : null}
      <section className="panel overflow-hidden">
        {loading ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : items.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No pending submissions"
              body="Pending records from contributors below your rank will appear here for review."
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[880px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Record</ResizableTh>
                  <ResizableTh>Type</ResizableTh>
                  <ResizableTh>Creator</ResizableTh>
                  <ResizableTh>Place or link</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Submitted</ResizableTh>
                  <ResizableTh className="text-right">Decision</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {visible.map((item) => {
                  const key = rowKey(item);
                  const activeAction = active?.key === key ? active.action : null;
                  return (
                    <Fragment key={key}>
                      <tr>
                        <td className="px-4 py-3">
                          <div className="font-medium text-ink-900">
                            {item.label}
                            {item.needsAdminApproval || item.outOfWindow ? (
                              <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
                                Out of workshop window — needs approval
                              </span>
                            ) : null}
                          </div>
                          {editLocked(item) ? (
                            <p className="mt-1 max-w-md text-xs text-amber-800">
                              Submitted outside its workshop&apos;s dates — only an admin or master admin can edit or
                              approve it.
                            </p>
                          ) : null}
                        </td>
                        <td className="px-4 py-3 capitalize text-ink-700">{item.recordType}</td>
                        <td className="px-4 py-3">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-ink-900">{item.createdBy?.name ?? "Unknown"}</span>
                            {item.createdBy?.role ? (
                              <span className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs font-medium text-ink-500">
                                {roleLabel(item.createdBy.role)}
                              </span>
                            ) : null}
                          </div>
                        </td>
                        <td className="px-4 py-3 text-ink-700">{item.place ?? "-"}</td>
                        <td className="px-4 py-3">
                          <StatusBadge status={item.status ?? "PENDING"} />
                        </td>
                        <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                        <td className="px-4 py-3 text-right">
                          <RowActions>
                            <button
                              className={rowAction("positive", activeAction === "approve" ? "bg-success-100" : undefined)}
                              onClick={() => toggleAction(item, "approve")}
                              disabled={submitting}
                            >
                              Approve
                            </button>
                            {/* Fix a small mistake in place rather than costing a round trip through
                                the contributor — the same fourth action the Android review card has. */}
                            {editLocked(item) || reviewEditableFields(item.recordType).length === 0 ? null : (
                              <button
                                className={rowAction("edit", activeAction === "edit" ? "bg-field-100" : undefined)}
                                onClick={() => toggleAction(item, "edit")}
                                disabled={submitting}
                              >
                                {activeAction === "edit" ? "Close editor" : "Edit"}
                              </button>
                            )}
                            <button
                              className={rowAction("neutral", activeAction === "revise" ? "bg-surface-50" : undefined)}
                              onClick={() => toggleAction(item, "revise")}
                              disabled={submitting}
                            >
                              Send for revision
                            </button>
                            <button
                              className={rowAction("danger", activeAction === "reject" ? "bg-red-50" : undefined)}
                              onClick={() => toggleAction(item, "reject")}
                              disabled={submitting}
                            >
                              Reject
                            </button>
                          </RowActions>
                        </td>
                      </tr>
                      {activeAction === "edit" ? (
                        <tr className="bg-surface-50">
                          <td className="px-4 py-3" colSpan={7}>
                            <ReviewEditPanel
                              recordType={item.recordType}
                              recordId={item.id}
                              label={item.label}
                              onSaved={(message) => {
                                // The status did not move, so the row stays put; only the receipt changes.
                                setInfo(message);
                                setError(null);
                              }}
                              onApproved={async (message) => {
                                setActive(null);
                                setInfo(message);
                                setError(null);
                                await load();
                              }}
                              onClose={() => setActive(null)}
                            />
                          </td>
                        </tr>
                      ) : activeAction ? (
                        <tr className="bg-surface-50">
                          <td className="px-4 py-3" colSpan={7}>
                            <label className="block text-xs font-semibold uppercase tracking-wide text-ink-500">
                              {ACTION_COPY[activeAction].noteLabel}
                            </label>
                            <textarea
                              className="mt-1.5 w-full rounded-md border border-line-200 bg-card p-2 text-sm text-ink-900"
                              rows={3}
                              value={note}
                              onChange={(event) => setNote(event.target.value)}
                              autoFocus
                            />
                            {activeAction === "revise" ? (
                              <p className="mt-1 text-xs text-ink-500">
                                Comments are required — the record goes back to the contributor as &quot;Needs revision&quot; and
                                returns to Pending once they edit it.
                              </p>
                            ) : null}
                            <div className="mt-2 flex flex-wrap gap-2">
                              <button
                                className="field-button h-9 min-h-0 px-3 text-xs"
                                disabled={submitting || (activeAction === "revise" && !note.trim())}
                                onClick={() => submitDecision(item, activeAction)}
                              >
                                {submitting ? "Saving..." : ACTION_COPY[activeAction].confirmLabel}
                              </button>
                              <button
                                className="field-button-secondary h-9 min-h-0 px-3 text-xs"
                                disabled={submitting}
                                onClick={() => {
                                  setActive(null);
                                  setNote("");
                                }}
                              >
                                Cancel
                              </button>
                            </div>
                          </td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {!loading && items.length > 0 ? <Pagination page={page} pages={pages} total={items.length} onPage={setPage} /> : null}
      </section>
    </>
  );
}
