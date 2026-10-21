"use client";

import { useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { loadQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { questionnaireApi } from "@/lib/questionnaireApi";
import { readDismissed, visibleInbox, type Inbox } from "@/lib/questionnaireInbox";

/** Five minutes: inbox items wait a day before they badge, so a fast poll would buy nothing. */
const REFRESH_MS = 5 * 60_000;

let inboxInFlight: Promise<Inbox> | null = null;

/**
 * `GET /questionnaire/inbox`, shared by the badge and the inbox page while one request is out: on
 * the inbox page both mount together. `fresh` starts a new read regardless (after an action, when
 * an answer that left before it would be out of date). Nothing is kept once a request settles.
 */
export function readInbox(fresh = false, fetchInbox: () => Promise<Inbox> = questionnaireApi.inbox): Promise<Inbox> {
  if (!fresh && inboxInFlight) return inboxInFlight;
  const request = fetchInbox().finally(() => {
    if (inboxInFlight === request) inboxInFlight = null;
  });
  inboxInFlight = request;
  return request;
}

/**
 * The questionnaire inbox's badge (DESIGN §4.7): how many items have waited more than 24 h, or null
 * while unknown, when v2 is off, or when the count is zero. Asks nothing until v2 is on.
 */
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
        // A failed poll keeps the last number; the badge is a nudge, never a source of truth.
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
