"use client";

import { AlertTriangle } from "lucide-react";
import { useEffect, useId } from "react";

import type { ArtisanIdentityMatch } from "@/lib/types";

/**
 * "This artisan is already in the repository" — the decision point at the end of the Aadhaar
 * de-duplication path.
 *
 * `AadhaarField` already whispers the same fact inline while the number is being typed, and that is
 * deliberately only a whisper: a researcher mid-form may still be correcting a digit, and shouting
 * at them then would be wrong. The moment they press Save, though, the whisper has to become a
 * question, because from here the three ways forward are genuinely different pieces of work and only
 * the person in the room knows which one applies:
 *
 * - **Open the existing record** — same person, already documented. Their answers belong on that
 *   record, so go there rather than creating a second one.
 * - **Discard this entry** — the form was started by mistake (a second researcher covering an
 *   artisan a colleague already did). Throw it away rather than leaving a half-record behind.
 * - **Keep editing** — the number was mistyped, or this really is somebody else. Stay put and fix it.
 *
 * The same dialog serves the pre-flight lookup and the server's HTTP 409, so a duplicate looks
 * identical whether it is caught before the request or by the unique index behind it.
 *
 * Amber, not red: nothing has gone wrong and nothing was lost — the repository just recognised
 * somebody. Red is reserved for failures the researcher has to recover from.
 */
export function DuplicateArtisanDialog({
  open,
  artisan,
  message,
  maskedValue,
  onOpenExisting,
  onDiscard,
  onKeepEditing
}: {
  open: boolean;
  /** The artisan who already holds the number, when the server named one. */
  artisan?: ArtisanIdentityMatch | null;
  /** The server's own sentence when it refused the save; a default is used for the pre-flight catch. */
  message?: string | null;
  /** e.g. "XXXX XXXX 9012" — enough to check the card against without exposing the number. */
  maskedValue?: string | null;
  onOpenExisting: () => void;
  onDiscard: () => void;
  onKeepEditing: () => void;
}) {
  const titleId = useId();
  const bodyId = `${titleId}-body`;

  // Escape is "Keep editing": the least destructive of the three, and the one a reflex press means.
  useEffect(() => {
    if (!open) return;
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onKeepEditing();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onKeepEditing]);

  if (!open) return null;

  const description = [artisan?.name, artisan?.place, artisan?.craft, artisan?.workshop].filter(Boolean).join(" · ");

  return (
    <div
      className="fixed inset-0 z-[100] grid place-items-center bg-ink-900/40 p-4"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onKeepEditing();
      }}
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        className="w-full max-w-md rounded-xl border border-amber-500 bg-amber-100 p-5 text-amber-800 shadow-lg"
      >
        <div className="flex items-start gap-3">
          <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden />
          <div className="min-w-0">
            <h2 id={titleId} className="font-display text-lg font-bold text-amber-800">
              This artisan already exists
            </h2>
            <p id={bodyId} className="mt-2 text-sm leading-6">
              {message?.trim() || "Another artisan record already holds this identity number."}
            </p>
            {description ? <p className="mt-2 text-sm font-medium leading-6">{description}</p> : null}
            {maskedValue ? <p className="mt-1 text-xs">Number on file: {maskedValue}</p> : null}
            <p className="mt-2 text-xs leading-5">
              Nothing has been saved. Open the record that already exists, throw this entry away, or stay here and
              correct the number.
            </p>
          </div>
        </div>
        <div className="mt-5 grid gap-2 sm:flex sm:flex-wrap sm:justify-end">
          <button type="button" className="field-button-secondary" onClick={onKeepEditing}>
            Keep editing
          </button>
          <button type="button" className="field-danger" onClick={onDiscard}>
            Discard this entry
          </button>
          {/* Only offered when the server named the record — a link to nowhere is worse than no link. */}
          {artisan ? (
            <button type="button" className="field-button" onClick={onOpenExisting}>
              Open the existing record
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
