import { devices, expect, test, type Page } from "@playwright/test";

import type { Transcript } from "../lib/transcript/contract";
import { FIXTURES, MEDIA_ID, TranscriptFake } from "./support/transcriptFake";

/**
 * THE SHARED TRANSCRIPT VIEWER IN A REAL BROWSER, both portals (`shared/transcript-web.sha256`).
 *
 * A production build (`next build && next start`, the `transcript-web` job in checks.yml) serves the
 * real record page for one recording; every `/api` call is answered by `support/transcriptFake.ts`
 * from the contract's own example. Each block runs on a desktop, a tablet and a phone viewport:
 * every copy mode lands on the clipboard exactly as `fixtures.copy` says (and through the selection
 * fallback when the Clipboard API is missing), hostile markdown renders inert, and karaoke seeks.
 * 0.0.6 adds reading mode, the all-marks button, the mark's bubble (hover and keyboard), marks on
 * the bar's centre, one colour per speaker, and auto-follow with its "Follow along" pill.
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
      // A page of the app (a secure context with the API), not about:blank, which has no clipboard.
      const reader = await page.context().newPage();
      await reader.goto("/login");
      await reader.evaluate(() => navigator.clipboard.writeText("sentinel"));
      await page.bringToFront();
      await pick(page, viewer.getByTestId("copy-turn-turn_a"), "english");
      await expect(viewer.locator('[data-copy-status="ok"]')).toHaveText("Copied");
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
      await expect(menu.locator('[data-menu-item="original"]')).toBeFocused();
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
      await clickIsHarmless(page, answer);
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

/** A transcript long enough to scroll: `count` turns, alternating two speakers, across 28 s of audio. */
function longTranscript(fake: TranscriptFake, count = 40) {
  const view = fake.view as unknown as Transcript;
  const [first, second] = view.turns;
  const step = Math.floor(28000 / count);
  view.turns = Array.from({ length: count }, (_, index) => {
    const base = index % 2 === 0 ? first : second;
    return {
      ...base,
      id: `turn_${index}`,
      index,
      startMs: index * step,
      endMs: index * step + step - 50,
      words: [],
      lowConfidence: [],
      text: `${base.text} (${index + 1}) — a longer line so each turn takes up real room on a phone and on a laptop alike.`,
      translation: { ...base.translation, text: `${base.translation.text} (${index + 1})` }
    };
  });
}

const activeInView = (page: Page) =>
  page.evaluate(() => {
    const element = document.querySelector('[data-follow-turn][data-active="true"]');
    if (!element) return false;
    const box = element.getBoundingClientRect();
    return box.bottom > 0 && box.top < window.innerHeight;
  });

const centreY = async (locator: ReturnType<Page["locator"]>) => {
  const box = await locator.boundingBox();
  if (!box) throw new Error("not laid out");
  return box.y + box.height / 2;
};

for (const viewport of VIEWPORTS) {
  test.describe(`${viewport.name} (0.0.6)`, () => {
    test.use(viewport.use);

    test("reading mode is one flowing transcript in Original, English and Both, with no timestamps", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      await viewer.locator('[data-view-mode="reading"]').click();
      await expect(viewer.locator('[data-view-mode="reading"]')).toHaveAttribute("aria-pressed", "true");
      const reading = viewer.locator("[data-reading]");
      await expect(reading).toHaveAttribute("data-reading", "original");
      await expect(reading).toContainText("आप ये रंग कैसे बनाते हैं?");
      await expect(reading).not.toContainText("How do you make these colours?");
      await expect(reading.locator("[data-reading-block] [data-speaker]")).toHaveText(["Nandini (researcher)", "Giriraj"]);
      // No clock anywhere in the view, and no turn cards with their tools.
      expect(await reading.innerText()).not.toMatch(/\d{1,2}:\d{2}(:\d{2})?/);
      await expect(reading.locator("[data-seek-ms]")).toHaveCount(0);
      await expect(viewer.locator("[data-turn-id]")).toHaveCount(0);

      await viewer.locator('[data-tab="english"]').click();
      await expect(reading).toHaveAttribute("data-reading", "english");
      await expect(reading).toContainText("We mix clay and gum for dabu (mud-resist printing).");
      await expect(reading).not.toContainText("हम dabu");

      const both = viewer.locator('[data-tab="side"]');
      await expect(both).toHaveText("Both");
      await both.click();
      await expect(reading).toHaveAttribute("data-reading", "both");
      await expect(reading.locator("[data-reading-original]").first()).toContainText("आप ये रंग");
      await expect(reading.locator("[data-reading-english]").first()).toContainText("How do you make these colours?");
      expect(await reading.innerText()).not.toMatch(/\d{1,2}:\d{2}(:\d{2})?/);

      // The view copies as it reads: no timestamps.
      await viewer.getByTestId("copy-view").click();
      await expect(viewer.locator('[data-copy-status="ok"]')).toHaveText("Copied");
      expect(await clipboard(page)).toBe(FIXTURES.copy.all_both_no_timestamps);

      await viewer.locator('[data-view-mode="turns"]').click();
      await expect(viewer.locator("[data-turn-id]")).toHaveCount(2);
    });

    test("one button marks every turn and clears them all", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      const all = viewer.getByTestId("marks-all");
      await expect(all).toHaveText("Mark all");
      await all.click();
      await expect(viewer.locator("[data-timeline-mark]")).toHaveCount(2);
      await expect(viewer.locator("[data-mark-turn]:checked")).toHaveCount(2);
      await expect(all).toHaveText("Clear marks");
      await all.click();
      await expect(viewer.locator("[data-timeline-mark]")).toHaveCount(0);
      await expect(viewer.locator("[data-mark-turn]:checked")).toHaveCount(0);
      await expect(all).toHaveText("Mark all");
    });

    test("a mark sits on the bar's vertical centre and shows what was said on hover and on keyboard focus", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      await viewer.getByTestId("marks-all").click();
      const bar = viewer.locator("[data-timeline-bar]");
      for (const id of ["turn_a", "turn_b"]) {
        const mark = viewer.locator(`[data-timeline-mark="${id}"]`);
        expect(Math.abs((await centreY(mark)) - (await centreY(bar)))).toBeLessThanOrEqual(1);
      }
      const mark = viewer.locator('[data-timeline-mark="turn_b"]');
      await mark.hover();
      const bubble = viewer.locator('[data-mark-bubble="turn_b"]');
      await expect(bubble).toBeVisible();
      await expect(bubble.locator("[data-bubble-speaker]")).toHaveText("Giriraj");
      await expect(bubble.locator("[data-bubble-original]")).toHaveText("हम dabu के लिए मिट्टी और गोंद मिलाते हैं");
      await expect(bubble.locator("[data-bubble-english]")).toHaveText("We mix clay and gum for dabu (mud-resist printing).");
      // Inside the viewport, even beside the bar's right end on a phone.
      const box = await bubble.boundingBox();
      const width = page.viewportSize()?.width ?? 0;
      expect(box && box.x >= 0 && box.x + box.width <= width).toBe(true);
      await page.mouse.move(1, 1);
      await expect(bubble).toHaveCount(0);

      await viewer.locator('[data-timeline-mark="turn_a"]').focus();
      const focused = viewer.locator('[data-mark-bubble="turn_a"]');
      await expect(focused).toBeVisible();
      await expect(focused.locator("[data-bubble-speaker]")).toHaveText("Nandini (researcher)");
      await expect(viewer.locator('[data-timeline-mark="turn_a"]')).toHaveAttribute("aria-describedby", /.+/);
      await page.keyboard.press("Escape");
      await expect(focused).toHaveCount(0);
    });

    test("each speaker keeps one colour on their label, their marks and the reading view", async ({ page }) => {
      const viewer = await open(page, new TranscriptFake());
      const labelA = viewer.locator('[data-turn-id="turn_a"] [data-speaker]');
      const labelB = viewer.locator('[data-turn-id="turn_b"] [data-speaker]');
      await expect(labelA).toHaveAttribute("data-color-key", "s0");
      await expect(labelB).toHaveAttribute("data-color-key", "s1");
      const colour = (locator: ReturnType<Page["locator"]>, property: "color" | "backgroundColor") =>
        locator.evaluate((element, name) => getComputedStyle(element)[name as "color"], property);
      expect(await colour(labelA, "color")).not.toBe(await colour(labelB, "color"));
      await viewer.getByTestId("marks-all").click();
      expect(await colour(viewer.locator('[data-timeline-mark="turn_b"]'), "backgroundColor")).toBe(await colour(labelB, "color"));
      expect(await colour(viewer.locator('[data-timeline-mark="turn_a"]'), "backgroundColor")).toBe(await colour(labelA, "color"));
      const before = await colour(labelB, "color");
      await page.reload();
      await expect(labelB).toHaveAttribute("data-color-key", "s1");
      expect(await colour(labelB, "color")).toBe(before);
      await viewer.locator('[data-view-mode="reading"]').click();
      await expect(viewer.locator("[data-reading-block] [data-speaker]").nth(1)).toHaveAttribute("data-color-key", "s1");
    });

    test("auto-follow keeps the spoken line in view, stops when the reader takes the page back, and the pill resumes it", async ({ page }) => {
      const fake = new TranscriptFake();
      longTranscript(fake);
      const viewer = await open(page, fake);
      await expect(viewer.getByTestId("follow-along")).toHaveCount(0);
      await viewer.getByTestId("transcript-play").click();
      // Following: the page travels with the audio and the lit line stays on screen.
      await expect.poll(() => page.evaluate(() => window.scrollY), { timeout: 20_000 }).toBeGreaterThan(300);
      await expect.poll(() => activeInView(page)).toBe(true);
      await expect(viewer.getByTestId("follow-along")).toHaveCount(0);

      // The reader takes over: a touch on a touch screen, a wheel upwards with a mouse.
      if (viewport.name === "desktop") {
        await page.mouse.move(640, 500);
        await page.mouse.wheel(0, -400);
      } else {
        await page.touchscreen.tap(4, 500);
      }
      const pill = page.getByTestId("follow-along");
      await expect(pill).toBeVisible();
      await page.waitForTimeout(600);
      const parked = await page.evaluate(() => window.scrollY);
      await page.waitForTimeout(2500);
      expect(Math.abs((await page.evaluate(() => window.scrollY)) - parked)).toBeLessThanOrEqual(2);

      // "Follow along" picks it up again.
      await pill.click();
      await expect(pill).toHaveCount(0);
      await expect.poll(() => activeInView(page), { timeout: 10_000 }).toBe(true);
      await viewer.locator("audio[data-transcript-audio]").evaluate((element: HTMLAudioElement) => element.pause());
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

  test("a read-only reader is told once and offered no edit, speaker or re-translate control", async ({ page }) => {
    const fake = new TranscriptFake();
    const message = "Read-only: part of a workshop you oversee.";
    const view = fake.view as unknown as Transcript;
    view.readOnly = { code: "HELD_POST", message };
    view.permissions = { ...view.permissions, edit: false, retry: false, autodraft: false, mapSpeakers: false };
    view.turns[1].translation.status = "STALE";
    const viewer = await open(page, fake);
    await expect(viewer.locator('[data-transcript-read-only="HELD_POST"]')).toHaveText(message);
    await expect(viewer.getByText(message)).toHaveCount(1);
    await expect(viewer.getByRole("button", { name: "Edit Giriraj at 00:00:09" })).toHaveCount(0);
    await expect(viewer.getByTestId("speakers-open")).toHaveCount(0);
    await viewer.locator('[data-tab="english"]').click();
    await expect(viewer.locator('[data-retranslate="turn_b"]')).toHaveCount(0);
    await expect(viewer.getByTestId("ask-open")).toBeVisible();
  });

  test("the whole transcript and its English copy and download without timestamps", async ({ page }) => {
    const fake = new TranscriptFake();
    const viewer = await open(page, fake);
    await pick(page, viewer.getByTestId("copy-all"), "both-plain");
    await expect(viewer.locator('[data-copy-status="ok"]')).toHaveText("Copied");
    expect(await clipboard(page)).toBe(FIXTURES.copy.all_both_no_timestamps);
    const download = page.waitForEvent("download");
    await pick(page, viewer.getByTestId("download"), "md-plain");
    await download;
    const asked = fake.calledWith("GET", `/transcripts/${MEDIA_ID}/download`).at(-1);
    expect(asked?.query.get("timestamps")).toBe("false");
    expect(asked?.query.get("format")).toBe("md");
  });

  test("each speaker plays a sample of their own voice to be named by", async ({ page }) => {
    const viewer = await open(page, new TranscriptFake());
    await viewer.getByTestId("speakers-open").click();
    const play = viewer.locator('[data-play-sample="speaker_1"]');
    await expect(play).toHaveText("Play a sample");
    await play.click();
    await expect(play).toHaveAttribute("aria-pressed", "true");
    const at = await page.locator("[data-transcript-audio]").evaluate((audio: HTMLAudioElement) => audio.currentTime);
    expect(at).toBeGreaterThanOrEqual(9);
  });

  test("a ticked turn becomes a point on the timeline, and none is there until ticked", async ({ page }) => {
    const viewer = await open(page, new TranscriptFake());
    await expect(viewer.locator("[data-timeline-mark]")).toHaveCount(0);
    const box = viewer.locator('[data-mark-turn="turn_b"]');
    await expect(box).not.toBeChecked();
    await box.check();
    const mark = viewer.locator('[data-timeline-mark="turn_b"]');
    await expect(mark).toBeVisible();
    await mark.click();
    const at = await page.locator("[data-transcript-audio]").evaluate((audio: HTMLAudioElement) => audio.currentTime);
    expect(at).toBeGreaterThanOrEqual(8.9);
    await box.uncheck();
    await expect(viewer.locator("[data-timeline-mark]")).toHaveCount(0);
  });

  test("cleaned audio plays by default and the recording as made is one tick away", async ({ page }) => {
    const fake = new TranscriptFake();
    const view = fake.view as unknown as Transcript;
    view.audio = { ...view.audio, cleaned: true, originalUrl: `${view.audio.url}?original=1` };
    const viewer = await open(page, fake);
    await expect(viewer.locator("[data-audio-cleaned]")).toContainText("Background noise removed");
    const src = () => page.locator("[data-transcript-audio]").evaluate((audio: HTMLAudioElement) => audio.getAttribute("src"));
    expect(await src()).not.toContain("original=1");
    await viewer.getByTestId("play-original").check();
    await expect.poll(src).toContain("original=1");
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
    expect((await download).suggestedFilename()).toMatch(/_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Quote-Card\.png$/);
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
async function clickIsHarmless(page: Page, scope: ReturnType<Page["locator"]>) {
  await scope.getByText("click me").click();
  await page.waitForTimeout(200);
}
