"use client";

import { AlertTriangle } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import type { ArtisanIdentityMatch } from "@/lib/types";

/**
 * "This artisan already exists" — the decision point at the end of the Aadhaar de-duplication path.
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
 * somebody. Red is reserved for failures the researcher has to recover from. The amber wash IS the
 * message here, which is why this is the one dialog that overrides the shared card surface
 * (`surfaceClassName`) instead of settling for the amber tone ring.
 *
 * Three ways forward, so no corner X, and Escape means "Keep editing" — the least destructive of the
 * three and the one a reflex press intends.
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
  const keepEditingRef = useRef<HTMLButtonElement | null>(null);
  const description = [artisan?.name, artisan?.place, artisan?.craft, artisan?.workshop].filter(Boolean).join(" · ");

  return (
    <FieldDialog
      open={open}
      onClose={onKeepEditing}
      role="alertdialog"
      tone="warning"
      icon={<AlertTriangle className="h-4 w-4" aria-hidden />}
      showClose={false}
      // Backing out to the form loses nothing, so a click outside is allowed to mean "keep editing".
      dismissOnBackdrop
      initialFocusRef={keepEditingRef}
      surfaceClassName="border-amber-500 bg-amber-100"
      title={<span className="text-amber-800">This artisan already exists</span>}
      description={
        <div className="text-amber-800">
          <p>{message?.trim() || "Another artisan record already holds this identity number."}</p>
          {description ? <p className="mt-2 font-medium">{description}</p> : null}
          {maskedValue ? <p className="mt-1 text-xs">Number on file: {maskedValue}</p> : null}
        </div>
      }
      footer={
        <>
          <button type="button" ref={keepEditingRef} className="field-button-secondary" onClick={onKeepEditing}>
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
        </>
      }
    >
      <p className="mt-2 text-xs leading-5 text-amber-800">
        Nothing has been saved. Open the record that already exists, throw this entry away, or stay here and
        correct the number.
      </p>
    </FieldDialog>
  );
}
