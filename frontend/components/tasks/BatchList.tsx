"use client";

import { useState } from "react";
import { Trash2, Users } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Markdown } from "@/components/Markdown";
import { Pagination } from "@/components/Pagination";
import { rowAction } from "@/components/RowActions";
import { DueBadge, GapChip, PercentBar, PersonLine, ScopeChips, TaskStatusPill } from "@/components/tasks/TaskPrimitives";
import type { TaskBatch } from "@/components/tasks/types";
import { apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { useConfirm } from "@/components/dialogs";

/**
 * Assignments grouped back into the action that created them.
 *
 * An admin thinks in the thing they did — "I gave the tool survey to five people" — not in the five
 * rows it became, so withdrawing it has to be one action too. Rows written before batching existed
 * (and single-assignee creates) come back with `batchId: null`; those are deleted one task at a time
 * through the task endpoint, which is why the delete below branches.
 */
export function BatchList({
  batches,
  loading,
  error,
  page,
  pages,
  total,
  onPage,
  onChanged
}: {
  batches: TaskBatch[];
  loading: boolean;
  error: string | null;
  page: number;
  pages: number;
  total: number;
  onPage: (page: number) => void;
  onChanged: () => void;
}) {
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const confirm = useConfirm();
  async function remove(batch: TaskBatch) {
    const people = `${batch.assigneeCount} ${batch.assigneeCount === 1 ? "person" : "people"}`;
    const ok = await confirm({
      title: batch.batchId ? "Withdraw this assignment?" : "Delete this task?",
      body: batch.batchId
        ? `"${batch.title}" will be withdrawn from all ${people}.`
        : `"${batch.title}" will be deleted.`,
      note: "Any progress already reported against it is lost.",
      confirmLabel: batch.batchId ? "Withdraw" : "Delete task",
      tone: "danger"
    });
    if (!ok) return;
    setBusyKey(batch.key);
    setDeleteError(null);
    try {
      if (batch.batchId) await apiFetch(`/tasks/batch/${batch.batchId}`, { method: "DELETE" });
      else if (batch.assignees[0]) await apiFetch(`/tasks/${batch.assignees[0].taskId}`, { method: "DELETE" });
      onChanged();
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : "Unable to withdraw this assignment");
    } finally {
      setBusyKey(null);
    }
  }

  if (error) return <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>;
  if (loading && !batches.length) return <div className="panel p-4 text-sm text-ink-500">Loading assignments...</div>;
  if (!batches.length) {
    return (
      <EmptyState
        title="No assignments here yet"
        body="Everything handed out from the assignment builder shows up here as one manageable unit, with the whole group's progress and a single withdraw action."
      />
    );
  }

  return (
    <div className="grid gap-3">
      {deleteError ? (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{deleteError}</div>
      ) : null}

      {batches.map((batch) => (
        <article key={batch.key} className="panel grid gap-3 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <h3 className="font-display text-base font-bold text-ink-900">{batch.title}</h3>
              <p className="mt-0.5 text-xs text-ink-500">
                Sent by {batch.createdBy?.name ?? "an administrator"} on {formatDate(batch.createdAt)}
                {batch.batchId ? "" : " · single task"}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-line-200 bg-surface-50 px-2.5 py-1 text-xs font-medium text-ink-700">
                <Users className="h-3.5 w-3.5" aria-hidden />
                {batch.assigneeCount}
              </span>
              <button
                type="button"
                className={rowAction("danger")}
                disabled={busyKey === batch.key}
                onClick={() => remove(batch)}
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden />
                {batch.batchId ? "Withdraw" : "Delete"}
              </button>
            </div>
          </div>

          {batch.description ? <Markdown text={batch.description} className="text-sm text-ink-700" /> : null}

          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <ScopeChips
              recordTypeLabels={batch.recordTypeLabels}
              sections={batch.sections}
              artisans={batch.artisans}
              targetCount={batch.targetCount}
              workshopTitle={batch.workshopTitle}
            />
            <DueBadge dueAt={batch.dueAt} overdue={batch.overdueCount > 0} />
          </div>

          <div className="grid gap-3 border-t border-line-200 pt-3 md:grid-cols-[minmax(0,18rem)_minmax(0,1fr)] md:items-start">
            <div className="grid gap-2">
              <PercentBar percent={batch.percentComplete} label="Group progress" />
              <p className="text-xs text-ink-500">
                {batch.doneCount} of {batch.assigneeCount} finished · {batch.openCount} outstanding
                {batch.overdueCount ? ` · ${batch.overdueCount} overdue` : ""}
              </p>
              <GapChip reported={batch.reportedTotal} derived={batch.derivedTotal} />
            </div>
            <ul className="grid gap-2">
              {batch.assignees.map((assignee) => (
                <li
                  key={assignee.taskId}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
                >
                  <PersonLine user={assignee.user} className="text-sm" />
                  <div className="flex items-center gap-3">
                    <span className="text-xs tabular-nums text-ink-500">
                      reported {assignee.progressCount}
                      {batch.targetCount ? ` / ${batch.targetCount}` : ""} · in repository {assignee.derivedCount ?? "—"}
                    </span>
                    <TaskStatusPill status={assignee.status} />
                  </div>
                </li>
              ))}
            </ul>
          </div>
        </article>
      ))}

      {pages > 1 ? (
        <div className="panel">
          <Pagination page={page} pages={pages} total={total} onPage={onPage} />
        </div>
      ) : null}
    </div>
  );
}
