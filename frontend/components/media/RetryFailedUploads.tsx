"use client";

import { useState } from "react";
import { RotateCcw } from "lucide-react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { failedFilesOf, type FailedUpload } from "@/components/media/failedUploads";

export { failedFilesOf, type FailedUpload } from "@/components/media/failedUploads";

export function RetryFailedUploads({
  files,
  upload,
  onDone
}: {
  files: File[];
  upload: (files: File[]) => Promise<{ failed: FailedUpload[] }>;
  onDone: () => void;
}) {
  const [pending, setPending] = useState(files);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  if (!pending.length) return null;

  async function retry() {
    setBusy(true);
    setProblem(null);
    try {
      const { failed } = await upload(pending);
      const still = failedFilesOf(pending, failed);
      if (!still.length) {
        setPending([]);
        onDone();
        return;
      }
      setPending(still);
      setProblem(failed[0]?.error ?? null);
    } catch (err) {
      setProblem(err instanceof Error ? err.message : "No connection.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div role="alert" className="grid gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2.5 text-sm text-amber-800">
      <p>
        Saved. {pending.length} file{pending.length === 1 ? "" : "s"} didn&rsquo;t upload: {pending.map((file) => file.name).join(", ")}.
        {problem ? ` ${problem}` : ""}
      </p>
      <ButtonRow align="start">
        <button type="button" className="field-button" onClick={retry} disabled={busy}>
          <RotateCcw className="h-4 w-4" aria-hidden />
          {busy ? "Retrying…" : `Retry ${pending.length} file${pending.length === 1 ? "" : "s"}`}
        </button>
        <button type="button" className="field-button-secondary" onClick={onDone} disabled={busy}>
          Continue without them
        </button>
      </ButtonRow>
    </div>
  );
}
