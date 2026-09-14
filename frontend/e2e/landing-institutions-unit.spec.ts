import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE INSTITUTIONAL MARKS AND OUTBOUND LINKS ON THE PUBLIC LANDING PAGE, AND THE FOUR SILENT WAYS
 * THEY BREAK.
 *
 * ── WHY THIS FILE EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * Everything the landing page said before this change was TEXT and lucide icons: `public/` held
 * three boundary data files, consumed by the signed-in map and referenced from nothing on this
 * route, and not one image. The institutional port put the first real picture assets on the page —
 * `iit-kharagpur.svg` and `dc-handicrafts.png`, each rendered on two separate surfaces (the hero
 * masthead corners and the colophon band above the footer) — plus three outbound destinations.
 * Every failure mode that arrives with them is SILENT:
 *
 *   1. A MISSING FILE BREAKS EACH MARK DIFFERENTLY, AND NEITHER WAY REACHES A LOG. Both marks are
 *      decorative by design — the accessible name lives on the anchor, which is the correct
 *      construction and is argued at length in `HeroLanding.tsx`. Renaming a file in
 *      `public/logos/`, or moving the page's `src` and not the file, is invisible to every other
 *      check in this repository, which is what the two filesystem assertions below exist for.
 *
 *      The loud one is the PNG: Chromium paints its broken-image glyph INSIDE the cream plate at
 *      the masthead's top-left corner, because an empty `alt` suppresses that glyph only for an
 *      image with no intrinsic box and this one carries `width`/`height` on purpose. (Firefox does
 *      collapse it, which is where the folklore that this is always silent comes from.)
 *
 *      The silent one is the seal: a `mask-image` whose source never arrives masks its box out
 *      completely, so the corner is simply empty. A pending or failed mask is NOT the same event as
 *      an ABSENT mask property — see failure 4, which is the case that paints a white rectangle.
 *
 *   2. AN ORPHAN FILE IN `public/logos/` IS A MARK WAITING TO BE MISREAD. This is not hypothetical:
 *      in the sibling repository a `centre-of-excellence.svg` sat there unreferenced, and its
 *      geometry was `app/icon.svg` VERBATIM — this product family's own eight-point terracotta star.
 *      Nothing rendered it, so nothing caught it; the next editor to see three files in a logos
 *      folder and two marks on the page would have "fixed" the omission by wiring it up, and the
 *      colophon whose entire job is provenance would have carried this application's own mark under
 *      another institution's name. A wrong mark is far more expensive than a missing one, because a
 *      missing logo is visibly missing and a plausible logo in the right slot is never questioned
 *      again. Two assertions below close that: one on unrendered files, one on the star path itself.
 *
 *      ⚠ THIS DIRECTORY DID NOT EXIST IN THIS REPOSITORY UNTIL THE PORT, so the folder has no habits
 *      yet and these two gates are the whole of them.
 *
 *   3. AN OUTBOUND LINK THAT LOSES `target`/`rel` LOSES IT QUIETLY. These four anchors are the only
 *      links on this page that leave the application at all — before the port the landing page had
 *      no external `href` of any kind — so there is no established habit here for an editor to copy,
 *      and a link that opens in place still WORKS. It just takes a visitor out of a page they were
 *      about to sign in to.
 *
 *   4. A `mask-*` PROPERTY THAT LOSES ITS `-webkit-` TWIN BREAKS ONE ENGINE ONLY. The unprefixed
 *      form is what a reviewer reads; the prefixed one is what several shipping browsers actually
 *      apply. Drop `WebkitMaskImage` and the seal's `<span>` stops being a mark at all: with no mask
 *      in force its `backgroundColor` paints the whole box and the corner becomes a solid white
 *      rectangle — on those browsers, and nowhere else. This is the ONE case that produces that
 *      rectangle, and failure 1 records why a missing FILE does not: an absent mask property paints
 *      everything, an unresolved mask source paints nothing.
 *
 * ── WHY THIS READS SOURCE RATHER THAN LOADING THE PAGE ──────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies — `record-pickers-unit.spec.ts`
 * says so and reads its subject the same way. A browser spec would also be the wrong instrument for
 * failure 1 in particular: Playwright does not fail a page because an `<img>` 404'd, and asserting
 * on a rendered mark's box would pass on the white rectangle. What has to be checked is the one pair
 * of facts a running page cannot state — that the path in the source and the file on disk are the
 * same path — and that is a filesystem question rather than a rendering one. This spec therefore
 * needs no server and no `page`.
 */

const ROOT = join(__dirname, "..");
const PUBLIC_DIR = join(ROOT, "public");
const LOGOS_DIR = join(PUBLIC_DIR, "logos");

const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/**
 * The same source with its comments taken out. Essential rather than tidy: the house style is long
 * prose naming the defect each rule closed, so `HeroLanding.tsx` contains the string `alt="logo"` in
 * a sentence about the trap it avoids — the exact shape one of the assertions below forbids. Every
 * count and every path scan here runs through this.
 *
 * The `[^:]` guard on the line-comment arm is what keeps `https://…` intact: the `//` in a URL is
 * preceded by a colon, so it is not treated as the start of a comment.
 */
const codeOnly = (relative: string) =>
  read(relative)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");

const LANDING = "components/hero/HeroLanding.tsx";
const LOGO_COMPONENT = "components/FieldRepoLogo.tsx";

/** Every file the landing page is assembled from: the server component and the whole hero island. */
const PAGE_SOURCES = [
  "app/page.tsx",
  ...readdirSync(join(ROOT, "components/hero"))
    .filter((name) => name.endsWith(".ts") || name.endsWith(".tsx"))
    .map((name) => "components/hero/" + name)
];

/**
 * A root-relative asset path in a string literal — the only shape a `public/` file can be referenced
 * by from this page. Deliberately wider than the two extensions in use today: the point is to catch
 * the NEXT asset somebody adds, and a check that only knows about `.svg` and `.png` stops being a
 * check the first time a font or a `.webp` arrives.
 */
const ASSET_REFERENCE =
  /["'`](\/[A-Za-z0-9_\-./]+\.(?:svg|png|jpe?g|webp|gif|avif|ico|woff2?|json|txt|csv))["'`]/g;

const referencedAssets = (): string[] => {
  const found = new Set<string>();
  for (const source of PAGE_SOURCES) {
    for (const match of codeOnly(source).matchAll(ASSET_REFERENCE)) found.add(match[1]);
  }
  return [...found].sort();
};

test("the comment stripper really removed the prose these assertions would otherwise match", () => {
  // Without this the file is vacuously green in both directions. `HeroLanding.tsx` discusses
  // `alt="logo"`, `<img>`, `target="_blank"` and every mask property in prose — so a negative
  // assertion would fail for an honest-looking wrong reason, and every count below would be counting
  // sentences. `alt="logo"` is the canary on purpose: it is the exact shape the decorative-image
  // test forbids, so these two assertions interlock rather than merely coexisting.
  expect(read(LANDING)).toContain('alt="logo"');
  expect(codeOnly(LANDING)).not.toContain('alt="logo"');
});

test("every asset path the landing page references exists on disk", () => {
  const assets = referencedAssets();

  // The scan finding nothing would make the loop below pass while proving nothing, and that is the
  // state this page was in for its whole life until the institutional port — no file-based asset
  // anywhere on this route.
  expect(assets.length).toBeGreaterThan(0);

  for (const asset of assets) {
    const onDisk = existsSync(join(PUBLIC_DIR, asset.replace(/^\//, "")));
    expect(onDisk, asset + " is referenced by the landing page but is not in public/").toBe(true);
  }
});

test("public/logos holds nothing the landing page does not render", () => {
  // THE ORPHAN GATE. A file here that no source references is either a mark that was meant to be
  // wired up and was forgotten, or one that was deliberately not wired up — and from the folder
  // alone those two are indistinguishable, which is exactly how a stray fourth copy of this
  // product's own mark came to sit in the sibling repository's logos folder referenced by nothing.
  const referenced = new Set(referencedAssets().filter((asset) => asset.startsWith("/logos/")));
  const onDisk = readdirSync(LOGOS_DIR).map((name) => "/logos/" + name);

  expect(onDisk.length).toBeGreaterThan(0);
  for (const file of onDisk) {
    expect(referenced.has(file), file + " is in public/logos but nothing on the landing page renders it").toBe(true);
  }
});

test("no file in public/logos duplicates the star path that FieldRepoLogo already declares", () => {
  // WHAT THIS REFUSES IS A DUPLICATED FILE, NOT THE MARK. `FieldRepoLogo.tsx:2-3` declares the path
  // data to be the Android drawable transcribed verbatim, and `app/icon.svg` carries the identical
  // string — so the mark already lives in three places that must be edited in lockstep. A copy under
  // `public/logos/` would be a fourth, and the way that fails is silent: somebody redraws the star in
  // three places, the fourth keeps serving the old one, and the landing page quietly stops matching
  // the app.
  //
  // The landing page DOES render this mark, beside the Centre of Excellence's name in the colophon —
  // it draws `<FieldRepoLogo>`, which is a call site of the one declaration and not a copy of it,
  // which is why that render does not trip this test and must not be "fixed" into a file.
  //
  // Derived from the component rather than typed out here, so a redraw of the mark keeps this honest
  // instead of pinning it to a path that no longer exists.
  const star = codeOnly(LOGO_COMPONENT).match(/d="([^"]+)"/);
  expect(star, "FieldRepoLogo no longer declares a single path — re-derive this assertion").not.toBeNull();
  const path = star ? star[1] : "";

  for (const name of readdirSync(LOGOS_DIR)) {
    // latin1 rather than utf8: one of these files is binary, and a lossy decode could in principle
    // manufacture or destroy a match. Nothing here needs the bytes to mean anything.
    const bytes = readFileSync(join(LOGOS_DIR, name)).toString("latin1");
    expect(bytes.includes(path), name + " contains FieldRepoLogo's own path data").toBe(false);
  }
});

test("the three institutional destinations are the ones on record, each written exactly once", () => {
  const code = codeOnly(LANDING);

  // The two institutions' sites, and the decision that the Centre of Excellence is a destination
  // rather than an import. Once each because each is declared in a constant and every surface renders
  // it from there — a second literal is the beginning of two hosts that disagree, which is the whole
  // reason IIT_KHARAGPUR and DC_HANDICRAFTS sit at module scope rather than inside the two JSX blocks
  // that use them.
  const destinations = ["https://www.iitkgp.ac.in/", "https://handicrafts.nic.in/", "https://cxa-cms.vercel.app/"];
  for (const href of destinations) {
    expect(code.split(href).length - 1, href + " should appear exactly once").toBe(1);
  }
});

test("every outbound anchor opens in a new tab, says so, and carries rel=noreferrer", () => {
  const code = codeOnly(LANDING);
  // JSX attribute values here never contain a `>`, so the open tag really does end at the first one.
  const anchors = code.match(/<a\s[\s\S]*?>/g) ?? [];

  // EIGHT ANCHORS: four in-page fragments in the footer nav, four that leave the application. The
  // sign-in and walkthrough links are `<Link>`, not `<a>`, and are not counted here.
  //
  // The four outbound are: the two masthead marks, the ONE anchor inside the band's `INSTITUTIONS`
  // map (it renders twice and is written once — this counts source, not renders), and the Centre of
  // Excellence link.
  expect(anchors.length).toBe(8);

  const outbound = anchors.filter((tag) => !/href="#/.test(tag));
  expect(outbound.length).toBe(4);

  for (const tag of outbound) {
    const href = tag.match(/href=(\{[^}]*\}|"[^"]*")/);
    const named = href ? href[1] : tag;
    expect(/target="_blank"/.test(tag), named + ' leaves the app without target="_blank"').toBe(true);
    // `noreferrer` implies `noopener` in every browser this app supports, and it is the house form
    // here — ten call sites across the tree against two of `rel="noreferrer noopener"`.
    expect(/rel="noreferrer"/.test(tag), named + ' leaves the app without rel="noreferrer"').toBe(true);
  }

  // One announcement per outbound anchor, no more and no fewer. A tab that opens unannounced is
  // WCAG 3.2.5; two announcements on one link is a screen reader saying it twice.
  expect(code.split("(opens in a new tab)").length - 1).toBe(outbound.length);
});

test("both institutional images stay decorative, because the name is on the anchor", () => {
  const code = codeOnly(LANDING);
  const images = code.match(/<img\b/g) ?? [];

  expect(images.length).toBe(2);
  // Every one of them, not merely one: an empty `alt` is correct here ONLY because each anchor
  // carries the institution's full name — visible text in the colophon, `sr-only` text in the
  // masthead. An `alt="Indian Institute of Technology Kharagpur"` added "for accessibility" would
  // make a screen reader announce the institution twice on the band, which is the failure that reads
  // as a fix.
  expect(code.split('alt=""').length - 1).toBe(images.length);
  // ⚠ FILE-WIDE, so a captioned image added anywhere else in this file fails here. That is
  // deliberate for now — there are no others — but if one legitimately arrives, narrow this to the
  // two institutional `<img>` tags rather than deleting it.
  expect(code).not.toMatch(/alt="[^"]+"/);
});

test("every masked property on the landing page ships its -webkit- twin", () => {
  const code = codeOnly(LANDING);
  // Two call sites: the seal painted through its own alpha, and the closing band's radial fade.
  const properties = [...code.matchAll(/\bmask([A-Z][A-Za-z]*)\s*:/g)].map((match) => match[1]);
  expect(properties.length).toBeGreaterThan(0);

  for (const property of new Set(properties)) {
    // The two spellings never overlap as substrings — `WebkitMaskImage` capitalises the M, so
    // `maskImage` is not inside it — which is what lets these be two independent counts rather than
    // one count minus the other.
    const plain = code.split("mask" + property + ":").length - 1;
    const webkit = code.split("WebkitMask" + property + ":").length - 1;
    expect(webkit, "mask" + property + " is declared " + plain + "x and WebkitMask" + property + " " + webkit + "x").toBe(
      plain
    );
  }
});

test("the masthead marks and the wrappers that hide them are gated at md together", () => {
  const code = codeOnly(LANDING);

  // THE "NOTHING MOVES ON A PHONE" GUARANTEE, WHICH IS TWO CLASSES AND NOT ONE. `hidden … md:flex`
  // on the anchors takes the marks out. `contents md:flex` on the two groups that hold them is what
  // stops the WRAPPERS changing the row: a `display: contents` box is not in the box tree at all, so
  // below md the header's flex items are once again exactly the wordmark cluster and the button.
  // Wrapping the marks in ordinary flex groups instead measured 5.1px wider on the wordmark and
  // 5.9px narrower on the button at 360px — with the marks already hidden. Two groups, two marks,
  // and the counts must stay level: gating one and not the other is the shape of the regression.
  expect(code.split("contents md:flex").length - 1).toBe(2);
  expect(code.split("hidden shrink-0 rounded-md transition").length - 1).toBe(2);
  // `sm:` here would put the marks back into the band of widths where `data-larger-text` overflows
  // the row — 640px and 641px and nowhere else, which is why it was measured rather than reasoned.
  expect(code).not.toContain("contents sm:flex");
});

/**
 * ── THE FIFTH SILENT FAILURE: A MARK THAT RENDERS PERFECTLY IN THE WRONG PLACE ──────────────────
 *
 * The four failures this file opens with are all about a mark that is MISSING, WRONG or UNANNOUNCED.
 * The one below is the opposite and is why it went unnoticed for two weeks: both marks loaded, both
 * linked correctly, both announced themselves, and they sat at the ends of a 1152px content column
 * instead of in the screen's margins. Nothing renders differently at 1280px in a headless browser
 * than it does at 1920px unless something measures it, so a viewport-dependent misplacement is
 * invisible to every check in this repository — including a screenshot taken at the default size.
 *
 * WHAT MAKES IT CHECKABLE WITHOUT A BROWSER is that the whole defect was two Tailwind classes on one
 * element, and the whole fix is their absence plus a matching cap one element down. Those are
 * source facts. The arithmetic is `HeroLanding.tsx`'s own: `mx-auto max-w-6xl` on a 1920px screen
 * leaves (1920 − 1152) / 2 = 384px of flat purple down each side, and a mark held inside the cap
 * lands at x = 408 — against the copy column, with the margin it was asked to occupy left empty.
 *
 * AND IT HAS COME BACK ONCE ALREADY, from the other side: the comment this file's subject used to
 * carry argued the cap was a deliberate port decision, complete with a reason. It was the defect.
 * A test is what tells the next reader which of those two a class is.
 */
test("the masthead is full bleed and the hero grid is capped to match it", () => {
  const code = codeOnly(LANDING);

  const header = code.match(/<header\s[\s\S]*?>/);
  expect(header, "the hero masthead is no longer a <header> — re-derive this assertion").not.toBeNull();
  const masthead = header ? header[0] : "";

  // THE TWO CLASSES THAT WERE THE DEFECT. `w-full` with no cap and no centring is what puts a mark
  // in the viewport's corner; either one of these coming back pulls both marks back inside the
  // content column, where the owner reported them on 2026-09-14.
  expect(masthead).toContain("w-full");
  expect(/\bmx-auto\b/.test(masthead), "the masthead is centred again — it must be full bleed").toBe(false);
  expect(/\bmax-w-/.test(masthead), "the masthead is capped again — it must be full bleed").toBe(false);

  // THE OTHER HALF, WHICH IS NOT OPTIONAL. An uncapped bar over a `max-w-6xl` grid is the same
  // misalignment seen from the other side, and an uncapped GRID is two islands of copy with a
  // thousand pixels of purple between them past 2000px. The header and the grid move together or
  // neither does — the sentence the old comment got right while defending the wrong conclusion.
  const capped = code.match(/className="([^"]*max-w-\[120rem\][^"]*)"/);
  expect(capped, "the hero grid lost its 120rem cap — an uncapped bar needs a capped grid").not.toBeNull();
  const grid = capped ? capped[1] : "";

  // ONE GUTTER LADDER, ON BOTH, OR THE MARKS STOP BEING THE BOUNDARY THE HERO LINES UP ON. The DC
  // mark's left edge and the headline's left edge are the same number only while these agree.
  for (const [name, classes] of [["masthead", masthead], ["hero grid", grid]] as const) {
    expect(classes).toContain("px-6");
    expect(classes, name + " lost its md:px-10 — the two elements must share one gutter").toContain("md:px-10");
    // `sm:px-10` — which is what the sibling repository uses — would widen this gutter across
    // 640…767px, a band where the marks are still `hidden`. Existing content moving to make room
    // for ornament that is not on screen is exactly what the marks' "NOTHING RENDERS AND NOTHING
    // MOVES" clause forbids, and `md` is the first width at which a mark actually renders.
    expect(classes, name + " steps its gutter up at sm, below the width any mark renders at").not.toContain(
      "sm:px-10"
    );
  }
});
