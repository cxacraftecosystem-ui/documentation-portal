import { resetSessionStateForTests } from "../../lib/session";
import { SESSION_MARKER_COOKIE } from "../../lib/sessionCookie";

export type FakeSessionCookie = {
  readonly marker: string | null;
  current(): string | null;
  set(marker: string | null): void;
};

export function installSessionCookie(label: string | null): FakeSessionCookie {
  resetSessionStateForTests();
  const marker = label;
  let value = marker;
  (globalThis as Record<string, unknown>).document = {
    get cookie() {
      return value ? `${SESSION_MARKER_COOKIE}=${value}` : "";
    }
  };
  return {
    marker,
    current: () => value,
    set: (next) => {
      value = next;
    }
  };
}

export function removeSessionCookie(): void {
  delete (globalThis as Record<string, unknown>).document;
}

export function isSessionEndCall(input: unknown): boolean {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : (input as { url?: string })?.url ?? "";
  return url.endsWith("/api/session/end");
}
