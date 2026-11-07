"use client";

import { DeadEndFrame, ErrorState } from "@/components/DeadEnd";

export default function NotFound() {
  return (
    <DeadEndFrame nav>
      <ErrorState kind="not-found" />
    </DeadEndFrame>
  );
}
