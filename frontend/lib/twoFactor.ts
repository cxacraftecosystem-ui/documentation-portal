/**
 * Two-step verification — the client half of `/api/auth/two-factor` (backend/app/services/two_factor.py).
 *
 * The wire shape is snake_case on purpose: the designer portal and the documentation portal implement
 * one shared spec with one shape, and this file is meant to be copyable between them. Everything that
 * matters is decided on the server — the code is checked and SPENT there, the QR square is drawn there
 * — so what lives here is the calls, the shape test that picks which box a code belongs in, and the
 * text of the recovery-code download.
 */

import { apiFetch } from "@/lib/api";
import type { User } from "@/lib/types";

export type TwoFactorStatus = {
  enabled: boolean;
  recovery_codes_remaining: number;
  enabled_at: string | null;
};

export type TwoFactorSetup = {
  /** The base32 key, for typing into an authenticator by hand. */
  secret: string;
  otpauth_uri: string;
  /** Finished SVG markup from the server: a white square and one black path. */
  qr_svg: string;
};

/** What `POST /auth/login` answers instead of a session when the account has this switched on. */
export type TwoFactorChallenge = {
  two_factor_required: true;
  challenge_token: string;
};

/**
 * The server's sentence for a challenge that is finished — five wrong codes, five minutes, or already
 * used (`two_factor.CHALLENGE_DEAD_DETAIL`). Matched exactly: it is the one refusal after which the
 * form has nothing left to offer but signing in again.
 */
export const CHALLENGE_ENDED_DETAIL = "This sign-in has timed out. Sign in again.";

/** The two things a sign-in can answer: a session, or a request for the second step. */
export type SignInResult = { accessToken: string; user: User } | TwoFactorChallenge;

export function isTwoFactorChallenge(value: unknown): value is TwoFactorChallenge {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as { two_factor_required?: unknown }).two_factor_required === true &&
    typeof (value as { challenge_token?: unknown }).challenge_token === "string"
  );
}

/**
 * Which kind of code this is, by SHAPE — the server's `code_kind`, to the character: six digits is an
 * authenticator code, eight letters and digits (hyphen and spaces optional) a recovery code.
 */
export function codeKind(text: string): "totp" | "recovery" | null {
  const compact = text.replace(/[\s-]/g, "");
  if (/^\d{6}$/.test(compact)) return "totp";
  if (/^[A-Za-z0-9]{8}$/.test(compact)) return "recovery";
  return null;
}

export function fetchTwoFactorStatus(): Promise<TwoFactorStatus> {
  return apiFetch<TwoFactorStatus>("/auth/two-factor");
}

export function beginTwoFactorSetup(): Promise<TwoFactorSetup> {
  return apiFetch<TwoFactorSetup>("/auth/two-factor/setup", { method: "POST" });
}

export async function enableTwoFactor(code: string): Promise<string[]> {
  const result = await apiFetch<{ recovery_codes: string[] }>("/auth/two-factor/enable", {
    method: "POST",
    body: JSON.stringify({ code })
  });
  return result.recovery_codes;
}

export async function disableTwoFactor(code: string): Promise<void> {
  await apiFetch("/auth/two-factor/disable", { method: "POST", body: JSON.stringify({ code }) });
}

export async function makeNewRecoveryCodes(code: string): Promise<string[]> {
  const result = await apiFetch<{ recovery_codes: string[] }>("/auth/two-factor/recovery-codes", {
    method: "POST",
    body: JSON.stringify({ code })
  });
  return result.recovery_codes;
}

/** An administrator turning somebody else's off — they lost their phone. Answers the row as it now is. */
export function resetTwoFactorForUser(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}/two-factor`, { method: "DELETE" });
}

/** The text of the "Download" file: a heading, the account, and one code per line. */
export function recoveryCodesFileText(codes: string[], email: string): string {
  return [
    "Field Repository — recovery codes",
    email,
    "",
    "Each code works once, in place of the 6-digit code from your authenticator app.",
    "",
    ...codes,
    ""
  ].join("\n");
}
