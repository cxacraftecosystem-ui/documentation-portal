import { test } from "node:test";
import assert from "node:assert/strict";

import { tableCell } from "./markdown.mjs";

test("a pipe in a cell stays inside the cell", () => {
  assert.equal(tableCell("a | b"), "a \\| b");
});

test("a backslash is escaped before the pipe, so it cannot cancel the pipe escape", () => {
  assert.equal(tableCell("a\\|b"), "a\\\\\\|b");
  assert.equal(tableCell("ends with \\"), "ends with \\\\");
});

test("backticks cannot open a code span", () => {
  assert.equal(tableCell("`x`"), "\\`x\\`");
});

test("whitespace collapses and the limit applies before escaping", () => {
  assert.equal(tableCell("a\n\tb"), "a b");
  assert.equal(tableCell("abc\\", 4), "abc\\\\");
  assert.equal(tableCell("abcd|", 4), "abcd");
});

test("missing values become empty cells", () => {
  assert.equal(tableCell(undefined), "");
  assert.equal(tableCell(null), "");
});
