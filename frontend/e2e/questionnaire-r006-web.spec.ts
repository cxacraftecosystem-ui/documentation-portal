import { devices, expect, test, type Page, type Route } from "@playwright/test";

import { QuestionnaireFake } from "./support/qv2Fake";

/**
 * 0.0.6 ON THE SHARED QUESTIONNAIRE SCREENS, IN A REAL BROWSER, BOTH PORTALS (this file is the same
 * in designer-portal and documentation-portal; each portal's `QuestionnaireFake` answers the API).
 *
 * On a desktop, a tablet and a phone: a take's transcript preview reads as speaker-labelled text
 * (mapped names, no asterisks, no "Transcript" prefix, a clean clamp); on workshop coverage an
 * artisan's truncated name shows whole on hover or focus, and a dot opens that artisan × section's
 * recording — one directly, several through a short chooser, none not at all.
 */

const VIEWPORTS = [
  { name: "desktop", use: { viewport: { width: 1280, height: 900 } } },
  { name: "tablet", use: { viewport: { width: 820, height: 1180 }, hasTouch: true } },
  {
    name: "phone",
    use: (() => {
      const { defaultBrowserType: _ignored, ...pixel } = devices["Pixel 7"];
      void _ignored;
      return pixel;
    })()
  }
] as const;

const PREVIEW = "Transcript **Speaker 1:** Wow! That is a lovely block.\n**Speaker 2:** Yes, my uncle carved it in Pethapur.\n\n**Speaker 1:** How long did it take?\n**Speaker 2:** Three weeks.";

type Json = Record<string, unknown>;

const LONG_NAME = "Meena Devi Kumari Sharma";

function rec(recordingId: string, capturedAt: string, researcher: string, durationMs: number): Json {
  return { recordingId, mediaFileId: `m-${recordingId}`, sessionId: "s-1", capturedAt, durationMs, recordedBy: { id: `u-${researcher}`, name: researcher }, needsDecision: false };
}

function cell(artisanId: string, state: string, takes: number, source: string | null, recordings: Json[]): Json {
  return { artisanId, subjectKey: `A:${artisanId}`, state, takes, covered: takes ? 1 : 0, total: 1, needsDecision: false, override: null, source, addRecording: source === "TYPED", recordings };
}

/** The grid as contract v2 sends it: each cell lists the takes behind it, oldest first. */
function coverageGrid(): Json {
  return {
    questionnaireId: "f1",
    artisans: [
      { artisanId: "a1", name: "Giriraj Prasad Chhipa" },
      { artisanId: "a2", name: LONG_NAME }
    ],
    respondents: [],
    sections: [
      {
        sectionId: "s1",
        code: "A",
        title: "Tools",
        cells: [cell("a1", "RECORDED", 1, "RECORDED", [rec("rec-1", "2026-10-22T05:10:00Z", "Nandini Rao", 92000)]), cell("a2", "NOT_RECORDED", 0, null, [])]
      },
      {
        sectionId: "s2",
        code: "B",
        title: "Dyes",
        cells: [
          cell("a1", "RECORDED", 2, "RECORDED", [rec("rec-1", "2026-10-22T05:10:00Z", "Nandini Rao", 92000), rec("rec-2", "2026-10-25T05:10:00Z", "Priya Shah", 185000)]),
          cell("a2", "PARTIAL", 0, "TYPED", [])
        ]
      }
    ]
  };
}

async function openCoverage(page: Page) {
  const fake = new QuestionnaireFake();
  fake.grid = coverageGrid;
  fake.recordings.push({ ...fake.recordings[0], id: "rec-2", mediaFileId: "m-rec-2", capturedAt: "2026-10-25T05:10:00Z" });
  await fake.install(page);
  // The cells list their takes, so a dot never needs the coverage check.
  const checks: unknown[] = [];
  await page.route(
    (url) => url.pathname === "/api/questionnaire/coverage/check",
    async (route: Route) => {
      checks.push(route.request().postDataJSON());
      await route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
    }
  );
  await page.goto("/questionnaire/coverage");
  const grid = page.locator('[data-coverage-grid="recordings"]');
  await expect(grid).toBeVisible({ timeout: 30_000 });
  return { grid, checks };
}

for (const viewport of VIEWPORTS) {
  test.describe(viewport.name, () => {
    test.use(viewport.use);

    test("@qf:E9 a take's transcript preview reads as speaker-labelled text, with mapped names and no Markdown", async ({ page }) => {
      const fake = new QuestionnaireFake();
      // The server's preview (mapped names, colour keys) on one take; only the stored Markdown on another.
      fake.recordings[0].transcript = {
        status: "COMPLETED",
        paused: false,
        text: PREVIEW,
        language: "hi",
        preview: {
          text: "Nandini: Wow! That is a lovely block. Giriraj Prasad Chhipa: Yes, my uncle carved it in Pethapur.",
          lines: [
            { speaker: "Nandini", speakerKey: "speaker_0", colorKey: "s0", text: "Wow! That is a lovely block." },
            { speaker: "Giriraj Prasad Chhipa", speakerKey: "speaker_1", colorKey: "s1", text: "Yes, my uncle carved it in Pethapur." }
          ]
        }
      };
      fake.recordings.push({ ...fake.recordings[0], id: "rec-md", mediaFileId: "m-rec-md", transcript: { status: "COMPLETED", paused: false, text: PREVIEW, language: "hi" } });
      await fake.install(page);
      await page.goto("/questionnaire/recordings");
      await expect(page.locator("[data-recording-row] [data-transcript-preview]")).toHaveCount(2, { timeout: 30_000 });
      const server = page.locator('[data-recording-row="rec-1"] [data-transcript-preview]');
      const local = page.locator('[data-recording-row="rec-md"] [data-transcript-preview]');
      await expect(server.locator("[data-preview-speaker]")).toHaveText(["Nandini", "Giriraj Prasad Chhipa"]);
      await expect(local.locator("[data-preview-speaker]")).toHaveText(["Speaker 1", "Speaker 2", "Speaker 1"]);
      await expect(local.locator("[data-preview-text]").first()).toHaveText("Wow! That is a lovely block.");
      for (const preview of [server, local]) {
        const text = await preview.innerText();
        expect(text).not.toMatch(/[*`]/);
        expect(text.trim().startsWith("Transcript")).toBe(false);
        // Labels are styled as labels, each speaker in their own colour; the block clamps cleanly.
        const speakers = preview.locator("[data-preview-speaker]");
        expect(await speakers.nth(0).evaluate((element) => getComputedStyle(element).fontWeight)).toBe("600");
        const colour = (index: number) => speakers.nth(index).evaluate((element) => getComputedStyle(element).color);
        expect(await colour(0)).not.toBe(await colour(1));
        expect(await preview.evaluate((element) => getComputedStyle(element).webkitLineClamp)).toBe("3");
      }
    });

    test("@qf:E7 an artisan's truncated name shows whole and larger on hover and on focus", async ({ page }) => {
      const { grid } = await openCoverage(page);
      const name = grid.locator('[data-artisan-column="a2"] [data-artisan-name]');
      const short = name.locator("span").first();
      expect(await short.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
      const full = name.locator("[data-artisan-full-name]");
      await expect(full).toBeHidden();
      // A pointer that hovers shows it on hover; a touch screen has no hover, so a tap (focus) does.
      if (viewport.name === "desktop") await name.hover();
      else await name.tap();
      await expect(full).toBeVisible();
      await expect(full).toHaveText(LONG_NAME);
      const size = (locator: ReturnType<Page["locator"]>) => locator.evaluate((element) => parseFloat(getComputedStyle(element).fontSize));
      expect(await size(full)).toBeGreaterThan(await size(short));
      if (viewport.name === "desktop") await page.mouse.move(1, 1);
      else await name.evaluate((element: HTMLElement) => element.blur());
      await expect(full).toBeHidden();
      await name.focus();
      await expect(full).toBeVisible();
      await expect(name).toHaveAttribute("aria-label", LONG_NAME);
    });

    test("@qf:E8 a dot opens its recording, several open a chooser, and an empty cell is not a button", async ({ page }) => {
      const { grid, checks } = await openCoverage(page);

      // None: no button, nothing to press, nowhere to go.
      await expect(grid.getByRole("button", { name: `A ${LONG_NAME}: Not recorded` })).toHaveCount(0);
      await expect(grid.getByRole("button", { name: `B ${LONG_NAME}: Typed · Add a recording?` })).toHaveCount(0);
      const empty = grid.locator('[data-coverage-cell="s1:a2"]');
      expect(await empty.evaluate((element) => element.tagName)).toBe("SPAN");
      await empty.click();
      await expect(page.locator("[data-recording-chooser]")).toHaveCount(0);
      await expect(page).toHaveURL(/\/questionnaire\/coverage$/);

      // Several: a chooser, oldest first, by date, researcher and duration.
      await grid.getByRole("button", { name: "B Giriraj Prasad Chhipa: Recorded" }).click();
      const chooser = page.locator("[data-recording-chooser]");
      await expect(chooser).toBeVisible();
      const choices = chooser.locator("[data-recording-choice]");
      await expect(choices).toHaveCount(2);
      await expect(choices.nth(0)).toHaveAttribute("data-recording-choice", "rec-1");
      await expect(choices.nth(0)).toContainText("Nandini Rao");
      await expect(choices.nth(1)).toContainText("Priya Shah");
      await choices.nth(1).click();
      await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-1\/view\?recording=rec-2$/);
      await expect(page.locator('[data-recording="rec-2"]')).toHaveAttribute("data-focused", "true");

      // One: straight to it.
      await page.goBack();
      const again = page.locator('[data-coverage-grid="recordings"]');
      await expect(again).toBeVisible({ timeout: 30_000 });
      await again.getByRole("button", { name: "A Giriraj Prasad Chhipa: Recorded" }).click();
      await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-1\/view\?recording=rec-1$/);
      await expect(page.locator('[data-recording="rec-1"]')).toHaveAttribute("data-focused", "true");
      expect(checks).toHaveLength(0);
    });
  });
}
