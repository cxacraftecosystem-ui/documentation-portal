"use client";

/**
 * "Get the Android app" — the web half of the cross-link between the two clients.
 *
 * The two apps are one product and each is better at a different half of the job: the phone is what
 * goes into the workshop (offline outbox, camera, GPS, the questionnaire recorder), the laptop is
 * where reviewing, browsing and administration happen. A researcher given only one of them does the
 * wrong half of their work in the wrong place, and nothing in either app used to mention the other.
 *
 * THE LINK NAMES NO BUILD. It used to: the card fetched the current release and put that release's
 * storage URL into the `href`, which meant the button handed out whichever build was newest at the
 * moment the page happened to load. A tab left open across a release, a link copied to someone else,
 * anything that cached the markup — all of them kept serving the old APK, and nothing on screen said
 * so. Now the `href` is the fixed address `/api/app/download`, which resolves the newest release
 * server-side on every single click, from the same row the phone's update check reads. The version
 * and date below are a *label* on that link, not the thing that decides what it fetches.
 */

import { useEffect, useState } from "react";
import { AlertTriangle, Download, Smartphone } from "lucide-react";

import { API_BASE, apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";

type Release = {
  versionName?: string;
  versionCode?: number;
  notes?: string | null;
  publishedAt?: string;
};

/**
 * Three outcomes worth telling apart, because two of them still deserve a working button: we know
 * the current version, nothing has ever been published (nothing to offer), or the check itself
 * failed (the download endpoint may well be fine — only our label for it is missing).
 */
type Status = "loading" | "published" | "none" | "unchecked";

/** Fixed, versionless, resolved server-side per request. See the file comment. */
const DOWNLOAD_URL = `${API_BASE}/api/app/download`;

export function GetTheAppPanel() {
  const [release, setRelease] = useState<Release | null>(null);
  const [status, setStatus] = useState<Status>("loading");

  useEffect(() => {
    apiFetch<Release>("/app/release/latest")
      .then((latest) => {
        // The endpoint answers `{}` when no release exists — a real answer, not a failure.
        if (latest?.versionCode) {
          setRelease(latest);
          setStatus("published");
        } else {
          setStatus("none");
        }
      })
      .catch(() => setStatus("unchecked"));
  }, []);

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

      {status === "loading" ? (
        <p className="text-xs text-ink-500">Checking which build is current…</p>
      ) : status === "none" ? (
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
          No build has been published yet. A master admin publishes one — from &ldquo;Publish an Android update&rdquo;
          on this page, or from the phone&rsquo;s own &ldquo;Push update to all&rdquo; — and it appears here
          automatically.
        </p>
      ) : (
        <div className="grid gap-2">
          <div className="flex flex-wrap items-center gap-3">
            {/* No `download` attribute: it is ignored on a cross-origin link, and the API already
                sends the filename — field-repository-v<version>.apk — in Content-Disposition. */}
            <a className="field-button" href={DOWNLOAD_URL}>
              <Download className="h-4 w-4" aria-hidden />
              Download the APK
              {release?.versionName ? ` · v${release.versionName}` : ""}
            </a>
            <span className="text-xs text-ink-500">
              Android will ask you to allow installing from this browser the first time.
            </span>
          </div>

          {status === "published" ? (
            <p className="text-xs text-ink-500">
              Version {release?.versionName} (build {release?.versionCode}), published{" "}
              {formatDate(release?.publishedAt)}. The link always fetches the newest published build — the same one
              phones are prompted to install.
            </p>
          ) : (
            <p className="flex items-start gap-1.5 text-xs text-ink-500">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden />
              <span>
                Could not reach the repository to check which version is current. The download itself resolves the
                newest build when you click it, so it is still worth trying.
              </span>
            </p>
          )}

          {status === "published" && release?.notes ? (
            <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
              {release.notes}
            </p>
          ) : null}
        </div>
      )}
    </section>
  );
}
