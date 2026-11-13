import { ApiError, apiFetch, apiFetchWithHeaders } from "@/lib/api";
import type { User, UserRole } from "@/lib/types";
import { SESSION_ROTATED_HEADER } from "@/lib/sessionCookie";
import { writeBody } from "@/lib/writeBody";

export type IssuedPasswordLink = {
  id: string;
  link: string | null;
  expiresAt: string;
  purpose: "INVITE" | "RESET";
  deliveredBy: "COPY_LINK" | "EMAIL" | string;
};

export type PasswordLinkDelivery = "COPY_LINK" | "EMAIL";

export async function issuePasswordLink(
  userId: string,
  delivery: PasswordLinkDelivery = "COPY_LINK"
): Promise<IssuedPasswordLink> {
  return apiFetch<IssuedPasswordLink>("/auth/password-links", {
    method: "POST",
    body: JSON.stringify(delivery === "EMAIL" ? { userId, delivery } : { userId })
  });
}

export async function revokePasswordLink(linkId: string): Promise<void> {
  await apiFetch(`/auth/password-links/${encodeURIComponent(linkId)}/revoke`, { method: "POST" });
}

export type PasswordLinkCheck = {
  valid: boolean;
  reason: string | null;
  purpose: "INVITE" | "RESET" | null;
};

export async function checkPasswordLink(token: string): Promise<PasswordLinkCheck> {
  return apiFetch<PasswordLinkCheck>("/auth/set-password/check", {
    method: "POST",
    body: JSON.stringify({ token })
  });
}

export const LINK_REFUSALS: Record<string, string> = {
  missing: "This link is incomplete. Open the whole link you were sent.",
  malformed: "This link isn’t valid. Ask for a new one.",
  expired: "This link has expired. Ask for a new one.",
  revoked: "This link was withdrawn. Ask for a new one.",
  spent: "This link has already been used. Sign in with the password you set.",
  "unknown-account": "This link no longer points at an account."
};

export function linkRefusal(reason: string | null | undefined): string {
  return LINK_REFUSALS[reason ?? ""] ?? "This password link is not valid.";
}

export function takeLinkTokenFromAddress(href: string): { token: string; address: string | null } {
  const url = new URL(href);
  const fragment = new URLSearchParams(url.hash.slice(1));
  const inFragment = fragment.has("token");
  const inQuery = url.searchParams.has("token");
  if (!inFragment && !inQuery) return { token: "", address: null };
  const token = fragment.get("token") || url.searchParams.get("token") || "";
  url.searchParams.delete("token");
  let hash = url.hash;
  if (inFragment) {
    fragment.delete("token");
    const rest = fragment.toString();
    hash = rest ? `#${rest}` : "";
  }
  return { token, address: `${url.pathname}${url.search}${hash}` };
}

export async function setPasswordWithLink(token: string, password: string): Promise<void> {
  await apiFetch("/auth/set-password", { method: "POST", body: JSON.stringify({ token, password }) });
}

export const FORGOT_PASSWORD_FALLBACK =
  "If that address has an account, a reset link is on its way.";

export async function requestPasswordLinkByEmail(email: string): Promise<string> {
  const answer = await apiFetch<{ message?: unknown }>("/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify({ email: email.trim() })
  });
  return typeof answer?.message === "string" && answer.message.trim() ? answer.message : FORGOT_PASSWORD_FALLBACK;
}

export function describeRetryAfter(header: string | null | undefined): string | null {
  const seconds = Number(header);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  const minutes = Math.max(1, Math.ceil(seconds / 60));
  return `Try again in about ${minutes} minute${minutes === 1 ? "" : "s"}.`;
}

export const SESSION_TOKEN_HEADER = "x-session-token";

function sessionRotated(headers: Headers): boolean {
  return (headers.get(SESSION_ROTATED_HEADER) ?? "").trim() === "1";
}

export const CONFIRM_SIGN_IN_HEADER = "x-confirm-sign-in";

export const TWO_FACTOR_REQUIRED_HEADER = "x-two-factor-required";

export const SIGN_IN_PROOF_HEADER = "x-sign-in-proof";

export function signInProofFrom(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const token = error.headers?.get(SIGN_IN_PROOF_HEADER)?.trim();
  return token ? token : null;
}

export function asksForTwoFactorCode(error: unknown): boolean {
  return error instanceof ApiError && error.headers?.get(TWO_FACTOR_REQUIRED_HEADER) === "1";
}

export function asksToConfirmSignIn(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const provider = error.headers?.get(CONFIRM_SIGN_IN_HEADER)?.trim().toUpperCase();
  return provider ? provider : null;
}

export async function changeOwnPassword(
  currentPassword: string,
  newPassword: string,
  twoFactorCode?: string,
  providerProof?: Record<string, string>
): Promise<boolean> {
  const code = twoFactorCode?.trim();
  const body = currentPassword
    ? { currentPassword, newPassword }
    : { newPassword, ...(code ? { twoFactorCode: code } : {}), ...(providerProof ?? {}) };
  const { headers } = await apiFetchWithHeaders<unknown>("/auth/change-password", {
    method: "POST",
    body: JSON.stringify(body)
  });
  return sessionRotated(headers);
}

export type PasswordMode = "SET" | "GENERATE" | "NONE";

export type NewPasswordAccount = {
  name: string;
  email: string;
  role: UserRole;
  passwordMode: PasswordMode;
  password?: string;
  mustChangePassword?: boolean;
  sendPasswordLink?: boolean;
  canManageQuestionnaire?: boolean;
  canDownloadDataset?: boolean;
};

export type CreatedAccount = User & {
  temporaryPassword?: string | null;
  passwordLink?: Omit<IssuedPasswordLink, "link"> | null;
  passwordLinkError?: string | null;
};

export async function createPasswordAccount(account: NewPasswordAccount): Promise<CreatedAccount> {
  return apiFetch<CreatedAccount>("/users", { method: "POST", body: JSON.stringify(writeBody(account, "POST")) });
}

export const EXISTING_ACCOUNT_HEADER = "x-existing-account-id";

export function existingAccountId(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const id = error.headers?.get(EXISTING_ACCOUNT_HEADER)?.trim();
  return id ? id : null;
}

export async function requirePasswordChange(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, { method: "PATCH", body: JSON.stringify({ mustChangePassword: true }) });
}

export async function setTemporaryPassword(userId: string, password: string, mustChangePassword: boolean): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, {
    method: "PATCH",
    body: JSON.stringify({ password, mustChangePassword })
  });
}

export async function signOutEverywhere(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}/sign-out-everywhere`, { method: "POST" });
}

export const MIN_PASSWORD_LENGTH = 8;

export const MAX_PASSWORD_LENGTH = 72;

export function passwordRuleLine(suffix?: string): string {
  return `At least ${MIN_PASSWORD_LENGTH} characters.${suffix ? ` ${suffix}` : ""}`;
}

export function mustChangePassword(user: User | null | undefined): boolean {
  return user?.mustChangePassword ?? false;
}
