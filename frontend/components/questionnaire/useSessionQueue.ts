"use client";

import { useCallback, useEffect, useSyncExternalStore } from "react";

import { loadQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { questionnaireApi, type Recording, type Session } from "@/lib/questionnaire/api";
import {
  answerConflicts,
  discardStep,
  enqueueStep,
  existingSessionUnit,
  fetchTransport,
  indexedDbQueueStore,
  memoryQueueStore,
  mutateUnit,
  resolveAnswerConflict,
  runUnit,
  sendStepAgain,
  sendWithoutConflicts,
  sessionUnitKey,
  type QueueStore,
  type SessionUnit,
  type Step
} from "@/lib/questionnaire/queue";

/**
 * The page-side driver of `lib/questionnaireQueue`: one store for the tab, one drain at a time,
 * re-run when the browser comes back online, when a step arrives mid-drain (audit C5), and on a
 * backing-off timer while anything is still waiting (audit M22). Screens subscribe to the units
 * (pending takes and per-clip progress) and append steps; nothing else talks to the outbox.
 */

type State = { units: SessionUnit[]; progress: Record<string, number>; running: boolean; online: boolean };
let state: State = { units: [], progress: {}, running: false, online: true };
const listeners = new Set<() => void>();
let store: QueueStore | null = null;
let loaded = false;
const recordingListeners = new Set<(r: Recording) => void>();
const sessionListeners = new Set<(s: Session) => void>();

function emit(next: Partial<State>) {
  state = { ...state, ...next };
  listeners.forEach((l) => l());
}

function getStore(): QueueStore {
  if (store) return store;
  store = typeof indexedDB === "undefined" ? memoryQueueStore() : indexedDbQueueStore();
  return store;
}

async function refresh() {
  emit({ units: await getStore().load() });
}

/** Retry back-off while steps wait: 15 s, doubling to 5 min; reset by any pass that sends something. */
export const RETRY_FIRST_MS = 15_000;
export const RETRY_MAX_MS = 5 * 60_000;
export function nextRetryDelay(previous: number | null): number {
  return previous === null ? RETRY_FIRST_MS : Math.min(RETRY_MAX_MS, previous * 2);
}

/** True while a unit still has something the queue will send by itself (not held for a person). */
export function hasRetryableWork(units: SessionUnit[]): boolean {
  return units.some((u) =>
    u.steps.some((s) => s.status === "PENDING" && !(s.kind === "answers" && s.error === "CONFLICT") && !(s.kind === "clip" && s.error === "MISSING_FILE"))
  );
}

let draining: Promise<void> | null = null;
let again = false;
let retryTimer: ReturnType<typeof setTimeout> | null = null;
let retryDelay: number | null = null;

function scheduleRetry() {
  if (retryTimer || typeof window === "undefined") return;
  if (!hasRetryableWork(state.units)) {
    retryDelay = null;
    return;
  }
  retryDelay = nextRetryDelay(retryDelay);
  retryTimer = setTimeout(() => {
    retryTimer = null;
    void drainQueue();
  }, retryDelay);
}

export function drainQueue(): Promise<void> {
  if (draining) {
    // A step arrived while a pass runs: run one more pass when this one ends.
    again = true;
    return draining;
  }
  if (typeof navigator !== "undefined" && navigator.onLine === false) return Promise.resolve();
  draining = (async () => {
    emit({ running: true });
    try {
      const config = await loadQuestionnaireConfig();
      const connection = (navigator as Navigator & { connection?: { downlink?: number } }).connection;
      const slowLink = typeof connection?.downlink === "number" && connection.downlink < 0.05;
      do {
        again = false;
        for (const unit of await getStore().load()) {
          await runUnit(unit, questionnaireApi, getStore(), fetchTransport, {
            multipartThresholdBytes: config.multipartThresholdBytes,
            partSizeBytes: config.partSizeBytes,
            slowLink,
            onProgress: (key, fraction) => emit({ progress: { ...state.progress, [key]: fraction } }),
            onRecording: (r) => recordingListeners.forEach((l) => l(r)),
            onSession: (s) => sessionListeners.forEach((l) => l(s))
          });
          await refresh();
        }
      } while (again && (typeof navigator === "undefined" || navigator.onLine !== false));
    } finally {
      emit({ running: false });
      draining = null;
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      scheduleRetry();
    }
  })();
  return draining;
}

async function ensureLoaded() {
  if (loaded) return;
  loaded = true;
  await refresh();
  if (typeof window !== "undefined") {
    emit({ online: navigator.onLine !== false });
    window.addEventListener("online", () => {
      emit({ online: true });
      retryDelay = null;
      void drainQueue();
    });
    window.addEventListener("offline", () => emit({ online: false }));
  }
  void drainQueue();
}

/** The unit a session's steps go into: an offline-started one by clientKey, any other by clientKey or id. */
function baseUnit(session: Pick<Session, "id" | "clientKey"> | SessionUnit): SessionUnit {
  return "steps" in session ? session : existingSessionUnit(session);
}

/**
 * Adds a step to a session's unit (creating the unit for an existing session if needed) and drains.
 * Resolves once the step is in the store; rejects when it could not be written, so a caller never
 * treats an unsaved take as saved.
 */
export async function enqueue(session: Pick<Session, "id" | "clientKey"> | SessionUnit, step: Step, blob?: { key: string; blob: Blob }) {
  const s = getStore();
  if (blob) await s.putBlob(blob.key, blob.blob);
  await enqueueStep(s, baseUnit(session), step);
  await refresh();
  void drainQueue();
}

/** Saves a whole new unit (an offline-started session) and drains. */
export async function enqueueUnit(unit: SessionUnit) {
  await mutateUnit(getStore(), unit.id, (current) => current ?? unit);
  await refresh();
  void drainQueue();
}

/** P9 for an answer held in the outbox (audit H18). */
export async function resolveQueuedAnswer(
  unitId: string,
  key: string,
  target: { artisanId?: string | null; respondentId?: string | null; questionLineageId: string },
  choice: "mine" | "theirs"
) {
  await resolveAnswerConflict(getStore(), unitId, key, target, choice);
  await refresh();
  void drainQueue();
}

/** The fix tray (UNIFY B39): send a refused step again, discard it, or send the answers that are not in conflict. */
export async function trayAct(unitId: string, key: string, act: "again" | "discard" | "without") {
  const s = getStore();
  if (act === "again") await sendStepAgain(s, unitId, key);
  else if (act === "discard") await discardStep(s, unitId, key);
  else await sendWithoutConflicts(s, unitId, key);
  await refresh();
  retryDelay = null;
  void drainQueue();
}

export function onRecordingLanded(listener: (r: Recording) => void) {
  recordingListeners.add(listener);
  return () => recordingListeners.delete(listener);
}
export function onSessionLanded(listener: (s: Session) => void) {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

/** `session` is anything carrying the session's id and clientKey; a bare string is a unit key. */
export function useSessionQueue(session?: Pick<Session, "id" | "clientKey"> | string | null) {
  const snapshot = useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
    () => state
  );
  useEffect(() => {
    void ensureLoaded();
  }, []);
  const key = !session ? null : typeof session === "string" ? session : sessionUnitKey(session);
  // A session that landed after starting offline keeps its clientKey; one opened by id may be stored either way.
  const unit =
    key === null
      ? null
      : snapshot.units.find((u) => u.id === key) ??
        (typeof session === "object" && session ? snapshot.units.find((u) => u.id === session.id || (u.sessionId !== null && u.sessionId === session.id)) ?? null : null);
  const pendingClips = (unit ? [unit] : key === null ? snapshot.units : []).flatMap((u) => u.steps.filter((s) => s.kind === "clip" && s.status !== "DONE"));
  const retry = useCallback(() => {
    retryDelay = null;
    void drainQueue();
  }, []);
  return {
    units: snapshot.units,
    unit,
    pendingClips,
    conflicts: answerConflicts(unit),
    progress: snapshot.progress,
    running: snapshot.running,
    online: snapshot.online,
    retry
  };
}
