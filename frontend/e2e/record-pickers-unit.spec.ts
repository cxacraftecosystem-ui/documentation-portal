import { expect, test } from "@playwright/test";

import { cappedListNotice, cutOf, mergeById, type ListCut } from "@/components/data/cappedList";
import {
  craftChangeClearsArtisan,
  craftNameFor,
  craftsChangeClearsArtisans,
  craftsKey,
  sortArtisansByCraft
} from "@/components/forms/recordPickers";
import type { Artisan, Craft } from "@/lib/types";

function artisan(id: string, craftId: string | null): Artisan {
  return { id, name: `Artisan ${id}`, place: "Place", status: "APPROVED", craftId } as unknown as Artisan;
}

function named(id: string, name: string, craftId: string | null, hydratedCraft?: string): Artisan {
  return {
    id,
    name,
    place: "Place",
    status: "APPROVED",
    craftId,
    craft: hydratedCraft ? ({ id: craftId ?? "", name: hydratedCraft } as Craft) : null
  } as unknown as Artisan;
}

function craft(id: string, name: string): Craft {
  return { id, name } as Craft;
}

test.describe("craftChangeClearsArtisan", () => {
  test("an artisan known to practise another craft is unlinked", () => {
    const artisans = [artisan("a1", "weaving")];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "a1", artisans })).toBe(true);
  });

  test("an artisan known to practise the chosen craft is kept", () => {
    const artisans = [artisan("a1", "pottery")];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "a1", artisans })).toBe(false);
  });

  test("an artisan the picker cannot see keeps their link", () => {
    const pageOne = [artisan("someone-else", "pottery")];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "off-page", artisans: pageOne })).toBe(false);
    expect(craftChangeClearsArtisan({ nextCraftId: "blockprinting", artisanId: "off-page", artisans: pageOne })).toBe(false);
  });

  test("an artisan on the page with no craft recorded is a known difference", () => {
    const artisans = [artisan("a1", null)];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "a1", artisans })).toBe(true);
  });

  test("unlinking the craft never touches the artisan", () => {
    const artisans = [artisan("a1", "weaving")];
    expect(craftChangeClearsArtisan({ nextCraftId: "", artisanId: "a1", artisans })).toBe(false);
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "", artisans })).toBe(false);
  });
});

test.describe("craftsChangeClearsArtisans", () => {
  test("deselecting one craft drops only that craft's people", () => {
    const artisans = [artisan("weaver", "weaving"), artisan("potter", "pottery")];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["pottery"],
        removedCraftIds: ["weaving"],
        artisanIds: ["weaver", "potter"],
        artisans
      })
    ).toEqual(["weaver"]);
  });

  test("an artisan of a craft that is still ticked is kept", () => {
    const artisans = [artisan("potter", "pottery")];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["pottery", "weaving"],
        removedCraftIds: ["blockprinting"],
        artisanIds: ["potter"],
        artisans
      })
    ).toEqual([]);
  });

  test("an artisan whose craft was never ticked is not touched by unticking another", () => {
    const artisans = [artisan("mohan", "pottery"), artisan("printer", "blockprinting")];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["bandhani"],
        removedCraftIds: ["blockprinting"],
        artisanIds: ["mohan", "printer"],
        artisans
      })
    ).toEqual(["printer"]);
  });

  test("an artisan the picker cannot see keeps their link", () => {
    const pageOne = [artisan("someone-else", "pottery")];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["pottery"],
        removedCraftIds: ["weaving"],
        artisanIds: ["off-page"],
        artisans: pageOne
      })
    ).toEqual([]);
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["blockprinting"],
        removedCraftIds: ["pottery"],
        artisanIds: ["off-page"],
        artisans: pageOne
      })
    ).toEqual([]);
  });

  test("an artisan with no craft recorded is kept, not guessed about", () => {
    const artisans = [artisan("unfiled", null)];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["pottery"],
        removedCraftIds: ["weaving"],
        artisanIds: ["unfiled"],
        artisans
      })
    ).toEqual([]);
  });

  test("unticking the last craft drops that craft's people and nobody else's", () => {
    const artisans = [artisan("potter", "pottery"), artisan("weaver", "weaving"), artisan("unfiled", null)];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: [],
        removedCraftIds: ["pottery"],
        artisanIds: ["potter", "weaver", "unfiled", "off-page"],
        artisans
      })
    ).toEqual(["potter"]);
  });

  test("the same two gestures in either order remove the same people", () => {
    const artisans = [artisan("potter", "pottery"), artisan("weaver", "weaving")];
    const ids = ["potter", "weaver"];
    const unticked = craftsChangeClearsArtisans({
      nextCraftIds: [],
      removedCraftIds: ["pottery"],
      artisanIds: ids,
      artisans
    });
    const thenTicked = craftsChangeClearsArtisans({
      nextCraftIds: ["weaving"],
      removedCraftIds: [],
      artisanIds: ids.filter((id) => !unticked.includes(id)),
      artisans
    });
    const ticked = craftsChangeClearsArtisans({
      nextCraftIds: ["pottery", "weaving"],
      removedCraftIds: [],
      artisanIds: ids,
      artisans
    });
    const thenUnticked = craftsChangeClearsArtisans({
      nextCraftIds: ["weaving"],
      removedCraftIds: ["pottery"],
      artisanIds: ids.filter((id) => !ticked.includes(id)),
      artisans
    });
    expect([...unticked, ...thenTicked]).toEqual(["potter"]);
    expect([...ticked, ...thenUnticked]).toEqual(["potter"]);
  });

  test("nothing ticked is nothing to drop", () => {
    const artisans = [artisan("potter", "pottery")];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["weaving"],
        removedCraftIds: ["pottery"],
        artisanIds: [],
        artisans
      })
    ).toEqual([]);
  });

  test("ticking a craft drops nobody", () => {
    const artisans = [artisan("weaver", "weaving"), artisan("potter", "pottery")];
    expect(
      craftsChangeClearsArtisans({
        nextCraftIds: ["pottery", "weaving"],
        removedCraftIds: [],
        artisanIds: ["weaver", "potter"],
        artisans
      })
    ).toEqual([]);
  });
});

test.describe("the artisan roster's order, which both clients must compute identically", () => {
  const crafts = [craft("c-bandhani", "Bandhani"), craft("c-block", "Block printing"), craft("c-ajrakh", "Ajrakh")];

  test("by craft name A→Z, then by artisan name A→Z", () => {
    const rows = [
      named("a1", "Zubair", "c-block"),
      named("a2", "Amina", "c-block"),
      named("a3", "Yusuf", "c-ajrakh"),
      named("a4", "Bhavna", "c-bandhani")
    ];
    expect(sortArtisansByCraft(rows, crafts).map((row) => row.name)).toEqual([
      "Yusuf",
      "Bhavna",
      "Amina",
      "Zubair"
    ]);
  });

  test("an artisan whose craft this client cannot name sorts LAST, never first", () => {
    const rows = [named("a1", "Anonymous", "c-unknown-to-this-page"), named("a2", "Bhavna", "c-bandhani")];
    expect(sortArtisansByCraft(rows, crafts).map((row) => row.name)).toEqual(["Bhavna", "Anonymous"]);
  });

  test("the order is TOTAL, so two clients cannot disagree about a tie", () => {
    const rows = [named("z", "Amina", "c-block"), named("a", "Amina", "c-block")];
    expect(sortArtisansByCraft(rows, crafts).map((row) => row.id)).toEqual(["a", "z"]);
  });

  test("case is folded for the comparison and not for the display", () => {
    const rows = [named("a1", "amina", "c-block"), named("a2", "Bhavna", "c-block")];
    const sorted = sortArtisansByCraft(rows, crafts);
    expect(sorted.map((row) => row.name)).toEqual(["amina", "Bhavna"]);
  });

  test("the hydrated craft wins over the selected list, and the selected list over nothing", () => {
    expect(craftNameFor(named("a1", "Amina", "c-block", "Renamed since"), crafts)).toBe("Renamed since");
    expect(craftNameFor(named("a2", "Amina", "c-block"), crafts)).toBe("Block printing");
    expect(craftNameFor(named("a3", "Amina", "c-nowhere"), crafts)).toBe("");
    expect(craftNameFor(named("a4", "Amina", null), crafts)).toBe("");
  });

  test("the sort does not mutate the array it was handed", () => {
    const rows = [named("a1", "Zubair", "c-block"), named("a2", "Amina", "c-block")];
    sortArtisansByCraft(rows, crafts);
    expect(rows.map((row) => row.name)).toEqual(["Zubair", "Amina"]);
  });
});

test.describe("craftsKey", () => {
  test("tick order does not make two keys out of one roster", () => {
    expect(craftsKey(["b", "a"])).toBe(craftsKey(["a", "b"]));
  });

  test("blanks and duplicates cannot widen it", () => {
    expect(craftsKey(["a", "", "a", "b"])).toBe("a,b");
    expect(craftsKey([])).toBe("");
    expect(craftsKey([""])).toBe("");
  });
});

test.describe("the sentence under a capped list", () => {
  test("a complete list says nothing", () => {
    expect(cutOf(42, 42, "artisans")).toBeNull();
    expect(cappedListNotice(null)).toBe("");
  });

  test("a missing total is silence, not NaN", () => {
    expect(cutOf(10, undefined as unknown as number, "artisans")).toBeNull();
  });

  test("a cut list prints both numbers and points at Search", () => {
    const cut = cutOf(100, 749, "artisans") as ListCut;
    expect(cappedListNotice(cut)).toBe(
      "Showing 100 of 749 artisans. To find another, use Browse records from the menu."
    );
    expect(cappedListNotice(cut, "pager")).toBe(
      "Showing 100 of 749 artisans — use the pager to reach the rest, which are not searched by the box above."
    );
  });

  test("nothing loaded over a non-empty repository gets its own words", () => {
    const cut = cutOf(0, 749, "artisans") as ListCut;
    expect(cappedListNotice(cut)).toBe(
      "None of the 749 artisans could be loaded. Try again."
    );
  });
});

test.describe("mergeById", () => {
  test("adds rows and never removes one", () => {
    const page = [artisan("a1", "weaving"), artisan("a2", "pottery")];
    const roster = [artisan("a2", "pottery"), artisan("a3", "pottery")];
    expect(mergeById(page, roster).map((row) => row.id)).toEqual(["a1", "a2", "a3"]);
  });

  test("a narrower answer never shortens the options", () => {
    const page = [artisan("editing-this-one", "weaving")];
    expect(mergeById(page, [])).toBe(page);
    expect(mergeById(page, [artisan("other", "pottery")]).some((row) => row.id === "editing-this-one")).toBe(true);
  });

  test("first writer wins on a duplicate id", () => {
    const first = artisan("a1", "weaving");
    const second = { ...artisan("a1", "pottery"), name: "Renamed" };
    const merged = mergeById([first], [second]);
    expect(merged).toHaveLength(1);
    expect(merged[0].name).toBe("Artisan a1");
  });
});
