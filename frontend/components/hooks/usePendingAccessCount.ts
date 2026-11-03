"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { accessRosterPendingCount } from "@/lib/accessRoster";
import { canManageAccessRoster } from "@/lib/permissions";

export const REFRESH_MS = 60_000;

type Listener = (value: number | null) => void;

export type PollTimers = {
  every: (run: () => void, ms: number) => unknown;
  stop: (handle: unknown) => void;
};

const browserTimers: PollTimers = {
  every: (run, ms) => window.setInterval(run, ms),
  stop: (handle) => window.clearInterval(handle as number)
};

export function createPendingCountStore(fetchCount: () => Promise<number>, timers: PollTimers = browserTimers) {
  let value: number | null = null;
  let generation = 0;
  let inFlight: Promise<void> | null = null;
  let timer: unknown = null;
  const listeners = new Set<Listener>();

  function read(): Promise<void> {
    const mine = ++generation;
    const request = fetchCount()
      .then((next) => {
        if (mine !== generation || listeners.size === 0) return;
        value = next;
        listeners.forEach((listener) => listener(value));
      })
      .catch(() => {
      })
      .finally(() => {
        if (inFlight === request) inFlight = null;
      });
    inFlight = request;
    return request;
  }

  function refresh(force = false): Promise<void> {
    if (!force && inFlight) return inFlight;
    return read();
  }

  function subscribe(listener: Listener): () => void {
    listeners.add(listener);
    listener(value);
    void refresh();
    if (timer === null) timer = timers.every(() => void refresh(), REFRESH_MS);
    return () => {
      listeners.delete(listener);
      if (listeners.size > 0) return;
      if (timer !== null) timers.stop(timer);
      timer = null;
      generation += 1;
      inFlight = null;
      value = null;
    };
  }

  return { subscribe, refresh };
}

const sharedStore = createPendingCountStore(accessRosterPendingCount);

export type PendingAccessCount = {
  pending: number | null;
  refresh: () => void;
};

export function usePendingAccessCount(): PendingAccessCount {
  const { user } = useAuth();
  const permitted = canManageAccessRoster(user);
  const [pending, setPending] = useState<number | null>(null);

  useEffect(() => {
    if (!permitted) {
      setPending(null);
      return;
    }
    return sharedStore.subscribe(setPending);
  }, [permitted]);

  const refresh = useCallback(() => {
    if (permitted) void sharedStore.refresh(true);
  }, [permitted]);

  return { pending, refresh };
}
