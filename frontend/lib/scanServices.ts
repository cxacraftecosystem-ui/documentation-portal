import { ApiError } from "@/lib/api";
import { resolveArtisanCard } from "@/lib/artisanCardApi";
import { uploadFailureIsOffline } from "@/lib/offline";
import { questionnaireApi, type Session } from "@/lib/questionnaireApi";
import { SCAN_CODECS } from "@/lib/scanCodecs";
import {
  SCAN_COPY,
  SCAN_HREF,
  SCAN_PORTAL,
  type CardAnswer,
  type RecordAnswer,
  type ScanAction,
  type ScanOutcome,
  type ScanServices,
  type SessionQr
} from "@/lib/scanDispatch";

const CARD_FAILED = "That card couldn't be looked up. Try again.";
const SESSION_FAILED = "That session couldn't be joined. Check the code.";

function serverDetail(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const detail = (error.payload as { detail?: unknown } | undefined)?.detail;
  if (typeof detail === "string" && detail.trim()) return detail;
  if (detail && typeof detail === "object" && typeof (detail as { message?: unknown }).message === "string") {
    return (detail as { message: string }).message;
  }
  return null;
}

async function resolveCard(raw: string): Promise<CardAnswer> {
  try {
    const answer = await resolveArtisanCard(raw);
    if (answer.status === "FOUND") {
      const a = answer.artisan;
      const detail = [a.craft, a.cluster ?? a.place].filter(Boolean).join(" · ");
      return { status: "found", artisanId: a.id, name: a.name, detail: detail || undefined };
    }
    return { status: "replaced", artisanId: answer.artisanId };
  } catch (error) {
    if (uploadFailureIsOffline(error)) return { status: "offline" };
    return { status: "refused", message: serverDetail(error) ?? CARD_FAILED };
  }
}

async function lookUpRecord(): Promise<RecordAnswer> {
  return { status: "missing" };
}

export function scanServices(): ScanServices {
  return { portal: SCAN_PORTAL, codecs: SCAN_CODECS, resolveCard, lookUpRecord };
}

export type ScanFollowUp = { navigate: string; external?: boolean } | { outcome: ScanOutcome };

async function joinFromQr(qr: SessionQr): Promise<Session> {
  return questionnaireApi.joinByKey(qr.clientKey);
}

export async function runScanAction(action: ScanAction): Promise<ScanFollowUp> {
  switch (action.type) {
    case "open":
      return { navigate: action.href, external: action.external };
    case "add":
      return { navigate: action.href };
    case "joinSession": {
      try {
        const session = action.joinCode
          ? await questionnaireApi.join(action.joinCode)
          : action.qr
            ? await joinFromQr(action.qr)
            : null;
        if (!session) return { outcome: { kind: "REFUSED", title: SESSION_FAILED } };
        return { navigate: SCAN_HREF.session(session.id) };
      } catch (error) {
        if (uploadFailureIsOffline(error)) return { outcome: { kind: "OFFLINE", title: SCAN_COPY.offline } };
        return { outcome: { kind: "REFUSED", title: serverDetail(error) ?? SESSION_FAILED } };
      }
    }
    case "redeem":
      return { outcome: { kind: "REFUSED", title: SCAN_COPY.joinCardElsewhere } };
    case "requestAccess":
      return { outcome: { kind: "REFUSED", title: SCAN_COPY.designerCode } };
  }
}
