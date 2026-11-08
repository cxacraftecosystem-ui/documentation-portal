"use client";

import { DeadEndFrame, ErrorState } from "@/components/DeadEnd";
import { errorKindOf } from "@/lib/errorKind";

export default function RootError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <DeadEndFrame nav={false}>
      <ErrorState kind={errorKindOf(error)} reference={error.digest} onRetry={reset} />
    </DeadEndFrame>
  );
}
