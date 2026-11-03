"use client";

import { useEffect, useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { Toggle } from "@/components/ui/Toggle";
import { artisanCardStatus, CARD_SHEET_LIMIT, downloadArtisanCardSheet, type CardSheetRequest, type CardSize } from "@/lib/artisanCardApi";

export function useArtisanCardsEnabled(): boolean {
  const [enabled, setEnabled] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void artisanCardStatus().then((status) => {
      if (!cancelled) setEnabled(status.enabled);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return enabled;
}

export function ArtisanCardSheetActions({
  source,
  primaryLabel = "Print cards",
  count
}: {
  source: CardSheetRequest | null;
  primaryLabel?: string;
  count?: number;
}) {
  const enabled = useArtisanCardsEnabled();
  const [photo, setPhoto] = useState(true);
  const [busy, setBusy] = useState<CardSize | null>(null);
  const [note, setNote] = useState<string | null>(null);

  if (!enabled) return null;

  const tooMany = typeof count === "number" && count > CARD_SHEET_LIMIT;
  const held = !source || busy !== null || tooMany || count === 0;

  async function print(size: CardSize) {
    if (!source) return;
    setBusy(size);
    setNote(null);
    try {
      await downloadArtisanCardSheet(source, size, photo);
    } catch (error) {
      setNote(error instanceof Error && error.message.trim() ? error.message : "The cards couldn't be printed. Try again.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="grid gap-2" data-testid="artisan-card-sheet-actions">
      <Toggle label="Include photos" checked={photo} onChange={setPhoto} disabled={busy !== null} />
      <ButtonRow align="start" label="Print artisan cards">
        <button type="button" className="field-button" disabled={held} onClick={() => void print("id1")}>
          {busy === "id1" ? "Preparing…" : primaryLabel}
        </button>
        <button type="button" className="field-button-secondary" disabled={held} onClick={() => void print("a6")}>
          {busy === "a6" ? "Preparing…" : "Print A6"}
        </button>
      </ButtonRow>
      {tooMany ? <p className="text-sm text-ink-muted">Print at most {CARD_SHEET_LIMIT} cards at a time.</p> : null}
      {note ? (
        <p role="status" className="text-sm text-ink-muted">
          {note}
        </p>
      ) : null}
    </div>
  );
}
