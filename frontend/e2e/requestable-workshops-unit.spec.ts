import { expect, test } from "@playwright/test";

import {
  REQUESTABLE_CAP_HINT,
  readRequestableAnswer,
  requestableCeilingSentence
} from "@/lib/requestableWorkshops";

/**
 * `GET /workshops/requestable` as the server answers it today — `{items, truncated}` — and as it
 * answered before (a bare array). The panel handed the envelope to a sort that spreads its argument,
 * and Settings → "Request workshop access" threw "e is not iterable" in production. The server half
 * of this contract is `backend/tests/test_requestable_contract.py`; the designer portal's copy of
 * this spec also pins the ceiling sentence to its `selectFilter.ts` wording.
 */

type Row = { id: string; title: string; accessStatus: string | null; restricted: boolean };

const row = (id: string): Row => ({ id, title: `Workshop ${id}`, accessStatus: null, restricted: false });

/** Byte for byte what `list_requestable_workshops` returns, keys and all. */
const ENVELOPE = {
  items: [
    {
      id: "w1",
      title: "Ajrakh block printing",
      place: "Kutch",
      date: null,
      startDate: "2026-09-01T00:00:00Z",
      endDate: null,
      accessStatus: "PENDING",
      accessLevel: "EDIT",
      restricted: true
    }
  ],
  truncated: false
};

test("the real envelope decodes to iterable rows and its flag", () => {
  const answer = readRequestableAnswer<Row>(JSON.parse(JSON.stringify(ENVELOPE)));
  expect(answer.truncated).toBe(false);
  expect([...answer.items].map((r) => r.id)).toEqual(["w1"]);
  expect(answer.items[0]).toEqual(ENVELOPE.items[0]);
});

test("a capped envelope raises the flag, and only a real boolean does", () => {
  expect(readRequestableAnswer<Row>({ items: [row("a")], truncated: true }).truncated).toBe(true);
  expect(readRequestableAnswer<Row>({ items: [row("a")], truncated: "true" }).truncated).toBe(false);
  expect(readRequestableAnswer<Row>({ items: [row("a")] }).truncated).toBe(false);
});

test("a bare array, the route's old answer, still decodes and claims no cut", () => {
  const answer = readRequestableAnswer<Row>([row("a"), row("b")]);
  expect(answer.items.map((r) => r.id)).toEqual(["a", "b"]);
  expect(answer.truncated).toBe(false);
});

test("anything else is refused rather than drawn as an empty list", () => {
  for (const junk of [null, undefined, "x", 3, {}, { items: "nope" }, { rows: [] }]) {
    expect(() => readRequestableAnswer<Row>(junk)).toThrow();
  }
});

test("the ceiling is stated only when the server said it cut the list", () => {
  expect(requestableCeilingSentence(200, true)).toBe(
    `Showing the first 200. There are more than are shown. ${REQUESTABLE_CAP_HINT}`
  );
  expect(requestableCeilingSentence(200, false)).toBe("");
  expect(requestableCeilingSentence(0, true)).toBe("");
});
