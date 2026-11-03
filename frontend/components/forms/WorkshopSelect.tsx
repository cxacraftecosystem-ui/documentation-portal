"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { Field } from "@/components/FormControls";
import { LateSubmissionDialog } from "@/components/LateSubmissionDialog";
import { ComboBox } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";
import { loadWorkshopList } from "@/lib/workshopList";
import { hasTypedTitle, workshopDisplayTitle } from "@/lib/workshopTitle";

const DEFAULT_PROBE_LIMIT = 5;

const NO_WORKSHOP_LABEL = "Not linked to a workshop";

export function workshopOccurrenceDate(workshop: Workshop): string {
  return workshop.startDate ?? workshop.date ?? workshop.createdAt ?? "";
}

export function sortWorkshopsByOccurrence(workshops: Workshop[]): Workshop[] {
  return [...workshops].sort((a, b) => workshopOccurrenceDate(b).localeCompare(workshopOccurrenceDate(a)));
}

export function mostRecentRecordableWorkshop(workshops: Workshop[], now: number = Date.now()): Workshop | null {
  const today = istDayKey(now);
  const candidates = workshops.filter((w) => {
    if (w.canRecord === false) return false;
    const start = workshopDayKey(w.startDate ?? w.date);
    return !start || start <= today;
  });
  candidates.sort((a, b) => {
    const day = (workshopDayKey(workshopOccurrenceDate(b)) ?? "").localeCompare(workshopDayKey(workshopOccurrenceDate(a)) ?? "");
    return day !== 0 ? day : (b.createdAt ?? "").localeCompare(a.createdAt ?? "");
  });
  return candidates[0] ?? null;
}

function workshopOptionLabel(workshop: Workshop): string {
  if (!hasTypedTitle(workshop)) return workshopDisplayTitle(workshop);
  const title = workshopDisplayTitle(workshop);
  const when = formatWorkshopDay(workshopOccurrenceDate(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

const IST_OFFSET_MINUTES = 330;

export type WorkshopWindowState = "NOT_STARTED" | "IN_WINDOW" | "ENDED";

export function workshopDayKey(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const stamped = /^(\d{4}-\d{2}-\d{2})/.exec(raw.trim());
  if (stamped) return stamped[1];
  const parsed = new Date(raw);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString().slice(0, 10);
}

export function istDayKey(now: number = Date.now()): string {
  return new Date(now + IST_OFFSET_MINUTES * 60_000).toISOString().slice(0, 10);
}

export function formatWorkshopDay(raw: string | null | undefined): string {
  const key = workshopDayKey(raw);
  if (!key) return "-";
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    timeZone: "UTC"
  }).format(new Date(`${key}T00:00:00Z`));
}

export function workshopWindowState(
  check: Pick<WorkshopSubmissionCheck, "outOfWindow" | "isOver"> | null,
  workshop: Workshop | undefined,
  now: number = Date.now()
): WorkshopWindowState {
  if (check) {
    if (check.isOver) return "ENDED";
    return check.outOfWindow ? "NOT_STARTED" : "IN_WINDOW";
  }
  if (!workshop) return "IN_WINDOW";
  const today = istDayKey(now);
  const endKey = workshopDayKey(workshop.endDate ?? workshop.date ?? workshop.startDate);
  if (endKey && today > endKey) return "ENDED";
  const startKey = workshopDayKey(workshop.startDate ?? workshop.date);
  if (startKey && today < startKey) return "NOT_STARTED";
  return "IN_WINDOW";
}

export function workshopRunsToday(workshop: Workshop, now: number = Date.now()): boolean {
  if (!workshopDayKey(workshop.startDate ?? workshop.date)) return false;
  return workshopWindowState(null, workshop, now) === "IN_WINDOW";
}

export function workshopWindowNotice({
  state,
  startLabel,
  endLabel,
  needsAdminApproval
}: {
  state: WorkshopWindowState;
  startLabel: string;
  endLabel: string;
  needsAdminApproval: boolean;
}): string | null {
  if (state === "IN_WINDOW") return null;
  if (state === "NOT_STARTED") {
    const when = startLabel === "-" ? "This workshop has not started yet." : `This workshop starts on ${startLabel}.`;
    return `${when} ${
      needsAdminApproval
        ? "Saving now counts as an early submission and needs an admin's approval."
        : "Saving now is recorded as an early submission."
    }`;
  }
  const when = endLabel === "-" ? "This workshop has already ended." : `This workshop ended on ${endLabel}.`;
  return `${when} ${
    needsAdminApproval
      ? "Saving now counts as a late submission and needs an admin's approval."
      : "Saving now is recorded as a late submission."
  }`;
}

export type WorkshopSelection = {
  workshopId: string;
  touched: boolean;
  workshops: Workshop[];
  loading: boolean;
  check: WorkshopSubmissionCheck | null;
  setWorkshopId: (workshopId: string) => void;
  confirmSubmission: () => Promise<boolean>;
  dialog: { open: boolean; check: WorkshopSubmissionCheck | null; confirm: () => void; cancel: () => void };
};

export function useWorkshopSelection({
  initialWorkshopId,
  isEdit = false,
  resetKey = null,
  defaultRule = "running"
}: {
  initialWorkshopId?: string | null;
  isEdit?: boolean;
  resetKey?: string | null;
  defaultRule?: "running" | "mostRecent";
} = {}): WorkshopSelection {
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const readerId = useAuth().user?.id ?? null;
  const [loading, setLoading] = useState(true);
  const [workshopId, setWorkshopIdState] = useState(initialWorkshopId ?? "");
  const [touched, setTouched] = useState(Boolean(initialWorkshopId));
  const [check, setCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogCheck, setDialogCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const checkCache = useRef(new Map<string, WorkshopSubmissionCheck>());
  const touchedRef = useRef(Boolean(initialWorkshopId));
  const resolveRef = useRef<((confirmed: boolean) => void) | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadWorkshopList(readerId)
      .then((result) => {
        if (!cancelled) setWorkshops(sortWorkshopsByOccurrence(result.items));
      })
      .catch(() => {
        if (!cancelled) setWorkshops([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [readerId]);

  useEffect(() => {
    touchedRef.current = Boolean(initialWorkshopId);
    setTouched(Boolean(initialWorkshopId));
    setWorkshopIdState(initialWorkshopId ?? "");
  }, [resetKey, initialWorkshopId]);

  const fetchCheck = useCallback(async (id: string): Promise<WorkshopSubmissionCheck | null> => {
    if (!id) return null;
    const cached = checkCache.current.get(id);
    if (cached) return cached;
    try {
      const result = await apiFetch<WorkshopSubmissionCheck>(`/workshops/${id}/submission-check`);
      checkCache.current.set(id, result);
      return result;
    } catch {
      return null;
    }
  }, []);

  useEffect(() => {
    if (isEdit || touchedRef.current || !workshops.length) return;
    if (defaultRule === "mostRecent") {
      const pick = mostRecentRecordableWorkshop(workshops);
      if (pick) setWorkshopIdState(pick.id);
      return;
    }
    let cancelled = false;
    (async () => {
      for (const workshop of workshops.slice(0, DEFAULT_PROBE_LIMIT)) {
        const result = await fetchCheck(workshop.id);
        if (cancelled || touchedRef.current) return;
        const running = result
          ? result.canSubmit && !result.isOver && !result.outOfWindow
          : workshopRunsToday(workshop);
        if (running) {
          setWorkshopIdState(workshop.id);
          return;
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workshops, isEdit, fetchCheck, defaultRule]);

  useEffect(() => {
    let cancelled = false;
    setCheck(null);
    if (!workshopId) return;
    fetchCheck(workshopId).then((result) => {
      if (!cancelled) setCheck(result);
    });
    return () => {
      cancelled = true;
    };
  }, [workshopId, fetchCheck]);

  const setWorkshopId = useCallback((next: string) => {
    touchedRef.current = true;
    setTouched(true);
    setWorkshopIdState(next);
  }, []);

  const settleDialog = useCallback((confirmed: boolean) => {
    setDialogOpen(false);
    const resolve = resolveRef.current;
    resolveRef.current = null;
    resolve?.(confirmed);
  }, []);

  const confirmSubmission = useCallback(async (): Promise<boolean> => {
    if (!workshopId) return true;
    const result = await fetchCheck(workshopId);
    if (!result || (!result.outOfWindow && !result.isOver)) return true;
    setDialogCheck(result);
    setDialogOpen(true);
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve;
    });
  }, [workshopId, fetchCheck]);

  useEffect(() => {
    return () => resolveRef.current?.(false);
  }, []);

  return {
    workshopId,
    touched,
    workshops,
    loading,
    check,
    setWorkshopId,
    confirmSubmission,
    dialog: {
      open: dialogOpen,
      check: dialogCheck,
      confirm: () => settleDialog(true),
      cancel: () => settleDialog(false)
    }
  };
}

export function WorkshopSelect({
  state,
  label = "Workshop",
  onDirty,
  saving
}: {
  state: WorkshopSelection;
  label?: string;
  onDirty?: () => void;
  saving?: boolean;
}) {
  const { workshopId, workshops, loading, check, setWorkshopId, dialog } = state;

  const options = useMemo(
    () => [
      { value: "", label: NO_WORKSHOP_LABEL },
      ...workshops.map((workshop) => ({ value: workshop.id, label: workshopOptionLabel(workshop) }))
    ],
    [workshops]
  );

  const selected = workshops.find((workshop) => workshop.id === workshopId);
  const blocked = Boolean(check && !check.canSubmit);
  const windowState = workshopId ? workshopWindowState(check, selected) : "IN_WINDOW";
  const notice = workshopWindowNotice({
    state: windowState,
    startLabel: formatWorkshopDay(selected?.startDate ?? selected?.date ?? null),
    endLabel: formatWorkshopDay(check?.endDate ?? selected?.endDate ?? selected?.date ?? null),
    needsAdminApproval: check ? check.needsAdminApproval : true
  });

  return (
    <div className="grid min-w-0 content-start gap-1" onInput={(event) => event.stopPropagation()}>
      <Field label={label}>
        <ComboBox
          options={options}
          value={workshopId}
          onChange={(next) => {
            setWorkshopId(next);
            onDirty?.();
          }}
          placeholder={loading ? "Loading workshops…" : "Choose the workshop this record belongs to"}
        />
      </Field>
      {blocked ? (
        <p className="text-xs font-medium text-error-600">
          You’re not assigned to this workshop. Ask an admin, or pick another.
        </p>
      ) : null}
      {notice && !blocked ? <p className="text-xs font-medium text-amber-800 dark:text-amber-200">{notice}</p> : null}
      <LateSubmissionDialog
        open={dialog.open}
        workshopTitle={dialog.check?.title ?? selected?.title}
        endDate={workshopDayKey(dialog.check?.endDate ?? selected?.endDate ?? selected?.date)}
        needsAdminApproval={Boolean(dialog.check?.needsAdminApproval)}
        saving={saving}
        onConfirm={dialog.confirm}
        onCancel={dialog.cancel}
      />
    </div>
  );
}
