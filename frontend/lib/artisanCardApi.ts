import { API_BASE, ApiError, apiFetch, assertApiConfigured, describeApiDetail } from "@/lib/api";
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

export const CARD_SHEET_LIMIT = 200;

let statusPromise: Promise<{ enabled: boolean; issuer: string }> | null = null;

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

async function savePdf(path: string, init: RequestInit, fallbackName: string): Promise<string> {
  assertApiConfigured();
  const headers = new Headers(init.headers);
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

export function maskedLast4(last4: string | null | undefined): string | null {
  return last4 && /^\d{4}$/.test(last4) ? `XXXX XXXX ${last4}` : null;
}

const SVG_NS = "http://www.w3.org/2000/svg";

export function standaloneSvg(svg: string): string {
  const trimmed = svg.trim();
  if (!/^<svg[\s>]/i.test(trimmed) || /^<svg[^>]*\sxmlns=/i.test(trimmed)) return trimmed;
  return trimmed.replace(/^<svg/i, `<svg xmlns="${SVG_NS}"`);
}

export function qrImageSrc(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(standaloneSvg(svg))}`;
}
