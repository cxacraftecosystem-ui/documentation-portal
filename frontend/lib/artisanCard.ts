/**
 * Artisan QR card codes: parse and format only (the server signs; no client ever holds a key).
 *
 * QR payload `CXA1:<issuer><keyVersion>:<code>:<tag>`; short code `D-7K3QM-9XA2F-HQ`. The rules are
 * `backend/app/services/artisan_card.py`'s, held together by `shared/artisan-card-vectors.json`
 * (`e2e/artisan-card-unit.spec.ts`). Both portals carry this file byte for byte.
 */

export const CARD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const CARD_PREFIX = "CXA1";
export const CARD_ISSUERS = ["D", "F"] as const;
export type CardIssuer = (typeof CARD_ISSUERS)[number];

const CODE_LENGTH = 10;
const TAG_LENGTH = 16;
const SHORT_LENGTH = 1 + CODE_LENGTH + 2;

export type CardParseFailure = "NOT_A_CARD" | "BAD_CHECK" | "BAD_ISSUER" | "BAD_CHARACTER";

export type ParsedCard = {
  issuer: CardIssuer;
  code: string;
  keyVersion: number | null;
  tag: string | null;
  typed: boolean;
};

export type CardParse = { ok: true; card: ParsedCard } | { ok: false; reason: CardParseFailure };

const isCrockford = (text: string) => [...text].every((ch) => CARD_ALPHABET.includes(ch));
const mapConfusables = (text: string) => text.replace(/[IL]/g, "1").replace(/O/g, "0");
const isIssuer = (ch: string): ch is CardIssuer => ch === "D" || ch === "F";

function fnv1a32(text: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

/** The two check characters: FNV-1a 32-bit over `CXA1:<issuer>:<code>`, mod 1024. */
export function cardCheck(issuer: string, code: string): string {
  const value = fnv1a32(`${CARD_PREFIX}:${issuer}:${code}`) % 1024;
  return CARD_ALPHABET[value >> 5] + CARD_ALPHABET[value & 31];
}

/** `D-7K3QM-9XA2F-HQ`, what is printed under the QR. */
export function cardShortCode(issuer: string, code: string): string {
  return `${issuer}-${code.slice(0, 5)}-${code.slice(5)}-${cardCheck(issuer, code)}`;
}

/** A QR payload or a typed short code. Anything that is not a card (an old DPW1 code) is NOT_A_CARD. */
export function parseArtisanCard(raw: string | null | undefined): CardParse {
  if (typeof raw !== "string") return { ok: false, reason: "NOT_A_CARD" };
  const cleaned = raw.toUpperCase().replace(/[\s\-.]/g, "");
  if (!cleaned) return { ok: false, reason: "NOT_A_CARD" };
  if (cleaned.startsWith(`${CARD_PREFIX}:`)) {
    const parts = cleaned.split(":");
    if (parts.length !== 4 || parts[1].length !== 2) return { ok: false, reason: "NOT_A_CARD" };
    const [, head, rawBody, rawTag] = parts;
    if (rawBody.includes("U") || rawTag.includes("U")) return { ok: false, reason: "BAD_CHARACTER" };
    const issuer = head[0];
    const version = head[1];
    if (!isIssuer(issuer)) return { ok: false, reason: "BAD_ISSUER" };
    if (!"123456789".includes(version)) return { ok: false, reason: "NOT_A_CARD" };
    const body = mapConfusables(rawBody);
    const tag = mapConfusables(rawTag);
    if (body.length !== CODE_LENGTH || tag.length !== TAG_LENGTH) return { ok: false, reason: "NOT_A_CARD" };
    if (!isCrockford(body) || !isCrockford(tag)) return { ok: false, reason: "NOT_A_CARD" };
    return { ok: true, card: { issuer, code: body, keyVersion: Number(version), tag, typed: false } };
  }
  if (cleaned.includes(":")) return { ok: false, reason: "NOT_A_CARD" };
  if (cleaned.includes("U")) return { ok: false, reason: "BAD_CHARACTER" };
  const mapped = mapConfusables(cleaned);
  if (mapped.length !== SHORT_LENGTH || !isCrockford(mapped)) return { ok: false, reason: "NOT_A_CARD" };
  const issuer = mapped[0];
  const body = mapped.slice(1, 1 + CODE_LENGTH);
  const given = mapped.slice(1 + CODE_LENGTH);
  if (!isIssuer(issuer)) return { ok: false, reason: "BAD_ISSUER" };
  if (cardCheck(issuer, body) !== given) return { ok: false, reason: "BAD_CHECK" };
  return { ok: true, card: { issuer, code: body, keyVersion: null, tag: null, typed: true } };
}

/** Is this meant as an artisan card, even if mistyped? (A DPW1 code and free text are not.) */
export function looksLikeArtisanCard(raw: string | null | undefined): boolean {
  const parsed = parseArtisanCard(raw);
  if (parsed.ok || parsed.reason === "BAD_CHECK" || parsed.reason === "BAD_CHARACTER") return true;
  return parsed.reason === "BAD_ISSUER" && (raw ?? "").trim().toUpperCase().startsWith(`${CARD_PREFIX}:`);
}

/** The sentence for a code that is a card but cannot be right. Same words as the server's 422. */
export const CARD_CHECK_MESSAGE = "Check the code. One character is wrong.";

/** The typed box's placeholder. */
export const CARD_PLACEHOLDER = "D-XXXXX-XXXXX-XX";
