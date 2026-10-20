import { expect, test, type Page } from "@playwright/test";

/**
 * A content admin sees Delete in admin view (REQUIREMENTS_2026-10-20 §5.4), in a browser, against a
 * running stack. This portal has no Ministry Admin tier: its content admins are ADMIN and
 * MASTER_ADMIN, so E2E_EMAIL must be one of them.
 *
 * The control is the comment's Delete on a record page (`CollabPanel`, `user && adminMode`). The
 * spec posts a comment on the first artisan through the API, opens that artisan, and checks the
 * Delete is there with admin view on and gone with it off. With E2E_RESEARCHER_EMAIL set it also
 * checks a researcher sees none.
 *
 *   E2E_EMAIL=… E2E_PASSWORD=… npx playwright test e2e/admin-comment-delete.spec.ts
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const RESEARCHER_EMAIL = process.env.E2E_RESEARCHER_EMAIL ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function token(page: Page, email: string): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email, password: PASSWORD } });
  expect(res.ok(), `sign in as ${email}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

/** Signed in through the login form, with admin view set as the toggle sets it. */
async function signIn(page: Page, email: string, adminView: boolean) {
  await page.goto("/login");
  await page.waitForLoadState("networkidle").catch(() => {});
  const emailBox = page.getByPlaceholder("Enter your email");
  const passwordBox = page.getByPlaceholder("Enter your password");
  await expect(async () => {
    await emailBox.fill(email);
    await passwordBox.fill(PASSWORD);
    expect(await emailBox.inputValue()).toBe(email);
  }).toPass({ timeout: 30_000 });
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  await page.evaluate((on) => {
    const stored = window.localStorage.getItem("field_repo_token") ?? "";
    const sub = String(JSON.parse(atob(stored.split(".")[1] ?? "")).sub ?? "");
    window.localStorage.setItem(`field_repo_admin_view:${sub}`, on ? "on" : "off");
  }, adminView);
}

async function commentedArtisan(page: Page, stamp: number): Promise<{ id: string; body: string }> {
  const auth = { Authorization: `Bearer ${await token(page, EMAIL)}` };
  const list = await page.request.get(`${API}/api/artisans?pageSize=1`, { headers: auth });
  expect(list.ok()).toBeTruthy();
  const artisan = ((await list.json()).items ?? [])[0] as { id: string } | undefined;
  test.skip(!artisan, "No artisan to comment on in this database.");
  const body = `E2E delete check ${stamp}`;
  const posted = await page.request.post(`${API}/api/data-access/comments`, {
    headers: auth,
    data: { recordType: "artisan", recordId: artisan!.id, body }
  });
  expect(posted.status(), await posted.text()).toBe(201);
  return { id: artisan!.id, body };
}

function commentRow(page: Page, body: string) {
  return page.locator("li").filter({ hasText: body }).first();
}

test("an admin in admin view sees Delete on a comment, and not with admin view off", async ({ page }) => {
  const { id, body } = await commentedArtisan(page, Date.now());

  await signIn(page, EMAIL, true);
  await page.goto(`/artisans/${id}`);
  await expect(commentRow(page, body).getByRole("button", { name: "Delete" })).toBeVisible();

  await signIn(page, EMAIL, false);
  await page.goto(`/artisans/${id}`);
  await expect(commentRow(page, body)).toBeVisible();
  await expect(commentRow(page, body).getByRole("button", { name: "Delete" })).toHaveCount(0);
});

test("a researcher sees no Delete on the same comment", async ({ page }) => {
  test.skip(!RESEARCHER_EMAIL, "Set E2E_RESEARCHER_EMAIL to check a researcher.");
  const { id, body } = await commentedArtisan(page, Date.now());
  await signIn(page, RESEARCHER_EMAIL, true);
  await page.goto(`/artisans/${id}`);
  await expect(commentRow(page, body)).toBeVisible();
  await expect(commentRow(page, body).getByRole("button", { name: "Delete" })).toHaveCount(0);
});
