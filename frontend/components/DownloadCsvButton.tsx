"use client";

import { useState } from "react";
import { Download } from "lucide-react";

import { API_BASE, getToken } from "@/lib/api";

export function DownloadCsvButton({
  path,
  filename,
  onError
}: {
  path: string;
  filename: string;
  onError?: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function download() {
    setError(null);
    setBusy(true);
    try {
      const token = getToken();
      const response = await fetch(`${API_BASE}/api${path}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
      if (!response.ok) throw new Error(`Unable to export CSV (HTTP ${response.status})`);
      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = filename;
      anchor.click();
      window.URL.revokeObjectURL(url);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Unable to export CSV";
      setError(message);
      onError?.(message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      <button type="button" className="field-button-secondary" disabled={busy} onClick={download}>
        <Download className="h-4 w-4" aria-hidden />
        {busy ? "Exporting…" : "Export CSV"}
      </button>
      {error ? (
        <span className="text-xs font-medium text-red-700" role="alert">
          {error}
        </span>
      ) : null}
    </span>
  );
}
