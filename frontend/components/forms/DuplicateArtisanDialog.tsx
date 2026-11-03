"use client";

import { AlertTriangle } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import type { ArtisanIdentityMatch } from "@/lib/types";

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
  artisan?: ArtisanIdentityMatch | null;
  message?: string | null;
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
          {artisan ? (
            <button type="button" className="field-button" onClick={onOpenExisting}>
              Open the existing record
            </button>
          ) : null}
        </>
      }
    >
      <p className="mt-2 text-xs leading-5 text-amber-800">
        Nothing was saved.
      </p>
    </FieldDialog>
  );
}
