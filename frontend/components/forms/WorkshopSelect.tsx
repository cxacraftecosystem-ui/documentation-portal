"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { Field } from "@/components/FormControls";
import { LateSubmissionDialog } from "@/components/LateSubmissionDialog";
import { ComboBox } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";

/**
 * The ONE workshop picker every record form mounts, above its craft / artisan / product dropdowns.
 *
 * Every record type carries a workshop now (artisan, craft, product, tool, process, questionnaire),
 * and the workshop decides two things the researcher must learn BEFORE saving, not after:
 *
 * 1. **Assignment.** Once a workshop has assignments only those researchers (and admins) may file
 *    records against it — the API answers 403. This warns at select time instead.
 * 2. **The window.** A record filed after the workshop ended is accepted but pinned to PENDING and
 *    flagged `needsAdminApproval`, so only an admin can approve it. `confirmSubmission()` surfaces
 *    that as <LateSubmissionDialog> and the save proceeds only on confirm.
 *
 * Both facts come from `GET /workshops/{id}/submission-check`, which never 403s — it only reports.
 * When that endpoint is missing or fails we degrade to a purely local "this workshop looks like it
 * ended" hint and NEVER block the save: a researcher in the field must not lose work to a flaky
 * pre-flight request.
 *
 * The selected id lives in React state and is read from `state.workshopId` at submit time — there is
 * deliberately no `name`/FormData mirror, so there is exactly one source of truth for it.
 */

/** How far down the list the auto-default walks looking for a workshop the user may submit to. */
const DEFAULT_PROBE_LIMIT = 5;

const NO_WORKSHOP_LABEL = "Not linked to a workshop";

/**
 * The date a workshop HAPPENED — Android parity with `WorkshopDetailDto.occurrenceDate()`
 * (`startDate ?: date ?: createdAt`). Sorting on `createdAt` is wrong: a workshop entered into the
 * system last is not the workshop that ran last.
 */
export function workshopOccurrenceDate(workshop: Workshop): string {
  return workshop.startDate ?? workshop.date ?? workshop.createdAt ?? "";
}

/** Most recent by occurrence first. ISO-8601 strings compare chronologically, as on Android. */
export function sortWorkshopsByOccurrence(workshops: Workshop[]): Workshop[] {
  return [...workshops].sort((a, b) => workshopOccurrenceDate(b).localeCompare(workshopOccurrenceDate(a)));
}

/**
 * Clean human label: title plus the day it ran. Never an id.
 *
 * "·" (middle dot) is the separator Android uses in `workshopOptionLabel` and in every other record
 * label, so the same workshop reads identically on both clients.
 */
function workshopOptionLabel(workshop: Workshop): string {
  const title = workshop.title?.trim() || "Untitled workshop";
  const when = formatDate(workshopOccurrenceDate(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

/**
 * Local fallback for "has this workshop ended?", used only when the pre-flight endpoint is
 * unavailable. Mirrors the backend rule: the whole of the end day is still in-window.
 */
function endedLocally(workshop: Workshop | undefined): boolean {
  if (!workshop) return false;
  const raw = workshop.endDate ?? workshop.date ?? workshop.startDate;
  if (!raw) return false;
  const end = new Date(raw);
  if (Number.isNaN(end.getTime())) return false;
  return Date.now() >= end.getTime() + 24 * 60 * 60 * 1000;
}

export type WorkshopSelection = {
  /** The chosen workshop id ("" = not linked). Put this in the payload as `workshopId || null`. */
  workshopId: string;
  /** True once the USER picked a workshop (or the form opened on a record that already had one). */
  touched: boolean;
  workshops: Workshop[];
  loading: boolean;
  /** Pre-flight answer for the current selection; null while loading or when unavailable. */
  check: WorkshopSubmissionCheck | null;
  setWorkshopId: (workshopId: string) => void;
  /**
   * Call FIRST in the form's submit handler (after capturing FormData, before `setSaving(true)`).
   * Resolves true when the save may go ahead, false when the user backed out of a late submission.
   */
  confirmSubmission: () => Promise<boolean>;
  /** Internal wiring for <WorkshopSelect>; not meant for call sites. */
  dialog: { open: boolean; check: WorkshopSubmissionCheck | null; confirm: () => void; cancel: () => void };
};

/**
 * Owns the workshop selection for one form: loads the list, preselects the most recent workshop the
 * user may submit to (on CREATE only — an existing link is never clobbered), keeps the pre-flight
 * check in sync, and gates submission behind the late-submission confirmation.
 *
 * `resetKey` re-seeds the field when a single mounted form switches records (the crafts page edits
 * every craft through one inline form); pass the record's id.
 */
export function useWorkshopSelection({
  initialWorkshopId,
  isEdit = false,
  resetKey = null
}: {
  initialWorkshopId?: string | null;
  isEdit?: boolean;
  resetKey?: string | null;
} = {}): WorkshopSelection {
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [loading, setLoading] = useState(true);
  const [workshopId, setWorkshopIdState] = useState(initialWorkshopId ?? "");
  const [touched, setTouched] = useState(Boolean(initialWorkshopId));
  const [check, setCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogCheck, setDialogCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  // Successful pre-flight answers only. Failures are not cached, so a blip retries on the next
  // selection or on submit instead of disabling the gate for the rest of the session.
  const checkCache = useRef(new Map<string, WorkshopSubmissionCheck>());
  // Read by the async default probe, which must not fight a user who picked while it was running.
  const touchedRef = useRef(Boolean(initialWorkshopId));
  const resolveRef = useRef<((confirmed: boolean) => void) | null>(null);

  useEffect(() => {
    let cancelled = false;
    listResource<Workshop>("/workshops", { pageSize: 100 })
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
  }, []);

  // Declared BEFORE the default probe so a record switch clears `touched` in the same commit that
  // the probe reads it.
  useEffect(() => {
    touchedRef.current = Boolean(initialWorkshopId);
    setTouched(Boolean(initialWorkshopId));
    setWorkshopIdState(initialWorkshopId ?? "");
  }, [resetKey, initialWorkshopId]);

  /** Pre-flight for one workshop. Never throws and never blocks: unavailable answers are null. */
  const fetchCheck = useCallback(async (id: string): Promise<WorkshopSubmissionCheck | null> => {
    if (!id) return null;
    const cached = checkCache.current.get(id);
    if (cached) return cached;
    try {
      const result = await apiFetch<WorkshopSubmissionCheck>(`/workshops/${id}/submission-check`);
      checkCache.current.set(id, result);
      return result;
    } catch {
      // Endpoint not deployed yet, offline, or a transient 5xx — the researcher still gets to save.
      return null;
    }
  }, []);

  // Create only: preselect the most recent workshop the user may actually submit to, walking down
  // the occurrence order past workshops they are not assigned to.
  useEffect(() => {
    if (isEdit || touchedRef.current || !workshops.length) return;
    let cancelled = false;
    (async () => {
      for (const workshop of workshops.slice(0, DEFAULT_PROBE_LIMIT)) {
        const result = await fetchCheck(workshop.id);
        if (cancelled || touchedRef.current) return;
        if (!result || result.canSubmit) {
          setWorkshopIdState(workshop.id);
          return;
        }
      }
      // Every recent workshop belongs to somebody else: land on the most recent anyway so the
      // inline warning can explain why, instead of silently offering nothing.
      if (!cancelled && !touchedRef.current) setWorkshopIdState(workshops[0].id);
    })();
    return () => {
      cancelled = true;
    };
  }, [workshops, isEdit, fetchCheck]);

  // Keep the pre-flight answer in step with the selection (cache hits cost no request).
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
    // No answer (endpoint missing/errored) or the workshop is still running: save as normal.
    if (!result || (!result.outOfWindow && !result.isOver)) return true;
    setDialogCheck(result);
    setDialogOpen(true);
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve;
    });
  }, [workshopId, fetchCheck]);

  // A form unmounted mid-dialog must not leave its submit promise hanging forever.
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

/**
 * The workshop field itself: a searchable ComboBox (workshop lists get long), the assignment and
 * late-submission warnings for the current pick, and the confirmation dialog its `confirmSubmission`
 * opens. Mount it as the first dropdown in the form and hang `onDirty` on it — picking a workshop
 * only updates React state and never fires a form event, so the unsaved-changes guard needs telling.
 *
 * The reverse also has to be handled: the ComboBox's search box IS a real `<input type="text">`, and
 * the record forms mark themselves dirty from `<form onInput={markDirty}>`. Left alone, merely TYPING
 * to filter the list — changing nothing — would arm the "unsaved changes" prompt, so a user who
 * searched, picked nothing and pressed Escape could not leave without confirming. The wrapper below
 * therefore swallows the native input event; the only thing that reports dirtiness here is an actual
 * selection, through `onDirty` in `onChange`.
 */
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
  // Prefer the server's verdict; fall back to local dates when the pre-flight is unavailable.
  const late = check ? check.outOfWindow || check.isOver : Boolean(workshopId) && endedLocally(selected);
  const endLabel = formatDate(check?.endDate ?? selected?.endDate ?? selected?.date ?? null);

  return (
    // Search keystrokes stop here instead of bubbling to the form's `onInput` dirty tracker (see the
    // note above). Nothing inside this subtree is a form control the parent needs input events from.
    <div className="grid content-start gap-1" onInput={(event) => event.stopPropagation()}>
      <Field label={label}>
        <ComboBox
          options={options}
          value={workshopId}
          onChange={(next) => {
            setWorkshopId(next);
            onDirty?.();
          }}
          placeholder={loading ? "Loading workshops…" : "Select or type to search"}
        />
      </Field>
      {blocked ? (
        <p className="text-xs font-medium text-error-600">
          You are not assigned to this workshop, so saving will be refused. Ask an admin to assign you to it, or pick
          another workshop.
        </p>
      ) : null}
      {late && !blocked ? (
        <p className="text-xs font-medium text-amber-800">
          {endLabel === "-" ? "This workshop has already ended." : `This workshop ended on ${endLabel}.`}{" "}
          {check?.needsAdminApproval || !check
            ? "Saving now counts as a late submission and needs an admin's approval."
            : "Saving now is recorded as a late submission."}
        </p>
      ) : null}
      <LateSubmissionDialog
        open={dialog.open}
        workshopTitle={dialog.check?.title ?? selected?.title}
        endDate={dialog.check?.endDate ?? selected?.endDate ?? selected?.date}
        needsAdminApproval={Boolean(dialog.check?.needsAdminApproval)}
        saving={saving}
        onConfirm={dialog.confirm}
        onCancel={dialog.cancel}
      />
    </div>
  );
}
