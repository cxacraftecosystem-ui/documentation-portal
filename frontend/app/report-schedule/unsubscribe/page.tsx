"use client";

import { useEffect, useState } from "react";

import { ReportUnsubscribe } from "@/components/dataExplorer/ScheduledReportPanels";
import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { takeLinkTokenFromAddress } from "@/lib/signIn";

export default function ReportUnsubscribePage() {
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    const taken = takeLinkTokenFromAddress(window.location.href);
    if (taken.address !== null) {
      const address = taken.address;
      queueMicrotask(() => window.history.replaceState(null, "", address));
    }
    setToken((previous) => previous || taken.token);
  }, []);

  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-bg-0 px-4 py-6 md:px-10">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute inset-0 grad-mesh opacity-60" />
      </div>
      <GlassSurface options={GLASS_PANEL} className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <FieldRepoLogo className="h-12 w-12 rounded-xl shadow-sm" />
          <h1 className="font-display text-2xl font-bold text-ink-900">Scheduled report</h1>
        </div>
        {token === null ? null : <ReportUnsubscribe token={token} />}
      </GlassSurface>
    </main>
  );
}
