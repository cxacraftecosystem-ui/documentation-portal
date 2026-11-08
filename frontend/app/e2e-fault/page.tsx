"use client";

import { DeadEndFrame, ErrorState } from "@/components/DeadEnd";
import { GlobalErrorView } from "@/components/GlobalErrorView";
import { e2eRenderFault, useE2eFault } from "@/lib/e2eFault";

export default function E2eFaultPage() {
  const { ready, fault } = useE2eFault();
  if (!ready) return null;
  if (fault === "render") throw e2eRenderFault();
  if (fault === "global") return <GlobalErrorView reference="e2e-4f2a9c1d" />;
  return (
    <DeadEndFrame nav>
      <ErrorState kind="not-found" />
    </DeadEndFrame>
  );
}
