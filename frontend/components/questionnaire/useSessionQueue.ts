"use client";

import { useCallback, useEffect, useSyncExternalStore } from "react";

import { useAuth } from "@/components/AuthProvider";
import { loadQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { questionnaireApi, type Recording, type Session } from "@/lib/questionnaire/api";
import {
  answerConflicts,
  claimUnits,
  discardStep,
  discardUnits,
  drainableUnits,
  enqueueStep,
  existingSessionUnit,
  fetchTransport,
  heldUnits,
  indexedDbQueueStore,
  memoryQueueStore,
  mutateUnit,
  QueueOwnerError,
  resolveAnswerConflict,
  runUnit,
  sendStepAgain,
  sendWithoutConflicts,
  sessionUnitKey,
  stampOwner,
  unitsOwnedBy,
  type HeldUnits,
  type QueueStore,
  type SessionUnit,
  type Step
} from "@/lib/questionnaire/queue";

type State = { units: SessionUnit[]; held: HeldUnits; progress: Record<string, number>; running: boolean; online: boolean };
let state: State = { units: [], held: { otherAccount: [], unowned: [] }, progress: {}, running: false, online: true };
let owner: string | null = null;
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
  const all = await getStore().load();
  emit({ units: unitsOwnedBy(all, owner), held: heldUnits(all, owner) });
}

export function setQueueOwner(userId: string | null) {
  if (owner === (userId || null)) return;
  owner = userId || null;
  retryDelay = null;
  if (!loaded) return;
  void refresh().then(() => drainQueue());
}

export const RETRY_FIRST_MS = 15_000;
export const RETRY_MAX_MS = 5 * 60_000;
export function nextRetryDelay(previous: number | null): number {
  return previous === null ? RETRY_FIRST_MS : Math.min(RETRY_MAX_MS, previous * 2);
}

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
        for (const unit of await drainableUnits(getStore(), owner)) {
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

function baseUnit(session: Pick<Session, "id" | "clientKey"> | SessionUnit): SessionUnit {
  return "steps" in session ? session : existingSessionUnit(session);
}

export async function enqueue(session: Pick<Session, "id" | "clientKey"> | SessionUnit, step: Step, blob?: { key: string; blob: Blob }) {
  const s = getStore();
  if (blob) await s.putBlob(blob.key, blob.blob);
  await enqueueStep(s, stampOwner(baseUnit(session), owner), step);
  await refresh();
  void drainQueue();
}

export async function enqueueUnit(unit: SessionUnit) {
  const owned = stampOwner(unit, owner);
  await mutateUnit(getStore(), unit.id, (current) => {
    if (current?.ownerUserId && current.ownerUserId !== owned.ownerUserId) throw new QueueOwnerError();
    return current ?? owned;
  });
  await refresh();
  void drainQueue();
}

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

export async function trayAct(unitId: string, key: string, act: "again" | "discard" | "without") {
  const s = getStore();
  if (act === "again") await sendStepAgain(s, unitId, key);
  else if (act === "discard") await discardStep(s, unitId, key);
  else await sendWithoutConflicts(s, unitId, key);
  await refresh();
  retryDelay = null;
  void drainQueue();
}

export async function settleHeld(act: "claim" | "discard", which: keyof HeldUnits) {
  const ids = state.held[which];
  if (!ids.length) return;
  if (act === "claim") {
    if (!owner || which !== "unowned") return;
    await claimUnits(getStore(), ids, owner);
  } else {
    await discardUnits(getStore(), ids);
  }
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

export function useSessionQueue(session?: Pick<Session, "id" | "clientKey"> | string | null) {
  const snapshot = useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
    () => state
  );
  const { user, loading } = useAuth();
  const userId = loading ? null : (user?.id ?? null);
  useEffect(() => {
    setQueueOwner(userId);
  }, [userId]);
  useEffect(() => {
    void ensureLoaded();
  }, []);
  const key = !session ? null : typeof session === "string" ? session : sessionUnitKey(session);
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
    held: snapshot.held,
    unit,
    pendingClips,
    conflicts: answerConflicts(unit),
    progress: snapshot.progress,
    running: snapshot.running,
    online: snapshot.online,
    retry
  };
}
