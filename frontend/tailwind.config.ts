import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        field: {
          50: "#faf9f5",
          100: "#f5f0e8",
          200: "#efe9de",
          300: "#e8e0d2",
          400: "#e8a55a",
          500: "#cc785c",
          600: "#a9583e",
          700: "#7f3f2d",
          900: "#181715"
        },
        ink: {
          DEFAULT: "#141413",
          body: "#3d3d3a",
          muted: "#6c6a64",
          soft: "#8e8b82"
        },
        background: "#faf9f5",
        foreground: "#141413",
        card: "#ffffff",
        popover: "#ffffff",
        border: "#e6dfd8",
        input: "#e6dfd8",
        ring: "#cc785c",
        accent: "#f5f0e8",
        "accent-foreground": "#141413",
        primary: "#a9583e",
        "primary-foreground": "#ffffff",
        secondary: "#efe9de",
        "secondary-foreground": "#141413",
        destructive: "#dc2626",
        "destructive-foreground": "#ffffff",
        muted: "#f5f0e8",
        "muted-foreground": "#6c6a64"
      },
      boxShadow: {
        soft: "0 1px 3px rgba(20, 20, 19, 0.08)",
        panel: "0 20px 50px rgba(20, 20, 19, 0.08)"
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
