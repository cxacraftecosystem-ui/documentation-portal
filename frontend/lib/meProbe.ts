import { ApiError } from "@/lib/api";
import type { User } from "@/lib/types";

export const ME_TIMEOUT_MS = 8000;

export const ME_RETRY_DELAYS_MS = [2000, 4000, 8000, 15000, 30000] as const;

export const ME_CACHE_KEY = "portal_me_cache_v1";

export type MeFailure = "signedOut" | "replaced" | "unreachable";

export type MeReach = "ok" | "retrying";

export class MeTimeoutError extends Error {
  constructor() {
    super("The server took too long to answer.");
    this.name = "MeTimeoutError";
  }
}

export function meFailure(error: unknown, replaced: boolean): MeFailure {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) return replaced ? "replaced" : "signedOut";
  return "unreachable";
}

export function meRetryDelay(attempt: number): number {
  const index = Math.min(Math.max(0, Math.floor(attempt)), ME_RETRY_DELAYS_MS.length - 1);
  return ME_RETRY_DELAYS_MS[index];
}

export async function withinTimeout<T>(load: (signal: AbortSignal) => Promise<T>, ms: number = ME_TIMEOUT_MS): Promise<T> {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expiry = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      controller.abort();
      reject(new MeTimeoutError());
    }, ms);
  });
  try {
    return await Promise.race([load(controller.signal), expiry]);
  } finally {
    clearTimeout(timer);
  }
}

export function markerFingerprint(marker: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < marker.length; index += 1) {
    hash ^= marker.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(36);
}

type Stored = { session: string; user: User };

function storage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function readCachedMe(marker: string | null): User | null {
  if (!marker) return null;
  try {
    const raw = storage()?.getItem(ME_CACHE_KEY);
    if (!raw) return null;
    const stored = JSON.parse(raw) as Partial<Stored>;
    if (stored.session !== markerFingerprint(marker) || !stored.user || typeof stored.user !== "object" || !stored.user.id) return null;
    return stored.user;
  } catch {
    return null;
  }
}

export function writeCachedMe(marker: string | null, user: User | null): void {
  try {
    const store = storage();
    if (!store) return;
    if (!marker || !user) {
      store.removeItem(ME_CACHE_KEY);
      return;
    }
    const stored: Stored = { session: markerFingerprint(marker), user };
    store.setItem(ME_CACHE_KEY, JSON.stringify(stored));
  } catch {
  }
}

export function forgetCachedMe(): void {
  writeCachedMe(null, null);
}
