import { ApiError } from "@/lib/api";
import type { User } from "@/lib/types";

export function hasOwnPassword(user: Pick<User, "hasPassword" | "passwordSetAt"> | null | undefined): boolean {
  if (!user) return false;
  if (typeof user.hasPassword === "boolean") return user.hasPassword;
  return user.passwordSetAt != null;
}

export const PASSWORD_CHANGE_PROMPT = "Choose a new password to continue.";

export const PASSWORD_CHANGE_SESSIONS = "You stay signed in here, and are signed out everywhere else.";

export const FIRST_PASSWORD_NOTE =
  "You can then sign in with this password or the way you do now. You stay signed in here, and are signed out everywhere else.";

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

export function currentPasswordRefused(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400;
}

export function passwordFailureSentence(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return error.message || fallback;
  if (error instanceof TypeError) return "Couldn't connect. Check your connection and try again.";
  return error instanceof Error && error.message ? error.message : fallback;
}
