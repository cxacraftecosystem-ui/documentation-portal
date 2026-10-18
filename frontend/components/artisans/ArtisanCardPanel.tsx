"use client";

/**
 * The artisan's QR card: a preview of the printed face, then Print card (ID-1), Print A6 and Reissue.
 *
 * Draws nothing until `GET /artisan-cards/status` says cards are on. The QR is the server's own SVG
 * (`qrSvg`, the symbol the PDF prints), shown through an `<img>` so no markup from the wire is put
 * into the page. The QR holds an opaque signed token, never the record id; the face prints the last
 * four Aadhaar digits only. Reissue revokes the live card (the old one then reads "replaced") and
 * asks why first. The server decides who may reissue; its refusal is shown as it says it.
 */
import { useEffect, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import {
  artisanCardStatus,
  downloadArtisanCard,
  getArtisanCard,
  maskedLast4,
  qrImageSrc,
  reissueArtisanCard,
  type ArtisanCard,
  type CardSize,
  type ReissueReason
} from "@/lib/artisanCardApi";

export const REISSUE_REASONS: { value: ReissueReason; label: string }[] = [
  { value: "LOST", label: "Lost" },
  { value: "DAMAGED", label: "Damaged" },
  { value: "OTHER", label: "Other" }
];

const messageOf = (error: unknown, fallback: string) => (error instanceof Error && error.message.trim() ? error.message : fallback);

export function ArtisanCardPanel({ artisanId, canReissue }: { artisanId: string; canReissue: boolean }) {
  const [enabled, setEnabled] = useState(false);
  const [card, setCard] = useState<ArtisanCard | null>(null);
  const [photo, setPhoto] = useState(true);
  const [busy, setBusy] = useState<null | CardSize | "reissue">(null);
  const [note, setNote] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState<ReissueReason | "">("");

  useEffect(() => {
    let cancelled = false;
    void artisanCardStatus().then((status) => {
      if (cancelled || !status.enabled) return;
      setEnabled(true);
      getArtisanCard(artisanId)
        .then((answer) => {
          if (!cancelled) setCard(answer);
        })
        .catch((error) => {
          if (!cancelled) setNote(messageOf(error, "The card couldn't be loaded. Try again."));
        });
    });
    return () => {
      cancelled = true;
    };
  }, [artisanId]);

  if (!enabled) return null;

  async function print(size: CardSize) {
    setBusy(size);
    setNote(null);
    try {
      await downloadArtisanCard(artisanId, size, photo);
    } catch (error) {
      setNote(messageOf(error, "The card couldn't be printed. Try again."));
    } finally {
      setBusy(null);
    }
  }

  function closeDialog() {
    setAsking(false);
    setReason("");
  }

  async function reissue() {
    if (!reason) return;
    setBusy("reissue");
    setNote(null);
    try {
      setCard(await reissueArtisanCard(artisanId, reason));
      closeDialog();
      setNote("New card issued. The old one no longer opens this artisan.");
    } catch (error) {
      setNote(messageOf(error, "The card couldn't be reissued. Try again."));
    } finally {
      setBusy(null);
    }
  }

  const last4 = maskedLast4(card?.face.aadhaarLast4);

  return (
    <section className="panel grid gap-4 p-4" aria-label="Artisan card" data-testid="artisan-card-panel">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
        <div className="grid h-36 w-36 shrink-0 place-items-center self-center rounded-md border border-line-200 bg-white p-1">
          {card ? (
            // eslint-disable-next-line @next/next/no-img-element -- a data: URI of the server's SVG, nothing to optimise
            <img src={qrImageSrc(card.qrSvg)} alt={`QR code for card ${card.shortCode}`} className="h-full w-full" />
          ) : (
            <span className="text-xs text-ink-muted">Loading…</span>
          )}
        </div>
        <div className="grid min-w-0 gap-1">
          <p className="field-label">Artisan card</p>
          <p className="font-mono text-lg font-semibold tracking-wide text-ink-900" data-testid="artisan-card-short-code">
            {card?.shortCode ?? "…"}
          </p>
          {card ? (
            <p className="text-sm text-ink-700">{[card.face.craft, card.face.cluster].filter(Boolean).join(" · ") || card.face.name}</p>
          ) : null}
          {last4 ? <p className="text-sm text-ink-muted">Aadhaar {last4}</p> : null}
          {card && card.replacedCards > 0 ? (
            <p className="text-xs text-ink-muted">
              Replaces {card.replacedCards === 1 ? "1 earlier card" : `${card.replacedCards} earlier cards`}.
            </p>
          ) : null}
        </div>
      </div>
      <Toggle label="Include photo" checked={photo} onChange={setPhoto} disabled={busy !== null} />
      <ButtonRow
        label="Card actions"
        destructive={
          canReissue ? (
            <button type="button" className="field-danger disabled:opacity-50" disabled={!card || busy !== null} onClick={() => setAsking(true)}>
              Reissue
            </button>
          ) : null
        }
      >
        <button type="button" className="field-button" disabled={!card || busy !== null} onClick={() => void print("id1")}>
          {busy === "id1" ? "Preparing…" : "Print card"}
        </button>
        <button type="button" className="field-button-secondary" disabled={!card || busy !== null} onClick={() => void print("a6")}>
          {busy === "a6" ? "Preparing…" : "Print A6"}
        </button>
      </ButtonRow>
      {note ? (
        <p role="status" className="text-sm text-ink-muted">
          {note}
        </p>
      ) : null}
      <FieldDialog
        open={asking}
        onClose={closeDialog}
        busy={busy === "reissue"}
        tone="danger"
        title="Reissue this card?"
        description="The current card stops opening this artisan."
        footer={
          <>
            <button type="button" className="field-button-secondary" disabled={busy === "reissue"} onClick={closeDialog}>
              Cancel
            </button>
            <button type="button" className="field-danger disabled:opacity-50" disabled={!reason || busy === "reissue"} onClick={() => void reissue()}>
              {busy === "reissue" ? "Reissuing…" : "Reissue"}
            </button>
          </>
        }
      >
        <SearchableSelect
          value={reason}
          onChange={(value) => setReason(value as ReissueReason)}
          options={REISSUE_REASONS}
          placeholder="Why?"
          ariaLabel="Reason for reissuing"
          advanceOnSelect={false}
        />
      </FieldDialog>
    </section>
  );
}
