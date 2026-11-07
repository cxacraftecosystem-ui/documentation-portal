"use client";

import { TriangleAlert } from "lucide-react";

import { DeadEndDetail, DeadEndFrame, DeadEndPanel, DeadEndText, DeadEndWayBack } from "@/components/DeadEnd";

export default function RootError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <DeadEndFrame nav={false}>
      <DeadEndPanel
        tone="alert"
        icon={<TriangleAlert className="h-5 w-5" aria-hidden />}
        title="This page stopped before it finished"
      >
        <DeadEndText>
          Something went wrong while loading this page. It was not anything you did, and trying again usually works.
        </DeadEndText>
        {error.digest ? <DeadEndDetail label="Reference" value={error.digest} /> : null}
        <DeadEndWayBack>
          <button type="button" onClick={reset} className="field-button">
            Try again
          </button>
        </DeadEndWayBack>
      </DeadEndPanel>
    </DeadEndFrame>
  );
}
