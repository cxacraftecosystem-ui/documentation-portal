"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { ApiError, apiFetch, clearPasswordChangeOwed, onPasswordChangeRequired, setToken } from "@/lib/api";
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
  /** Forget the session in this tab without calling the server — a password link has just ended it. */
  clearSession: () => void;
  /**
   * The password of the last password sign-in that succeeded in this tab, so `FirstPasswordGate` does
   * not ask somebody to retype what they typed seconds ago. "" when there is none.
   */
  recentPassword: () => string;
  /** The gate was satisfied: forget the remembered password and re-read the account. */
  markPasswordChanged: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const signInPassword = useRef("");
  const pendingPassword = useRef("");

  const refreshMe = useCallback(async () => {
    try {
      const me = await apiFetch<User>("/me");
      setUser(me);
    } catch (err) {
      // A request that left with a token this tab has since replaced says nothing about the live one.
      if (err instanceof ApiError && err.sessionReplaced) return;
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

  // A request refused because the account must choose a new password: re-read `/me` (which carries
  // `mustChangePassword`) so the shell draws the gate in place. One re-read at a time.
  const rereading = useRef(false);
  useEffect(() => {
    onPasswordChangeRequired(() => {
      if (rereading.current) return;
      rereading.current = true;
      void refreshMe().finally(() => {
        rereading.current = false;
      });
    });
    return () => onPasswordChangeRequired(null);
  }, [refreshMe]);

  const clearSession = useCallback(() => {
    setToken(null);
    setUser(null);
    signInPassword.current = "";
    pendingPassword.current = "";
    clearPasswordChangeOwed();
  }, []);

  const recentPassword = useCallback(() => signInPassword.current, []);

  const markPasswordChanged = useCallback(async () => {
    signInPassword.current = "";
    clearPasswordChangeOwed();
    await refreshMe();
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

  const login = useCallback(
    async (email: string, password: string) => {
      const result = await signIn({ email, password });
      // Held in memory only, and only for the forced change straight after this sign-in.
      if (isTwoFactorChallenge(result)) pendingPassword.current = password;
      else signInPassword.current = password;
      return result;
    },
    [signIn]
  );

  const loginWithGoogle = useCallback(
    (googleIdToken: string) => {
      signInPassword.current = "";
      pendingPassword.current = "";
      return signIn({ googleIdToken });
    },
    [signIn]
  );

  const loginWithOidc = useCallback(
    (body: ReturnType<typeof loginBody>) => {
      signInPassword.current = "";
      pendingPassword.current = "";
      return signIn(body);
    },
    [signIn]
  );

  const verifyTwoFactor = useCallback(
    async (challengeToken: string, code: string) => {
      const account = open(
        await apiFetch<SignInResult>("/auth/two-factor/verify", {
          method: "POST",
          body: JSON.stringify({ challenge_token: challengeToken, code })
        })
      );
      if (isTwoFactorChallenge(account)) throw new Error("Sign in again.");
      signInPassword.current = pendingPassword.current;
      pendingPassword.current = "";
      return account;
    },
    [open]
  );

  const logout = useCallback(async () => {
    try {
      await apiFetch("/auth/logout", { method: "POST" });
    } finally {
      clearSession();
    }
  }, [clearSession]);

  const value = useMemo(
    () => ({
      user,
      loading,
      login,
      loginWithGoogle,
      loginWithOidc,
      verifyTwoFactor,
      logout,
      refreshMe,
      clearSession,
      recentPassword,
      markPasswordChanged
    }),
    [
      user,
      loading,
      login,
      loginWithGoogle,
      loginWithOidc,
      verifyTwoFactor,
      logout,
      refreshMe,
      clearSession,
      recentPassword,
      markPasswordChanged
    ]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
