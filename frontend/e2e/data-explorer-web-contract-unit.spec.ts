import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { BROWSE_KINDS, browseKindOf } from "@/lib/dataExplorer/contract";
import { reviewDetail, reviewFraction, reviewTone, transcriptDetail } from "@/lib/dataExplorer/explorerView";
import { readUrlState } from "@/lib/dataExplorer/urlState";

import { OVERVIEW } from "./support/dataExplorerFake";
import { WORKSHOP_OVERVIEW } from "./support/designFidelityWebFake";

const SHARED = join(__dirname, "..", "..", "shared");

type Schema = string | Schema[] | { [key: string]: Schema };

function readShared<T>(name: string): T {
  return JSON.parse(readFileSync(join(SHARED, name), "utf8")) as T;
}

function typeName(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  return typeof value;
}

function drift(value: unknown, schema: Schema, where = "$"): string[] {
  if (Array.isArray(schema)) {
    if (!Array.isArray(value)) return [`${where} is ${typeName(value)}, the schema has an array`];
    return value.flatMap((item, index) => drift(item, schema[0], `${where}[${index}]`));
  }
  if (typeof schema === "object") {
    if (typeName(value) !== "object") return [`${where} is ${typeName(value)}, the schema has an object`];
    const record = value as Record<string, unknown>;
    const wanted = new Map(Object.keys(schema).map((key) => [key.replace(/\?$/, ""), key]));
    const out: string[] = [];
    for (const [key, raw] of wanted) if (!(key in record) && !raw.endsWith("?")) out.push(`${where}.${key} is missing`);
    for (const [key, item] of Object.entries(record)) {
      const raw = wanted.get(key);
      if (!raw) out.push(`${where}.${key} is not in the schema`);
      else out.push(...drift(item, schema[raw], `${where}.${key}`));
    }
    return out;
  }
  return schema.split("|").includes(typeName(value)) ? [] : [`${where} is ${typeName(value)}, the schema has ${schema}`];
}

const OVERVIEW_SCHEMA = readShared<{ schema: Schema }>("data-explorer-overview-schema.json").schema;

test("the e2e fakes send the overview in the shape the server sends", () => {
  expect(drift(OVERVIEW, OVERVIEW_SCHEMA)).toEqual([]);
  expect(drift(WORKSHOP_OVERVIEW, OVERVIEW_SCHEMA)).toEqual([]);
});

test("the schema check catches an artisan review sent as a word", () => {
  const broken = { ...OVERVIEW, artisans: OVERVIEW.artisans.map((artisan) => ({ ...artisan, review: "APPROVED" })) };
  expect(drift(broken, OVERVIEW_SCHEMA)).toContain("$.artisans[0].review is string, the schema has an object");
});

test("artisan counts read the way the totals cards do", () => {
  expect(reviewFraction({ approved: 2, pending: 1, total: 3 })).toBe("2 / 3");
  expect(reviewDetail({ approved: 2, pending: 1, total: 3 })).toBe("approved · 1 pending");
  expect(reviewTone({ approved: 3, pending: 0, total: 3 })).toBe("done");
  expect(reviewTone({ approved: 1, pending: 2, total: 3 })).toBe("waiting");
  expect(reviewTone({ approved: 0, pending: 0, total: 0 })).toBe("none");
  expect(transcriptDetail({ total: 2, ready: 1, translating: 0, failed: 1 })).toBe("1 ready · 0 translating · 1 failed");
});

test("browse by type sends the server's own type tokens", () => {
  const contract = readShared<{ routes: { id: string; query?: { type?: string } }[] }>("data-explorer-contract.json");
  const route = contract.routes.find((candidate) => candidate.id === "browse");
  const tokens = (route?.query?.type ?? "").split("|").map((token) => token.trim());
  expect([...BROWSE_KINDS]).toEqual(tokens);
  expect(browseKindOf("questionnaire")).toBe("interview");
  expect(browseKindOf("Interview")).toBe("interview");
  expect(browseKindOf("bogus")).toBe("");
  expect(readUrlState(new URLSearchParams("browse=questionnaire")).browse).toBe("interview");
});
