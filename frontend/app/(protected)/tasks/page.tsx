"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ClipboardCheck, SlidersHorizontal } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { useAuth } from "@/components/AuthProvider";
import { MyTaskCard } from "@/components/tasks/MyTaskCard";
import { STATUS_LABEL } from "@/components/tasks/scope";
import type { FieldTask, TaskStatus } from "@/components/tasks/types";
import { apiFetch, buildQuery } from "@/lib/api";
import { canAssignTasks } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";
import { cn } from "@/lib/utils";

/**
 * My tasks — the assignee's half of the assignment system.
 *
 * The scope arrives resolved (workshop title, artisan names, section codes), so this page never has
 * to look anything up: it renders what the work IS, how much of it the person has claimed, and what
 * the repository can independently see. Assigning work lives on the admin board at /settings/tasks —
 * one scope handed to several people at once — and the strip below points admins at it, because the
 * single-assignee form that used to sit here could not express artisan subsets or questionnaire
 * sections at all.
 */

const PAGE_SIZE = 12;
const STATUSES: TaskStatus[] = ["OPEN", "IN_PROGRESS", "DONE", "CANCELLED"];

export default function TasksPage() {
  const { user } = useAuth();
  const assigner = canAssignTasks(user);

  const [statusFilter, setStatusFilter] = useState<TaskStatus | "">("");
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<PageResult<FieldTask> | null>(null);
  const [counts, setCounts] = useState<Partial<Record<TaskStatus, number>>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiFetch<PageResult<FieldTask>>(
        `/tasks${buildQuery({ view: "assigned", status: statusFilter || undefined, page, pageSize: PAGE_SIZE })}`
      );
      setResult(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load your tasks");
    } finally {
      setLoading(false);
    }
  }, [statusFilter, page]);

  /**
   * Exact per-status totals for the filter chips. Four tiny calls (`pageSize=1`, derivation off) are
   * cheaper and more honest than counting the current page, which only ever sees twelve rows.
   */
  const loadCounts = useCallback(async () => {
    try {
      const entries = await Promise.all(
        STATUSES.map(async (status) => {
          const data = await apiFetch<PageResult<FieldTask>>(
            `/tasks${buildQuery({ view: "assigned", status, pageSize: 1, withDerived: "false" })}`
          );
          return [status, data.total] as const;
        })
      );
      setCounts(Object.fromEntries(entries));
    } catch {
      // The chips fall back to unlabelled filters; the list itself is what matters.
      setCounts({});
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);
  useEffect(() => {
    loadCounts();
  }, [loadCounts]);

  async function patch(task: FieldTask, body: Record<string, unknown>) {
    setBusyId(task.id);
    try {
      await apiFetch(`/tasks/${task.id}`, { method: "PATCH", body: JSON.stringify(body) });
      setError(null);
      await Promise.all([load(), loadCounts()]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update the task");
    } finally {
      setBusyId(null);
    }
  }

  const total = counts.OPEN !== undefined ? STATUSES.reduce((sum, status) => sum + (counts[status] ?? 0), 0) : undefined;
  const items = result?.items ?? [];

  return (
    <>
      <PageHeader
        title="Tasks"
        description="The documentation work assigned to you — what it covers, how far along you are, and what the repository has already recorded against it."
        icon={<ClipboardCheck className="h-5 w-5" aria-hidden />}
        actions={
          assigner ? (
            <Link href="/settings/tasks" className="field-button">
              <SlidersHorizontal className="h-4 w-4" aria-hidden />
              Assignment board
            </Link>
          ) : undefined
        }
      />

      {assigner ? (
        <p className="mb-4 rounded-md border border-purple-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-700">
          Handing work out happens on the{" "}
          <Link href="/settings/tasks" className="font-semibold text-purple-700 underline">
            assignment board
          </Link>
          : one scope — record types, an artisan subset, questionnaire sections, a target count — given to several
          people at once, with the accountability rollup beside it.
        </p>
      ) : null}

      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div className="mb-4 flex flex-wrap gap-2">
        {[{ key: "" as const, label: "All" }, ...STATUSES.map((status) => ({ key: status, label: STATUS_LABEL[status] }))].map(
          (entry) => {
            const active = statusFilter === entry.key;
            const count = entry.key === "" ? total : counts[entry.key];
            return (
              <button
                key={entry.key || "all"}
                type="button"
                aria-pressed={active}
                onClick={() => {
                  setStatusFilter(entry.key);
                  setPage(1);
                }}
                className={cn(
                  "rounded-full border px-3.5 py-1.5 text-sm font-medium transition",
                  active
                    ? "border-purple-700 bg-purple-700 text-white"
                    : "border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                )}
              >
                {entry.label}
                {count !== undefined ? (
                  <span className={cn("ml-2 text-xs tabular-nums", active ? "text-white/80" : "text-ink-500")}>{count}</span>
                ) : null}
              </button>
            );
          }
        )}
      </div>

      {loading && !result ? (
        <div className="panel p-4 text-sm text-ink-500">Loading your tasks...</div>
      ) : items.length === 0 ? (
        <EmptyState
          title={statusFilter ? `No ${STATUS_LABEL[statusFilter].toLowerCase()} tasks` : "No tasks assigned to you"}
          body={
            statusFilter
              ? "Nothing in this state right now. Try another filter."
              : "When an admin assigns you documentation work it appears here with everything it covers — the workshop, the artisans, the questionnaire sections — plus its due date and your progress."
          }
        />
      ) : (
        <>
          <div className="grid gap-3 md:grid-cols-2">
            {items.map((task) => (
              <MyTaskCard
                key={task.id}
                task={task}
                editable={task.assigneeId === user?.id && task.status !== "CANCELLED"}
                busy={busyId === task.id}
                onStatus={(status) => patch(task, { status })}
                onReport={(progressCount) => patch(task, { progressCount })}
              />
            ))}
          </div>
          {result && result.pages > 1 ? (
            <div className="panel mt-4">
              <Pagination page={result.page} pages={result.pages} total={result.total} onPage={setPage} />
            </div>
          ) : null}
        </>
      )}
    </>
  );
}
