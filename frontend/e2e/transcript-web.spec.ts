import { devices, expect, test, type Page } from "@playwright/test";

import { FIXTURES, MEDIA_ID, TranscriptFake } from "./support/transcriptFake";

/**
 * THE SHARED TRANSCRIPT VIEWER IN A REAL BROWSER, both portals (`shared/transcript-web.sha256`).
 *
 * A production build (`next build && next start`, the `transcript-web` job in checks.yml) serves the
 * real record page for one recording; every `/api` call is answered by `support/transcriptFake.ts`
 * from the contract's own example. Each block runs on a desktop, a tablet and a phone viewport:
 * every copy mode lands on the clipboard exactly as `fixtures.copy` says (and through the selection
 * fallback when the Clipboard API is missing), hostile markdown renders inert, and karaoke seeks.
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

test.use({ launchOptions: { args: ["--autoplay-policy=no-user-gesture-required"] } });

async function open(page: Page, fake: TranscriptFake) {
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await fake.install(page);
  await page.goto(`/media/${MEDIA_ID}`);
  const viewer = page.locator(`[data-transcript-viewer="${MEDIA_ID}"]`);
  await expect(viewer).toBeVisible({ timeout: 30_000 });
  return viewer;
}

const clipboard = (page: Page) => page.evaluate(() => navigator.clipboard.readText());

async function pick(page: Page, menuButton: ReturnType<Page["locator"]>, item: string) {
  await menuButton.click();
  await page.locator(`[role="menu"] [data-menu-item="${item}"]`).click();
}

for (const viewport of VIEWPORTS) {
  test.describe(viewport.name, () => {
    test.use(viewport.use);

    test("the header shows the facts, the status and the model badges", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      await expect(viewer.locator("[data-transcript-status]")).toHaveText("Ready");
      await expect(viewer.locator("[data-model-badges]")).toContainText("Scribe v2");
      await expect(viewer.locator("[data-model-badges]")).toContainText("Gemini 3.8 Flash");
      await expect(viewer.locator("[data-transcript-facts]")).toContainText("Hindi, English");
      await expect(viewer.locator("[data-transcript-facts]")).toContainText("25:24");
      await expect(viewer.locator("[data-turn-id]")).toHaveCount(2);
      await expect(viewer.locator('[data-turn-id="turn_a"] [data-speaker]')).toHaveText("Nandini (researcher)");
      // The unsure stretch is underlined and says so.
      await expect(viewer.locator('[data-turn-id="turn_b"] [data-low-confidence="true"]')).toHaveText("dabu");
    });

    test("Copy all in each track is the contract's text, and says Copied", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      const button = viewer.getByTestId("copy-all");
      for (const track of ["original", "english", "both"] as const) {
        await pick(page, button, track);
        await expect(viewer.locator('[data-copy-status="ok"]')).toHaveText("Copied");
        expect(await clipboard(page)).toBe(FIXTURES.copy[`all_${track}`]);
      }
    });

    test("each turn copies original, English or both", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      const button = viewer.getByTestId("copy-turn-turn_a");
      await expect(button).toHaveAttribute("aria-label", "Copy Nandini (researcher) at 00:00:04");
      await pick(page, button, "original");
      expect(await clipboard(page)).toBe(FIXTURES.copy.single_original_turn_a);
      await pick(page, button, "english");
      expect(await clipboard(page)).toBe(FIXTURES.copy.single_english_turn_a);
      await pick(page, button, "both");
      expect(await clipboard(page)).toBe(FIXTURES.copy.single_both_turn_a);
    });

    test("Select turns, then Copy selected", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      await viewer.getByTestId("select-turns").click();
      await viewer.locator('[data-select-turn="turn_b"]').check();
      await expect(viewer.locator("[data-selected-count]")).toHaveText("1 selected");
      await pick(page, viewer.getByTestId("copy-selected"), "both");
      await expect(viewer.locator('[data-copy-status="ok"]')).toBeVisible();
      expect(await clipboard(page)).toBe(FIXTURES.copy.selected_both_turn_b);
    });

    test("without the Clipboard API the copy still lands, by selection", async ({ page }) => {
      await page.addInitScript(() => {
        Object.defineProperty(Navigator.prototype, "clipboard", { configurable: true, get: () => undefined });
      });
      const viewer = await open(page, new TranscriptFake());
      // Read the clipboard back with a fresh page in the same context, where the API still exists.
      const reader = await page.context().newPage();
      await pick(page, viewer.getByTestId("copy-turn-turn_a"), "english");
      await expect(viewer.locator('[data-copy-status="ok"]')).toHaveText("Copied");
      await reader.goto("about:blank");
      expect(await reader.evaluate(() => navigator.clipboard.readText())).toBe(FIXTURES.copy.single_english_turn_a);
      await reader.close();
    });

    test("the copy controls are reachable and work from the keyboard", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      const button = viewer.getByTestId("copy-turn-turn_b");
      await button.focus();
      await page.keyboard.press("Enter");
      const menu = page.locator('[role="menu"]');
      await expect(menu.locator('[data-menu-item="original"]')).toBeFocused();
      await page.keyboard.press("ArrowDown");
      await expect(menu.locator('[data-menu-item="english"]')).toBeFocused();
      await page.keyboard.press("Enter");
      await expect(button).toBeFocused();
      expect(await clipboard(page)).toBe("We mix clay and gum for dabu (mud-resist printing).");
      await button.focus();
      await page.keyboard.press("Enter");
      await page.keyboard.press("Escape");
      await expect(menu).toHaveCount(0);
      await expect(button).toBeFocused();
    });

    test("hostile markdown renders inert", async ({ page }) => {
      const fake = new TranscriptFake();
      fake.hostile = true;
      const viewer = await open(page, fake);
      const summary = viewer.locator("[data-transcript-summary]");
      await expect(summary).toContainText("Safe bold line.");
      await viewer.getByTestId("ask-open").click();
      await viewer.locator("[data-ask-panel] input").fill("What is in the paste?");
      await viewer.locator("[data-ask-panel] button[type=submit]").click();
      const answer = viewer.locator("[data-ask-answer]");
      await expect(answer).toContainText("Safe bold line.");
      for (const scope of [summary, answer]) {
        await expect(scope.locator("script, img, iframe, [onerror]")).toHaveCount(0);
        await expect(scope.locator('a[href^="javascript"]')).toHaveCount(0);
        await expect(scope.getByText("click me")).toBeVisible();
        const ok = scope.locator('a[href="https://example.org/"]');
        await expect(ok).toHaveAttribute("rel", "noopener noreferrer nofollow");
      }
      await scope_click_is_harmless(page, answer);
      expect(await page.evaluate(() => (window as unknown as { __pwned?: string }).__pwned ?? null)).toBeNull();
    });

    test("karaoke: tapping a word plays from it and lights it, with its English turn", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      const audio = viewer.locator("audio[data-transcript-audio]");
      await viewer.locator('[data-turn-id="turn_a"] [data-word="3"]').click();
      await expect.poll(() => audio.evaluate((element: HTMLAudioElement) => element.currentTime), { timeout: 10_000 }).toBeGreaterThanOrEqual(5.1);
      await expect(viewer.locator('[data-turn-id="turn_a"]')).toHaveAttribute("data-active", "true");
      await expect(viewer.locator('[data-turn-id="turn_a"] [data-current="true"]').first()).toBeVisible();
      await viewer.locator('[data-tab="side"]').click();
      await expect(viewer.locator('[data-turn-id="turn_a"] [data-active-english="true"]')).toBeVisible();
      // The clock on the next turn seeks there.
      await viewer.locator('[data-turn-id="turn_b"] [data-seek-ms="9000"]').click();
      await expect.poll(() => audio.evaluate((element: HTMLAudioElement) => element.currentTime), { timeout: 10_000 }).toBeGreaterThanOrEqual(9);
      await expect(viewer.locator('[data-turn-id="turn_b"]')).toHaveAttribute("data-active", "true");
      await audio.evaluate((element: HTMLAudioElement) => element.pause());
    });

    test("Ask this interview cites the turns as chips that seek, or says it isn't covered", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      await viewer.getByTestId("ask-open").click();
      const panel = viewer.locator("[data-ask-panel]");
      await panel.locator("input").fill("How is the paste made?");
      await panel.locator("button[type=submit]").click();
      await expect(panel.locator("[data-ask-answer]")).toContainText("He mixes clay and gum");
      await panel.locator('[data-citations] [data-seek-ms="9000"]').click();
      await expect
        .poll(() => viewer.locator("audio[data-transcript-audio]").evaluate((element: HTMLAudioElement) => element.currentTime), { timeout: 10_000 })
        .toBeGreaterThanOrEqual(9);
      await viewer.locator("audio[data-transcript-audio]").evaluate((element: HTMLAudioElement) => element.pause());
      await panel.locator("input").fill("What was the weather?");
      await panel.locator("button[type=submit]").click();
      await expect(panel.locator('[data-ask-answer][data-covered="false"]')).toContainText("The interview doesn't cover this.");
      await expect(panel.locator("[data-citations]")).toHaveCount(0);
    });
  });
}

test.describe("desktop actions", () => {
  test.use({ viewport: { width: 1280, height: 900 } });

  test("speaker names: one tap applies to every turn", async ({ page }) => {
    const fake = new TranscriptFake();
    const viewer = await open(page, fake);
    await viewer.getByTestId("speakers-open").click();
    await viewer.locator('[data-speaker-row="speaker_1"] [data-candidate="Meena Devi"]').click();
    await expect(viewer.locator('[data-turn-id="turn_b"] [data-speaker]')).toHaveText("Meena Devi");
    const put = fake.calledWith("PUT", `/transcripts/${MEDIA_ID}/speakers`)[0].body as { baseRevision: number; mappings: unknown[] };
    expect(put).toEqual({ baseRevision: 3, mappings: [{ speakerKey: "speaker_1", name: "Meena Devi", role: "ARTISAN", personId: "a_2" }] });
  });

  test("editing a turn marks it Edited and offers Re-translate this turn", async ({ page }) => {
    const fake = new TranscriptFake();
    const viewer = await open(page, fake);
    await viewer.getByRole("button", { name: "Edit Giriraj at 00:00:09" }).click();
    const editor = viewer.locator('[data-turn-id="turn_b"] [data-turn-editor]');
    await editor.locator("textarea").first().fill("हम dabu के लिए मिट्टी और गोंद मिलाते हैं।");
    await editor.getByRole("button", { name: "Save" }).click();
    const row = viewer.locator('[data-turn-id="turn_b"]');
    await expect(row).toContainText("Edited");
    expect(fake.calledWith("PATCH", `/transcripts/${MEDIA_ID}/turns/turn_b`)[0].body).toEqual({ baseRevision: 1, text: "हम dabu के लिए मिट्टी और गोंद मिलाते हैं।" });
    await viewer.locator('[data-tab="english"]').click();
    await expect(row).toContainText("Out of date");
    await row.locator('[data-retranslate="turn_b"]').click();
    await expect(row).toContainText("Re-translated:");
  });

  test("a quote card previews and downloads", async ({ page }) => {
    const viewer = await open(page, new TranscriptFake());
    await viewer.getByTestId("select-turns").click();
    await viewer.locator('[data-select-turn="turn_b"]').check();
    await viewer.getByTestId("quote-open").click();
    const card = viewer.locator("[data-quote-card]");
    await expect(card.locator("[data-quote-image]")).toBeVisible();
    const download = page.waitForEvent("download");
    await card.getByRole("button", { name: "Download image" }).click();
    expect((await download).suggestedFilename()).toMatch(/_Interview_.*Quote-Card\.png$/);
  });

  test("the Markdown download carries the server's name", async ({ page }) => {
    const viewer = await open(page, new TranscriptFake());
    const download = page.waitForEvent("download");
    await pick(page, viewer.getByTestId("download"), "md");
    expect((await download).suggestedFilename()).toBe("DesignWorkshop_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Transcript.md");
  });

  test("with the pipeline off the stored transcript shows as before", async ({ page }) => {
    const fake = new TranscriptFake();
    fake.off = true;
    await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
    await fake.install(page);
    await page.goto(`/media/${MEDIA_ID}`);
    await expect(page.getByText("Legacy transcript text.")).toBeVisible({ timeout: 30_000 });
    await expect(page.locator("[data-transcript-viewer]")).toHaveCount(0);
  });
});

/** A click on a neutralised link must not run anything either. */
async function scope_click_is_harmless(page: Page, scope: ReturnType<Page["locator"]>) {
  await scope.getByText("click me").click();
  await page.waitForTimeout(200);
}
