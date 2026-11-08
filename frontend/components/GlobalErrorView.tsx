"use client";

import { TriangleAlert } from "lucide-react";

import { ErrorBrand, ErrorPanel } from "@/components/ErrorPanel";
import { errorCopy } from "@/lib/errorKind";

const COPY = errorCopy("global");

export function GlobalErrorView({ reference }: { reference?: string | null }) {
  return (
    <main id="main-content" className="mx-auto max-w-7xl px-4 pb-12 pt-24">
      <ErrorBrand />
      <ErrorPanel
        screen="global"
        tone="alert"
        icon={<TriangleAlert className="h-5 w-5" aria-hidden />}
        title={COPY.title}
        line={COPY.line}
        reference={reference}
      >
        <button type="button" onClick={() => window.location.reload()} className="field-button">
          Reload
        </button>
      </ErrorPanel>
    </main>
  );
}
