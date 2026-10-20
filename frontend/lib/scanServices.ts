/**
 * The Field Repository's half of the top-bar scanner: the network calls that `lib/scanDispatch.ts`
 * (shared byte for byte with the Designer Repository) is handed, and the action the person pressed.
 * Nothing here decides an outcome or writes copy beyond the server's own sentences.
 */

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
    // An unlinked Designer Repository card is refused by the server in its own words.
    return { status: "refused", message: serverDetail(error) ?? CARD_FAILED };
  }
}

/** The Field Repository reads no DPW record codes; the dispatcher refuses them before this. */
async function lookUpRecord(): Promise<RecordAnswer> {
  return { status: "missing" };
}

/** The live services for this portal. */
export function scanServices(): ScanServices {
  return { portal: SCAN_PORTAL, codecs: SCAN_CODECS, resolveCard, lookUpRecord };
}

/** What pressing an outcome's button led to: a place to go, or a new outcome to show. */
export type ScanFollowUp = { navigate: string; external?: boolean } | { outcome: ScanOutcome };

/**
 * A scanned session QR: join the session its `clientKey` names. Never create it — a row made here
 * from the QR's few fields would stand in for the lead's own and drop their place, language, note
 * and co-researchers. Until the lead's phone has synced the server answers "not synced yet".
 */
async function joinFromQr(qr: SessionQr): Promise<Session> {
  return questionnaireApi.joinByKey(qr.clientKey);
}

/** Run the action the person pressed. Never throws: every failure is an outcome with a sentence. */
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
