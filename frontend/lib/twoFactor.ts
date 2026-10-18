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
  /** True ONLY once a code has been verified by `/enable`. The one fact that means "On". */
  enabled: boolean;
  /**
   * A setup was started (a QR was shown) and not yet confirmed, and is younger than 15 minutes.
   * Still OFF: sign-in never asks for a code on the strength of it. Optional so a server that
   * predates the field reads as "not pending".
   */
  pending?: boolean;
  recovery_codes_remaining: number;
  enabled_at: string | null;
};

export type TwoFactorSetup = {
  /** The base32 key, for typing into an authenticator by hand. */
  secret: string;
  otpauth_uri: string;
  /** Finished SVG markup from the server: a white square and one black path. */
  qr_svg: string;
  /** How long this pending secret stays usable before the server discards it (900). */
  expires_in_seconds?: number;
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
/** The token itself never reaches the page: this site's sign-in route moves it into the session cookie. */
export type SignInResult = { user: User } | TwoFactorChallenge;

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

/**
 * Forget a setup that was started and not confirmed. Idempotent on the server and never touches an
 * enabled secret, so it is safe on every way out of setup: Cancel, switching the toggle back, leaving
 * the page, the tab closing (`keepalive`, so the request outlives the page).
 */
export async function cancelTwoFactorSetup(options: { keepalive?: boolean } = {}): Promise<void> {
  await apiFetch("/auth/two-factor/setup/cancel", { method: "POST", keepalive: options.keepalive });
}

/** What the two-step toggle shows. "setting-up" is still OFF on the switch — only "on" is on. */
export type TwoFactorView = "off" | "setting-up" | "on";

/**
 * The toggle's state from the server's answer plus whether THIS screen is in the middle of setup.
 *
 * The server's `enabled` is the only thing that turns the switch on. A `pending` flag from the
 * server WITHOUT a setup on this screen (the person started on another visit and walked away) is
 * shown as Off — the switch offers to start again — and the screen cancels that pending secret.
 */
export function twoFactorView(status: TwoFactorStatus | null, settingUpHere: boolean): TwoFactorView {
  if (status?.enabled) return "on";
  if (settingUpHere) return "setting-up";
  return "off";
}

/** The plain-words line under the toggle, e.g. "On since 17 Oct 2026 · 8 recovery codes left". */
export function twoFactorStatusLine(
  status: TwoFactorStatus | null,
  view: TwoFactorView,
  formatDate: (iso: string) => string
): string {
  if (view === "setting-up") return "Setting up… It turns on once the app's code is checked.";
  if (!status || view === "off") return "Off. Turn it on to ask for a code from your phone at sign-in.";
  const left = status.recovery_codes_remaining;
  const codes = left === 1 ? "1 recovery code left" : `${left} recovery codes left`;
  return status.enabled_at ? `On since ${formatDate(status.enabled_at)} · ${codes}` : `On · ${codes}`;
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
