"use client";

import { useState } from "react";
import { UserRound } from "lucide-react";

import { ButtonRow } from "@/components/ui/ButtonRow";

type Held = "otherAccount" | "unowned";

export function outboxHoldTitle(which: Held, count: number): string {
  const entries = `${count} ${count === 1 ? "entry" : "entries"}`;
  return which === "otherAccount" ? `${entries} from another account` : `${entries} saved without an account`;
}

export function outboxHoldBody(which: Held): string {
  return which === "otherAccount"
    ? "Kept on this device. They send when that account signs in."
    : "Send them as you only if you captured them.";
}

export function OutboxAccountHold({
  otherAccount,
  unowned,
  busy,
  onClaim,
  onDiscard
}: {
  otherAccount: number;
  unowned: number;
  busy?: boolean;
  onClaim: () => void;
  onDiscard: (which: Held) => void;
}) {
  const [kept, setKept] = useState(false);
  const [confirming, setConfirming] = useState<Held | null>(null);
  const rows: Held[] = [];
  if (otherAccount && !kept) rows.push("otherAccount");
  if (unowned) rows.push("unowned");
  if (!rows.length) return null;

  return (
    <div className="grid gap-2" data-outbox-hold>
      {rows.map((which) => {
        const count = which === "otherAccount" ? otherAccount : unowned;
        return (
          <div
            key={which}
            data-outbox-hold-row={which}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
          >
            <div className="flex min-w-0 items-start gap-2">
              <UserRound className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
              <div className="min-w-0">
                <p className="text-sm font-medium">
                  {confirming === which ? `Discard ${count === 1 ? "this entry" : `these ${count} entries`}?` : outboxHoldTitle(which, count)}
                </p>
                <p className="text-xs text-ink-700">
                  {confirming === which ? "They were never sent and can't be brought back." : outboxHoldBody(which)}
                </p>
              </div>
            </div>
            {confirming === which ? (
              <ButtonRow size="sm" align="end">
                <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setConfirming(null)}>
                  Keep
                </button>
                <button
                  type="button"
                  className="field-button-secondary field-danger"
                  disabled={busy}
                  onClick={() => {
                    setConfirming(null);
                    onDiscard(which);
                  }}
                >
                  Discard
                </button>
              </ButtonRow>
            ) : (
              <ButtonRow size="sm" align="end">
                {which === "otherAccount" ? (
                  <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setKept(true)}>
                    Keep for them
                  </button>
                ) : (
                  <button type="button" className="field-button-secondary" disabled={busy} onClick={onClaim}>
                    Send as me
                  </button>
                )}
                <button
                  type="button"
                  className="field-button-secondary field-danger"
                  disabled={busy}
                  onClick={() => setConfirming(which)}
                >
                  Discard
                </button>
              </ButtonRow>
            )}
          </div>
        );
      })}
    </div>
  );
}
