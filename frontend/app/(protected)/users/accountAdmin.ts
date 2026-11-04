import { ROLE_RANK, ROLES_BY_RANK, canManageUser, isAdmin, roleRank } from "@/lib/permissions";
import type { CreatedAccount, NewPasswordAccount, PasswordMode } from "@/lib/signIn";
import type { User, UserRole } from "@/lib/types";

export function hasPassword(user: User): boolean {
  if (typeof user.hasPassword === "boolean") return user.hasPassword;
  return user.passwordSetAt != null;
}

export type RowOffers = {
  emailLink: boolean;
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

export function passwordFrom(form: FormData, key = "password"): string {
  const value = form.get(key);
  return typeof value === "string" ? value : "";
}

export function passwordHasEdgeSpace(password: string): boolean {
  return password.length > 0 && password !== password.trim();
}

export function mayUpdateUser(viewer: User | null | undefined, user: User): boolean {
  return canManageUser(viewer, user) && user.role !== "MASTER_ADMIN";
}

export function signInSummary(user: User): string {
  const provider = (user.authProvider ?? "LOCAL").toUpperCase();
  const providerLabel = provider === "LOCAL" ? null : provider.charAt(0) + provider.slice(1).toLowerCase();
  if (user.mustChangePassword) return "Must choose a new password";
  if (hasPassword(user) && providerLabel) return `Password and ${providerLabel}`;
  if (hasPassword(user)) return "Password";
  if (providerLabel) return `${providerLabel} only`;
  return "No password yet";
}

export const FIRST_SIGN_IN_UNRECORDED = "Yes, date not recorded";

export function firstSignInRecorded(user: Pick<User, "firstLoginAt" | "createdAt">): boolean {
  if (!user.firstLoginAt) return false;
  const first = Date.parse(user.firstLoginAt);
  const created = user.createdAt ? Date.parse(user.createdAt) : Number.NaN;
  return !(Number.isFinite(first) && Number.isFinite(created) && first === created);
}

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

export function createdSentence(created: CreatedAccount, mode: PasswordMode): string {
  const who = `${created.name || created.email} (${created.email})`;
  if (mode === "GENERATE") {
    return `${who} added. They’ll change the temporary password below at first sign-in.`;
  }
  if (mode === "NONE") {
    const base = `${who} added.`;
    if (created.passwordLink) return `${base} Password link sent.`;
    if (created.passwordLinkError) return `${base} ${created.passwordLinkError}`;
    return base;
  }
  return `${who} added${created.mustChangePassword ? " — they’ll choose a new password at first sign-in" : ""}. Send them the password.`;
}
