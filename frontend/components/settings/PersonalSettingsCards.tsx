"use client";

import { Accessibility, Monitor, Moon, Palette, Sun } from "lucide-react";

import { Toggle } from "@/components/settings/Toggle";
import { useThemePreferences } from "@/components/ThemeProvider";
import type { Preferences, ThemeChoice } from "@/lib/preferences";

/**
 * The two cards EVERY signed-in account owns: how the repository looks, and how it reads.
 *
 * They carry no role gate on purpose — these are this account's own preferences, saved to the
 * account the moment they are clicked — and they are the only thing an ordinary user sees on
 * /settings, side by side. Keeping them here (rather than inline on the page) is what lets the page
 * lay them out as a pair without also inheriting the admin panels that used to share their column.
 */

const THEME_OPTIONS: Array<{ value: ThemeChoice; label: string; helper: string; Icon: typeof Monitor }> = [
  { value: "system", label: "Match my device", helper: "Follows your system's light/dark setting, and switches with it.", Icon: Monitor },
  { value: "light", label: "Light", helper: "The default paper-white canvas.", Icon: Sun },
  { value: "dark", label: "Dark", helper: "Purple-tinted dark surfaces for low light.", Icon: Moon }
];

/** The three accessibility switches, keyed by the preference they flip. */
const ACCESSIBILITY_OPTIONS: Array<{
  key: Exclude<keyof Preferences, "theme">;
  label: string;
  helper: string;
}> = [
  {
    key: "reducedMotion",
    label: "Reduce motion",
    helper: "Stops animations and transitions. Your device's own reduce-motion setting is always honoured too — this only ever adds to it."
  },
  { key: "largerText", label: "Larger text", helper: "Scales the whole interface up by about a tenth." },
  {
    key: "highContrast",
    label: "High contrast",
    helper: "Stronger borders, darker muted text and a thicker keyboard focus ring."
  }
];

function CardHeading({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="flex items-center gap-2.5">
      <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">{icon}</span>
      <h2 className="font-display font-bold text-ink-900">{title}</h2>
    </div>
  );
}

export function AppearanceCard() {
  const { preferences, resolvedTheme, update } = useThemePreferences();

  return (
    <section className="panel flex h-full flex-col p-5">
      <CardHeading icon={<Palette className="h-4 w-4" aria-hidden />} title="Appearance" />
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        Applies to this account everywhere you sign in.{" "}
        {preferences.theme === "system" ? `Your device is currently ${resolvedTheme}.` : null}
      </p>
      <div className="mt-3 grid gap-2">
        {THEME_OPTIONS.map((option) => {
          const active = preferences.theme === option.value;
          return (
            <label
              className={`flex cursor-pointer items-start gap-3 rounded-md border p-3 transition ${
                active ? "border-purple-300 bg-purple-50" : "border-line-200 bg-card hover:border-purple-300"
              }`}
              key={option.value}
            >
              <input
                checked={active}
                className="mt-1 accent-purple-700"
                name="theme"
                onChange={() => update({ theme: option.value })}
                type="radio"
                value={option.value}
              />
              <span className="flex-1">
                <span className="flex items-center gap-1.5 text-sm font-medium text-ink-900">
                  <option.Icon className="h-4 w-4 text-purple-700" aria-hidden />
                  {option.label}
                </span>
                <span className="block text-xs leading-5 text-ink-500">{option.helper}</span>
              </span>
            </label>
          );
        })}
      </div>
    </section>
  );
}

export function AccessibilityCard() {
  const { preferences, update, saving, error } = useThemePreferences();

  return (
    <section className="panel flex h-full flex-col p-5">
      <CardHeading icon={<Accessibility className="h-4 w-4" aria-hidden />} title="Accessibility" />
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        Reading comfort for this account. Each switch applies the moment it is flipped.
      </p>
      <div className="mt-3 grid gap-3">
        {ACCESSIBILITY_OPTIONS.map((option) => (
          <div className="flex items-start justify-between gap-4" key={option.key}>
            <div>
              <span className="block text-sm font-medium text-ink-900">{option.label}</span>
              <span className="block text-xs leading-5 text-ink-500">{option.helper}</span>
            </div>
            <Toggle checked={preferences[option.key]} label={option.label} onChange={(next) => update({ [option.key]: next })} />
          </div>
        ))}
      </div>
      <p className="mt-auto pt-4 text-xs leading-5 text-ink-500" role="status">
        {error ?? (saving ? "Saving to your account..." : "Saved to your account automatically.")}
      </p>
    </section>
  );
}
