import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/*
  THE CRAFT PICKER IS ONE IMPLEMENTATION IN BOTH PORTALS (REQUIREMENTS_2026-10-20 §2.8).

  `CraftSelect.tsx` and `lib/craftCatalogue.ts` are byte-identical in designer-portal and
  documentation-portal; Android's `CraftPicker.kt` and `CraftCatalogueCache.kt` are identical apart
  from the package. Read off the sibling checkout when it sits beside this one (or where
  DESIGNER_PORTAL_DIR says); a CI runner with one repository skips, and the sibling pins the same.
*/

const REPO = join(__dirname, "..", "..");
const SIBLING = [process.env.DESIGNER_PORTAL_DIR, join(REPO, "..", "designer-portal")].find(
  (dir): dir is string => Boolean(dir) && existsSync(join(dir as string, "frontend", "lib"))
);

const read = (root: string, rel: string) => readFileSync(join(root, rel), "utf8").replace(/\r\n/g, "\n");

const WEB = ["frontend/components/forms/CraftSelect.tsx", "frontend/lib/craftCatalogue.ts", "frontend/e2e/fixtures/craft-catalogue.json"];
const ANDROID: Array<[string, string]> = [
  ["android/app/src/main/java/com/fieldrepository/app/ui/CraftPicker.kt", "android/app/src/main/java/com/designprototype/workshop/ui/CraftPicker.kt"],
  [
    "android/app/src/main/java/com/fieldrepository/app/data/CraftCatalogueCache.kt",
    "android/app/src/main/java/com/designprototype/workshop/data/CraftCatalogueCache.kt"
  ]
];

for (const rel of WEB) {
  test(`${rel} is the same file in both portals`, () => {
    test.skip(!SIBLING || !existsSync(join(SIBLING, rel)), "sibling checkout not beside this one");
    expect(read(REPO, rel)).toBe(read(SIBLING as string, rel));
  });
}

for (const [here, there] of ANDROID) {
  test(`${here.split("/").pop()} is the same in both apps apart from the package`, () => {
    test.skip(!SIBLING || !existsSync(join(SIBLING, there)), "sibling checkout not beside this one");
    const normalise = (text: string) => text.replace(/com\.designprototype\.workshop/g, "com.fieldrepository.app");
    expect(normalise(read(REPO, here))).toBe(normalise(read(SIBLING as string, there)));
  });
}

test("this portal carries the shared files", () => {
  for (const rel of [...WEB, ...ANDROID.map(([here]) => here)]) expect(existsSync(join(REPO, rel)), rel).toBe(true);
});
