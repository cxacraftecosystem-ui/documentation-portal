/**
 * The top-bar scanner's one dispatcher: what a scanned or typed code is, and what pressing its
 * button does. BYTE-IDENTICAL in both portals (designer-portal and documentation-portal); the only
 * input that differs is {@link SCAN_PORTAL}, read from the portal's naming config. Android's
 * `data/ScanDispatch.kt` carries the same outcome names, order and copy, and both are held to
 * `e2e/fixtures/scan-dispatch.json` (REQUIREMENTS_2026-10-20 §6).
 *
 * Order, fixed: DPW2 join card → Field Repository session → artisan card → DPW1 → DPW3 → refusal.
 *
 * Rules every branch keeps:
 *  - It never navigates. An outcome names what was found and offers at most one action.
 *  - A refusal never echoes the input. An Aadhaar Secure QR, a payment QR or an authenticator link
 *    is answered with one fixed sentence and none of its contents are kept, logged or returned.
 *  - A join card's secret travels only inside the action, never into a title or a detail line.
 *  - "Not found" never says whether a record exists (the API answers 404 for both on purpose).
 */

import { CARD_CHECK_MESSAGE, looksLikeArtisanCard, parseArtisanCard } from "@/lib/artisanCard";
import { PORTAL } from "@/lib/namingPortal";

export type ScanPortal = "designer" | "field";

/** Which portal this build is. The naming config is the one line that differs between the repos. */
export const SCAN_PORTAL: ScanPortal = (PORTAL as string) === "FieldRepository" ? "field" : "designer";

export type ScanOutcomeKind =
  | "REDEEM_JOIN_CARD"
  | "JOIN_SESSION"
  | "OPEN"
  | "ADD_FROM_PEER"
  | "JOIN_WORKSHOP"
  | "POINTER"
  | "REFUSED"
  | "NOT_FOUND"
  | "OFFLINE";

/** Every sentence the dispatcher says. Identical in both portals and on Android. */
export const SCAN_COPY = {
  joinCard: "A workshop join card.",
  redeem: "Redeem",
  joined: "You're in.",
  openWorkshop: "Open workshop",
  joinCardElsewhere: "This card opens a Designer Repository workshop.",
  session: "A questionnaire session.",
  joinSession: "Join",
  sessionElsewhere: "This is a Field Repository session.",
  open: "Open",
  cardReplaced: "This card was replaced.",
  fromPeer: "From the Field Repository.",
  addArtisan: "Add artisan",
  askToJoin: "Ask to join",
  thisWorkshop: "this workshop",
  prototype: "A prototype tag. Open the workshop's Cards & tags.",
  fileCheck: "A file check code. Open the received file in Questionnaires to check it.",
  designerCode: "This code is from the Designer Repository.",
  unknown: "This code isn't one this app reads.",
  notFound: "No record matches this code.",
  offline: "You're offline. Try again when connected.",
  empty: "Scan or type a code.",
  scanACode: "Scan a code",
  useCamera: "Use the camera",
  stopCamera: "Stop camera",
  choosePhoto: "Choose a photo",
  typeCode: "Type the code",
  lookUp: "Look up"
} as const;

export type ScanRecordType =
  | "artisan"
  | "craft"
  | "workshop"
  | "product"
  | "process"
  | "tool"
  | "questionnaire"
  | "media"
  | "designWorkshop"
  | "prototype";

/** The heading word per record type — `workshopRecordTypeLabel` / Android's label, verbatim. */
export const SCAN_RECORD_LABEL: Record<ScanRecordType, string> = {
  artisan: "Artisan",
  craft: "Craft",
  workshop: "Workshop",
  product: "Product",
  process: "Process",
  tool: "Tool",
  questionnaire: "Interview",
  media: "Media file",
  designWorkshop: "Design workshop",
  prototype: "Prototype"
};

/** Where the actions land. One table so the web clients cannot drift on a route. */
export const SCAN_HREF = {
  artisan: (id: string) => `/artisans/${encodeURIComponent(id)}`,
  addFromPeer: (card: string) => `/artisans/new?card=${encodeURIComponent(card)}`,
  designWorkshop: (id: string) => `/design-workshops/${encodeURIComponent(id)}`,
  designWorkshops: "/design-workshops",
  questionnaires: "/questionnaires",
  session: (id: string) => `/questionnaire/sessions/${encodeURIComponent(id)}`
} as const;

/** A Field Repository session QR (`JoinQr` on Android): bare v1 JSON, or `FRS1:` + the same JSON. */
export type SessionQr = {
  v: 1;
  clientKey: string;
  questionnaireId: string;
  workshopId: string | null;
  sessionDate: string;
  participants: string[];
};

export const SESSION_QR_PREFIX = "FRS1:";

export type ScanAction =
  | { type: "open"; label: string; href: string; external?: boolean }
  | { type: "add"; label: string; href: string }
  | { type: "redeem"; label: string; workshopId: string; code: string }
  | { type: "joinSession"; label: string; joinCode: string | null; qr: SessionQr | null }
  | { type: "requestAccess"; label: string; workshopId: string; scannedCode: string };

export type ScanOutcome = { kind: ScanOutcomeKind; title: string; detail?: string; action?: ScanAction };

/** Designer-only decoders (the DPW grammars live only in the designer portal). */
export type ScanCodecs = {
  decodeJoinCard: (raw: string) => { ok: true; workshopId: string; code: string } | { ok: false; message: string };
  decodeRecordCode: (
    raw: string
  ) => { ok: true; recordType: ScanRecordType; id: string; code: string } | { ok: false; message: string };
};

export type CardAnswer =
  | { status: "found"; artisanId: string; name: string; detail?: string }
  | { status: "replaced"; artisanId: string }
  /** A card from the other portal with no linked artisan here. */
  | { status: "peer" }
  | { status: "refused"; message: string }
  | { status: "offline" };

export type RecordAnswer =
  | { status: "found"; label: string; detail?: string; href: string; external?: boolean }
  /** `title`: a design workshop the reader cannot open yet, named for "Ask to join {title}" (D34). */
  | { status: "missing"; title?: string }
  | { status: "offline" };

export type ScanServices = {
  portal: ScanPortal;
  codecs?: ScanCodecs;
  resolveCard: (raw: string) => Promise<CardAnswer>;
  /** `code` is the canonical scanned code, which the design-workshop title lookup needs. */
  lookUpRecord: (recordType: ScanRecordType, id: string, code: string) => Promise<RecordAnswer>;
};

/** What the pure half decided: a final outcome, or the one lookup still owed. */
export type ScanStep =
  | { step: "outcome"; outcome: ScanOutcome }
  | { step: "card"; raw: string }
  | { step: "record"; recordType: ScanRecordType; id: string; code: string };

const refused = (title: string): ScanStep => ({ step: "outcome", outcome: { kind: "REFUSED", title } });

const DPW = /^DPW\d*:/;
const JOIN_CARD = /^DPW\d+:J:/;
const FILE_CHECK = /^DPW3:H:/;
/** Six letters or digits with at least one digit, so a six-letter word is never read as a session. */
const SESSION_CODE = /^(?=[A-Z]*[0-9])[A-Z0-9]{6}$/;

/** A typed 6-character session code (at least one digit), normalised (upper case, no spaces or hyphens), or null. */
export function normaliseSessionCode(raw: string): string | null {
  const code = raw.toUpperCase().replace(/[\s-]/g, "");
  return SESSION_CODE.test(code) ? code : null;
}

/** A session QR's payload, or null for anything else. Missing `v` reads as 1, as on Android. */
export function decodeSessionQr(raw: string): SessionQr | null {
  let text = raw.trim();
  if (text.toUpperCase().startsWith(SESSION_QR_PREFIX)) text = text.slice(SESSION_QR_PREFIX.length).trim();
  if (!text.startsWith("{")) return null;
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const o = value as Record<string, unknown>;
  const str = (key: string) => (typeof o[key] === "string" ? (o[key] as string).trim() : "");
  if ((o.v ?? 1) !== 1) return null;
  if (!str("clientKey") || !str("questionnaireId") || !str("sessionDate")) return null;
  const participants = Array.isArray(o.participants) ? o.participants.filter((p): p is string => typeof p === "string") : [];
  return {
    v: 1,
    clientKey: str("clientKey"),
    questionnaireId: str("questionnaireId"),
    workshopId: str("workshopId") || null,
    sessionDate: str("sessionDate"),
    participants
  };
}

/** The pure half: classify in the fixed order. No network, no storage. */
export function classifyScan(raw: string, portal: ScanPortal, codecs?: ScanCodecs): ScanStep {
  const text = (raw ?? "").trim();
  if (!text) return refused(SCAN_COPY.empty);
  const upper = text.replace(/\s+/g, "").toUpperCase();

  // 1. DPW2 join card.
  if (JOIN_CARD.test(upper)) {
    if (portal === "field" || !codecs) return refused(SCAN_COPY.joinCardElsewhere);
    const card = codecs.decodeJoinCard(text);
    if (!card.ok) return refused(card.message);
    return {
      step: "outcome",
      outcome: {
        kind: "REDEEM_JOIN_CARD",
        title: SCAN_COPY.joinCard,
        action: { type: "redeem", label: SCAN_COPY.redeem, workshopId: card.workshopId, code: card.code }
      }
    };
  }

  // 2. Field Repository session: the QR (bare or FRS1:) or a typed 6-character code with a digit.
  const qr = decodeSessionQr(text);
  const joinCode = qr ? null : normaliseSessionCode(text);
  if (qr || joinCode) {
    if (portal === "designer") return refused(SCAN_COPY.sessionElsewhere);
    return {
      step: "outcome",
      outcome: {
        kind: "JOIN_SESSION",
        title: SCAN_COPY.session,
        action: { type: "joinSession", label: SCAN_COPY.joinSession, joinCode, qr }
      }
    };
  }

  // 3. Artisan card, either portal's.
  if (parseArtisanCard(text).ok) return { step: "card", raw: text };
  if (looksLikeArtisanCard(text)) return refused(CARD_CHECK_MESSAGE);

  // 5 before 4 only because DPW1's decoder would read DPW3 as "a newer format".
  if (FILE_CHECK.test(upper)) {
    if (portal === "field") return refused(SCAN_COPY.designerCode);
    return {
      step: "outcome",
      outcome: {
        kind: "POINTER",
        title: SCAN_COPY.fileCheck,
        action: { type: "open", label: SCAN_COPY.open, href: SCAN_HREF.questionnaires }
      }
    };
  }

  // 4. DPW1 record codes.
  if (DPW.test(upper)) {
    if (portal === "field" || !codecs) return refused(SCAN_COPY.designerCode);
    const ref = codecs.decodeRecordCode(text);
    if (!ref.ok) return refused(ref.message);
    if (ref.recordType === "prototype") {
      return {
        step: "outcome",
        outcome: {
          kind: "POINTER",
          title: SCAN_COPY.prototype,
          action: { type: "open", label: SCAN_COPY.open, href: SCAN_HREF.designWorkshops }
        }
      };
    }
    return { step: "record", recordType: ref.recordType, id: ref.id, code: ref.code };
  }

  // 6. Anything else — an Aadhaar Secure QR, otpauth://, upi://, a URL — one sentence, nothing kept.
  return refused(SCAN_COPY.unknown);
}

/** Classify, then run the one lookup the code owes. A result for every input; never a throw. */
export async function dispatchScan(raw: string, services: ScanServices): Promise<ScanOutcome> {
  const step = classifyScan(raw, services.portal, services.codecs);
  if (step.step === "outcome") return step.outcome;

  if (step.step === "card") {
    let answer: CardAnswer;
    try {
      answer = await services.resolveCard(step.raw);
    } catch {
      answer = { status: "offline" };
    }
    switch (answer.status) {
      case "found":
        return {
          kind: "OPEN",
          title: `${SCAN_RECORD_LABEL.artisan}: ${answer.name}`,
          detail: answer.detail,
          action: { type: "open", label: SCAN_COPY.open, href: SCAN_HREF.artisan(answer.artisanId) }
        };
      case "replaced":
        return {
          kind: "OPEN",
          title: SCAN_COPY.cardReplaced,
          action: { type: "open", label: SCAN_COPY.open, href: SCAN_HREF.artisan(answer.artisanId) }
        };
      case "peer":
        if (services.portal === "field") return { kind: "REFUSED", title: SCAN_COPY.notFound };
        return {
          kind: "ADD_FROM_PEER",
          title: SCAN_COPY.fromPeer,
          action: { type: "add", label: SCAN_COPY.addArtisan, href: SCAN_HREF.addFromPeer(step.raw) }
        };
      case "refused":
        return { kind: "REFUSED", title: answer.message };
      case "offline":
        return { kind: "OFFLINE", title: SCAN_COPY.offline };
    }
  }

  let answer: RecordAnswer;
  try {
    answer = await services.lookUpRecord(step.recordType, step.id, step.code);
  } catch {
    answer = { status: "offline" };
  }
  if (answer.status === "offline") return { kind: "OFFLINE", title: SCAN_COPY.offline };
  if (answer.status === "missing") {
    if (step.recordType === "designWorkshop") {
      // The workshop's title when the server names it for this code (owner decision, D34);
      // "this workshop" when it cannot (offline, deleted, or an older server).
      const name = answer.title?.trim();
      return {
        kind: "JOIN_WORKSHOP",
        title: `${SCAN_COPY.askToJoin} ${name || SCAN_COPY.thisWorkshop}`,
        action: { type: "requestAccess", label: SCAN_COPY.askToJoin, workshopId: step.id, scannedCode: step.code }
      };
    }
    return { kind: "NOT_FOUND", title: SCAN_COPY.notFound };
  }
  return {
    kind: "OPEN",
    title: `${SCAN_RECORD_LABEL[step.recordType]}: ${answer.label}`,
    detail: answer.detail,
    action: { type: "open", label: SCAN_COPY.open, href: answer.href, external: answer.external }
  };
}
