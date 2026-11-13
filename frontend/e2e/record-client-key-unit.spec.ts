import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CLIENT_KEY_ENDPOINTS, endpointTakesClientKey, mintClientKey, saveOrQueue } from "@/lib/offline";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const OFFLINE = read("lib", "offline.ts");
const PACKAGE_JSON = read("package.json");

const SAVE_OR_QUEUE = OFFLINE.slice(OFFLINE.indexOf("export async function saveOrQueue"));

function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

function captureRequests(): { bodies: unknown[]; restore: () => void } {
  const bodies: unknown[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (_input: unknown, init?: { body?: unknown }) => {
    bodies.push(typeof init?.body === "string" ? JSON.parse(init.body) : init?.body);
    return new Response(JSON.stringify({ id: "srv-1" }), {
      status: 200,
      headers: { "content-type": "application/json" }
    });
  }) as unknown as typeof globalThis.fetch;
  return { bodies, restore: () => { globalThis.fetch = original; } };
}

async function sentBody(endpoint: string, method: "POST" | "PATCH", body: unknown): Promise<Record<string, unknown>> {
  const { bodies, restore } = captureRequests();
  try {
    const outcome = await saveOrQueue<{ id: string }>({ label: "test", endpoint, method, body });
    expect(outcome.queued, "this browser is online in a test — the online path is the one under test").toBe(false);
  } finally {
    restore();
  }
  expect(bodies.length, `${method} ${endpoint} must have made exactly one request`).toBe(1);
  return bodies[0] as Record<string, unknown>;
}

test("four create endpoints take a key, and /artisans deliberately does not", () => {
  expect([...CLIENT_KEY_ENDPOINTS].sort()).toEqual(["/processes", "/products", "/tools", "/workshops"]);
  for (const endpoint of CLIENT_KEY_ENDPOINTS) expect(endpointTakesClientKey(endpoint)).toBe(true);

  for (const endpoint of ["/artisans", "/crafts", "/questionnaire/interviews", "/media", "/review"]) {
    expect(endpointTakesClientKey(endpoint), `${endpoint} has no key column`).toBe(false);
  }
});

test("the match is exact, because three live POST routes sit under those paths", () => {
  expect(endpointTakesClientKey("/workshops/unmapped/map"), "a POST that is not a create").toBe(false);
  expect(endpointTakesClientKey("/workshops/abc123"), "an update path").toBe(false);
  expect(endpointTakesClientKey("/workshops/abc/questionnaire")).toBe(false);
  expect(endpointTakesClientKey("/toolsomething"), "a prefix is not a path").toBe(false);
  expect(endpointTakesClientKey("/tools/"), "a trailing slash is the same route").toBe(true);
  expect(endpointTakesClientKey("/tools?draft=1"), "and a query string is not part of it").toBe(true);
});

test("a minted key is unique per save and fits the column it is going into", () => {
  const keys = new Set(Array.from({ length: 200 }, () => mintClientKey()));
  expect(keys.size, "200 mints, 200 distinct keys").toBe(200);
  for (const key of keys) {
    expect(key.length, "the server declares max_length=200").toBeLessThanOrEqual(200);
    expect(key.length, "and an empty key is not a key").toBeGreaterThan(8);
  }
});

test("the FIRST POST carries the key, which is the entire point of minting it here", async () => {
  const body = await sentBody("/tools", "POST", { toolkitName: "Anvil" });
  expect(typeof body.clientKey, "the create request itself is keyed").toBe("string");
  expect((body.clientKey as string).length).toBeGreaterThan(8);
  expect(body.toolkitName, "and the caller's own body is untouched").toBe("Anvil");
});

test("every keyed create endpoint is keyed, and each save gets its own key", async () => {
  const keys: string[] = [];
  for (const endpoint of CLIENT_KEY_ENDPOINTS) {
    const body = await sentBody(endpoint, "POST", { title: "x" });
    expect(typeof body.clientKey, `${endpoint} POST must be keyed`).toBe("string");
    keys.push(body.clientKey as string);
  }
  expect(new Set(keys).size, "one key per save — a shared key answers the second create from the first row").toBe(
    keys.length
  );
});

test("a PATCH is never keyed, because extra_forbidden is a 422 the outbox retries for ever", async () => {
  const body = await sentBody("/tools/tool-1", "PATCH", { toolkitName: "Anvil" });
  expect("clientKey" in body, "no key on an update, not even a null one").toBe(false);
});

test("an endpoint with no key column is never keyed either", async () => {
  for (const [endpoint, why] of [
    ["/artisans", "no clientKey column — guarded by the Aadhaar unique index instead"],
    ["/crafts", "no clientKey column"],
    ["/workshops/unmapped/map", "a POST that sits UNDER a keyed path and is not a create"]
  ] as const) {
    const body = await sentBody(endpoint, "POST", { name: "x" });
    expect("clientKey" in body, `${endpoint}: ${why}`).toBe(false);
  }
});

test("a caller that supplies its own key is never overruled", async () => {
  expect((await sentBody("/tools", "POST", { clientKey: "mine-1" })).clientKey).toBe("mine-1");
  expect((await sentBody("/tools", "POST", { clientKey: null })).clientKey).toBeNull();
});

test("one payload string is built, and both the request and the queued entry send it", () => {
  const code = codeOnly(SAVE_OR_QUEUE);
  expect((code.match(/JSON\.stringify\(/g) ?? []).length, "exactly one serialisation in saveOrQueue").toBe(1);
  expect(code).toContain("const payload = JSON.stringify(bodyWithClientKey(endpoint, method, writeBody(body, method)));");
  expect(code, "the queued entry sends that same string").toContain("body: payload");
  expect(code, "and so does the online request").toContain("apiFetch<T>(endpoint, { method, body: payload })");

  expect(
    code.indexOf("const payload = JSON.stringify"),
    "the payload is built before the online request is made"
  ).toBeLessThan(code.indexOf("apiFetch<T>(endpoint"));
  const queueBody = code.slice(code.indexOf("const queue = async () => {"), code.indexOf("};", code.indexOf("const queue")));
  expect(queueBody, "nothing is minted inside queue()").not.toContain("mintClientKey");
  expect(queueBody).not.toContain("bodyWithClientKey");

  const mints = (codeOnly(OFFLINE).match(/mintClientKey\(\)/g) ?? []).length;
  expect(mints, "the declaration, plus exactly one call").toBe(2);
  expect(codeOnly(OFFLINE), "and the call is the merge, not something else").toContain("clientKey: mintClientKey()");
});

test("test:unit selects the -unit specs and nothing that needs a server", () => {
  const scripts = (JSON.parse(PACKAGE_JSON) as { scripts: Record<string, string> }).scripts;
  expect(scripts["test:e2e"], "the full run stays available").toBe("playwright test");

  const command = scripts["test:unit"];
  expect(command, "test:unit must exist").toBeTruthy();
  const quoted = /^playwright test "(.+)"$/.exec(command);
  expect(quoted, `test:unit must be playwright with one quoted pattern, got: ${command}`).not.toBeNull();

  const pattern = new RegExp(quoted![1]);
  expect(pattern.test("e2e/record-client-key-unit.spec.ts"), "it selects this spec").toBe(true);
  expect(pattern.test("e2e/record-parity-fields-unit.spec.ts"), "and its sibling").toBe(true);
  expect(pattern.test("e2e/record-form-dictation-unit.spec.ts")).toBe(true);
  expect(pattern.test("e2e/back-control.spec.ts"), "and not the ones that need a signed-in server").toBe(false);
  expect(pattern.test("e2e/nav-sheet-scroll.spec.ts")).toBe(false);
});
