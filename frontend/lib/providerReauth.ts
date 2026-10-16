/**
 * SETTING A FIRST PASSWORD: THE PROVIDER SIGN-IN, RUN AGAIN.
 *
 * An account that signs in with Google, Microsoft or Yahoo and has no password may add one, but the
 * server asks for proof that the person at the keyboard is the account's owner NOW — not merely that
 * this browser holds a session: the request must carry that provider's sign-in, run again in the
 * last five minutes, for the account's own address (`_provider_proof` in
 * `backend/app/api/routes/auth.py`). A refusal carries `X-Confirm-Sign-In: <provider>`.
 *
 * Google answers in the page (an ID token from its button). Microsoft and Yahoo leave for the
 * provider and come back to `/settings` with a code (`REAUTH_STATE_PREFIX` in `lib/oidcSignIn.ts`);
 * the code is redeemed by the server only when the password is saved, so nobody is signed in by it.
 *
 * Android does the same from its Password card (`ProviderReauth.kt`), with the same words.
 */

import type { OidcProviderId, PendingSignIn } from "@/lib/oidcSignIn";
import { loginBody } from "@/lib/oidcSignIn";
import type { User } from "@/lib/types";

export type ReauthProvider = "GOOGLE" | OidcProviderId;

/**
 * How long a confirmation is used for here. The server accepts five minutes; the page stops a little
 * earlier so a save sent at 4:59 is not refused on the way.
 */
export const PROOF_LIFETIME_MS = 4.5 * 60 * 1000;

export interface ProviderProof {
  provider: ReauthProvider;
  /** The body fields `POST /auth/change-password` takes for this provider. */
  fields: Record<string, string>;
  obtainedAt: number;
}

const LABELS: Record<ReauthProvider, string> = { GOOGLE: "Google", MICROSOFT: "Microsoft", YAHOO: "Yahoo" };

export function reauthLabel(provider: ReauthProvider): string {
  return LABELS[provider];
}

/** The provider this account signs in with, which is the one to run again (Google when unknown). */
export function reauthProviderFor(user: Pick<User, "authProvider"> | null | undefined): ReauthProvider {
  const provider = (user?.authProvider ?? "").trim().toUpperCase();
  return provider === "MICROSOFT" || provider === "YAHOO" ? provider : "GOOGLE";
}

/** A header value from the server, read as a provider, or null for anything else. */
export function asReauthProvider(value: string | null | undefined): ReauthProvider | null {
  const provider = (value ?? "").trim().toUpperCase();
  return provider === "GOOGLE" || provider === "MICROSOFT" || provider === "YAHOO" ? provider : null;
}

export function googleProof(idToken: string, now: number = Date.now()): ProviderProof {
  return { provider: "GOOGLE", fields: { googleIdToken: idToken }, obtainedAt: now };
}

export function oidcProof(pending: PendingSignIn, code: string, now: number = Date.now()): ProviderProof {
  return { provider: pending.provider, fields: loginBody(pending, code), obtainedAt: now };
}

/** Still young enough to send? */
export function proofIsFresh(proof: ProviderProof | null, now: number = Date.now()): proof is ProviderProof {
  return proof !== null && now - proof.obtainedAt >= 0 && now - proof.obtainedAt < PROOF_LIFETIME_MS;
}

/** What the card says before the person has confirmed. */
export function confirmPrompt(provider: ReauthProvider): string {
  return `First confirm it's you: sign in with ${reauthLabel(provider)} again. Then choose your password within 5 minutes.`;
}

/** What the card says once they have. */
export function confirmedLine(provider: ReauthProvider): string {
  return `Confirmed with ${reauthLabel(provider)}. Choose your password now — this lasts 5 minutes.`;
}

/** What a save without a fresh confirmation says, before anything is sent. */
export function confirmMissing(provider: ReauthProvider): string {
  return `Sign in with ${reauthLabel(provider)} again first, using the button above.`;
}
