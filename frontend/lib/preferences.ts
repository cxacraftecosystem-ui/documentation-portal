/**
 * Per-user appearance + accessibility preferences.
 *
 * Everything here is deliberately framework-free (no React, no "use client") so the server
 * component in app/layout.tsx can import the boot script string while the client components
 * import the readers/writers. The whole contract is four values; they live in three places and
 * must agree:
 *   - localStorage, so the choice applies before React (or the network) wakes up;
 *   - `data-*` attributes on <html>, which app/globals.css and tailwind.config.ts key off;
 *   - GET/PUT /preferences/me, so the choice follows the account onto another device.
 */

export type ThemeChoice = "system" | "light" | "dark";
/** What `system` actually resolved to — the value stamped as data-theme. */
export type ResolvedTheme = "light" | "dark";

export type Preferences = {
  theme: ThemeChoice;
  /** Force reduced motion. ORs with the OS setting; it can never switch the OS preference off. */
  reducedMotion: boolean;
  largerText: boolean;
  highContrast: boolean;
};

export const DEFAULT_PREFERENCES: Preferences = {
  theme: "system",
  reducedMotion: false,
  largerText: false,
  highContrast: false
};

/** localStorage key, alongside `field_repo_token` from lib/api.ts. */
export const PREFERENCES_KEY = "field_repo_preferences";

export const DARK_MEDIA_QUERY = "(prefers-color-scheme: dark)";

/** Browser-chrome colour per theme — keep in step with `--bg-0` in app/globals.css. */
export const THEME_COLOR: Record<ResolvedTheme, string> = {
  light: "#f7f6fb",
  dark: "#110f19"
};

export const THEME_CHOICES: ThemeChoice[] = ["system", "light", "dark"];

/** Coerce anything (stale localStorage, an older API row) into a complete, valid Preferences. */
export function normalizePreferences(value: unknown): Preferences {
  if (!value || typeof value !== "object") return { ...DEFAULT_PREFERENCES };
  const raw = value as Partial<Record<keyof Preferences, unknown>>;
  return {
    theme: THEME_CHOICES.includes(raw.theme as ThemeChoice)
      ? (raw.theme as ThemeChoice)
      : DEFAULT_PREFERENCES.theme,
    reducedMotion: raw.reducedMotion === true,
    largerText: raw.largerText === true,
    highContrast: raw.highContrast === true
  };
}

export function readStoredPreferences(): Preferences {
  if (typeof window === "undefined") return { ...DEFAULT_PREFERENCES };
  try {
    const raw = window.localStorage.getItem(PREFERENCES_KEY);
    return raw ? normalizePreferences(JSON.parse(raw)) : { ...DEFAULT_PREFERENCES };
  } catch {
    // Corrupt JSON or a locked-down storage sandbox — fall back to the defaults rather than
    // taking the whole app down over a cosmetic setting.
    return { ...DEFAULT_PREFERENCES };
  }
}

export function writeStoredPreferences(preferences: Preferences): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(PREFERENCES_KEY, JSON.stringify(preferences));
  } catch {
    // Private mode / quota — the preference still applies for this session.
  }
}

export function systemPrefersDark(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  return window.matchMedia(DARK_MEDIA_QUERY).matches;
}

export function resolveTheme(theme: ThemeChoice): ResolvedTheme {
  if (theme === "light" || theme === "dark") return theme;
  return systemPrefersDark() ? "dark" : "light";
}

function setFlag(root: HTMLElement, attribute: string, on: boolean): void {
  if (on) root.setAttribute(attribute, "true");
  else root.removeAttribute(attribute);
}

/**
 * Stamp the preferences onto <html> (and the address-bar colour) and report the resolved theme.
 * This is the ONLY place that writes those attributes at runtime; the boot script below is its
 * pre-hydration twin.
 */
export function applyPreferences(preferences: Preferences): ResolvedTheme {
  const resolved = resolveTheme(preferences.theme);
  if (typeof document === "undefined") return resolved;

  const root = document.documentElement;
  root.setAttribute("data-theme", resolved);
  setFlag(root, "data-reduced-motion", preferences.reducedMotion);
  setFlag(root, "data-larger-text", preferences.largerText);
  setFlag(root, "data-high-contrast", preferences.highContrast);

  // The <meta name="theme-color"> pair rendered from `viewport` in app/layout.tsx is scoped by
  // prefers-color-scheme, which is wrong the moment a user picks a theme the OS disagrees with.
  // Dropping `media` and painting both tags with the resolved colour makes the explicit choice win.
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => {
    meta.removeAttribute("media");
    meta.setAttribute("content", THEME_COLOR[resolved]);
  });
  return resolved;
}

/**
 * Blocking inline script for <body>'s first child: it runs while the parser is still above any
 * content, so the themed attributes are on <html> before the first paint. Without it every load
 * flashes the light theme before React hydrates. Kept as a string built from the constants above
 * so the storage key and the attribute names can never drift from applyPreferences().
 */
export const PREFERENCES_BOOT_SCRIPT = `(function(){try{` +
  `var d=document.documentElement;` +
  `var p=JSON.parse(window.localStorage.getItem(${JSON.stringify(PREFERENCES_KEY)})||"{}")||{};` +
  `var t=p.theme;` +
  `var dark=t==="dark"||(t!=="light"&&window.matchMedia(${JSON.stringify(DARK_MEDIA_QUERY)}).matches);` +
  `d.setAttribute("data-theme",dark?"dark":"light");` +
  `if(p.reducedMotion===true)d.setAttribute("data-reduced-motion","true");` +
  `if(p.largerText===true)d.setAttribute("data-larger-text","true");` +
  `if(p.highContrast===true)d.setAttribute("data-high-contrast","true");` +
  `}catch(e){}})();`;
