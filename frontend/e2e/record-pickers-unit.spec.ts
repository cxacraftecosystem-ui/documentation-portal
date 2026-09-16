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

/**
 * A CRAFT CORRECTION THAT DELETED AN ARTISAN LINK, and the ceiling that hid it.
 *
 * WHY THESE RULES ARE FUNCTIONS AND WHY THIS FILE HAS NO `page`. Reproducing the defect on screen
 * needs a repository holding more than 100 artisans AND a record old enough to sort off the first
 * page of `GET /artisans` (which orders `createdAt desc`). Nobody arranges that before a release,
 * and the failure is silent — a link disappears and the save returns 200 — so the decision was
 * lifted out of the two form components into `components/forms/recordPickers` where a test can
 * reach it. This repository has no React renderer in its devDependencies, which is why this is a
 * Playwright spec that never opens a browser rather than a component test; it is the same shape,
 * and the same argument, as the sibling repository's `discarded-work-unit.spec.ts`.
 *
 * ANDROID PARITY. Every assertion below has a Kotlin twin in
 * `android/app/src/test/java/com/fieldrepository/app/RecordPickersTest.kt`, against
 * `ui/RecordPickers.kt`. Two surfaces answering one question differently is this product family's
 * most repeated defect class; if you change a rule here and the Kotlin test still passes unchanged,
 * you have just created one.
 */

function artisan(id: string, craftId: string | null): Artisan {
  // Only the three fields the rule reads are meaningful; the cast keeps the fixture to the point
  // rather than inventing a plausible-looking whole artisan record that nothing asserts on.
  return { id, name: `Artisan ${id}`, place: "Place", status: "APPROVED", craftId } as unknown as Artisan;
}

/** An artisan whose NAME matters, for the ordering rules below. */
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

  /**
   * THE REGRESSION. This is the case the old
   * `!artisans.some((a) => a.id === artisanId && a.craftId === next)` got wrong: the artisan is
   * simply not on the loaded page, which says nothing whatever about their craft. The old
   * expression read that silence as "wrong craft" and blanked the field, and because `artisanId` is
   * in the backend's CLEARABLE_KEYS and both forms submit `artisanId: artisanId || null`, the blank
   * was written through as a real unlink. Opening a record to fix its CRAFT destroyed its ARTISAN.
   */
  test("an artisan the picker cannot see keeps their link", () => {
    const pageOne = [artisan("someone-else", "pottery")];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "off-page", artisans: pageOne })).toBe(false);
    // And when the new craft is one no loaded artisan practises — the shape a 100-row page produces
    // most often — the answer must still be "keep".
    expect(craftChangeClearsArtisan({ nextCraftId: "blockprinting", artisanId: "off-page", artisans: pageOne })).toBe(false);
  });

  test("an artisan on the page with no craft recorded is a known difference", () => {
    const artisans = [artisan("a1", null)];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "a1", artisans })).toBe(true);
  });

  test("unlinking the craft never touches the artisan", () => {
    const artisans = [artisan("a1", "weaving")];
    // "Unlinked / type below" is a blank craft id. Clearing the artisan too would destroy a second
    // link the researcher never touched.
    expect(craftChangeClearsArtisan({ nextCraftId: "", artisanId: "a1", artisans })).toBe(false);
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "", artisans })).toBe(false);
  });
});

/**
 * THE SAME RULE FOR THE TOOL FORM'S MULTI-SELECT, which is where the stakes are higher: the single
 * rule decides whether ONE link survives a craft correction, and this one decides which of several
 * do. Every case below mirrors one above, deliberately — if a case here answers differently from its
 * singular twin without a stated reason, one of the two is wrong.
 *
 * THE ARGUMENT LIST GREW A THIRD MEMBER AND THAT IS THE POINT OF HALF THESE CASES. `removedCraftIds`
 * is the crafts THIS GESTURE took away; without it the rule asks only "is this artisan of a craft
 * that is not ticked", which is a different question and answers wrongly for everybody whose craft
 * was never ticked at all. A tool's artisans do not all arrive through its craft picker — "Assign a
 * tool to multiple artisans" links anybody, of any craft — so the old reading deleted a potter's
 * assignment when a researcher unticked Block printing.
 *
 * ANDROID PARITY. `craftsChangeClearsArtisans` in `ui/RecordPickers.kt`, driven by the same cases in
 * `RecordPickersTest.kt`.
 */
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

  /**
   * THE HEADLINE DEFECT, AND THE ONE CASE THE OLD RULE COULD NOT GET RIGHT. Mohan is a potter; this
   * tool was linked to him through "Assign a tool to multiple artisans", and Pottery has never been
   * ticked on this form. Unticking Block printing has nothing to do with him. The old rule read his
   * craft's absence from the next list as a reason to drop him, the PATCH carried the shortened
   * list, and `_replace_artisan_links` deleted his `ToolArtisan` row under a 200.
   */
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

  /**
   * THE REGRESSION, IN ITS PLURAL FORM. An artisan simply not on the loaded page says nothing
   * whatever about their craft, and reading that silence as "wrong craft" is the silent unlink the
   * singular rule was written to stop. Against a 100-row page of a longer table this is the ORDINARY
   * case on any older record, and a multi-select makes it worse rather than better: the form draws
   * the artisan as ticked while believing it knows they do not belong.
   */
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

  /**
   * DIFFERENT FROM THE SINGULAR, AND DELIBERATELY SO. `craftChangeClearsArtisan` treats an artisan
   * with no craft recorded as a known difference and clears the link, because in a single-select the
   * only alternative reading is "this row has a craft I cannot see". Here the answer is KEEP: a null
   * `craftId` is a fact about that artisan's own record, not about which crafts are ticked, and a
   * multi-select has room to leave them visible and one click from being corrected. Dropping them
   * would delete a link over an absence.
   */
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

  /**
   * UNTICKING THE LAST CRAFT IS NOT A SPECIAL CASE, AND THE CASE THAT SAID IT WAS IS QUOTED.
   *
   * This was named *"unticking every craft drops the artisans it can account for, and only those"*
   * and it passed for the wrong reason: the rule dropped everyone whose craft was not ticked, which
   * over an empty list is everyone the form can read a craft for, including people no gesture here
   * had anything to do with. The Kotlin twin answered the opposite with an
   * `if (nextCraftIds.none { it.isNotBlank() }) emptyList()` arm, so the two clients disagreed on
   * every gesture that empties the list.
   *
   * Both sides now run ONE rule with no empty-list arm (`ui/RecordPickers.kt` retires its own with
   * the argument), and `removedCraftIds` makes the empty case fall out of the general one: the craft
   * just unticked is in `removedCraftIds`, so its artisans go, and nobody else's does — which is
   * what unticking a craft means whether or not it was the last.
   *
   * AND THE ORDER-DEPENDENCE IS GONE WITH IT: both gestures below remove the same craft, so both
   * produce the same record. Under the old rule untick-A-then-tick-B and tick-B-then-untick-A did
   * not, and a researcher had no way to know which one they had performed.
   */
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
    // Untick Pottery first (list empties), then tick Weaving.
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
    // Tick Weaving first, then untick Pottery.
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

  /**
   * A PURE ADDITION DROPS NOBODY, which falls out of `removedCraftIds` being empty and is asserted
   * rather than left to be inferred: the form only calls this when something was removed, and a rule
   * that answered otherwise here would be one refactor away from being called on every toggle.
   */
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
      // Ajrakh · Bandhani · Block printing — and inside Block printing, Amina before Zubair.
      "Yusuf",
      "Bhavna",
      "Amina",
      "Zubair"
    ]);
  });

  test("an artisan whose craft this client cannot name sorts LAST, never first", () => {
    /*
      An empty craft name sorts before everything under a plain comparison, which would open the list
      with the rows the client can say the least about. The key carries an explicit "unknown" flag
      ahead of the name for exactly that reason.
    */
    const rows = [named("a1", "Anonymous", "c-unknown-to-this-page"), named("a2", "Bhavna", "c-bandhani")];
    expect(sortArtisansByCraft(rows, crafts).map((row) => row.name)).toEqual(["Bhavna", "Anonymous"]);
  });

  test("the order is TOTAL, so two clients cannot disagree about a tie", () => {
    // Same craft, same name: the key falls through to the cuid, which is unique. It therefore does
    // not matter whether either platform's sort is stable.
    const rows = [named("z", "Amina", "c-block"), named("a", "Amina", "c-block")];
    expect(sortArtisansByCraft(rows, crafts).map((row) => row.id)).toEqual(["a", "z"]);
  });

  test("case is folded for the comparison and not for the display", () => {
    const rows = [named("a1", "amina", "c-block"), named("a2", "Bhavna", "c-block")];
    const sorted = sortArtisansByCraft(rows, crafts);
    // "Bhavna" < "amina" by raw UTF-16 code unit (upper case sorts first); folding is what puts them
    // in the order a reader expects, and the name itself is untouched.
    expect(sorted.map((row) => row.name)).toEqual(["amina", "Bhavna"]);
  });

  test("the hydrated craft wins over the selected list, and the selected list over nothing", () => {
    // The API includes `artisan.craft`; the ticked craft rows are the fallback for a page that has
    // the id but not the row. Both are real sources and the order between them is stated.
    expect(craftNameFor(named("a1", "Amina", "c-block", "Renamed since"), crafts)).toBe("Renamed since");
    expect(craftNameFor(named("a2", "Amina", "c-block"), crafts)).toBe("Block printing");
    expect(craftNameFor(named("a3", "Amina", "c-nowhere"), crafts)).toBe("");
    expect(craftNameFor(named("a4", "Amina", null), crafts)).toBe("");
  });

  test("the sort does not mutate the array it was handed", () => {
    // It feeds a `useMemo` over hook state; sorting in place would reorder the loaded roster itself.
    const rows = [named("a1", "Zubair", "c-block"), named("a2", "Amina", "c-block")];
    sortArtisansByCraft(rows, crafts);
    expect(rows.map((row) => row.name)).toEqual(["Zubair", "Amina"]);
  });
});

test.describe("craftsKey", () => {
  test("tick order does not make two keys out of one roster", () => {
    // It is compared with `===` in a render to decide whether "no artisans are linked to these
    // crafts" may be said yet. Two keys for one selection would leave that sentence printed off the
    // previous roster while the new request was still in flight.
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

  /**
   * `total` is a plain cast off the wire — `apiFetch` does not validate a schema — so a deployment
   * that has not shipped the field yet sends `undefined`, and the screen must say NOTHING rather
   * than print a cut of `NaN`.
   */
  test("a missing total is silence, not NaN", () => {
    expect(cutOf(10, undefined as unknown as number, "artisans")).toBeNull();
  });

  test("a cut list prints both numbers and admits the search is local", () => {
    const cut = cutOf(100, 749, "artisans") as ListCut;
    expect(cappedListNotice(cut)).toBe(
      "Showing 100 of 749 artisans — the other 649 are not on this list, and typing here searches only the 100 shown."
    );
    expect(cappedListNotice(cut, "pager")).toBe(
      "Showing 100 of 749 artisans — use the pager to reach the rest, which are not searched by the box above."
    );
  });

  /**
   * The arm no live database produces from page one, and the reason this is a pure function rather
   * than a ternary in JSX: nothing renders, so nobody can screenshot it, so this test is the only
   * place the wording is ever checked. It must not tell the reader to search or to page — neither
   * would help — and it must deny the reading the empty control invites.
   */
  test("nothing loaded over a non-empty repository gets its own words", () => {
    const cut = cutOf(0, 749, "artisans") as ListCut;
    expect(cappedListNotice(cut)).toBe(
      "None of the 749 artisans could be listed here — this is not an empty repository."
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
    // The craft-scoped roster arriving must not make an edit form forget the artisan it is editing.
    const page = [artisan("editing-this-one", "weaving")];
    expect(mergeById(page, [])).toBe(page);
    expect(mergeById(page, [artisan("other", "pottery")]).some((row) => row.id === "editing-this-one")).toBe(true);
  });

  test("first writer wins on a duplicate id", () => {
    const first = artisan("a1", "weaving");
    const second = { ...artisan("a1", "pottery"), name: "Renamed" };
    const merged = mergeById([first], [second]);
    expect(merged).toHaveLength(1);
    // The row already on screen is not swapped for a differently-shaped one mid-interaction.
    expect(merged[0].name).toBe("Artisan a1");
  });
});
