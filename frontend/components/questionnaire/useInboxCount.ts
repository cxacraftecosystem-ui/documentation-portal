"use client";

import { useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { loadQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { questionnaireApi } from "@/lib/questionnaire/api";
import { readDismissed, visibleInbox, type Inbox } from "@/lib/questionnaire/inbox";

const REFRESH_MS = 5 * 60_000;

let inboxInFlight: Promise<Inbox> | null = null;

export function readInbox(fresh = false, fetchInbox: () => Promise<Inbox> = questionnaireApi.inbox): Promise<Inbox> {
  if (!fresh && inboxInFlight) return inboxInFlight;
  const request = fetchInbox().finally(() => {
    if (inboxInFlight === request) inboxInFlight = null;
  });
  inboxInFlight = request;
  return request;
}

export function useInboxCount(): number | null {
  const { user } = useAuth();
  const [count, setCount] = useState<number | null>(null);
  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    let timer: ReturnType<typeof setInterval> | null = null;
    const read = async () => {
      try {
        const inbox = visibleInbox(await readInbox(), readDismissed());
        if (!cancelled) setCount(inbox.counts.olderThan24h > 0 ? inbox.counts.olderThan24h : null);
      } catch {
      }
    };
    void loadQuestionnaireConfig().then((config) => {
      if (cancelled || !config.v2Enabled) return;
      void read();
      timer = setInterval(() => void read(), REFRESH_MS);
    });
    return () => {
      cancelled = true;
      if (timer) clearInterval(timer);
    };
  }, [user]);
  return count;
}
