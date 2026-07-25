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
  /** The user's choices, including `theme: "system"` as chosen rather than as resolved. */
  preferences: Preferences;
  /** What `system` currently means — "light" until the first effect runs on the client. */
  resolvedTheme: ResolvedTheme;
  /** Patch one or more preferences: applies instantly, then catches the server up. */
  update: (patch: Partial<Preferences>) => void;
  saving: boolean;
  error: string | null;
};

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

/**
 * Owns theme + accessibility preferences for the whole app.
 *
 * Order of authority, and why: the inline boot script in app/layout.tsx paints from localStorage
 * before hydration, this provider re-applies the same stored values on mount, and the server row
 * (GET /preferences/me) lands last and only once the user is known. So the UI never flashes and
 * never waits on the network — signing in on a second device simply corrects it a moment later.
 *
 * Must sit inside AuthProvider: the sync step needs the current user.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { user, loading: authLoading } = useAuth();
  // Starts at the defaults so the first client render matches the server-rendered HTML; the mount
  // effect immediately swaps in the stored values (the boot script already painted them).
  const [preferences, setPreferences] = useState<Preferences>(DEFAULT_PREFERENCES);
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>("light");
  const [hydrated, setHydrated] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Latest applied preferences / auth state, readable from async callbacks without re-subscribing.
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

  // Single writer: whatever `preferences` holds is what <html> and localStorage carry. Gated on
  // `hydrated` so the pre-mount defaults never stomp the theme the boot script already painted.
  useEffect(() => {
    if (!hydrated) return;
    latest.current = preferences;
    writeStoredPreferences(preferences);
    setResolvedTheme(applyPreferences(preferences));
  }, [hydrated, preferences]);

  // Follow the OS while the choice is "system" — an explicit light/dark ignores it entirely.
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
        // The endpoint answers `{}` when the user has never saved any (same shape as /feedback/me).
        // Seed the account from this device instead, so the current look is what follows them.
        if (!remote || typeof remote.theme !== "string") {
          void persist(latest.current);
          return;
        }
        const next = normalizePreferences(remote);
        latest.current = next;
        setPreferences(next);
      })
      .catch(() => {
        // Offline or a 5xx: the local preferences are already applied, so say nothing.
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
