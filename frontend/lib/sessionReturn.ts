export const SESSION_ENDED_PATH = "/session-ended";

export const SIGN_IN_RETURN_KEY = "portal_sign_in_return";

const NO_RETURN = new Set(["/login", SESSION_ENDED_PATH, "/set-password", "/forgot-password"]);

const BASE = "https://portal.invalid";

export function safeNext(value: string | null | undefined): string | null {
  if (!value || !value.startsWith("/") || value.startsWith("//")) return null;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 0x20 || code === 0x7f || code === 0x5c) return null;
  }
  try {
    const url = new URL(value, BASE);
    if (url.origin !== BASE || NO_RETURN.has(url.pathname)) return null;
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return null;
  }
}

function withNext(path: string, next: string | null | undefined): string {
  const safe = safeNext(next);
  return safe ? `${path}?next=${encodeURIComponent(safe)}` : path;
}

export function signInHref(next: string | null | undefined): string {
  return withNext("/login", next);
}

export function sessionEndedHref(next: string | null | undefined): string {
  return withNext(SESSION_ENDED_PATH, next);
}

export function nextFromSearch(search: string): string | null {
  try {
    return safeNext(new URLSearchParams(search).get("next"));
  } catch {
    return null;
  }
}

export function rememberSignInReturn(search: string): void {
  const next = nextFromSearch(search);
  if (!next) return;
  try {
    window.sessionStorage.setItem(SIGN_IN_RETURN_KEY, next);
  } catch {
  }
}

export function takeSignInReturn(fallback = "/dashboard"): string {
  let stored: string | null = null;
  try {
    stored = window.sessionStorage.getItem(SIGN_IN_RETURN_KEY);
    window.sessionStorage.removeItem(SIGN_IN_RETURN_KEY);
  } catch {
    stored = null;
  }
  return nextFromSearch(window.location.search) ?? safeNext(stored) ?? fallback;
}
