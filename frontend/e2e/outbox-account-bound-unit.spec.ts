import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import * as offline from "@/lib/offline";
import type { OutboxEntry } from "@/lib/offline";

type OwnerApi = {
  outboxEntryIsHeld: (owner: string | null | undefined, signedIn: string | null) => boolean;
  outboxEntriesForThisAccount: (entries: readonly OutboxEntry[], signedIn: string | null) => OutboxEntry[];
  outboxEntriesForAnotherAccount: (entries: readonly OutboxEntry[], signedIn: string | null) => number;
  outboxEntriesWithoutOwner: (entries: readonly OutboxEntry[], signedIn: string | null) => number;
};

const api = offline as unknown as OwnerApi;
const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function entry(label: string, ownerUserId: string | null | undefined, failure: string | null = null): OutboxEntry {
  return {
    id: label.length,
    label,
    createdAt: 0,
    endpoint: "/artisans",
    method: "POST",
    body: JSON.stringify({ name: "Dummy Person", aadhaarNumber: "234567890123" }),
    media: [],
    attempts: 0,
    failure,
    ownerUserId
  } as OutboxEntry;
}

test.describe("The offline outbox belongs to the account that queued it", () => {
  test("only the queuing account may send an entry; unknown owners and signed-out sessions are held", () => {
    expect(typeof api.outboxEntryIsHeld).toBe("function");
    expect(api.outboxEntryIsHeld("designer-a", "designer-a")).toBe(false);
    expect(api.outboxEntryIsHeld("designer-a", "designer-b")).toBe(true);
    expect(api.outboxEntryIsHeld(null, "designer-b")).toBe(true);
    expect(api.outboxEntryIsHeld(undefined, "designer-b")).toBe(true);
    expect(api.outboxEntryIsHeld("designer-a", null)).toBe(true);
  });

  test("the next account sees only its own entries, and a count for the rest", () => {
    const rows = [
      entry("Artisan · A", "designer-a"),
      entry("Artisan · A refused", "designer-a", "The server refused this."),
      entry("Artisan · B", "designer-b"),
      entry("Artisan · unknown", null)
    ];
    expect(api.outboxEntriesForThisAccount(rows, "designer-b").map((row) => row.label)).toEqual(["Artisan · B"]);
    expect(api.outboxEntriesForThisAccount(rows, null)).toEqual([]);
    expect(api.outboxEntriesForAnotherAccount(rows, "designer-b")).toBe(2);
    expect(api.outboxEntriesWithoutOwner(rows, "designer-b")).toBe(1);
  });

  test("the queue stamps the owner, the drain skips held entries, and the banner lists only this account's", () => {
    const source = read("lib", "offline.ts");
    const queue = source.slice(source.indexOf("export async function queueOffline("));
    expect(queue.slice(0, queue.indexOf("\n}"))).toContain("ownerUserId: outboxSessionUserId");
    const loop = source.slice(source.indexOf("async function runSync("));
    expect(loop).toContain("if (outboxEntryIsHeld(entry.ownerUserId, sessionUserId)) continue;");
    const banner = read("components", "OutboxBanner.tsx");
    expect(banner).toContain("setOutboxSessionUser(");
    expect(banner).toContain("const entries = outboxEntriesForThisAccount(stored, signedIn);");
    expect(banner).toContain("<OutboxAccountHold");
  });
});
