"use client";

import { useCallback, useEffect, useSyncExternalStore } from "react";

import { loadQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { questionnaireApi, type Recording, type Session } from "@/lib/questionnaireApi";
import {
  appendStep,
  existingSessionUnit,
  fetchTransport,
  indexedDbQueueStore,
  memoryQueueStore,
  runUnit,
  type QueueStore,
  type SessionUnit,
  type Step
} from "@/lib/questionnaireQueue";

/**
 * The page-side driver of `lib/questionnaireQueue`: one store for the tab, one drain at a time,
 * re-run when the browser comes back online. Screens subscribe to the units (pending takes and
 * per-clip progress) and append steps; nothing else talks to the outbox.
 */

type State = { units: SessionUnit[]; progress: Record<string, number>; running: boolean };
let state: State = { units: [], progress: {}, running: false };
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

let draining: Promise<void> | null = null;
export function drainQueue(): Promise<void> {
  if (draining) return draining;
  if (typeof navigator !== "undefined" && navigator.onLine === false) return Promise.resolve();
  draining = (async () => {
    emit({ running: true });
    try {
      const config = await loadQuestionnaireConfig();
      const connection = (navigator as Navigator & { connection?: { downlink?: number } }).connection;
      const slowLink = typeof connection?.downlink === "number" && connection.downlink < 0.05;
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
    } finally {
      emit({ running: false });
      draining = null;
    }
  })();
  return draining;
}

async function ensureLoaded() {
  if (loaded) return;
  loaded = true;
  await refresh();
  if (typeof window !== "undefined") window.addEventListener("online", () => void drainQueue());
  void drainQueue();
}

/** Adds a step to a session's unit (creating the unit for an existing session if needed) and drains. */
export async function enqueue(session: Pick<Session, "id" | "clientKey"> | SessionUnit, step: Step, blob?: { key: string; blob: Blob }) {
  const s = getStore();
  if (blob) await s.putBlob(blob.key, blob.blob);
  const units = await s.load();
  const id = "steps" in session ? session.id : session.clientKey;
  const base = units.find((u) => u.id === id) ?? ("steps" in session ? session : existingSessionUnit(session));
  await s.save(appendStep(base, step));
  await refresh();
  void drainQueue();
}

/** Saves a whole new unit (an offline-started session) and drains. */
export async function enqueueUnit(unit: SessionUnit) {
  await getStore().save(unit);
  await refresh();
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

export function useSessionQueue(sessionKey?: string | null) {
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
  const unit = sessionKey ? snapshot.units.find((u) => u.id === sessionKey) ?? null : null;
  const pendingClips = (unit ? [unit] : snapshot.units).flatMap((u) => u.steps.filter((s) => s.kind === "clip" && s.status !== "DONE"));
  const retry = useCallback(() => void drainQueue(), []);
  return { units: snapshot.units, unit, pendingClips, progress: snapshot.progress, running: snapshot.running, retry };
}
