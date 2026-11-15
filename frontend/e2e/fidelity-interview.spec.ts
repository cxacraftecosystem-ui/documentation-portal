import { mkdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { expect, test, type Page } from "@playwright/test";

import {
  GROUP,
  INTERVIEW_SEED_MISSING,
  MEERA,
  S,
  as,
  audioFile,
  boardSession,
  createSession,
  fillStartSheet,
  openSection,
  openSession,
  section,
  stackContext,
  uploadTake,
  type Theme
} from "./support/interviewStack";

test.skip(Boolean(INTERVIEW_SEED_MISSING), INTERVIEW_SEED_MISSING);
test.describe.configure({ mode: "parallel" });
test.use({ launchOptions: { args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] } });

const OUT = process.env.DESIGN_OUT ?? join("test-results", "design-fidelity");
const FILES = join(process.env.INTERVIEW_FILES ?? tmpdir(), "interview-fidelity");
const WIDTHS = [360, 768, 1280] as const;
const THEMES: Theme[] = ["light", "dark"];
const HEIGHT: Record<(typeof WIDTHS)[number], number> = { 360: 800, 768: 1024, 1280: 900 };

type Width = (typeof WIDTHS)[number];

async function settle(page: Page) {
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.addStyleTag({ content: "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}" });
  await page.waitForTimeout(300);
}

async function shoot(page: Page, board: string, width: Width, theme: Theme, variant = "", fullPage = false) {
  await settle(page);
  const dir = join(OUT, `interview-${board}`);
  mkdirSync(dir, { recursive: true });
  const name = ["impl-web", variant, String(width), theme === "dark" ? "dark" : ""].filter(Boolean).join("-");
  await page.screenshot({ path: join(dir, `${name}.png`), fullPage });
}

function capture(board: string, title: string, run: (page: Page, width: Width, theme: Theme) => Promise<void>) {
  for (const width of WIDTHS) {
    for (const theme of THEMES) {
      test(`impl interview-${board} · ${title} · ${width}${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
        test.setTimeout(180_000);
        const { context, page } = await stackContext(browser, width, theme, HEIGHT[width]);
        try {
          await run(page, width, theme);
        } finally {
          await context.close();
        }
      });
    }
  }
}

capture("WebSessions", "sessions list", async (page, width, theme) => {
  await boardSession();
  const finished = await createSession({ artisans: [GROUP[1]] });
  await as().finish(finished.id);
  await page.goto("/questionnaire");
  await expect(page.getByText(S.openToday).first()).toBeVisible({ timeout: 30_000 });
  await shoot(page, "WebSessions", width, theme);
  await shoot(page, "Main", width, theme, "list");
});

capture("WebNew", "new session", async (page, width, theme) => {
  await page.goto("/questionnaire/sessions/new");
  const sheet = await fillStartSheet(page, [MEERA]);
  await expect(sheet.getByRole("group", { name: S.fieldRecordBy })).toBeVisible();
  await shoot(page, "WebNew", width, theme, "", true);
  await shoot(page, "New", width, theme, "", true);
});

capture("SessionClosed", "all sections closed", async (page, width, theme) => {
  const session = await boardSession();
  await openSession(page, session);
  await expect(page.getByText(S.questionsOfTotal({ answered: 18, total: 108 }))).toBeVisible();
  await shoot(page, "SessionClosed", width, theme);
});

capture("WebWorkspace", "by section, menu open", async (page, width, theme) => {
  const session = await boardSession();
  await openSession(page, session);
  await openSection(page, "E");
  await expect(page.getByRole("button", { name: S.recordSection }).first()).toBeVisible();
  await shoot(page, "Sections", width, theme);
  await page.getByRole("button", { name: S.sessionOptions }).click();
  await expect(page.getByText(S.recordByHelper)).toBeVisible();
  await shoot(page, "WebWorkspace", width, theme);
  await shoot(page, "Menu", width, theme);
});

capture("Question", "by question", async (page, width, theme) => {
  const session = await boardSession({ recordBy: "QUESTION" });
  await openSession(page, session);
  await openSection(page, "E");
  await expect(page.getByRole("button", { name: S.record, exact: true }).first()).toBeVisible();
  await shoot(page, "Question", width, theme);
});

capture("WebWorkspaceDark", "recording section E", async (page, width, theme) => {
  const session = await boardSession();
  await openSession(page, session);
  await openSection(page, "E");
  await page.getByRole("button", { name: S.recordSection }).first().click();
  await expect(page.getByText(S.recordingSection({ code: "E" }))).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(1500);
  await shoot(page, "WebWorkspaceDark", width, theme);
  await shoot(page, "SectionRecording", width, theme);
  await page.getByRole("button", { name: S.stop, exact: true }).click();
});

capture("WebUpload", "upload recordings", async (page, width, theme) => {
  const session = await createSession({ artisans: [MEERA] });
  const dup = audioFile(FILES, `already-${width}-${theme}.wav`, 2);
  await uploadTake(session, { sectionId: section("E") }, { bytes: new Uint8Array(readFileSync(dup.path)) });
  const first = audioFile(FILES, `E-take-1-${width}-${theme}.wav`, 1);
  const second = audioFile(FILES, `E-take-2-${width}-${theme}.wav`, 1);
  await openSession(page, session);
  await openSection(page, "E");
  await page.getByRole("button", { name: S.upload, exact: true }).first().click();
  const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
  await dialog.locator("input[type=file]").setInputFiles([dup.path, first.path, second.path]);
  await expect(dialog.getByText(/^Already uploaded on /)).toBeVisible({ timeout: 30_000 });
  await shoot(page, "WebUpload", width, theme);
  await shoot(page, "Upload", width, theme);
});

capture("AnsweredBy", "who answered", async (page, width, theme) => {
  const session = await createSession({ artisans: [...GROUP] });
  await openSession(page, session);
  await openSection(page, "B");
  await page.getByRole("button", { name: S.recordSection }).first().click();
  await expect(page.getByText(S.recordingSection({ code: "B" }))).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(1500);
  await page.getByRole("button", { name: S.stop, exact: true }).click();
  await expect(page.getByRole("dialog", { name: S.whoAnsweredTitle })).toBeVisible();
  await shoot(page, "AnsweredBy", width, theme);
});

capture("Already", "already recorded", async (page, width, theme) => {
  const session = await boardSession();
  await openSession(page, session);
  await openSection(page, "A");
  await page.getByRole("button", { name: S.recordSection }).first().click();
  await expect(page.getByText(S.alreadyRecordedCode({ code: "A" }))).toBeVisible({ timeout: 30_000 });
  await shoot(page, "Already", width, theme);
});

capture("WebReview", "review and finish", async (page, width, theme) => {
  const session = await boardSession();
  await openSession(page, session);
  await page.getByRole("button", { name: S.review }).click();
  await expect(page.getByText(S.questionsAnswered({ answered: 18, total: 108 }))).toBeVisible();
  await shoot(page, "WebReview", width, theme, "", true);
  await shoot(page, "Review", width, theme, "", true);
});

capture("States", "finished, read only", async (page, width, theme) => {
  const session = await boardSession();
  await as().finish(session.id);
  await openSession(page, session);
  await expect(page.getByText(/^Finished on .+\. Read only\.$/)).toBeVisible();
  await shoot(page, "States", width, theme, "read-only");
});

capture("States", "waiting for signal", async (page, width, theme) => {
  const session = await boardSession();
  await openSession(page, session);
  await page.context().setOffline(true);
  await page.getByRole("alertdialog").getByRole("button", { name: "Continue offline" }).click();
  await page.getByRole("button", { name: S.sessionOptions }).click();
  await page.getByRole("group", { name: S.fieldRecordBy }).getByRole("button", { name: S.recordByQuestion, exact: true }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByText(S.waitingForSignal).first()).toBeVisible();
  await shoot(page, "States", width, theme, "offline");
  await page.context().setOffline(false);
});

capture("States", "microphone off", async (page, width, theme) => {
  const session = await boardSession();
  await page.context().clearPermissions();
  await page.addInitScript(() => {
    const denied = () => Promise.reject(new DOMException("Permission denied", "NotAllowedError"));
    Object.defineProperty(navigator, "mediaDevices", { value: { getUserMedia: denied, enumerateDevices: () => Promise.resolve([]) }, configurable: true });
  });
  await openSession(page, session);
  await openSection(page, "F");
  await page.getByRole("button", { name: S.recordSection }).first().click();
  await expect(page.getByText(S.micOff).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: S.uploadInstead })).toBeVisible();
  await shoot(page, "States", width, theme, "mic-off");
});
