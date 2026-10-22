"use client";

import Link from "next/link";
import { useState } from "react";

import { PromptDialog } from "@/components/questionnaire/Prompts";
import { trayAct, useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { qs } from "@/lib/questionnaire/qs";
import { trayItems, type SessionUnit, type Step, type TrayItem } from "@/lib/questionnaire/queue";

/**
 * The outbox fix tray (UNIFY B39): what is waiting for a PERSON rather than for a connection.
 *
 * A step the server refused (FAILED) offers Send again and Discard. Answers someone else changed
 * meanwhile offer Decide each (P9, in the session's workspace), Send without the conflicting
 * answers (theirs stand, the rest go up) and Discard. Discard asks first: what was never sent
 * cannot be brought back. Nothing here is drawn while the outbox only waits for signal.
 *
 * `sessionId` narrows the tray to one session's unit (the workspace); without it every session's.
 */
export function OutboxTray({ sessionId }: { sessionId?: string | null }) {
  const queue = useSessionQueue();
  const [confirm, setConfirm] = useState<TrayItem | null>(null);
  const [busy, setBusy] = useState(false);
  const units = sessionId ? queue.units.filter((u) => u.id === sessionId || u.sessionId === sessionId) : queue.units;
  const items = trayItems(units);
  if (!items.length) return null;

  async function act(item: TrayItem, what: "again" | "discard" | "without") {
    setBusy(true);
    try {
      await trayAct(item.unitId, item.stepKey, what);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel mb-4 grid gap-3 p-4" data-outbox-tray aria-label={qs("outboxTitle")}>
      <h2 className="font-display text-base font-bold text-ink-900">{qs("outboxTitle")}</h2>
      <ul className="grid gap-3">
        {items.map((item) => {
          const unit = queue.units.find((u) => u.id === item.unitId);
          return (
            <li key={`${item.unitId}|${item.stepKey}`} className="grid gap-2 rounded-md border border-line-200 p-3" data-tray-item={item.state}>
              <div className="grid gap-0.5 text-sm">
                <span className="font-medium text-ink-900">{stepLabel(item.step)}</span>
                <span className="text-ink-600">
                  {item.state === "failed"
                    ? qs("outboxNotSent", { error: item.step.error || qs("outboxWaiting") })
                    : qs("outboxConflicts", { count: item.step.kind === "answers" ? (item.step.conflicts?.length ?? 0) : 0 })}
                </span>
              </div>
              {item.state === "failed" ? (
                <ButtonRow size="sm" align="start">
                  <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void act(item, "again")}>
                    {qs("outboxSendAgain")}
                  </button>
                  <button type="button" className="field-danger" disabled={busy} onClick={() => setConfirm(item)}>
                    {qs("discard")}
                  </button>
                </ButtonRow>
              ) : (
                <div className="grid gap-2 sm:max-w-sm">
                  {sessionHref(unit) ? (
                    <Link className="field-button-secondary" href={sessionHref(unit)!}>
                      {qs("outboxDecideEach")}
                    </Link>
                  ) : null}
                  <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void act(item, "without")}>
                    {qs("outboxSendWithout")}
                  </button>
                  <button type="button" className="field-danger" disabled={busy} onClick={() => setConfirm(item)}>
                    {qs("discard")}
                  </button>
                </div>
              )}
            </li>
          );
        })}
      </ul>
      {confirm ? (
        <PromptDialog
          open
          spec={{
            id: "outboxDiscard",
            title: qs("outboxDiscardTitle"),
            body: qs("outboxDiscardBody"),
            buttons: [
              { id: "keep", label: qs("outboxKeep"), tone: "secondary" },
              { id: "discard", label: qs("discard"), tone: "danger" }
            ],
            dismiss: "keep"
          }}
          onChoose={(choice) => {
            const item = confirm;
            setConfirm(null);
            if (choice === "discard") void act(item, "discard");
          }}
        />
      ) : null}
    </section>
  );
}

function sessionHref(unit: SessionUnit | undefined): string | null {
  const id = unit?.sessionId ?? unit?.id;
  return id ? `/questionnaire/sessions/${encodeURIComponent(id)}` : null;
}

function stepLabel(step: Step): string {
  switch (step.kind) {
    case "session":
      return qs("outboxStepSession");
    case "clip":
      return qs("outboxStepTake", { label: step.label || step.extension });
    case "answers":
      return qs("outboxStepAnswers", { count: step.items.length });
    default:
      return qs("outboxStepParticipant");
  }
}
