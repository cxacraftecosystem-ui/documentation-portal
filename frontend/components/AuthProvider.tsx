"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { ApiError, apiFetch, clearPasswordChangeOwed, endSession, onPasswordChangeRequired } from "@/lib/api";
import { migrateStoredToken } from "@/lib/session";
import type { loginBody } from "@/lib/oidcSignIn";
import { isTwoFactorChallenge, type SignInResult, type TwoFactorChallenge } from "@/lib/twoFactor";
import type { User } from "@/lib/types";
import { invalidateWorkshopList } from "@/lib/workshopList";

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<User | TwoFactorChallenge>;
  loginWithGoogle: (googleIdToken: string) => Promise<User | TwoFactorChallenge>;
  loginWithOidc: (body: ReturnType<typeof loginBody>) => Promise<User | TwoFactorChallenge>;
  verifyTwoFactor: (challengeToken: string, code: string) => Promise<User>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
  clearSession: () => void;
  recentPassword: () => string;
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
      if (err instanceof ApiError && err.sessionReplaced) return;
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        endSession();
      }
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void migrateStoredToken().finally(() => refreshMe());
  }, [refreshMe]);

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
    endSession();
    setUser(null);
    signInPassword.current = "";
    pendingPassword.current = "";
    clearPasswordChangeOwed();
    invalidateWorkshopList();
  }, []);

  const recentPassword = useCallback(() => signInPassword.current, []);

  const markPasswordChanged = useCallback(async () => {
    signInPassword.current = "";
    clearPasswordChangeOwed();
    await refreshMe();
  }, [refreshMe]);

  const open = useCallback((result: SignInResult): User | TwoFactorChallenge => {
    if (isTwoFactorChallenge(result)) return result;
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
