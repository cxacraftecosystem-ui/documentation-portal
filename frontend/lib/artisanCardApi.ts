/**
 * The artisan card routes (`backend/app/api/routes/artisan_cards.py`). Nothing is drawn until
 * `GET /artisan-cards/status` says cards are on; while they are off every other route answers 404.
 *
 * A Design Prototype Workshop card (issuer D) resolves here only through a link that portal made, so
 * there is no REMOTE answer on this side: the server says FOUND, REPLACED, or a 404 sentence.
 */
import { API_BASE, ApiError, apiFetch, assertApiConfigured, describeApiDetail, getToken } from "@/lib/api";
import { saveBlobToDevice } from "@/lib/saveToDevice";

export type ArtisanCardFace = {
  name: string;
  localName: string | null;
  craft: string | null;
  cluster: string | null;
  aadhaarLast4: string | null;
  photoMediaId: string | null;
};

export type ArtisanCard = {
  artisanId: string;
  code: string;
  shortCode: string;
  token: string;
  issuedAt: string;
  replacedCards: number;
  face: ArtisanCardFace;
  qrSvg: string;
};

export type CardSize = "id1" | "a6";
export type ReissueReason = "LOST" | "DAMAGED" | "OTHER";

export type CardArtisanSummary = {
  id: string;
  name: string;
  craft: string | null;
  cluster: string | null;
  place: string | null;
  aadhaarLast4: string | null;
};

export type CardResolution =
  | { status: "FOUND"; via: "LOCAL" | "LINK"; artisan: CardArtisanSummary }
  | { status: "REPLACED"; artisanId: string };

export type CardSheetRequest = { artisanIds: string[]; workshopId?: undefined } | { workshopId: string; artisanIds?: undefined };

/** The most cards one sheet request prints (the server's SHEET_LIMIT). */
export const CARD_SHEET_LIMIT = 200;

let statusPromise: Promise<{ enabled: boolean; issuer: string }> | null = null;

/** Whether cards are on here. Asked once per page load; a failure reads as "off" and is asked again. */
export function artisanCardStatus(): Promise<{ enabled: boolean; issuer: string }> {
  if (!statusPromise) {
    statusPromise = apiFetch<{ enabled?: boolean; issuer?: string | null }>("/artisan-cards/status")
      .then((answer) => ({ enabled: Boolean(answer?.enabled), issuer: answer?.issuer || "F" }))
      .catch(() => {
        statusPromise = null;
        return { enabled: false, issuer: "F" };
      });
  }
  return statusPromise;
}

export function getArtisanCard(artisanId: string): Promise<ArtisanCard> {
  return apiFetch(`/artisans/${encodeURIComponent(artisanId)}/card?source=WEB`);
}

export function reissueArtisanCard(artisanId: string, reason: ReissueReason): Promise<ArtisanCard> {
  return apiFetch(`/artisans/${encodeURIComponent(artisanId)}/card/reissue`, {
    method: "POST",
    body: JSON.stringify({ reason, source: "WEB" })
  });
}

/** The card PDF path for one artisan. */
export function artisanCardPdfPath(artisanId: string, size: CardSize, photo: boolean): string {
  return `/artisans/${encodeURIComponent(artisanId)}/card.pdf?size=${size}&photo=${photo ? 1 : 0}&source=WEB`;
}

export function sheetRequestBody(source: CardSheetRequest, size: CardSize, photo: boolean) {
  return { artisanIds: source.artisanIds ?? null, workshopId: source.workshopId ?? null, size, photo, source: "WEB" };
}

function fileNameFrom(header: string | null, fallback: string): string {
  const match = header ? /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header) : null;
  return match?.[1] ?? fallback;
}

/**
 * A PDF from the API, saved to the device. Built here rather than through `apiFetch`, which reads
 * every body as JSON or text and would mangle the bytes; same address check, token and error sentence.
 */
async function savePdf(path: string, init: RequestInit, fallbackName: string): Promise<string> {
  assertApiConfigured();
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body) headers.set("Content-Type", "application/json");
  const response = await fetch(`${API_BASE}/api${path}`, { ...init, headers, cache: "no-store" });
  if (!response.ok) {
    const type = response.headers.get("content-type") ?? "";
    const payload = type.includes("application/json") ? await response.json() : await response.text();
    const detail = typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    throw new ApiError(response.status, describeApiDetail(detail, "The card couldn't be printed. Try again."), payload);
  }
  const name = fileNameFrom(response.headers.get("content-disposition"), fallbackName);
  saveBlobToDevice(await response.blob(), name);
  return name;
}

export function downloadArtisanCard(artisanId: string, size: CardSize, photo: boolean): Promise<string> {
  return savePdf(artisanCardPdfPath(artisanId, size, photo), {}, "artisan-card.pdf");
}

/** A sheet of cards: A4 pages of ten ID-1 cards with crop marks, or one A6 card per page. */
export function downloadArtisanCardSheet(source: CardSheetRequest, size: CardSize = "id1", photo = true): Promise<string> {
  return savePdf(
    "/artisan-cards/sheet.pdf",
    { method: "POST", body: JSON.stringify(sheetRequestBody(source, size, photo)) },
    "artisan-cards.pdf"
  );
}

export function resolveArtisanCard(input: string): Promise<CardResolution> {
  return apiFetch("/artisan-cards/resolve", {
    method: "POST",
    body: JSON.stringify({ input, source: "WEB", consent: false })
  });
}

/** "XXXX XXXX 9012", or null when the last four are unknown. */
export function maskedLast4(last4: string | null | undefined): string | null {
  return last4 && /^\d{4}$/.test(last4) ? `XXXX XXXX ${last4}` : null;
}

/** The QR as an image source: the server's SVG through an `<img>`, so no markup from the wire is mounted. */
export function qrImageSrc(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}
