import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { codeOptions, unmappedHeading } from "@/components/crafts/UnmappedCraftsPanel";

/**
 * C26 on the web: an administrator maps a craft to the shared code from the crafts page, and the
 * panel is drawn for administrators only. Same spec in both portals. No browser.
 */
test("the code picker names the craft first and sorts by name", () => {
  expect(
    codeOptions([
      { code: "zardozi", name: "Zardozi" },
      { code: "bandhani", name: "Bandhani" }
    ])
  ).toEqual([
    { value: "bandhani", label: "Bandhani · bandhani" },
    { value: "zardozi", label: "Zardozi · zardozi" }
  ]);
});

test("the heading counts what is left", () => {
  expect(unmappedHeading(1)).toBe("1 craft without a shared code");
  expect(unmappedHeading(3)).toBe("3 crafts without a shared code");
});

test("the crafts page draws the panel for administrators in admin view only", () => {
  const page = readFileSync(join(__dirname, "../app/(protected)/crafts/page.tsx"), "utf8");
  // Under the Needs review tab, which only content admins see; the panel itself needs admin view.
  expect(page).toContain("const contentAdmin = canAdministerCraftList(user);");
  expect(page).toContain('{tab === "review" && contentAdmin ? (');
  expect(page).toContain("{adminMode ? <UnmappedCraftsPanel /> : null}");
  const panel = readFileSync(join(__dirname, "../components/crafts/UnmappedCraftsPanel.tsx"), "utf8");
  expect(panel).toContain('"/craft-codes/unmapped"');
  expect(panel).toContain("if (!items || items.length === 0) return null;");
  expect(panel).not.toMatch(/<select/);
});
