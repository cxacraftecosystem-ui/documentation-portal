import { readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { expect, test, type Locator, type Page } from "@playwright/test";

import {
  GROUP,
  INTERVIEW_API,
  INTERVIEW_SEED_MISSING,
  LALITA,
  MEERA,
  S,
  artisanIn,
  as,
  audioFile,
  boardSession,
  createSession,
  escape,
  fillStartSheet,
  holdUploads,
  lineage,
  openSection,
  openSession,
  raw,
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
  return page.getByRole("dialog", { name: S.sessionOptions });
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
  const group = sheet.getByRole("group", { name: S.fieldRecordBy });
  await expect(group.getByRole("button", { name: S.recordBySection, exact: true })).toHaveAttribute("aria-pressed", "true");
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
  const sheet = await fillStartSheet(page, [LALITA]);
  await sheet.getByRole("group", { name: S.fieldRecordBy }).getByRole("button", { name: S.recordByQuestion, exact: true }).click();
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
  await expect(page.getByText(S.inSectionTake, { exact: true })).toHaveCount(5);
});

test("@qf:Q1 @qf:Q8 the session menu leads with Record by, keeps its order, and switches online", async ({ page, context }) => {
  await signInAs(context, "admin");
  const session = await boardSession();
  await openSession(page, session);
  const menu = await sessionMenu(page);
  await expect(menu.getByText(S.recordByHelper)).toBeVisible();
  const labels = (await menu.getByRole("menuitem").allInnerTexts()).map((t) => t.trim());
  const order: string[] = [S.editSession, S.addResearcher, S.joinCodeAndQr, S.readView, S.continueAnotherDay, S.removeArtisan, S.mergeIntoAnotherSession, S.deleteSession];
  expect(labels.filter((l) => order.includes(l))).toEqual(order.filter((l) => labels.includes(l)));
  expect(labels.slice(-1)[0]).toBe(S.deleteSession);
  const patched = page.waitForResponse((r) => r.request().method() === "PATCH" && r.url().includes(`/questionnaire/sessions/${session.id}`));
  await menu.getByRole("group", { name: S.fieldRecordBy }).getByRole("button", { name: S.recordByQuestion, exact: true }).click();
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
  await page.getByRole("alertdialog").getByRole("button", { name: "Continue offline" }).click();
  const menu = await sessionMenu(page);
  await menu.getByRole("group", { name: S.fieldRecordBy }).getByRole("button", { name: S.recordBySection, exact: true }).click();
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
  const original = (await as().getSession(session.id)).recordings[0]?.id;
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
  await expect(dialog.getByRole("radio", { name: S.attachSections, exact: true })).toBeChecked();
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.countsForSections({ codes: "E" }));
  await dialog.getByRole("button", { name: S.uploadFiles({ count: 2 }) }).click();
  const again = page.getByRole("dialog", { name: S.alreadyRecordedCode({ code: "E" }) });
  await expect(again).toBeVisible();
  await again.getByRole("button", { name: S.uploadAgain, exact: true }).click();
  await expect
    .poll(async () => (await as().getSession(session.id)).recordings.length, { timeout: 90_000 })
    .toBe(3);
  const takes = (await as().getSession(session.id)).recordings.map((x) => x.id);
  const found = await as().shaLookup([dup.sha256, first.sha256, second.sha256], session.id);
  expect(found.find((m) => m.sha === dup.sha256)?.recordingId, "the duplicate stays the earlier take").toBe(original);
  for (const file of [first, second]) {
    const match = found.find((m) => m.sha === file.sha256);
    expect(match?.sameSession, `${file.path} landed in this session`).toBe(true);
    expect(takes).toContain(match?.recordingId);
  }
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
  expect(answered.sort()).toEqual([GROUP[0], GROUP[1]].map((n) => artisanIn(session, n)).sort());
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
  const confirmed = confirm
    .waitFor({ state: "visible" })
    .then(() => confirm.click())
    .catch(() => undefined);
  const response = await Promise.race([finished, confirmed.then(() => finished)]);
  expect(response.ok()).toBe(true);
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

test("a designer plays a recorded take in place through the Data explorer's own routes, and starting a recording collapses it", async ({ page }) => {
  const session = await boardSession();
  const detail = await as().getSession(session.id);
  const take = detail.recordings.find((r) => r.targets.some((t) => t.questionLineageId === lineage("E2")))!;
  const asked: { path: string; status: number }[] = [];
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (path.includes(`/data/media/${take.mediaFileId}/`) || path.endsWith(`/transcripts/${take.mediaFileId}`)) asked.push({ path, status: response.status() });
  });
  await openSession(page, session);
  await openSection(page, "E");
  const e2 = page.locator("[data-question='E2']");
  await e2.locator("[data-take-play]").click();
  const region = e2.locator("[data-take-region]");
  await expect(region.getByTestId("preview-controls")).toBeVisible({ timeout: 30_000 });
  await expect(region.locator("audio")).toHaveAttribute("src", `/api/data/media/${take.mediaFileId}/preview`);
  await expect.poll(() => asked.filter((a) => a.path.endsWith("/peaks")).length, { timeout: 30_000 }).toBeGreaterThan(0);
  await expect.poll(() => asked.filter((a) => a.path.endsWith("/preview")).length, { timeout: 30_000 }).toBeGreaterThan(0);
  expect(asked.filter((a) => a.status === 401 || a.status === 403), "the explorer's routes let the take's researcher in").toEqual([]);
  await page.getByRole("button", { name: S.recordSection }).first().click();
  await expect(page.getByText(S.recordingSection({ code: "E" }))).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("[data-take-region]")).toHaveCount(0);
  await page.getByRole("button", { name: S.stop, exact: true }).click();
  await page.goto(`/questionnaire/sessions/${encodeURIComponent(session.id)}/view?recording=${encodeURIComponent(take.id)}`);
  await expect(page.locator(`[data-take-region='${take.id}']`).getByTestId("preview-controls")).toBeVisible({ timeout: 30_000 });
});

async function uploadThroughSheet(page: Page, opener: Locator, files: string[], confirm: string) {
  await opener.click();
  const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
  await expect(dialog).toBeVisible();
  await dialog.locator("input[type=file]").setInputFiles(files);
  const button = dialog.getByRole("button", { name: confirm });
  await expect(button).toBeEnabled({ timeout: 30_000 });
  return { dialog, button };
}

test("@qf:Q4 a whole-session upload shows at once in the Whole session block, uploads in place and is confirmed", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  await uploadTake(session, null, { durationMs: 95_000 });
  const file = audioFile(FILES, "Whole-session-earlier.wav", 2);
  await openSession(page, session);
  const whole = page.locator("[data-whole-session]");
  await expect(whole.getByRole("heading", { name: S.wholeSession })).toBeVisible();
  await expect(whole.locator("[data-whole-take]")).toHaveCount(1);
  await expect(whole.locator("[data-whole-session-count]")).toHaveText(S.takeCountOne);
  const pair = whole.getByRole("group", { name: S.wholeSessionActions });
  const [record, upload] = [pair.getByRole("button", { name: S.record, exact: true }), pair.getByRole("button", { name: S.upload, exact: true })];
  const [r, u] = [await record.boundingBox(), await upload.boundingBox()];
  expect(Math.abs(r!.width - u!.width), "Record and Upload are an equal pair").toBeLessThanOrEqual(1);
  expect(Math.abs(r!.height - u!.height)).toBeLessThanOrEqual(1);
  expect(Math.round(r!.y)).toBe(Math.round(u!.y));
  await expect(record).toHaveClass(/field-button/);
  const release = await holdUploads(page);
  const { dialog, button } = await uploadThroughSheet(page, upload, [file.path], S.uploadOneFile);
  await expect(dialog.getByRole("radio", { name: S.wholeSession })).toBeChecked();
  await button.click();
  await expect(dialog).toBeHidden();
  const rows = whole.locator("[data-take-row-in-place]");
  await expect(rows).toHaveCount(2, { timeout: 15_000 });
  const uploading = whole.locator("[data-take-upload='UPLOADING']");
  await expect(uploading).toHaveCount(1);
  await expect(uploading.locator(".progress-track")).toBeVisible();
  await expect(whole.locator("[data-whole-take]").last()).toContainText(S.wholeSession);
  await expect(uploading).toBeInViewport();
  release();
  await expect(page.getByText(S.uploadedOne({ target: S.wholeSessionTarget }))).toBeVisible({ timeout: 90_000 });
  await expect(whole.locator("[data-take-upload]")).toHaveCount(0);
  await expect(whole.locator("[data-whole-session-count]")).toHaveText(S.takeCount({ count: 2 }));
  const detail = await as().getSession(session.id);
  expect(detail.recordings.filter((x) => x.targets.length === 0), "both takes are filed to the whole session").toHaveLength(2);
  await whole.locator("[data-take-play]").last().click();
  await expect(whole.locator("[data-take-region]")).toHaveCount(1);
  await expect(whole.locator("[data-take-region]").getByTestId("preview-controls")).toBeVisible({ timeout: 30_000 });
});

test("@qf:Q4 an upload to a question of a closed section opens that section and shows the row where it was attached", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  const file = audioFile(FILES, "E2-from-section-A.wav", 1);
  await openSession(page, session);
  await expect(sectionHeader(page, "E")).toHaveAttribute("aria-expanded", "false");
  const release = await holdUploads(page);
  await page.locator("[data-whole-session]").getByRole("button", { name: S.upload, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
  await dialog.locator("input[type=file]").setInputFiles([file.path]);
  await dialog.getByRole("radio", { name: S.attachQuestion }).click();
  await dialog.getByRole("button", { name: S.attachQuestion }).click();
  await page.keyboard.type("E2");
  await page.getByRole("option", { name: /^E2 / }).first().click();
  await dialog.getByRole("button", { name: S.uploadOneFile }).click();
  await expect(sectionHeader(page, "E")).toHaveAttribute("aria-expanded", "true", { timeout: 15_000 });
  const e2 = page.locator("[data-question='E2']");
  const row = e2.locator("[data-take-row-in-place]").first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(e2.locator("[data-take-upload]")).toHaveCount(1);
  await expect(row).toBeInViewport();
  const headerBottom = await headerTop(page);
  expect((await row.boundingBox())!.y, "clear of the sticky session header").toBeGreaterThanOrEqual(headerBottom - 1);
  release();
  await expect(page.getByText(S.uploadedOne({ target: "E2" }))).toBeVisible({ timeout: 90_000 });
  await expect(e2.locator("[data-question-state='QUESTION_TAKE']")).toBeVisible();
  await expect(e2.locator("[data-take-upload]")).toHaveCount(0);
});

test("@qf:Q4 the Upload sheet says where a duplicate is: this list, this device, this session or another, and Open goes there", async ({ page }) => {
  const other = await createSession({ artisans: [LALITA] });
  const theirs = audioFile(FILES, "Sandesh-13-Nov.wav", 2);
  await uploadTake(other, { sectionId: section("B") }, { bytes: new Uint8Array(readFileSync(theirs.path)), artisans: [LALITA] });
  const session = await createSession({ recordBy: "SECTION" });
  const mine = audioFile(FILES, "Meera-D-earlier.wav", 2);
  await uploadTake(session, { sectionId: section("D") }, { bytes: new Uint8Array(readFileSync(mine.path)) });
  const sending = audioFile(FILES, "Meera-still-sending.wav", 1);
  const fresh = audioFile(FILES, "Meera-fresh.wav", 1);
  const twice = join(FILES, "Meera-fresh-copy.wav");
  writeFileSync(twice, readFileSync(fresh.path));
  await openSession(page, session);
  await openSection(page, "E");
  const release = await holdUploads(page);
  const first = await uploadThroughSheet(page, page.getByRole("button", { name: S.upload, exact: true }).first(), [sending.path], S.uploadOneFile);
  await first.button.click();
  await expect(page.locator("[data-section-takes='E'] [data-take-upload='UPLOADING']")).toHaveCount(1, { timeout: 15_000 });
  await page.getByRole("button", { name: S.upload, exact: true }).first().click();
  const dialog = page.getByRole("dialog", { name: S.uploadRecordings });
  await dialog.locator("input[type=file]").setInputFiles([mine.path, theirs.path, sending.path, fresh.path, twice]);
  const row = (name: string) => dialog.locator(`[data-upload-row='${name}']`);
  await expect(row("Meera-D-earlier.wav").locator("[data-already-uploaded]")).toHaveText(/^Already uploaded on /, { timeout: 30_000 });
  await expect(row("Meera-D-earlier.wav").locator("[data-duplicate-where]")).toContainText(S.duplicateHere);
  await expect(row("Sandesh-13-Nov.wav").locator("[data-already-uploaded]")).toHaveText(/^Already uploaded on /);
  await expect(row("Sandesh-13-Nov.wav").locator("[data-duplicate-where]")).toContainText(S.duplicateIn({ title: other.title }));
  await expect(row("Meera-still-sending.wav").locator("[data-already-uploaded]")).toHaveText(S.duplicateQueued);
  await expect(row("Meera-fresh-copy.wav").locator("[data-already-uploaded]")).toHaveText(S.duplicateInList);
  await expect(row("Meera-fresh.wav").locator("[data-already-uploaded]")).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: S.uploadOneFile }), "only the fresh file uploads").toBeEnabled();
  await row("Meera-D-earlier.wav").getByRole("button", { name: S.openDuplicateNamed({ name: "Meera-D-earlier.wav" }) }).click();
  await expect(dialog).toBeHidden();
  const earlier = (await as().getSession(session.id)).recordings.find((x) => x.targets.some((t) => t.sectionId === section("D")))!;
  await expect(sectionHeader(page, "D")).toHaveAttribute("aria-expanded", "true", { timeout: 15_000 });
  await expect(page.locator(`[data-take-region='${earlier.clientClipKey}']`)).toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: S.upload, exact: true }).first().click();
  await dialog.locator("input[type=file]").setInputFiles([theirs.path]);
  await row("Sandesh-13-Nov.wav").getByRole("button", { name: S.openDuplicateNamed({ name: "Sandesh-13-Nov.wav" }) }).click({ timeout: 30_000 });
  await expect(page).toHaveURL(new RegExp(`/questionnaire/sessions/${escape(other.id)}\\?recording=`), { timeout: 30_000 });
  await expect(sectionHeader(page, "B")).toHaveAttribute("aria-expanded", "true", { timeout: 30_000 });
  release();
});

test("@qf:Q6 leaving with a take still uploading says it keeps going in this tab, and it lands after the user leaves", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  const file = audioFile(FILES, "Leave-while-sending.wav", 1);
  await openSession(page, session);
  await openSection(page, "C");
  const release = await holdUploads(page);
  const { button } = await uploadThroughSheet(page, page.getByRole("button", { name: S.upload, exact: true }).first(), [file.path], S.uploadOneFile);
  await button.click();
  await expect(page.locator("[data-section-takes='C'] [data-take-upload='UPLOADING']")).toHaveCount(1, { timeout: 15_000 });
  await page.getByRole("link", { name: S.backToSessions }).click();
  const prompt = page.getByRole("dialog", { name: S.leaveTitle }).or(page.getByRole("alertdialog", { name: S.leaveTitle }));
  await expect(prompt).toBeVisible();
  await expect(prompt).toContainText(S.leaveBodyWebOne);
  await expect(prompt).not.toContainText(/background/i);
  await prompt.getByRole("button", { name: S.leave, exact: true }).click();
  await expect(page).toHaveURL(/\/questionnaire$/, { timeout: 30_000 });
  release();
  await expect
    .poll(async () => (await as().getSession(session.id)).recordings.length, { timeout: 90_000, message: "the take kept uploading after leaving the session" })
    .toBe(1);
});

test("@qf:Q7 the recordings list orders by upload by default, by recording on request, and remembers the choice", async ({ page }) => {
  const session = await createSession({ artisans: [GROUP[2]] });
  const artisanId = session.participants[0].artisanId;
  const recordedFirst = await uploadTake(session, { sectionId: section("A") }, { artisans: [GROUP[2]], capturedAt: new Date().toISOString() });
  const recordedEarlier = await uploadTake(session, { sectionId: section("B") }, { artisans: [GROUP[2]], capturedAt: "2026-11-01T05:00:00.000Z" });
  const ids = async (sort: "added" | "recorded") => (await as().listRecordings({ artisanId: [artisanId] }, null, null, 30, sort)).items.map((x) => x.clientClipKey);
  expect(await ids("added"), "newest upload first").toEqual([recordedEarlier.clientClipKey, recordedFirst.clientClipKey]);
  expect(await ids("recorded"), "newest capture first").toEqual([recordedFirst.clientClipKey, recordedEarlier.clientClipKey]);
  const keyOf = new Map((await as().getSession(session.id)).recordings.map((x) => [x.id, x.clientClipKey] as const));
  const shown = async () => (await page.locator("[data-recording-row]").evaluateAll((rows) => rows.map((row) => row.getAttribute("data-recording-row") ?? ""))).map((id) => keyOf.get(id));
  await page.goto(`/questionnaire/recordings?artisanId=${encodeURIComponent(artisanId)}`);
  const toggle = page.getByRole("group", { name: S.sortRecordingsBy });
  await expect(toggle.getByRole("button", { name: S.sortAdded, exact: true })).toHaveAttribute("aria-pressed", "true", { timeout: 30_000 });
  await expect.poll(shown, { timeout: 30_000 }).toEqual([recordedEarlier.clientClipKey, recordedFirst.clientClipKey]);
  const asked = page.waitForRequest((r) => r.url().includes("/questionnaire/recordings") && r.url().includes("sort=recorded"));
  await toggle.getByRole("button", { name: S.sortRecorded, exact: true }).click();
  await asked;
  await expect.poll(shown, { timeout: 30_000 }).toEqual([recordedFirst.clientClipKey, recordedEarlier.clientClipKey]);
  await page.reload();
  await expect(page.getByRole("group", { name: S.sortRecordingsBy }).getByRole("button", { name: S.sortRecorded, exact: true })).toHaveAttribute("aria-pressed", "true", { timeout: 30_000 });
  await expect.poll(shown, { timeout: 30_000 }).toEqual([recordedFirst.clientClipKey, recordedEarlier.clientClipKey]);
  await page.getByRole("group", { name: S.sortRecordingsBy }).getByRole("button", { name: S.sortAdded, exact: true }).click();
  await expect.poll(shown, { timeout: 30_000 }).toEqual([recordedEarlier.clientClipKey, recordedFirst.clientClipKey]);
});

async function chooseSections(page: Page, scope: Locator, codes: string[]) {
  await scope.getByRole("button", { name: S.attachSections }).click();
  for (const code of codes) await page.getByRole("option", { name: new RegExp(`^${escape(S.chipSection({ code }))} ·`) }).click();
  await page.keyboard.press("Escape");
}

test("@qf:Q4 an upload can attach to several sections: it lists under each one and counts all their questions", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  const file = audioFile(FILES, "Sections-A-and-C.wav", 1);
  await openSession(page, session);
  const whole = page.locator("[data-whole-session]");
  const { dialog, button } = await uploadThroughSheet(page, whole.getByRole("button", { name: S.upload, exact: true }), [file.path], S.uploadOneFile);
  await expect(dialog.getByRole("radio", { name: S.wholeSession })).toBeChecked();
  await expect(dialog.locator("[data-sections-chooser]")).toHaveCount(0);
  await dialog.getByRole("radio", { name: S.attachSections, exact: true }).click();
  await expect(button, "Sections with none chosen uploads nothing").toBeDisabled();
  await chooseSections(page, dialog, ["C", "A"]);
  await expect(dialog).toBeVisible();
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.countsForSections({ codes: "A, C" }));
  await expect(dialog.getByText(/\(\d+ selected\)/)).toHaveCount(0);
  await button.click();
  await expect(page.getByText(S.uploadedOne({ target: S.sectionsLabel({ codes: "A, C" }) }))).toBeVisible({ timeout: 90_000 });
  const take = (await as().getSession(session.id)).recordings[0];
  expect(take.targets.map((x) => x.sectionId).sort(), "one section target per chosen section").toEqual([section("A"), section("C")].sort());
  await expect(sectionHeader(page, "A")).toHaveAttribute("aria-expanded", "true");
  await expect(page.locator("[data-section-body='A'] [data-question-state='SECTION_TAKE']").first()).toBeVisible();
  await expect(sectionHeader(page, "C")).toContainText(S.inSectionTake);
  await expect(whole.locator("[data-whole-take]")).toHaveCount(0);
  await openSection(page, "C");
  await expect(sectionHeader(page, "A")).toContainText(S.inSectionTake);
  await expect(page.locator("[data-section-takes='C'] [data-take-row-in-place]")).toHaveCount(1);
  await expect(page.locator("[data-section-body='C'] [data-question-state='SECTION_TAKE']").first()).toBeVisible();
});

test("@qf:Q4 Change sections moves a whole-session take into sections, a stale version is reloaded and said, and clearing them moves it back", async ({ page }) => {
  const session = await createSession({ recordBy: "SECTION" });
  await uploadTake(session, null, { durationMs: 70_000 });
  const take = (await as().getSession(session.id)).recordings[0];
  await openSession(page, session);
  const whole = page.locator("[data-whole-session]");
  await expect(whole.locator("[data-whole-take]")).toHaveCount(1);
  await whole.locator("[data-change-sections-open]").click();
  const dialog = page.getByRole("dialog", { name: S.changeSections });
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.marksNoSection);
  await chooseSections(page, dialog, ["B", "D"]);
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.countsForSections({ codes: "B, D" }));
  await as().patchRecording(take.id, { version: take.version, targets: [] });
  await dialog.locator("[data-change-sections-save]").click();
  await expect(dialog.locator("[data-change-sections-notice]")).toHaveText(S.takeChangedReloaded);
  await dialog.locator("[data-change-sections-save]").click();
  await expect(dialog).toBeHidden({ timeout: 30_000 });
  await expect(sectionHeader(page, "B")).toHaveAttribute("aria-expanded", "true");
  await expect(page.locator("[data-section-body='B'] [data-question-state='SECTION_TAKE']").first()).toBeVisible();
  await expect(sectionHeader(page, "D")).toContainText(S.inSectionTake);
  await expect(whole.locator("[data-whole-take]")).toHaveCount(0);
  const moved = (await as().getSession(session.id)) as unknown as { recordings: { targets: { sectionId: string | null }[] }[]; progress?: { answeredQuestions: number } };
  expect(moved.recordings[0].targets.map((x) => x.sectionId).sort()).toEqual([section("B"), section("D")].sort());
  expect(moved.progress?.answeredQuestions ?? 0, "the session's progress counts sections B and D").toBeGreaterThan(0);

  const row = page.locator("[data-section-takes='B'] [data-take-row-in-place]");
  await row.locator("[data-change-sections-open]").click();
  await expect(dialog.getByRole("button", { name: S.attachSections })).toContainText("2 selected");
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.countsForSections({ codes: "B, D" }));
  for (const code of ["B", "D"]) await dialog.getByRole("button", { name: new RegExp(`^Remove ${escape(S.chipSection({ code }))}`) }).click();
  await expect(dialog.locator("[data-sections-helper]")).toHaveText(S.marksNoSection);
  await dialog.locator("[data-change-sections-save]").click();
  await expect(dialog).toBeHidden({ timeout: 30_000 });
  await expect(whole.locator("[data-whole-take]")).toHaveCount(1);
  await expect(whole.locator("[data-whole-take]")).toBeInViewport();
  expect((await as().getSession(session.id)).recordings[0].targets, "clearing every section moves it back to the whole session").toEqual([]);
});

test("@qf:Q6 every request says what this browser's outbox holds, and the admin list shows the phones holding recordings", async ({ page, browser }) => {
  const session = await createSession({ recordBy: "SECTION" });
  const file = audioFile(FILES, "Held-on-this-browser.wav", 1);
  await openSession(page, session);
  await openSection(page, "B");
  const release = await holdUploads(page);
  const { button } = await uploadThroughSheet(page, page.getByRole("button", { name: S.upload, exact: true }).first(), [file.path], S.uploadOneFile);
  await button.click();
  await expect(page.locator("[data-section-takes='B'] [data-take-upload='UPLOADING']")).toHaveCount(1, { timeout: 15_000 });
  const reported = page.waitForRequest((r) => new URL(r.url()).pathname.startsWith("/api/") && r.headers()["x-outbox-pending"] === "1", { timeout: 60_000 });
  await page.getByRole("button", { name: S.review }).first().click();
  const headers = (await reported).headers();
  expect(headers["x-install-id"]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  expect(headers["x-outbox-failed"]).toBe("0");
  expect(Number.isNaN(Date.parse(headers["x-outbox-oldest"] ?? ""))).toBe(false);
  release();

  const install = `e2e-${Date.now().toString(36)}-phone`;
  const sent = await fetch(`${INTERVIEW_API}/api/questionnaire/sessions?limit=1`, {
    headers: {
      Authorization: `Bearer ${seed().tokens.other}`,
      "X-App-Platform": "android",
      "X-App-Version": "0.0.7",
      "X-App-Version-Code": "27",
      "X-Install-Id": install,
      "X-Outbox-Pending": "4",
      "X-Outbox-Failed": "1",
      "X-Outbox-Oldest": "2026-11-10T08:00:00Z"
    }
  });
  expect(sent.ok).toBe(true);
  type Holding = { items: { installId: string; pending: number; failed: number; platform: string; oldestAt: string | null; name: string }[] };
  await expect
    .poll(async () => (await raw<Holding>("admin", "GET", "/admin/outbox-reports")).items.find((i) => i.installId === install) ?? null, { timeout: 30_000 })
    .toMatchObject({ pending: 4, failed: 1, platform: "android", oldestAt: "2026-11-10T08:00:00Z", name: seed().users.other.name });
  const listed = (await raw<Holding>("admin", "GET", "/admin/outbox-reports")).items.map((i) => i.oldestAt ?? "");
  expect(listed, "oldest first").toEqual([...listed].sort((a, b) => (a && b ? a.localeCompare(b) : a ? -1 : 1)));

  const admin = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  try {
    await signInAs(admin, "admin");
    const adminPage = await admin.newPage();
    await adminPage.goto("/admin");
    const row = adminPage.locator(`[data-phone-row='${install}']`);
    await expect(adminPage.getByRole("heading", { name: S.phonesHoldingTitle })).toBeVisible({ timeout: 30_000 });
    await expect(row).toContainText(S.holdingWaiting({ count: 4 }));
    await expect(row).toContainText(S.holdingFailed({ count: 1 }));
    await expect(row).toContainText(seed().users.other.name);
    await expect(row).toContainText("Android");
  } finally {
    await admin.close();
  }
});
