import { defineConfig, devices } from "@playwright/test";

const OUT = process.env.GATE_OUT ?? "../release-gate-out";

export default defineConfig({
  testDir: "./release-gate",
  timeout: 300_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  outputDir: `${OUT}/web-test-results`,
  reporter: [["list"], ["json", { outputFile: `${OUT}/web-report.json` }]],
  use: {
    baseURL: process.env.GATE_WEB,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    acceptDownloads: true
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }]
});
