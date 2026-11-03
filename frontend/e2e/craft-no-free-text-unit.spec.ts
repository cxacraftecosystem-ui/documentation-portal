import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

import { reviewEditableFields } from "@/components/review/reviewEditFields";

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
    "components/search/SearchFilters.tsx",
    "app/(protected)/workshops/page.tsx",
    "app/(protected)/media/page.tsx"
  ];
  for (const file of pickers) expect(read(file), file).toContain("<CraftSelect");
  expect(read("app/(protected)/crafts/page.tsx")).toContain("<CraftSubCategorySelect");
  expect(read("app/(protected)/crafts/page.tsx")).not.toContain('name="category"');
  expect(read("app/(protected)/crafts/page.tsx")).not.toMatch(/name="place"/);
});

test("no picker reads the paged craft list any more", () => {
  for (const file of [...files("components"), ...files("app")]) {
    expect(read(file), file).not.toContain('listResource<Craft>("/crafts"');
  }
});

test("CraftSelect submits ids only, and its search box is not a form value", () => {
  const select = read("components/forms/CraftSelect.tsx");
  const box = select.slice(select.indexOf('role="combobox"') - 400, select.indexOf('role="combobox"') + 200);
  expect(box).not.toMatch(/\bname=/);
  expect(select).toContain("value={selectedIds[0] ?? \"\"}");
  expect(select).toContain("CRAFT_NOT_IN_LIST");
});

test("the two shared craft files carry no portal-specific import", () => {
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

const SHARED_DIGESTS: Record<string, string> = {
  "components/forms/CraftSelect.tsx": "f891d3054bce0aee4ad60a277fd9dfefc80cb6294fc0ed4db106a741ffdeed61",
  "lib/craftCatalogue.ts": "5c31b2fe1afeed68ee9c2641abd8f26998e6a7f564794e18496cd79096e94587",
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
