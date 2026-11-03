export type ThemeChoice = "system" | "light" | "dark";
export type ResolvedTheme = "light" | "dark";

export type Preferences = {
  theme: ThemeChoice;
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

export const PREFERENCES_KEY = "field_repo_preferences";

export const DARK_MEDIA_QUERY = "(prefers-color-scheme: dark)";

export const THEME_COLOR: Record<ResolvedTheme, string> = {
  light: "#f7f6fb",
  dark: "#110f19"
};

export const THEME_CHOICES: ThemeChoice[] = ["system", "light", "dark"];

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
    return { ...DEFAULT_PREFERENCES };
  }
}

export function writeStoredPreferences(preferences: Preferences): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(PREFERENCES_KEY, JSON.stringify(preferences));
  } catch {
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

export function applyPreferences(preferences: Preferences): ResolvedTheme {
  const resolved = resolveTheme(preferences.theme);
  if (typeof document === "undefined") return resolved;

  const root = document.documentElement;
  root.setAttribute("data-theme", resolved);
  setFlag(root, "data-reduced-motion", preferences.reducedMotion);
  setFlag(root, "data-larger-text", preferences.largerText);
  setFlag(root, "data-high-contrast", preferences.highContrast);

  document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => {
    meta.removeAttribute("media");
    meta.setAttribute("content", THEME_COLOR[resolved]);
  });
  return resolved;
}

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
