import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { APP_VERSION, ApiError, clockOffset, recordClockOffset } from "@/lib/api";
import { contractError, CONTRACT_VERSION, toGridView, type CoverageGrid } from "@/lib/questionnaireApi";
import { CONTRACT } from "./questionnaire-contract-fixture";

/**
 * The typed client against the frozen contract (`shared/questionnaire-contract.json`): the same
 * version, a call for every route, errors read from `{detail:{code}}`, the contract grid read into
 * the screens' grid, and X-Client-Time / X-App-Version on every request (the proxy forwards both).
 */

const API = readFileSync(join(__dirname, "..", "lib", "questionnaireApi.ts"), "utf8");

test("contract version and error codes", () => {
  expect(CONTRACT_VERSION).toBe(CONTRACT.contractVersion);
  for (const code of CONTRACT.errorCodes) expect(API, code).toContain(`"${code}"`);
  const err = new ApiError(409, "Changed on another device.", CONTRACT.errorExample);
  expect(contractError(err)?.code).toBe("STALE_VERSION");
  expect(contractError(new Error("x"))).toBeNull();
});

test("every contract route has a client call", () => {
  const legacy = new Set(["/api/questionnaire/interviews", "/api/questionnaire/corpus.jsonl", "/api/questionnaire/corpus.csv"]);
  for (const { method, path } of CONTRACT.routes) {
    if (legacy.has(path)) continue;
    const literal = path
      .replace(/^\/api\/questionnaire/, "${Q}")
      .replace(/^\/api/, "")
      .replace(/\{id\}|\{targetId\}|\{artisanId\}|\{clientClipKey\}/g, "")
      .split("/")
      .filter((part) => part && part !== "${Q}")
      .pop()!;
    expect(API, `${method} ${path}`).toContain(literal);
  }
  // Creates carry an Idempotency-Key; the body's clientKey/clientClipKey is the key.
  expect(API).toContain('json("POST", body, body.clientKey)');
  expect(API).toContain('json("POST", body, body.clientClipKey)');
});

test("the contract grid reads into the screens' grid", () => {
  const view = toGridView(CONTRACT.examples.CoverageGrid as CoverageGrid);
  expect(view.sections).toEqual([{ id: "secD", code: "D", title: "Process", questions: undefined }]);
  expect(view.cells).toEqual([{ artisanId: "a1", targetKey: "S:secD", state: "PARTIAL", count: 1, partial: { done: 4, of: 10 } }]);
});

test("X-Client-Time, X-App-Version and the clock offset", () => {
  const api = readFileSync(join(__dirname, "..", "lib", "api.ts"), "utf8");
  expect(api).toContain('headers.set("X-Client-Time", new Date(sentAt).toISOString());');
  expect(api).toContain('headers.set("X-App-Version", APP_VERSION);');
  expect(APP_VERSION).toMatch(/^web-/);
  recordClockOffset("Wed, 01 Jul 2026 12:54:10 GMT", Date.parse("2026-07-01T12:54:00Z"), Date.parse("2026-07-01T12:54:02Z"));
  expect(clockOffset()?.offsetMs).toBe(9000);
  const proxy = readFileSync(join(__dirname, "..", "lib", "server", "apiProxy.ts"), "utf8");
  expect(proxy).toContain('"x-client-time"');
  expect(proxy).toContain('"x-app-version"');
});
