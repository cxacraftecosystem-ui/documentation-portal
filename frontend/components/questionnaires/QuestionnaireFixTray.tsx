"use client";

/**
 * The questionnaire outbox's fix tray (UNIFY B39) under the sessions list: what has not reached the
 * server from this device, and Send again, Send without the conflicting answers, or Discard. Drawn
 * only while something is stuck.
 */

import { AlertTriangle } from "lucide-react";
import { useState } from "react";

import { useConfirm } from "@/components/dialogs";
import { drainQueue, useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { indexedDbQueueStore, memoryQueueStore, type QueueStore } from "@/lib/questionnaire/queue";
import { discardStep, sendAgain, sendWithoutConflicts, stuckSteps, trayActions, TRAY_COPY, type StuckStep } from "@/lib/questionnaireFixTray";

let store: QueueStore | null = null;
function queueStore(): QueueStore {
  store ??= typeof indexedDB === "undefined" ? memoryQueueStore() : indexedDbQueueStore();
  return store;
}

export function QuestionnaireFixTray() {
  const { units, online } = useSessionQueue();
  const confirm = useConfirm();
  const [busy, setBusy] = useState<string | null>(null);
  const stuck = stuckSteps(units);
  if (!stuck.length) return null;

  async function act(step: StuckStep, action: "sendAgain" | "withoutConflicts" | "discard") {
    if (action === "discard") {
      const ok = await confirm({ title: TRAY_COPY.discardTitle, body: TRAY_COPY.discardBody, tone: "danger", confirmLabel: TRAY_COPY.discard });
      if (!ok) return;
    }
    setBusy(`${step.unitId}:${step.key}`);
    try {
      if (action === "sendAgain") await sendAgain(queueStore(), step.unitId, step.key);
      else if (action === "withoutConflicts") await sendWithoutConflicts(queueStore(), step.unitId, step.key);
      else await discardStep(queueStore(), step.unitId, step.key);
      await drainQueue();
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel mt-5 grid gap-3 border-amber-500/30 p-4" aria-labelledby="fix-tray-heading" data-fix-tray>
      <h2 id="fix-tray-heading" className="flex items-center gap-2 font-display text-lg font-bold text-ink-900">
        <AlertTriangle className="h-4 w-4 text-amber-800" aria-hidden /> {TRAY_COPY.title}
      </h2>
      <ul className="divide-y divide-line-200 rounded-md border border-line-200">
        {stuck.map((step) => {
          const id = `${step.unitId}:${step.key}`;
          return (
            <li key={id} className="flex flex-col gap-2 p-3 sm:flex-row sm:items-center sm:justify-between" data-stuck-step={step.key}>
              <div className="min-w-0">
                <p className="font-medium text-ink-900">{step.label}</p>
                <p className="mt-0.5 text-xs text-ink-500">{step.reason}</p>
              </div>
              <ButtonRow
                align="end"
                size="sm"
                destructive={
                  <button type="button" className="field-button-secondary field-danger" disabled={busy === id} onClick={() => void act(step, "discard")}>
                    {TRAY_COPY.discard}
                  </button>
                }
              >
                {trayActions(step)
                  .filter((a) => a !== "discard")
                  .map((action) => (
                    <button
                      key={action}
                      type="button"
                      className="field-button-secondary"
                      disabled={busy === id || !online}
                      title={online ? undefined : "Needs a connection"}
                      onClick={() => void act(step, action)}
                    >
                      {action === "sendAgain" ? TRAY_COPY.sendAgain : TRAY_COPY.withoutConflicts}
                    </button>
                  ))}
              </ButtonRow>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
