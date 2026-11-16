import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { expect, test, type Locator, type Page } from "@playwright/test";

import {
  GROUP,
  INTERVIEW_SEED_MISSING,
  MEERA,
  S,
  as,
  audioBytes,
  audioFile,
  boardSession,
  createSession,
  escape,
  fillStartSheet,
  holdUploads,
  LALITA,
  lineage,
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

async function partShot(part: Locator, board: string, width: Width, theme: Theme, variant: string) {
  await settle(part.page());
  const dir = join(OUT, `interview-${board}`);
  mkdirSync(dir, { recursive: true });
  const name = ["impl-web", variant, String(width), theme === "dark" ? "dark" : ""].filter(Boolean).join("-");
  await part.screenshot({ path: join(dir, `${name}.png`) });
}

capture("WholeSession", "whole session block, an uploaded take and one uploading", async (page, width, theme) => {
  const session = await createSession({ artisans: [MEERA] });
  await uploadTake(session, null, { durationMs: 192_000 });
  const file = audioFile(FILES, `whole-${width}-${theme}.wav`, 1);
  await openSession(page, session);
  const release = await holdUploads(page);
  try {
    const whole = page.locator("[data-whole-session]");
    await whole.getByRole("button", { name: S.upload, exact: true }).click();
    const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
    await dialog.locator("input[type=file]").setInputFiles([file.path]);
    const confirm = dialog.getByRole("button", { name: S.uploadOneFile });
    await expect(confirm).toBeEnabled({ timeout: 30_000 });
    await confirm.click();
    await expect(whole.locator("[data-take-upload='UPLOADING']")).toHaveCount(1, { timeout: 30_000 });
    await expect(whole.locator("[data-whole-take]")).toHaveCount(2);
    await shoot(page, "WholeSession", width, theme);
    await partShot(whole, "WholeSession", width, theme, "block");
  } finally {
    release();
  }
});

capture("UploadDuplicates", "upload sheet, every duplicate state", async (page, width, theme) => {
  const other = await createSession({ artisans: [LALITA] });
  const theirs = audioFile(FILES, `Sandesh-13-Nov-${width}-${theme}.wav`, 2);
  await uploadTake(other, { sectionId: section("B") }, { bytes: new Uint8Array(readFileSync(theirs.path)), artisans: [LALITA] });
  const session = await createSession({ artisans: [MEERA] });
  const mine = audioFile(FILES, `Meera-D-${width}-${theme}.wav`, 2);
  await uploadTake(session, { sectionId: section("D") }, { bytes: new Uint8Array(readFileSync(mine.path)) });
  const sending = audioFile(FILES, `Meera-sending-${width}-${theme}.wav`, 1);
  const fresh = audioFile(FILES, `Meera-E-${width}-${theme}.wav`, 1);
  const twice = join(FILES, `Meera-E-copy-${width}-${theme}.wav`);
  writeFileSync(twice, readFileSync(fresh.path));
  await openSession(page, session);
  await openSection(page, "E");
  const release = await holdUploads(page);
  try {
    await page.getByRole("button", { name: S.upload, exact: true }).first().click();
    const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
    await dialog.locator("input[type=file]").setInputFiles([sending.path]);
    const confirm = dialog.getByRole("button", { name: S.uploadOneFile });
    await expect(confirm).toBeEnabled({ timeout: 30_000 });
    await confirm.click();
    await expect(page.locator("[data-section-takes='E'] [data-take-upload='UPLOADING']")).toHaveCount(1, { timeout: 30_000 });
    await page.getByRole("button", { name: S.upload, exact: true }).first().click();
    await dialog.locator("input[type=file]").setInputFiles([mine.path, theirs.path, sending.path, fresh.path, twice]);
    await expect(dialog.locator("[data-duplicate='SERVER']")).toHaveCount(2, { timeout: 30_000 });
    await expect(dialog.locator("[data-duplicate='QUEUED']")).toHaveCount(1);
    await expect(dialog.locator("[data-duplicate='LIST']")).toHaveCount(1);
    await expect(dialog.getByRole("button", { name: S.uploadOneFile })).toBeEnabled();
    await shoot(page, "UploadDuplicates", width, theme);
    await partShot(dialog.locator("[data-upload-rows]"), "UploadDuplicates", width, theme, "rows");
  } finally {
    release();
  }
});

capture("RecordingsSort", "recordings list, added then recorded", async (page, width, theme) => {
  const session = await createSession({ artisans: [GROUP[2]] });
  const artisanId = session.participants[0].artisanId;
  await uploadTake(session, { sectionId: section("A") }, { artisans: [GROUP[2]], durationMs: 192_000 });
  await uploadTake(session, { sectionId: section("B") }, { artisans: [GROUP[2]], capturedAt: "2026-11-01T05:00:00.000Z", durationMs: 61_000 });
  await page.goto(`/questionnaire/recordings?artisanId=${encodeURIComponent(artisanId)}`);
  const toggle = page.getByRole("group", { name: S.sortRecordingsBy });
  await expect(toggle.getByRole("button", { name: S.sortAdded, exact: true })).toHaveAttribute("aria-pressed", "true", { timeout: 30_000 });
  await expect(page.locator("[data-recording-row]")).toHaveCount(2, { timeout: 30_000 });
  await shoot(page, "RecordingsSort", width, theme, "added");
  const answered = page.waitForResponse((r) => r.url().includes("/questionnaire/recordings") && r.url().includes("sort=recorded"));
  await toggle.getByRole("button", { name: S.sortRecorded, exact: true }).click();
  await answered;
  await expect(page.locator("[data-recordings-sort='recorded']")).toBeVisible();
  await expect(page.locator("[data-recording-row]")).toHaveCount(2, { timeout: 30_000 });
  await shoot(page, "RecordingsSort", width, theme, "recorded");
});

async function pickSections(page: Page, scope: Locator, codes: string[]) {
  await scope.getByRole("button", { name: S.coversSections }).click();
  for (const code of codes) await page.getByRole("option", { name: new RegExp(`^${escape(S.chipSection({ code }))} ·`) }).click();
  await page.keyboard.press("Escape");
}

capture("UploadCovers", "upload to the whole session covering sections", async (page, width, theme) => {
  const session = await createSession({ artisans: [MEERA] });
  const file = audioFile(FILES, `covers-${width}-${theme}.wav`, 1);
  await openSession(page, session);
  await page.locator("[data-whole-session]").getByRole("button", { name: S.upload, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
  await dialog.locator("input[type=file]").setInputFiles([file.path]);
  await expect(dialog.getByRole("button", { name: S.uploadOneFile })).toBeEnabled({ timeout: 30_000 });
  await pickSections(page, dialog, ["A", "C"]);
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.countsForSections({ codes: "A, C" }));
  await dialog.locator("[data-sections-chooser]").scrollIntoViewIfNeeded();
  await shoot(page, "UploadCovers", width, theme);
  await partShot(dialog, "UploadCovers", width, theme, "dialog");
});

capture("MarkSections", "mark a whole-session take with sections", async (page, width, theme) => {
  const session = await createSession({ artisans: [MEERA] });
  await uploadTake(session, null, { durationMs: 192_000 });
  await openSession(page, session);
  await page.locator("[data-whole-session] [data-mark-sections-open]").click();
  const dialog = page.getByRole("dialog", { name: S.markSections });
  await pickSections(page, dialog, ["B"]);
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.countsForSections({ codes: "B" }));
  await shoot(page, "MarkSections", width, theme);
  await partShot(dialog, "MarkSections", width, theme, "dialog");
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

const TAKE_MS = 192_000;

const TAKE_LINES: [number, string, string, string][] = [
  [0, "speaker_0", "ନୂଆ ଶିଖାଳିଙ୍କ ପାଇଁ କେଉଁ ସ୍ତର ସବୁଠାରୁ କଷ୍ଟକର?", "Which stage is the hardest for a beginner to learn?"],
  [9_000, "speaker_1", "ଛେଣିରେ ମୋଟାମୋଟି କାଟିବା। ହାତ ସ୍ଥିର ନ ରହିଲେ ପଥର ଫାଟିଯାଏ।", "The rough cutting with the chisel. If the hand is not steady, the stone cracks."],
  [42_000, "speaker_0", "ପଥରକୁ ଆଗରୁ କାହିଁକି ଭିଜାନ୍ତି?", "Why do you soak the stone first?"],
  [50_000, "speaker_1", "ରାତିସାରା ପାଣିରେ ଭିଜାଉ। ପଥର ନରମ ହୁଏ, ଛେଣି ସଫା ଚାଲେ।", "We keep it in water overnight. The stone softens and the chisel moves cleanly."],
  [96_000, "speaker_0", "ଆଉ ଶେଷରେ ଘଷିବା?", "And the polishing at the end?"],
  [104_000, "speaker_1", "ଓଦା ବାଲିରେ କିଛି ଦିନ ଘଷୁ। ନୂଆ ଶିଖାଳିଙ୍କୁ ଏଥିରେ ବର୍ଷେ ଲାଗେ।", "We rub it with wet sand for days. A new learner needs a year to get an even finish."],
  [150_000, "speaker_0", "ଏହା କେତେ ଦିନ ଲାଗେ?", "How many days does it take?"],
  [158_000, "speaker_1", "ବଡ଼ ମୂର୍ତ୍ତି ପାଇଁ ପ୍ରାୟ ଏକ ମାସ।", "About a month for a large figure."]
];

const TAKE_SPEAKERS: Record<string, string> = { speaker_0: "Interviewer", speaker_1: "Meera Devi" };

function takeTranscript(mediaId: string) {
  return {
    mediaId,
    title: "Question take",
    status: "READY",
    queueReason: null,
    statusMessage: null,
    version: 1,
    languages: [
      { code: "or", label: "Odia" },
      { code: "en", label: "English" }
    ],
    durationMs: TAKE_MS,
    speakerCount: 2,
    badges: [],
    speakers: Object.entries(TAKE_SPEAKERS).map(([speakerKey, name], i) => ({ speakerKey, label: `Speaker ${i + 1}`, name, role: i ? "ARTISAN" : "RESEARCHER", personId: null, suggested: false })),
    turns: TAKE_LINES.map(([startMs, key, text, english], index) => ({
      id: `t${index}`,
      index,
      speakerKey: key,
      speakerName: TAKE_SPEAKERS[key],
      startMs,
      endMs: (TAKE_LINES[index + 1]?.[0] ?? TAKE_MS) - 500,
      language: "or",
      text,
      textSource: "MACHINE",
      lowConfidence: [],
      words: [],
      translation: { text: english, status: "TRANSLATED", source: "MACHINE", model: null },
      revision: 1
    })),
    summary: [],
    quotes: [],
    cost: null,
    audio: { url: null, durationMs: TAKE_MS },
    permissions: { edit: false, retry: false, transcribeNow: false, ask: false, autodraft: false, quoteCard: false, mapSpeakers: false },
    readOnly: null,
    revision: 1,
    updatedAt: "2026-11-15T10:00:00Z"
  };
}

function takeWave(): number[] {
  return Array.from({ length: 800 }, (_, i) => Math.max(0.12, Math.min(1, 0.55 + 0.3 * Math.sin(i / 7) * Math.cos(i / 19) + 0.15 * Math.sin(i / 3.3))));
}

async function takesSession() {
  const session = await createSession({ artisans: [MEERA] });
  await uploadTake(session, { sectionId: section("A") }, { durationMs: TAKE_MS, bytes: audioBytes(TAKE_MS / 1000) });
  await uploadTake(session, { questionLineageId: lineage("E2") }, { durationMs: TAKE_MS, bytes: audioBytes(TAKE_MS / 1000) });
  const detail = await as().getSession(session.id);
  return { session, media: detail.recordings.map((r) => r.mediaFileId) };
}

async function explorerData(page: Page, media: string[]) {
  for (const id of media) {
    await page.route(new RegExp(`/api/transcripts/${escape(id)}(\\?.*)?$`), (route) =>
      route.request().method() === "GET" ? route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(takeTranscript(id)) }) : route.fallback()
    );
    await page.route(new RegExp(`/api/data/media/${escape(id)}/peaks(\\?.*)?$`), (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ peaks: takeWave(), durationMs: TAKE_MS, isolated: false, canIsolate: false }) })
    );
  }
}

async function takeShot(target: Page | Locator, board: string, width: Width, theme: Theme, variant = "") {
  const dir = join(OUT, `takes-${board}`);
  mkdirSync(dir, { recursive: true });
  const name = ["impl-web", variant, String(width), theme === "dark" ? "dark" : ""].filter(Boolean).join("-");
  await target.screenshot({ path: join(dir, `${name}.png`) });
}

async function previewReady(scope: Locator) {
  await expect(scope.getByTestId("preview-controls")).toBeVisible({ timeout: 30_000 });
  await expect(scope.locator("[data-line]").first()).toBeVisible({ timeout: 30_000 });
  await expect(scope.getByTestId("preview-seek").locator("i").first()).toBeVisible({ timeout: 30_000 });
}

capture("TakeOpen", "question take open in place", async (page, width, theme) => {
  const { session, media } = await takesSession();
  await explorerData(page, media);
  await openSession(page, session);
  await openSection(page, "E");
  const e2 = page.locator("[data-question='E2']");
  await expect(e2.locator("[data-question-state='QUESTION_TAKE']")).toBeVisible({ timeout: 30_000 });
  await e2.locator("[data-take-play]").click();
  const region = e2.locator("[data-take-region]");
  await previewReady(region);
  await settle(page);
  const boards = ["WebTakeOpen", ...(theme === "dark" ? ["WebTakeDark"] : []), ...(width === 360 ? ["WebTakePhone"] : [])];
  for (const board of boards) {
    await takeShot(page, board, width, theme);
    await takeShot(region.getByTestId("preview-controls"), board, width, theme, "controls");
  }
  await e2.locator("[data-take-full]").click();
  const dialog = page.getByRole("dialog");
  await previewReady(dialog);
  await settle(page);
  for (const board of boards) {
    await takeShot(page, board, width, theme, "explorer");
    await takeShot(dialog.getByTestId("preview-controls"), board, width, theme, "explorer-controls");
  }
});

capture("TakeSection", "section take open in place, side by side", async (page, width, theme) => {
  const { session, media } = await takesSession();
  await explorerData(page, media);
  await openSession(page, session);
  await openSection(page, "A");
  const takes = page.locator("[data-section-takes='A']");
  await takes.locator("[data-take-play]").first().click();
  const region = takes.locator("[data-take-region]");
  await previewReady(region);
  const side = region.getByRole("tab", { name: "Side by side" });
  if (await side.count()) await side.click();
  await settle(page);
  await takeShot(page, "WebTakeSideBySide", width, theme);
  await takeShot(region.getByTestId("preview-controls"), "WebTakeSideBySide", width, theme, "controls");
  const a1 = page.locator("[data-question='A1']");
  await a1.locator("[data-take-play]").click();
  await previewReady(a1.locator("[data-take-region]"));
  await expect(region).toHaveCount(0);
  await settle(page);
  await takeShot(page, "WebTakeSideBySide", width, theme, "question");
  await a1.locator("[data-take-full]").click();
  const dialog = page.getByRole("dialog");
  await previewReady(dialog);
  const fullSide = dialog.getByRole("tab", { name: "Side by side" });
  if (await fullSide.count()) await fullSide.click();
  await settle(page);
  await takeShot(page, "WebTakeSideBySide", width, theme, "explorer");
});
