"use client";

import { useEffect, useState } from "react";
import { AudioLines, ImageDown, Loader2, X } from "lucide-react";

import { errorCode, makeQuoteCard, saveBlob } from "@/lib/transcript/api";
import type { QuoteCard, TranscriptTurn } from "@/lib/transcript/contract";
import { QUOTE_CARD_WHAT, QUOTE_CLIP_WHAT, quoteFileName } from "@/lib/transcript/names";
import { ts } from "@/lib/transcript/strings";

import { Actions, ICON_BUTTON, PRIMARY, SECONDARY } from "./parts";

const WIDTH = 1200;
const PAD = 72;

/**
 * Quote cards (design §10.5). The server assembles the card's words from the chosen turns (the
 * original, the English, the artisan's name, the craft and the date) and refuses with
 * CONSENT_REFUSED when a quoted artisan withdrew; the image is drawn here, on a canvas, from that
 * answer, and the audio clip of those turns comes from the url the server signs.
 */
export function QuoteCardBuilder({ mediaId, turns, onClose }: { mediaId: string; turns: TranscriptTurn[]; onClose: () => void }) {
  const [card, setCard] = useState<QuoteCard | null>(null);
  const [image, setImage] = useState<{ url: string; blob: Blob } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ids = turns.map((turn) => turn.id).join(",");

  useEffect(() => {
    let cancelled = false;
    setCard(null);
    setError(null);
    makeQuoteCard(mediaId, ids.split(","))
      .then((answer) => {
        if (!cancelled) setCard(answer);
      })
      .catch((failure) => {
        if (cancelled) return;
        setError(errorCode(failure) === "CONSENT_REFUSED" ? ts("quoteCardWithdrawn") : failure instanceof Error ? failure.message : ts("loadFailed"));
      });
    return () => {
      cancelled = true;
    };
  }, [mediaId, ids]);

  useEffect(() => {
    if (!card) return;
    let url: string | null = null;
    let cancelled = false;
    void drawCard(card).then((blob) => {
      if (cancelled || !blob) return;
      url = URL.createObjectURL(blob);
      setImage({ url, blob });
    });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [card]);

  const subject = card?.artisanName || card?.speakerName || null;


  return (
    <div className="grid gap-3 rounded-lg border border-line-200 bg-surface-50 p-3 sm:p-4" data-quote-card>
      <div className="flex items-center justify-between gap-2">
        <h3 className="font-display text-sm font-bold text-ink-900">{ts("quotePreview")}</h3>
        <button type="button" className={ICON_BUTTON} onClick={onClose} aria-label={ts("close")}>
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>
      {error ? (
        <p className="rounded-md bg-amber-100 px-3 py-2 text-sm text-amber-800" role="alert">
          {error}
        </p>
      ) : image && card ? (
        // eslint-disable-next-line @next/next/no-img-element -- a blob drawn on this device
        <img src={image.url} alt={`${card.original} — ${subject ?? ""}`} className="w-full rounded-md border border-line-200" data-quote-image />
      ) : (
        <div className="grid aspect-[1200/630] w-full place-items-center rounded-md bg-field-100" aria-hidden>
          <Loader2 className="h-5 w-5 animate-spin text-ink-300 motion-reduce:animate-none" />
        </div>
      )}
      {card && !error ? (
        <Actions>
          <button type="button" className={PRIMARY} disabled={!image} onClick={() => image && saveBlob(image.blob, quoteFileName(QUOTE_CARD_WHAT, "png", subject, card?.craft))}>
            <ImageDown className="h-4 w-4" aria-hidden />
            {ts("quoteCardDownload")}
          </button>
          {card.clip.url ? (
            <a className={SECONDARY} href={card.clip.url} download={quoteFileName(QUOTE_CLIP_WHAT, "m4a", subject, card?.craft)} target="_blank" rel="noopener noreferrer">
              <AudioLines className="h-4 w-4" aria-hidden />
              {ts("quoteCardClip")}
            </a>
          ) : null}
        </Actions>
      ) : null}
    </div>
  );
}

function wrap(context: CanvasRenderingContext2D, text: string, width: number): string[] {
  const lines: string[] = [];
  for (const paragraph of text.split("\n")) {
    let line = "";
    for (const word of paragraph.split(/\s+/).filter(Boolean)) {
      const next = line ? `${line} ${word}` : word;
      if (context.measureText(next).width > width && line) {
        lines.push(line);
        line = word;
      } else line = next;
    }
    if (line) lines.push(line);
  }
  return lines;
}

/** The page's own face (next/font names it), so the card reads like the app. */
function fontFamily(): string {
  return getComputedStyle(document.body).fontFamily || "sans-serif";
}

async function drawCard(card: QuoteCard): Promise<Blob | null> {
  const canvas = document.createElement("canvas");
  const context = canvas.getContext("2d");
  if (!context) return null;
  const family = fontFamily();
  const inner = WIDTH - PAD * 2;
  context.font = `600 44px ${family}`;
  const original = wrap(context, `“${card.original}”`, inner).slice(0, 9);
  context.font = `400 30px ${family}`;
  const english = card.english && card.english !== card.original ? wrap(context, card.english, inner).slice(0, 9) : [];
  const height = Math.max(630, PAD * 2 + original.length * 60 + (english.length ? 32 + english.length * 42 : 0) + 140);
  canvas.width = WIDTH;
  canvas.height = height;

  context.fillStyle = "#faf9fd";
  context.fillRect(0, 0, WIDTH, height);
  context.fillStyle = "#762cb1";
  context.fillRect(0, 0, 14, height);

  let y = PAD + 44;
  context.fillStyle = "#1e1b2e";
  context.font = `600 44px ${family}`;
  for (const line of original) {
    context.fillText(line, PAD, y);
    y += 60;
  }
  if (english.length) {
    y += 32;
    context.fillStyle = "#3a3651";
    context.font = `400 30px ${family}`;
    for (const line of english) {
      context.fillText(line, PAD, y);
      y += 42;
    }
  }
  context.fillStyle = "#762cb1";
  context.font = `700 28px ${family}`;
  context.fillText(card.artisanName || card.speakerName, PAD, height - PAD - 34);
  context.fillStyle = "#615d7a";
  context.font = `400 24px ${family}`;
  context.fillText([card.craft, card.date].filter(Boolean).join(" · "), PAD, height - PAD);

  return new Promise((resolve) => canvas.toBlob((blob) => resolve(blob), "image/png"));
}
