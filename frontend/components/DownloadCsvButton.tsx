"use client";

import { API_BASE, getToken } from "@/lib/api";
import { Download } from "lucide-react";

export function DownloadCsvButton({ path, filename }: { path: "/export/products.csv" | "/export/tools.csv"; filename: string }) {
  async function download() {
    const token = getToken();
    const response = await fetch(`${API_BASE}/api${path}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    });
    if (!response.ok) throw new Error("Unable to export CSV");
    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    window.URL.revokeObjectURL(url);
  }

  return (
    <button className="field-button-secondary" onClick={download}>
      <Download className="h-4 w-4" aria-hidden />
      Export CSV
    </button>
  );
}
