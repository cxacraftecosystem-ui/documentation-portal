"use client";

/**
 * The artisan's agreement that their recordings may be sent for transcription (the consent rule,
 * 18 Oct 2026). Without it a recording is kept and simply not sent. A setting, so a switch, shown
 * ON only once the server says so. Recordings made before 18 Oct 2026 carry consent by the owner's
 * attestation (`source: "owner attestation"`), which the status line names.
 */
import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ApiError, apiFetch } from "@/lib/api";

type RecordingConsent = {
  decision: "GRANTED" | "REFUSED" | "NOT_RECORDED";
  recorded: boolean;
  granted: boolean;
  source: string | null;
  attestedAt: string | null;
};

export function recordingConsentStatus(consent: RecordingConsent): string {
  if (!consent.recorded) return "Not asked yet";
  const when = consent.attestedAt
    ? new Date(consent.attestedAt).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })
    : null;
  const head = consent.granted ? "Agreed" : "Declined";
  const by = consent.source === "owner attestation" ? " · confirmed by the owner" : "";
  return `${head}${when ? ` on ${when}` : ""}${by}`;
}

export function RecordingConsentToggle({ artisanId }: { artisanId: string }) {
  const [consent, setConsent] = useState<RecordingConsent | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const path = `/artisans/${encodeURIComponent(artisanId)}/recording-consent`;

  useEffect(() => {
    let cancelled = false;
    apiFetch<RecordingConsent>(path)
      .then((answer) => {
        if (!cancelled) setConsent(answer);
      })
      .catch(() => {
        if (!cancelled) setError("Couldn't load recording consent.");
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  async function change(next: boolean) {
    setBusy(true);
    setError(null);
    try {
      setConsent(
        await apiFetch<RecordingConsent>(path, {
          method: "PUT",
          body: JSON.stringify({ decision: next ? "GRANTED" : "REFUSED" })
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
            checked={consent.granted}
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
