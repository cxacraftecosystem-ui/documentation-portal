/**
 * Setting a first password: the provider sign-in, run again (`lib/providerReauth.ts`), and the
 * `reauth.` state that brings a Microsoft or Yahoo answer back to Settings instead of the sign-in page.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { REAUTH_STATE_PREFIX, callbackTarget, type PendingSignIn } from "@/lib/oidcSignIn";
import {
  PROOF_LIFETIME_MS,
  asReauthProvider,
  carriedProof,
  confirmMissing,
  confirmPrompt,
  googleProof,
  oidcProof,
  proofIsFresh,
  reauthProviderFor
} from "@/lib/providerReauth";

const root = join(__dirname, "..");

test("the provider to run again is the account's own, and Google when it is not one of the three", () => {
  expect(reauthProviderFor({ authProvider: "MICROSOFT" })).toBe("MICROSOFT");
  expect(reauthProviderFor({ authProvider: "yahoo" })).toBe("YAHOO");
  expect(reauthProviderFor({ authProvider: "GOOGLE" })).toBe("GOOGLE");
  expect(reauthProviderFor({ authProvider: "LOCAL" })).toBe("GOOGLE");
  expect(reauthProviderFor(null)).toBe("GOOGLE");
  expect(asReauthProvider("microsoft")).toBe("MICROSOFT");
  expect(asReauthProvider("1")).toBeNull();
});

test("a confirmation is used for under five minutes, then asked for again", () => {
  const proof = googleProof("id-token", 1_000);
  expect(proof.fields).toEqual({ googleIdToken: "id-token" });
  expect(proofIsFresh(proof, 1_000 + PROOF_LIFETIME_MS - 1)).toBe(true);
  expect(proofIsFresh(proof, 1_000 + PROOF_LIFETIME_MS)).toBe(false);
  expect(proofIsFresh(null, 1_000)).toBe(false);
  expect(PROOF_LIFETIME_MS).toBeLessThan(5 * 60 * 1000);
});

test("a Microsoft or Yahoo confirmation is the sign-in body, unredeemed", () => {
  const pending: PendingSignIn = {
    provider: "YAHOO",
    state: `${REAUTH_STATE_PREFIX}abc`,
    codeVerifier: "v".repeat(64),
    rawNonce: "n".repeat(43),
    redirectUri: "https://portal.example.org/login/callback",
    agreedAt: null,
    startedAt: 0
  };
  const proof = oidcProof(pending, "the-code", 5);
  expect(proof.provider).toBe("YAHOO");
  expect(proof.fields).toEqual({
    oidcProvider: "YAHOO",
    oidcCode: "the-code",
    oidcCodeVerifier: "v".repeat(64),
    oidcRedirectUri: "https://portal.example.org/login/callback",
    oidcNonce: "n".repeat(43)
  });
});

test("a re-run sign-in comes back to Settings in the fragment, never to the sign-in page", () => {
  const target = callbackTarget(new URLSearchParams(`code=abc&state=${REAUTH_STATE_PREFIX}s1&session_state=z`));
  expect(target.startsWith("/settings#oidc=")).toBe(true);
  expect(decodeURIComponent(target.split("#oidc=")[1])).toBe(`code=abc&state=${REAUTH_STATE_PREFIX}s1`);
  expect(callbackTarget(new URLSearchParams("code=abc&state=web.s1")).startsWith("/login#oidc=")).toBe(true);
});

test("the words name the provider and never the mechanism", () => {
  for (const line of [confirmPrompt("GOOGLE"), confirmMissing("MICROSOFT")]) {
    expect(line).not.toMatch(/token|header|iat|proof|endpoint/i);
  }
  expect(confirmPrompt("YAHOO")).toContain("sign in with Yahoo again");
});

test("the card sends the confirmation with the password and drops it on a refusal", () => {
  const card = readFileSync(join(root, "components", "settings", "ChangePasswordCard.tsx"), "utf8");
  expect(card).toContain("sending?.fields");
  expect(card).toMatch(/askedFor[\s\S]{0,200}setProof\(null\)/);
  expect(card).toContain("callback.state.startsWith(REAUTH_STATE_PREFIX)");
});

test("a wrong code keeps the confirmation: what the server handed back replaces the spent answer", () => {
  const pending = { provider: "MICROSOFT", state: "reauth.x", codeVerifier: "v".repeat(64), nonce: "n".repeat(20), redirectUri: "https://x.test/login/callback" } as unknown as PendingSignIn;
  const first = oidcProof(pending, "the-code", 1_000);
  const next = carriedProof(first, "stand-in");
  expect(next.fields).toEqual({ providerProof: "stand-in" });
  expect(next.provider).toBe("MICROSOFT");
  // Its clock is the original sign-in's, so it is never kept past the server's five minutes.
  expect(next.obtainedAt).toBe(1_000);
  expect(proofIsFresh(next, 1_000 + PROOF_LIFETIME_MS)).toBe(false);
  const card = readFileSync(join(root, "components/settings/ChangePasswordCard.tsx"), "utf8");
  expect(card).toContain("setProof(carriedProof(sending, carried))");
});
