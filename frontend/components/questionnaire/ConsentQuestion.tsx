"use client";

import { useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { shortName } from "@/lib/questionnaire/coverageCache";
import { mintKey, questionnaireApi, type ConsentRecord } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

/**
 * The consent notice (DESIGN §3.11), inline under the start sheet's artisan field, on the artisan
 * record and on a refused take. Consent is assumed by default and can be revoked (owner decision,
 * 19 Oct 2026): with no answer on record it says recordings may be sent, with Switch off beside OK;
 * after a refusal it says so, with Switch back on. It never blocks recording. Every answer is a new row.
 */
export function ConsentQuestion({
  artisanId,
  name,
  refused = false,
  onRecorded,
  onDismiss
}: {
  artisanId: string;
  name: string;
  refused?: boolean;
  onRecorded?: (record: ConsentRecord) => void;
  onDismiss?: () => void;
}) {
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

  const first = shortName(name);
  return (
    <div className="rounded-md border border-line-200 bg-surface-50 p-3" data-consent-question={artisanId}>
      <p className="text-sm text-ink-900">{qs(refused ? "consentRefusedNotice" : "consentNotice", { name: first })}</p>
      <ButtonRow className="mt-2" size="sm">
        {refused ? (
          <>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => onDismiss?.()}>
              {qs("ok")}
            </button>
            <button type="button" className="field-button" disabled={busy} onClick={() => void record("GRANTED")}>
              {qs("consentSwitchOn")}
            </button>
          </>
        ) : (
          <>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void record("REFUSED")}>
              {qs("consentSwitchOff")}
            </button>
            <button type="button" className="field-button" disabled={busy} onClick={() => onDismiss?.()}>
              {qs("ok")}
            </button>
          </>
        )}
      </ButtonRow>
      {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
    </div>
  );
}
