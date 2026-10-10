"use client";

import { useEffect, useState } from "react";
import { Mail } from "lucide-react";

import { Toggle } from "@/components/settings/Toggle";
import {
  fetchNotificationPreferences,
  saveNotificationPreferences,
  type NotificationPreferences
} from "@/lib/notifications";

/**
 * This account's e-mail opt-out. NO ROLE GATE — the opt-out belongs to the person — and NOTHING AT ALL
 * is drawn until the server says mail is on (`available`).
 */
export function EmailNotificationsCard() {
  const [state, setState] = useState<NotificationPreferences | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void fetchNotificationPreferences().then((answer) => {
      if (live) setState(answer);
    });
    return () => {
      live = false;
    };
  }, []);

  if (!state?.available) return null;

  async function change(next: boolean) {
    const before = state;
    setState((current) => (current ? { ...current, emailTaskUpdates: next } : current));
    setSaving(true);
    setError(null);
    try {
      setState(await saveNotificationPreferences({ emailTaskUpdates: next }));
    } catch (err) {
      setState(before);
      setError(err instanceof Error ? err.message : "Your choice could not be saved. Try again.");
    } finally {
      setSaving(false);
    }
  }

  const label = "My tasks";
  return (
    <section className="panel p-5" aria-labelledby="email-notifications-heading">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <Mail className="h-4 w-4" aria-hidden />
        </span>
        <h2 id="email-notifications-heading" className="font-display font-bold text-ink-900">
          E-mail
        </h2>
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">What this account is sent by e-mail.</p>
      <div className="mt-3 flex items-start justify-between gap-4">
        <div>
          <span className="block text-sm font-medium text-ink-900">{label}</span>
          <span className="block text-xs leading-5 text-ink-500">
            An e-mail when a task you gave out is handed in for your approval, and when a task of yours is approved or
            sent back.
          </span>
        </div>
        <Toggle checked={state.emailTaskUpdates} label={label} onChange={(next) => void change(next)} />
      </div>
      <p className="pt-4 text-xs leading-5 text-ink-500" role="status">
        {error ?? (saving ? "Saving to your account..." : "Saved to your account automatically.")}
      </p>
    </section>
  );
}
