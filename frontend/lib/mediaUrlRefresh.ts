import { apiFetch } from "@/lib/api";
import type { MediaFile } from "@/lib/types";

export type RefreshableMedia = {
  id?: string | null;
  url?: string | null;
};

export type MediaFailure = { kind: "status"; status: number } | { kind: "unreadable" };

export type RefreshVerdict =
  | "refresh"
  | "offline"
  | "no-record"
  | "already-tried"
  | "budget-spent"
  | "gone";

const SIGNATURE_PARAM = "X-Amz-Signature";
const DATE_PARAM = "X-Amz-Date";
const EXPIRES_PARAM = "X-Amz-Expires";

const EXPIRY_SLACK_MS = 10_000;

export const MEDIA_REFRESH_BUDGET = 2;

export function isPresignedUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  return url.includes(`${SIGNATURE_PARAM}=`) && url.includes(`${EXPIRES_PARAM}=`);
}

export function presignedExpiryAt(url: string | null | undefined): number | null {
  if (!url) return null;
  let query: URLSearchParams;
  try {
    query = new URL(url, "https://placeholder.invalid").searchParams;
  } catch {
    return null;
  }
  const stamp = query.get(DATE_PARAM);
  const lifetime = Number(query.get(EXPIRES_PARAM));
  if (!stamp || !Number.isFinite(lifetime) || lifetime <= 0) return null;
  const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(stamp);
  if (!match) return null;
  const [, year, month, day, hour, minute, second] = match;
  const signedAt = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second)
  );
  if (!Number.isFinite(signedAt)) return null;
  return signedAt + lifetime * 1000;
}

export function presignedUrlExpired(url: string | null | undefined, nowMs: number): boolean {
  const expiresAt = presignedExpiryAt(url);
  if (expiresAt === null) return false;
  return nowMs >= expiresAt - EXPIRY_SLACK_MS;
}

export const MEDIA_NEAR_EXPIRY_MS = 60_000;

export function presignedUrlExpiresSoon(
  url: string | null | undefined,
  nowMs: number,
  windowMs: number = MEDIA_NEAR_EXPIRY_MS
): boolean {
  const expiresAt = presignedExpiryAt(url);
  if (expiresAt === null) return false;
  return nowMs >= expiresAt - Math.max(windowMs, EXPIRY_SLACK_MS);
}

export function attemptKey(mediaId: string, url: string | null | undefined): string {
  return `${mediaId}\n${url ?? ""}`;
}

export function refreshVerdict(input: {
  mediaId: string | null | undefined;
  url: string | null | undefined;
  failure: MediaFailure;
  online: boolean;
  attempted: (key: string) => boolean;
  spent: (mediaId: string) => number;
}): RefreshVerdict {
  const { mediaId, url, failure, online, attempted, spent } = input;
  if (!mediaId) return "no-record";
  if (!online) return "offline";
  if (attempted(attemptKey(mediaId, url))) return "already-tried";
  if (spent(mediaId) >= MEDIA_REFRESH_BUDGET) return "budget-spent";
  if (failure.kind === "unreadable") return "refresh";
  return failure.status === 403 ? "refresh" : "gone";
}

export async function probeMediaUrl(url: string): Promise<MediaFailure> {
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: { Range: "bytes=0-0" },
      cache: "no-store"
    });
    return { kind: "status", status: response.status };
  } catch {
    return { kind: "unreadable" };
  }
}

const attempts = new Set<string>();

const spend = new Map<string, number>();

const inFlight = new Map<string, Promise<string | null>>();

const missing = new Set<string>();

export function mediaFileMissing(mediaId: string | null | undefined): boolean {
  return Boolean(mediaId) && missing.has(mediaId as string);
}

export function hasAttempted(key: string): boolean {
  return attempts.has(key);
}

export function refreshesSpent(mediaId: string): number {
  return spend.get(mediaId) ?? 0;
}

export function isOnline(): boolean {
  if (typeof navigator === "undefined") return true;
  return typeof navigator.onLine === "boolean" ? navigator.onLine : true;
}

export function resetMediaRefreshLedger(): void {
  attempts.clear();
  spend.clear();
  inFlight.clear();
  missing.clear();
}

export async function refreshMediaUrl(
  mediaId: string,
  failedUrl: string | null | undefined
): Promise<string | null> {
  attempts.add(attemptKey(mediaId, failedUrl));
  const pending = inFlight.get(mediaId);
  if (pending) return pending;
  spend.set(mediaId, refreshesSpent(mediaId) + 1);

  const request = apiFetch<MediaFile & { fileMissing?: boolean }>(`/media/${mediaId}?verify=1`)
    .then((row) => {
      if (row?.fileMissing) {
        missing.add(mediaId);
        return null;
      }
      return row?.url ?? null;
    })
    .catch(() => null)
    .finally(() => {
      inFlight.delete(mediaId);
    });
  inFlight.set(mediaId, request);
  return request;
}

export async function resolveFailedMediaUrl(media: RefreshableMedia): Promise<string | null> {
  const url = media.url ?? null;
  if (!url) return null;

  const local = refreshVerdict({
    mediaId: media.id,
    url,
    failure: { kind: "unreadable" },
    online: isOnline(),
    attempted: hasAttempted,
    spent: refreshesSpent
  });
  if (local !== "refresh") return null;

  const failure = await probeMediaUrl(url);
  const verdict = refreshVerdict({
    mediaId: media.id,
    url,
    failure,
    online: isOnline(),
    attempted: hasAttempted,
    spent: refreshesSpent
  });
  if (verdict !== "refresh") return null;
  const fresh = await refreshMediaUrl(media.id as string, url);
  return fresh && fresh !== url ? fresh : null;
}
