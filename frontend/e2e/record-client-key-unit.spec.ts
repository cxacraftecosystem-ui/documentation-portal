import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CLIENT_KEY_ENDPOINTS, endpointTakesClientKey, mintClientKey, saveOrQueue } from "@/lib/offline";

/**
 * THE CREATE-IDEMPOTENCY KEY, AND THE ONE PLACE IT CAN BE MINTED THAT ACTUALLY WORKS.
 *
 * ── THE DUPLICATE NO GUARD MADE OF REPLIES CAN SEE ──────────────────────────────────────────────
 *
 * `lib/offline.ts` already resumes a half-finished replay through `created` / `createdId`: once the
 * server has answered, re-sending the body would make a second record, so the entry records the
 * answer and the next pass skips to the media. That guard is a record of a REPLY, and it is blind to
 * the case it matters most in: the POST goes out, the server COMMITS the row, and the answer is lost
 * on the way back — a connection that died in those seconds, a captive portal, a closed tab. This
 * browser learned nothing, the entry is still queued, and the next pass sends the identical body and
 * creates a SECOND record. `clientKey` closes it from the other end: the key travels with the first
 * POST, so `client_key_replay` on the server answers the second landing from the row the first one
 * wrote.
 *
 * ── WHICH MEANS THE MINT SITE IS THE WHOLE FEATURE ──────────────────────────────────────────────
 *
 * Minting the key inside `queue()` is the arrangement that looks right — the key is "for the
 * outbox", so build it where the outbox entry is built — and it buys NOTHING. The first POST would
 * go out unkeyed; if its answer is lost, the entry queued behind it carries a key the server has
 * never seen, the replay matches no row, and it creates the duplicate. A feature that appears to
 * ship, passes review, and prevents exactly the zero cases it was written for.
 *
 * So the key is minted at the TOP of `saveOrQueue`, ABOVE the online `apiFetch`, and the tests below
 * EXECUTE that: they stub `fetch`, call the real `saveOrQueue`, and read the body the server would
 * have received. A source regex cannot tell a key that is in the request from one that is merely in
 * the file.
 *
 * ── AND THE THREE PLACES IT MUST NOT GO ─────────────────────────────────────────────────────────
 *
 * Every request model in that API is `extra="forbid"` and `clientKey` is declared on the CREATE
 * schemas alone. So a key on a PATCH, on an endpoint without the column, or on a POST route that
 * merely sits UNDER one of those paths is `extra_forbidden` — a 422. And a 422 is precisely what
 * this outbox triages as PERMANENT: the queued record would be marked failed and parked in front of
 * the researcher as a defect. Each of those three is a test below.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const OFFLINE = read("lib", "offline.ts");
const PACKAGE_JSON = read("package.json");

/** The `saveOrQueue` body alone — the region whose ORDER this spec is about. */
const SAVE_OR_QUEUE = OFFLINE.slice(OFFLINE.indexOf("export async function saveOrQueue"));

/** Comments in `lib/offline.ts` argue at length about where the key must NOT be minted. */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

/**
 * Stand in for the network and hand back the bodies that were actually sent.
 *
 * A REAL `Response`, not a hand-made object: `apiFetch` reads `status`, `headers.get`, `ok` and
 * `json()`, and a fake that happened to satisfy today's four would silently stop exercising the
 * fifth. Restored in `finally` by every caller, or one test's stub answers the next one's request.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The vocabulary — exactly four endpoints, matched exactly
 * ──────────────────────────────────────────────────────────────────────────── */

test("four create endpoints take a key, and /artisans deliberately does not", () => {
  expect([...CLIENT_KEY_ENDPOINTS].sort()).toEqual(["/processes", "/products", "/tools", "/workshops"]);
  for (const endpoint of CLIENT_KEY_ENDPOINTS) expect(endpointTakesClientKey(endpoint)).toBe(true);

  /*
    `/artisans` IS ABSENT ON PURPOSE. It has no `clientKey` column: its create is already guarded by
    the Aadhaar unique index, which answers a replay with a 409 the researcher can act on. Adding a
    key there is a schema change, not a client change, and a client that sent one would collect a
    422 on every queued artisan.
  */
  for (const endpoint of ["/artisans", "/crafts", "/questionnaire/interviews", "/media", "/review"]) {
    expect(endpointTakesClientKey(endpoint), `${endpoint} has no key column`).toBe(false);
  }
});

test("the match is exact, because three live POST routes sit under those paths", () => {
  /*
    A `startsWith` test is the obvious implementation and it posts a key into `/workshops/unmapped/map`
    (a POST), `/workshops/{id}/questionnaire` and every `/{type}/{id}` PATCH — four 422s from one
    convenience. The trailing slash and the query string are normalised because `/tools/` and `/tools`
    are the same route to FastAPI and would not be the same string here.
  */
  expect(endpointTakesClientKey("/workshops/unmapped/map"), "a POST that is not a create").toBe(false);
  expect(endpointTakesClientKey("/workshops/abc123"), "an update path").toBe(false);
  expect(endpointTakesClientKey("/workshops/abc/questionnaire")).toBe(false);
  expect(endpointTakesClientKey("/toolsomething"), "a prefix is not a path").toBe(false);
  expect(endpointTakesClientKey("/tools/"), "a trailing slash is the same route").toBe(true);
  expect(endpointTakesClientKey("/tools?draft=1"), "and a query string is not part of it").toBe(true);
});

test("a minted key is unique per save and fits the column it is going into", () => {
  const keys = new Set(Array.from({ length: 200 }, () => mintClientKey()));
  // Per SAVE, never per module or per session: two records queued on one device must not collide on
  // a column with a UNIQUE index, or the second one is answered with the first one's row.
  expect(keys.size, "200 mints, 200 distinct keys").toBe(200);
  for (const key of keys) {
    expect(key.length, "the server declares max_length=200").toBeLessThanOrEqual(200);
    expect(key.length, "and an empty key is not a key").toBeGreaterThan(8);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The mint site — executed against the real `saveOrQueue`
 * ──────────────────────────────────────────────────────────────────────────── */

test("the FIRST POST carries the key, which is the entire point of minting it here", async () => {
  /*
    THE ASSERTION THAT WOULD FAIL IF THE KEY WERE MINTED INSIDE `queue()`. That arrangement leaves
    this request unkeyed, so a lost answer means the replay matches no row and creates a second
    record — the exact duplicate `clientKey` exists to prevent, with the feature apparently shipped.
  */
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
  /*
    `clientKey` is declared on the CREATE schemas alone and every request model is `extra="forbid"`.
    A key on a correction is `extra_forbidden` — a 422 — and `isTransient` reads a 4xx as PERMANENT,
    so the queued edit would be marked failed and sat in front of the researcher on a prepaid
    connection. The key is absent, not null: an unknown key with ANY value is refused.
  */
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
  /*
    Nothing passes one today. The test is `"clientKey" in body` rather than truthiness, so a caller
    that deliberately sends `null` — meaning "no key, create a fresh row" — is honoured rather than
    silently given one. A replay guard that overwrites the caller's intent is a guard the caller
    cannot reason about.
  */
  expect((await sentBody("/tools", "POST", { clientKey: "mine-1" })).clientKey).toBe("mine-1");
  expect((await sentBody("/tools", "POST", { clientKey: null })).clientKey).toBeNull();
});

test("one payload string is built, and both the request and the queued entry send it", () => {
  /*
    EXECUTION CAN ONLY REACH THE ONLINE HALF HERE — the queue path needs IndexedDB, which Node does
    not have — so the queued half is proven structurally, and it is provable structurally because
    there is exactly ONE serialisation. The request and the outbox entry are the same STRING, so they
    cannot carry different keys however the file is later rearranged.
  */
  const code = codeOnly(SAVE_OR_QUEUE);
  expect((code.match(/JSON\.stringify\(/g) ?? []).length, "exactly one serialisation in saveOrQueue").toBe(1);
  expect(code).toContain("const payload = JSON.stringify(bodyWithClientKey(endpoint, method, body));");
  expect(code, "the queued entry sends that same string").toContain("body: payload");
  expect(code, "and so does the online request").toContain("apiFetch<T>(endpoint, { method, body: payload })");

  // ORDER, WHICH IS THE RULE THIS FILE IS ABOUT: the mint is above the request, not inside `queue`.
  expect(
    code.indexOf("const payload = JSON.stringify"),
    "the payload is built before the online request is made"
  ).toBeLessThan(code.indexOf("apiFetch<T>(endpoint"));
  const queueBody = code.slice(code.indexOf("const queue = async () => {"), code.indexOf("};", code.indexOf("const queue")));
  expect(queueBody, "nothing is minted inside queue()").not.toContain("mintClientKey");
  expect(queueBody).not.toContain("bodyWithClientKey");

  /*
    AND THE MINT HAS EXACTLY ONE CALL SITE IN THE WHOLE MODULE, so there is one place to read and one
    place a second one could be added. TWO occurrences, not one: `mintClientKey(): string` is the
    declaration and the count has to include it, which is the sort of off-by-one that makes a
    tightened assertion look like a real failure six months from now.
  */
  const mints = (codeOnly(OFFLINE).match(/mintClientKey\(\)/g) ?? []).length;
  expect(mints, "the declaration, plus exactly one call").toBe(2);
  expect(codeOnly(OFFLINE), "and the call is the merge, not something else").toContain("clientKey: mintClientKey()");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The scripts that run every unit spec, including this one
 * ──────────────────────────────────────────────────────────────────────────── */

test("test:unit selects the -unit specs and nothing that needs a server", () => {
  /*
    The `-unit` suffix is a CONTRACT, not a naming habit: `playwright.config.ts` deliberately starts
    no server, so every spec without the suffix is pointed at a dev server the developer is already
    running and at a real API it signs into. A CI job that ran `test:e2e` would sign in against
    nothing; `test:unit` is the half that can run anywhere, and the sibling repository spells these
    two scripts the same way so one instruction serves both.

    THE PATTERN IS EXECUTED AGAINST REAL FILENAMES rather than compared to a literal. A regex that is
    merely present can still select nothing.
  */
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

/**
 * ── WHAT THIS SPEC DOES NOT PROVE ───────────────────────────────────────────────────────────────
 *
 *  - THE DRAIN. `syncOutbox` replays `entry.body` — the same string built above — but exercising it
 *    needs IndexedDB, which Node does not have, so nothing here runs a replay. What the drain does
 *    with a keyed body is the server's question anyway: `backend/tests` owns `client_key_replay` and
 *    `client_key_replay_after_violation`, including the race where two requests reach the write at
 *    once.
 *  - THAT THE SERVER ACCEPTS THE KEY. These tests stub `fetch`. A `clientKey` sent to an endpoint
 *    whose schema does not declare it is a 422, and only `backend/tests` can tell you which
 *    endpoints those are — the list here is a MIRROR of `WorkshopCreate`/`ProductCreate`/
 *    `ToolCreate`/`ProcessCreate`, and a fifth create schema gaining the field obliges an entry in
 *    `CLIENT_KEY_ENDPOINTS` that nothing on this side will notice is missing.
 *  - ANY FORM. No form mentions `clientKey`; `saveOrQueue` merges it and the created record carries
 *    it back. `e2e/record-parity-fields-unit.spec.ts` is the form half of this sweep.
 */
