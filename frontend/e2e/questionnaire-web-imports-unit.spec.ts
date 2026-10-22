import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE SHARED WEB QUESTIONNAIRE STAYS COPYABLE (UNIFY 2.4, Web). `lib/questionnaire/**` and
 * `components/questionnaire/**` are byte-identical in designer-portal and Field Repository
 * (`shared/questionnaire.sha256`, `scripts/sync_questionnaire.py`), so a file there may import only
 * the two trees, the portal's own `lib/questionnairePortal`, and what
 * `shared/questionnaire-web-imports.json` lists — modules both portals carry with the same exports.
 * Anything else would make the copy fail to build in the other portal.
 */

const ROOT = join(__dirname, "..");
const RULES = JSON.parse(readFileSync(join(ROOT, "..", "shared", "questionnaire-web-imports.json"), "utf8")) as {
  trees: string[];
  portal: string;
  packages: string[];
  modules: string[];
};

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.(ts|tsx)$/.test(name) ? [path] : [];
  });
}

const SHARED = [...files(join(ROOT, "lib", "questionnaire")), ...files(join(ROOT, "components", "questionnaire"))];

test("the shared trees exist and carry the client", () => {
  const names = SHARED.map((p) => relative(ROOT, p).split("\\").join("/"));
  for (const expected of ["lib/questionnaire/api.ts", "lib/questionnaire/queue.ts", "lib/questionnaire/bundle.ts", "components/questionnaire/SessionWorkspace.tsx"]) {
    expect(names).toContain(expected);
  }
});

test("a shared file imports only what both portals carry", () => {
  const offenders: string[] = [];
  for (const path of SHARED) {
    const source = readFileSync(path, "utf8");
    for (const match of source.matchAll(/from\s+["']([^"']+)["']|import\(\s*["']([^"']+)["']\s*\)/g)) {
      const target = match[1] ?? match[2];
      const ok =
        RULES.trees.some((tree) => target.startsWith(tree)) ||
        target === RULES.portal ||
        RULES.packages.includes(target) ||
        RULES.modules.includes(target);
      if (!ok) offenders.push(`${relative(ROOT, path)} imports ${target}`);
    }
  }
  expect(offenders, "add the module to shared/questionnaire-web-imports.json only if both portals carry it").toEqual([]);
});

test("every listed module exists in this portal", () => {
  for (const name of [RULES.portal, ...RULES.modules]) {
    const base = join(ROOT, name.replace(/^@\//, ""));
    const found = [".ts", ".tsx", "/index.ts", "/index.tsx"].some((ext) => {
      try {
        return statSync(base + ext).isFile();
      } catch {
        return false;
      }
    });
    expect(found, `${name} is listed but missing`).toBe(true);
  }
});
