"use client";

import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ApiError, apiFetch } from "@/lib/api";

type Consent = {
  granted: boolean;
  recorded: boolean;
  recordedAt: string | null;
  linkedRecords: number;
};

export const SHARED_FIELDS_FALLBACK = "name, local name, gender, date of birth, Pehchan card, phone number, address, place, craft and craft experience";

type CrossPortalStatus = { enabled: boolean; sharedFields: string };

let statusPromise: Promise<CrossPortalStatus> | null = null;

export function crossPortalStatus(): Promise<CrossPortalStatus> {
  if (!statusPromise) {
    statusPromise = apiFetch<{ enabled: boolean; sharedFields?: string }>("/cross-portal/status")
      .then((answer) => ({
        enabled: Boolean(answer?.enabled),
        sharedFields: answer?.sharedFields?.trim() || SHARED_FIELDS_FALLBACK
      }))
      .catch(() => {
        statusPromise = null;
        return { enabled: false, sharedFields: SHARED_FIELDS_FALLBACK };
      });
  }
  return statusPromise;
}

export function crossPortalEnabled(): Promise<boolean> {
  return crossPortalStatus().then((status) => status.enabled);
}

export function consentDescription(sharedFields: string): string {
  return `The artisan agrees designers may look up their ${sharedFields}. Aadhaar never leaves this portal.`;
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
  const [sharedFields, setSharedFields] = useState(SHARED_FIELDS_FALLBACK);
  const [consent, setConsent] = useState<Consent | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void crossPortalStatus().then(async (status) => {
      if (cancelled || !status.enabled) return;
      setEnabled(true);
      setSharedFields(status.sharedFields);
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
        description={consentDescription(sharedFields)}
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
