#!/usr/bin/env node
/**
 * The field-repo-frontend skill's "shared core" for the five form primitives (§11.11) must be
 * byte-identical in documentation-portal and designer-portal. This diffs the block between the
 * `shared:form-primitives` markers in this repo against the sibling repo's copy.
 *
 *   node scripts/check-skill-shared-block.mjs [path/to/designer-portal]
 *
 * The sibling defaults to $SIBLING_REPO, then ../designer-portal next to this checkout, then
 * C:/dev/IIT/designer-portal. Exit 0 = identical; 1 = they differ or a block is missing; 2 = no
 * sibling checkout found (nothing to compare — CI has only one repo).
 */
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SKILL = join(".claude", "skills", "field-repo-frontend", "SKILL.md");
const START = "<!-- shared:form-primitives:start";
const END = "<!-- shared:form-primitives:end -->";

const here = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function block(root) {
  const file = join(root, SKILL);
  if (!existsSync(file)) return { error: `no skill at ${file}` };
  const text = readFileSync(file, "utf8").replace(/\r\n/g, "\n");
  const start = text.indexOf(START);
  const end = text.indexOf(END);
  if (start < 0 || end < start) return { error: `no shared:form-primitives block in ${file}` };
  return { text: text.slice(start, end + END.length) };
}

const candidates = [process.argv[2], process.env.SIBLING_REPO, resolve(here, "..", "designer-portal"), "C:/dev/IIT/designer-portal"].filter(Boolean);
const sibling = candidates.find((root) => existsSync(join(root, SKILL)));
if (!sibling) {
  console.log("No designer-portal checkout found; nothing to compare.");
  process.exit(2);
}

const mine = block(here);
const theirs = block(sibling);
for (const [name, result] of [["this repo", mine], [sibling, theirs]]) {
  if (result.error) {
    console.error(`${name}: ${result.error}`);
    process.exit(1);
  }
}
if (mine.text === theirs.text) {
  console.log(`The shared core is identical in both skills (${sibling}).`);
  process.exit(0);
}
const a = mine.text.split("\n");
const b = theirs.text.split("\n");
const first = a.findIndex((line, index) => line !== b[index]);
console.error(`The shared core differs from ${sibling}, first at block line ${first + 1}:`);
console.error(`  here:    ${a[first] ?? "(end of block)"}`);
console.error(`  sibling: ${b[first] ?? "(end of block)"}`);
process.exit(1);
