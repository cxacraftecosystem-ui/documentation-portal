"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { ClipboardCheck, Lock, RefreshCw } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { AccountabilityBoard } from "@/components/tasks/AccountabilityBoard";
import { AssignmentBuilder } from "@/components/tasks/AssignmentBuilder";
import { BatchList } from "@/components/tasks/BatchList";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import type { TaskBatch, TaskBatchResult, TaskOptions, TaskProgressReport } from "@/components/tasks/types";
import { apiFetch, buildQuery } from "@/lib/api";
import { isAdmin } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";
import { cn } from "@/lib/utils";

type TabKey = "assign" | "progress" | "batches";

const TABS: { key: TabKey; label: string }[] = [
  { key: "assign", label: "Assign work" },
  { key: "progress", label: "Accountability" },
  { key: "batches", label: "Assignments" }
];

export default function TaskAssignmentBoardPage() {
  const { user, loading: authLoading } = useAuth();
  const { toast } = useToast();
  const permitted = isAdmin(user);

  const [tab, setTab] = useState<TabKey>("assign");
  const [workshopId, setWorkshopId] = useState("");

  const [options, setOptions] = useState<TaskOptions | null>(null);
  const [optionsLoading, setOptionsLoading] = useState(true);
  const [optionsError, setOptionsError] = useState<string | null>(null);

  const [report, setReport] = useState<TaskProgressReport | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [reportError, setReportError] = useState<string | null>(null);

  const [batches, setBatches] = useState<PageResult<TaskBatch> | null>(null);
  const [batchesLoading, setBatchesLoading] = useState(false);
  const [batchesError, setBatchesError] = useState<string | null>(null);
  const [batchPage, setBatchPage] = useState(1);

  const loadOptions = useCallback(async () => {
    if (!permitted) return;
    setOptionsLoading(true);
    try {
      const data = await apiFetch<TaskOptions>(`/tasks/options${buildQuery({ workshopId })}`);
      setOptions(data);
      setOptionsError(null);
    } catch (err) {
      setOptionsError(err instanceof Error ? err.message : "Unable to load the assignment pickers");
    } finally {
      setOptionsLoading(false);
    }
  }, [permitted, workshopId]);

  const loadReport = useCallback(async () => {
    if (!permitted) return;
    setReportLoading(true);
    try {
      const data = await apiFetch<TaskProgressReport>(`/tasks/progress${buildQuery({ workshopId })}`);
      setReport(data);
      setReportError(null);
    } catch (err) {
      setReportError(err instanceof Error ? err.message : "Unable to load the accountability rollup");
    } finally {
      setReportLoading(false);
    }
  }, [permitted, workshopId]);

  const loadBatches = useCallback(async () => {
    if (!permitted) return;
    setBatchesLoading(true);
    try {
      const data = await apiFetch<PageResult<TaskBatch>>(
        `/tasks/batches${buildQuery({ workshopId, page: batchPage, pageSize: 10 })}`
      );
      setBatches(data);
      setBatchesError(null);
    } catch (err) {
      setBatchesError(err instanceof Error ? err.message : "Unable to load the assignments");
    } finally {
      setBatchesLoading(false);
    }
  }, [permitted, workshopId, batchPage]);

  useEffect(() => {
    loadOptions();
  }, [loadOptions]);
  useEffect(() => {
    loadReport();
  }, [loadReport]);
  useEffect(() => {
    loadBatches();
  }, [loadBatches]);

  const chooseWorkshop = useCallback(
    (next: string) => {
      if (next !== workshopId) setBatchPage(1);
      setWorkshopId(next);
    },
    [workshopId]
  );

  const workshopOptions = useMemo(
    () => [
      { value: "", label: "All workshops" },
      ...(options?.workshops ?? []).map((workshop) => ({
        value: workshop.id,
        label: workshop.place ? `${workshop.title} · ${workshop.place}` : workshop.title
      }))
    ],
    [options]
  );

  const workshopTitle = useMemo(
    () => options?.workshops.find((workshop) => workshop.id === workshopId)?.title ?? null,
    [options, workshopId]
  );

  function refreshAll() {
    loadReport();
    loadBatches();
  }

  function onAssigned(result: TaskBatchResult) {
    toast({
      title: `Assigned to ${result.created} ${result.created === 1 ? "person" : "people"}`,
      description: result.title,
      tone: "success"
    });
    setBatchPage(1);
    refreshAll();
    setTab("batches");
  }

  const header = (
    <PageHeader
      title="Task assignment"
      description="Assign, track and approve work."
      icon={<ClipboardCheck className="h-5 w-5" aria-hidden />}
      actions={
        <Link href="/tasks" className="field-button-secondary">
          My tasks
        </Link>
      }
    />
  );

  if (authLoading) {
    return (
      <>
        {header}
        <div className="panel p-4 text-sm text-ink-500">Loading...</div>
      </>
    );
  }

  if (!permitted) {
    return (
      <>
        {header}
        <section className="panel px-6 py-12 text-center">
          <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="text-base font-medium text-ink-900">Assigning work is restricted</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">
            Admins only. Your own tasks are on the Tasks page.
          </p>
          <Link href="/tasks" className="field-button mt-5 inline-flex">
            Go to my tasks
          </Link>
        </section>
      </>
    );
  }

  return (
    <>
      {header}

      {optionsError ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{optionsError}</div>
      ) : null}

      <section className="panel mb-4 grid gap-3 p-4 md:grid-cols-[minmax(0,24rem)_minmax(0,1fr)] md:items-end">
        <div className="grid gap-1">
          <div className="flex items-center gap-3">
            <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-purple-700 text-xs font-semibold text-white">
              1
            </span>
            <h2 className="font-display text-base font-bold text-ink-900">Workshop</h2>
          </div>
          <div className="pl-9">
            <FieldBlock label="Scope everything below to">
              <Dropdown
                value={workshopId}
                onChange={chooseWorkshop}
                options={workshopOptions}
                advanceOnSelect={false}
                ariaLabel="Workshop scope"
                placeholder={optionsLoading ? "Loading workshops..." : "All workshops"}
              />
            </FieldBlock>
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 pl-9 md:pl-0">
          <p className="max-w-md text-xs leading-5 text-ink-500">
            {workshopTitle
              ? `Limited to ${workshopTitle}.`
              : "Pick a workshop to narrow everything below."}
          </p>
          <button type="button" className="field-button-secondary" onClick={refreshAll} disabled={reportLoading || batchesLoading}>
            <RefreshCw className={cn("h-4 w-4", (reportLoading || batchesLoading) && "animate-spin")} aria-hidden />
            Refresh
          </button>
        </div>
      </section>

      <div role="tablist" aria-label="Assignment board views" className="mb-4 flex flex-wrap gap-2">
        {TABS.map((entry) => {
          const active = tab === entry.key;
          const count =
            entry.key === "progress" ? report?.assigneeCount : entry.key === "batches" ? batches?.total : undefined;
          return (
            <button
              key={entry.key}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setTab(entry.key)}
              className={cn(
                "rounded-md border px-4 py-2 text-sm font-medium transition",
                active
                  ? "border-purple-700 bg-purple-700 text-white shadow-cta"
                  : "border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
              )}
            >
              {entry.label}
              {count !== undefined ? <span className={cn("ml-2 text-xs", active ? "text-white/80" : "text-ink-500")}>{count}</span> : null}
              {entry.key === "progress" && (report?.awaitingReviewCount ?? 0) > 0 ? (
                <span
                  className={cn(
                    "ml-2 rounded-full px-2 py-0.5 text-xs font-semibold",
                    active ? "bg-white/20 text-white" : "border border-purple-200 bg-purple-50 text-purple-700"
                  )}
                >
                  {report?.awaitingReviewCount} to approve
                </span>
              ) : null}
            </button>
          );
        })}
      </div>

      {tab === "assign" ? (
        <AssignmentBuilder
          options={options}
          loading={optionsLoading}
          workshopId={workshopId}
          workshopTitle={workshopTitle}
          onAssigned={onAssigned}
        />
      ) : null}

      {tab === "progress" ? (
        <AccountabilityBoard
          report={report}
          loading={reportLoading}
          error={reportError}
          canOverride={permitted}
          onOverridden={refreshAll}
        />
      ) : null}

      {tab === "batches" ? (
        <BatchList
          batches={batches?.items ?? []}
          loading={batchesLoading}
          error={batchesError}
          page={batches?.page ?? 1}
          pages={batches?.pages ?? 0}
          total={batches?.total ?? 0}
          onPage={setBatchPage}
          onChanged={refreshAll}
        />
      ) : null}
    </>
  );
}
