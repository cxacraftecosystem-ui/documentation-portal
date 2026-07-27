import { test, expect, type Page } from "@playwright/test";
import fs from "node:fs";

/**
 * Screenshot probe for the consolidated per-artisan questionnaire.
 *
 * The dev server that is running was built against the DEPLOYED API, which does not yet carry this
 * feature's route, so the one new endpoint is served from a local backend instead. That backend
 * reads the SAME production database, and every other request on the page still goes to the
 * deployed API — so what these shots capture is the real page, the real styles and real data, with
 * only the transport for the new endpoint redirected.
 */

const EMAIL = process.env.E2E_EMAIL ?? "admin@example.com";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const ARTISAN_ID = process.env.E2E_ARTISAN_ID ?? "cmqiz1mmb0005kb59dydk6g49";
const OUT = process.env.E2E_SHOT_DIR ?? "shots";
const LOCAL_API = process.env.E2E_LOCAL_API ?? "http://127.0.0.1:8000";

/**
 * Opt-in, matching `zz-map-scopes`.
 *
 * This file is a screenshot probe, not a regression test: it needs a local backend carrying the
 * consolidated route, which no clean checkout has. Left unguarded it FAILED the suite by default,
 * and a suite that is red on a fresh clone teaches everyone to ignore red. Point E2E_LOCAL_API at a
 * backend that serves /api/questionnaire/artisans/** to run it.
 */
const LOCAL_BACKEND_PROVIDED = Boolean(process.env.E2E_LOCAL_API);

/**
 * ThemeProvider applies, in order: the localStorage boot paint, then the SERVER row from
 * GET /preferences/me — which lands last and wins. Setting only localStorage therefore gets
 * overwritten with the account's stored (light) theme a moment after load, which is why the first
 * run of this probe produced two identical "light" shots. Both sources are set here.
 */
async function setTheme(page: Page, theme: "light" | "dark") {
  const preferences = { theme, reducedMotion: false, largerText: false, highContrast: false };
  await page.route("**/api/preferences/me", async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(preferences) });
  });
  await page.evaluate((value) => {
    window.localStorage.setItem("field_repo_preferences", JSON.stringify(value));
  }, preferences);
}

test("consolidated questionnaire screenshots", async ({ page, request }) => {
  test.skip(!LOCAL_BACKEND_PROVIDED, "E2E_LOCAL_API must point at a local backend carrying the consolidated route");
  fs.mkdirSync(OUT, { recursive: true });

  const login = await request.post(`${LOCAL_API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  const localToken = (await login.json()).accessToken as string;

  await page.route("**/api/questionnaire/artisans/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const upstream = await request.get(`${LOCAL_API}${path}`, {
      headers: { Authorization: `Bearer ${localToken}` }
    });
    await route.fulfill({
      status: upstream.status(),
      contentType: upstream.headers()["content-type"] ?? "application/json",
      body: await upstream.body()
    });
  });

  await page.goto("/login");
  await page.getByLabel(/email/i).first().fill(EMAIL);
  await page.getByLabel(/password/i).first().fill(PASSWORD);
  await page.getByRole("button", { name: /sign in|log in/i }).first().click();
  await page.waitForURL((url) => !url.pathname.includes("/login"), { timeout: 120_000 });

  for (const [label, width, height] of [
    ["1280", 1280, 900],
    ["390", 390, 844]
  ] as const) {
    for (const theme of ["light", "dark"] as const) {
      await page.setViewportSize({ width, height });
      await setTheme(page, theme);
      await page.goto(`/questionnaire/consolidated/${ARTISAN_ID}`);
      await page.reload();
      await expect(page.getByRole("heading", { level: 1, name: /Vikram|Consolidated/i })).toBeVisible({
        timeout: 120_000
      });
      await expect(page.getByText(/Sources \(/i)).toBeVisible({ timeout: 120_000 });
      await page.waitForTimeout(1500);
      await page.screenshot({ path: `${OUT}/consolidated-${label}-${theme}.png` });
      await page.screenshot({ path: `${OUT}/consolidated-${label}-${theme}-full.png`, fullPage: true });
    }
  }

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/questionnaire/consolidated");
  await page.waitForTimeout(3000);
  await page.screenshot({ path: `${OUT}/consolidated-index-1280.png` });
});

/**
 * FIXTURE, NOT LIVE DATA — and labelled as such wherever these shots are shown.
 *
 * The production database currently holds no typed questionnaire answers at all (every answer in it
 * is an audio clip), so the divergent-answer case cannot be photographed from real rows however long
 * one looks. It is also the case the whole view exists for, so it is rendered here from a fixture
 * shaped exactly like the endpoint's response.
 */
test("conflict rendering, from a fixture", async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });

  const provenance = (id: string, title: string, date: string, count: number, co: string[]) => ({
    interviewId: id,
    interviewTitle: title,
    interviewDate: date,
    dateBasis: "interviewDate",
    interviewStatus: "APPROVED",
    workshopTitle: "Almora bamboo workshop",
    artisanCount: count,
    coParticipants: co,
    attribution: count > 1 ? "GROUP" : "SOLE"
  });

  const fixture = {
    artisan: { id: "fix", name: "Vikram Lal", craftName: "Cane and Bamboo", place: "Basar, Almora" },
    generatedAt: new Date().toISOString(),
    // NOTE the different shape: `interviews[]` is the SOURCE list (id/title/date), while the block
    // copied onto each answer is the provenance shape (interviewId/interviewTitle/interviewDate).
    interviews: [
      {
        id: "iv-b",
        title: "Ankit Shah, Heera Singh, Manoj Ram and Vikram Lal",
        date: "2026-06-22T09:00:00Z",
        dateBasis: "interviewDate",
        status: "APPROVED",
        workshopTitle: "Almora bamboo workshop",
        artisanCount: 4,
        coParticipants: ["Ankit Shah", "Heera Singh", "Manoj Ram"],
        attribution: "GROUP"
      },
      {
        id: "iv-a",
        title: "Vikram Lal",
        date: "2026-06-21T09:00:00Z",
        dateBasis: "interviewDate",
        status: "APPROVED",
        workshopTitle: "Almora bamboo workshop",
        artisanCount: 1,
        coParticipants: [],
        attribution: "SOLE"
      }
    ],
    sections: [
      {
        id: "sec-a",
        code: "A",
        title: "ORIGIN, HISTORY, PLACE AND PERSONAL JOURNEY",
        sortOrder: 1,
        questions: [
          {
            id: "q1",
            prompt: "How did you come to learn this craft?",
            sortOrder: 1,
            conflict: true,
            answers: [
              {
                kind: "TYPED",
                sourceId: "r2",
                answerText:
                  "I picked it up in the workshop here, after school. Everyone in the group learned together — we were taught by the same master.",
                recordedByName: "D. Nath",
                ...provenance("iv-b", "Ankit Shah, Heera Singh, Manoj Ram and Vikram Lal", "2026-06-22T09:00:00Z", 4, [
                  "Ankit Shah",
                  "Heera Singh",
                  "Manoj Ram"
                ])
              },
              {
                kind: "TYPED",
                sourceId: "r1",
                answerText: "My father taught me. I was eleven, and I made my first tokri that winter.",
                notes: "Answered without hesitation; repeated the age twice.",
                recordedByName: "D. Nath",
                ...provenance("iv-a", "Vikram Lal", "2026-06-21T09:00:00Z", 1, [])
              }
            ]
          },
          {
            id: "q2",
            prompt: "Who else in your family practises it?",
            sortOrder: 2,
            conflict: false,
            answers: [
              {
                kind: "TYPED",
                sourceId: "r3",
                answerText: "My younger brother, and my son helps at the weekends.",
                recordedByName: "D. Nath",
                ...provenance("iv-a", "Vikram Lal", "2026-06-21T09:00:00Z", 1, [])
              }
            ]
          }
        ],
        recordings: []
      }
    ],
    unfiled: [],
    summary: {
      interviewCount: 2,
      groupSittingCount: 1,
      soleSittingCount: 1,
      answeredQuestionCount: 2,
      typedAnswerCount: 3,
      recordedAnswerCount: 0,
      unfiledRecordingCount: 0,
      conflictCount: 1
    },
    meta: { queryCount: 7 }
  };

  await page.route("**/api/questionnaire/artisans/**/consolidated", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixture) });
  });

  await page.goto("/login");
  await page.getByLabel(/email/i).first().fill(EMAIL);
  await page.getByLabel(/password/i).first().fill(PASSWORD);
  await page.getByRole("button", { name: /sign in|log in/i }).first().click();
  await page.waitForURL((url) => !url.pathname.includes("/login"), { timeout: 120_000 });

  for (const [label, width, height] of [
    ["1280", 1280, 900],
    ["390", 390, 844]
  ] as const) {
    for (const theme of ["light", "dark"] as const) {
      await page.setViewportSize({ width, height });
      await setTheme(page, theme);
      await page.goto("/questionnaire/consolidated/fix");
      await page.reload();
      await expect(page.getByText(/Answered differently in 2 interviews/i)).toBeVisible({ timeout: 120_000 });
      await page.waitForTimeout(800);
      await page.screenshot({ path: `${OUT}/conflict-${label}-${theme}.png` });
      await page.screenshot({ path: `${OUT}/conflict-${label}-${theme}-full.png`, fullPage: true });
    }
  }
});
