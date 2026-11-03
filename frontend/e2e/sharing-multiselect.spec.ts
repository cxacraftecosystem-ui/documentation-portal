import { expect, test, type Locator, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const SHOTS = process.env.E2E_SHOT_DIR ?? "test-results/sharing";

const PEOPLE = [
  { id: "p-alice", name: "Alice Fernandes", email: "alice@example.org", role: "RESEARCHER" },
  { id: "p-bhaskar", name: "Bhaskar Rao", email: "bhaskar@example.org", role: "RESEARCHER" },
  { id: "p-chandra", name: "Chandra Menon", email: "chandra@example.org", role: "RESEARCHER" },
  { id: "p-devi", name: "Devi Krishnan", email: "devi@example.org", role: "PROFESSOR" },
  { id: "p-esha", name: "Esha Roy", email: "esha@example.org", role: "FIELD_CONTRIBUTOR" },
  { id: "p-farid", name: "Farid Ali", email: "farid@example.org", role: "RESEARCHER" },
  { id: "p-gita", name: "Gita Sharma", email: "gita@example.org", role: "RESEARCHER" },
  { id: "p-hari", name: "Hari Prasad", email: "hari@example.org", role: "RESEARCHER" },
  { id: "p-indu", name: "Indu Bose", email: "indu@example.org", role: "RESEARCHER" }
];

const byId = new Map(PEOPLE.map((p) => [p.id, p]));

function grantRow(over: Record<string, unknown>) {
  return {
    id: `g-${over.granteeId ?? over.ownerId}`,
    ownerId: "me",
    granteeId: "me",
    tier: "DOWNLOAD",
    status: "GRANTED",
    allData: true,
    scopeItems: [],
    owner: byId.get(String(over.ownerId)) ?? null,
    grantee: byId.get(String(over.granteeId)) ?? null,
    ...over
  };
}

const GRANTS_FIXTURE = {
  incoming: [
    grantRow({ granteeId: "p-alice", tier: "EDIT", status: "GRANTED", allData: true }),
    grantRow({ granteeId: "p-bhaskar", tier: "DOWNLOAD", status: "GRANTED", allData: true }),
    grantRow({ granteeId: "p-chandra", tier: "COMMENT", status: "PENDING", allData: true })
  ],
  outgoing: [grantRow({ ownerId: "p-devi", granteeId: "me", tier: "COMMENT", status: "GRANTED", allData: true })]
};

const OUTCOMES: Record<string, { status: number; detail?: string }> = {
  "p-devi": { status: 409, detail: "You already have an active grant (COMMENT) from this researcher." },
  "p-esha": { status: 500, detail: "Database connection could not be established." }
};

type Posted = { path: "grants" | "requests"; personId: string; tier: string; allData: boolean; scopeCount: number };

let token = "";

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  token = (await page.evaluate(() => window.localStorage.getItem("field_repo_token"))) ?? "";
  await context.close();
  expect(token).not.toBe("");
});

async function stubSharing(page: Page, options: { own?: number; dark?: boolean } = {}) {
  const posted: Posted[] = [];
  let meId = "";

  await page.addInitScript(
    ([value, theme]) => {
      window.localStorage.setItem("field_repo_token", value);
      if (theme) {
        window.localStorage.setItem(
          "field_repo_preferences",
          JSON.stringify({ theme: "dark", reducedMotion: false, largerText: false, highContrast: false })
        );
      }
    },
    [token, options.dark ? "dark" : ""] as const
  );

  await page.route(/\/api\/me$/, async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    meId = String(body.id);
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });

  await page.route(/\/api\/preferences\/me$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        theme: options.dark ? "dark" : "light",
        reducedMotion: false,
        largerText: false,
        highContrast: false
      })
    })
  );

  await page.route(/\/api\/users\/directory/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(PEOPLE) })
  );

  await page.route(/\/api\/data-access\/grants(\?|$)/, async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(GRANTS_FIXTURE) });
    }
    const body = request.postDataJSON() as { granteeId: string; tier: string; allData: boolean; scopeItems?: unknown[] };
    posted.push({
      path: "grants",
      personId: body.granteeId,
      tier: body.tier,
      allData: body.allData,
      scopeCount: (body.scopeItems ?? []).length
    });
    const outcome = OUTCOMES[body.granteeId] ?? { status: 201 };
    return route.fulfill({
      status: outcome.status,
      contentType: "application/json",
      body: JSON.stringify(outcome.detail ? { detail: outcome.detail } : grantRow({ granteeId: body.granteeId }))
    });
  });

  await page.route(/\/api\/data-access\/requests$/, async (route) => {
    const body = route.request().postDataJSON() as { ownerId: string; tier: string };
    posted.push({ path: "requests", personId: body.ownerId, tier: body.tier, allData: true, scopeCount: 0 });
    const outcome = OUTCOMES[body.ownerId] ?? { status: 201 };
    return route.fulfill({
      status: outcome.status,
      contentType: "application/json",
      body: JSON.stringify(outcome.detail ? { detail: outcome.detail } : grantRow({ ownerId: body.ownerId }))
    });
  });

  const emptyPage = JSON.stringify({ items: [], total: 0, page: 1, pageSize: 100, pages: 1 });
  await page.route(/\/api\/artisans\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: Array.from({ length: options.own ?? 0 }, (_, index) => ({
          id: `a-${index}`,
          name: `Fixture artisan ${index + 1}`,
          createdById: meId
        })),
        total: options.own ?? 0,
        page: 1,
        pageSize: 100,
        pages: 1
      })
    })
  );
  for (const path of [/\/api\/products\?/, /\/api\/tools\?/, /\/api\/workshops\?/, /\/api\/questionnaire\/interviews\?/]) {
    await page.route(path, (route) => route.fulfill({ status: 200, contentType: "application/json", body: emptyPage }));
  }

  return posted;
}

const grantSection = (page: Page) =>
  page.locator("section").filter({ has: page.getByRole("heading", { name: /grant access to your data/i }) });
const requestSection = (page: Page) =>
  page.locator("section").filter({ has: page.getByRole("heading", { name: /request access to researchers/i }) });

function field(scope: Locator, label: string): Locator {
  return scope
    .locator("label")
    .filter({ has: scope.page().locator("span.field-label", { hasText: new RegExp(`^${label}( \\*)?$`) }) })
    .locator("[data-searchable-select]");
}

const panel = (page: Page) => page.locator("[data-anchored-popover]");
const rows = (page: Page) => panel(page).getByRole("option");
const modal = (page: Page) => page.locator("[data-field-dialog]");
const sentence = (scope: Locator) => scope.locator("p").first();

async function openSharing(page: Page) {
  await page.goto("/sharing");
  await expect(page.getByRole("heading", { name: /grant access to your data/i })).toBeVisible();
}

async function openPicker(page: Page, scope: Locator, label: string, count = PEOPLE.length) {
  await expect(panel(page)).toHaveCount(0);
  const trigger = field(scope, label);
  await trigger.evaluate((node) => node.scrollIntoView({ block: "center" }));
  await trigger.click();
  await expect(panel(page)).toHaveCount(1);
  await expect(rows(page)).toHaveCount(count);
  await page.waitForFunction(
    () => {
      const node = document.querySelector("[data-anchored-popover]");
      if (!node) return false;
      const { top, left, width, height } = node.getBoundingClientRect();
      const store = window as unknown as { __panelBox?: string };
      const box = [top, left, width, height].map((value) => Math.round(value * 10) / 10).join(",");
      const settled = store.__panelBox === box;
      store.__panelBox = box;
      return settled;
    },
    null,
    { polling: "raf" }
  );
}

async function closePicker(page: Page) {
  await page.keyboard.press("Escape");
  await expect(panel(page)).toHaveCount(0);
}

async function pick(page: Page, names: string[]) {
  for (const name of names) await rows(page).filter({ hasText: name }).click();
}

test.describe("sharing multi-select", () => {
  test("one colleague still takes three clicks and no confirmation", async ({ page }) => {
    const posted = await stubSharing(page);
    await openSharing(page);

    await openPicker(page, grantSection(page), "Colleagues");
    await rows(page).filter({ hasText: "Farid Ali" }).click();
    await grantSection(page).getByRole("button", { name: /^grant$/i }).click();

    await expect(modal(page)).toHaveCount(0);
    await expect.poll(() => posted.length).toBe(1);
    expect(posted[0]).toMatchObject({ path: "grants", personId: "p-farid", tier: "DOWNLOAD", allData: true });
    await expect(page.getByText("Access granted to 1 colleague.")).toBeVisible();
  });

  test("the picker states what each person already holds", async ({ page }) => {
    await stubSharing(page);
    await openSharing(page);

    await openPicker(page, grantSection(page), "Colleagues");
    await expect(rows(page).filter({ hasText: "Alice Fernandes" })).toContainText("has EDIT, all data");
    await expect(rows(page).filter({ hasText: "Bhaskar Rao" })).toContainText("DOWNLOAD, all data · no change");
    await expect(rows(page).filter({ hasText: "Chandra Menon" })).toContainText("asked for COMMENT");
    await expect(rows(page).filter({ hasText: "Farid Ali" })).not.toContainText("—");
    await page.screenshot({ path: `${SHOTS}/multiselect-open-light.png` });
    await closePicker(page);

    await openPicker(page, requestSection(page), "Researchers");
    const devi = rows(page).filter({ hasText: "Devi Krishnan" });
    await expect(devi).toContainText("you already have COMMENT");
    await expect(devi).toHaveAttribute("aria-disabled", "true");
    await closePicker(page);

    await openPicker(page, grantSection(page), "Colleagues");
    await pick(page, ["Alice Fernandes", "Farid Ali"]);
    await closePicker(page);
    await expect(grantSection(page).getByText(/would LOWER access/)).toContainText("Alice Fernandes (EDIT, all data)");
    await page.screenshot({ path: `${SHOTS}/reduce-warning-light.png` });
  });

  test("typing filters the people, and Enter takes the top match", async ({ page }) => {
    await stubSharing(page);
    await openSharing(page);

    await openPicker(page, grantSection(page), "Colleagues");

    const filter = panel(page).getByRole("combobox");
    await filter.fill("ali");
    await expect(rows(page).first()).toContainText("Alice Fernandes");
    await filter.press("Enter");

    await expect(sentence(grantSection(page))).toHaveText("Grant DOWNLOAD on all your data to Alice Fernandes.");
    await closePicker(page);
  });

  test("the sentence names the tier, the scope and the number of people", async ({ page }) => {
    await stubSharing(page, { own: 3 });
    await openSharing(page);

    await openPicker(page, grantSection(page), "Colleagues");
    await panel(page).getByRole("button", { name: /select all 9/i }).click();
    await pick(page, ["Farid Ali", "Gita Sharma", "Hari Prasad", "Indu Bose"]);
    await closePicker(page);

    await openPicker(page, grantSection(page), "Tier", 3);
    await rows(page).filter({ hasText: "Edit (maximum)" }).click();

    await grantSection(page).getByRole("radio", { name: /only selected records/i }).check();
    const records = grantSection(page).getByRole("checkbox");
    await expect(records).toHaveCount(3);
    for (let index = 0; index < 3; index++) await records.nth(index).check();

    await expect(sentence(grantSection(page))).toHaveText("Grant EDIT on 3 selected records to 5 colleagues.");
  });

  test("three succeed, two fail, and retrying does not re-grant the three", async ({ page }) => {
    const posted = await stubSharing(page);
    await openSharing(page);

    await openPicker(page, grantSection(page), "Colleagues");
    await pick(page, ["Alice Fernandes", "Bhaskar Rao", "Chandra Menon", "Devi Krishnan", "Esha Roy"]);
    await closePicker(page);
    await expect(sentence(grantSection(page))).toHaveText("Grant DOWNLOAD on all your data to 5 colleagues.");

    await grantSection(page).getByRole("button", { name: /^grant$/i }).click();

    await expect(modal(page)).toContainText("Grant DOWNLOAD to 5 colleagues?");
    await expect(modal(page)).toContainText("Alice Fernandes");
    await page.screenshot({ path: `${SHOTS}/bulk-confirm-light.png` });
    await modal(page).getByRole("button", { name: /grant to 5/i }).click();

    await expect(page.getByText("3 of 5 granted.")).toBeVisible();
    await expect(page.getByText(/Devi Krishnan.*already have an active grant/)).toBeVisible();
    await expect(page.getByText(/Esha Roy.*Database connection/)).toBeVisible();
    await expect(page.getByText(/HTTP 500/)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/partial-failure-light.png`, fullPage: true });

    expect(posted.filter((entry) => entry.path === "grants")).toHaveLength(5);

    await expect(sentence(grantSection(page))).toHaveText("Grant DOWNLOAD on all your data to 2 colleagues.");

    posted.length = 0;
    await page.getByRole("button", { name: /retry the 2 that failed/i }).click();
    await expect.poll(() => posted.length, { timeout: 20_000 }).toBe(2);
    expect(posted.map((entry) => entry.personId).sort()).toEqual(["p-devi", "p-esha"]);
  });

  test("the same states read correctly in dark mode", async ({ page }) => {
    await stubSharing(page, { dark: true });
    await page.emulateMedia({ colorScheme: "dark" });
    await openSharing(page);
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

    await openPicker(page, grantSection(page), "Colleagues");
    await page.screenshot({ path: `${SHOTS}/multiselect-open-dark.png` });
    await pick(page, ["Alice Fernandes", "Bhaskar Rao", "Chandra Menon", "Devi Krishnan", "Esha Roy"]);
    await closePicker(page);
    await grantSection(page).getByRole("button", { name: /^grant$/i }).click();
    await page.screenshot({ path: `${SHOTS}/bulk-confirm-dark.png` });
    await modal(page).getByRole("button", { name: /grant to 5/i }).click();
    await expect(page.getByText("3 of 5 granted.")).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/partial-failure-dark.png`, fullPage: true });
  });

  test("one colleague at 360px still takes three clicks", async ({ page }) => {
    const posted = await stubSharing(page);
    await page.setViewportSize({ width: 360, height: 740 });
    await openSharing(page);

    await openPicker(page, grantSection(page), "Colleagues");
    await rows(page).filter({ hasText: "Farid Ali" }).click();
    await grantSection(page).getByRole("button", { name: /^grant$/i }).click();

    await expect.poll(() => posted.length, { timeout: 20_000 }).toBe(1);
    await page.screenshot({ path: `${SHOTS}/narrow-360.png`, fullPage: true });
    const scroll = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth
    }));
    expect(scroll.scrollWidth).toBeLessThanOrEqual(scroll.clientWidth + 1);
  });
});
