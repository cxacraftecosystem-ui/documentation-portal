import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { expect, test } from "@playwright/test";

import { Pagination } from "@/components/Pagination";

const render = (page: number, pages: number, total: number) => renderToStaticMarkup(createElement(Pagination, { page, pages, total, onPage: () => undefined }));

test("an empty list shows no pager at all, never Page 0 of 0", () => {
  expect(render(0, 0, 0)).toBe("");
  expect(render(1, 1, 0)).toBe("");
});

test("one page shows the count without Previous and Next", () => {
  const html = render(1, 1, 3);
  expect(html).toContain("3 records");
  expect(html).not.toContain("Previous");
  expect(html).not.toContain("Page ");
  expect(render(1, 1, 1)).toContain("1 record<");
});

test("more pages show where you are and both buttons", () => {
  const html = render(2, 5, 96);
  expect(html).toContain("Page 2 of 5 · 96 records");
  expect(html).toContain("Previous");
  expect(html).toContain("Next");
});
