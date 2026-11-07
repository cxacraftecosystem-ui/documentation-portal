"use client";

import { TriangleAlert } from "lucide-react";

import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { PREFERENCES_BOOT_SCRIPT } from "@/lib/preferences";
import "./globals.css";

export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <title>Something went wrong · Field Repository</title>
      </head>
      <body className="min-h-screen bg-bg-0 font-sans text-ink-900">
        <script dangerouslySetInnerHTML={{ __html: PREFERENCES_BOOT_SCRIPT }} />
        <main id="main-content" className="mx-auto max-w-7xl px-4 pb-12 pt-24">
          <div className="mb-8 flex items-center justify-center gap-2">
            <FieldRepoLogo className="h-8 w-8 rounded-lg" />
            <span className="font-display text-base font-bold tracking-tight">Field Repository</span>
          </div>
          <section className="panel px-6 py-14 text-center" data-testid="global-error">
            <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-error-100 text-red-800">
              <TriangleAlert className="h-5 w-5" aria-hidden />
            </div>
            <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">The app stopped loading</h1>
            <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">Saved work is safe. Try again, or reload the page.</p>
            {error.digest ? (
              <p className="mx-auto mt-4 max-w-lg rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
                Reference <code className="break-all font-mono text-ink-700">{error.digest}</code>
              </p>
            ) : null}
            <ActionGroup className="mt-7">
              <button type="button" onClick={reset} className="field-button">
                Try again
              </button>
              <button type="button" onClick={() => window.location.reload()} className="field-button-secondary">
                Reload
              </button>
            </ActionGroup>
          </section>
        </main>
      </body>
    </html>
  );
}
