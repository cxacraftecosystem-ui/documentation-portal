import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { LANGUAGES } from "@/lib/languages";
import { questionnaireStrings } from "@/lib/strings/questionnaire";
import { qs, WEB_ONLY_STRINGS } from "@/lib/strings/qs";

/**
 * DESIGN test 59 (web half) and the BUILD_PLAN §0.1 parity rule:
 * - `shared/` is a verbatim copy of `feature/q-backend` (every file matches `shared/manifest.json`);
 * - `lib/strings/questionnaire.ts` is exactly what `scripts/generate_shared.py` emits from
 *   `shared/strings/questionnaire.json` (same keys, same words);
 * - every `qs()` key the v2 screens use exists, and web-only labels never shadow a shared key.
 */

const ROOT = join(__dirname, "..", "..");
const sha = (path: string) => createHash("sha256").update(readFileSync(path).toString("utf8").replace(/\r\n/g, "\n")).digest("hex");

function files(dir: string, ext: RegExp): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path, ext) : ext.test(path) ? [path] : [];
  });
}

test("shared/ matches its manifest: a verbatim copy, never edited here", () => {
  const manifest = JSON.parse(readFileSync(join(ROOT, "shared", "manifest.json"), "utf8")) as { sha256: Record<string, string> };
  for (const [name, digest] of Object.entries(manifest.sha256)) expect(sha(join(ROOT, "shared", name)), name).toBe(digest);
});

test("the generated strings module holds every shared key with the shared words", () => {
  const shared = (JSON.parse(readFileSync(join(ROOT, "shared", "strings", "questionnaire.json"), "utf8")) as { strings: Record<string, string> }).strings;
  expect(Object.keys(questionnaireStrings).sort()).toEqual(Object.keys(shared).sort());
  for (const [key, text] of Object.entries(shared)) {
    const names = [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
    const vars = Object.fromEntries(names.map((n) => [n, `<${n}>`]));
    expect(qs(key as keyof typeof questionnaireStrings, vars), key).toBe(text.replace(/\{(\w+)\}/g, (_w, n: string) => `<${n}>`));
  }
});

test("every qs() key the screens use exists; web-only labels never shadow a shared key", () => {
  const used = new Set<string>();
  for (const path of [...files(join(__dirname, "..", "components", "questionnaire"), /\.tsx?$/), ...files(join(__dirname, "..", "lib"), /questionnaire\w*\.ts$|coverageCache\.ts$/)]) {
    for (const m of readFileSync(path, "utf8").matchAll(/qs\("(\w+)"/g)) used.add(m[1]);
  }
  const known = new Set([...Object.keys(questionnaireStrings), ...Object.keys(WEB_ONLY_STRINGS)]);
  expect([...used].filter((k) => !known.has(k))).toEqual([]);
  expect(Object.keys(WEB_ONLY_STRINGS).filter((k) => k in questionnaireStrings)).toEqual([]);
  expect(used.size).toBeGreaterThan(60);
});

test("languages are the shared list, and bn is Bengali", () => {
  const shared = JSON.parse(readFileSync(join(ROOT, "shared", "languages.json"), "utf8")) as { languages: { code: string; label: string }[] };
  expect(LANGUAGES.map((l) => [l.code, l.label])).toEqual(shared.languages.map((l) => [l.code, l.label]));
  expect(LANGUAGES.find((l) => l.code === "bn")?.label).toBe("Bengali");
});
