import { expect, test } from "@playwright/test";

import {
  REQUESTABLE_CAP_HINT,
  readEveryRequestable,
  readRequestableAnswer,
  requestableCeilingSentence
} from "@/lib/requestableWorkshops";

type Row = { id: string; title: string; accessStatus: string | null; restricted: boolean };

const row = (id: string): Row => ({ id, title: `Workshop ${id}`, accessStatus: null, restricted: false });

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

test("every page is walked, so the oldest workshop can be asked about", async () => {
  const all = Array.from({ length: 31 }, (_, index) => row(`w${index + 1}`));
  const asked: number[] = [];
  const answer = await readEveryRequestable<Row>(async (offset) => {
    asked.push(offset);
    const items = all.slice(offset, offset + 10);
    const more = offset + items.length < all.length;
    return { items, truncated: more, nextOffset: more ? offset + items.length : null };
  });
  expect(asked).toEqual([0, 10, 20, 30]);
  expect(answer.items.map((r) => r.id).at(-1)).toBe("w31");
  expect(answer.items).toHaveLength(31);
  expect(answer.truncated).toBe(false);
});

test("an older server without nextOffset still answers, and says it cut the list", async () => {
  const answer = await readEveryRequestable<Row>(async () => ({ items: [row("a")], truncated: true }));
  expect(answer.items.map((r) => r.id)).toEqual(["a"]);
  expect(answer.truncated).toBe(true);
});
