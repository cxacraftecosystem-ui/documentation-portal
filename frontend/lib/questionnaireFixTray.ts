/**
 * The questionnaire outbox's fix tray (UNIFY B39, section 4.3): what is stuck on this device and the
 * three ways forward — Send again, Discard, and Send without the conflicting answers — over the
 * shared queue (`lib/questionnaire/queue.ts`), through its one writer (`mutateUnit`), so a step
 * being sent elsewhere is never overwritten. P9 itself (Keep theirs / Keep mine) stays in the
 * session's workspace; the tray's third button is "keep theirs" for every held answer at once.
 *
 * Field Repository's own module over the shared queue; the tray component is
 * `components/questionnaires/QuestionnaireFixTray.tsx`.
 */

import {
  answerConflicts,
  mutateUnit,
  resolveAnswerConflict,
  stepKey,
  type QueueStore,
  type SessionUnit,
  type Step
} from "@/lib/questionnaire/queue";

export type StuckStep = {
  unitId: string;
  sessionId: string | null;
  key: string;
  kind: Step["kind"];
  /** What it is, in words. */
  label: string;
  /** Why it is stuck, the server's sentence where there is one. */
  reason: string;
  /** Answers held for a decision (P9): the third button applies. */
  conflicts: number;
};

function label(step: Step): string {
  switch (step.kind) {
    case "session":
      return "New session";
    case "participant":
      return step.op === "add" ? "Artisan added" : step.op === "leave" ? "Artisan away" : "Artisan back";
    case "clip":
      return step.label ? `Recording · ${step.label}` : "Recording";
    default:
      return step.items.length === 1 ? "1 answer" : `${step.items.length} answers`;
  }
}

/** Every step that will not go on its own: a failure, or answers held for a decision. */
export function stuckSteps(units: SessionUnit[]): StuckStep[] {
  return units.flatMap((unit) =>
    unit.steps.flatMap((step) => {
      const held = step.kind === "answers" && step.error === "CONFLICT";
      if (step.status !== "FAILED" && !held) return [];
      const conflicts = held ? answerConflicts({ ...unit, steps: [step] }).length : 0;
      return [
        {
          unitId: unit.id,
          sessionId: unit.sessionId,
          key: stepKey(step),
          kind: step.kind,
          label: label(step),
          reason: held ? `${conflicts} changed on another device` : step.error || "Not sent",
          conflicts
        }
      ];
    })
  );
}

/** Send again: the step goes back to PENDING with its error cleared; the next pass sends it. */
export function sendAgain(store: QueueStore, unitId: string, key: string): Promise<SessionUnit | null> {
  return mutateUnit(store, unitId, (unit) => {
    if (!unit) return null;
    for (const step of unit.steps) {
      if (stepKey(step) !== key) continue;
      step.status = "PENDING";
      step.error = undefined;
      if (step.kind === "clip") step.attempts = 0;
    }
    return unit;
  });
}

/**
 * Discard: the step is dropped from this device (a recording's saved bytes too). A unit with nothing
 * left to send is removed. Discarding the session itself discards everything queued under it.
 */
export async function discardStep(store: QueueStore, unitId: string, key: string): Promise<SessionUnit | null> {
  const before = await store.get(unitId);
  const dropped = (before?.steps ?? []).filter((s) => key === "session" || stepKey(s) === key);
  const next = await mutateUnit(store, unitId, (unit) => {
    if (!unit || key === "session") return null;
    const steps = unit.steps.filter((s) => stepKey(s) !== key);
    return steps.some((s) => s.status !== "DONE") ? { ...unit, steps } : null;
  });
  for (const step of dropped) if (step.kind === "clip") await store.deleteBlob(step.blobKey);
  return next;
}

/** Send without the conflicting answers: every held answer keeps the other device's text. */
export async function sendWithoutConflicts(store: QueueStore, unitId: string, key: string): Promise<SessionUnit | null> {
  const unit = await store.get(unitId);
  let last: SessionUnit | null = unit;
  for (const held of answerConflicts(unit).filter((c) => c.stepKey === key)) {
    last = await resolveAnswerConflict(store, unitId, key, held.item, "theirs");
  }
  return last;
}

/** The tray's buttons for one stuck step, in order (Discard is destructive and goes last). */
export function trayActions(step: Pick<StuckStep, "conflicts">): ("sendAgain" | "withoutConflicts" | "discard")[] {
  return step.conflicts > 0 ? ["withoutConflicts", "discard"] : ["sendAgain", "discard"];
}

export const TRAY_COPY = {
  title: "Not sent yet",
  sendAgain: "Send again",
  withoutConflicts: "Send without the conflicting answers",
  discard: "Discard",
  discardTitle: "Discard this from the device?",
  discardBody: "It has not reached the server, so it will be lost.",
  empty: null
} as const;
