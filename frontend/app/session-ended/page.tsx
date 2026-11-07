"use client";

import { useEffect, useState } from "react";

import { DeadEndFrame, ErrorState } from "@/components/DeadEnd";
import { nextFromSearch } from "@/lib/errorKind";

export default function SessionEndedPage() {
  const [next, setNext] = useState<string | null>(null);
  useEffect(() => {
    setNext(nextFromSearch(window.location.search));
  }, []);
  return (
    <DeadEndFrame nav={false}>
      <ErrorState kind="session" next={next ?? "/dashboard"} />
    </DeadEndFrame>
  );
}
