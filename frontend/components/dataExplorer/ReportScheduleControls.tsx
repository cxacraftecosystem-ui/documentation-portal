"use client";

import { CalendarClock, Plus, Trash2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";

import { Note, Problem, Skeleton } from "@/components/dataExplorer/explorerUi";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { fetchReportOptions } from "@/lib/dataExplorer/api";
import type { SavedView } from "@/lib/dataExplorer/contract";
import {
  browserTimezone,
  canAddSchedule,
  createSchedule,
  DEFAULT_HOUR,
  DEFAULT_WEEKDAY,
  deleteSchedule,
  fetchSchedules,
  HOUR_OPTIONS,
  scheduleLabel,
  scheduleStatus,
  updateSchedule,
  viewOptions,
  WEEKDAY_OPTIONS,
  type ReportSchedule,
  type ReportScheduleList
} from "@/lib/dataExplorer/reportSchedules";

function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

const STATUS_TONE: Record<"info" | "warn" | "done", string> = {
  info: "text-ink-500",
  warn: "text-amber-800 dark:text-amber-500",
  done: "text-ink-500"
};

export function ReportScheduleControls({
  savedViews,
  onChanged
}: {
  savedViews: readonly SavedView[];
  onChanged?: () => void;
}) {
  const [list, setList] = useState<ReportScheduleList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [viewId, setViewId] = useState("");
  const [weekday, setWeekday] = useState(String(DEFAULT_WEEKDAY));
  const [hour, setHour] = useState(String(DEFAULT_HOUR));
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setList(await fetchSchedules());
    } catch (err) {
      setError(messageOf(err, "Scheduled reports didn't load."));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const options = useMemo(() => viewOptions(savedViews), [savedViews]);
  const chosen = options.some((option) => option.value === viewId) ? viewId : options[0]?.value ?? "";
  const timezone = useMemo(() => browserTimezone(), []);

  const changed = async (work: () => Promise<unknown>, key: string, fallback: string) => {
    setBusy(key);
    setError(null);
    try {
      await work();
      await load();
      onChanged?.();
    } catch (err) {
      setError(messageOf(err, fallback));
    } finally {
      setBusy(null);
    }
  };

  const add = () =>
    changed(
      async () => {
        const remembered = await fetchReportOptions().catch(() => null);
        await createSchedule({
          savedViewId: chosen,
          weekday: Number(weekday),
          hourLocal: Number(hour),
          timezone,
          ...(remembered ? { options: remembered } : {})
        });
      },
      "new",
      "The schedule wasn't saved."
    );

  const pause = (schedule: ReportSchedule, sending: boolean) =>
    changed(() => updateSchedule(schedule.id, { paused: !sending }), schedule.id, "The schedule wasn't updated.");

  const remove = (schedule: ReportSchedule) =>
    changed(() => deleteSchedule(schedule.id), schedule.id, "The schedule wasn't removed.");

  if (list && (!list.enabled || !list.mailAvailable)) return null;

  return (
    <section className="grid gap-3 rounded-md border border-line-200 p-3" aria-labelledby="report-schedules-title">
      <div className="flex items-center gap-2">
        <CalendarClock className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        <h3 id="report-schedules-title" className="text-sm font-semibold text-ink-900">
          Email me a report
        </h3>
      </div>
      {error ? <Problem onRetry={() => void load()}>{error}</Problem> : null}
      {!list ? (
        <Skeleton className="h-12" />
      ) : (
        <>
          {options.length === 0 ? (
            <p className="text-xs text-ink-500">Save a view first.</p>
          ) : canAddSchedule(list) ? (
            <div className="grid gap-2">
              <div className="grid gap-2 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)]">
                <SearchableSelect ariaLabel="Saved view" value={chosen} onChange={setViewId} options={options} searchable advanceOnSelect={false} />
                <SearchableSelect ariaLabel="Day" value={weekday} onChange={setWeekday} options={WEEKDAY_OPTIONS} advanceOnSelect={false} />
                <SearchableSelect ariaLabel="Time" value={hour} onChange={setHour} options={HOUR_OPTIONS} advanceOnSelect={false} />
              </div>
              <p className="text-xs text-ink-500">{scheduleLabel(Number(weekday), Number(hour), timezone)}, as Excel, with your report choices.</p>
              <ActionGroup align="start">
                <button type="button" className="field-button" disabled={!chosen || busy !== null} onClick={() => void add()}>
                  <Plus className="h-4 w-4" aria-hidden />
                  {busy === "new" ? "Scheduling…" : "Schedule"}
                </button>
              </ActionGroup>
            </div>
          ) : (
            <Note>You can keep {list.maxPerOwner} scheduled reports. Delete one to add another.</Note>
          )}
          {list.items.length === 0 ? null : (
            <ul className="grid gap-2" aria-label="Scheduled reports">
              {list.items.map((schedule) => {
                const status = scheduleStatus(schedule);
                return (
                  <li key={schedule.id} className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold text-ink-900">{schedule.viewName ?? "Saved view"}</p>
                      <p className="text-xs text-ink-700">{scheduleLabel(schedule.weekday, schedule.hourLocal, schedule.timezone)}</p>
                      <p className={`text-xs ${STATUS_TONE[status.tone]}`} role={status.tone === "warn" ? "status" : undefined}>
                        {status.text}
                      </p>
                    </div>
                    <Toggle
                      label="Sending"
                      checked={!schedule.paused}
                      busy={busy === schedule.id}
                      onChange={(next) => void pause(schedule, next)}
                    />
                    <ActionGroup align="end" inline>
                      <button
                        type="button"
                        className="field-danger inline-flex min-h-9 items-center gap-1.5 rounded-md px-3 text-xs pointer-coarse:min-h-11"
                        data-danger=""
                        disabled={busy !== null}
                        onClick={() => void remove(schedule)}
                      >
                        <Trash2 className="h-3.5 w-3.5" aria-hidden />
                        Delete
                      </button>
                    </ActionGroup>
                  </li>
                );
              })}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
