/**
 * WHAT /users OFFERS ON EACH ACCOUNT — "help them back in" — decided in one pure place.
 *
 * Ported in shape from the Design Prototype Workshop's file of the same name. The server holds every
 * rule (an administrator, over an account ranked below them, never their own); this file only keeps
 * the screen from drawing a button the server would refuse. Pure, so `e2e/account-password-unit.spec.ts`
 * calls it directly.
 */

import { ROLE_RANK, ROLES_BY_RANK, canManageUser, isAdmin, roleRank } from "@/lib/permissions";
import type { CreatedAccount, NewPasswordAccount, PasswordMode } from "@/lib/signIn";
import type { User, UserRole } from "@/lib/types";

/** Has the account a password of its own? The server's `hasPassword`, else `passwordSetAt`. */
export function hasPassword(user: User): boolean {
  if (typeof user.hasPassword === "boolean") return user.hasPassword;
  return user.passwordSetAt != null;
}

export type RowOffers = {
  /** "Email them a password link" — only where the deployment sends e-mail. */
  emailLink: boolean;
  /** "Make a password link" — copied and handed over by phone or WhatsApp. */
  copyLink: boolean;
  temporaryPassword: boolean;
  requireChange: boolean;
  signOutEverywhere: boolean;
  turnOffTwoStep: boolean;
};

export const NO_OFFERS: RowOffers = {
  emailLink: false,
  copyLink: false,
  temporaryPassword: false,
  requireChange: false,
  signOutEverywhere: false,
  turnOffTwoStep: false
};

/**
 * A password link resets a password or sets a first one on an account made for one; an account that
 * signs in ONLY with Google, Microsoft or Yahoo is refused one by the server (there is nothing to
 * reset), so it is offered a temporary password instead.
 */
export function linkOffered(target: User): boolean {
  return hasPassword(target) || (target.authProvider ?? "LOCAL").toUpperCase() === "LOCAL";
}

export function rowOffers(
  viewer: User | null | undefined,
  target: User,
  { adminView, mailAvailable }: { adminView: boolean; mailAvailable: boolean }
): RowOffers {
  if (!viewer || !adminView || !isAdmin(viewer)) return NO_OFFERS;
  if (target.id === viewer.id || target.role === "MASTER_ADMIN" || !canManageUser(viewer, target)) return NO_OFFERS;
  const link = linkOffered(target);
  return {
    emailLink: link && mailAvailable,
    copyLink: link,
    temporaryPassword: true,
    requireChange: hasPassword(target) && !target.mustChangePassword,
    signOutEverywhere: true,
    turnOffTwoStep: Boolean(target.twoFactorEnabled)
  };
}

/** A password typed for somebody else, exactly as typed — never trimmed. */
export function passwordFrom(form: FormData, key = "password"): string {
  const value = form.get(key);
  return typeof value === "string" ? value : "";
}

export function passwordHasEdgeSpace(password: string): boolean {
  return password.length > 0 && password !== password.trim();
}

/** What the "Sign-in" column says about an account. */
export function signInSummary(user: User): string {
  const provider = (user.authProvider ?? "LOCAL").toUpperCase();
  const providerLabel = provider === "LOCAL" ? null : provider.charAt(0) + provider.slice(1).toLowerCase();
  if (user.mustChangePassword) return "Must choose a new password";
  if (hasPassword(user) && providerLabel) return `Password and ${providerLabel}`;
  if (hasPassword(user)) return "Password";
  if (providerLabel) return `${providerLabel} only`;
  return "No password yet";
}

/* ── "Add a person" — one step: the account, the access roster, and how they sign in ─────────── */

/**
 * The roles this viewer may give a NEW account: strictly below their own rank. So only the master
 * admin is offered Admin, and nobody is offered Master Admin. The server holds the same rule.
 */
export function creatableRoles(viewer: User | null | undefined): UserRole[] {
  if (!viewer) return [];
  const own = roleRank(viewer);
  return ROLES_BY_RANK.filter((role) => ROLE_RANK[role] < own);
}

export type NewAccountForm = {
  name: string;
  email: string;
  role: UserRole;
  passwordMode: PasswordMode;
  password: string;
  mustChangePassword: boolean;
  sendPasswordLink: boolean;
  canManageQuestionnaire: boolean;
  canDownloadDataset: boolean;
};

/**
 * What `POST /api/users` is sent. Only what the chosen way of signing in uses travels: a password
 * (exactly as typed) and its must-change choice for SET; nothing for GENERATE (the server makes one
 * and always requires a change); the link choice for NONE, and only where e-mail can carry it.
 */
export function newAccountBody(form: NewAccountForm, { mailAvailable }: { mailAvailable: boolean }): NewPasswordAccount {
  const body: NewPasswordAccount = {
    name: form.name.trim(),
    email: form.email.trim(),
    role: form.role,
    passwordMode: form.passwordMode,
    canManageQuestionnaire: form.canManageQuestionnaire,
    canDownloadDataset: form.canDownloadDataset
  };
  if (form.passwordMode === "SET") {
    body.password = form.password;
    body.mustChangePassword = form.mustChangePassword;
  }
  if (form.passwordMode === "NONE" && mailAvailable && form.sendPasswordLink) body.sendPasswordLink = true;
  return body;
}

/** What the screen says once the person is added. The temporary password itself is shown apart. */
export function createdSentence(created: CreatedAccount, mode: PasswordMode): string {
  const who = `${created.name || created.email} (${created.email})`;
  if (mode === "GENERATE") {
    return `${who} is added and can sign in with the temporary password below. They must change it when they first sign in.`;
  }
  if (mode === "NONE") {
    const base = `${who} is added and can sign in with Google, Microsoft or Yahoo on that address.`;
    if (created.passwordLink) return `${base} A link to set a password is on its way to their inbox.`;
    if (created.passwordLinkError) return `${base} ${created.passwordLinkError}`;
    return base;
  }
  return `${who} is added and can sign in with the password you typed${
    created.mustChangePassword ? ", and will choose their own at their first sign-in" : ""
  }. Send it to them yourself — nothing shows it again.`;
}
