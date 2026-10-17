/**
 * PASSWORDS: every call the browser makes about one, and the rules every password box shares.
 *
 * Ported from the Design Prototype Workshop's `frontend/lib/signIn.ts`, with the same names and the
 * same routes, so the two clients stay copyable:
 *
 *  - password links — an administrator makes one (`POST /auth/password-links`), withdraws one
 *    (`…/{id}/revoke`), and the person redeems it at `/set-password` (`POST /auth/set-password`);
 *  - "Forgot your password?" — `POST /auth/forgot-password`, which answers the same sentence for
 *    every address;
 *  - changing your own password — `POST /auth/change-password`, which also SETS a first password for
 *    an account that signs in only with Google, Microsoft or Yahoo;
 *  - the administrator's help: a temporary password, "require a new password", and signing somebody
 *    out of every device.
 *
 * No call here ever opens a session. A link sets a password and the person then signs in, meeting
 * two-step verification there if it is on.
 */

import { ApiError, apiFetch, apiFetchWithHeaders } from "@/lib/api";
import type { User, UserRole } from "@/lib/types";

/* ── Password links ─────────────────────────────────────────────────────────────────────────── */

export type IssuedPasswordLink = {
  id: string;
  /** Null when the link was e-mailed: the administrator is not handed a copy. */
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

/** Is this link still good? The token travels in the body, never in a query string a log keeps. */
export async function checkPasswordLink(token: string): Promise<PasswordLinkCheck> {
  return apiFetch<PasswordLinkCheck>("/auth/set-password/check", {
    method: "POST",
    body: JSON.stringify({ token })
  });
}

/** One sentence per reason a link is refused, because each has a different next step. */
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

/**
 * The token out of the address bar, and the address without it — so the credential does not stay in
 * the history or on screen. Reads `?token=` (what the server writes) and `#token=` alike.
 */
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

/* ── Forgot your password? ──────────────────────────────────────────────────────────────────── */

/** What the screen prints if an older server answers without its own sentence. */
export const FORGOT_PASSWORD_FALLBACK =
  "If that address belongs to an account that signs in with a password, a link to choose a new password is on its way.";

/**
 * Ask for a password link by e-mail. The answer is the server's one sentence, the same for every
 * address — there is deliberately no "we could not find that address".
 */
export async function requestPasswordLinkByEmail(email: string): Promise<string> {
  const answer = await apiFetch<{ message?: unknown }>("/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify({ email: email.trim() })
  });
  return typeof answer?.message === "string" && answer.message.trim() ? answer.message : FORGOT_PASSWORD_FALLBACK;
}

/** "Try again in about 5 minutes." from a `Retry-After` in seconds, or null when there is none. */
export function describeRetryAfter(header: string | null | undefined): string | null {
  const seconds = Number(header);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  const minutes = Math.max(1, Math.ceil(seconds / 60));
  return `Try again in about ${minutes} minute${minutes === 1 ? "" : "s"}.`;
}

/* ── Changing your own password ─────────────────────────────────────────────────────────────── */

/** The header `POST /auth/change-password` hands the fresh session token back in. */
export const SESSION_TOKEN_HEADER = "x-session-token";

/**
 * The header a set-first-password refusal carries when the request lacked a provider sign-in RUN
 * AGAIN in the last five minutes for this account (missing, stale or another address). Its value
 * names the provider to run: `GOOGLE`, `MICROSOFT` or `YAHOO`. See `lib/providerReauth.ts`.
 */
export const CONFIRM_SIGN_IN_HEADER = "x-confirm-sign-in";

/**
 * The header a set-first-password refusal carries when the account has two-step verification on and
 * the request lacked a good code from the authenticator app (a missing one or a wrong one).
 */
export const TWO_FACTOR_REQUIRED_HEADER = "x-two-factor-required";

/**
 * On a wrong code AFTER the provider sign-in was accepted, the refusal also carries this: what stands
 * in for that sign-in on the next try (a Microsoft or Yahoo code works once and is already spent).
 */
export const SIGN_IN_PROOF_HEADER = "x-sign-in-proof";

/** The stand-in a two-step refusal handed back, or null. */
export function signInProofFrom(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const token = error.headers?.get(SIGN_IN_PROOF_HEADER)?.trim();
  return token ? token : null;
}

/** Did the server refuse a first password for want of a good two-step code? */
export function asksForTwoFactorCode(error: unknown): boolean {
  return error instanceof ApiError && error.headers?.get(TWO_FACTOR_REQUIRED_HEADER) === "1";
}

/** Which provider the server wants signed in with again before a first password, or null. */
export function asksToConfirmSignIn(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const provider = error.headers?.get(CONFIRM_SIGN_IN_HEADER)?.trim().toUpperCase();
  return provider ? provider : null;
}

/**
 * Change (or, with no current password, SET) the signed-in account's password. Returns the fresh
 * session token from {@link SESSION_TOKEN_HEADER}, or null when the answer carried none. The token
 * this call was sent with is retired by its own success, so the caller adopts the fresh one BEFORE
 * re-reading `/me`.
 */
export async function changeOwnPassword(
  currentPassword: string,
  newPassword: string,
  twoFactorCode?: string,
  /** A FIRST password only: the provider sign-in run again (`ProviderProof.fields`). */
  providerProof?: Record<string, string>
): Promise<string | null> {
  const code = twoFactorCode?.trim();
  const body = currentPassword
    ? { currentPassword, newPassword }
    : { newPassword, ...(code ? { twoFactorCode: code } : {}), ...(providerProof ?? {}) };
  const { headers } = await apiFetchWithHeaders<unknown>("/auth/change-password", {
    method: "POST",
    body: JSON.stringify(body)
  });
  const token = headers.get(SESSION_TOKEN_HEADER)?.trim();
  return token ? token : null;
}

/* ── An administrator looking after somebody's account ──────────────────────────────────────── */

/**
 * How a person added on /users will sign in — `POST /api/users`'s `passwordMode`:
 *  - SET: the administrator types a password (`mustChangePassword`, default on);
 *  - GENERATE: the server makes a strong temporary one and answers it ONCE (`temporaryPassword`);
 *  - NONE: Google, Microsoft or Yahoo only — optionally e-mailed a link to set a password too.
 */
export type PasswordMode = "SET" | "GENERATE" | "NONE";

export type NewPasswordAccount = {
  name: string;
  email: string;
  role: UserRole;
  passwordMode: PasswordMode;
  /** SET only, exactly as typed. */
  password?: string;
  /** SET only; GENERATE is always must-change. */
  mustChangePassword?: boolean;
  /** NONE only: e-mail them a link to set a password too, after the account is created. */
  sendPasswordLink?: boolean;
  canManageQuestionnaire?: boolean;
  canDownloadDataset?: boolean;
};

/** The answer to a create: the account, plus what exists only in this one answer. */
export type CreatedAccount = User & {
  /** GENERATE: the temporary password, shown once and never again. */
  temporaryPassword?: string | null;
  /** NONE + sendPasswordLink: the link that was e-mailed (no link text — it went to their inbox). */
  passwordLink?: Omit<IssuedPasswordLink, "link"> | null;
  /** NONE + sendPasswordLink, when the e-mail could not be sent; the account is still created. */
  passwordLinkError?: string | null;
};

export async function createPasswordAccount(account: NewPasswordAccount): Promise<CreatedAccount> {
  return apiFetch<CreatedAccount>("/users", { method: "POST", body: JSON.stringify(account) });
}

/** The header a duplicate (409) carries: the id of the account that already uses the address. */
export const EXISTING_ACCOUNT_HEADER = "x-existing-account-id";

/** The existing account's id from a 409, or null. */
export function existingAccountId(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const id = error.headers?.get(EXISTING_ACCOUNT_HEADER)?.trim();
  return id ? id : null;
}

/** Ask an account to choose a new password at its next sign-in; its password is not touched. */
export async function requirePasswordChange(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, { method: "PATCH", body: JSON.stringify({ mustChangePassword: true }) });
}

export async function setTemporaryPassword(userId: string, password: string, mustChangePassword: boolean): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, {
    method: "PATCH",
    body: JSON.stringify({ password, mustChangePassword })
  });
}

/** End every session the account has, on the web and on the phone. */
export async function signOutEverywhere(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}/sign-out-everywhere`, { method: "POST" });
}

/* ── The rules every password box shares ────────────────────────────────────────────────────── */

export const MIN_PASSWORD_LENGTH = 8;

/** bcrypt reads at most 72 bytes; the server refuses more (`BCRYPT_MAX_PASSWORD_BYTES`). */
export const MAX_PASSWORD_LENGTH = 72;

export function passwordRuleLine(suffix?: string): string {
  return `At least ${MIN_PASSWORD_LENGTH} characters.${suffix ? ` ${suffix}` : ""}`;
}

export function mustChangePassword(user: User | null | undefined): boolean {
  return user?.mustChangePassword ?? false;
}
