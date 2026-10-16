/**
 * TWO-STEP VERIFICATION — the browser's half, pinned.
 *
 * Pure functions from `lib/twoFactor.ts`, plus source assertions for the judgements that live in JSX
 * (there is no React renderer in devDependencies):
 *
 *   1. A code's KIND is read from its shape exactly as the server's `code_kind` reads it.
 *   2. A sign-in that answers a challenge is recognised as one, and a session is not.
 *   3. Every first factor on /login goes through the one branch that can show the code form.
 *   4. The challenge form's "timed out" sentence is the server's, character for character.
 *
 * ⚠ Source assertions are line-ending agnostic: `\s` and `[\s\S]`, never `\n`.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CHALLENGE_ENDED_DETAIL, codeKind, isTwoFactorChallenge, recoveryCodesFileText } from "@/lib/twoFactor";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const LOGIN = read("app", "login", "page.tsx");
const SERVICE = read("..", "backend", "app", "services", "two_factor.py");

test("a code's kind is its shape", () => {
  expect(codeKind("123456")).toBe("totp");
  expect(codeKind("123 456")).toBe("totp");
  expect(codeKind("abcd-efgh")).toBe("recovery");
  expect(codeKind("ABCD EFGH")).toBe("recovery");
  expect(codeKind("12345")).toBeNull();
  expect(codeKind("abcd-efg")).toBeNull();
  expect(codeKind("")).toBeNull();
});

test("a challenge is told apart from a session", () => {
  expect(isTwoFactorChallenge({ two_factor_required: true, challenge_token: "t" })).toBe(true);
  expect(isTwoFactorChallenge({ accessToken: "a", user: {} })).toBe(false);
  expect(isTwoFactorChallenge({ two_factor_required: true })).toBe(false);
  expect(isTwoFactorChallenge(null)).toBe(false);
});

test("the recovery-code file carries every code, one per line", () => {
  const text = recoveryCodesFileText(["abcd-efgh", "jkmn-pqrs"], "meera@example.org");
  expect(text).toContain("meera@example.org");
  expect(text.split(/\r?\n/)).toEqual(expect.arrayContaining(["abcd-efgh", "jkmn-pqrs"]));
});

test("every first factor on /login can lead to the code form", () => {
  expect(LOGIN).toMatch(/proceed\(await login\(email, password\)\)/);
  expect(LOGIN).toMatch(/proceed\(await loginWithGoogle\(/);
  expect(LOGIN).toMatch(/proceed\(await loginWithOidc\(/);
  expect(LOGIN).toMatch(/<TwoStepChallengeForm[\s\S]*challengeToken=\{challenge\}/);
});

test("the timed-out sentence is the server's", () => {
  expect(SERVICE).toContain(`CHALLENGE_DEAD_DETAIL = "${CHALLENGE_ENDED_DETAIL}"`);
});
