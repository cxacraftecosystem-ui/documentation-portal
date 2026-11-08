"use client";

import { ErrorState } from "@/components/DeadEnd";
import { e2eRenderFault, useE2eFault } from "@/lib/e2eFault";

export default function E2eShellFaultPage() {
  const { ready, fault } = useE2eFault();
  if (!ready) return null;
  if (fault === "render") throw e2eRenderFault();
  return <ErrorState kind="not-found" />;
}
