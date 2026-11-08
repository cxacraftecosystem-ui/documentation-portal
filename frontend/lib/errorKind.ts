export type ErrorKind = "not-found" | "forbidden" | "session" | "server" | "offline" | "unavailable";

export type ErrorScreen = ErrorKind | "global";

export const RETRY_STEPS_SECONDS = [10, 20, 30] as const;

const UNAVAILABLE_STATUSES = new Set([408, 502, 503, 504]);

const NETWORK_MESSAGE = /failed to fetch|networkerror|network request failed|load failed|internet connection/i;

const TIMEOUT_NAMES = new Set(["TimeoutError", "AbortError", "MeTimeoutError"]);

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
