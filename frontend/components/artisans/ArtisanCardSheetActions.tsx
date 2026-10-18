"use client";

/**
 * Bulk printing of artisan cards: one PDF from the server (`POST /artisan-cards/sheet.pdf`), ID-1
 * cards ten to an A4 sheet with crop marks, or one A6 card per page. Used by the artisans list (the
 * ticked rows) and a workshop's page (its roster).
 *
 * Draws nothing while cards are off. All or nothing: the server refuses a request naming any artisan
 * it can't print, and that sentence is shown as it comes.
 */
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
  /** What to print; null while nothing is chosen. */
  source: CardSheetRequest | null;
  primaryLabel?: string;
  /** How many cards the source holds, when known: past the limit the buttons hold and say why. */
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
