"use client";

/**
 * The artisan's agreement that their recordings may be sent for transcription (the consent rule,
 * 18 Oct 2026). Without it a recording is kept and simply not sent. A setting, so a switch, shown
 * ON only once the server says so. Recordings made before 18 Oct 2026 carry consent by the owner's
 * attestation (`source: "owner attestation"`), which the status line names.
 *
 * ONE CONSENT STORE (19 Oct 2026): this reads and writes the artisan's row in the consent ledger
 * through `/artisans/{id}/consent`, the same record the questionnaire's consent question answers, so
 * the two can never disagree.
 */
import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ApiError } from "@/lib/api";
import { mintKey, questionnaireApi, type ConsentRecord } from "@/lib/questionnaireApi";

type ConsentStatus = Pick<ConsentRecord, "state" | "source" | "recordedAt">;

export function recordingConsentStatus(consent: ConsentStatus): string {
  if (consent.state !== "GRANTED" && consent.state !== "REFUSED") return "Not asked yet";
  const when = consent.recordedAt
    ? new Date(consent.recordedAt).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })
    : null;
  const head = consent.state === "GRANTED" ? "Agreed" : "Declined";
  const by = consent.source === "owner attestation" ? " · confirmed by the owner" : "";
  return `${head}${when ? ` on ${when}` : ""}${by}`;
}

export function RecordingConsentToggle({ artisanId }: { artisanId: string }) {
  const [consent, setConsent] = useState<ConsentRecord | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    questionnaireApi
      .getConsent(artisanId)
      .then((answer) => {
        if (!cancelled) setConsent(answer);
      })
      .catch(() => {
        if (!cancelled) setError("Couldn't load recording consent.");
      });
    return () => {
      cancelled = true;
    };
  }, [artisanId]);

  async function change(next: boolean) {
    setBusy(true);
    setError(null);
    try {
      setConsent(
        await questionnaireApi.postConsent(artisanId, {
          purpose: "EXTERNAL_PROCESSING",
          state: next ? "GRANTED" : "REFUSED",
          basis: "VERBAL_RECORDED",
          note: null,
          evidenceMediaId: null,
          clientKey: mintKey("cons")
        })
      );
    } catch (err) {
      const detail = err instanceof ApiError ? (err.payload as { detail?: unknown } | undefined)?.detail : null;
      setError(typeof detail === "string" ? detail : "Couldn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="md:col-span-3 grid gap-1">
      {consent ? (
        <>
          <Toggle
            label="Recordings may be transcribed"
            description="The artisan agrees their recordings may be sent to a transcription service."
            checked={consent.state === "GRANTED"}
            busy={busy}
            onChange={(next) => void change(next)}
          />
          <p className="text-xs text-ink-muted">{recordingConsentStatus(consent)}</p>
        </>
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
    </div>
  );
}
