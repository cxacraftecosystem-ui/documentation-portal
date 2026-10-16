/**
 * CHANGING YOUR OWN PASSWORD — THE RULES BOTH FORMS ENFORCE BEFORE ANYTHING IS SENT.
 *
 * Two forms call `POST /auth/change-password`: `FirstPasswordGate` (an account that must choose a
 * new password meets it above the whole protected tree) and the Password card in Settings. They
 * refuse the same things in the same words, so the refusals live here. Ported from the Design
 * Prototype Workshop's file of the same name; Android's `newPasswordRefusal` says the same sentences.
 */

import { ApiError } from "@/lib/api";
import type { User } from "@/lib/types";

/**
 * Has this account a password of its own? `hasPassword` is the server's answer; `passwordSetAt` is
 * the fallback for a server that predates it.
 */
export function hasOwnPassword(user: Pick<User, "hasPassword" | "passwordSetAt"> | null | undefined): boolean {
  if (!user) return false;
  if (typeof user.hasPassword === "boolean") return user.hasPassword;
  return user.passwordSetAt != null;
}

/** What the gate says, and what the server says when it refuses a gated request: one sentence. */
export const PASSWORD_CHANGE_PROMPT = "Choose a new password to continue.";

/** What changing a password does to the account's other sessions, said under both forms. */
export const PASSWORD_CHANGE_SESSIONS = "You stay signed in here, and are signed out everywhere else.";

/** Under the set-a-first-password form. */
export const FIRST_PASSWORD_NOTE =
  "You can then sign in with this password or the way you do now. You stay signed in here, and are signed out everywhere else.";

/** Why this change must not be sent, or null when it may be. */
export function newPasswordProblem({
  current,
  next,
  confirm
}: {
  current: string;
  next: string;
  confirm: string;
}): string | null {
  if (next !== confirm) return "The two passwords do not match.";
  if (current && next === current) return "Your new password must be different from your current one.";
  return null;
}

/**
 * Did `POST /auth/change-password` refuse the CURRENT password this form sent? A 400 is how the
 * server answers it — never a 401, which would have read as a dead session.
 */
export function currentPasswordRefused(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400;
}

/**
 * The sentence a failed password request shows: the server's own wherever it answered, and a plain
 * "couldn't connect" where it did not ("Failed to fetch" is the browser talking, not the server).
 */
export function passwordFailureSentence(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return error.message || fallback;
  if (error instanceof TypeError) return "Couldn't connect. Check your connection and try again.";
  return error instanceof Error && error.message ? error.message : fallback;
}
