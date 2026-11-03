"use client";

import { useEffect, useState } from "react";

import { apiFetch } from "@/lib/api";
import { UNREAD_COUNT_PATH } from "@/lib/inAppNotifications";

const FRESH_FOR_MS = 30_000;
const POLL_MS = 120_000;

type Subscriber = (value: number) => void;

let cached = 0;
let fetchedAt = 0;
let inFlight: Promise<void> | null = null;
const subscribers = new Set<Subscriber>();

function publish() {
  subscribers.forEach((notify) => notify(cached));
}

export function setUnreadNotificationCount(value: number) {
  if (!Number.isFinite(value) || value < 0) return;
  cached = value;
  fetchedAt = Date.now();
  publish();
}

export async function refreshUnreadNotificationCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = apiFetch<{ unreadCount: number }>(UNREAD_COUNT_PATH)
    .then((answer) => setUnreadNotificationCount(Number(answer?.unreadCount ?? 0)))
    .catch(() => {
    })
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

export function useUnreadNotificationCount(enabled: boolean): number {
  const [value, setValue] = useState<number>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    const stale = () => Date.now() - fetchedAt > FRESH_FOR_MS;
    if (stale()) void refreshUnreadNotificationCount();
    const onFocus = () => {
      if (stale()) void refreshUnreadNotificationCount();
    };
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void refreshUnreadNotificationCount();
    }, POLL_MS);
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
      window.clearInterval(timer);
    };
  }, [enabled]);

  return enabled ? value : 0;
}
