import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  BUNDLED_RULES,
  completenessAria,
  completenessOf,
  completenessSentence,
  evaluateCompleteness,
  parseCompletenessFilter,
  pickRules,
  withFocus,
  type CompletenessRules
} from "@/lib/completenessCore";
import { focusField, focusKeyFromSearch } from "@/lib/forms";
import { decodeListReturn, encodeListReturn } from "@/lib/listReturn";

/**
 * The web half of record completeness (2026-10-18), against `shared/record-completeness.json` — the
 * table both portals carry byte for byte, which the server and the handset also run. The evaluator
 * and the sentence are held to the shared vectors and to the same edge cases the server's
 * `tests/test_record_completeness.py` pins; the focus helper is run in a real page.
 */

const ROOT = join(__dirname, "..", "..");
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf-8").split("\r\n").join("\n");

test("the bundled copy is the shared file", () => {
  expect(read("frontend", "lib", "generated", "record-completeness.json")).toBe(read("shared", "record-completeness.json"));
});

test("the shared vectors hold", () => {
  const rules = JSON.parse(read("shared", "record-completeness.json")) as CompletenessRules;
  expect(rules.vectors.length).toBeGreaterThan(0);
  for (const vector of rules.vectors) {
    const block = evaluateCompleteness(vector.kind, vector.record, vector.counts, rules);
    expect(block.filled).toBe(vector.expect.filled);
    expect(block.total).toBe(vector.expect.total);
    expect(block.missing.map((item) => item.key)).toEqual(vector.expect.missing);
    expect(block.requiredMissing).toBe(vector.expect.requiredMissing);
    expect(completenessSentence(block)).toBe(vector.expect.sentence);
  }
});

const FULL = { dateOfBirth: "1970-01-01", craftStartDate: "1990-01-01", craftId: "c", dos: "d", donts: "n", locationId: "l" };

test("sentence and percent edges match the server's", () => {
  const cases: Array<[Record<string, unknown>, Record<string, number>, string, number]> = [
    [{}, {}, "7 fields left: date of birth, craft start date, craft, +4 more", 0],
    [FULL, { photos: 0 }, "1 field left: photo", 85],
    [FULL, { photos: 2 }, "Complete", 100],
    [{ ...FULL, dos: "  ", locationId: null }, { photos: 1 }, "2 fields left: do's, location", 71]
  ];
  for (const [record, counts, sentence, percent] of cases) {
    const block = evaluateCompleteness("artisan", record, counts);
    expect(completenessSentence(block)).toBe(sentence);
    expect(block.percent).toBe(percent);
  }
});

test("zero is present and UNKNOWN is not", () => {
  const product = { artisanId: "a", sellingPrice: 0, costOfMaking: 0, timeTakenToCompleteProduct: "2 days", heightInches: 0, marketDemand: "UNKNOWN" };
  expect(evaluateCompleteness("product", product, { photos: 1 }).missing.map((m) => m.key)).toEqual(["marketDemand"]);
});

test("the server's block wins over the local evaluator", () => {
  const server = { rulesVersion: 1, filled: 1, total: 2, percent: 50, requiredMissing: 0, missing: [{ key: "x", label: "X", words: "x", level: "recommended" as const }] };
  expect(completenessOf("artisan", { completeness: server })).toBe(server);
  expect(completenessOf("artisan", {}).total).toBe(7);
  expect(completenessAria(server)).toBe("1 of 2 fields filled");
});

test("a pill keeps either Update shape intact", () => {
  expect(withFocus("/artisans/a1/edit", "craftStartDate")).toBe("/artisans/a1/edit?focus=craftStartDate");
  expect(withFocus("/workshops?edit=w1", "photo")).toBe("/workshops?edit=w1&focus=photo");
  expect(focusKeyFromSearch("?edit=w1&focus=photo")).toBe("photo");
  expect(focusKeyFromSearch("?focus=%22%3E%3Cimg")).toBeNull();
});

test("the filter value and the rules pick are defensive", () => {
  expect(parseCompletenessFilter("required")).toBe("required");
  expect(parseCompletenessFilter("everything")).toBe("");
  expect(pickRules(BUNDLED_RULES, null)).toBe(BUNDLED_RULES);
  expect(pickRules(BUNDLED_RULES, { ...BUNDLED_RULES, version: 0 })).toBe(BUNDLED_RULES);
  const newer = { ...BUNDLED_RULES, version: BUNDLED_RULES.version + 1 };
  expect(pickRules(BUNDLED_RULES, newer)).toBe(newer);
  expect(pickRules(BUNDLED_RULES, { contract: "other", version: 99, kinds: {} })).toBe(BUNDLED_RULES);
});

test("focusField scrolls to and focuses the anchored field, skipping a mirror twin", () => {
  // No browser: unit specs run without one in CI (checks.yml), so the DOM is a hand-made stand-in
  // that records what focusField asks of it.
  const calls: string[] = [];
  let focused: string | null = null;
  const typed = { focus: () => (focused = "typed") };
  const anchor = {
    dataset: {} as Record<string, string>,
    classList: { add: (name: string) => calls.push(`add:${name}`), remove: () => undefined },
    scrollIntoView: (options: ScrollIntoViewOptions) => calls.push(`scroll:${options.block}`),
    matches: () => false,
    querySelector: (selector: string) => {
      calls.push(`control:${selector}`);
      return typed;
    }
  };
  const root = {
    querySelector: (selector: string) => (selector === '[data-field="craftStartDate"]' ? anchor : null)
  };
  const g = globalThis as unknown as { document?: unknown; window?: unknown };
  const saved = { document: g.document, window: g.window };
  g.document = { documentElement: { dataset: { reducedMotion: "true" } } };
  g.window = { matchMedia: () => ({ matches: true }), setTimeout: () => 0 };
  try {
    expect(focusField("craftStartDate", root as unknown as ParentNode)).toBe(true);
    expect(focused).toBe("typed");
    expect(anchor.dataset.flash).toBe("true");
    expect(calls).toContain("scroll:center");
    // The mirror twin (tabindex -1) is never the control that takes focus.
    const control = calls.find((call) => call.startsWith("control:")) ?? "";
    expect(control).toContain(':not([tabindex="-1"])');
    expect(focusField("nowhere", root as unknown as ParentNode)).toBe(false);
  } finally {
    g.document = saved.document;
    g.window = saved.window;
  }
});

test("every record form carries the anchors its rules name", () => {
  // A form's anchors may sit in a control it mounts: the workshop's two dates are in DateRangeField.
  const anchors = (file: string) => {
    const files = file === "app/(protected)/workshops/page.tsx" ? [file, "components/forms/DateRangeField.tsx"] : [file];
    const text = files.map((one) => read("frontend", ...one.split("/"))).join("\n");
    return new Set([...text.matchAll(/(?:data-field|fieldKey|startFieldKey|endFieldKey)="([A-Za-z]+)"/g)].map((match) => match[1]));
  };
  const expected: Record<string, string[]> = {
    "components/forms/ArtisanForm.tsx": ["dateOfBirth", "craftStartDate", "craft", "dos", "donts", "location", "photo"],
    "components/forms/ProductForm.tsx": ["artisan", "sellingPrice", "costOfMaking", "timeTaken", "dimensions", "marketDemand", "photo"],
    "components/forms/ToolForm.tsx": ["artisan", "localName", "material", "dimensions", "maker", "traditionType", "replacementCost", "photo"],
    "app/(protected)/workshops/page.tsx": ["startDate", "endDate", "artisans", "crafts", "photo"],
    // The interview date is derived by the server, so it has no box to jump to.
    "app/(protected)/questionnaire/page.tsx": ["place", "language", "artisans", "answers", "recording"]
  };
  for (const [file, keys] of Object.entries(expected)) {
    const found = anchors(file);
    for (const key of keys) expect(found.has(key), `${file} lacks data-field "${key}"`).toBe(true);
  }
  // And the list above is every rule key, so a rule added without an anchor fails here.
  const kindOf: Record<string, string> = {
    "components/forms/ArtisanForm.tsx": "artisan",
    "components/forms/ProductForm.tsx": "product",
    "components/forms/ToolForm.tsx": "tool",
    "app/(protected)/workshops/page.tsx": "workshop",
    "app/(protected)/questionnaire/page.tsx": "interview"
  };
  for (const [file, kind] of Object.entries(kindOf)) {
    const keys = BUNDLED_RULES.kinds[kind as keyof typeof BUNDLED_RULES.kinds].map((rule) => rule.key).filter((key) => key !== "interviewDate");
    expect(new Set(expected[file])).toEqual(new Set(keys));
  }
});

test("Back to a list keeps its completeness filter, and only a known one", () => {
  const now = Date.now();
  const kept = decodeListReturn(encodeListReturn({ query: "", page: 2, scrollY: 0, completeness: "required" }, now), now);
  expect(kept?.completeness).toBe("required");
  const junk = decodeListReturn(JSON.stringify({ query: "", page: 1, scrollY: 0, completeness: "everything", savedAt: now }), now);
  expect(junk?.completeness).toBeUndefined();
});
