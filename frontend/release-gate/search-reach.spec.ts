import { expect, test } from "./gate";

type Group = { type: string; label: string; total: number; items: { id: string }[] };

const WORDS = ["audio", "craft", "artisan", "workshop", "weaving", "indigo", "product", "tool"];

test("data search reaches the last result under All and under its busiest type", async ({ page, gate }) => {
  let word = WORDS[0];
  let groups: Group[] = [];
  for (const candidate of WORDS) {
    const answer = (await gate.get(`/api/search/unified?q=${encodeURIComponent(candidate)}&scope=local&limit=5&page=1`)) as { groups?: Group[] };
    const found = answer.groups ?? [];
    if (found.reduce((n, g) => n + g.total, 0) > groups.reduce((n, g) => n + g.total, 0)) {
      word = candidate;
      groups = found;
    }
  }
  const busiest = [...groups].sort((a, b) => b.total - a.total)[0];
  gate.count({ query: word, groups: groups.map((g) => `${g.type}:${g.total}`).join(" "), busiest: busiest?.type ?? "none" });
  expect(busiest, "the busiest word finds records").toBeTruthy();

  await page.goto("/data");
  await page.getByRole("searchbox", { name: "Search all data" }).fill(word);
  await expect(page.getByTestId(`search-group-${busiest.type}`)).toBeVisible({ timeout: 60_000 });
  await gate.shot("all-first-page");

  const more = page.getByTestId("search-more");
  for (let i = 0; i < 400 && (await more.count()) > 0; i++) {
    await more.click();
    await expect(more).toBeEnabled({ timeout: 60_000 }).catch(() => undefined);
  }
  await gate.shot("all-last-page");
  gate.expectRow("All view has no Load more left after paging", 0, await more.count());

  await page.getByRole("searchbox", { name: "Search all data" }).fill("");
  await page.getByRole("searchbox", { name: "Search all data" }).fill(word);
  await expect(page.getByTestId(`search-all-${busiest.type}`)).toBeVisible({ timeout: 60_000 });
  await page.getByTestId(`search-all-${busiest.type}`).click();
  for (let i = 0; i < 400 && (await more.count()) > 0; i++) {
    await more.click();
    await expect(more).toBeEnabled({ timeout: 60_000 }).catch(() => undefined);
  }
  await page.keyboard.press("End");
  await gate.shot(`${busiest.type}-last-page`);
  gate.expectRow(`Show all ${busiest.total} ${busiest.type} has no Show more left`, 0, await more.count());
});
