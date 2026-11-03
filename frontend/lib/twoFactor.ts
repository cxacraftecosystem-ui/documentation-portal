import { apiFetch } from "@/lib/api";
import type { User } from "@/lib/types";

export type TwoFactorStatus = {
  enabled: boolean;
  pending?: boolean;
  recovery_codes_remaining: number;
  enabled_at: string | null;
};

export type TwoFactorSetup = {
  secret: string;
  otpauth_uri: string;
  qr_svg: string;
  expires_in_seconds?: number;
};

export type TwoFactorChallenge = {
  two_factor_required: true;
  challenge_token: string;
};

export const CHALLENGE_ENDED_DETAIL = "This sign-in has timed out. Sign in again.";

export type SignInResult = { user: User } | TwoFactorChallenge;

export function isTwoFactorChallenge(value: unknown): value is TwoFactorChallenge {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as { two_factor_required?: unknown }).two_factor_required === true &&
    typeof (value as { challenge_token?: unknown }).challenge_token === "string"
  );
}

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

export async function cancelTwoFactorSetup(options: { keepalive?: boolean } = {}): Promise<void> {
  await apiFetch("/auth/two-factor/setup/cancel", { method: "POST", keepalive: options.keepalive });
}

export type TwoFactorView = "off" | "setting-up" | "on";

export function twoFactorView(status: TwoFactorStatus | null, settingUpHere: boolean): TwoFactorView {
  if (status?.enabled) return "on";
  if (settingUpHere) return "setting-up";
  return "off";
}

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

export function resetTwoFactorForUser(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}/two-factor`, { method: "DELETE" });
}

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
