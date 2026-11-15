import { readFileSync } from "node:fs";
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
  escape,
  fillStartSheet,
  lineage,
  openSection,
  openSession,
  section,
  seed,
  signInAs,
  today,
  uploadTake
} from "./support/interviewStack";

test.skip(Boolean(INTERVIEW_SEED_MISSING), INTERVIEW_SEED_MISSING);
test.use({
  viewport: { width: 1280, height: 900 },
  permissions: ["microphone"],
  launchOptions: { args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] }
});

const FILES = join(process.env.INTERVIEW_FILES ?? tmpdir(), "interview-uploads");

test.beforeEach(async ({ context }) => {
  await signInAs(context);
});

async function sessionMenu(page: Page) {
  await page.getByRole("button", { name: S.sessionOptions }).click();
  return page.getByRole("menu");
}

async function headerTop(page: Page): Promise<number> {
  return page.evaluate(() => {
    const header = document.querySelector("[data-session-header]") ?? document.querySelector("header");
    return header ? header.getBoundingClientRect().bottom : 0;
  });
}

function sectionHeader(page: Page, code: string) {
  const title = seed().sectionTitles[code];
  return page.getByRole("button", { name: new RegExp(`^(Section )?${code},? ${escape(title)}`) }).first();
}

async function serverRecordBy(id: string): Promise<string | undefined> {
  const detail = (await as().getSession(id)) as unknown as { recordBy?: string };
  return detail.recordBy;
}

test("@qf:Q1 a session started by section sends recordBy SECTION and opens at no questions answered", async ({ page }) => {
  await page.goto("/questionnaire/sessions/new");
  const sheet = await fillStartSheet(page, [MEERA]);
  const group = sheet.getByRole("radiogroup", { name: S.fieldRecordBy });
  await expect(group.getByRole("radio", { name: S.recordBySection })).toBeChecked();
  await expect(sheet.getByText(S.fromThisWorkshop).first()).toBeVisible();
  const created = page.waitForResponse((r) => r.request().method() === "POST" && /\/questionnaire\/sessions$/.test(new URL(r.url()).pathname));
  await sheet.getByRole("button", { name: S.startSession }).click();
  const response = await created;
  expect(response.request().postDataJSON()).toMatchObject({ recordBy: "SECTION" });
  const session = (await response.json()) as { id: string; recordBy?: string };
  expect(session.recordBy).toBe("SECTION");
  await expect(page).toHaveURL(new RegExp(`/questionnaire/sessions/${escape(session.id)}`), { timeout: 30_000 });
  await expect(page.getByText(S.questionsOfTotal({ answered: 0, total: 108 }))).toBeVisible();
  await expect(page.getByRole("button", { name: S.review })).toBeVisible();
});

test("@qf:Q1 @qf:Q3 a session started by question sends recordBy QUESTION and every question carries Record and Upload", async ({ page }) => {
  await page.goto("/questionnaire/sessions/new");
  const sheet = await fillStartSheet(page, [MEERA]);
  await sheet.getByRole("radiogroup", { name: S.fieldRecordBy }).getByRole("radio", { name: S.recordByQuestion }).click();
  const created = page.waitForResponse((r) => r.request().method() === "POST" && /\/questionnaire\/sessions$/.test(new URL(r.url()).pathname));
  await sheet.getByRole("button", { name: S.startSession }).click();
  const response = await created;
  expect(response.request().postDataJSON()).toMatchObject({ recordBy: "QUESTION" });
  const id = ((await response.json()) as { id: string }).id;
  expect(await serverRecordBy(id)).toBe("QUESTION");
  await openSection(page, "E");
  const e1 = page.locator("[data-question='E1'], [data-question-code='E1']").first();
  const pair = e1.getByRole("button", { name: S.record, exact: true });
  await expect(pair).toBeVisible();
  const upload = e1.getByRole("button", { name: S.upload, exact: true });
  const [a, b] = [await pair.boundingBox(), await upload.boundingBox()];
  expect(Math.abs(a!.width - b!.width), "Record and Upload are the same width").toBeLessThanOrEqual(1);
  expect(Math.abs(a!.height - b!.height), "Record and Upload are the same height").toBeLessThanOrEqual(1);
  await expect(page.getByRole("button", { name: S.recordSection })).toHaveCount(0);
});

test("@qf:Q2 opening a section closes the others and brings its header to the top under the session header", async ({ page }) => {
  const session = await boardSession();
  await openSession(page, session);
  await openSection(page, "D");
  await expect(sectionHeader(page, "D")).toHaveAttribute("aria-expanded", "true");
  await openSection(page, "E");
  await expect(sectionHeader(page, "E")).toHaveAttribute("aria-expanded", "true");
  await expect(sectionHeader(page, "D")).toHaveAttribute("aria-expanded", "false");
  await expect(page.locator("[aria-expanded='true']").filter({ has: page.locator("text=/^E$/") })).toHaveCount(1);
  await expect
    .poll(async () => {
      const box = await sectionHeader(page, "E").boundingBox();
      return Math.round((box?.y ?? -999) - (await headerTop(page)));
    }, { message: "section E's header sits right under the sticky session header" })
    .toBeLessThanOrEqual(2);
  const above = await sectionHeader(page, "D").boundingBox();
  expect(above === null || above.y < 0, "section D stays above, reachable by scrolling up").toBe(true);
  await expect(page.getByText(S.questionsOfTotal({ answered: 18, total: 108 }))).toBeVisible();
  await expect(page.getByText(S.sectionsOfTotal({ done: 3, total: 23 }))).toBeVisible();
});

test("@qf:Q5 a section take answers every question in it and the header counts questions, not artisans", async ({ page }) => {
  const session = await boardSession();
  const detail = (await as().getSession(session.id)) as unknown as { progress?: { answeredQuestions: number; totalQuestions: number } };
  expect(detail.progress).toMatchObject({ answeredQuestions: 18, totalQuestions: 108 });
  await openSession(page, session);
  await expect(sectionHeader(page, "A")).toContainText(`${S.answeredOfTotal({ answered: 5, total: 5 })} · ${S.inSectionTake}`);
  await expect(sectionHeader(page, "D")).toContainText(S.answeredOfTotal({ answered: 6, total: 9 }));
  await expect(sectionHeader(page, "F")).toContainText(S.questionCount({ count: 5 }));
  await openSection(page, "A");
  await expect(page.getByText(S.inSectionTake)).toHaveCount(5);
});

test("@qf:Q1 @qf:Q8 the session menu leads with Record by, keeps its order, and switches online", async ({ page }) => {
  const session = await boardSession();
  await openSession(page, session);
  const menu = await sessionMenu(page);
  await expect(menu.getByText(S.recordByHelper)).toBeVisible();
  const labels = (await menu.getByRole("menuitem").allInnerTexts()).map((t) => t.trim());
  const order: string[] = [S.editSession, S.addResearcher, S.joinCodeAndQr, S.readView, S.continueAnotherDay, S.removeArtisan, S.mergeIntoAnotherSession, S.deleteSession];
  expect(labels.filter((l) => order.includes(l))).toEqual(order.filter((l) => labels.includes(l)));
  expect(labels.slice(-1)[0]).toBe(S.deleteSession);
  const patched = page.waitForResponse((r) => r.request().method() === "PATCH" && r.url().includes(`/questionnaire/sessions/${session.id}`));
  await menu.getByRole("radio", { name: S.recordByQuestion }).click();
  expect((await patched).ok()).toBe(true);
  await expect.poll(() => serverRecordBy(session.id)).toBe("QUESTION");
  await page.keyboard.press("Escape");
  await openSection(page, "F");
  await expect(page.getByRole("button", { name: S.recordSection })).toHaveCount(0);
  await expect(page.getByRole("button", { name: S.record, exact: true }).first()).toBeVisible();
});

test("@qf:Q1 a Record by change made offline is queued and reaches the server when the signal returns", async ({ page, context }) => {
  const session = await createSession({ recordBy: "QUESTION" });
  await openSession(page, session);
  await context.setOffline(true);
  const menu = await sessionMenu(page);
  await menu.getByRole("radio", { name: S.recordBySection }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByText(S.waitingForSignal).first()).toBeVisible();
  expect(await serverRecordBy(session.id)).toBe("QUESTION");
  await context.setOffline(false);
  await expect.poll(() => serverRecordBy(session.id), { timeout: 60_000 }).toBe("SECTION");
});

test("@qf:Q4 @qf:Q3 several files upload at once, a file already uploaded is flagged and skipped", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  const dup = audioFile(FILES, "E-take-already.wav", 2);
  await uploadTake(session, { sectionId: section("E") }, { bytes: new Uint8Array(readFileSync(dup.path)) });
  const first = audioFile(FILES, "E-take-1.wav", 1);
  const second = audioFile(FILES, "E-take-2.wav", 1);
  await openSession(page, session);
  await openSection(page, "E");
  const record = page.getByRole("button", { name: S.recordSection }).first();
  const upload = page.getByRole("button", { name: S.upload, exact: true }).first();
  const [r, u] = [await record.boundingBox(), await upload.boundingBox()];
  expect(Math.abs(r!.width - u!.width)).toBeLessThanOrEqual(1);
  await upload.click();
  const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
  await expect(dialog).toBeVisible();
  await dialog.locator("input[type=file]").setInputFiles([dup.path, first.path, second.path]);
  await expect(dialog.getByText(/^Already uploaded on /)).toBeVisible({ timeout: 30_000 });
  await expect(dialog.getByRole("radio", { name: S.chipSection({ code: "E" }) })).toBeChecked();
  await expect(dialog.getByText(S.countsForAllQuestions({ count: 10, code: "E" }))).toBeVisible();
  await dialog.getByRole("button", { name: S.uploadFiles({ count: 2 }) }).click();
  await expect
    .poll(async () => (await as().getSession(session.id)).recordings.length, { timeout: 90_000 })
    .toBe(3);
  const shas = (await as().getSession(session.id)).recordings.map((x) => (x as unknown as { contentSha256?: string }).contentSha256);
  expect(shas.filter((sha) => sha === dup.sha256)).toHaveLength(1);
});

test("@qf:Q3 a typed answer opens from the question's overflow and shows as a one-line preview", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  await openSession(page, session);
  await openSection(page, "E");
  await page.getByRole("button", { name: S.moreFor({ code: "E3" }) }).click();
  const items = page.getByRole("menuitem");
  await expect(items).toHaveText([S.recordThisQuestion, S.uploadForThisQuestion, S.typeAnAnswer]);
  await page.getByRole("menuitem", { name: S.typeAnAnswer }).click();
  await page.getByRole("textbox").last().fill("Splitting the block along the grain");
  await page.getByRole("button", { name: S.save, exact: true }).click();
  await expect
    .poll(async () => (await as().getSession(session.id)).answers.find((a) => a.questionLineageId === lineage("E3"))?.text)
    .toContain("Splitting the block along the grain");
  await expect(page.getByText(new RegExp(`^${escape(S.typedPreview({ text: "Splitting the block" }))}`))).toBeVisible();
  await expect(page.getByRole("switch", { name: "Type answers" })).toHaveCount(0);
});

test("@qf:Q3 recording a section runs in the dock, hides Upload, and the take keeps running in another section", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  await openSession(page, session);
  await openSection(page, "E");
  await page.getByRole("button", { name: S.recordSection }).first().click();
  await expect(page.getByText(S.recordingSection({ code: "E" }))).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: S.upload, exact: true })).toHaveCount(0);
  await openSection(page, "F");
  await expect(page.getByText(S.recordingSection({ code: "E" }))).toBeVisible();
  await page.waitForTimeout(2000);
  await page.getByRole("button", { name: S.stop, exact: true }).click();
  await expect.poll(async () => (await as().getSession(session.id)).recordings.length, { timeout: 90_000 }).toBe(1);
});

test("@qf:Q3 after Stop in a group session the who-answered sheet preselects those present and saves the take", async ({ page }) => {
  const session = await createSession({ artisans: [...GROUP], recordBy: "SECTION" });
  await openSession(page, session);
  await openSection(page, "B");
  await page.getByRole("button", { name: S.recordSection }).first().click();
  await expect(page.getByText(S.recordingSection({ code: "B" }))).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(2000);
  await page.getByRole("button", { name: S.stop, exact: true }).click();
  const sheet = page.getByRole("dialog", { name: S.whoAnsweredTitle });
  await expect(sheet).toBeVisible();
  for (const name of GROUP) await expect(sheet.getByRole("checkbox", { name: new RegExp(escape(name)) })).toBeChecked();
  await sheet.getByRole("checkbox", { name: new RegExp(escape(GROUP[2])) }).uncheck();
  await sheet.getByRole("button", { name: S.saveTake }).click();
  await expect.poll(async () => (await as().getSession(session.id)).recordings.length, { timeout: 90_000 }).toBe(1);
  const take = (await as().getSession(session.id)).recordings[0];
  const answered = take.subjects.filter((x) => x.role === "ANSWERED").map((x) => x.artisanId);
  expect(answered.sort()).toEqual([GROUP[0], GROUP[1]].map((n) => seed().artisans[n]).sort());
});

test("@qf:Q6 Review shows the question summary, what is still to ask, and Keep going returns to the session", async ({ page }) => {
  const session = await boardSession();
  await openSession(page, session);
  await page.getByRole("button", { name: S.review }).click();
  await expect(page.getByText(S.questionsAnswered({ answered: 18, total: 108 }))).toBeVisible();
  await expect(page.getByText(S.stillToAsk({ count: 20 }))).toBeVisible();
  await expect(page.getByText(S.notAskedCount({ count: 5 }))).toBeVisible();
  await page.getByRole("button", { name: S.keepGoing }).click();
  await expect(page.getByRole("button", { name: S.sessionOptions })).toBeVisible();
});

test("@qf:Q6 Finish lives on Review, and a finished session opens read only", async ({ page }) => {
  const session = await boardSession();
  await openSession(page, session);
  await expect(page.getByRole("button", { name: S.finish, exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: S.review }).click();
  const finished = page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith(`/questionnaire/sessions/${session.id}/finish`));
  await page.getByRole("button", { name: S.finish, exact: true }).click();
  const confirm = page.getByRole("dialog").getByRole("button", { name: S.finish, exact: true });
  if (await confirm.isVisible().catch(() => false)) await confirm.click();
  expect((await finished).ok()).toBe(true);
  await openSession(page, session);
  await expect(page.getByText(/^Finished on .+\. Read only\.$/)).toBeVisible();
  await openSection(page, "E");
  await expect(page.getByRole("button", { name: S.recordSection })).toHaveCount(0);
  await expect(page.getByRole("button", { name: S.upload, exact: true })).toHaveCount(0);
});

test("@qf:Q7 the sessions list leads with today's open session and filters by status with counts", async ({ page }) => {
  const open = await boardSession();
  const done = await createSession({ artisans: [GROUP[1]], date: today() });
  await as().finish(done.id);
  await page.goto("/questionnaire");
  const card = page.locator("section, article, div").filter({ hasText: S.openToday }).filter({ hasText: S.continueAt({ code: "E3" }) }).first();
  await expect(card).toBeVisible({ timeout: 30_000 });
  await expect(card).toContainText(MEERA);
  for (const label of [S.sessionsAll, S.sessionsOpen, S.sessionsFinished]) {
    await expect(page.getByRole("button", { name: new RegExp(`^${label} \\d+$`) }).or(page.getByRole("radio", { name: new RegExp(`^${label} \\d+$`) })).first()).toBeVisible();
  }
  await page.getByRole("button", { name: new RegExp(`^${S.sessionsFinished} \\d+$`) }).or(page.getByRole("radio", { name: new RegExp(`^${S.sessionsFinished} \\d+$`) })).first().click();
  await expect(page.getByText(GROUP[1]).first()).toBeVisible();
  await card.getByRole("button", { name: S.continueAt({ code: "E3" }) }).or(card.getByRole("link", { name: S.continueAt({ code: "E3" }) })).first().click();
  await expect(page).toHaveURL(new RegExp(escape(open.id)));
  await expect(sectionHeader(page, "E")).toHaveAttribute("aria-expanded", "true");
});
