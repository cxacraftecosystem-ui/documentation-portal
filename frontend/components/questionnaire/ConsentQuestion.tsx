"use client";

import { useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { shortName } from "@/lib/coverageCache";
import { mintKey, questionnaireApi, type ConsentRecord } from "@/lib/questionnaireApi";
import { qs } from "@/lib/strings/qs";

/**
 * The consent question (DESIGN §3.11), asked once per artisan: inline under the start sheet's artisan
 * field and on the artisan record. It never blocks recording; missing consent only holds back the
 * outside send (transcription, translation). Every answer is a new row; a refusal can be changed.
 */
export function ConsentQuestion({
  artisanId,
  name,
  onRecorded,
  onDismiss
}: {
  artisanId: string;
  name: string;
  onRecorded?: (record: ConsentRecord) => void;
  onDismiss?: () => void;
}) {
  const [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  async function record(state: "GRANTED" | "REFUSED") {
    setBusy(true);
    setError(null);
    try {
      const saved = await questionnaireApi.postConsent(artisanId, {
        purpose: "EXTERNAL_PROCESSING",
        state,
        basis: "VERBAL_RECORDED",
        note: null,
        evidenceMediaId: null,
        clientKey: mintKey("cons")
      });
      setDone(state === "GRANTED" ? qs("consentSaved") : qs("consentRefusedSaved"));
      onRecorded?.(saved);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <p role="status" className="text-sm text-ink-500" data-consent-question={artisanId}>
        {done}
      </p>
    );
  }

  return (
    <div className="rounded-md border border-line-200 bg-surface-50 p-3" data-consent-question={artisanId}>
      <p className="text-sm text-ink-900">{qs("consentQuestion", { name: shortName(name) })}</p>
      <ButtonRow className="mt-2" size="sm">
        <button type="button" className="field-button-secondary" disabled={busy} onClick={() => onDismiss?.()}>
          {qs("consentNotNow")}
        </button>
        <button type="button" className="field-button" disabled={busy} onClick={() => void record("GRANTED")}>
          {qs("consentYes")}
        </button>
      </ButtonRow>
      <div className="mt-1 flex justify-center">
        {more ? (
          <button type="button" className="text-sm font-medium text-ink-500 underline" disabled={busy} onClick={() => void record("REFUSED")}>
            {qs("consentNo")}
          </button>
        ) : (
          <button type="button" className="text-sm font-medium text-ink-500 underline" aria-expanded={false} onClick={() => setMore(true)}>
            {qs("consentMore")}
          </button>
        )}
      </div>
      {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
    </div>
  );
}
