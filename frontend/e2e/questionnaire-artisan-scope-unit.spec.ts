import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { cutOf, type ListCut } from "@/components/data/cappedList";
import {
  ARTISAN_PAGE_BUDGET,
  artisanPickerOptionLabel,
  artisanPickerOptions,
  artisanScopeNoun,
  artisanSetKey,
  artisansNotAtWorkshop,
  outOfWorkshopNotice,
  primaryInterviewArtisanId,
  workshopArtisanParams,
  workshopScopeSettling
} from "@/components/questionnaires/interviewArtisans";
import type { Artisan, Craft } from "@/lib/types";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");

function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = codeOnly(PAGE);

function artisan(id: string, name: string, craftName: string | null = "Weaving", place = "Bhuj"): Artisan {
  return {
    id,
    name,
    place,
    status: "APPROVED",
    craftId: craftName ? craftName.toLowerCase() : null,
    craft: craftName ? ({ id: craftName.toLowerCase(), name: craftName } as Craft) : null
  } as unknown as Artisan;
}

const RAMESH = artisan("a-ramesh", "Ramesh Kumar");
const SITA = artisan("a-sita", "Sita Devi", "Block printing", "Ajrakhpur");
const OUTSIDER = artisan("a-outsider", "Hiren Patel", "Pottery", "Khavda");

test.describe("the artisan picker under a chosen workshop", () => {
  test("an artisan of another workshop is not offered", () => {
    const options = artisanPickerOptions({
      scoped: [RAMESH, SITA],
      known: [RAMESH, SITA, OUTSIDER],
      selectedIds: []
    });
    expect(options.map((option) => option.value)).toEqual(["a-ramesh", "a-sita"]);
    expect(options.some((option) => option.label.includes("Hiren Patel"))).toBe(false);
  });

  test("the previous workshop's roster does not survive into the next workshop's options", () => {
    const atWorkshopOne = artisanPickerOptions({ scoped: [RAMESH, SITA], known: [RAMESH, SITA], selectedIds: [] });
    expect(atWorkshopOne.map((option) => option.value)).toEqual(["a-ramesh", "a-sita"]);
    const atWorkshopTwo = artisanPickerOptions({
      scoped: [OUTSIDER],
      known: [RAMESH, SITA, OUTSIDER],
      selectedIds: []
    });
    expect(atWorkshopTwo.map((option) => option.value)).toEqual(["a-outsider"]);
  });

  test("an artisan already ticked is still drawn, even when the roster does not hold them", () => {
    const options = artisanPickerOptions({
      scoped: [RAMESH, SITA],
      known: [RAMESH, SITA, OUTSIDER],
      selectedIds: ["a-outsider"]
    });
    expect(options.map((option) => option.value)).toEqual(["a-ramesh", "a-sita", "a-outsider"]);
    expect(options.at(-1)?.label).toContain("Hiren Patel");
  });

  test("options keep the server's order", () => {
    const serverOrder = [SITA, OUTSIDER, RAMESH];
    const options = artisanPickerOptions({ scoped: serverOrder, known: serverOrder, selectedIds: [] });
    expect(options.map((option) => option.value)).toEqual(["a-sita", "a-outsider", "a-ramesh"]);
  });

  test("the label reads as a person and never as an id", () => {
    expect(artisanPickerOptionLabel(SITA)).toBe("Sita Devi - Block printing - Ajrakhpur");
    expect(artisanPickerOptionLabel(artisan("a-x", "Nameless Craft", null, "Nirona"))).toBe(
      "Nameless Craft - No craft - Nirona"
    );
  });
});

test.describe("the artisan request", () => {
  test("carries the chosen workshop as the PLURAL scope", () => {
    expect(workshopArtisanParams("w-3", 1)).toEqual({ workshopIds: "w-3", page: 1, pageSize: 100 });
  });

  test("does not send the singular workshopId beside it", () => {
    expect(Object.keys(workshopArtisanParams("w-3", 1))).not.toContain("workshopId");
  });

  test("asks for everybody when no workshop is chosen", () => {
    expect(workshopArtisanParams("", 1)).toEqual({ workshopIds: undefined, page: 1, pageSize: 100 });
  });

  test("pages, and the page number is the only thing that moves", () => {
    expect(workshopArtisanParams("w-3", 4)).toEqual({ workshopIds: "w-3", page: 4, pageSize: 100 });
    expect(ARTISAN_PAGE_BUDGET).toBeGreaterThan(1);
  });
});

test.describe("workshopScopeSettling", () => {
  const workshops = [{ id: "w-1" }, { id: "w-2" }];

  test("while the workshop list is still loading", () => {
    expect(workshopScopeSettling({ loading: true, touched: false, workshopId: "", workshops })).toBe(true);
  });

  test("while the default probe is still walking", () => {
    expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "", workshops })).toBe(true);
  });

  test("not once the probe has landed on a workshop", () => {
    expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "w-1", workshops })).toBe(false);
  });

  test("not once the researcher has touched the control", () => {
    expect(workshopScopeSettling({ loading: false, touched: true, workshopId: "", workshops })).toBe(false);
  });

  test("not on a deployment with no workshops at all", () => {
    expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "", workshops: [] })).toBe(false);
  });
});

test.describe("primaryInterviewArtisanId", () => {
  test("is the head of the selection", () => {
    expect(primaryInterviewArtisanId(["a-sita", "a-ramesh"])).toBe("a-sita");
  });

  test("does not move when another artisan is ticked", () => {
    expect(primaryInterviewArtisanId(["a-sita"])).toBe("a-sita");
    expect(primaryInterviewArtisanId(["a-sita", "a-ramesh"])).toBe("a-sita");
    expect(primaryInterviewArtisanId(["a-sita", "a-ramesh", "a-outsider"])).toBe("a-sita");
  });

  test("moves to the next when the head is untickd", () => {
    expect(primaryInterviewArtisanId(["a-ramesh"])).toBe("a-ramesh");
  });

  test("is empty when nobody is ticked, and skips a blank", () => {
    expect(primaryInterviewArtisanId([])).toBe("");
    expect(primaryInterviewArtisanId(["", "a-ramesh"])).toBe("a-ramesh");
  });
});

test.describe("artisanSetKey", () => {
  test("two tick orders are one interview", () => {
    expect(artisanSetKey(["a-ramesh", "a-sita"])).toBe(artisanSetKey(["a-sita", "a-ramesh"]));
  });

  test("blanks and duplicates never make a different set", () => {
    expect(artisanSetKey(["a-ramesh", "", "a-ramesh"])).toBe("a-ramesh");
    expect(artisanSetKey([])).toBe("");
  });

  test("does not describe the order the ids are sent in", () => {
    const selection = ["b-second", "a-first"];
    expect(artisanSetKey(selection)).toBe("a-first,b-second");
    expect(primaryInterviewArtisanId(selection)).toBe("b-second");
  });
});

test.describe("artisansNotAtWorkshop", () => {
  const OFFERED = ["a-ramesh", "a-sita"];

  test("an artisan the workshop's complete roster does not hold is named", () => {
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        loadedForWorkshop: "w-2",
        workshopId: "w-2",
        cut: null
      })
    ).toEqual(["a-outsider"]);
  });

  test("nobody is named before this workshop's roster has landed", () => {
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        loadedForWorkshop: "w-1",
        workshopId: "w-2",
        cut: null
      })
    ).toEqual([]);
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: [],
        loadedForWorkshop: null,
        workshopId: "w-2",
        cut: null
      })
    ).toEqual([]);
  });

  test("nobody is named while the roster is cut", () => {
    const cut: ListCut = { noun: "artisans at this workshop", loaded: 500, total: 640 };
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        loadedForWorkshop: "w-2",
        workshopId: "w-2",
        cut
      })
    ).toEqual([]);
  });

  test("the named ids keep the researcher's own order, and the selection is untouched", () => {
    const selected = ["a-sita", "a-outsider", "a-stranger", "a-ramesh"];
    expect(
      artisansNotAtWorkshop({
        selectedIds: selected,
        offeredIds: OFFERED,
        loadedForWorkshop: "w-2",
        workshopId: "w-2",
        cut: null
      })
    ).toEqual(["a-outsider", "a-stranger"]);
    expect(primaryInterviewArtisanId(selected)).toBe("a-sita");
  });

  test("with no workshop selected the same rule applies to the unscoped list", () => {
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        loadedForWorkshop: "",
        workshopId: "",
        cut: null
      })
    ).toEqual(["a-outsider"]);
  });
});

test.describe("outOfWorkshopNotice", () => {
  test("says nothing when there is nothing to say", () => {
    expect(outOfWorkshopNotice([])).toBe("");
    expect(outOfWorkshopNotice(["", "   "])).toBe("");
  });

  test("names one artisan, and does not read as an accusation", () => {
    const line = outOfWorkshopNotice(["Ramesh Kumar"]);
    expect(line).toContain("Ramesh Kumar is new to this workshop");
    expect(line).toContain("Saving links them to it");
  });

  test("names several, in the order given", () => {
    expect(outOfWorkshopNotice(["Sita Devi", "Ramesh Kumar"])).toContain("Sita Devi, Ramesh Kumar are");
  });

  test("caps the list and counts the remainder", () => {
    const line = outOfWorkshopNotice(["A", "B", "C", "D", "E", "F"]);
    expect(line).toContain("A, B, C, D and 2 more are");
    expect(line).not.toContain("E");
  });
});

test.describe("the capped-list report", () => {
  test("a roster inside the budget says nothing at all", () => {
    expect(cutOf(64, 64, artisanScopeNoun("w-2"))).toBeNull();
  });

  test("a roster past the budget is reported, in the scope's own words", () => {
    const cut = cutOf(ARTISAN_PAGE_BUDGET * 100, 640, artisanScopeNoun("w-2"));
    expect(cut).toEqual({ noun: "artisans at this workshop", loaded: 500, total: 640 });
  });

  test("the noun follows the scope", () => {
    expect(artisanScopeNoun("w-2")).toBe("artisans at this workshop");
    expect(artisanScopeNoun("")).toBe("artisans");
  });
});

test.describe("the capture page itself", () => {
  test("no longer loads artisans without a workshop scope", () => {
    expect(CODE, "the page itself must not fetch /artisans at all any more").not.toMatch(/["'`]\/artisans/);
  });

  test("holds one artisan selection and not a primary/additional pair", () => {
    expect(CODE, "no second artisan state").not.toMatch(/additionalArtisanIds/);
    expect(CODE, "no single-select primary control").not.toMatch(/primaryArtisanId"/);
    expect(CODE).toMatch(/const \[selectedArtisanIds, setSelectedArtisanIds\]/);
    expect(CODE, "one multi-select, fed by that one selection").toMatch(
      /<MultiSelectDropdown\s+values=\{selectedArtisanIds\}/
    );
  });

  test("scopes the interview list by the funnel's workshop", () => {
    expect(CODE).toMatch(/workshopId: funnel\.workshopId \|\| undefined/);
    expect(CODE, "and re-runs when that workshop changes").toMatch(/\[funnelReady, page, funnel\.workshopId,/);
  });
});
