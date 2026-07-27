import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end config. Deliberately does NOT start its own server: these specs are pointed at a
 * dev server the developer is already running, or at a deployed URL via E2E_BASE_URL, so a run
 * never silently tests a different build from the one on screen.
 *
 * Credentials come from the environment rather than a fixture — the specs sign in against a real
 * API, and a checked-in password would be a credential in git.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // The specs sign in as the same user and navigate real records; running them concurrently would
  // have them fighting over one session.
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    screenshot: "only-on-failure",
    trace: "retain-on-failure"
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }]
});
