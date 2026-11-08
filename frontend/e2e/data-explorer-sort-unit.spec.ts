import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  availableKeys,
  DEFAULT_DIR,
  directionsOf,
  effectiveSort,
  LIST_DEFAULT,
  parseSort,
  readSort,
  SORT_DIRECTIONS,
  SORT_KEYS,
  SORT_LABEL,
  sortParam,
  sortStorageKey,
  sortWords,
  treeLevel,
  withKey,
  writeSort,
  type SortList
} from "@/lib/dataExplorer/sorting";

import { fakeSorted, SORTED_FILES, SORTED_FOLDER } from "./support/dataExplorerFake";

const SHARED = join(__dirname, "..", "..", "shared");

type Contract = {
  sorting: {
    keys: string[];
    labels: Record<string, string>;
    directions: Record<string, Record<string, string>>;
    defaultDir: Record<string, string>;
    routes: Record<string, { allowed: string[]; default: string }>;
  };
};

function contract(): Contract {
  return JSON.parse(readFileSync(join(SHARED, "data-explorer-contract.json"), "utf8")) as Contract;
}

class MemoryStorage {
  values = new Map<string, string>();
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
  removeItem(key: string) {
    this.values.delete(key);
  }
}

function withWindow<T>(storage: unknown, run: () => T): T {
  const holder = globalThis as unknown as { window?: unknown };
  const before = holder.window;
  holder.window = { localStorage: storage };
  try {
    return run();
  } finally {
    holder.window = before;
  }
}

test.describe("@qf:X1 sort choices", () => {
  test("the labels, direction words and defaults are the contract's", () => {
    const sorting = contract().sorting;
    expect([...SORT_KEYS]).toEqual(sorting.keys);
    expect(SORT_LABEL).toEqual(sorting.labels);
    expect(SORT_DIRECTIONS).toEqual(sorting.directions);
    expect(DEFAULT_DIR).toEqual(sorting.defaultDir);
    for (const [list, route] of Object.entries(sorting.routes)) {
      const fallback = LIST_DEFAULT[list as SortList];
      expect(`${fallback.key}:${fallback.dir}`).toBe(route.default);
      expect(route.allowed).toContain(fallback.key);
    }
  });

  test("a sort reads key and direction and refuses anything else", () => {
    expect(parseSort("name:desc")).toEqual({ key: "name", dir: "desc" });
    expect(parseSort({ key: "size", dir: "asc" })).toEqual({ key: "size", dir: "asc" });
    for (const bad of ["", "name", "name:up", "shoe:asc", "relevance:asc", null, 4, { key: "name" }]) expect(parseSort(bad)).toBeNull();
  });

  test("the default is never sent and each list has its own default", () => {
    expect(sortParam({ key: "name", dir: "asc" }, "tree")).toBeUndefined();
    expect(sortParam({ key: "name", dir: "desc" }, "tree")).toBe("name:desc");
    expect(sortParam({ key: "folder", dir: "asc" }, "transcripts-list")).toBeUndefined();
    expect(sortParam({ key: "added", dir: "desc" }, "browse")).toBeUndefined();
    expect(sortParam({ key: "name", dir: "asc" }, "browse")).toBe("name:asc");
    expect(sortParam(null, "search-unified")).toBeUndefined();
  });

  test("picking a key starts in its natural direction and the panel lists that one first", () => {
    expect(withKey({ key: "name", dir: "asc" }, "recorded")).toEqual({ key: "recorded", dir: "desc" });
    expect(withKey({ key: "size", dir: "asc" }, "size")).toEqual({ key: "size", dir: "asc" });
    expect(directionsOf("recorded")).toEqual(["desc", "asc"]);
    expect(directionsOf("name")).toEqual(["asc", "desc"]);
    expect(directionsOf("relevance")).toEqual(["desc"]);
    expect(sortWords({ key: "recorded", dir: "asc" })).toBe("Date recorded · Oldest");
    expect(sortWords({ key: "size", dir: "desc" })).toBe("Size · Largest");
  });

  test("only the keys the server offers are shown, in menu order, and a remembered key it does not offer falls back", () => {
    expect(availableKeys(["size", "name", "bogus", "recorded"])).toEqual(["name", "recorded", "size"]);
    expect(availableKeys(undefined)).toEqual([]);
    expect(effectiveSort({ key: "duration", dir: "desc" }, ["name", "size"], "tree")).toEqual(LIST_DEFAULT.tree);
    expect(effectiveSort({ key: "size", dir: "asc" }, ["name", "size"], "tree")).toEqual({ key: "size", dir: "asc" });
  });

  test("a level is the taxonomy and depth, remembered per viewer", () => {
    expect(treeLevel("")).toBe("tree:root:0");
    expect(treeLevel("by-workshop/w1/crafts/_none")).toBe("tree:by-workshop:4");
    expect(sortStorageKey("u1", "transcripts")).toBe("dataExplorer.sort.u1.transcripts");
    const storage = new MemoryStorage();
    withWindow(storage, () => {
      writeSort("u1", "tree:by-workshop:2", { key: "recorded", dir: "asc" }, "tree");
      expect(readSort("u1", "tree:by-workshop:2")).toEqual({ key: "recorded", dir: "asc" });
      expect(readSort("u2", "tree:by-workshop:2")).toBeNull();
      writeSort("u1", "tree:by-workshop:2", { key: "name", dir: "asc" }, "tree");
      expect(storage.values.size).toBe(0);
      storage.setItem(sortStorageKey("u1", "transcripts"), "nonsense");
      expect(readSort("u1", "transcripts")).toBeNull();
    });
  });

  test("storage that throws never breaks the explorer", () => {
    const broken = {
      getItem() {
        throw new Error("blocked");
      },
      setItem() {
        throw new Error("blocked");
      },
      removeItem() {
        throw new Error("blocked");
      }
    };
    withWindow(broken, () => {
      expect(readSort("u1", "transcripts")).toBeNull();
      expect(() => writeSort("u1", "transcripts", { key: "name", dir: "desc" }, "transcripts-list")).not.toThrow();
    });
  });

  test("the fake folder sorts the way the server does: folders first, missing last, ties by name", () => {
    const entries = [
      ...SORTED_FILES.map((file) => ({ ...file, kind: "file", path: `${SORTED_FOLDER}/${file.name}`, sizeBytes: file.bytes })),
      { kind: "folder", name: "zz", path: "z", bytes: null },
      { kind: "file", name: "notes.txt", path: "n", sizeBytes: null }
    ];
    expect(fakeSorted(entries, "size:desc").map((entry) => entry.name)).toEqual(["zz", "Bravo.mp4", "delta.jpg", "echo.pdf", "alpha.jpg", "charlie.m4a", "notes.txt"]);
    expect(fakeSorted(entries, "size:asc").map((entry) => entry.name)).toEqual(["zz", "charlie.m4a", "alpha.jpg", "echo.pdf", "delta.jpg", "Bravo.mp4", "notes.txt"]);
  });
});
