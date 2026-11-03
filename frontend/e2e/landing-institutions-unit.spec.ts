import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const PUBLIC_DIR = join(ROOT, "public");
const LOGOS_DIR = join(PUBLIC_DIR, "logos");

const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const codeOnly = (relative: string) =>
  read(relative)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");

const LANDING = "components/hero/HeroLanding.tsx";
const LOGO_COMPONENT = "components/FieldRepoLogo.tsx";

const PAGE_SOURCES = [
  "app/page.tsx",
  ...readdirSync(join(ROOT, "components/hero"))
    .filter((name) => name.endsWith(".ts") || name.endsWith(".tsx"))
    .map((name) => "components/hero/" + name)
];

const ASSET_REFERENCE =
  /["'`](\/[A-Za-z0-9_\-./]+\.(?:svg|png|jpe?g|webp|gif|avif|ico|woff2?|json|txt|csv))["'`]/g;

const referencedAssets = (): string[] => {
  const found = new Set<string>();
  for (const source of PAGE_SOURCES) {
    for (const match of codeOnly(source).matchAll(ASSET_REFERENCE)) found.add(match[1]);
  }
  return [...found].sort();
};

test("every asset path the landing page references exists on disk", () => {
  const assets = referencedAssets();

  expect(assets.length).toBeGreaterThan(0);

  for (const asset of assets) {
    const onDisk = existsSync(join(PUBLIC_DIR, asset.replace(/^\//, "")));
    expect(onDisk, asset + " is referenced by the landing page but is not in public/").toBe(true);
  }
});

test("public/logos holds nothing the landing page does not render", () => {
  const referenced = new Set(referencedAssets().filter((asset) => asset.startsWith("/logos/")));
  const onDisk = readdirSync(LOGOS_DIR).map((name) => "/logos/" + name);

  expect(onDisk.length).toBeGreaterThan(0);
  for (const file of onDisk) {
    expect(referenced.has(file), file + " is in public/logos but nothing on the landing page renders it").toBe(true);
  }
});

test("no file in public/logos duplicates the star path that FieldRepoLogo already declares", () => {
  const star = codeOnly(LOGO_COMPONENT).match(/d="([^"]+)"/);
  expect(star, "FieldRepoLogo no longer declares a single path — re-derive this assertion").not.toBeNull();
  const path = star ? star[1] : "";

  for (const name of readdirSync(LOGOS_DIR)) {
    const bytes = readFileSync(join(LOGOS_DIR, name)).toString("latin1");
    expect(bytes.includes(path), name + " contains FieldRepoLogo's own path data").toBe(false);
  }
});

test("the three institutional destinations are the ones on record, each written exactly once", () => {
  const code = codeOnly(LANDING);

  const destinations = ["https://www.iitkgp.ac.in/", "https://handicrafts.nic.in/", "https://cxa-cms.vercel.app/"];
  for (const href of destinations) {
    expect(code.split(href).length - 1, href + " should appear exactly once").toBe(1);
  }
});

test("every outbound anchor opens in a new tab, says so, and carries rel=noreferrer", () => {
  const code = codeOnly(LANDING);
  const anchors = code.match(/<a\s[\s\S]*?>/g) ?? [];

  expect(anchors.length).toBe(8);

  const outbound = anchors.filter((tag) => !/href="#/.test(tag));
  expect(outbound.length).toBe(4);

  for (const tag of outbound) {
    const href = tag.match(/href=(\{[^}]*\}|"[^"]*")/);
    const named = href ? href[1] : tag;
    expect(/target="_blank"/.test(tag), named + ' leaves the app without target="_blank"').toBe(true);
    expect(/rel="noreferrer"/.test(tag), named + ' leaves the app without rel="noreferrer"').toBe(true);
  }

  expect(code.split("(opens in a new tab)").length - 1).toBe(outbound.length);
});

test("both institutional images stay decorative, because the name is on the anchor", () => {
  const code = codeOnly(LANDING);
  const images = code.match(/<img\b/g) ?? [];

  expect(images.length).toBe(2);
  expect(code.split('alt=""').length - 1).toBe(images.length);
  expect(code).not.toMatch(/alt="[^"]+"/);
});

test("every masked property on the landing page ships its -webkit- twin", () => {
  const code = codeOnly(LANDING);
  const properties = [...code.matchAll(/\bmask([A-Z][A-Za-z]*)\s*:/g)].map((match) => match[1]);
  expect(properties.length).toBeGreaterThan(0);

  for (const property of new Set(properties)) {
    const plain = code.split("mask" + property + ":").length - 1;
    const webkit = code.split("WebkitMask" + property + ":").length - 1;
    expect(webkit, "mask" + property + " is declared " + plain + "x and WebkitMask" + property + " " + webkit + "x").toBe(
      plain
    );
  }
});

test("the masthead marks and the wrappers that hide them are gated at md together", () => {
  const code = codeOnly(LANDING);

  expect(code.split("contents md:flex").length - 1).toBe(2);
  expect(code.split("hidden shrink-0 rounded-md transition").length - 1).toBe(2);
  expect(code).not.toContain("contents sm:flex");
});

test("the masthead is full bleed and the hero grid is capped to match it", () => {
  const code = codeOnly(LANDING);

  const header = code.match(/<header\s[\s\S]*?>/);
  expect(header, "the hero masthead is no longer a <header> — re-derive this assertion").not.toBeNull();
  const masthead = header ? header[0] : "";

  expect(masthead).toContain("w-full");
  expect(/\bmx-auto\b/.test(masthead), "the masthead is centred again — it must be full bleed").toBe(false);
  expect(/\bmax-w-/.test(masthead), "the masthead is capped again — it must be full bleed").toBe(false);

  const capped = code.match(/className="([^"]*max-w-\[120rem\][^"]*)"/);
  expect(capped, "the hero grid lost its 120rem cap — an uncapped bar needs a capped grid").not.toBeNull();
  const grid = capped ? capped[1] : "";

  for (const [name, classes] of [["masthead", masthead], ["hero grid", grid]] as const) {
    expect(classes).toContain("px-6");
    expect(classes, name + " lost its md:px-10 — the two elements must share one gutter").toContain("md:px-10");
    expect(classes, name + " steps its gutter up at sm, below the width any mark renders at").not.toContain(
      "sm:px-10"
    );
  }
});
