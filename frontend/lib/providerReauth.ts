import type { OidcProviderId, PendingSignIn } from "@/lib/oidcSignIn";
import { loginBody } from "@/lib/oidcSignIn";
import type { User } from "@/lib/types";

export type ReauthProvider = "GOOGLE" | OidcProviderId;

export const PROOF_LIFETIME_MS = 4.5 * 60 * 1000;

export interface ProviderProof {
  provider: ReauthProvider;
  fields: Record<string, string>;
  obtainedAt: number;
}

const LABELS: Record<ReauthProvider, string> = { GOOGLE: "Google", MICROSOFT: "Microsoft", YAHOO: "Yahoo" };

export function reauthLabel(provider: ReauthProvider): string {
  return LABELS[provider];
}

export function reauthProviderFor(user: Pick<User, "authProvider"> | null | undefined): ReauthProvider {
  const provider = (user?.authProvider ?? "").trim().toUpperCase();
  return provider === "MICROSOFT" || provider === "YAHOO" ? provider : "GOOGLE";
}

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

export function carriedProof(proof: ProviderProof, token: string): ProviderProof {
  return { provider: proof.provider, fields: { providerProof: token }, obtainedAt: proof.obtainedAt };
}

export function proofIsFresh(proof: ProviderProof | null, now: number = Date.now()): proof is ProviderProof {
  return proof !== null && now - proof.obtainedAt >= 0 && now - proof.obtainedAt < PROOF_LIFETIME_MS;
}

export function confirmPrompt(provider: ReauthProvider): string {
  return `First confirm it's you: sign in with ${reauthLabel(provider)} again. Then choose your password within 5 minutes.`;
}

export function confirmedLine(provider: ReauthProvider): string {
  return `Confirmed with ${reauthLabel(provider)}. Choose your password now — this lasts 5 minutes.`;
}

export function confirmMissing(provider: ReauthProvider): string {
  return `Sign in with ${reauthLabel(provider)} again first, using the button above.`;
}
