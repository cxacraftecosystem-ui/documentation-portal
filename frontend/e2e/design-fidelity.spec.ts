import { mkdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Browser, type Page } from "@playwright/test";

import { AUDIO_ID, GIRIRAJ, VIDEO_ID, DesignFidelityFake } from "./support/designFidelityFake";

const OUT = process.env.DESIGN_OUT ?? join("test-results", "design-fidelity");

type Theme = "light" | "dark";

type Scene = {
  board: string;
  variant?: string;
  width: number;
  height: number;
  theme?: Theme;
  query: string;
  ready?: (page: Page) => Promise<void>;
  act?: (page: Page) => Promise<void>;
};

const DESKTOP = { width: 1440, height: 900 };
const TABLET = { width: 834, height: 1112 };
const PHONE = { width: 390, height: 844 };

function scaleFor(width: number): number {
  return width <= 600 ? 2 : 1;
}

function folder(path: string, extra: Record<string, string> = {}): string {
  return new URLSearchParams({ path, ...extra }).toString();
}

async function folderReady(page: Page) {
  await expect(page.getByTestId("download-folder")).toBeVisible();
}

async function audioReady(page: Page) {
  const body = page.getByTestId("preview-body");
  await expect(body).toBeVisible();
  await expect(page.getByTestId("preview-lines")).toContainText("Without the");
  await page.waitForFunction(() => {
    const media = document.querySelector("[data-testid=preview-body] audio") as HTMLAudioElement | null;
    return Boolean(media && Number.isFinite(media.duration) && media.duration > 100);
  });
}

async function englishLime(page: Page) {
  const english = page.getByRole("tab", { name: "English", exact: true });
  if (await english.isVisible()) await english.click();
  const next = page.getByRole("button", { name: "Next match" });
  if (await next.isVisible()) await next.click();
  await page.waitForTimeout(400);
}

async function settle(page: Page) {
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.addStyleTag({ content: "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}" });
  await page.waitForTimeout(300);
}

const AUDIO_URL = folder(GIRIRAJ, { preview: AUDIO_ID, t: "1100", hit: "lime", kind: "AUDIO" });
const VIDEO_URL = folder(GIRIRAJ, { preview: VIDEO_ID, t: "724", hit: "indigo", kind: "VIDEO" });

const SCENES: Scene[] = [
  { board: "WebAudio", ...DESKTOP, query: AUDIO_URL, ready: audioReady, act: englishLime },
  { board: "WebAudio", variant: "dark", ...DESKTOP, theme: "dark", query: AUDIO_URL, ready: audioReady, act: englishLime },
  { board: "WebAudio", variant: "tablet", ...TABLET, query: AUDIO_URL, ready: audioReady, act: englishLime },
  { board: "AndroidAudio", variant: "phone-web", ...PHONE, query: AUDIO_URL, ready: audioReady, act: englishLime },
  { board: "AndroidAudio", variant: "phone-web-dark", ...PHONE, theme: "dark", query: AUDIO_URL, ready: audioReady, act: englishLime },
  {
    board: "AndroidAudioPlayer",
    variant: "phone-web",
    ...PHONE,
    query: AUDIO_URL,
    ready: audioReady,
    act: async (page) => {
      await page.getByRole("button", { name: "Keep playing while browsing" }).click();
      await expect(page.getByRole("region", { name: "Now playing" })).toBeVisible();
    }
  },
  { board: "WebPreview", ...DESKTOP, query: VIDEO_URL, ready: async (page) => expect(page.getByTestId("preview-body")).toBeVisible() },
  { board: "Main", width: 1440, height: 1240, query: folder(GIRIRAJ), ready: folderReady },
  { board: "Main", variant: "dark", width: 1440, height: 1240, theme: "dark", query: folder(GIRIRAJ), ready: folderReady },
  { board: "WebTablet", ...TABLET, query: folder(GIRIRAJ), ready: folderReady },
  { board: "WebPhone", ...PHONE, query: folder(GIRIRAJ), ready: folderReady },
  { board: "WebTranscripts", width: 1440, height: 820, query: folder(GIRIRAJ, { view: "transcripts" }), ready: folderReady },
  { board: "WebTables", width: 1440, height: 1000, query: folder(GIRIRAJ, { view: "tables", q: "indigo" }), ready: folderReady },
  { board: "PhoneTables", variant: "phone-web", ...PHONE, query: folder(GIRIRAJ, { view: "tables", q: "indigo" }), ready: folderReady },
  { board: "WorkshopOverview", ...DESKTOP, query: folder("workshops/3rd"), ready: folderReady }
];

async function capture(browser: Browser, scene: Scene) {
  const context = await browser.newContext({
    viewport: { width: scene.width, height: scene.height },
    deviceScaleFactor: scaleFor(scene.width),
    isMobile: scene.width < 900,
    hasTouch: scene.width < 900,
    colorScheme: scene.theme ?? "light",
    serviceWorkers: "block",
    acceptDownloads: true,
    reducedMotion: "reduce"
  });
  const fake = new DesignFidelityFake();
  await fake.install(context, scene.theme ?? "light");
  const page = await context.newPage();
  try {
    await page.goto(`/data?${scene.query}`);
    if (scene.ready) await scene.ready(page);
    if (scene.act) await scene.act(page);
    await settle(page);
    const dir = join(OUT, scene.board);
    mkdirSync(dir, { recursive: true });
    await page.screenshot({ path: join(dir, `impl${scene.variant ? `-${scene.variant}` : ""}.png`) });
  } finally {
    await context.close();
  }
}

for (const scene of SCENES) {
  test(`impl ${scene.board}${scene.variant ? ` · ${scene.variant}` : ""}`, async ({ browser }) => {
    await capture(browser, scene);
  });
}
