export type ErrorKind = "not-found" | "forbidden" | "session" | "server" | "offline" | "unavailable";

export type ErrorScreen = ErrorKind | "global";

export const SESSION_ENDED_PATH = "/session-ended";

export const SIGN_IN_RETURN_KEY = "portal_sign_in_return";

export const RETRY_STEPS_SECONDS = [10, 20, 30] as const;

const UNAVAILABLE_STATUSES = new Set([408, 502, 503, 504]);

const NETWORK_MESSAGE = /failed to fetch|networkerror|network request failed|load failed|internet connection/i;

const TIMEOUT_NAMES = new Set(["TimeoutError", "AbortError", "MeTimeoutError"]);

const NO_RETURN = new Set(["/login", SESSION_ENDED_PATH, "/set-password", "/forgot-password"]);

const BASE = "https://portal.invalid";

export function retryDelaySeconds(round: number): number {
  const index = Math.min(Math.max(0, Math.floor(round)), RETRY_STEPS_SECONDS.length - 1);
  return RETRY_STEPS_SECONDS[index];
}

function statusOf(error: unknown): number | null {
  if (!error || typeof error !== "object") return null;
  const status = (error as { status?: unknown }).status;
  return typeof status === "number" && Number.isInteger(status) && status >= 100 && status <= 599 ? status : null;
}

export function browserOffline(): boolean {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}

export function errorKindOf(error: unknown, offline: boolean = browserOffline()): ErrorKind {
  const status = statusOf(error);
  if (status !== null) {
    if (status === 401) return "session";
    if (status === 403) return "forbidden";
    if (status === 404 || status === 410) return "not-found";
    if (UNAVAILABLE_STATUSES.has(status)) return offline ? "offline" : "unavailable";
    return "server";
  }
  if (offline) return "offline";
  if (error instanceof Error) {
    if (error instanceof TypeError && NETWORK_MESSAGE.test(error.message)) return "offline";
    if (TIMEOUT_NAMES.has(error.name)) return "unavailable";
  }
  return "server";
}

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

export type ErrorCopy = { title: string; line: string };

function capital(noun: string): string {
  return noun.charAt(0).toUpperCase() + noun.slice(1);
}

export function errorCopy(screen: ErrorScreen, options: { noun?: string; canRequest?: boolean } = {}): ErrorCopy {
  const { noun, canRequest } = options;
  switch (screen) {
    case "not-found":
      return noun
        ? { title: `${capital(noun)} not found`, line: `This ${noun} was removed, or the link is out of date.` }
        : { title: "Page not found", line: "The link may be out of date, or the page has moved." };
    case "forbidden":
      return {
        title: "No access",
        line: canRequest
          ? "Request access and an admin will review it."
          : `Your account can't open this ${noun ?? "page"}.`
      };
    case "session":
      return { title: "Session ended", line: "Sign in again to pick up where you left off." };
    case "server":
      return { title: "Something went wrong", line: "This page hit an error. Trying again usually fixes it." };
    case "offline":
      return { title: "You're offline", line: "Work saved offline stays safe. Reconnect to carry on." };
    case "unavailable":
      return { title: "Service unavailable", line: "The server isn't answering right now." };
    case "global":
      return { title: "The app didn't load", line: "Reload to start again. Saved work is safe." };
  }
}
