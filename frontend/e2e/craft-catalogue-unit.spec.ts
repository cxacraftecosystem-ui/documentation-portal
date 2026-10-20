import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CRAFT_NO_MATCH,
  catalogueCountLine,
  craftIndex,
  craftPath,
  findCraftByText,
  foldCraftText,
  groupCrafts,
  normaliseCatalogue,
  searchCraftCatalogue,
  type CraftCatalogue
} from "@/lib/craftCatalogue";

/*
  THE CRAFT LIST EVERY PICKER READS (REQUIREMENTS_2026-10-20 §2.3-§2.4), held against a mock of the
  frozen `GET /crafts/catalogue` answer built from the master list: 9 categories, 36 sub-categories,
  455 crafts, 209 of them GI. The fixture is the contract's shape, so these rules hold whatever the
  server's ids are. Android's `CraftCatalogueTest` reads the same fixture.
*/

const FIXTURE = join(__dirname, "fixtures", "craft-catalogue.json");
const catalogue = normaliseCatalogue(JSON.parse(readFileSync(FIXTURE, "utf8"))) as CraftCatalogue;

test("the mocked catalogue is the master list: 9 categories, 36 sub-categories, 455 crafts, 209 GI", () => {
  expect(catalogue.categories).toHaveLength(9);
  expect(catalogue.categories.flatMap((category) => category.subCategories)).toHaveLength(36);
  expect(catalogue.crafts).toHaveLength(455);
  expect(catalogue.crafts.filter((craft) => craft.giStatus === "GI")).toHaveLength(209);
  expect(catalogueCountLine(catalogue)).toBe("455 crafts in 9 categories");
});

test("typing 'ajrakh' finds Kutch Ajrakh under its heading, with a GI badge", () => {
  const found = searchCraftCatalogue(catalogue, "ajrakh");
  const ajrakh = found.find((craft) => craft.name === "Kutch Ajrakh");
  expect(ajrakh, "Kutch Ajrakh is on the list").toBeTruthy();
  expect(ajrakh?.giStatus).toBe("GI");
  const groups = groupCrafts(catalogue, found);
  const group = groups.find((row) => row.crafts.some((craft) => craft.name === "Kutch Ajrakh"));
  expect(group?.heading).toBe("Textiles based Craft Category › Hand Block Printing");
});

test("search folds case, accents, hyphens and '&', and ranks the name first", () => {
  expect(foldCraftText("  Kútch–AJRAKH & Co ")).toBe("kutch ajrakh and co");
  const byName = searchCraftCatalogue(catalogue, "Tharu Embroidery");
  expect(byName[0].name).toBe("Tharu Embroidery");
  // A state matches too, ranked below every name match.
  const byState = searchCraftCatalogue(catalogue, "gujarat");
  expect(byState.length).toBeGreaterThan(0);
  // Several words that no single field holds still match when every word is found somewhere.
  expect(searchCraftCatalogue(catalogue, "ajrakh gujarat").some((craft) => craft.name === "Kutch Ajrakh")).toBe(true);
  expect(searchCraftCatalogue(catalogue, "zzzz-not-a-craft")).toHaveLength(0);
  expect(CRAFT_NO_MATCH("zzzz")).toBe("No craft matches “zzzz”.");
});

test("an empty search is the whole list in catalogue order, grouped by sub-category", () => {
  const all = searchCraftCatalogue(catalogue, "");
  expect(all).toHaveLength(455);
  expect(all[0].sourceSerial).toBe(1);
  const groups = groupCrafts(catalogue, all);
  expect(groups).toHaveLength(36);
  const index = craftIndex(catalogue);
  expect(groups[0].heading).toBe(craftPath(index, all[0]));
});

test("free text resolves to a craft only when exactly one matches", () => {
  expect(findCraftByText(catalogue, "kutch ajrakh")?.slug).toBe("Kutch-Ajrakh");
  expect(findCraftByText(catalogue, "Kutch-Ajrakh")?.name).toBe("Kutch Ajrakh");
  expect(findCraftByText(catalogue, "")).toBeNull();
  expect(findCraftByText(catalogue, "Portrait, Jammu, Jammu & Kashmir")).toBeNull();
});

test("a body missing its arrays is refused, never half-read", () => {
  expect(normaliseCatalogue(null)).toBeNull();
  expect(normaliseCatalogue({ version: "x", categories: [] })).toBeNull();
  const thin = normaliseCatalogue({ version: "v", categories: [], crafts: [{ id: "a", slug: "A", name: "A" }] });
  expect(thin?.crafts[0].aliases).toEqual([]);
  expect(thin?.crafts[0].stateOfPractice).toEqual([]);
});

test("the catalogue revalidates with the server's ETag and survives offline from the stored copy", () => {
  const source = readFileSync(join(__dirname, "..", "lib", "craftCatalogue.ts"), "utf8");
  expect(source).toContain('headers["If-None-Match"] = stored.etag');
  expect(source).toContain("error.status === 304");
  expect(source).toContain("if (stored) return stored.catalogue;");
  expect(source).toContain('export const CRAFT_CATALOGUE_PATH = "/crafts/catalogue";');
});
