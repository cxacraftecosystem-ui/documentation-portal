"use client";

import { useEffect, useState } from "react";

import { AuthCard } from "@/components/AuthCard";
import { ReportUnsubscribe } from "@/components/dataExplorer/ScheduledReportPanels";
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

  return <AuthCard title="Scheduled report">{token === null ? null : <ReportUnsubscribe token={token} />}</AuthCard>;
}
