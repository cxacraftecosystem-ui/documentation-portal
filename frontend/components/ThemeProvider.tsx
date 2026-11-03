"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import {
  DARK_MEDIA_QUERY,
  DEFAULT_PREFERENCES,
  applyPreferences,
  normalizePreferences,
  readStoredPreferences,
  writeStoredPreferences,
  type Preferences,
  type ResolvedTheme
} from "@/lib/preferences";

type ThemeContextValue = {
  preferences: Preferences;
  resolvedTheme: ResolvedTheme;
  update: (patch: Partial<Preferences>) => void;
  saving: boolean;
  error: string | null;
};

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { user, loading: authLoading } = useAuth();
  const [preferences, setPreferences] = useState<Preferences>(DEFAULT_PREFERENCES);
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>("light");
  const [hydrated, setHydrated] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const latest = useRef<Preferences>(DEFAULT_PREFERENCES);
  const signedIn = useRef(false);

  const persist = useCallback(async (next: Preferences) => {
    setSaving(true);
    setError(null);
    try {
      await apiFetch("/preferences/me", { method: "PUT", body: JSON.stringify(next) });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save preferences");
    } finally {
      setSaving(false);
    }
  }, []);

  useEffect(() => {
    const stored = readStoredPreferences();
    latest.current = stored;
    setPreferences(stored);
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    latest.current = preferences;
    writeStoredPreferences(preferences);
    setResolvedTheme(applyPreferences(preferences));
  }, [hydrated, preferences]);

  useEffect(() => {
    if (!hydrated || preferences.theme !== "system") return;
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const media = window.matchMedia(DARK_MEDIA_QUERY);
    const onChange = () => setResolvedTheme(applyPreferences(latest.current));
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [hydrated, preferences.theme]);

  const userId = user?.id ?? null;

  useEffect(() => {
    signedIn.current = Boolean(userId);
    if (!hydrated || authLoading || !userId) return;
    let cancelled = false;
    apiFetch<Partial<Preferences>>("/preferences/me")
      .then((remote) => {
        if (cancelled) return;
        if (!remote || typeof remote.theme !== "string") {
          void persist(latest.current);
          return;
        }
        const next = normalizePreferences(remote);
        latest.current = next;
        setPreferences(next);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, hydrated, persist, userId]);

  const update = useCallback(
    (patch: Partial<Preferences>) => {
      const next = normalizePreferences({ ...latest.current, ...patch });
      latest.current = next;
      setPreferences(next);
      if (signedIn.current) void persist(next);
    },
    [persist]
  );

  const value = useMemo(
    () => ({ preferences, resolvedTheme, update, saving, error }),
    [preferences, resolvedTheme, update, saving, error]
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useThemePreferences() {
  const context = useContext(ThemeContext);
  if (!context) throw new Error("useThemePreferences must be used inside ThemeProvider");
  return context;
}
