"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { ApiError, apiFetch, clearPasswordChangeOwed, endSession, getSessionMarker, onPasswordChangeRequired } from "@/lib/api";
import { forgetCachedMe, meFailure, meRetryDelay, readCachedMe, withinTimeout, writeCachedMe, type MeReach } from "@/lib/meProbe";
import { hasLegacyToken, mayHoldSession, migrateStoredToken } from "@/lib/session";
import type { loginBody } from "@/lib/oidcSignIn";
import { isTwoFactorChallenge, type SignInResult, type TwoFactorChallenge } from "@/lib/twoFactor";
import type { User } from "@/lib/types";
import { invalidateWorkshopList } from "@/lib/workshopList";

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  reach: MeReach;
  retryMe: () => void;
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
  const [user, setUserState] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [reach, setReach] = useState<MeReach>("ok");
  const attempts = useRef(0);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const probeRef = useRef<() => Promise<void>>(async () => undefined);

  const setUser = useCallback((next: User | null) => {
    writeCachedMe(getSessionMarker(), next);
    setUserState(next);
  }, []);

  const scheduleRetry = useCallback(() => {
    if (retryTimer.current) clearTimeout(retryTimer.current);
    const delay = meRetryDelay(attempts.current);
    attempts.current += 1;
    retryTimer.current = setTimeout(() => {
      retryTimer.current = null;
      void probeRef.current();
    }, delay);
  }, []);

  const signInPassword = useRef("");
  const pendingPassword = useRef("");

  const refreshMe = useCallback(async () => {
    try {
      const me = await withinTimeout((signal) => apiFetch<User>("/me", { signal }));
      if (retryTimer.current) clearTimeout(retryTimer.current);
      retryTimer.current = null;
      attempts.current = 0;
      setReach("ok");
      setUser(me);
    } catch (err) {
      const failure = meFailure(err, err instanceof ApiError && err.sessionReplaced);
      if (failure === "replaced") return;
      if (failure === "signedOut") {
        endSession();
        setReach("ok");
        setUser(null);
        return;
      }
      if (!getSessionMarker()) {
        setReach("ok");
        setUser(null);
        return;
      }
      setReach("retrying");
      scheduleRetry();
    } finally {
      setLoading(false);
    }
  }, [scheduleRetry, setUser]);

  useEffect(() => {
    probeRef.current = refreshMe;
  }, [refreshMe]);

  const retryMe = useCallback(() => {
    if (retryTimer.current) clearTimeout(retryTimer.current);
    retryTimer.current = null;
    attempts.current = 0;
    void refreshMe();
  }, [refreshMe]);

  useEffect(() => {
    const legacyToken = hasLegacyToken();
    void migrateStoredToken().finally(() => {
      if (!mayHoldSession(window.location.pathname, legacyToken)) {
        setReach("ok");
        setLoading(false);
        return;
      }
      const cached = readCachedMe(getSessionMarker());
      if (cached) {
        setUserState(cached);
        setLoading(false);
      }
      void refreshMe();
    });
    return () => {
      if (retryTimer.current) clearTimeout(retryTimer.current);
    };
  }, [refreshMe]);

  useEffect(() => {
    if (reach !== "retrying") return;
    const again = () => {
      if (document.visibilityState === "visible") retryMe();
    };
    window.addEventListener("online", again);
    document.addEventListener("visibilitychange", again);
    return () => {
      window.removeEventListener("online", again);
      document.removeEventListener("visibilitychange", again);
    };
  }, [reach, retryMe]);

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
    forgetCachedMe();
    setUserState(null);
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
  }, [setUser]);

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
      reach,
      retryMe,
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
      reach,
      retryMe,
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
