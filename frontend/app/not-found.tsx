"use client";

import { useLayoutEffect } from "react";

import { DeadEndFrame, ErrorState } from "@/components/DeadEnd";
import { markNotFoundPage } from "@/lib/session";

export default function NotFound() {
  useLayoutEffect(() => {
    markNotFoundPage();
  }, []);
  return (
    <DeadEndFrame nav>
      <ErrorState kind="not-found" />
    </DeadEndFrame>
  );
}
