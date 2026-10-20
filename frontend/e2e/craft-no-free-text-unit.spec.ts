import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

/*
  NO FREE-TEXT CRAFT ANYWHERE A USER PICKS ONE (REQUIREMENTS_2026-10-20 §2.4).

  Every craft a user enters is picked from the database's list through `CraftSelect`; nothing typed
  is ever submitted as a craft. A source read, because there is no React renderer in this repository.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

function files(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(join(ROOT, dir))) {
    const rel = join(dir, name);
    if (statSync(join(ROOT, rel)).isDirectory()) out.push(...files(rel));
    else if (/\.tsx?$/.test(name)) out.push(rel);
  }
  return out;
}

test("no form has a free-text craft input", () => {
  const offenders: string[] = [];
  for (const file of files("components/forms")) {
    const source = read(file);
    if (/name=["']craftName["']/.test(source) || /name=["']newCraftName["']/.test(source)) {
      offenders.push(relative(ROOT, join(ROOT, file)));
    }
  }
  expect(offenders, "a form still takes a typed craft name").toEqual([]);
  expect(read("components/forms/ArtisanForm.tsx")).not.toContain("Or new craft name");
});

test("every craft picker is CraftSelect", () => {
  const pickers = [
    "components/forms/ArtisanForm.tsx",
    "components/forms/ProductForm.tsx",
    "components/forms/ToolForm.tsx",
    "components/forms/ToolAssignmentSection.tsx",
    "components/FunnelFilters.tsx",
    "app/(protected)/workshops/page.tsx",
    // The media page's "Linked entry" when the record type is Craft.
    "app/(protected)/media/page.tsx"
  ];
  for (const file of pickers) expect(read(file), file).toContain("<CraftSelect");
  // The craft form's category is Category → Sub-category, never typed.
  expect(read("app/(protected)/crafts/page.tsx")).toContain("<CraftSubCategorySelect");
  expect(read("app/(protected)/crafts/page.tsx")).not.toContain('name="category"');
});

test("no picker reads the paged craft list any more", () => {
  for (const file of [...files("components"), ...files("app")]) {
    expect(read(file), file).not.toContain('listResource<Craft>("/crafts"');
  }
});

test("CraftSelect submits ids only, and its search box is not a form value", () => {
  const select = read("components/forms/CraftSelect.tsx");
  // The text box has no `name`: what is typed searches and is never submitted.
  const box = select.slice(select.indexOf('role="combobox"') - 400, select.indexOf('role="combobox"') + 200);
  expect(box).not.toMatch(/\bname=/);
  // The mirror carries the picked id(s), and `required` speaks the contract's sentence.
  expect(select).toContain("value={selectedIds[0] ?? \"\"}");
  expect(select).toContain("CRAFT_NOT_IN_LIST");
});

test("the two shared craft files carry no portal-specific import", () => {
  // Byte-identical in designer-portal and documentation-portal: only modules both repos carry.
  for (const file of ["components/forms/CraftSelect.tsx", "lib/craftCatalogue.ts"]) {
    const imports = read(file).match(/from "([^"]+)"/g) ?? [];
    for (const line of imports) {
      expect(
        ["react", "lucide-react", "@/lib/api", "@/lib/unifiedSearch", "@/lib/utils", "@/lib/craftCatalogue", "@/components/ui/AnchoredPopover"].some(
          (allowed) => line === `from "${allowed}"`
        ),
        `${file}: ${line}`
      ).toBe(true);
    }
  }
});
