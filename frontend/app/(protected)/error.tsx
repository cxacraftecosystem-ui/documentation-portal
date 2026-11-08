"use client";

import { ErrorState } from "@/components/DeadEnd";
import { errorKindOf } from "@/lib/errorKind";

export default function ProtectedError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return <ErrorState kind={errorKindOf(error)} reference={error.digest} onRetry={reset} />;
}
