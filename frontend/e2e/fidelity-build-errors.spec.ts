import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type BrowserContext, type Page, type Route } from "@playwright/test";

const OUT = process.env.DESIGN_OUT ?? join("test-results", "design-fidelity");
const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

type Theme = "light" | "dark";
type Size = { name: string; width: number; height: number };
type RecordMode = "ok" | 401 | 403 | 404 | 503 | "offline";
type Fault = "render" | "global" | null;

const SIZES: Size[] = [
  { name: "1440", width: 1440, height: 900 },
  { name: "834", width: 834, height: 1112 },
  { name: "390", width: 390, height: 844 }
];
const THEMES: Theme[] = ["light", "dark"];

const WORKSHOP = "/workshops/w-kiln";
const ARTISAN = "/artisans/a-meera";
const START = "/guide";
const REFERENCE = "e2e-4f2a9c1d";

const ME = {
  id: "u-lead",
  name: "Nandini Rao",
  email: "nandini@example.org",
  role: "ADMIN",
  isActive: true,
  mustChangePassword: false,
  twoFactorEnabled: false,
  usageConsentAt: "2026-01-01T00:00:00Z"
};

const KILN = {
  id: "w-kiln",
  title: "Kiln Firing Workshop",
  place: "Khurja",
  status: "APPROVED",
  startDate: "2026-09-14",
  endDate: "2026-09-16",
  createdAt: "2026-09-01T10:00:00Z",
  artisans: []
};

class ErrorFake {
  record: RecordMode = "ok";
  recordHits = 0;

  async install(context: BrowserContext, theme: Theme, options: { fault?: Fault; offline?: boolean } = {}) {
    await context.addInitScript(
      ({ chosen, fault, offline }) => {
        window.localStorage.setItem("field_repo_preferences", JSON.stringify({ theme: chosen, reducedMotion: true, largerText: false, highContrast: false }));
        if (fault) window.localStorage.setItem("portal_e2e_fault", fault);
        else window.localStorage.removeItem("portal_e2e_fault");
        const flags = window as unknown as { __e2eOffline?: boolean };
        if (flags.__e2eOffline === undefined) flags.__e2eOffline = offline;
        Object.defineProperty(Navigator.prototype, "onLine", {
          configurable: true,
          get: () => !(window as unknown as { __e2eOffline?: boolean }).__e2eOffline
        });
      },
      { chosen: theme, fault: options.fault ?? null, offline: Boolean(options.offline) }
    );
    await context.addCookies([{ name: "session-marker", value: "e2e-marker", url: BASE_URL }]);
    await context.route((url) => url.pathname.startsWith("/api/"), (route) => this.answer(route));
  }

  private json(route: Route, status: number, body: unknown) {
    return route.fulfill({ status, contentType: "application/json", headers: { date: new Date().toUTCString() }, body: JSON.stringify(body) });
  }

  private async answer(route: Route) {
    const url = new URL(route.request().url());
    const path = url.pathname.replace(/^\/api/, "");
    if (path.startsWith("/session/")) return route.fulfill({ status: 204 });
    if (path === "/me") return this.json(route, 200, ME);
    if (path === "/preferences/me") return this.json(route, 200, {});
    if (path === WORKSHOP || path === ARTISAN) {
      this.recordHits += 1;
      const mode = this.record;
      if (mode === "offline") return route.abort("internetdisconnected");
      if (mode === 401) return this.json(route, 401, { detail: "Not authenticated" });
      if (mode === 403) return this.json(route, 403, { detail: "Forbidden" });
      if (mode === 404) return this.json(route, 404, { detail: "Not Found" });
      if (mode === 503) return this.json(route, 503, { detail: "Service Unavailable" });
      if (path === WORKSHOP) return this.json(route, 200, KILN);
      return this.json(route, 404, { detail: "Not Found" });
    }
    return this.json(route, 404, { detail: "Not Found" });
  }
}

async function open(browser: Browser, size: Size, theme: Theme, options: { fault?: Fault; offline?: boolean; record?: RecordMode } = {}) {
  const mobile = size.width < 900;
  const context = await browser.newContext({
    viewport: { width: size.width, height: size.height },
    deviceScaleFactor: size.width <= 600 ? 2 : 1,
    isMobile: mobile,
    hasTouch: mobile,
    colorScheme: theme,
    serviceWorkers: "block",
    reducedMotion: "reduce"
  });
  const fake = new ErrorFake();
  fake.record = options.record ?? "ok";
  await fake.install(context, theme, options);
  const page = await context.newPage();
  return { context, page, fake };
}

async function settle(page: Page) {
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.addStyleTag({ content: "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}" });
  await page.waitForTimeout(300);
}

async function dismissOfflineDialog(page: Page) {
  const dialog = page.getByRole("alertdialog");
  await dialog.waitFor({ state: "visible", timeout: 5_000 }).catch(() => undefined);
  if (await dialog.isVisible()) await dialog.getByRole("button", { name: "Continue offline" }).click();
  await expect(dialog).toHaveCount(0);
}

function shellNav(page: Page) {
  return page.locator(".nav-island-frame:visible header, .app-search-header-frame:visible header").first();
}

async function noSideScroll(page: Page) {
  const wide = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(wide, "no horizontal page scroll").toBeLessThanOrEqual(1);
}

type Shot = { state: string; testId: string; url: string; fault?: Fault; offline?: boolean; record?: RecordMode; shell?: boolean };

const SHOTS: Shot[] = [
  { state: "not-found", testId: "error-not-found", url: "/this-route-does-not-exist" },
  { state: "record-not-found", testId: "error-not-found", url: WORKSHOP, record: 404, shell: true },
  { state: "forbidden", testId: "error-forbidden", url: WORKSHOP, record: 403, shell: true },
  { state: "session-ended", testId: "error-session", url: `/session-ended?next=${encodeURIComponent(WORKSHOP)}` },
  { state: "server", testId: "error-server", url: "/e2e-shell-fault", fault: "render", shell: true },
  { state: "server-root", testId: "error-server", url: "/e2e-fault", fault: "render" },
  { state: "global", testId: "error-global", url: "/e2e-fault", fault: "global" },
  { state: "offline", testId: "error-offline", url: WORKSHOP, record: "offline", offline: true, shell: true },
  { state: "unavailable", testId: "error-unavailable", url: WORKSHOP, record: 503, shell: true }
];

test.describe.configure({ mode: "parallel" });

for (const shot of SHOTS) {
  for (const theme of THEMES) {
    for (const size of SIZES) {
      test(`error page ${shot.state} · ${theme} · ${size.name}`, async ({ browser }) => {
        const { context, page } = await open(browser, size, theme, shot);
        try {
          await page.goto(shot.url);
          const panel = page.getByTestId(shot.testId);
          await expect(panel).toBeVisible();
          if (shot.offline) await dismissOfflineDialog(page);
          await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
          await expect(panel.getByRole("heading", { level: 1 })).toBeVisible();
          if (shot.shell) await expect(shellNav(page)).toBeVisible();
          await noSideScroll(page);
          await settle(page);
          const dir = join(OUT, `errors-${shot.state}`);
          mkdirSync(dir, { recursive: true });
          await page.screenshot({ path: join(dir, `impl-${size.name}-${theme}.png`), fullPage: true });
        } finally {
          await context.close();
        }
      });
    }
  }
}

const DESKTOP = SIZES[0];

test("a forced render error inside the app keeps the nav and offers Try again, Home and the reference", async ({ browser }) => {
  const { context, page } = await open(browser, DESKTOP, "light", { fault: "render" });
  try {
    await page.goto("/e2e-shell-fault");
    const panel = page.getByTestId("error-server");
    await expect(panel.getByRole("heading", { level: 1, name: "Something went wrong" })).toBeVisible();
    await expect(shellNav(page)).toBeVisible();
    await expect(page.getByTestId("error-brand")).toHaveCount(0);
    await expect(panel.getByTestId("error-reference")).toHaveText(`Ref ${REFERENCE}`);
    await expect(panel.getByRole("button", { name: "Try again" })).toBeVisible();
    await expect(panel.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/dashboard");
    await panel.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByTestId("error-server")).toBeVisible();
  } finally {
    await context.close();
  }
});

test("a render error outside the app draws the branded page without the nav", async ({ browser }) => {
  const { context, page } = await open(browser, DESKTOP, "light", { fault: "render" });
  try {
    await page.goto("/e2e-fault");
    await expect(page.getByTestId("error-server")).toBeVisible();
    await expect(page.getByTestId("error-brand")).toBeVisible();
    await expect(page.getByTestId("error-reference")).toContainText(REFERENCE);
  } finally {
    await context.close();
  }
});

test("the global error page offers Reload and nothing else", async ({ browser }) => {
  const { context, page } = await open(browser, DESKTOP, "light", { fault: "global" });
  try {
    await page.goto("/e2e-fault");
    const panel = page.getByTestId("error-global");
    await expect(panel.getByRole("button")).toHaveCount(1);
    await expect(panel.getByRole("button", { name: "Reload" })).toBeVisible();
    await expect(panel.getByRole("link")).toHaveCount(0);
  } finally {
    await context.close();
  }
});

test("a record's API 404 draws the not-found page, and Go back returns to where the person was", async ({ browser }) => {
  const { context, page } = await open(browser, DESKTOP, "light", { record: 404 });
  try {
    await page.goto(START);
    await page.goto(WORKSHOP);
    const panel = page.getByTestId("error-not-found");
    await expect(panel.getByRole("heading", { level: 1, name: "Workshop not found" })).toBeVisible();
    await expect(panel.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/dashboard");
    await panel.getByRole("button", { name: "Go back" }).click();
    await expect(page).toHaveURL(new RegExp(`${START}$`));
  } finally {
    await context.close();
  }
});

test("a record's API 403 offers Request access where a request flow exists, and Home where none does", async ({ browser }) => {
  const { context, page } = await open(browser, DESKTOP, "light", { record: 403 });
  try {
    await page.goto(WORKSHOP);
    const panel = page.getByTestId("error-forbidden");
    await expect(panel.getByRole("heading", { level: 1, name: "No access" })).toBeVisible();
    await expect(panel.getByRole("link", { name: "Request access" })).toHaveAttribute("href", "/workshop-access/request");
    await page.goto(ARTISAN);
    await expect(page.getByTestId("error-forbidden").getByRole("link")).toHaveCount(1);
    await expect(page.getByTestId("error-forbidden").getByRole("link", { name: "Home" })).toBeVisible();
    await expect(page.getByTestId("error-forbidden")).toContainText("Your account can't open this artisan.");
  } finally {
    await context.close();
  }
});

test("a record's API 401 ends the session, and Sign in again returns to the same record", async ({ browser }) => {
  const { context, page, fake } = await open(browser, DESKTOP, "light", { record: 401 });
  try {
    await page.goto(WORKSHOP);
    await expect(page).toHaveURL(/\/session-ended\?next=%2Fworkshops%2Fw-kiln$/);
    const panel = page.getByTestId("error-session");
    await expect(panel.getByRole("heading", { level: 1, name: "Session ended" })).toBeVisible();
    const signIn = panel.getByRole("link", { name: "Sign in again" });
    await expect(signIn).toHaveAttribute("href", "/login?next=%2Fworkshops%2Fw-kiln");
    fake.record = "ok";
    await signIn.click();
    await expect(page).toHaveURL(new RegExp(`${WORKSHOP}$`), { timeout: 30_000 });
    await expect(page.getByRole("heading", { level: 1, name: KILN.title })).toBeVisible();
  } finally {
    await context.close();
  }
});

test("a record's API 503 counts down and retries on its own", async ({ browser }) => {
  const { context, page, fake } = await open(browser, DESKTOP, "light", { record: 503 });
  try {
    await page.goto(WORKSHOP);
    const panel = page.getByTestId("error-unavailable");
    await expect(panel.getByRole("heading", { level: 1, name: "Service unavailable" })).toBeVisible();
    await expect(panel.getByTestId("error-countdown")).toHaveText(/Retrying in \d+s/);
    await expect(panel.getByRole("button", { name: "Try again" })).toBeVisible();
    const before = fake.recordHits;
    fake.record = "ok";
    await expect(page.getByRole("heading", { level: 1, name: KILN.title })).toBeVisible({ timeout: 20_000 });
    expect(fake.recordHits).toBeGreaterThan(before);
  } finally {
    await context.close();
  }
});

test("a record's API 503 retries at once on Try again", async ({ browser }) => {
  const { context, page, fake } = await open(browser, DESKTOP, "light", { record: 503 });
  try {
    await page.goto(WORKSHOP);
    await expect(page.getByTestId("error-unavailable")).toBeVisible();
    fake.record = "ok";
    await page.getByTestId("error-unavailable").getByRole("button", { name: "Try again" }).click();
    await expect(page.getByRole("heading", { level: 1, name: KILN.title })).toBeVisible({ timeout: 5_000 });
  } finally {
    await context.close();
  }
});

test("offline, a record shows the offline state and loads itself when the connection returns", async ({ browser }) => {
  const { context, page, fake } = await open(browser, DESKTOP, "light", { record: "offline", offline: true });
  try {
    await page.goto(WORKSHOP);
    const panel = page.getByTestId("error-offline");
    await expect(panel).toBeVisible();
    await dismissOfflineDialog(page);
    await expect(panel.getByRole("heading", { level: 1, name: "You're offline" })).toBeVisible();
    await expect(panel).toContainText("Work saved offline stays safe.");
    await expect(panel.getByRole("button", { name: "Try again" })).toBeVisible();
    fake.record = "ok";
    await page.evaluate(() => {
      (window as unknown as { __e2eOffline?: boolean }).__e2eOffline = false;
      window.dispatchEvent(new Event("online"));
    });
    await expect(page.getByRole("heading", { level: 1, name: KILN.title })).toBeVisible();
  } finally {
    await context.close();
  }
});

const HISTORY: Shot[] = SHOTS.filter((shot) => shot.state !== "global" && shot.state !== "offline");

for (const shot of HISTORY) {
  test(`back and forward work from the ${shot.state} page`, async ({ browser }) => {
    const { context, page } = await open(browser, DESKTOP, "light", shot);
    try {
      await page.goto(START);
      await expect(page.getByTestId(shot.testId)).toHaveCount(0);
      await page.goto(shot.url);
      await expect(page.getByTestId(shot.testId)).toBeVisible();
      const errorUrl = page.url();
      await page.goBack();
      await expect(page).toHaveURL(new RegExp(`${START}$`));
      await expect(page.getByTestId(shot.testId)).toHaveCount(0);
      await page.goForward();
      await expect(page).toHaveURL(errorUrl);
      await expect(page.getByTestId(shot.testId)).toBeVisible();
    } finally {
      await context.close();
    }
  });
}
