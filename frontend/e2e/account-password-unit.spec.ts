/**
 * ACCOUNTS AND PASSWORDS — the browser's half, pinned.
 *
 * Pure functions from `lib/signIn.ts`, `lib/passwordChange.ts`, `lib/accessRoster.ts` and the users
 * screen's `accountAdmin.ts`, plus source assertions for the judgements that live in JSX (there is no
 * React renderer in devDependencies):
 *
 *   1. The two public password screens live OUTSIDE the protected tree, and sign-in links to one.
 *   2. "Forgot your password?" prints the server's one answer and never a "not found" branch.
 *   3. A 401 carrying `X-Password-Change-Required` keeps the session and is told apart.
 *   4. The forced change is drawn above the whole protected tree.
 *   5. What "help them back in" offers on each account.
 *   6. The access roster sends a password only for the password choice, exactly as typed.
 *
 * ⚠ Source assertions are line-ending agnostic: `\s` and `[\s\S]`, never `\n`.
 */

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  createdSentence,
  creatableRoles,
  linkOffered,
  newAccountBody,
  passwordHasEdgeSpace,
  rowOffers,
  signInSummary
} from "@/app/(protected)/users/accountAdmin";
import { ApiError, SIGNED_OUT_PAGES } from "@/lib/api";
import { rosterCreateBody } from "@/lib/accessRoster";
import { hasOwnPassword, newPasswordProblem, passwordFailureSentence } from "@/lib/passwordChange";
import {
  MAX_PASSWORD_LENGTH,
  asksForTwoFactorCode,
  asksToConfirmSignIn,
  describeRetryAfter,
  existingAccountId,
  linkRefusal,
  mustChangePassword,
  takeLinkTokenFromAddress
} from "@/lib/signIn";
import type { User } from "@/lib/types";

const root = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(root, ...parts), "utf8");

function account(overrides: Partial<User> = {}): User {
  return { id: "u1", email: "a@example.org", name: "Asha", role: "RESEARCHER", authProvider: "LOCAL", hasPassword: true, ...overrides };
}

const admin = account({ id: "admin", role: "ADMIN", email: "admin@example.org" });

test("the public password screens live outside the protected tree", () => {
  expect(existsSync(join(root, "app", "set-password", "page.tsx"))).toBe(true);
  expect(existsSync(join(root, "app", "forgot-password", "page.tsx"))).toBe(true);
  expect(existsSync(join(root, "app", "(protected)", "set-password"))).toBe(false);
  expect(existsSync(join(root, "app", "(protected)", "forgot-password"))).toBe(false);
  // An old token found on either must not bounce the person to /login and lose the link.
  expect(SIGNED_OUT_PAGES.has("/set-password")).toBe(true);
  expect(SIGNED_OUT_PAGES.has("/forgot-password")).toBe(true);
});

test("sign-in offers 'Forgot your password?'", () => {
  const login = read("app", "login", "page.tsx");
  expect(login).toMatch(/href="\/forgot-password"[\s\S]{0,200}Forgot your password\?/);
});

test("forgot-password prints the server's answer and has no 'not found' branch", () => {
  const page = read("app", "forgot-password", "page.tsx");
  expect(page).toContain("requestPasswordLinkByEmail");
  expect(page).not.toMatch(/not found|could not find|no account/i);
  expect(page).toContain("No email? Ask an administrator");
  expect(page).toContain("two-step verification");
});

test("a Retry-After reads as words", () => {
  expect(describeRetryAfter("60")).toBe("Try again in about 1 minute.");
  expect(describeRetryAfter("301")).toBe("Try again in about 6 minutes.");
  expect(describeRetryAfter(null)).toBeNull();
  expect(describeRetryAfter("soon")).toBeNull();
});

test("a link's token is taken off the address", () => {
  expect(takeLinkTokenFromAddress("https://x.test/set-password?token=abc.def")).toEqual({
    token: "abc.def",
    address: "/set-password"
  });
  expect(takeLinkTokenFromAddress("https://x.test/set-password#token=abc")).toEqual({ token: "abc", address: "/set-password" });
  expect(takeLinkTokenFromAddress("https://x.test/set-password")).toEqual({ token: "", address: null });
  expect(linkRefusal("expired")).toContain("expired");
  expect(linkRefusal("whatever")).toBe("This password link is not valid.");
});

test("a 401 with X-Password-Change-Required keeps the session", () => {
  const gated = new ApiError(401, "Choose a new password to continue.", null, new Headers({ "X-Password-Change-Required": "1" }));
  expect(gated.passwordChangeRequired).toBe(true);
  const ended = new ApiError(401, "Sign in again.", null, new Headers());
  expect(ended.passwordChangeRequired).toBe(false);
  const again = new ApiError(400, "sign in with Google again", null, new Headers({ "X-Confirm-Sign-In": "GOOGLE" }));
  expect(asksToConfirmSignIn(again)).toBe("GOOGLE");
  expect(asksToConfirmSignIn(ended)).toBeNull();
});

test("the forced change is drawn above the whole protected tree", () => {
  const shell = read("components", "AppShell.tsx");
  expect(shell).toMatch(/if \(mustChangePassword\(user\)\)[\s\S]{0,400}<FirstPasswordGate/);
  // Above the route guard: the gate's return comes before the guard is even computed.
  expect(shell.indexOf("<FirstPasswordGate")).toBeLessThan(shell.indexOf("routeGuardFor(pathname)"));
  expect(mustChangePassword(account({ mustChangePassword: true }))).toBe(true);
  expect(mustChangePassword(account())).toBe(false);
});

test("the change-password rules", () => {
  expect(newPasswordProblem({ current: "old-pass-1", next: "abcdefgh", confirm: "abcdefgi" })).toBe(
    "The two passwords do not match."
  );
  expect(newPasswordProblem({ current: "same-pass", next: "same-pass", confirm: "same-pass" })).toContain("different");
  // Setting a FIRST password: there is no current one to differ from.
  expect(newPasswordProblem({ current: "", next: "abcdefgh", confirm: "abcdefgh" })).toBeNull();
  expect(hasOwnPassword(account({ hasPassword: false, passwordSetAt: "2026-01-01" }))).toBe(false);
  expect(hasOwnPassword({ passwordSetAt: "2026-01-01" })).toBe(true);
  expect(hasOwnPassword({ passwordSetAt: null })).toBe(false);
  expect(passwordFailureSentence(new TypeError("Failed to fetch"), "x")).toContain("Couldn't connect");
  expect(MAX_PASSWORD_LENGTH).toBe(72);
});

test("Settings offers set-a-password to an account without one", () => {
  const card = read("components", "settings", "ChangePasswordCard.tsx");
  expect(card).toContain("Set a password");
  expect(card).toContain("asksToConfirmSignIn");
  // The provider sign-in is run again on the card itself; nobody is sent to sign out.
  expect(card).toContain("GoogleConfirmButton");
  expect(card).toContain("REAUTH_STATE_PREFIX");
  expect(card).not.toContain("Sign out and sign in again");
  expect(read("app", "(protected)", "settings", "page.tsx")).toContain("<ChangePasswordCard />");
});

test("help them back in: what each account is offered", () => {
  const view = { adminView: true, mailAvailable: true };
  const offers = rowOffers(admin, account({ twoFactorEnabled: true }), view);
  expect(offers).toEqual({
    emailLink: true,
    copyLink: true,
    temporaryPassword: true,
    requireChange: true,
    signOutEverywhere: true,
    turnOffTwoStep: true
  });
  // No mail on this deployment: no e-mail button, everything else stays.
  expect(rowOffers(admin, account(), { adminView: true, mailAvailable: false }).emailLink).toBe(false);
  // Google only: no link (nothing to reset) — a temporary password instead.
  const google = account({ authProvider: "GOOGLE", hasPassword: false });
  expect(linkOffered(google)).toBe(false);
  expect(rowOffers(admin, google, view)).toMatchObject({ copyLink: false, temporaryPassword: true, requireChange: false });
  // Never your own account, never a peer, never with admin view off.
  expect(rowOffers(admin, admin, view).signOutEverywhere).toBe(false);
  expect(rowOffers(admin, account({ role: "ADMIN" }), view).temporaryPassword).toBe(false);
  expect(rowOffers(admin, account(), { adminView: false, mailAvailable: true }).temporaryPassword).toBe(false);
  expect(signInSummary(account({ authProvider: "GOOGLE" }))).toBe("Password and Google");
  expect(signInSummary(google)).toBe("Google only");
  expect(passwordHasEdgeSpace(" secret12")).toBe(true);
  const page = read("app", "(protected)", "users", "page.tsx");
  for (const words of [
    "Email them a password link",
    "Make a password link",
    "Set a temporary password",
    "Require a new password",
    "Sign out of every device",
    "Turn off two-step verification",
  ]) {
    expect(page).toContain(words);
  }
});

test("add a person: roles strictly below your own, and only what the chosen sign-in uses is sent", () => {
  const master = account({ id: "m", role: "MASTER_ADMIN" });
  expect(creatableRoles(master)).toContain("ADMIN");
  expect(creatableRoles(master)).not.toContain("MASTER_ADMIN");
  expect(creatableRoles(admin)).not.toContain("ADMIN");
  expect(creatableRoles(admin)).toContain("PROFESSOR");
  expect(creatableRoles(account({ role: "RESEARCHER" }))).not.toContain("RESEARCHER");
  expect(creatableRoles(null)).toEqual([]);

  const form = {
    name: " Priya ",
    email: " p@example.org ",
    role: "RESEARCHER" as const,
    password: " typed pw ",
    mustChangePassword: false,
    sendPasswordLink: true,
    canManageQuestionnaire: false,
    canDownloadDataset: false
  };
  expect(newAccountBody({ ...form, passwordMode: "SET" }, { mailAvailable: true })).toMatchObject({
    name: "Priya",
    email: "p@example.org",
    passwordMode: "SET",
    password: " typed pw ",
    mustChangePassword: false
  });
  const generated = newAccountBody({ ...form, passwordMode: "GENERATE" }, { mailAvailable: true });
  expect(generated.password).toBeUndefined();
  expect(generated.mustChangePassword).toBeUndefined();
  expect(newAccountBody({ ...form, passwordMode: "NONE" }, { mailAvailable: true }).sendPasswordLink).toBe(true);
  expect(newAccountBody({ ...form, passwordMode: "NONE" }, { mailAvailable: false }).sendPasswordLink).toBeUndefined();
  expect(newAccountBody({ ...form, passwordMode: "NONE" }, { mailAvailable: true }).password).toBeUndefined();

  const created = { ...account({ email: "p@example.org", name: "Priya" }), temporaryPassword: "Xy7kQ2mP9rT4wZ" };
  expect(createdSentence(created, "GENERATE")).toContain("must change it when they first sign in");
  expect(createdSentence({ ...created, passwordLinkError: "Email could not be sent." }, "NONE")).toContain(
    "Email could not be sent."
  );

  const duplicate = new ApiError(409, "p@example.org already has an account.", null, new Headers({ "X-Existing-Account-Id": "acc_9" }));
  expect(existingAccountId(duplicate)).toBe("acc_9");
  expect(existingAccountId(new ApiError(409, "x", null, new Headers()))).toBeNull();
  expect(existingAccountId(new ApiError(400, "x", null, new Headers({ "X-Existing-Account-Id": "a" })))).toBeNull();

  const formSource = read("app", "(protected)", "users", "AddPersonForm.tsx");
  for (const words of [
    "Add a person",
    "How will they sign in?",
    "Set a password now",
    "Make a temporary password for me",
    "Google, Microsoft or Yahoo only",
    "Email them a link to set a password too",
    "Open their account",
    "Ask them to choose a new password at first sign-in"
  ]) {
    expect(formSource).toContain(words);
  }
  // The link offer is drawn only where e-mail can carry it.
  expect(formSource).toMatch(/\{mailAvailable \? \([\s\S]{0,300}Email them a link to set a password too/);
});

test("the access roster sends a password only for the password choice, exactly as typed", () => {
  const base = {
    email: " p@example.org ",
    fullName: " Priya ",
    notes: "",
    grantedRole: "RESEARCHER",
    sendInvite: false,
    password: " pass word ",
    mustChangePassword: true
  };
  expect(rosterCreateBody({ ...base, signIn: "PROVIDER" })).toEqual({
    email: "p@example.org",
    fullName: "Priya",
    notes: null,
    grantedRole: "RESEARCHER"
  });
  expect(rosterCreateBody({ ...base, signIn: "PASSWORD" })).toMatchObject({
    password: " pass word ",
    mustChangePassword: true
  });
  expect(read("app", "(protected)", "admin", "access-roster", "page.tsx")).toContain("How will they sign in?");
});

test("no copy admits a gap", () => {
  for (const file of [
    read("app", "forgot-password", "page.tsx"),
    read("app", "set-password", "page.tsx"),
    read("components", "settings", "ChangePasswordCard.tsx"),
    read("app", "(protected)", "users", "page.tsx")
  ]) {
    expect(file).not.toMatch(/coming soon|not (yet )?available|not built/i);
  }
});

test("a first password on a two-step account asks for the authenticator code", () => {
  const refused = new ApiError(400, "Enter the code from your authenticator app to set a password.", undefined, new Headers({ "X-Two-Factor-Required": "1" }));
  expect(asksForTwoFactorCode(refused)).toBe(true);
  expect(asksForTwoFactorCode(new ApiError(400, "Current password is incorrect", undefined))).toBe(false);
  const card = read("components", "settings", "ChangePasswordCard.tsx");
  expect(card).toMatch(/needsCode\s*=\s*setting\s*&&\s*Boolean\(user\.twoFactorEnabled\)/);
  expect(card).toContain("Code from your authenticator app");
  const signIn = read("lib", "signIn.ts");
  expect(signIn).toMatch(/twoFactorCode:\s*code/);
});

test("a master admin is offered nothing on another master admin's row", () => {
  const master = account({ id: "m1", role: "MASTER_ADMIN", email: "m1@example.org" });
  const other = account({ id: "m2", role: "MASTER_ADMIN", email: "m2@example.org", twoFactorEnabled: true });
  const offers = rowOffers(master, other, { adminView: true, mailAvailable: true });
  expect(Object.values(offers).some(Boolean)).toBe(false);
});

test("the set-password App Link is verified by an assetlinks.json naming the release certificate", () => {
  const links = JSON.parse(read("public", ".well-known", "assetlinks.json"));
  expect(links[0].target.package_name).toBe("com.fieldrepository.app");
  expect(links[0].target.sha256_cert_fingerprints).toEqual([
    "9B:D9:5F:76:37:43:DE:00:46:E7:E8:39:74:D3:81:B8:F5:D7:B2:FA:F5:BB:BA:AB:A4:E6:09:B5:CC:48:5F:3E"
  ]);
  const manifest = readFileSync(join(root, "..", "android", "app", "src", "main", "AndroidManifest.xml"), "utf8");
  expect(manifest).toMatch(/<intent-filter android:autoVerify="true">[\s\S]*?android:path="\/set-password"/);
});
