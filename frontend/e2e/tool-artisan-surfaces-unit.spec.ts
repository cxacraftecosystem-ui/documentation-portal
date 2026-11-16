import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { legacyHits } from "@/components/dataExplorer/paletteSearch";
import { legacyRows } from "@/lib/dataSearch";
import { toolArtisanNames, toolOwnArtisans, toolRowLabel, withOwnArtisans } from "@/lib/toolArtisans";
import type { Artisan, ToolDocumentation } from "@/lib/types";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const person = (id: string, name: string) => ({ id, name, place: "Moradabad" }) as unknown as Artisan;
const GRINDER = {
  id: "cmwekvo2d03omcbtag53r4g2x",
  toolkitName: "Grinder",
  craftName: "Metal craft",
  place: "Moradabad",
  artisanId: "a-fahim",
  artisanName: "Fahim",
  artisan: person("a-fahim", "Fahim"),
  artisanLinks: [
    { id: "l1", toolId: "cmwekvo2d03omcbtag53r4g2x", artisanId: "a-moin", artisan: person("a-moin", "Moin Khan") },
    { id: "l2", toolId: "cmwekvo2d03omcbtag53r4g2x", artisanId: "a-fahim", artisan: person("a-fahim", "Fahim") }
  ],
  status: "APPROVED"
} as unknown as ToolDocumentation;
const EVERY = "Fahim, Moin Khan";

test("a tool with several artisans is named by all of them, head first, once each", () => {
  expect(toolArtisanNames(GRINDER)).toBe(EVERY);
  expect(toolRowLabel(GRINDER)).toBe(`Grinder · ${EVERY}`);
  const headNotLinked = { ...GRINDER, artisanId: "a-salim", artisanName: "Salim" } as ToolDocumentation;
  expect(toolArtisanNames(headNotLinked), "a head with no link row still leads").toBe("Salim, Moin Khan, Fahim");
});

test("search results and the explorer's search name every artisan of a tool", () => {
  const rows = legacyRows({ tools: [GRINDER] });
  expect(rows.find((row) => row.id === GRINDER.id)?.subtitle).toBe(EVERY);
  expect(legacyHits({ tools: [GRINDER] }).map((hit) => hit.subtitle)).toEqual([`Tool · ${EVERY}`]);
});

test("the edit form keeps every artisan of the tool on offer, even ones outside the loaded list", () => {
  const own = toolOwnArtisans<Artisan>(GRINDER as unknown as Parameters<typeof toolOwnArtisans<Artisan>>[0]);
  expect(own.map((a) => a.id)).toEqual(["a-fahim", "a-moin"]);
  const offered = withOwnArtisans([person("a-fahim", "Fahim"), person("a-other", "Other")], own);
  expect(offered.map((a) => a.id)).toEqual(["a-fahim", "a-other", "a-moin"]);
});

test("the assignment list keeps the head, which only the tool form changes", () => {
  expect(read("components", "forms", "ToolAssignmentSection.tsx")).toContain("{artisan.isHead ? null : (");
});

test("the tools list, the tool view and the tool pickers label tools through the shared helper", () => {
  expect(read("app", "(protected)", "tools", "page.tsx")).toContain("{toolArtisanNames(tool)}");
  expect(read("components", "forms", "ToolAssignmentSection.tsx")).toContain("toolArtisanNames(tool)");
  expect(read("lib", "dataSearch.ts")).toContain("subtitle: toolArtisanNames(t)");
});

test("the tool view and the record switcher name every artisan", async () => {
  const { recordFieldRows } = await import("@/components/records/recordFields");
  expect(recordFieldRows("tool", GRINDER as never).find((row) => row.label === "Artisan")?.value).toBe(EVERY);
  const { recordOptionLabel } = await import("@/components/forms/RecordSwitcher");
  expect(recordOptionLabel("tool", GRINDER as never)).toBe(`Grinder · ${EVERY}`);
  expect(read("components", "forms", "ToolForm.tsx")).toContain("withOwnArtisans(offeredArtisans, ownArtisans)");
});
