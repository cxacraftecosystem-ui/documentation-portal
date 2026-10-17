"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { CalendarClock } from "lucide-react";

import { Toggle } from "@/components/ui/Toggle";
import {
  DIGEST_LABEL,
  digestCardView,
  fetchDigestPreference,
  saveDigestPreference,
  toggleDigest,
  type DigestPreference
} from "@/lib/weeklyDigest";

/**
 * The Monday summary's switch, for reviewers and administrators only (the server says who:
 * `eligible`). Unlike the e-mail card it shows while mail is off, because the summary still arrives
 * in the notifications list.
 */
export function WeeklyDigestCard() {
  const [state, setState] = useState<DigestPreference | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void fetchDigestPreference().then((answer) => {
      if (live) setState(answer);
    });
    return () => {
      live = false;
    };
  }, []);

  const view = digestCardView(state);
  if (!state || !view) return null;

  async function change(next: boolean) {
    if (!state) return;
    setSaving(true);
    setError(null);
    const outcome = await toggleDigest(state, next, saveDigestPreference, setState);
    setError(outcome.error);
    setSaving(false);
  }

  return (
    <section className="panel p-5" aria-labelledby="weekly-digest-heading">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <CalendarClock className="h-4 w-4" aria-hidden />
        </span>
        <h2 id="weekly-digest-heading" className="font-display font-bold text-ink-900">
          {DIGEST_LABEL}
        </h2>
      </div>
      <div className="mt-3">
        <Toggle
          checked={state.enabled}
          label={DIGEST_LABEL}
          description={`${view.description} ${view.channel}`}
          busy={saving}
          onChange={(next) => void change(next)}
        />
      </div>
      {view.latestHref ? (
        <p className="mt-3 text-center">
          <Link href={view.latestHref} className="text-sm font-medium text-purple-700 hover:underline">
            {"Open last Monday's summary"}
          </Link>
        </p>
      ) : null}
      <p className="pt-4 text-xs leading-5 text-ink-500" role="status">
        {error ?? (saving ? "Saving to your account..." : "Saved to your account automatically.")}
      </p>
    </section>
  );
}
