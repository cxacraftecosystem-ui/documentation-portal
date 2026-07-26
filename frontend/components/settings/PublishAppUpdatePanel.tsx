"use client";

/**
 * Publish an Android build from the browser — master admin only.
 *
 * Until now the only way to push an update to every device was the phone's own menu ("Push update
 * to all"), which publishes the build the publisher happens to be running. That is a strange place
 * for it: the person who has the signed APK is at a laptop, having just built it, and to ship it
 * they had to sideload it onto their own phone first and re-publish from there. This is the same
 * two-step the phone does — put the APK in object storage, then record the release against it —
 * done from where the file already is.
 *
 * THE ONE RULE THAT MATTERS: versionCode must be strictly greater than the published one. The
 * in-app updater compares version codes, so a release at or below the current one is accepted by
 * the API, appears to succeed, and then silently reaches nobody. That failure is invisible for days,
 * so it is checked here before a 25 MB upload starts rather than discovered later.
 */

import { useEffect, useState } from "react";
import { Loader2, Rocket, Upload } from "lucide-react";

import { Field, TextArea, TextInput } from "@/components/FormControls";
import { useToast } from "@/components/ui/Toast";
import { apiFetch } from "@/lib/api";
import { uploadStorageObject } from "@/lib/media";

type Release = {
  versionName?: string;
  versionCode?: number;
  url?: string | null;
  publishedAt?: string;
};

/** "1.1.17" -> 11117, the derivation android/app/build.gradle.kts uses. Null when it does not parse. */
function versionCodeFor(versionName: string): number | null {
  const parts = versionName.trim().split(".");
  if (parts.length !== 3) return null;
  const [major, minor, patch] = parts.map((part) => Number(part));
  if ([major, minor, patch].some((n) => !Number.isInteger(n) || n < 0 || n > 99)) return null;
  return major * 10000 + minor * 100 + patch;
}

export function PublishAppUpdatePanel() {
  const [current, setCurrent] = useState<Release | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [versionName, setVersionName] = useState("");
  const [notes, setNotes] = useState("");
  const [progress, setProgress] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { toast } = useToast();

  useEffect(() => {
    apiFetch<Release>("/app/release/latest")
      .then((release) => setCurrent(release?.versionCode ? release : null))
      .catch(() => setCurrent(null));
  }, []);

  const derived = versionName.trim() ? versionCodeFor(versionName) : null;
  const tooOld = derived !== null && current?.versionCode !== undefined && derived <= current.versionCode;

  async function publish(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (!file) return setError("Choose the signed APK first.");
    if (!derived) return setError("Version name must be three numbers, like 1.1.18.");
    if (tooOld) {
      return setError(
        `Version ${versionName.trim()} is code ${derived}, which is not above the published ${current?.versionCode}. ` +
          "Devices compare version codes, so this release would reach nobody."
      );
    }

    setBusy(true);
    setProgress(0);
    try {
      const object = await uploadStorageObject({
        file,
        onProgress: (loaded, total) => setProgress(total ? loaded / total : 0)
      });
      const published = await apiFetch<Release>("/app/release", {
        method: "POST",
        body: JSON.stringify({
          versionCode: derived,
          versionName: versionName.trim(),
          objectKey: object.objectKey,
          url: object.publicUrl,
          notes: notes.trim() || null
        })
      });
      setCurrent(published);
      setFile(null);
      setVersionName("");
      setNotes("");
      toast({
        title: `v${published.versionName} published`,
        description: "Every device gets the prompt the next time it opens.",
        tone: "success"
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to publish this release");
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  return (
    <section className="panel grid gap-3 p-4">
      <div className="flex items-start gap-3">
        <div className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-purple-800">
          <Rocket className="h-5 w-5 text-white" aria-hidden />
        </div>
        <div className="min-w-0">
          <h2 className="font-display text-base font-bold text-ink-900">Publish an Android update</h2>
          <p className="mt-0.5 text-xs leading-5 text-ink-500">
            Upload the signed APK and every device is prompted to install it the next time it opens. Same release
            the phone&rsquo;s &ldquo;Push update to all&rdquo; publishes — this is it from where the file already is.
          </p>
        </div>
      </div>

      <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
        {current?.versionCode
          ? `Currently published: v${current.versionName} (code ${current.versionCode}).`
          : "Nothing has been published yet — this will be the first release."}
      </p>

      <form className="grid gap-3" onSubmit={publish}>
        <Field label="Signed APK">
          <input
            type="file"
            accept=".apk,application/vnd.android.package-archive"
            className="field-input"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </Field>

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Version name">
            <TextInput
              name="versionName"
              value={versionName}
              onChange={(event) => setVersionName(event.target.value)}
              placeholder="1.1.18"
            />
            <p className="mt-1 text-xs text-ink-500">Three numbers, the same value as android/app/build.gradle.kts.</p>
          </Field>
          <Field label="Version code">
            <TextInput name="versionCode" value={derived ? String(derived) : ""} readOnly onChange={() => undefined} />
            <p className="mt-1 text-xs text-ink-500">Derived from the name, exactly as the build derives it.</p>
          </Field>
        </div>

        <Field label="Release notes">
          <TextArea name="notes" value={notes} onChange={(event) => setNotes(event.target.value)} rows={2} />
          <p className="mt-1 text-xs text-ink-500">Shown in the update prompt on every device. Optional.</p>
        </Field>

        {tooOld ? (
          <p className="rounded-md border border-amber-500/40 bg-amber-100/60 px-3 py-2 text-xs text-amber-800">
            Code {derived} is not above the published {current?.versionCode}. Devices compare codes, so this would
            upload successfully and reach nobody.
          </p>
        ) : null}
        {error ? (
          <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
        ) : null}

        {progress !== null ? (
          <div className="grid gap-1">
            <div className="h-1.5 overflow-hidden rounded-full bg-line-200">
              <div className="h-full rounded-full bg-purple-700 transition-[width]" style={{ width: `${Math.round(progress * 100)}%` }} />
            </div>
            <p className="text-xs text-ink-500">Uploading… {Math.round(progress * 100)}%</p>
          </div>
        ) : null}

        <div>
          <button className="field-button" type="submit" disabled={busy || !file || !derived || tooOld}>
            {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Upload className="h-4 w-4" aria-hidden />}
            {busy ? "Publishing…" : "Publish to every device"}
          </button>
        </div>
      </form>
    </section>
  );
}
