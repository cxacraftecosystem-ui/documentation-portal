/**
 * GOOGLE IDENTITY SERVICES — the script both Google buttons draw from.
 *
 * Two places run a Google sign-in on the web: `/login`, to open a session, and the Settings card
 * that SETS A FIRST PASSWORD, which since 2026-10-17 must carry a Google ID token minted just now
 * (`POST /auth/change-password` refuses a stale one, or one for another mailbox). Both load the same
 * script from the same URL and use the same `window.google` shape, declared here once.
 *
 * Google only renders ITS OWN button, inside a host element, and hands the ID token to the callback
 * given at `initialize`. `initialize` replaces the whole client configuration, so whichever screen
 * called it last owns the callback — fine here, because the two screens are never mounted together.
 */

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: { client_id: string; callback: (response: { credential: string }) => void }) => void;
          renderButton: (element: HTMLElement, options: Record<string, string | boolean | number>) => void;
        };
      };
    };
  }
}

export const GOOGLE_IDENTITY_SCRIPT = "https://accounts.google.com/gsi/client";

/** The client id this build signs in with, or null when Google sign-in is not configured. */
export function googleClientId(): string | null {
  const id = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID?.trim();
  return id ? id : null;
}

/** Resolve once `window.google` is ready, loading the script if no other screen has. Rejects when it
 *  cannot be loaded (offline, blocked). */
export function loadGoogleIdentity(): Promise<void> {
  if (typeof window === "undefined") return Promise.reject(new Error("no window"));
  if (window.google) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GOOGLE_IDENTITY_SCRIPT}"]`);
    const script = existing ?? document.createElement("script");
    script.addEventListener("load", () => resolve(), { once: true });
    script.addEventListener("error", () => reject(new Error("Google sign-in could not be loaded")), { once: true });
    if (!existing) {
      script.src = GOOGLE_IDENTITY_SCRIPT;
      script.async = true;
      script.defer = true;
      document.body.appendChild(script);
    }
  });
}
