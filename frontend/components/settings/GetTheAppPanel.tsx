"use client";

/**
 * "Get the Android app" — the web half of the cross-link between the two clients.
 *
 * The two apps are one product and each is better at a different half of the job: the phone is what
 * goes into the workshop (offline outbox, camera, GPS, the questionnaire recorder), the laptop is
 * where reviewing, browsing and administration happen. A researcher given only one of them does the
 * wrong half of their work in the wrong place, and nothing in either app used to mention the other.
 *
 * The download is whatever a master admin last published through the in-app updater
 * (`GET /app/release/latest`), so this is always the same build the OTA check hands out — there is
 * no second, staler copy to fall out of date. When nothing has been published yet the card says so
 * plainly instead of offering a dead button.
 */

import { useEffect, useState } from "react";
import { Download, Smartphone } from "lucide-react";

import { apiFetch } from "@/lib/api";

type Release = {
  versionName?: string;
  versionCode?: number;
  url?: string | null;
  notes?: string | null;
  publishedAt?: string;
};

export function GetTheAppPanel() {
  const [release, setRelease] = useState<Release | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<Release>("/app/release/latest")
      .then(setRelease)
      // A missing release is not an error worth showing: the card degrades to its "not published
      // yet" state, which is the same thing the researcher needs to know either way.
      .catch(() => setRelease(null))
      .finally(() => setLoading(false));
  }, []);

  const url = release?.url ?? null;

  return (
    <section className="panel grid gap-3 p-4">
      <div className="flex items-start gap-3">
        <div className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-purple-800">
          <Smartphone className="h-5 w-5 text-white" aria-hidden />
        </div>
        <div className="min-w-0">
          <h2 className="font-display text-base font-bold text-ink-900">Get the Android app</h2>
          <p className="mt-0.5 text-xs leading-5 text-ink-500">
            The phone app is the one that goes into the workshop: it records interviews, captures photos and GPS with
            no signal at all, and queues everything until you are back in range. This portal is for reviewing,
            browsing and administration. Same account, same repository.
          </p>
        </div>
      </div>

      {loading ? (
        <p className="text-xs text-ink-500">Checking for the current build…</p>
      ) : url ? (
        <div className="flex flex-wrap items-center gap-3">
          <a className="field-button" href={url} download>
            <Download className="h-4 w-4" aria-hidden />
            Download the APK
            {release?.versionName ? ` · v${release.versionName}` : ""}
          </a>
          <span className="text-xs text-ink-500">
            Android will ask you to allow installing from this browser the first time.
          </span>
        </div>
      ) : (
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
          No build has been published yet. A master admin publishes one from the Android app&rsquo;s menu
          (&ldquo;Push update to all&rdquo;), and it appears here automatically.
        </p>
      )}
    </section>
  );
}
