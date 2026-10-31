import { gzipSync } from "node:zlib";

import { expect, test, type Page } from "@playwright/test";

import { ME, OTHER, QuestionnaireFake } from "./support/qv2Fake";

/**
 * THE QUESTIONNAIRE SCREENS ON FIELD REPOSITORY, IN A BROWSER (UNIFY 5.4, Web; stream U3).
 *
 * The real pages over a production build; every API call answered by the in-memory fake in
 * `e2e/support/qv2Fake.ts`, which keeps what each screen sent. The session screens are the shared
 * client (`components/questionnaire/**`, synced from designer-portal) wired to this portal's
 * workshops; the forms library is this portal's own. The `questionnaire-web` job in
 * `.github/workflows/checks.yml` runs it. Each test carries the feature registry's `@qf:<id>` tags.
 */

const SHOTS = process.env.E2E_SHOT_DIR ?? "test-results/questionnaire-fr-web";

async function open(page: Page, fake: QuestionnaireFake, path: string) {
  await fake.install(page);
  await page.goto(path);
}

test.describe.configure({ mode: "serial" });

test("@qf:B28 @qf:A14 the sessions list shows guests, and a content admin restores a deleted session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire");
  const list = page.locator("[data-sessions-list]");
  await expect(list).toContainText("Interview_2026-10-22_Bagru-Jaipur_Hand-Block-Printing_s-1");
  await expect(list.locator("[data-guest-chip]")).toContainText("Shopper at Johari Bazaar · Guest");
  await page.locator("[data-deleted-toggle]").click();
  await expect(list).toContainText("Interview_2026-10-20_Bagru_Deleted");
  await page.locator("[data-restore-session='s-gone']").click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-gone/restore").length).toBe(1);
});

test("@qf:A18 @qf:B31 @qf:D2 a session with only a guest, on the workshop's primary form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/new");
  const sheet = page.locator("[data-start-sheet]");
  // B30: the most recent workshop; B31 and D2: the server's form resolution for it.
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/form-for").some((c) => c.query.get("workshopId") === "w1")).toBe(true);
  await sheet.getByRole("button", { name: "Someone else" }).click();
  await sheet.locator("[data-guest-name]").fill("Shopper at Johari Bazaar");
  await expect(sheet.locator("[data-title-preview]")).toContainText("Bagru");
  await sheet.getByRole("button", { name: "Start session" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions").length, { timeout: 15_000 }).toBe(1);
  const body = fake.calledWith("POST", "/questionnaire/sessions")[0].body as Record<string, unknown>;
  expect(body).toMatchObject({ questionnaireId: "f1", workshopId: "w1", participants: [], respondents: [{ name: "Shopper at Johari Bazaar", kind: "GUEST" }] });
  expect(body).not.toHaveProperty("title");
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-new/, { timeout: 15_000 });
});

test("a form's Start session opens the start sheet on that form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/new?questionnaireId=f2");
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/form-for").some((c) => c.query.get("questionnaireId") === "f2")).toBe(true);
});

test("@qf:D4 @qf:C5 @qf:B13 @qf:D1 the grid marks recorded, typed and overridden cells, and an admin sets an override", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  const grid = page.locator("[data-coverage-grid]");
  await expect(grid.getByRole("button", { name: "A Giriraj Prasad Chhipa: Recorded" })).toHaveText("●");
  await expect(grid.getByRole("button", { name: "A Shopper at Johari Bazaar: Typed · Add a recording?" })).toHaveText("✎ 1/2");
  await expect(grid.getByRole("button", { name: "B Giriraj Prasad Chhipa: Needs review" })).toHaveText("?");
  await grid.getByRole("button", { name: "B Giriraj Prasad Chhipa: Needs review" }).click();
  await page.locator("[data-section-override]").getByRole("button", { name: "Marked complete" }).click();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/completion").length).toBe(1);
  expect(fake.calledWith("PUT", "/questionnaire/completion")[0].body).toMatchObject({ artisanId: "a1", sectionId: "s2" });
});

test("@qf:C7 a content admin merges one sitting into another session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-session-actions]").getByRole("button", { name: "Merge into…" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("[data-searchable-select]").first().click();
  await page.getByRole("option", { name: "Interview_2026-10-21_Bagru_Second-Sitting" }).click();
  await dialog.getByRole("button", { name: "Merge", exact: true }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-1/merge-into/s-2").length).toBe(1);
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-2$/);
});

test("@qf:B33 @qf:C4 a refused artisan's consent shows on the start sheet and can be switched back on", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/new?artisanId=a2");
  await expect(page.locator("[data-start-sheet]")).toContainText("Recordings of Meena are not sent to outside services: consent was refused.");
  await page.getByRole("button", { name: "Switch back on" }).click();
  await expect.poll(() => fake.calledWith("POST", "/artisans/a2/consent").length).toBe(1);
  expect(fake.calledWith("POST", "/artisans/a2/consent")[0].body).toMatchObject({ state: "GRANTED" });
});

test("@qf:A11 @qf:A20 @qf:B21 someone else's answer is locked with who answered; a group answer and its note save", async ({ page }) => {
  const fake = new QuestionnaireFake();
  fake.role = "RESEARCHER";
  fake.sessions[0].researchers = [
    { userId: OTHER.id, name: OTHER.name, role: "LEAD" },
    { userId: ME.id, name: ME.name, role: "CONTRIBUTOR" }
  ];
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.getByRole("button", { name: "A", exact: true }).click();
  await page.getByRole("switch", { name: "Type answers" }).click();
  const a1 = page.locator("[data-answer-box='l1']");
  await a1.locator("[data-searchable-select]").first().click();
  await page.getByRole("option", { name: "Giriraj Prasad Chhipa" }).click();
  await expect(a1.locator("[role=textbox]")).toHaveAttribute("aria-readonly", "true");
  await expect(a1.locator("[data-p15]")).toHaveText("Answered by Priya. Only they or a lead can change it.");
  await a1.locator("[data-history]").click();
  await expect(page.locator("[data-answer-history]")).toContainText("Wooden blocks");
  await expect(page.locator("[data-answer-history]")).toContainText("Blocks");
  await page.getByRole("button", { name: "OK" }).click();
  const a2 = page.locator("[data-answer-box='l2']");
  await a2.getByRole("button", { name: "Add a note" }).click();
  await a2.locator("[data-answer-note]").fill("Asked twice");
  await a2.locator("[role=textbox]").fill("Carved locally");
  await a2.locator("[role=textbox]").blur();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBeGreaterThan(0);
  const items = (fake.calledWith("PUT", "/questionnaire/answers").at(-1)!.body as { items: Record<string, unknown>[] }).items;
  expect(items).toEqual([
    expect.objectContaining({ artisanId: "a1", questionLineageId: "l2", text: "Carved locally", attribution: "GROUP_SHARED", note: "Asked twice" }),
    expect.objectContaining({ respondentId: "r1", questionLineageId: "l2", text: "Carved locally", attribution: "GROUP_SHARED", note: "Asked twice" })
  ]);
});

test("@qf:B22 @qf:C2 @qf:B23 @qf:C3 @qf:B18 @qf:B40 a take's transcript is offered as an answer; removed takes restore; a take has a history", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-coverage-grid]").getByRole("button", { name: "A Giriraj Prasad Chhipa: Recorded" }).click();
  const sheet = page.locator("[data-take-sheet]");
  await expect(sheet).toContainText("Nandini");
  await page.getByRole("button", { name: "History" }).first().click();
  await expect(page.locator("[data-take-history]")).toContainText("moved");
  await page.getByRole("button", { name: "Show removed takes" }).click();
  await page.locator("[data-removed-takes]").getByRole("button", { name: "Restore" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/recordings/rec-gone/restore").length).toBe(1);
  await page.locator("[data-use-take]").click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/recordings/rec-1/answer-proposals").length).toBe(1);
  const a2 = page.locator("[data-answer-box='l2']");
  await expect(a2.locator("[data-use-as-answer]")).toContainText("My uncle carves them in Pethapur.");
  await a2.locator("[data-use-as-answer]").getByRole("button", { name: "Use as answer" }).click();
  await a2.locator("[role=textbox]").focus();
  await a2.locator("[role=textbox]").blur();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBeGreaterThan(0);
  const items = (fake.calledWith("PUT", "/questionnaire/answers").at(-1)!.body as { items: Record<string, unknown>[] }).items;
  expect(items[0]).toMatchObject({ questionLineageId: "l2", text: "My uncle carves them in Pethapur.", origin: "TRANSCRIPT", sourceRecordingId: "rec-1" });
});

test("@qf:A19 @qf:B7 Finish reports the required questions unanswered and still finishes", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-session-header]").getByRole("button", { name: "Finish" }).click();
  const prompt = page.locator("[data-prompt='FINISH']");
  await expect(page.getByRole("dialog")).toContainText("required questions unanswered.");
  await prompt.getByRole("button", { name: "Finish" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-1/finish").length).toBe(1);
});

test("@qf:B6 @qf:A17 @qf:B9 a researcher is added, a guest's name corrected, and a content admin deletes the session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  const actions = page.locator("[data-session-actions]");
  await actions.getByRole("button", { name: "Add researcher" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("[data-searchable-select]").first().click();
  await page.getByRole("option", { name: "Ravi Kumar" }).click();
  await dialog.getByRole("button", { name: "Add", exact: true }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-1/researchers").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaire/sessions/s-1/researchers")[0].body).toEqual({ userId: "u-third", role: "CONTRIBUTOR" });

  await actions.locator("[data-guest='r1']").getByRole("button", { name: "Edit" }).click();
  await page.locator("[data-guest-name-edit]").fill("");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect.poll(() => fake.calledWith("PATCH", "/questionnaire/sessions/s-1/respondents/r1").length).toBe(1);

  await actions.getByRole("button", { name: "Delete session" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("Admins can restore it from Deleted.");
  await page.locator("[data-confirm-delete]").click();
  await expect.poll(() => fake.calledWith("DELETE", "/questionnaire/sessions/s-1").length).toBe(1);
  await expect(page).toHaveURL(/\/questionnaire$/);
});

test("@qf:B8 continue another day opens the next session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-session-actions]").getByRole("button", { name: "Continue another day" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Start" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/continue").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaire/sessions/continue")[0].body).toMatchObject({ fromSessionId: "s-1", artisanIds: ["a1"], split: "ONE" });
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-next$/);
});

function bundleFile(): Buffer {
  return gzipSync(
    Buffer.from(
      JSON.stringify({
        v: 2,
        bundleId: "0123456789abcdef0123456789abcdef",
        createdAt: "2026-10-22T05:00:00+05:30",
        portal: "designer-portal",
        form: { title: "2nd Craft Toolkit (copy)", description: null, kind: "WORKSHOP_INTERVIEW" },
        sections: [{ code: "A", title: "Tools", sortOrder: 1 }],
        questions: [{ sectionCode: "A", prompt: "Which tools do you use?", helpText: null, isRequired: true, artisanField: null, questionCode: null, sortOrder: 1 }]
      })
    )
  );
}

test("Open bundle reads a designer-portal bundle and Add makes it a new form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires");
  await page.getByRole("button", { name: "Open bundle" }).click();
  await page.locator("[data-bundle-input]").setInputFiles({ name: "DesignWorkshop_Questionnaire_2026-10-22_Toolkit_Handoff.dpwq", mimeType: "application/octet-stream", buffer: bundleFile() });
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Received a form");
  await expect(dialog).toContainText("'2nd Craft Toolkit (copy)' (1 section).");
  await dialog.getByRole("button", { name: "Add" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaires/bundles/import").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaires/bundles/import")[0].body).toMatchObject({ v: 2, form: { title: "2nd Craft Toolkit (copy)" } });
  await expect(page).toHaveURL(/\/questionnaires\/fnew1$/);
});

for (const theme of ["light", "dark"] as const) {
  test(`P17 at 360 px in ${theme}: two buttons on one line`, async ({ page }) => {
    const fake = new QuestionnaireFake();
    await page.setViewportSize({ width: 360, height: 780 });
    await page.emulateMedia({ colorScheme: theme });
    await open(page, fake, "/questionnaires");
    await page.getByRole("button", { name: "Open bundle" }).click();
    await page.locator("[data-bundle-input]").setInputFiles({ name: "x.frwq", mimeType: "application/octet-stream", buffer: bundleFile() });
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("button", { name: "Not now" })).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Add" })).toBeVisible();
    // The dialog panel springs in (y 10 -> 0, scale 0.98 -> 1), so two separate boundingBox() calls
    // can land on different frames of that motion and disagree by several px with the buttons on one
    // line. Read both tops in one layout pass, once the fonts the labels are set in have loaded.
    await page.evaluate(() => document.fonts.ready.then(() => undefined));
    const tops = await dialog
      .getByRole("button", { name: /^(Not now|Add)$/ })
      .evaluateAll((buttons) => buttons.map((button) => button.getBoundingClientRect().top));
    expect(tops).toHaveLength(2);
    expect(Math.abs(tops[0] - tops[1])).toBeLessThan(2);
    await page.screenshot({ path: `${SHOTS}/p17-360-${theme}.png` });
  });
}

test("the forms library: Mine, Shared and Deleted tabs, and a deleted form restored", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires");
  const tabs = page.locator("[data-forms-tabs]");
  await expect(page.getByRole("button", { name: "New form" })).toBeVisible();
  await expect(page.getByText("2nd Craft Toolkit").first()).toBeVisible();
  await tabs.getByRole("button", { name: "Shared" }).click();
  await expect(page.getByText("Market survey 2026").first()).toBeVisible();
  await tabs.getByRole("button", { name: "Deleted" }).click();
  await expect(page.getByText("Old baseline").first()).toBeVisible();
  await page.getByRole("button", { name: /Restore/ }).first().click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaires/fold/restore").length).toBe(1);
});

test("a form's page: an admin keeps it bound and sets a workshop type's default", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires/f1");
  const bindings = page.locator("[data-form-bindings]");
  await expect(bindings).toContainText("Workshops");
  await expect(page.getByRole("switch", { name: "Share with everyone" })).toBeVisible();
  await page.goto("/questionnaires");
  await page.getByRole("button", { name: /Default form for Design and prototype workshops/ }).click();
  await page.getByRole("option", { name: "2nd Craft Toolkit" }).click();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/type-defaults").length).toBe(1);
  expect(fake.calledWith("PUT", "/questionnaire/type-defaults")[0].body).toEqual({ workshopType: "DESIGN_PROTOTYPE", questionnaireId: "f1" });
});

test("@qf:C9 the start sheet takes the device's location beside the typed place", async ({ page, context }) => {
  const fake = new QuestionnaireFake();
  await context.grantPermissions(["geolocation"]);
  await context.setGeolocation({ latitude: 26.82, longitude: 75.55, accuracy: 12 });
  await open(page, fake, "/questionnaire/sessions/new?artisanId=a1");
  const sheet = page.locator("[data-start-sheet]");
  await sheet.getByRole("button", { name: "Use my location" }).click();
  await expect(sheet.locator("[data-device-location='saved']")).toContainText("Location saved (within 12 m)");
  await sheet.getByRole("button", { name: "Start session" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaire/sessions")[0].body).toMatchObject({ workshopId: "w1", location: { latitude: 26.82, longitude: 75.55, accuracy: 12 } });
});

test("@qf:B24 a question mapped to an artisan column is prefilled from the artisan's record", async ({ page }) => {
  const fake = new QuestionnaireFake();
  fake.prefill = { l2: "craft" };
  await open(page, fake, "/questionnaire/sessions/s-1");
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBe(1);
  const items = (fake.calledWith("PUT", "/questionnaire/answers")[0].body as { items: Record<string, unknown>[] }).items;
  expect(items).toEqual([expect.objectContaining({ artisanId: "a1", questionLineageId: "l2", text: "Hand Block Printing", origin: "PREFILL" })]);
});

test("@qf:C8 a formatted answer is written as the document and its plain text", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.getByRole("button", { name: "A", exact: true }).click();
  await page.getByRole("switch", { name: "Type answers" }).click();
  const box = page.locator("[data-answer-box='l2'] [role=textbox]");
  await box.click();
  await page.keyboard.type("Indigo");
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.press("ControlOrMeta+B");
  await box.blur();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBeGreaterThan(0);
  const item = (fake.calledWith("PUT", "/questionnaire/answers").at(-1)!.body as { items: Record<string, unknown>[] }).items[0];
  expect(item.text).toBe("Indigo");
  expect(String(item.richText)).toContain("BOLD");
  expect(String(item.richText)).toContain("Indigo");
});

test("@qf:B12 workshop coverage reads the workshop", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/coverage");
  await expect(page.locator("[data-coverage-grid]")).toBeVisible();
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/coverage").some((c) => c.query.get("workshopId") === "w1")).toBe(true);
});

// ── Recording chips and the shared filters (owner, 31 Oct 2026) ───────────────────────────────

/** Types into a filter's searchable select and picks a row the server's typeahead answered. */
async function typeahead(page: Page, fake: QuestionnaireFake, scope: string, field: string, apiField: string, term: string, option: RegExp) {
  await page.locator(`${scope} [data-filter-field='${field}'] button`).first().click();
  await page.locator("[data-anchored-popover] input").last().fill(term);
  await expect
    .poll(() => fake.calledWith("GET", "/questionnaire/filters").some((c) => c.query.get("field") === apiField && c.query.get("q") === term))
    .toBe(true);
  await page.getByRole("option", { name: option }).click();
  await page.keyboard.press("Escape");
}

test("@qf:E3 @qf:E6 the sessions list searches and filters on the server and keeps both in the URL", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire");
  const list = page.locator("[data-sessions-list]");
  await expect(list).toContainText("Hand-Block-Printing_s-1");
  await page.locator("[data-filter-bar]").getByRole("searchbox").fill("s-1");
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/sessions").some((c) => c.query.get("q") === "s-1")).toBe(true);
  await expect(page).toHaveURL(/[?&]q=s-1/);
  await page.locator("[data-filters-toggle]").click();
  await page.locator("[data-filter-pill='transcript=HAS']").click();
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/sessions").some((c) => c.query.get("transcript") === "HAS")).toBe(true);
  fake.calls.length = 0;
  await page.reload();
  await expect(page.locator("[data-filter-bar]").getByRole("searchbox")).toHaveValue("s-1");
  await expect(page.locator("[data-active-filter='transcript=HAS']")).toBeVisible();
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/sessions").some((c) => c.query.get("transcript") === "HAS" && c.query.get("q") === "s-1")).toBe(true);
  await list.locator("[data-chip-filter='workshopId=w1']").first().click();
  await expect(page).toHaveURL(/workshopId=w1/);
  // The artisans select searches the server as the reader types, and its pick filters the list.
  await page.locator("[data-filters-toggle]").click();
  await typeahead(page, fake, "[data-filters-panel]", "artisanId", "artisans", "Mee", /Meena Devi/);
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/sessions").some((c) => c.query.get("artisanId") === "a2")).toBe(true);
  await expect(page).toHaveURL(/artisanId=a2/);
});

test("@qf:E1 @qf:E2 @qf:E5 @qf:E6 the recordings list shows each take's chips, a tap filters by one, and Back keeps it", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/recordings");
  const row = page.locator("[data-recording-row='rec-1']");
  const chips = row.locator("[data-recording-chips]");
  await expect(chips).toContainText("A · Tools");
  await expect(chips).toContainText("2nd Craft Toolkit");
  await expect(chips).toContainText("Meena Devi · present");
  await expect(chips).toContainText("22 Oct 2026");
  await expect(chips).toContainText("Bagru block printing 2026");
  await expect(chips).toContainText("1:32");
  await chips.locator("[data-chip-filter='workshopId=w1']").click();
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/recordings").some((c) => c.query.get("workshopId") === "w1")).toBe(true);
  await expect(page.locator("[data-active-filter='workshopId=w1']")).toContainText("Bagru block printing 2026");
  await page.locator("[data-filter-bar]").getByRole("searchbox").fill("Pethapur");
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/recordings").some((c) => c.query.get("q") === "Pethapur" && c.query.get("workshopId") === "w1")).toBe(true);
  await row.getByRole("link").first().click();
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-1\/view/);
  await page.goBack();
  await expect(page).toHaveURL(/workshopId=w1/);
  await expect(page.locator("[data-active-filter='workshopId=w1']")).toBeVisible();
});

test("@qf:E1 @qf:E2 a take's chips show on the take sheet and the read view, and lead to the recordings list", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-coverage-grid]").getByRole("button", { name: "A Giriraj Prasad Chhipa: Recorded" }).click();
  await expect(page.locator("[data-take-row='rec-1'] [data-recording-chips]")).toContainText("A · Tools");
  await page.goto("/questionnaire/sessions/s-1/view");
  const chips = page.locator("[data-recording-chips]").first();
  await expect(chips).toContainText("Giriraj Prasad Chhipa");
  await chips.locator("[data-chip-filter='sectionId=s1']").click();
  await expect(page).toHaveURL(/\/questionnaire\/recordings\?sectionId=s1$/);
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/recordings").some((c) => c.query.get("sectionId") === "s1")).toBe(true);
  await expect(page.locator("[data-active-filter='sectionId=s1']")).toContainText("A · Tools");
});

test("@qf:E4 the forms library and a form's own sessions narrow by the shared filters", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires?artisanId=a1");
  await expect.poll(() => fake.calledWith("GET", "/questionnaires").some((c) => c.query.get("artisanId") === "a1")).toBe(true);
  await expect(page.locator("[data-active-filter='artisanId=a1']")).toContainText("Giriraj Prasad Chhipa");
  await page.locator("[data-filter-bar]").getByRole("searchbox").fill("dyes");
  await expect.poll(() => fake.calledWith("GET", "/questionnaires").some((c) => c.query.get("q") === "dyes" && c.query.get("artisanId") === "a1")).toBe(true);
  await page.goto("/questionnaires/f1");
  const list = page.locator("[data-form-session-list]");
  await expect(list).toContainText("Hand-Block-Printing_s-1");
  await page.locator("[data-filters-toggle]").click();
  await page.locator("[data-filter-pill='source=UPLOAD']").click();
  await expect
    .poll(() => fake.calledWith("GET", "/questionnaire/sessions").some((c) => c.query.get("questionnaireId") === "f1" && c.query.get("source") === "UPLOAD"))
    .toBe(true);
  await expect(page).toHaveURL(/\/questionnaires\/f1\?source=UPLOAD/);
  // A form's sessions: the researcher select is a server typeahead too.
  await typeahead(page, fake, "[data-filters-panel]", "researcherId", "researchers", "Priya", /Priya Shah/);
  await expect
    .poll(() => fake.calledWith("GET", "/questionnaire/sessions").some((c) => c.query.get("questionnaireId") === "f1" && c.query.get("researcherId") === "u-other"))
    .toBe(true);
});

test("@qf:E5 the media list carries a take's chips and takes the questionnaire filters", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/media?sectionId=s1");
  await expect.poll(() => fake.calledWith("GET", "/media").some((c) => c.query.get("sectionId") === "s1")).toBe(true);
  const chips = page.locator("[data-recording-chips]:visible").first();
  await expect(chips).toContainText("A · Tools");
  await chips.locator("[data-chip-filter='artisanId=a1']").click();
  await expect.poll(() => fake.calledWith("GET", "/media").some((c) => c.query.get("artisanId") === "a1" && c.query.get("sectionId") === "s1")).toBe(true);
  await expect(page).toHaveURL(/artisanId=a1/);
});
