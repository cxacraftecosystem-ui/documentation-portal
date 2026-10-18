"use client";

import { Users } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { candidateSubtitle, type PossibleDuplicateHold } from "@/lib/duplicates";

/**
 * The save was held: the new artisan looks like one already recorded (409
 * `artisan_possible_duplicate`). Two ways forward — keep editing (the default, loses nothing) or say
 * it is a different person, which saves with those records acknowledged. Each candidate opens in a
 * new tab so the form stays as it is.
 */
export function PossibleDuplicateDialog({
  hold,
  busy = false,
  onKeepEditing,
  onDifferent
}: {
  hold: PossibleDuplicateHold | null;
  busy?: boolean;
  onKeepEditing: () => void;
  onDifferent: () => void;
}) {
  const keepRef = useRef<HTMLButtonElement | null>(null);
  return (
    <FieldDialog
      open={Boolean(hold)}
      onClose={onKeepEditing}
      role="alertdialog"
      tone="warning"
      icon={<Users className="h-4 w-4" aria-hidden />}
      showClose={false}
      dismissOnBackdrop
      busy={busy}
      initialFocusRef={keepRef}
      title="Already recorded?"
      description={hold?.message ?? null}
      footer={
        <>
          <button type="button" ref={keepRef} className="field-button-secondary" onClick={onKeepEditing}>
            Keep editing
          </button>
          <button type="button" className="field-button" onClick={onDifferent} disabled={busy}>
            Different person — save
          </button>
        </>
      }
    >
      <ul className="mt-3 grid gap-2">
        {(hold?.candidates ?? []).slice(0, 3).map((candidate) => (
          <li
            key={candidate.artisan.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
          >
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold text-ink-900">{candidate.artisan.name}</div>
              {candidateSubtitle(candidate) ? (
                <div className="truncate text-xs text-ink-500">{candidateSubtitle(candidate)}</div>
              ) : null}
              <div className="mt-1 flex flex-wrap gap-1">
                {candidate.reasons.map((reason) => (
                  <span
                    key={reason.code}
                    className="inline-flex items-center rounded-full border border-line-200 bg-card px-2 py-0.5 text-[11px] font-medium text-ink-700"
                  >
                    {reason.label}
                  </span>
                ))}
              </div>
            </div>
            <a
              className="field-button-secondary shrink-0"
              href={`/artisans/${encodeURIComponent(candidate.artisan.id)}`}
              target="_blank"
              rel="noopener"
            >
              Open
            </a>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-xs text-ink-500">Nothing was saved.</p>
    </FieldDialog>
  );
}
