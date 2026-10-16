"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { ApiError, apiFetch, setToken } from "@/lib/api";
import type { loginBody } from "@/lib/oidcSignIn";
import { isTwoFactorChallenge, type SignInResult, type TwoFactorChallenge } from "@/lib/twoFactor";
import type { User } from "@/lib/types";

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  /**
   * Each sign-in resolves to the account, or — when it has two-step verification on — to the
   * challenge the second step needs. Nobody is signed in until `verifyTwoFactor` succeeds.
   */
  login: (email: string, password: string) => Promise<User | TwoFactorChallenge>;
  loginWithGoogle: (googleIdToken: string) => Promise<User | TwoFactorChallenge>;
  /** Microsoft or Yahoo: the code the provider sent back, with this tab's half of the flow. See `lib/oidcSignIn.ts`. */
  loginWithOidc: (body: ReturnType<typeof loginBody>) => Promise<User | TwoFactorChallenge>;
  /** The challenge token and a 6-digit or recovery code; resolves to the account, signed in. */
  verifyTwoFactor: (challengeToken: string, code: string) => Promise<User>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const refreshMe = useCallback(async () => {
    try {
      const me = await apiFetch<User>("/me");
      setUser(me);
    } catch (err) {
      // Only discard the stored token when the server explicitly rejected it. On network
      // failures / 5xx the token may still be valid, so keep it for a later retry.
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        setToken(null);
      }
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refreshMe();
  }, [refreshMe]);

  /**
   * A sign-in's answer, taken: a session is adopted and its account returned; a TWO-STEP CHALLENGE is
   * returned untouched, with no token stored — the caller branches on `isTwoFactorChallenge`.
   */
  const open = useCallback((result: SignInResult): User | TwoFactorChallenge => {
    if (isTwoFactorChallenge(result)) return result;
    setToken(result.accessToken);
    setUser(result.user);
    return result.user;
  }, []);

  const signIn = useCallback(
    async (body: unknown) =>
      open(await apiFetch<SignInResult>("/auth/login", { method: "POST", body: JSON.stringify(body) })),
    [open]
  );

  const login = useCallback((email: string, password: string) => signIn({ email, password }), [signIn]);

  const loginWithGoogle = useCallback((googleIdToken: string) => signIn({ googleIdToken }), [signIn]);

  const loginWithOidc = useCallback((body: ReturnType<typeof loginBody>) => signIn(body), [signIn]);

  const verifyTwoFactor = useCallback(
    async (challengeToken: string, code: string) => {
      const account = open(
        await apiFetch<SignInResult>("/auth/two-factor/verify", {
          method: "POST",
          body: JSON.stringify({ challenge_token: challengeToken, code })
        })
      );
      if (isTwoFactorChallenge(account)) throw new Error("Sign in again.");
      return account;
    },
    [open]
  );

  const logout = useCallback(async () => {
    try {
      await apiFetch("/auth/logout", { method: "POST" });
    } finally {
      setToken(null);
      setUser(null);
    }
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, loginWithGoogle, loginWithOidc, verifyTwoFactor, logout, refreshMe }),
    [user, loading, login, loginWithGoogle, loginWithOidc, verifyTwoFactor, logout, refreshMe]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
