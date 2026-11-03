import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { cappedListNotice, type ListCut } from "@/components/data/cappedList";
import {
  ALL_WORKSHOPS_LABEL,
  EDIT_QUERY_PARAM,
  isInlineEditSurface,
  LABEL_SEPARATOR,
  RECORD_KINDS,
  recordEditHref,
  recordListCut,
  recordListMessage,
  recordNoun,
  recordOptionLabel,
  recordPlural,
  recordSingular,
  recordsAreForWorkshop,
  recordSwitcherTitle,
  recordSwitchWorkshopFor,
  recordSwitchWorkshopOptions,
  SEARCH_DEBOUNCE_MS,
  shouldSearchServer,
  type RecordKind,
  type SwitchableRecord
} from "@/components/forms/RecordSwitcher";

const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const KOTLIN_SWITCHER = ["android", "app", "src", "main", "java", "com", "fieldrepository", "app", "ui", "RecordSwitcher.kt"];
const KOTLIN_MAIN = ["android", "app", "src", "main", "java", "com", "fieldrepository", "app", "MainActivity.kt"];
const KOTLIN_PICKERS = ["android", "app", "src", "main", "java", "com", "fieldrepository", "app", "ui", "RecordPickers.kt"];

function row(fields: Partial<SwitchableRecord>): SwitchableRecord {
  return { id: "r1", ...fields } as SwitchableRecord;
}

test.describe("the option labels", () => {
  test("an artisan reads as name then place", () => {
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: "Bagru" }))).toBe("Ram Kumar · Bagru");
  });

  test("a missing second half takes the separator with it", () => {
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: "" }))).toBe("Ram Kumar");
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: "   " }))).toBe("Ram Kumar");
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: null }))).toBe("Ram Kumar");
  });

  test("a craft reads as name then place, a process as name then parent product", () => {
    expect(recordOptionLabel("craft", row({ name: "Block printing" }))).toBe("Block printing");
    expect(recordOptionLabel("craft", row({ name: "Block printing", place: "Bagru" }))).toBe("Block printing · Bagru");
    expect(recordOptionLabel("process", row({ name: "Dyeing" }))).toBe("Dyeing");
    expect(recordOptionLabel("process", row({ name: "Dyeing", product: { productName: "Cotton scarf" } }))).toBe(
      "Dyeing · Cotton scarf"
    );
  });

  test("a product and a tool read as their own name then the artisan", () => {
    expect(recordOptionLabel("product", row({ productName: "Cotton scarf", artisanName: "Ram Kumar" }))).toBe(
      "Cotton scarf · Ram Kumar"
    );
    expect(recordOptionLabel("tool", row({ toolkitName: "Bamboo comb", artisanName: "Ram Kumar" }))).toBe(
      "Bamboo comb · Ram Kumar"
    );
  });

  test("a record with no name at all is named by its type", () => {
    expect(recordOptionLabel("artisan", row({}))).toBe("Untitled artisan");
    expect(recordOptionLabel("product", row({}))).toBe("Untitled product");
    expect(recordOptionLabel("tool", row({ toolkitName: "  " }))).toBe("Untitled tool");
  });

  test("the separator is the spaced middle dot", () => {
    expect(LABEL_SEPARATOR).toBe(" · ");
  });
});

test.describe("where a picked record opens", () => {
  test("the three record types with routes of their own", () => {
    expect(recordEditHref("artisan", "abc")).toBe("/artisans/abc/edit");
    expect(recordEditHref("product", "abc")).toBe("/products/abc/edit");
    expect(recordEditHref("tool", "abc")).toBe("/tools/abc/edit");
  });

  test("the two edited by an inline form carry the intent as a parameter", () => {
    expect(recordEditHref("craft", "abc")).toBe("/crafts?edit=abc");
    expect(recordEditHref("process", "abc")).toBe("/processes?edit=abc");
    expect(isInlineEditSurface("craft")).toBe(true);
    expect(isInlineEditSurface("process")).toBe(true);
    expect(isInlineEditSurface("artisan")).toBe(false);
  });

  test("an id is encoded, on both shapes", () => {
    expect(recordEditHref("craft", "a b&c")).toBe("/crafts?edit=a%20b%26c");
    expect(recordEditHref("tool", "a b&c")).toBe("/tools/a%20b%26c/edit");
  });

  test("the parameter name is the one the deep-link hook actually reads", () => {
    const hook = read("frontend", "components", "hooks", "useEditDeepLink.ts");
    expect(hook).toContain(`export const EDIT_PARAM = "${EDIT_QUERY_PARAM}";`);
  });
});

test.describe("the sentence under the record dropdown", () => {
  const base = {
    kind: "artisan" as RecordKind,
    state: "loaded" as const,
    loadedForWorkshop: "w1",
    workshopId: "w1",
    shown: 3
  };

  test("a settled list with rows in it says nothing", () => {
    expect(recordListMessage(base)).toBeNull();
  });

  test("an empty workshop is only announced once its rows have arrived", () => {
    expect(recordListMessage({ ...base, shown: 0 })).toBe(
      "No artisans in this workshop yet. Pick another workshop above, or file the first one."
    );
    expect(recordListMessage({ ...base, state: "pending", loadedForWorkshop: null, shown: 0 })).toBe(
      "Loading this workshop's records…"
    );
  });

  test("rows belonging to the workshop just left are not described as this one's", () => {
    expect(recordListMessage({ ...base, workshopId: "w2", shown: 12 })).toBe("Loading this workshop's records…");
    expect(recordsAreForWorkshop("w1", "w2")).toBe(false);
    expect(recordsAreForWorkshop(null, "w2")).toBe(false);
    expect(recordsAreForWorkshop("w2", "w2")).toBe(true);
  });

  test("a failed load is named as a failed load and reassures about the open record", () => {
    expect(recordListMessage({ ...base, state: "unavailable", loadedForWorkshop: null, shown: 0 })).toBe(
      "These artisans could not be loaded. The record open below is unaffected."
    );
    expect(
      recordListMessage({ ...base, kind: "process", state: "unavailable", loadedForWorkshop: null, shown: 0 })
    ).toBe("These processes could not be loaded. The record open below is unaffected.");
  });

  test("under All workshops the sentences name no workshop", () => {
    const all = { ...base, workshopId: "", loadedForWorkshop: "" };
    expect(recordListMessage(all)).toBeNull();
    expect(recordListMessage({ ...all, shown: 0 })).toBe("No artisans yet.");
    expect(recordListMessage({ ...all, state: "pending", loadedForWorkshop: null })).toBe("Loading artisans…");
    expect(recordListMessage({ ...all, loadedForWorkshop: "w1" })).toBe("Loading artisans…");
    expect(recordListMessage({ ...all, state: "unavailable" })).toBe(
      "These artisans could not be loaded. The record open below is unaffected."
    );
  });

  test("the two nouns are kept apart for every kind", () => {
    expect(recordNoun("tool")).toBe("tools in this workshop");
    expect(recordPlural("tool")).toBe("tools");
    expect(recordSingular("tool")).toBe("tool");
  });

  test("the title names the record type", () => {
    expect(recordSwitcherTitle("artisan")).toBe("Open a different artisan");
    expect(recordSwitcherTitle("process")).toBe("Open a different process");
  });
});

test.describe("when a keystroke is allowed to cost a request", () => {
  test("a whole list never sends a keystroke anywhere", () => {
    expect(shouldSearchServer(null, "ram")).toBe(false);
  });

  test("a cut list searches the server, and a blank box is not a search", () => {
    const cut = recordListCut("artisan", 100, 240) as ListCut;
    expect(cut).not.toBeNull();
    expect(shouldSearchServer(cut, "ram")).toBe(true);
    expect(shouldSearchServer(cut, "")).toBe(false);
    expect(shouldSearchServer(cut, "   ")).toBe(false);
  });

  test("a complete workshop list is not a cut at all", () => {
    expect(recordListCut("artisan", 40, 40)).toBeNull();
    expect(recordListCut("artisan", 40, undefined as unknown as number)).toBeNull();
  });

  test("a cut under All workshops counts the bare plural", () => {
    expect(cappedListNotice(recordListCut("artisan", 100, 240, "") as ListCut, "search")).toBe(
      "Showing 100 of 240 artisans — type to search all 240."
    );
    expect(cappedListNotice(recordListCut("artisan", 100, 240, "w1") as ListCut, "search")).toBe(
      "Showing 100 of 240 artisans in this workshop — type to search all 240."
    );
  });

  test("the debounce is the 350ms every list page already uses", () => {
    expect(SEARCH_DEBOUNCE_MS).toBe(350);
    expect(read("frontend", "components", "forms", "RecordSwitcher.tsx")).toContain("SEARCH_DEBOUNCE_MS)");
  });

  test("a searchable cut invites typing rather than warning about a limit", () => {
    const cut = recordListCut("artisan", 100, 240) as ListCut;
    expect(cappedListNotice(cut, "search")).toBe(
      "Showing 100 of 240 artisans in this workshop — type to search all 240."
    );
    expect(cappedListNotice(cut)).toBe(
      "Showing 100 of 240 artisans in this workshop. To find another, use Browse records from the menu."
    );
  });
});

test.describe("the workshop dropdown", () => {
  test("opens with All workshops, the blank id, ahead of every workshop", () => {
    expect(ALL_WORKSHOPS_LABEL).toBe("All workshops");
    expect(
      recordSwitchWorkshopOptions([
        { value: "w1", label: "Pune" },
        { value: "", label: "stray" }
      ])
    ).toEqual([
      { value: "", label: "All workshops" },
      { value: "w1", label: "Pune" }
    ]);
  });

  test("moves to the record's own workshop once known, and never off a reader's pick", () => {
    expect(recordSwitchWorkshopFor("", "w2", ["w1", "w2"])).toBe("w2");
    expect(recordSwitchWorkshopFor("", "w9", ["w1", "w2"])).toBe("");
    expect(recordSwitchWorkshopFor("", null, ["w1"])).toBe("");
    expect(recordSwitchWorkshopFor("w1", "w2", ["w1", "w2"])).toBe("w1");
  });
});

test.describe("the handset's copy of all of it", () => {
  const kotlin = () => read(...KOTLIN_SWITCHER);

  test("the handset prints the same four sentences", () => {
    const source = kotlin();
    for (const fragment of [
      " could not be loaded. The record open below is unaffected.",
      "Loading this workshop's records…",
      " yet. Pick another workshop above, or file the first one.",
      "Open a different "
    ]) {
      expect(source, `RecordSwitcher.kt no longer contains: ${fragment}`).toContain(fragment);
    }
  });

  test("the handset opens on the same All workshops row and says the same two sentences", () => {
    const source = kotlin();
    expect(source).toContain(`ALL_WORKSHOPS_LABEL = "${ALL_WORKSHOPS_LABEL}"`);
    expect(source).toContain('"Loading ${kind.plural}…"');
    expect(source).toContain('"No ${kind.plural} yet."');
  });

  test("the handset declares the same record kinds in the same order", () => {
    const source = kotlin();
    const declared = RECORD_KINDS.map(
      (kind) => `${kind.toUpperCase()}("${recordSingular(kind)}", "${recordPlural(kind)}")`
    );
    expect(source).toContain(declared.join(",\n    ") + ";");
  });

  test("the handset agrees about the separator and the debounce", () => {
    const source = kotlin();
    expect(source).toContain(`const val LABEL_SEPARATOR = "${LABEL_SEPARATOR}"`);
    expect(source).toContain(`const val SEARCH_DEBOUNCE_MS = ${SEARCH_DEBOUNCE_MS}L`);
  });

  test("the handset's capped-list sentences still match", () => {
    const source = read(...KOTLIN_PICKERS);
    for (const fragment of [
      " — type to search all ",
      ". To find another, use Browse records from the menu.",
      " — use the pager to reach the rest, which are not ",
      "searched by the box above.",
      " could be loaded. Try again."
    ]) {
      expect(source, `RecordPickers.kt no longer contains: ${fragment}`).toContain(fragment);
    }
  });
});

test.describe("every update surface actually carries it", () => {
  const SURFACES: Array<{ parts: string[]; kind: RecordKind }> = [
    { parts: ["frontend", "app", "(protected)", "artisans", "[id]", "edit", "page.tsx"], kind: "artisan" },
    { parts: ["frontend", "app", "(protected)", "products", "[id]", "edit", "page.tsx"], kind: "product" },
    { parts: ["frontend", "app", "(protected)", "tools", "[id]", "edit", "page.tsx"], kind: "tool" },
    { parts: ["frontend", "app", "(protected)", "crafts", "page.tsx"], kind: "craft" },
    { parts: ["frontend", "app", "(protected)", "processes", "page.tsx"], kind: "process" }
  ];

  for (const surface of SURFACES) {
    test(`${surface.kind} — its update page mounts the switcher for its own kind`, () => {
      const source = read(...surface.parts);
      expect(source).toContain("<RecordSwitcher");
      expect(source).toContain(`kind="${surface.kind}"`);
    });
  }

  test("the handset mounts it on its edit screen, at the destination every other route uses", () => {
    const main = read(...KOTLIN_MAIN);
    expect(main).toContain("RecordSwitcher(");
    expect(main).toContain("onOpenRecord = ");
    expect(main).toContain("Screen.Edit(s.mode, picked)");
  });

  test("the shared select still reports its filter box out", () => {
    const primitive = read("frontend", "components", "ui", "SearchableSelect.tsx");
    expect(primitive).toContain("onSearch?: (query: string) => void;");
    expect(primitive).toContain("searchRef.current?.(next);");
    expect(read("frontend", "components", "ui", "Dropdown.tsx")).toContain("onSearch={onSearch}");
    expect(read("frontend", "components", "forms", "RecordSwitcher.tsx")).toContain("onSearch={setQuery}");
  });
});
