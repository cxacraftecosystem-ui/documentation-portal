"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { EmptyState } from "@/components/EmptyState";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { questionnaireApi, type Inbox, type InboxItem } from "@/lib/questionnaireApi";
import { qs, type QsKey } from "@/lib/strings/qs";

/** The inbox sections in DESIGN §4.7 order, with each section's actions. */
export const INBOX_SECTIONS: { key: keyof Omit<Inbox, "counts">; title: QsKey; actions: QsKey[] }[] = [
  { key: "toFile", title: "inboxToFile", actions: ["notNeeded", "fileIt"] },
  { key: "needsDecision", title: "inboxNeedsDecision", actions: ["keepBoth", "keepOne"] },
  { key: "legacy", title: "inboxLegacy", actions: ["confirm"] },
  { key: "setDates", title: "inboxSetDates", actions: ["setDate"] },
  { key: "noParticipants", title: "inboxWhoWasThere", actions: ["save"] },
  { key: "emptySessions", title: "inboxEmptySession", actions: ["keep", "delete"] },
  { key: "primaryChanged", title: "inboxPrimaryChanged", actions: ["ok"] }
];

/**
 * The inbox (DESIGN §4.7). Rows link to the session where the work is done; decisions that the API
 * answers directly (keep both, keep one) are offered in place.
 */
export function InboxList() {
  const [inbox, setInbox] = useState<Inbox | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    setError(null);
    try {
      setInbox(await questionnaireApi.inbox());
    } catch {
      setError(qs("listLoadError"));
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  if (error)
    return (
      <div className="panel grid gap-3 p-6 text-center">
        <p className="text-sm text-ink-700">{error}</p>
        <ButtonRow>
          <button type="button" className="field-button" onClick={() => void load()}>
            {qs("tryAgain")}
          </button>
        </ButtonRow>
      </div>
    );
  if (!inbox) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
  if (inbox.counts.total === 0) return <EmptyState title={qs("inboxEmpty")} />;

  async function act(section: string, item: InboxItem, action: QsKey) {
    if (section === "needsDecision" && action === "keepBoth") await questionnaireApi.decide(item.id, { action: "KEEP_BOTH" }).catch(() => undefined);
    void load();
  }

  return (
    <div className="grid gap-4">
      {INBOX_SECTIONS.filter((s) => inbox[s.key].length > 0).map((section) => (
        <section key={section.key} className="panel grid gap-2 p-4" data-inbox-section={section.key}>
          <h2 className="display-title text-base">
            {qs(section.title)} <span className="text-ink-500">({inbox[section.key].length})</span>
          </h2>
          <ul className="grid gap-2">
            {inbox[section.key].map((item) => (
              <li key={item.id} className="flex flex-col gap-2 rounded-md border border-line-200 p-3 sm:flex-row sm:items-center sm:justify-between">
                {item.sessionId ? (
                  <Link className="text-sm text-purple-700 underline" href={`/questionnaire/sessions/${encodeURIComponent(item.sessionId)}`}>
                    {item.label}
                  </Link>
                ) : (
                  <span className="text-sm text-ink-900">{item.label}</span>
                )}
                <ButtonRow size="sm" align="end" className="max-sm:w-full">
                  {section.actions.map((action) =>
                    item.sessionId && action !== "keepBoth" && action !== "ok" ? (
                      <Link key={action} className="field-button-secondary" href={`/questionnaire/sessions/${encodeURIComponent(item.sessionId)}`}>
                        {qs(action)}
                      </Link>
                    ) : (
                      <button key={action} type="button" className="field-button-secondary" onClick={() => void act(section.key, item, action)}>
                        {qs(action)}
                      </button>
                    )
                  )}
                </ButtonRow>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
