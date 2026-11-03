"use client";

import { useEffect, useState } from "react";
import { AlertTriangle, Download, Smartphone } from "lucide-react";

import { PUBLIC_API_ORIGIN, apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";

type Release = {
  versionName?: string;
  versionCode?: number;
  notes?: string | null;
  publishedAt?: string;
};

type Status = "loading" | "published" | "none" | "unchecked";

const DOWNLOAD_URL = `${PUBLIC_API_ORIGIN}/api/app/download`;

export function GetTheAppPanel() {
  const [release, setRelease] = useState<Release | null>(null);
  const [status, setStatus] = useState<Status>("loading");

  useEffect(() => {
    apiFetch<Release>("/app/release/latest")
      .then((latest) => {
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
            For field work — records offline and syncs later. Same account.
          </p>
        </div>
      </div>

      {status === "loading" ? (
        <p className="text-xs text-ink-500">Checking which build is current…</p>
      ) : status === "none" ? (
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
          No build published yet.
        </p>
      ) : (
        <div className="grid gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <a className="field-button" href={DOWNLOAD_URL}>
              <Download className="h-4 w-4" aria-hidden />
              Download the APK
              {release?.versionName ? ` · v${release.versionName}` : ""}
            </a>
            <span className="text-xs text-ink-500">
              Allow installs from this browser when asked.
            </span>
          </div>

          {status === "published" ? (
            <p className="text-xs text-ink-500">
              Version {release?.versionName} (build {release?.versionCode}), published{" "}
              {formatDate(release?.publishedAt)}.
            </p>
          ) : (
            <p className="flex items-start gap-1.5 text-xs text-ink-500">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden />
              <span>
                Couldn’t check the version. The download is still the latest.
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
