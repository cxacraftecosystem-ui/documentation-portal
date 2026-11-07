import {
  INSECURE_SESSION_MARKER_COOKIE,
  LEGACY_TOKEN_STORAGE_KEY,
  SESSION_ADOPT_PATH,
  SESSION_END_PATH,
  SESSION_MARKER_COOKIE
} from "@/lib/sessionCookie";

let endedMarker: string | null = null;

let migration: Promise<void> | null = null;

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  let raw = "";
  try {
    raw = document.cookie ?? "";
  } catch {
    return null;
  }
  for (const part of raw.split(";")) {
    const index = part.indexOf("=");
    if (index < 0) continue;
    if (part.slice(0, index).trim() !== name) continue;
    const value = part.slice(index + 1).trim();
    return value || null;
  }
  return null;
}

export function getSessionMarker(): string | null {
  const marker = readCookie(SESSION_MARKER_COOKIE) ?? readCookie(INSECURE_SESSION_MARKER_COOKIE);
  if (!marker || marker === endedMarker) return null;
  return marker;
}

export function endSession(): void {
  const marker = getSessionMarker();
  if (!marker) return;
  endedMarker = marker;
  if (typeof fetch === "undefined") return;
  try {
    void fetch(SESSION_END_PATH, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ marker }),
      cache: "no-store",
      keepalive: true
    }).catch(() => undefined);
  } catch {
  }
}

export function resetSessionStateForTests(): void {
  endedMarker = null;
  migration = null;
}

function readLegacyToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(LEGACY_TOKEN_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function hasLegacyToken(): boolean {
  return Boolean(readLegacyToken());
}

const SIGNED_OUT_ROUTES = ["/", "/login", "/forgot-password", "/set-password", "/terms", "/report-share", "/report-schedule", "/report-download", "/asset-links"];

export function isSignedOutRoute(pathname: string): boolean {
  const path = pathname.replace(/\/+$/, "") || "/";
  return SIGNED_OUT_ROUTES.some((route) => (route === "/" ? path === "/" : path === route || path.startsWith(`${route}/`)));
}

export function mayHoldSession(pathname: string, legacyToken: boolean): boolean {
  return Boolean(getSessionMarker()) || legacyToken || !isSignedOutRoute(pathname);
}

function dropLegacyToken(): void {
  try {
    window.localStorage.removeItem(LEGACY_TOKEN_STORAGE_KEY);
  } catch {
  }
}

export function migrateStoredToken(): Promise<void> {
  if (migration) return migration;
  migration = (async () => {
    const legacy = readLegacyToken();
    if (!legacy) return;
    if (getSessionMarker()) {
      dropLegacyToken();
      return;
    }
    try {
      const response = await fetch(SESSION_ADOPT_PATH, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: legacy }),
        cache: "no-store"
      });
      let verdict: unknown = undefined;
      try {
        verdict = ((await response.json()) as { adopted?: unknown }).adopted;
      } catch {
        verdict = undefined;
      }
      if (typeof verdict === "boolean") dropLegacyToken();
    } catch {
    }
  })();
  return migration;
}
