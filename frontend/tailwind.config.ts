import type { Config } from "tailwindcss";

/**
 * Field Repository design tokens — ported from the ChartMate design system
 * (D:\ChartmateV1-main frontend/src/app/globals.css) onto Tailwind v3.
 *
 * Purple ramp: OKLCH, hue locked at 305°; purple-700 is THE action color.
 * Tinted neutrals (ink/line/surface/bg-0) replace grey. Gold is a marketing
 * accent (hero + auth only). Shadows are purple-tinted.
 *
 * The legacy `field`/`thread` scales are kept as aliases onto the new ramp so
 * existing pages restyle without a rewrite (field-500/600/700 → purple actions,
 * field-50/100/200/300 → surfaces, field-900 → ink).
 *
 * Theming: every SEMANTIC neutral resolves through a CSS custom property declared in
 * app/globals.css as a bare "R G B" triplet, so `data-theme="dark"` on <html> repaints the whole
 * app without a single page edit. `<alpha-value>` keeps `bg-card/70`-style modifiers working.
 * The purple and gold ramps stay literal — brand colour does not invert.
 */
const neutral = (token: string) => `rgb(var(--${token}) / <alpha-value>)`;

const purple = {
  50: "oklch(0.977 0.013 305 / <alpha-value>)",
  100: "oklch(0.946 0.03 305 / <alpha-value>)",
  200: "oklch(0.9 0.058 305 / <alpha-value>)",
  300: "oklch(0.828 0.1 305 / <alpha-value>)",
  400: "oklch(0.738 0.15 305 / <alpha-value>)",
  500: "oklch(0.648 0.19 305 / <alpha-value>)",
  600: "oklch(0.56 0.205 305 / <alpha-value>)",
  700: "oklch(0.47 0.198 305 / <alpha-value>)",
  800: "oklch(0.4 0.18 305 / <alpha-value>)",
  900: "oklch(0.34 0.15 305 / <alpha-value>)",
  950: "oklch(0.255 0.108 305 / <alpha-value>)"
};

const gold = {
  100: "oklch(0.95 0.045 90 / <alpha-value>)",
  200: "oklch(0.9 0.08 88 / <alpha-value>)",
  300: "oklch(0.85 0.11 86 / <alpha-value>)",
  400: "oklch(0.78 0.135 84 / <alpha-value>)",
  500: "oklch(0.7 0.145 80 / <alpha-value>)",
  600: "oklch(0.6 0.13 75 / <alpha-value>)",
  700: "oklch(0.5 0.11 70 / <alpha-value>)"
};

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  // ThemeProvider stamps data-theme onto <html>; the "class" strategy keeps `dark:` usable too.
  darkMode: ["class", '[data-theme="dark"]'],
  theme: {
    extend: {
      fontFamily: {
        sans: ["var(--font-inter)", "ui-sans-serif", "system-ui", "sans-serif"],
        display: ["var(--font-jakarta)", "var(--font-inter)", "ui-sans-serif", "sans-serif"],
        // Legacy slots — everything resolves to the two brand faces.
        serif: ["var(--font-jakarta)", "var(--font-inter)", "ui-sans-serif", "sans-serif"]
      },
      colors: {
        purple,
        gold,
        ink: {
          DEFAULT: neutral("ink-900"),
          900: neutral("ink-900"),
          700: neutral("ink-700"),
          500: neutral("ink-500"),
          300: neutral("ink-300"),
          // Legacy aliases used across existing pages.
          body: neutral("ink-700"),
          muted: neutral("ink-500"),
          soft: neutral("ink-300")
        },
        line: { 200: neutral("line-200") },
        surface: { 50: neutral("surface-50") },
        "bg-0": neutral("bg-0"),
        // Legacy `field` scale → mapped onto the new system. 50–300 are the tinted surface ladder
        // (themed); 400–700 are the purple ramp (brand, never inverted); 900 is heading ink.
        field: {
          50: neutral("surface-50"),
          100: neutral("surface-100"),
          200: neutral("surface-200"),
          300: neutral("surface-300"),
          400: purple[400],
          500: purple[600],
          600: purple[700],
          700: purple[800],
          900: neutral("ink-900")
        },
        thread: { DEFAULT: gold[500], soft: gold[200] },
        // Brand-native logo colors (Android launcher icon) — never re-themed.
        logo: { cream: "#FAF9F5", terracotta: "#CC785C", ink: "#181715" },
        amber: { 100: "#fef3c7", 500: "#f59e0b", 800: "#92400e" },
        success: { 100: "#dcfce7", 600: "#15803d" },
        error: { 100: "#fee2e2", 600: "#dc2626" },
        background: neutral("bg-0"),
        foreground: neutral("ink-900"),
        // `card` is the themed stand-in for the old literal bg-white on every panel/card surface.
        // Tailwind's built-in `white` is deliberately untouched: text-white on purple stays white.
        card: neutral("card"),
        popover: neutral("card"),
        border: neutral("line-200"),
        input: neutral("line-200"),
        ring: purple[600],
        accent: purple[50],
        "accent-foreground": purple[700],
        primary: purple[700],
        "primary-foreground": "#ffffff",
        secondary: neutral("ink-500"),
        "secondary-foreground": neutral("card"),
        destructive: "#dc2626",
        "destructive-foreground": "#ffffff",
        muted: neutral("surface-50"),
        "muted-foreground": neutral("ink-500")
      },
      borderRadius: {
        sm: "8px",
        md: "12px",
        lg: "16px",
        xl: "24px"
      },
      boxShadow: {
        sm: "0 1px 2px rgba(46, 16, 101, 0.06)",
        soft: "0 1px 2px rgba(46, 16, 101, 0.06)",
        md: "0 4px 16px rgba(46, 16, 101, 0.08)",
        lg: "0 8px 32px rgba(46, 16, 101, 0.12)",
        panel: "0 8px 32px rgba(46, 16, 101, 0.12)",
        island: "0 4px 16px rgba(46, 16, 101, 0.12), 0 1px 2px rgba(46, 16, 101, 0.06)",
        cta: "0 8px 24px oklch(0.47 0.198 305 / 0.28)",
        glow: "0 8px 24px oklch(0.47 0.198 305 / 0.28)",
        "glow-soft": "0 4px 16px oklch(0.47 0.198 305 / 0.16)"
      },
      transitionTimingFunction: {
        out: "cubic-bezier(0.16, 1, 0.3, 1)",
        spring: "cubic-bezier(0.34, 1.56, 0.64, 1)"
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" }
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" }
        }
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out"
      }
    }
  },
  plugins: []
};

export default config;
