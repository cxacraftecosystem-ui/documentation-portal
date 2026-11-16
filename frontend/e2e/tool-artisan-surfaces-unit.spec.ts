import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

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
  artisanId: "a-salim",
  artisanName: "Salim",
  artisan: person("a-salim", "Salim"),
  artisanLinks: [
    { id: "l1", toolId: "cmwekvo2d03omcbtag53r4g2x", artisanId: "a-fahim", artisan: person("a-fahim", "Fahim") },
    { id: "l2", toolId: "cmwekvo2d03omcbtag53r4g2x", artisanId: "a-moin", artisan: person("a-moin", "Moin Khan") }
  ],
  status: "APPROVED"
} as unknown as ToolDocumentation;

test("a tool with several artisans is named by all of them, head first, once each", () => {
  expect(toolArtisanNames(GRINDER)).toBe("Salim, Fahim, Moin Khan");
  expect(toolRowLabel(GRINDER)).toBe("Grinder · Salim, Fahim, Moin Khan");
  const linkedLater = { ...GRINDER, artisanId: "a-moin", artisanName: "Moin Khan" } as ToolDocumentation;
  expect(toolArtisanNames(linkedLater), "the head leads even when it was linked after another").toBe("Moin Khan, Fahim");
});

test("search results name every artisan of a tool", () => {
  const rows = legacyRows({ tools: [GRINDER] });
  expect(rows.find((row) => row.id === GRINDER.id)?.subtitle).toBe("Salim, Fahim, Moin Khan");
});

test("the edit form keeps every artisan of the tool on offer, even ones outside the loaded list", () => {
  const own = toolOwnArtisans<Artisan>(GRINDER as unknown as Parameters<typeof toolOwnArtisans<Artisan>>[0]);
  expect(own.map((a) => a.id)).toEqual(["a-salim", "a-fahim", "a-moin"]);
  const offered = withOwnArtisans([person("a-fahim", "Fahim"), person("a-other", "Other")], own);
  expect(offered.map((a) => a.id)).toEqual(["a-fahim", "a-other", "a-salim", "a-moin"]);
});

test("the tools list, the tool view and the tool pickers label tools through the shared helper", () => {
  expect(read("app", "(protected)", "tools", "page.tsx")).toContain("{toolArtisanNames(tool)}");
  expect(read("components", "forms", "ToolAssignmentSection.tsx")).toContain("toolArtisanNames(tool)");
  expect(read("lib", "dataSearch.ts")).toContain("subtitle: toolArtisanNames(t)");
});

test("the tool view and the record switcher name every artisan", async () => {
  const { recordFieldRows } = await import("@/components/records/recordFields");
  expect(recordFieldRows("tool", GRINDER as never).find((row) => row.label === "Artisan")?.value).toBe("Salim, Fahim, Moin Khan");
  const { recordOptionLabel } = await import("@/components/forms/RecordSwitcher");
  expect(recordOptionLabel("tool", GRINDER as never)).toBe("Grinder · Salim, Fahim, Moin Khan");
  expect(read("components", "forms", "ToolForm.tsx")).toContain("withOwnArtisans(offeredArtisans, ownArtisans)");
});
