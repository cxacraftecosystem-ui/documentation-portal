import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

import { reviewEditableFields } from "@/components/review/reviewEditFields";

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

test("a review edit offers no typed craft, on the web or on Android", () => {
  // A reviewer fixes a record's prose; its craft is picked on the record's own edit screen.
  for (const type of ["artisan", "workshop", "product", "tool", "process", "questionnaire", "media"]) {
    expect(reviewEditableFields(type).map((field) => field.key).filter((key) => /craft/i.test(key)), type).toEqual([]);
  }
  const android = readFileSync(join(ROOT, "..", "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt"), "utf8");
  expect(android).not.toMatch(/ReviewField\("craft/);
});

test("every craft picker is CraftSelect", () => {
  const pickers = [
    "components/forms/ArtisanForm.tsx",
    "components/forms/ProductForm.tsx",
    "components/forms/ToolForm.tsx",
    "components/forms/ToolAssignmentSection.tsx",
    "components/FunnelFilters.tsx",
    // The Search page's craft filter.
    "components/search/SearchFilters.tsx",
    "app/(protected)/workshops/page.tsx",
    // The media page's "Linked entry" when the record type is Craft.
    "app/(protected)/media/page.tsx"
  ];
  for (const file of pickers) expect(read(file), file).toContain("<CraftSelect");
  // The craft form's category is Category → Sub-category, never typed.
  expect(read("app/(protected)/crafts/page.tsx")).toContain("<CraftSubCategorySelect");
  expect(read("app/(protected)/crafts/page.tsx")).not.toContain('name="category"');
  // No Place box on the craft form in either portal (2026-10-20); the column stays for old records.
  expect(read("app/(protected)/crafts/page.tsx")).not.toMatch(/name="place"/);
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

/*
  PARITY WITH THE DESIGNER PORTAL (§2.8), held two ways — the mirror of the designer portal's own
  test, with the same digests.

  1. A pinned SHA-256 of each shared file (line endings folded). This half always runs: the designer
     portal's test pins the same digests, and changing a shared file means changing both copies and
     both pins in one go.
  2. A straight comparison with the sibling checkout when it is present: $SIBLING_REPO (CI checks the
     designer portal's main out there), then `designer-portal` beside this repository, then
     C:/dev/IIT/designer-portal.
*/
const SHARED_DIGESTS: Record<string, string> = {
  "components/forms/CraftSelect.tsx": "b8c23a80b88c1bdbb33c56a35ef6acd17431ac7bffee38deeaebcb086d04e3f0",
  "lib/craftCatalogue.ts": "39b7dd6c7214dd6c3d1208a0262c16ebb2a9f06b93b8a87706064038a9812555",
  "e2e/fixtures/craft-catalogue.json": "33ee1b2655d0fa558f9d8c3a147cc056c434d4d867a81c966a99406be824c572"
};
const lf = (text: string) => text.replace(/\r\n/g, "\n");

test("the shared craft files match the pinned digests both portals carry", () => {
  for (const [file, digest] of Object.entries(SHARED_DIGESTS)) {
    expect(createHash("sha256").update(lf(read(file)), "utf8").digest("hex"), file).toBe(digest);
  }
});

const SIBLING_FRONTEND = [
  process.env.SIBLING_REPO,
  join(ROOT, "..", "..", "designer-portal"),
  "C:/dev/IIT/designer-portal"
]
  .filter((dir): dir is string => Boolean(dir))
  .map((dir) => join(dir, "frontend"))
  .find((dir) => Object.keys(SHARED_DIGESTS).every((file) => existsSync(join(dir, file))));

test("the shared craft files are byte-identical to the designer portal's checkout", () => {
  test.skip(!SIBLING_FRONTEND, "no designer-portal checkout carrying the craft files");
  for (const file of Object.keys(SHARED_DIGESTS)) {
    expect(lf(readFileSync(join(SIBLING_FRONTEND as string, file), "utf8")), file).toBe(lf(read(file)));
  }
});
