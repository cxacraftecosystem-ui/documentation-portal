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
        }
      },
      boxShadow: {
        soft: "0 1px 3px rgba(20, 20, 19, 0.08)",
        panel: "0 20px 50px rgba(20, 20, 19, 0.08)"
      }
    }
  },
  plugins: []
};

export default config;
