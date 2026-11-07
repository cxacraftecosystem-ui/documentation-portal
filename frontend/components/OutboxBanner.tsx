"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { CloudOff, RefreshCw, Trash2, TriangleAlert, Users } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { LocalFileDownloads } from "@/components/media/DownloadMediaButton";
import { OutboxAccountHold } from "@/components/OutboxAccountHold";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { useToast } from "@/components/ui/Toast";
import {
  claimUnownedOutboxEntries,
  discardHeldOutboxEntries,
  discardOutboxEntry,
  getOutboxSnapshot,
  getServerOutboxSnapshot,
  outboxEntriesForAnotherAccount,
  outboxEntriesForThisAccount,
  outboxEntriesWithoutOwner,
  outboxSessionUser,
  refreshOutbox,
  resolvePossibleDuplicate,
  setOutboxSessionUser,
  subscribeOutbox,
  syncOutbox,
  type OutboxEntry
} from "@/lib/offline";
import { countNoun } from "@/lib/format";

function fileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

function entryFiles(entry: OutboxEntry): File[] {
  return entry.media.flatMap((batch) => batch.files);
}

export function OutboxBanner() {
  const stored = useSyncExternalStore(subscribeOutbox, getOutboxSnapshot, getServerOutboxSnapshot);
  const [syncing, setSyncing] = useState(false);
  const { toast } = useToast();
  const { user, loading: authLoading } = useAuth();
  const signedIn = authLoading ? null : (user?.id ?? null);
  const entries = outboxEntriesForThisAccount(stored, signedIn);
  const otherAccount = outboxEntriesForAnotherAccount(stored, signedIn);
  const unowned = outboxEntriesWithoutOwner(stored, signedIn);

  useEffect(() => {
    if (authLoading) return;
    setOutboxSessionUser(user?.id ?? null);
    return () => setOutboxSessionUser(null);
  }, [authLoading, user?.id]);

  const announcedCount = useRef<number | null>(null);

  useEffect(() => {
    void refreshOutbox().then((rows) => {
      if (announcedCount.current === null) announcedCount.current = rows.length;
    });
  }, []);

  useEffect(() => {
    const seen = announcedCount.current;
    if (seen === null) return;
    announcedCount.current = entries.length;
    if (entries.length <= seen) return;
    const added = entries.length - seen;
    toast({
      id: "outbox-queued",
      tone: "info",
      title: `${added === 1 ? "Entry" : `${added} entries`} saved on this device`,
      description: "It sends when you’re back online."
    });
  }, [entries.length, toast]);

  async function answerDuplicate(entry: OutboxEntry, choice: "same" | "different") {
    setSyncing(true);
    try {
      const result = await resolvePossibleDuplicate(entry, choice);
      if (result.synced) {
        toast({
          id: "outbox-sync",
          tone: "success",
          title: choice === "same" ? "Added to the existing record" : "Saved as a new artisan"
        });
      }
    } finally {
      setSyncing(false);
    }
  }

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
          toast({
            id: "outbox-sync",
            tone: result.stoppedOffline ? "error" : "info",
            title: result.stoppedOffline ? "Still no connection" : "Nothing to send",
            description: result.stoppedOffline
              ? "Still queued. Try again with signal."
              : undefined
          });
        }
      } finally {
        setSyncing(false);
      }
    },
    [toast]
  );

  useEffect(() => {
    function onOnline() {
      if (outboxEntriesForThisAccount(getOutboxSnapshot(), outboxSessionUser()).length) drain("auto");
    }
    window.addEventListener("online", onOnline);
    return () => window.removeEventListener("online", onOnline);
  }, [drain]);

  const held = otherAccount + unowned;
  const holdPanel = held ? (
    <OutboxAccountHold
      otherAccount={otherAccount}
      unowned={unowned}
      busy={syncing}
      onClaim={() =>
        void claimUnownedOutboxEntries()
          .then(() => drain("manual"))
          .catch(() => undefined)
      }
      onDiscard={(which) => void discardHeldOutboxEntries(which).catch(() => undefined)}
    />
  ) : null;

  if (!entries.length) {
    return holdPanel ? (
      <section aria-live="polite" className="mb-4 grid gap-2 rounded-lg border border-amber-500/40 bg-amber-100/60 p-4 text-ink-900">
        {holdPanel}
      </section>
    ) : null;
  }

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
                ? "They send when you’re back online. Don’t clear this browser’s data until then."
                : "These couldn’t be saved."}
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

      {holdPanel}

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
                {fileCount(entry) ? ` · ${countNoun(fileCount(entry), "file")}` : ""}
              </span>
              {entry.possibleDuplicate ? (
                <div className="mt-0.5 text-xs text-amber-800">
                  <p className="flex items-start gap-1 font-semibold">
                    <Users className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                    May already be recorded
                  </p>
                  <p>{entry.failure}</p>
                </div>
              ) : entry.failure ? (
                <p className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  {entry.failure}
                </p>
              ) : null}
            </div>
            {entry.possibleDuplicate ? (
              <ButtonRow size="sm" align="end" className="basis-full sm:basis-auto">
                <button type="button" className="field-button-secondary" disabled={syncing} onClick={() => answerDuplicate(entry, "same")}>
                  Same person
                </button>
                <button type="button" className="field-button-secondary" disabled={syncing} onClick={() => answerDuplicate(entry, "different")}>
                  Different person
                </button>
              </ButtonRow>
            ) : null}
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
            <LocalFileDownloads files={entryFiles(entry)} className="basis-full" />
          </li>
        ))}
      </ul>
    </section>
  );
}
