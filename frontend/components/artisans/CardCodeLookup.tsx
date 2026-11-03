"use client";

import { useState } from "react";

import { useArtisanCardsEnabled } from "@/components/artisans/ArtisanCardSheetActions";
import { CARD_CHECK_MESSAGE, looksLikeArtisanCard, parseArtisanCard } from "@/lib/artisanCard";
import { resolveArtisanCard, type CardArtisanSummary } from "@/lib/artisanCardApi";

export const CARD_CODE_PLACEHOLDER = "F-XXXXX-XXXXX-XX";

export type CardLookupOutcome =
  | { kind: "found"; artisan: CardArtisanSummary }
  | { kind: "replaced"; artisanId: string }
  | { kind: "error"; message: string };

export async function lookUpCard(input: string, resolve = resolveArtisanCard): Promise<CardLookupOutcome> {
  const typed = input.trim();
  if (!typed) return { kind: "error", message: "Type the code under the QR." };
  const parsed = parseArtisanCard(typed);
  if (!parsed.ok) {
    return { kind: "error", message: looksLikeArtisanCard(typed) ? CARD_CHECK_MESSAGE : "That isn't an artisan card code." };
  }
  try {
    const answer = await resolve(typed);
    if (answer.status === "FOUND") return { kind: "found", artisan: answer.artisan };
    return { kind: "replaced", artisanId: answer.artisanId };
  } catch (error) {
    return { kind: "error", message: error instanceof Error && error.message.trim() ? error.message : "No artisan matches this card." };
  }
}

export function CardCodeLookup({
  onFound,
  onOpenReplaced,
  actionLabel = "Open",
  compact = false
}: {
  onFound: (artisan: CardArtisanSummary) => void;
  onOpenReplaced?: (artisanId: string) => void;
  actionLabel?: string;
  compact?: boolean;
}) {
  const enabled = useArtisanCardsEnabled();
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [replaced, setReplaced] = useState<string | null>(null);

  if (!enabled) return null;

  async function submit() {
    setBusy(true);
    setMessage(null);
    setReplaced(null);
    const outcome = await lookUpCard(code);
    setBusy(false);
    if (outcome.kind === "found") {
      setCode("");
      onFound(outcome.artisan);
    } else if (outcome.kind === "replaced") {
      setReplaced(outcome.artisanId);
    } else {
      setMessage(outcome.message);
    }
  }

  return (
    <div className={compact ? "mt-2 grid gap-1.5" : "panel mb-5 grid gap-2 p-4"} data-testid="card-code-lookup">
      <label className="grid gap-1.5">
        <span className="field-label">Card code</span>
        <span className="flex gap-2">
          <input
            className="field-input min-w-0 font-mono uppercase tracking-wide"
            value={code}
            placeholder={CARD_CODE_PLACEHOLDER}
            autoComplete="off"
            spellCheck={false}
            maxLength={64}
            onChange={(event) => setCode(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void submit();
              }
            }}
          />
          <button type="button" className="field-button-secondary shrink-0" disabled={busy || !code.trim()} onClick={() => void submit()}>
            {busy ? "Checking…" : actionLabel}
          </button>
        </span>
      </label>
      {message ? (
        <p role="alert" className="text-sm text-red-700 dark:text-red-300">
          {message}
        </p>
      ) : null}
      {replaced ? (
        <div className="grid gap-2" role="status">
          <p className="text-sm text-ink-700">This card was replaced.</p>
          {onOpenReplaced ? (
            <button type="button" className="field-button-secondary justify-self-start" onClick={() => onOpenReplaced(replaced)}>
              Open the artisan
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
