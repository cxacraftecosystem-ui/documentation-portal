import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";
import { createElement, Fragment } from "react";

import { buttonCount } from "@/components/ui/ButtonRow";

/**
 * THE BUTTON-ROW RULE, owner ruling 2026-10-17: one button centred, two on one line, three or more
 * stacked on a phone and pills from `sm` up. It is decided once, in `components/ui/ButtonRow.tsx`,
 * and the shared hosts route through it so no screen hand-styles its own row. There is no React
 * renderer in devDependencies, so the count is tested as a function and the layout as source.
 */
const read = (...parts: string[]) => readFileSync(join(process.cwd(), ...parts), "utf8").replace(/\r\n/g, "\n");
const ROW = read("components", "ui", "ButtonRow.tsx");

test("the count unwraps fragments and ignores what renders nothing", () => {
  const button = (key: string) => createElement("button", { key }, key);
  expect(buttonCount([button("a")])).toBe(1);
  expect(buttonCount(createElement(Fragment, null, button("a"), button("b")))).toBe(2);
  expect(buttonCount([button("a"), null, false, undefined, button("b")])).toBe(2);
  expect(buttonCount([button("a"), button("b")], button("delete"))).toBe(3);
  expect(buttonCount(null)).toBe(0);
});

test("one is centred whatever the alignment, two share a line, three or more stack then become pills", () => {
  expect(ROW).toContain('data-button-row="1" className={`flex justify-center');
  expect(ROW).toContain("grid grid-cols-2 gap-2");
  expect(ROW).toContain("max-sm:[&>*]:whitespace-normal");
  expect(ROW).toContain("flex flex-col gap-2");
  expect(ROW).toContain("sm:[&>*]:rounded-full");
});

test("the shared hosts render through it rather than a flex div of their own", () => {
  expect(read("components", "PageHeader.tsx")).toContain('<ButtonRow align="end">{actions}</ButtonRow>');
  expect(read("components", "RowActions.tsx")).toContain('<ButtonRow align="end" size="sm" destructive={destructive}>');
  expect(read("components", "dialogs", "FieldDialog.tsx")).toContain(
    '<ButtonRow align="end" className="mt-5">{footer}</ButtonRow>'
  );
  expect(read("components", "dialogs", "FieldDialog.tsx")).not.toContain('"mt-5 flex flex-wrap justify-end gap-2"');
});
