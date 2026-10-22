"use client";

import { useMemo } from "react";

import { Toggle } from "@/components/ui/Toggle";
import type { Session } from "@/lib/questionnaire/api";
import { modeLabel } from "@/lib/questionnaire/view";
import { qs } from "@/lib/questionnaire/qs";
import { encodeQr, qrSvgPath } from "@/lib/qrEncode";

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
  onRemove,
  onAddArtisan
}: {
  session: Session;
  pendingTakes: number;
  onFinish?: () => void;
  onPresence?: (artisanId: string, present: boolean) => void;
  /** Remove an artisan from the session; the caller asks P10 first when they have takes. */
  onRemove?: (artisanId: string) => void;
  onAddArtisan?: () => void;
}) {
  // The join QR, drawn as an SVG by the repository's own encoder (lib/qrEncode.ts, shared by both
  // portals): no QR package, and nothing to wait for.
  const qr = useMemo(() => {
    if (!session.joinQr) return null;
    try {
      return qrSvgPath(encodeQr(session.joinQr, "M"));
    } catch {
      return null;
    }
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
          {qr ? (
            <svg
              viewBox={`0 0 ${qr.extent} ${qr.extent}`}
              role="img"
              aria-label={session.joinCode ? qs("joinCode", { code: session.joinCode }) : qs("joinCode", { code: "" })}
              className="h-20 w-20 rounded-md border border-line-200 bg-white"
              data-join-qr
            >
              <path d={qr.path} fill="#000" shapeRendering="crispEdges" />
            </svg>
          ) : null}
          {onFinish && session.state === "OPEN" ? (
            <button type="button" className="field-button" onClick={onFinish}>
              {qs("finish")}
            </button>
          ) : null}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2" aria-label={qs("present")}>
        {session.participants.map((p) => (
          <div key={p.artisanId} className="flex w-full items-center gap-2 sm:w-auto">
            <div className="min-w-0 flex-1">
              <Toggle
                label={p.name}
                description={p.leftAt ? qs("away") : qs("present")}
                checked={!p.leftAt}
                onChange={(present) => onPresence?.(p.artisanId, present)}
              />
            </div>
            {onRemove && session.participants.length > 1 ? (
              <button
                type="button"
                className="text-xs font-medium text-ink-500 underline hover:text-error-600"
                aria-label={qs("p10Title", { name: p.name })}
                data-remove-participant={p.artisanId}
                onClick={() => onRemove(p.artisanId)}
              >
                {qs("remove")}
              </button>
            ) : null}
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
