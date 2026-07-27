import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * A DROPDOWN MUST OFFER NAMES, NOT DATABASE IDS.
 *
 * `optionsFromChildren` in components/FormControls accepted a label only when `props.children` was a
 * SINGLE string or number. Every label in this app is interpolated —
 * `{artisan.name} · {artisan.place}` — which compiles to an ARRAY of children, so the test failed
 * and the label fell through to `String(props.value)`: the record's CUID. Three live dropdowns
 * offered `cmg...` where a name belonged.
 *
 * WHY IT MATTERED MOST ON /questionnaire. The primary artisan decides `artisanSetKey` — which
 * interview a submission folds into — so choosing the wrong row merges one interview into another
 * artisan's set. It is a required field, and it was asking a researcher to recognise an artisan
 * from twenty-five random characters.
 *
 * WHAT THIS SPEC ACTUALLY PINS, because "no CUIDs" alone would pass on an empty list: each case
 * asserts BOTH that nothing reads as an id AND that the real label shape is present — the
 * separator the page composes its rows with. The two together cannot be satisfied by a dropdown
 * that has quietly stopped rendering its options.
 *
 * Read-only throughout: forms are opened and dropdowns are unfolded, nothing is submitted.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * A bare token long enough to be a generated id and containing nothing a person would read: no
 * space, no separator. Deliberately not anchored to the "c" of a CUID — a Postgres UUID or a nanoid
 * arriving in a label is the same defect and should fail the same way.
 */
const LOOKS_LIKE_AN_ID = /^[a-z0-9-]{16,}$/i;

/**
 * The dropdown trigger inside the field whose label is exactly `label`.
 *
 * Same idea as `fixtures/location`'s `fieldSelect` — matched through the `.field-label` span, since
 * `FormControls.Field` wraps every control in a `<label>` and the button's accessible name would
 * therefore be the field label glued to the button's own text — but the label is ESCAPED before it
 * becomes a pattern. "Whose activity?" is one of the three fields under test and its question mark
 * is a quantifier: unescaped, `^Whose activity?$` matches "Whose activit" and nothing on the page,
 * so this spec silently skipped the very case it was written for and reported green.
 */
function fieldSelect(page: Page, label: string): Locator {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: new RegExp(`^${escaped}( \\*)?$`) }) })
    .locator("[data-searchable-select]");
}

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/**
 * Everything the named dropdown is offering, read from the open panel and then folded away.
 *
 * Waits for a row BEYOND the placeholder before reading. Every one of these lists is fetched after
 * the form mounts, so a panel opened the instant the page settles holds nothing but "Select
 * artisan" — which would let this spec pass on an empty dropdown, the one outcome it must never
 * mistake for a fixed one.
 */
async function readOptions(page: Page, field: string): Promise<string[]> {
  const trigger = fieldSelect(page, field);
  await expect(trigger).toBeVisible({ timeout: 30_000 });
  await trigger.click();
  const panel = page.locator("[data-anchored-popover]").last();
  await expect(panel.getByRole("option").nth(1)).toBeVisible({ timeout: 30_000 });
  const labels = await panel.getByRole("option").allTextContents();
  await page.keyboard.press("Escape");
  return labels.map((entry) => entry.trim()).filter(Boolean);
}

/** The one assertion all three cases share: readable rows, and not one of them an id. */
function expectReadable(labels: string[], separator: string) {
  expect(labels.length).toBeGreaterThan(1);
  expect(labels.filter((label) => LOOKS_LIKE_AN_ID.test(label))).toEqual([]);
  expect(labels.some((label) => label.includes(separator))).toBe(true);
}

test.describe("Dropdown labels", () => {
  test("/questionnaire — the primary artisan reads as a name, craft and place", async ({ page }) => {
    await signIn(page);
    await page.goto("/questionnaire");
    // `{artisan.name} - {artisan.craft?.name ?? "No craft"} - {artisan.place}`
    expectReadable(await readOptions(page, "Primary artisan"), " - ");
  });

  test("/processes — the required artisan picker reads as a name and place", async ({ page }) => {
    await signIn(page);
    await page.goto("/processes?new=1");
    // `{artisan.name} · {artisan.place}`
    expectReadable(await readOptions(page, "Artisan"), " · ");
  });

  test("/activity — both the options and the closed trigger read as people", async ({ page }) => {
    await signIn(page);
    await page.goto("/activity");

    const trigger = fieldSelect(page, "Whose activity?");
    // The control only appears for someone with contributors below them to inspect — and it appears
    // only once the user directory has been fetched, so absence has to be waited for rather than
    // sampled, or this skips itself on a slow morning and reports green.
    const rendered = await trigger
      .waitFor({ state: "visible", timeout: 30_000 })
      .then(() => true)
      .catch(() => false);
    if (!rendered) {
      test.skip(true, "This account oversees nobody, so the picker is not rendered.");
      return;
    }

    // THE CLOSED TRIGGER IS THE HALF THAT WAS WORST HERE: the control defaults to the signed-in
    // user's own id, so the resting state of the page — before anything is opened — read as a CUID.
    await expect(trigger).toHaveText(/^Me — .+/);

    // `Me — {user.name}`, then `{entry.name} — {roleLabel(entry.role)}` for everyone below.
    expectReadable(await readOptions(page, "Whose activity?"), " — ");
  });
});
