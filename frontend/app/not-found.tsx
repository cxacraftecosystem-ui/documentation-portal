"use client";

import { useEffect, useState } from "react";
import { MapPinOff } from "lucide-react";

import { DeadEndDetail, DeadEndFrame, DeadEndPanel, DeadEndText, DeadEndWayBack } from "@/components/DeadEnd";

export default function NotFound() {
  const [asked, setAsked] = useState<string | null>(null);
  useEffect(() => {
    setAsked(window.location.pathname + window.location.search);
  }, []);

  return (
    <DeadEndFrame nav>
      <DeadEndPanel tone="notice" icon={<MapPinOff className="h-5 w-5" aria-hidden />} title="There is nothing at this address">
        <DeadEndText>
          If you followed a link to a record — a craft, a workshop, an artisan — it may have been deleted, or it may not
          be one this account can open.
        </DeadEndText>
        <DeadEndText>
          The address may also simply be mistyped, or point at a page that has moved since the link was written. If a
          colleague sent it, the address below is the part worth sending back to them.
        </DeadEndText>
        {asked ? <DeadEndDetail label="You asked for" value={asked} /> : null}
        <DeadEndWayBack identity />
      </DeadEndPanel>
    </DeadEndFrame>
  );
}
