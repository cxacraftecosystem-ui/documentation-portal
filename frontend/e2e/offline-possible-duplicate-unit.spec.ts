import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  differentPersonEntry,
  possibleDuplicateSentence,
  samePersonEntry,
  type OutboxEntry
} from "@/lib/offline";

/**
 * A queued artisan the server held as a likely duplicate when the outbox drained.
 *
 * It is NOT a clash and NOT a rejection, so it gets its own state (`possibleDuplicate`) and its own
 * two answers: "Same person" skips the create and lets the media go to the existing artisan;
 * "Different person" re-sends the create with the candidates acknowledged. Discard stays.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function held(body: Record<string, unknown> = { name: "Giriraj", duplicateAcknowledged: [] }): OutboxEntry {
  return {
    id: 7,
    label: "Artisan · Giriraj",
    createdAt: 1,
    endpoint: "/artisans",
    method: "POST",
    body: JSON.stringify(body),
    media: [{ files: [], linkedRecordType: "artisan" }],
    attempts: 1,
    failure: "Not saved yet. This looks like Giriraj Prasaad (Bhuj).",
    possibleDuplicate: {
      message: "This may be Giriraj Prasaad (Bhuj), already recorded.",
      candidates: [
        { id: "a1", name: "Giriraj Prasaad", place: "Bhuj" },
        { id: "a2", name: "G. Prasad", place: null }
      ]
    }
  };
}

test("the tray sentence names the first candidate and its place", () => {
  expect(possibleDuplicateSentence(held())).toBe("Not saved yet. This looks like Giriraj Prasaad (Bhuj).");
  expect(possibleDuplicateSentence({ possibleDuplicate: { message: "", candidates: [{ id: "x", name: "Meena" }] } })).toBe(
    "Not saved yet. This looks like Meena."
  );
  expect(possibleDuplicateSentence({ possibleDuplicate: null })).toBeNull();
});

test("Same person: no create, the media goes to the existing artisan", () => {
  const next = samePersonEntry(held(), "a1");
  expect(next.created).toBe(true);
  expect(next.createdId).toBe("a1");
  expect(next.attachedToExisting).toBe(true);
  expect(next.failure).toBeNull();
  expect(next.possibleDuplicate).toBeNull();
  // The queued fields are not copied anywhere: the body is left exactly as it was.
  expect(next.body).toBe(held().body);
});

test("Different person: the create is re-sent with every candidate acknowledged", () => {
  const next = differentPersonEntry(held({ name: "Giriraj", duplicateAcknowledged: ["z9"] }));
  expect(next.created).toBeUndefined();
  expect(next.failure).toBeNull();
  expect(next.possibleDuplicate).toBeNull();
  expect(JSON.parse(next.body)).toEqual({ name: "Giriraj", duplicateAcknowledged: ["z9", "a1", "a2"] });
});

test("an old queued body without the field gains it on Different person", () => {
  const next = differentPersonEntry(held({ name: "Giriraj" }));
  expect(JSON.parse(next.body).duplicateAcknowledged).toEqual(["a1", "a2"]);
});

test("the drain reads the hold before the generic clash, and keeps the entry", () => {
  const offline = read("lib", "offline.ts");
  const arm = offline.slice(offline.indexOf("if (error instanceof ApiError && error.status === 409)"));
  const holdAt = arm.indexOf("holdFromPayload(error.payload)");
  const clashAt = arm.indexOf("`Possible duplicate. ${error.message}");
  expect(holdAt).toBeGreaterThan(0);
  expect(clashAt).toBeGreaterThan(holdAt);
  expect(arm.slice(holdAt, clashAt)).toContain("{ possibleDuplicate }");
  expect(arm.slice(holdAt, clashAt)).not.toContain("discardOutboxEntry");
});

test("the tray offers Same person and Different person on one row, and keeps Discard", () => {
  const banner = read("components", "OutboxBanner.tsx");
  expect(banner).toContain("May already be recorded");
  expect(banner).toContain("Same person");
  expect(banner).toContain("Different person");
  expect(banner).toContain("<ButtonRow size=\"sm\" align=\"end\"");
  expect(banner).toContain("Discard");
});
