import { expect, test } from "@playwright/test";

import { listAllPages } from "@/lib/listAllPages";

type Row = { id: string };

function server(total: number) {
  const asked: number[] = [];
  const fetchPage = async (_path: string, params: Record<string, string | number | undefined | null>) => {
    const page = Number(params.page);
    const pageSize = Number(params.pageSize);
    asked.push(page);
    const items: Row[] = Array.from({ length: Math.max(0, Math.min(pageSize, total - (page - 1) * pageSize)) }, (_, index) => ({ id: `r${(page - 1) * pageSize + index + 1}` }));
    return { items, total, page, pageSize, pages: Math.ceil(total / pageSize) };
  };
  return { asked, fetchPage };
}

test("a picker gets every row, not the first hundred", async () => {
  const { asked, fetchPage } = server(231);
  const result = await listAllPages<Row>("/artisans", { pageSize: 100, createdBy: "me" }, fetchPage);
  expect(asked).toEqual([1, 2, 3]);
  expect(result.items).toHaveLength(231);
  expect(result.items.at(-1)?.id).toBe("r231");
  expect(result.total).toBe(result.items.length);
});

test("an empty list asks once", async () => {
  const { asked, fetchPage } = server(0);
  const result = await listAllPages<Row>("/artisans", {}, fetchPage);
  expect(asked).toEqual([1]);
  expect(result.items).toEqual([]);
});

test("a server that repeats a page cannot loop for ever", async () => {
  let calls = 0;
  const result = await listAllPages<Row>("/x", { pageSize: 2 }, async () => {
    calls += 1;
    return { items: [{ id: "a" }, { id: "b" }], total: 10, page: 1, pageSize: 2, pages: 5 };
  });
  expect(calls).toBe(2);
  expect(result.items.map((row) => row.id)).toEqual(["a", "b"]);
});
