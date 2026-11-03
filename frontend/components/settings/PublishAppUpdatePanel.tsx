"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
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

function parseVersionCode(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isSafeInteger(value) && value >= 1 ? value : null;
}

function isVersionName(text: string): boolean {
  return /^\d+\.\d+\.\d+$/.test(text.trim());
}

export function PublishAppUpdatePanel() {
  const [current, setCurrent] = useState<Release | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [versionName, setVersionName] = useState("");
  const [versionCodeText, setVersionCodeText] = useState("");
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

  const derived = parseVersionCode(versionCodeText);
  const nextCode = current?.versionCode ? current.versionCode + 1 : 1;
  const tooOld = derived !== null && current?.versionCode !== undefined && derived <= current.versionCode;

  async function publish(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (!file) return setError("Choose the signed APK first.");
    if (!isVersionName(versionName)) return setError("Version name must be three numbers, like 0.0.2.");
    if (!derived) return setError("Version code must be a whole number above the published one.");
    if (tooOld) {
      return setError(
        `Code ${derived} must be above the published ${current?.versionCode}.`
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
      setVersionCodeText("");
      setNotes("");
      toast({
        title: `v${published.versionName} published`,
        description: "Devices are prompted on next open.",
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
            Devices are prompted to install it on next open.
          </p>
        </div>
      </div>

      <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
        {current?.versionCode
          ? `Currently published: v${current.versionName} (code ${current.versionCode}).`
          : "Nothing published yet."}
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
              placeholder="0.0.2"
            />
            <p className="mt-1 text-xs text-ink-500">e.g. 1.4.2</p>
          </Field>
          <Field label="Version code">
            <TextInput
              name="versionCode"
              inputMode="numeric"
              value={versionCodeText}
              onChange={(event) => setVersionCodeText(event.target.value)}
              placeholder={String(nextCode)}
            />
            <p className="mt-1 text-xs text-ink-500">The app&apos;s appVersionCode, above the published code.</p>
          </Field>
        </div>

        <Field label="Release notes">
          <TextArea name="notes" value={notes} onChange={(event) => setNotes(event.target.value)} rows={2} />
          <p className="mt-1 text-xs text-ink-500">Optional. Shown in the prompt.</p>
        </Field>

        {tooOld ? (
          <p className="rounded-md border border-amber-500/40 bg-amber-100/60 px-3 py-2 text-xs text-amber-800">
            Code {derived} must be above {current?.versionCode}, or no device will update.
          </p>
        ) : null}
        {error ? (
          <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
        ) : null}

        {progress !== null ? (
          <div className="grid gap-1">
            <ProgressBar percent={Math.round(progress * 100)} fillClassName="transition-[width]" />
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
