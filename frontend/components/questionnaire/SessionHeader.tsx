"use client";

import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import type { Session } from "@/lib/questionnaireApi";
import { modeLabel } from "@/lib/questionnaireView";
import { qs } from "@/lib/strings/qs";

/**
 * Title (generated), note underneath, the participant strip with Present/Away per artisan, the join
 * code and its QR (DESIGN §4.3, §3.7). The code works online; the QR carries the session's
 * clientKey so a second device can join offline.
 */
export function SessionHeader({
  session,
  pendingTakes,
  onFinish,
  onPresence,
  onAddArtisan
}: {
  session: Session;
  pendingTakes: number;
  onFinish?: () => void;
  onPresence?: (artisanId: string, present: boolean) => void;
  onAddArtisan?: () => void;
}) {
  const [qr, setQr] = useState<string | null>(null);
  useEffect(() => {
    if (!session.joinQr) return;
    let cancelled = false;
    void import("qrcode").then((mod) =>
      mod.default.toDataURL(session.joinQr!, { margin: 1, width: 160 }).then((url) => {
        if (!cancelled) setQr(url);
      })
    );
    return () => {
      cancelled = true;
    };
  }, [session.joinQr]);

  return (
    <header className="panel grid gap-3 p-4" data-session-header>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="display-title break-all text-xl md:text-2xl" data-session-title>
            {session.title}
          </h1>
          {session.note ? <p className="mt-1 text-sm text-ink-500">{session.note}</p> : null}
          <p className="mt-1 text-sm text-ink-700">
            {[session.participants.map((p) => p.name.split(" ")[0]).join(", "), modeLabel(session), session.joinCode ? qs("joinCode", { code: session.joinCode }) : ""]
              .filter(Boolean)
              .join(" · ")}
          </p>
          {pendingTakes > 0 ? <p className="mt-1 text-xs text-purple-700">{qs("takesUploading", { count: pendingTakes })}</p> : null}
        </div>
        <div className="flex items-start gap-3">
          {/* A data: URL drawn once; next/image has nothing to optimise here. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {qr ? <img src={qr} alt={session.joinCode ? qs("joinCode", { code: session.joinCode }) : ""} className="h-20 w-20 rounded-md border border-line-200 bg-white" /> : null}
          {onFinish && session.state === "OPEN" ? (
            <button type="button" className="field-button" onClick={onFinish}>
              {qs("finish")}
            </button>
          ) : null}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2" aria-label={qs("present")}>
        {session.participants.map((p) => (
          <div key={p.artisanId} className="w-full sm:w-auto">
            <Toggle
              label={p.name}
              description={p.leftAt ? qs("away") : qs("present")}
              checked={!p.leftAt}
              onChange={(present) => onPresence?.(p.artisanId, present)}
            />
          </div>
        ))}
        {onAddArtisan ? (
          <button type="button" className="text-sm font-medium text-purple-700 underline" onClick={onAddArtisan}>
            {qs("addArtisan")}
          </button>
        ) : null}
      </div>
    </header>
  );
}
