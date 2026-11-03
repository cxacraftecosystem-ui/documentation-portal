"use client";

import { Fragment, useEffect, useRef, useState } from "react";
import { Check, ClipboardCheck, RotateCcw, X } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { OpenableBlock, OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { ReviewEditPanel } from "@/components/review/ReviewEditPanel";
import { reviewEditableFields } from "@/components/review/reviewEditFields";
import { RowActions, rowAction } from "@/components/RowActions";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch, listResource } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { isAdmin, roleLabel } from "@/lib/permissions";
import { recordViewHref } from "@/lib/recordView";
import {
  alsoApprovesLine,
  approvedReceipt,
  bulkApprove,
  bulkReceipt,
  decisionBody,
  fetchReviewReasons,
  queueFiltersFrom,
  queueQuery,
  refusalButtonLabel,
  refusalOutcome,
  refusalReady,
  reviseReasons,
  type PreviousReview,
  type QueueFilters,
  type ReviewReason
} from "@/lib/review";
import type { Workshop } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";

type PendingItem = {
  recordType: string;
  id: string;
  label: string;
  place?: string | null;
  status?: string | null;
  createdAt?: string | null;
  submittedAt?: string | null;
  createdBy?: { id?: string; name?: string | null; role?: string | null } | null;
  needsAdminApproval?: boolean;
  outOfWindow?: boolean;
  resubmitted?: boolean;
  previousReview?: PreviousReview | null;
  pendingMediaCount?: number;
};

function rowKey(item: PendingItem) {
  return `${item.recordType}-${item.id}`;
}

function ReviewTitle({ item }: { item: PendingItem }) {
  const href = recordViewHref(item.recordType, item.id);
  return href ? <RecordTitleLink href={href}>{item.label}</RecordTitleLink> : <>{item.label}</>;
}

function ReviewCardBody({ item, children }: { item: PendingItem; children: React.ReactNode }) {
  const href = recordViewHref(item.recordType, item.id);
  if (!href) return <div className="flex items-start gap-2">{children}</div>;
  return (
    <OpenableBlock href={href} className="-m-2 flex items-start gap-2 rounded-md p-2">
      {children}
    </OpenableBlock>
  );
}

function ReviewRow({ item, className, children }: { item: PendingItem; className?: string; children: React.ReactNode }) {
  const href = recordViewHref(item.recordType, item.id);
  if (!href) return <tr className={className}>{children}</tr>;
  return (
    <OpenableRow href={href} className={className}>
      {children}
    </OpenableRow>
  );
}

const PAGE_SIZE = 20;

type DecisionAction = "approve" | "revise" | "reject";
type PanelAction = DecisionAction | "edit";

const ACTION_COPY: Record<DecisionAction, { noteLabel: string; confirmLabel: string }> = {
  approve: { noteLabel: "Approval note (optional)", confirmLabel: "Confirm approve" },
  revise: { noteLabel: "What must the contributor change? (required)", confirmLabel: "Send for revision" },
  reject: { noteLabel: "Note for the contributor", confirmLabel: "Reject record" }
};

const ORDER_OPTIONS = [
  { value: "oldest", label: "Waiting longest" },
  { value: "newest", label: "Newest first" }
];

export default function ReviewPage() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const [items, setItems] = useState<PendingItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const currentLoad = useRef(0);
  const [error, setError] = useState<string | null>(null);
  const [active, setActive] = useState<{ key: string; action: PanelAction } | null>(null);
  const [note, setNote] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [info, setInfo] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkProgress, setBulkProgress] = useState<{ done: number; total: number } | null>(null);
  const viewerIsAdmin = isAdmin(user);
  const [filters, setFilters] = useState<QueueFilters | null>(null);
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [reasons, setReasons] = useState<ReviewReason[]>([]);
  const [reasonCode, setReasonCode] = useState("");

  const [workshopSearch, setWorkshopSearch] = useState("");
  const workshopLoad = useRef(0);
  useEffect(() => {
    const generation = ++workshopLoad.current;
    const timer = window.setTimeout(() => {
      listResource<Workshop>("/workshops", { pageSize: 80, search: workshopSearch.trim() || undefined })
        .then((result) => {
          if (generation !== workshopLoad.current) return;
          setWorkshops((current) => {
            const chosen = current.filter((w) => w.id === filters?.workshopId);
            return [...chosen, ...result.items.filter((w) => !chosen.some((c) => c.id === w.id))];
          });
        })
        .catch(() => undefined);
    }, workshopSearch ? 250 : 0);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workshopSearch]);

  useEffect(() => {
    setFilters(queueFiltersFrom(new URLSearchParams(window.location.search)));
    fetchReviewReasons()
      .then(setReasons)
      .catch(() => setReasons([]));
  }, []);

  function changeFilters(next: QueueFilters) {
    setFilters(next);
    setPage(1);
    setSelected(new Set());
  }

  async function load(onPage: number) {
    if (!filters) return;
    const generation = ++currentLoad.current;
    try {
      const result = await apiFetch<{ items: PendingItem[]; total: number }>(
        `/review/pending${queueQuery(onPage, PAGE_SIZE, filters)}`
      );
      if (generation !== currentLoad.current) return;
      setItems(result.items);
      setTotal(result.total);
      setError(null);
    } catch (err) {
      if (generation !== currentLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load review queue");
    } finally {
      if (generation === currentLoad.current) setLoading(false);
    }
  }

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, filters]);

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  useEffect(() => {
    if (!loading && page > pages) setPage(pages);
  }, [loading, page, pages]);
  const visible = items;
  const eligible = visible.filter(bulkEligible);
  const chosen = items.filter((item) => selected.has(rowKey(item)));
  const allEligibleTicked = eligible.length > 0 && eligible.every((item) => selected.has(rowKey(item)));

  useEffect(() => {
    setSelected((current) => {
      if (current.size === 0) return current;
      const live = new Set(items.map(rowKey));
      const next = new Set([...current].filter((key) => live.has(key)));
      return next.size === current.size ? current : next;
    });
  }, [items]);

  function bulkEligible(item: PendingItem) {
    return !editLocked(item);
  }

  function tickPage(on: boolean) {
    setSelected((current) => {
      const next = new Set(current);
      eligible.forEach((item) => (on ? next.add(rowKey(item)) : next.delete(rowKey(item))));
      return next;
    });
  }

  function toggleSelected(item: PendingItem) {
    const key = rowKey(item);
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  async function approveSelected(rows: PendingItem[]) {
    const files = alsoApprovesLine(rows.reduce((sum, row) => sum + (row.pendingMediaCount ?? 0), 0));
    const ok = await confirm({
      title: `Approve ${rows.length} record${rows.length === 1 ? "" : "s"}?`,
      body: files ? `Approved as they are, with no note. ${files}` : "Approved as they are, with no note.",
      note: "Can’t be undone here.",
      confirmLabel: `Approve ${rows.length}`
    });
    if (!ok) return;

    setSubmitting(true);
    setError(null);
    setInfo(null);
    setBulkProgress({ done: 0, total: rows.length });
    let failed: string[] = [];
    let approved = 0;
    let filesApproved = 0;
    try {
      const answer = await bulkApprove(rows.map((item) => ({ recordType: item.recordType, id: item.id })));
      failed = answer.results.filter((r) => r.outcome === "FAILED").map((r) => `${r.recordType}-${r.id}`);
      approved = answer.approved;
      filesApproved = answer.mediaApproved ?? 0;
    } catch (err) {
      failed = rows.map(rowKey);
      setError(err instanceof Error ? err.message : "Unable to approve these records");
    }
    setBulkProgress(null);
    setSubmitting(false);
    setSelected(new Set(failed));
    if (approved > 0 || failed.length < rows.length) {
      if (failed.length) setError(bulkReceipt(approved, failed.length, filesApproved));
      else setInfo(bulkReceipt(approved, 0, filesApproved));
    }
    await load(page);
  }

  function toggleAction(item: PendingItem, action: PanelAction) {
    const key = rowKey(item);
    setNote("");
    setReasonCode("");
    setInfo(null);
    setActive((current) => (current && current.key === key && current.action === action ? null : { key, action }));
  }

  function editLocked(item: PendingItem) {
    return Boolean(item.needsAdminApproval || item.outOfWindow) && !viewerIsAdmin;
  }

  async function submitDecision(item: PendingItem, action: DecisionAction) {
    const notes = note.trim();
    if (action === "revise" && !notes) return;
    const reason = reasons.find((r) => r.code === reasonCode) ?? null;
    if (action === "reject" && reasons.length > 0 && !refusalReady(reason, notes)) return;
    const sentReason = action === "approve" ? null : action === "revise" ? (reason?.fixable ? reason : null) : reason;
    if (action === "reject" && refusalOutcome(reason) === "REJECTED") {
      const ok = await confirm({
        title: "Reject this record?",
        body: (
          <>
            <span className="font-medium text-ink-900">{item.label}</span> will show as rejected to the contributor.
          </>
        ),
        note: "To ask for changes, use Send for revision.",
        tone: "danger",
        confirmLabel: "Reject record"
      });
      if (!ok) return;
    }
    setSubmitting(true);
    try {
      const answer = await apiFetch<{ mediaApproved?: number }>(`/review/${item.recordType}/${item.id}/${action}`, {
        method: "POST",
        body: JSON.stringify(decisionBody(notes, sentReason))
      });
      setActive(null);
      setNote("");
      setReasonCode("");
      setError(null);
      setInfo(
        action === "reject" && refusalOutcome(reason) === "NEEDS_REVISION"
          ? `Sent “${item.label}” back for revision.`
          : action === "approve" && answer?.mediaApproved
            ? approvedReceipt(answer.mediaApproved)
            : null
      );
      await load(page);
    } catch (err) {
      const verb = action === "revise" ? "send this record for revision" : `${action} this record`;
      setError(err instanceof Error ? err.message : `Unable to ${verb}`);
    } finally {
      setSubmitting(false);
    }
  }

  function decisionButtons(item: PendingItem, activeAction: PanelAction | null) {
    return (
      <RowActions>
        <button
          className={rowAction("positive", activeAction === "approve" ? "bg-success-100" : undefined)}
          onClick={() => toggleAction(item, "approve")}
          disabled={submitting}
        >
          Approve
        </button>
        {editLocked(item) || reviewEditableFields(item.recordType).length === 0 ? null : (
          <button
            className={rowAction("edit", activeAction === "edit" ? "bg-field-100" : undefined)}
            onClick={() => toggleAction(item, "edit")}
            disabled={submitting}
          >
            {activeAction === "edit" ? "Close editor" : "Update"}
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
          className={rowAction("danger", activeAction === "reject" ? "bg-red-50 dark:bg-red-950/40" : undefined)}
          onClick={() => toggleAction(item, "reject")}
          disabled={submitting}
        >
          Reject
        </button>
      </RowActions>
    );
  }

  function actionPanel(item: PendingItem, activeAction: PanelAction, fill = false) {
    if (activeAction === "edit") {
      return (
        <ReviewEditPanel
          recordType={item.recordType}
          recordId={item.id}
          label={item.label}
          onSaved={(message) => {
            setInfo(message);
            setError(null);
          }}
          onApproved={async (message) => {
            setActive(null);
            setInfo(message);
            setError(null);
            await load(page);
          }}
          onClose={() => setActive(null)}
        />
      );
    }
    const noteId = `review-note-${rowKey(item)}`;
    const reason = reasons.find((r) => r.code === reasonCode) ?? null;
    const picking = activeAction === "reject" && reasons.length > 0;
    const fixable = activeAction === "revise" ? reviseReasons(reasons) : [];
    const confirmLabel = picking ? refusalButtonLabel(reason) : ACTION_COPY[activeAction].confirmLabel;
    const blocked =
      submitting || (activeAction === "revise" && !note.trim()) || (picking && !refusalReady(reason, note));
    return (
      <>
        {picking ? (
          <div className="mb-3 grid gap-1">
            <span className="block text-xs font-semibold uppercase tracking-wide text-ink-500">Reason</span>
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
        {fixable.length > 0 ? (
          <div className="mb-3 grid gap-1">
            <span className="block text-xs font-semibold uppercase tracking-wide text-ink-500">Reason (optional)</span>
            <SearchableSelect
              ariaLabel="Reason"
              value={reasonCode}
              onChange={setReasonCode}
              advanceOnSelect={false}
              placeholder="Pick a reason"
              clearable
              clearLabel="No reason"
              options={fixable.map((r) => ({ value: r.code, label: r.label }))}
            />
          </div>
        ) : null}
        <label htmlFor={noteId} className="block text-xs font-semibold uppercase tracking-wide text-ink-500">
          {picking ? (reason?.needsNote ? "Note for the contributor (required)" : "Note for the contributor (optional)") : ACTION_COPY[activeAction].noteLabel}
        </label>
        <textarea
          id={noteId}
          className="mt-1.5 w-full rounded-md border border-line-200 bg-card p-2 text-sm text-ink-900"
          rows={3}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          autoFocus
        />
        {activeAction === "revise" ? (
          <p className="mt-1 text-xs text-ink-500">
            Comments required.
          </p>
        ) : null}
        {activeAction === "approve" && alsoApprovesLine(item.pendingMediaCount) ? (
          <p className="mt-1 text-xs text-ink-700">{alsoApprovesLine(item.pendingMediaCount)}</p>
        ) : null}
        <ButtonRow size="sm" fill={fill} className="mt-2">
          <button
            className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
            disabled={blocked}
            onClick={() => submitDecision(item, activeAction)}
          >
            {submitting ? "Saving..." : confirmLabel}
          </button>
          <button
            className="field-button-secondary h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
            disabled={submitting}
            onClick={() => {
              setActive(null);
              setNote("");
            }}
          >
            Cancel
          </button>
        </ButtonRow>
      </>
    );
  }

  function rowCheckbox(item: PendingItem) {
    if (!bulkEligible(item)) return null;
    return (
      <label className="-m-2 inline-grid cursor-pointer place-items-center p-2 pointer-coarse:min-h-11 pointer-coarse:min-w-11">
        <input
          type="checkbox"
          className="h-4 w-4 accent-purple-700 pointer-coarse:h-5 pointer-coarse:w-5"
          aria-label={`Select ${item.label}`}
          checked={selected.has(rowKey(item))}
          onChange={() => toggleSelected(item)}
        />
      </label>
    );
  }

  function resubmittedNote(item: PendingItem) {
    if (!item.resubmitted) return null;
    const previous = item.previousReview;
    return (
      <div className="mt-1 max-w-xl">
        <span className="inline-flex items-center gap-1 rounded-full border border-purple-300 bg-purple-50 px-2 py-0.5 text-xs font-semibold text-purple-700">
          <RotateCcw className="h-3 w-3" aria-hidden />
          Resubmitted
        </span>
        {previous?.notes ? (
          <p className="mt-1 text-xs text-ink-500">
            Last time{previous.reviewerName ? ` (${previous.reviewerName})` : ""}:{" "}
            <span className="text-ink-700">{previous.notes}</span>
          </p>
        ) : null}
      </div>
    );
  }

  function outOfWindowNotes(item: PendingItem) {
    return (
      <>
        {item.needsAdminApproval || item.outOfWindow ? (
          <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
            Out of workshop window — needs approval
          </span>
        ) : null}
        {editLocked(item) ? (
          <p className="mt-1 max-w-md text-xs text-amber-800 dark:text-amber-200">
            Submitted outside workshop dates — admins only.
          </p>
        ) : null}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Review"
        description="Pending submissions to review."
        icon={<ClipboardCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {info ? (
        <div className="mb-4 rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">{info}</div>
      ) : null}
      {filters ? (
        <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <div className="grid gap-1">
            <span className="field-label">Workshop</span>
            <SearchableSelect
              ariaLabel="Workshop"
              value={filters.workshopId ?? ""}
              onChange={(value) => changeFilters({ ...filters, workshopId: value || undefined })}
              options={workshops.map((w) => ({ value: w.id, label: w.title, hint: w.place || undefined }))}
              placeholder="All workshops"
              searchable
              onSearch={setWorkshopSearch}
              clearable
              clearLabel="Show all workshops"
              advanceOnSelect={false}
            />
          </div>
          <div className="grid gap-1">
            <span className="field-label">Order</span>
            <SegmentedChoice
              label="Order"
              value={filters.order ?? "oldest"}
              onChange={(value) => changeFilters({ ...filters, order: value === "newest" ? "newest" : undefined })}
              options={ORDER_OPTIONS}
            />
          </div>
          {filters.createdById || filters.recordType || filters.artisanId ? (
            <div className="flex items-end">
              <button
                type="button"
                className="inline-flex min-h-10 items-center gap-1.5 rounded-full border border-purple-300 bg-purple-50 px-3 text-sm text-purple-800"
                onClick={() => changeFilters({ ...filters, createdById: undefined, recordType: undefined, artisanId: undefined })}
              >
                From a task
                <X className="h-4 w-4" aria-hidden />
                <span className="sr-only">Show everyone&apos;s records</span>
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
      <section className="panel overflow-hidden">
        {loading ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : items.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No pending submissions"
              body="You’re all caught up."
            />
          </div>
        ) : (
          <>
          {chosen.length ? (
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line-200 bg-purple-50 px-4 py-3">
              <span className="text-sm font-medium text-ink-900">
                {chosen.length} record{chosen.length === 1 ? "" : "s"} selected
                {bulkProgress ? ` · approving ${bulkProgress.done + 1} of ${bulkProgress.total}…` : ""}
              </span>
              <ButtonRow size="sm" align="end" className="w-full sm:w-auto">
                <button
                  type="button"
                  className="field-button-secondary h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
                  onClick={() => setSelected(new Set())}
                >
                  Clear
                </button>
                <button
                  type="button"
                  className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
                  disabled={submitting}
                  onClick={() => approveSelected(chosen)}
                >
                  <Check className="h-3.5 w-3.5" aria-hidden />
                  Approve {chosen.length}
                </button>
              </ButtonRow>
            </div>
          ) : null}
          <div className="md:hidden">
            {eligible.length ? (
              <label className="flex cursor-pointer items-center gap-3 border-b border-line-200 bg-surface-50 px-4 py-2 text-sm text-ink-700 pointer-coarse:min-h-11">
                <input
                  type="checkbox"
                  className="h-5 w-5 accent-purple-700"
                  checked={allEligibleTicked}
                  onChange={(event) => tickPage(event.target.checked)}
                />
                Select every record on this page
              </label>
            ) : null}
            <ul className="divide-y divide-line-200">
              {visible.map((item) => {
                const key = rowKey(item);
                const activeAction = active?.key === key ? active.action : null;
                return (
                  <li key={key} className={selected.has(key) ? "bg-purple-50/60 p-4 dark:bg-purple-950/30" : "p-4"}>
                    <ReviewCardBody item={item}>
                      {rowCheckbox(item)}
                      <div className="min-w-0 flex-1">
                        <div className="font-medium text-ink-900">
                          <ReviewTitle item={item} />
                          {outOfWindowNotes(item)}
                        </div>
                        {resubmittedNote(item)}
                        <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs text-ink-700">
                          <dt className="text-ink-500">Type</dt>
                          <dd className="capitalize">{item.recordType}</dd>
                          <dt className="text-ink-500">Creator</dt>
                          <dd>
                            {item.createdBy?.name ?? "Unknown"}
                            {item.createdBy?.role ? ` · ${roleLabel(item.createdBy.role)}` : ""}
                          </dd>
                          <dt className="text-ink-500">Place</dt>
                          <dd>{item.place ?? "-"}</dd>
                          <dt className="text-ink-500">Waiting since</dt>
                          <dd>{formatDateTime(item.submittedAt ?? item.createdAt)}</dd>
                        </dl>
                      </div>
                      <StatusBadge status={item.status ?? "PENDING"} />
                    </ReviewCardBody>
                    <div data-row-ignore className="mt-3 [&>div]:justify-start">
                      {decisionButtons(item, activeAction)}
                    </div>
                    {activeAction ? (
                      <div data-row-ignore className="mt-3 rounded-md bg-surface-50 p-3">
                        {actionPanel(item, activeAction, true)}
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </div>
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[920px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th scope="col" className="w-10 px-4 py-3">
                    <input
                      type="checkbox"
                      className="h-4 w-4 accent-purple-700"
                      aria-label="Select every record on this page"
                      checked={allEligibleTicked}
                      disabled={eligible.length === 0}
                      onChange={(event) => tickPage(event.target.checked)}
                    />
                  </th>
                  <ResizableTh>Record</ResizableTh>
                  <ResizableTh>Type</ResizableTh>
                  <ResizableTh>Creator</ResizableTh>
                  <ResizableTh>Place or link</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Waiting since</ResizableTh>
                  <ResizableTh className="text-right">Decision</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {visible.map((item) => {
                  const key = rowKey(item);
                  const activeAction = active?.key === key ? active.action : null;
                  return (
                    <Fragment key={key}>
                      <ReviewRow item={item} className={selected.has(key) ? "bg-purple-50/60 dark:bg-purple-950/30" : undefined}>
                        <td className="px-4 py-3 align-top">{rowCheckbox(item)}</td>
                        <td className="px-4 py-3">
                          <div className="font-medium text-ink-900">
                            <ReviewTitle item={item} />
                            {outOfWindowNotes(item)}
                          </div>
                          {resubmittedNote(item)}
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
                        <td className="px-4 py-3 text-ink-700">{formatDateTime(item.submittedAt ?? item.createdAt)}</td>
                        <td className="px-4 py-3 text-right">{decisionButtons(item, activeAction)}</td>
                      </ReviewRow>
                      {activeAction ? (
                        <tr className="bg-surface-50">
                          <td className="px-4 py-3" colSpan={8}>
                            {actionPanel(item, activeAction)}
                          </td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
          </>
        )}
        {!loading && total > 0 ? <Pagination page={page} pages={pages} total={total} onPage={setPage} /> : null}
      </section>
    </>
  );
}
