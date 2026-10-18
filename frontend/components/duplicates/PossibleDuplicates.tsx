"use client";

import { Users } from "lucide-react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { candidateSubtitle, type DuplicateCandidate } from "@/lib/duplicates";

/**
 * "Already recorded?" — the likely duplicates of the artisan being typed.
 *
 * The server scored them (`POST /artisan-duplicates/check`, or the 409 hold on save); this only
 * lists them. Each row opens the existing record in a new tab, so the form being filled is never
 * lost to a look. "Different person" dismisses every row shown, and those ids travel with the save
 * as `duplicateAcknowledged`, which is what stops the server asking again.
 *
 * Amber like the identity-conflict dialog: nothing went wrong, the register recognised somebody.
 */
export function PossibleDuplicates({
  candidates,
  onDifferent,
  busy = false,
  className = ""
}: {
  candidates: DuplicateCandidate[];
  onDifferent: () => void;
  busy?: boolean;
  className?: string;
}) {
  if (!candidates.length) return null;
  return (
    <section
      aria-label="Already recorded?"
      className={`rounded-lg border border-amber-500/40 bg-amber-100/60 p-3 text-amber-800 ${className}`}
      data-testid="possible-duplicates"
    >
      <div className="flex items-center gap-2">
        <Users className="h-4 w-4 shrink-0" aria-hidden />
        <h2 className="font-display text-sm font-bold">Already recorded?</h2>
      </div>
      <ul className="mt-2 grid gap-2">
        {candidates.map((candidate) => (
          <li
            key={candidate.artisan.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-card px-3 py-2 text-ink-900"
          >
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold">{candidate.artisan.name}</div>
              {candidateSubtitle(candidate) ? (
                <div className="truncate text-xs text-ink-500">{candidateSubtitle(candidate)}</div>
              ) : null}
              <div className="mt-1 flex flex-wrap gap-1">
                {candidate.reasons.map((reason) => (
                  <span
                    key={reason.code}
                    className="inline-flex items-center rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] font-medium text-ink-700"
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
      <ButtonRow className="mt-3">
        <button type="button" className="field-button-secondary" onClick={onDifferent} disabled={busy}>
          Different person
        </button>
      </ButtonRow>
    </section>
  );
}
