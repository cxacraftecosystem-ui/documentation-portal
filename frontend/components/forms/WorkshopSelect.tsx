"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { Field } from "@/components/FormControls";
import { LateSubmissionDialog } from "@/components/LateSubmissionDialog";
import { ComboBox } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";

/**
 * The ONE workshop picker every record form mounts, above its craft / artisan / product dropdowns.
 *
 * Every record type carries a workshop now (artisan, craft, product, tool, process, questionnaire),
 * and the workshop decides two things the researcher must learn BEFORE saving, not after:
 *
 * 1. **Assignment.** Once a workshop has assignments only those researchers (and admins) may file
 *    records against it — the API answers 403. This warns at select time instead.
 * 2. **The window.** A record filed OUTSIDE the workshop's run of days — before it opens as well as
 *    after it closes — is accepted but pinned to PENDING and flagged `needsAdminApproval`, so only
 *    an admin can approve it. `confirmSubmission()` surfaces that as <LateSubmissionDialog> and the
 *    save proceeds only on confirm. There are three states here and not two; see the block comment
 *    above `workshopWindowState` for the sentence that saying otherwise put on screen.
 *
 * Both facts come from `GET /workshops/{id}/submission-check`, which never 403s — it only reports.
 * When that endpoint is missing or fails we degrade to a purely local reading of the workshop's own
 * dates and NEVER block the save: a researcher in the field must not lose work to a flaky pre-flight
 * request.
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
  // `formatWorkshopDay`, not `formatDate`: the occurrence date is a workshop DAY, and rendering the
  // stored instant in the browser's zone is what put a workshop's own label a day off its window —
  // the same one-day skew that printed "ended on 24 Sept" for a workshop whose last day is the 23rd.
  const when = formatWorkshopDay(workshopOccurrenceDate(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

/* ------------------------------------------------------------------------------------------------
 * THE WINDOW: THREE STATES, COUNTED IN IST DAYS.
 *
 * WHAT THIS REPLACED, AND WHY IT IS WORTH THIS MUCH FILE. On 14 Sept 2026 at 00:55 IST the owner
 * opened a form for a workshop running 14–23 Sept and read:
 *
 *     "This workshop ended on 24 Sept 2026. Saving now is recorded as a late submission."
 *
 * Three sentences' worth of wrong, from three separate causes, all of which live here now:
 *
 *  1. `late = check.outOfWindow || check.isOver` (this file, previously line 287) collapsed a
 *     TWO-SIDED flag to one bit and then printed the after-the-end half of it. `outOfWindow` is
 *     true before the start as well as after the end — see
 *     `backend/app/services/workshop_access.py::describe_workshop_submission` — so a workshop that
 *     had not opened was announced as ENDED. There was no copy for "not started" at all.
 *  2. The backend judged the window in UTC against days typed in IST, so every workshop read as
 *     not-started for the first 5h30m of each IST day. Fixed server-side (`WORKSHOP_TZ` there); the
 *     local fallback below had the same skew via `Date.now() >= end + 24h` and is fixed here.
 *  3. `formatDate` renders an instant in the BROWSER's zone, so an endDate stored as
 *     2026-09-23T23:59:59.999Z printed as 24 Sept on an IST laptop — a date the workshop does not
 *     contain. `formatWorkshopDay` below renders the day the column NAMES instead.
 *
 * The state is derived with NO new wire field. `isOver` is already the after-the-end half on its
 * own and `outOfWindow` is already the union, so the two booleans this endpoint has always returned
 * spell all three states between them. That keeps `lib/types.ts` and Android's `ApiModels.kt` — two
 * hand-maintained mirrors of one dict — untouched, and a client that missed a DTO edit would have
 * read a new discriminator as `false` and printed the old wrong sentence anyway.
 *
 * These are exported plain functions, not inline JSX expressions, for the same reason as
 * `components/forms/recordPickers`: reproducing the defect on screen needs a workshop whose window
 * straddles the hour you happen to run the app, so the ruling is lifted somewhere
 * `e2e/workshop-window-unit.spec.ts` can stand in front of it at a named instant. The Kotlin twin is
 * `android/app/src/main/java/com/fieldrepository/app/ui/WorkshopOptions.kt`, tested by
 * `WorkshopWindowTest.kt`; if you change a rule here and that suite still passes, you have just made
 * the two clients disagree about one workshop.
 * ---------------------------------------------------------------------------------------------- */

/** India Standard Time, in minutes. One offset, no daylight saving since 1945. */
const IST_OFFSET_MINUTES = 330;

export type WorkshopWindowState = "NOT_STARTED" | "IN_WINDOW" | "ENDED";

/**
 * The calendar day a workshop's `startDate` / `endDate` NAMES, as "YYYY-MM-DD".
 *
 * Read off the stored wall clock rather than converted into any zone, because the column is a day
 * wearing a datetime's clothes: the forms write the start at 00:00:00 and the end at 23:59:59.999,
 * and whichever offset the writer's client stamped on it, the date part is the day the researcher
 * typed. "2026-09-23T23:59:59.999+00:00" and "...+05:30" are both the 23rd. This is the same read
 * Android makes with `OffsetDateTime.parse(value).toLocalDate()`, and the same one the server makes
 * in `_boundary_day`.
 */
export function workshopDayKey(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const stamped = /^(\d{4}-\d{2}-\d{2})/.exec(raw.trim());
  if (stamped) return stamped[1];
  const parsed = new Date(raw);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString().slice(0, 10);
}

/** The IST calendar day an instant falls on — the "today" a workshop window is judged against. */
export function istDayKey(now: number = Date.now()): string {
  return new Date(now + IST_OFFSET_MINUTES * 60_000).toISOString().slice(0, 10);
}

/**
 * "23 Sept 2026" for a workshop boundary column — the day it names, never the instant.
 *
 * Same options as `lib/format.ts::formatDate` so the label reads identically to every other date in
 * the app, plus a pinned `timeZone` so the rendering cannot walk a day in either direction. Routing
 * through `formatDate` is what printed "24 Sept" for a workshop whose last day is the 23rd.
 */
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

/**
 * Which of the three states the current pick is in.
 *
 * The server's verdict wins when there is one. When the pre-flight is unavailable — not deployed,
 * offline, a transient 5xx — we fall back to the workshop's own dates, judged the same way the
 * server judges them: whole IST calendar days, inclusive at both ends. A researcher in a courtyard
 * with no signal is exactly who reads this sentence, and they must not be told their workshop ended
 * because the sun has not yet risen on its last day in UTC.
 */
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
  // No dates recorded at all is not evidence of anything, and never a reason to warn.
  if (startKey && today < startKey) return "NOT_STARTED";
  return "IN_WINDOW";
}

/**
 * The inline sentence for a pick that is outside its window, or null when it is inside one.
 *
 * Each state gets copy that is TRUE of it. The date half degrades to a dateless form when the day
 * is unknown — the pre-flight carries no `startDate`, so a workshop that has scrolled off the loaded
 * page can leave `startLabel` at "-" — because "This workshop has not started yet" is still worth
 * saying, and inventing a date would not be.
 *
 * `needsAdminApproval` is the server's answer; pass `true` when there is no server answer, which is
 * the wording that promises the most and is therefore the safe one to be wrong about.
 */
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
  // Three states, not two. See the block comment above `workshopWindowState` for the sentence this
  // replaced and the two defects that produced it.
  const windowState = workshopId ? workshopWindowState(check, selected) : "IN_WINDOW";
  const notice = workshopWindowNotice({
    state: windowState,
    // The pre-flight deliberately carries no startDate (the wire did not change), so the start day
    // comes from the loaded workshop row; "-" when that row is off the page, which the copy handles.
    startLabel: formatWorkshopDay(selected?.startDate ?? selected?.date ?? null),
    endLabel: formatWorkshopDay(check?.endDate ?? selected?.endDate ?? selected?.date ?? null),
    // No answer means we cannot promise the submission escapes review, so we do not.
    needsAdminApproval: check ? check.needsAdminApproval : true
  });

  return (
    // Search keystrokes stop here instead of bubbling to the form's `onInput` dirty tracker (see the
    // note above). Nothing inside this subtree is a form control the parent needs input events from.
    <div className="grid min-w-0 content-start gap-1" onInput={(event) => event.stopPropagation()}>
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
      {notice && !blocked ? <p className="text-xs font-medium text-amber-800">{notice}</p> : null}
      <LateSubmissionDialog
        open={dialog.open}
        workshopTitle={dialog.check?.title ?? selected?.title}
        // The DAY, not the instant. <LateSubmissionDialog> renders this through `lib/format.ts`'s
        // `formatDate`, which resolves an instant in the browser's zone — that is what turned an
        // endDate stored as 2026-09-23T23:59:59.999Z into "24 Sept 2026", a date the workshop does
        // not contain. Handing it the bare day key ("2026-09-23") is the day the column names.
        endDate={workshopDayKey(dialog.check?.endDate ?? selected?.endDate ?? selected?.date)}
        needsAdminApproval={Boolean(dialog.check?.needsAdminApproval)}
        saving={saving}
        onConfirm={dialog.confirm}
        onCancel={dialog.cancel}
      />
    </div>
  );
}
