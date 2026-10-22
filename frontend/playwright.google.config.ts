import { defineConfig, devices } from "@playwright/test";

/**
 * The Google sign-in device matrix — `e2e/google-signin-devices.spec.ts` on the screens people use.
 *
 * A config of its own because the main one is a single desktop Chromium project with a preflight
 * that wants a live API; this spec needs neither, and it needs WebKit. `E2E_BASE_URL` is required:
 * the workflow points it at the live site or at a production build of the branch.
 */
const BASE_URL = process.env.E2E_BASE_URL;
if (!BASE_URL) throw new Error("Set E2E_BASE_URL to the site whose login page should be checked.");

export default defineConfig({
  testDir: "./e2e",
  testMatch: "google-signin-devices.spec.ts",
  outputDir: process.env.E2E_SHOT_DIR ?? "test-results/google-signin",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: true,
  workers: 2,
  retries: 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report/google-signin" }]],
  use: {
    baseURL: BASE_URL,
    screenshot: "on",
    trace: "retain-on-failure"
  },
  projects: [
    { name: "ipad-webkit", use: { ...devices["iPad Pro 11"] } },
    { name: "ipad-webkit-landscape", use: { ...devices["iPad Pro 11 landscape"] } },
    { name: "galaxy-tab-chromium", use: { ...devices["Galaxy Tab S4"] } },
    { name: "galaxy-tab-chromium-landscape", use: { ...devices["Galaxy Tab S4 landscape"] } },
    { name: "iphone-webkit", use: { ...devices["iPhone 15"] } },
    { name: "pixel-chromium", use: { ...devices["Pixel 7"] } },
    { name: "desktop-chromium", use: { ...devices["Desktop Chrome"] } }
  ]
});
