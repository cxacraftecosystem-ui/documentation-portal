"use client";

/**
 * C27 — the artisan's agreement that the Designer Portal may look up their identity details.
 *
 * Drawn only while the server says the cross-portal feature is on. A SETTING on the record, so a
 * switch (§11.11), saved the moment it is pressed and shown ON only once the server says so. Turning
 * it off withdraws the agreement and unlinks every Designer Portal record. The Aadhaar number never
 * leaves this portal either way; the label says so because it is the question an artisan asks.
 */
import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ApiError, apiFetch } from "@/lib/api";

type Consent = {
  granted: boolean;
  recorded: boolean;
  recordedAt: string | null;
  linkedRecords: number;
};

let statusPromise: Promise<boolean> | null = null;

export function crossPortalEnabled(): Promise<boolean> {
  if (!statusPromise) {
    statusPromise = apiFetch<{ enabled: boolean }>("/cross-portal/status")
      .then((answer) => Boolean(answer?.enabled))
      .catch(() => {
        statusPromise = null;
        return false;
      });
  }
  return statusPromise;
}

export function consentStatusLine(consent: Consent): string {
  if (!consent.recorded) return "Not asked yet";
  const when = consent.recordedAt
    ? new Date(consent.recordedAt).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })
    : null;
  const head = consent.granted ? "Agreed" : "Declined";
  const links =
    consent.linkedRecords > 0
      ? ` · linked to ${consent.linkedRecords} Designer Portal record${consent.linkedRecords === 1 ? "" : "s"}`
      : "";
  return `${head}${when ? ` on ${when}` : ""}${links}`;
}

export function SharingConsentToggle({ artisanId }: { artisanId: string }) {
  const [enabled, setEnabled] = useState(false);
  const [consent, setConsent] = useState<Consent | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void crossPortalEnabled().then(async (on) => {
      if (cancelled || !on) return;
      setEnabled(true);
      try {
        const answer = await apiFetch<Consent>(`/cross-portal/artisans/${encodeURIComponent(artisanId)}/consent`);
        if (!cancelled) setConsent(answer);
      } catch {
        if (!cancelled) setEnabled(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [artisanId]);

  if (!enabled || !consent) return null;

  async function change(next: boolean) {
    setBusy(true);
    setError(null);
    try {
      const answer = await apiFetch<Consent>(`/cross-portal/artisans/${encodeURIComponent(artisanId)}/consent`, {
        method: "PUT",
        body: JSON.stringify({ granted: next })
      });
      setConsent(answer);
    } catch (err) {
      const detail = err instanceof ApiError ? (err.payload as { detail?: unknown } | undefined)?.detail : null;
      setError(typeof detail === "string" ? detail : "Couldn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="md:col-span-3 grid gap-1">
      <Toggle
        label="Share with the Designer Portal"
        description="The artisan agrees designers may look up their name, gender, date of birth and Pehchan. Aadhaar never leaves this portal."
        checked={consent.granted}
        busy={busy}
        onChange={(next) => void change(next)}
      />
      <p className="text-xs text-ink-muted">{consentStatusLine(consent)}</p>
      {error ? (
        <p role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
    </div>
  );
}
