"use client";

/**
 * The offline outbox, made visible.
 *
 * An outbox nobody can see is worse than no outbox: the researcher believes the record is saved,
 * the record is on one laptop, and nobody finds out until the dataset is short. So this sits above
 * the page content whenever anything is queued and says three things plainly — how many entries are
 * waiting, that they are on THIS device only, and what will send them.
 *
 * It drains automatically when the connection returns (the `online` event) and offers "Sync now"
 * for the case the browser's own online flag is optimistic — captive portals and hotel wi-fi report
 * `navigator.onLine === true` while nothing routes.
 *
 * Entries the server permanently rejected stay listed with the reason, each with its own Discard,
 * because the only person who can decide whether a rejected record matters is the person who typed
 * it. Nothing here deletes an entry on its own.
 */

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { CloudOff, RefreshCw, Trash2, TriangleAlert } from "lucide-react";

import { useToast } from "@/components/ui/Toast";
import {
  discardOutboxEntry,
  getOutboxSnapshot,
  getServerOutboxSnapshot,
  refreshOutbox,
  subscribeOutbox,
  syncOutbox,
  type OutboxEntry
} from "@/lib/offline";

/** Files across every batch of one entry — what the user attached, not how the form grouped it. */
function fileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

export function OutboxBanner() {
  const entries = useSyncExternalStore(subscribeOutbox, getOutboxSnapshot, getServerOutboxSnapshot);
  const [syncing, setSyncing] = useState(false);
  const { toast } = useToast();

  // Read the store once on mount: entries survive a browser restart, so a fresh tab has to look.
  useEffect(() => {
    refreshOutbox();
  }, []);

  // Confirming a queued save belongs HERE, not in each of the six forms that can queue one. A form
  // that queues just stops and scrolls; the count going up is the event, wherever it came from, so
  // one watcher covers every save path and cannot drift out of step with the banner beneath it.
  const previousCount = useRef<number | null>(null);
  useEffect(() => {
    const seen = previousCount.current;
    previousCount.current = entries.length;
    if (seen === null || entries.length <= seen) return;
    const added = entries.length - seen;
    toast({
      id: "outbox-queued",
      tone: "info",
      title: `${added === 1 ? "Entry" : `${added} entries`} saved on this device`,
      description: "There is no connection, so it is queued below and sends itself when signal returns."
    });
  }, [entries.length, toast]);

  const drain = useCallback(
    async (trigger: "auto" | "manual") => {
      setSyncing(true);
      try {
        const result = await syncOutbox();
        if (result.synced) {
          toast({
            id: "outbox-sync",
            tone: "success",
            title: `${result.synced} saved ${result.synced === 1 ? "entry" : "entries"} sent`,
            description: result.remaining ? `${result.remaining} still waiting.` : "The outbox is empty."
          });
        } else if (trigger === "manual") {
          // Only a click deserves an answer when nothing moved; an automatic pass stays quiet.
          toast({
            id: "outbox-sync",
            tone: result.stoppedOffline ? "error" : "info",
            title: result.stoppedOffline ? "Still no connection" : "Nothing to send",
            description: result.stoppedOffline
              ? "Everything stays queued on this device. Try again once you have signal."
              : undefined
          });
        }
      } finally {
        setSyncing(false);
      }
    },
    [toast]
  );

  // The connection coming back is the whole point of the queue — drain without being asked.
  useEffect(() => {
    function onOnline() {
      if (getOutboxSnapshot().length) drain("auto");
    }
    window.addEventListener("online", onOnline);
    return () => window.removeEventListener("online", onOnline);
  }, [drain]);

  if (!entries.length) return null;

  const rejected = entries.filter((entry) => entry.failure);
  const waiting = entries.length - rejected.length;

  return (
    <section
      aria-live="polite"
      className="mb-4 grid gap-3 rounded-lg border border-amber-500/40 bg-amber-100/60 p-4 text-ink-900"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <CloudOff className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
          <div>
            <h2 className="font-display text-sm font-bold">
              {waiting
                ? `${waiting} ${waiting === 1 ? "entry is" : "entries are"} saved on this device only`
                : "Entries on this device need your attention"}
            </h2>
            <p className="mt-0.5 text-xs text-ink-700">
              {waiting
                ? "They were made without a connection and have not reached the repository yet. They send themselves when the connection returns — but they live in this browser, so do not clear its data or hand the laptop on until the outbox is empty."
                : "Nothing is waiting on the network. The entries below were refused by the server and need a decision."}
            </p>
          </div>
        </div>
        {waiting ? (
          <button type="button" className="field-button-secondary shrink-0" disabled={syncing} onClick={() => drain("manual")}>
            <RefreshCw className={`h-4 w-4 ${syncing ? "animate-spin" : ""}`} aria-hidden />
            {syncing ? "Sending…" : "Sync now"}
          </button>
        ) : null}
      </div>

      <ul className="grid gap-1.5">
        {entries.map((entry) => (
          <li
            key={entry.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
          >
            <div className="min-w-0">
              <span className="text-sm font-medium">{entry.label}</span>
              <span className="ml-2 text-xs text-ink-500">
                {new Date(entry.createdAt).toLocaleString()}
                {fileCount(entry) ? ` · ${fileCount(entry)} file(s)` : ""}
              </span>
              {entry.failure ? (
                <p className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  {entry.failure}
                </p>
              ) : null}
            </div>
            {entry.failure ? (
              <button
                type="button"
                className="inline-flex items-center gap-1 text-xs font-semibold text-error-600"
                onClick={() => discardOutboxEntry(entry.id!)}
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden />
                Discard
              </button>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}
