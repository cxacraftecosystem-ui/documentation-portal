"use client";

import Link from "next/link";
import { useRef } from "react";
import { motion, useScroll, useTransform } from "framer-motion";
import {
  Brush,
  ChevronDown,
  ClipboardList,
  ExternalLink,
  GitBranch,
  Images,
  Languages,
  Mic,
  Package,
  ShieldCheck,
  User as UserIcon,
  UsersRound,
  Wifi,
  Wrench
} from "lucide-react";

import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import { useAuth } from "@/components/AuthProvider";
import AccessLadder from "@/components/hero/AccessLadder";
import HeroFAQ from "@/components/hero/HeroFAQ";
import HowItWorks from "@/components/hero/HowItWorks";
import type { CorpusCensus } from "@/components/hero/corpusCensus";
import PrintingBed from "@/components/hero/PrintingBed";
import TeamSection from "@/components/hero/TeamSection";
import WalkthroughCallout from "@/components/hero/WalkthroughCallout";
import { butiTileUrl } from "@/components/hero/buti";
import { heroEntrance, useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/**
 * The cloth, in three states, is the page's one structural idea.
 *
 *   1. THE HERO is the bare ground — the buti at 96px and 3.2% white, a weave you register without
 *      naming. It replaces the 24px dot grain that used to sit here (that recipe moved onto the
 *      printing bed, so nothing was lost).
 *   2. THE PRINTING BED is the printing itself — 72 individually misregistered impressions that
 *      build as you scroll, with one gold head. That is the signature and the only place with
 *      bespoke geometry or motion.
 *   3. THE CLOSING BAND is the finished length — the same tile, fully present, STATIC, and with no
 *      gold at all. The moment it moves or takes gold, the bed stops being the one signature
 *      moment and becomes a repeated effect.
 *
 * Both static states are the CSS tile, which carries four differently-rotated impressions per
 * 96px repeat so it does not read as machine-perfect wallpaper. Only the bed stamps individual
 * impressions; that is what earns it the attention.
 */
const BUTI_TILE = butiTileUrl();

const TRUST_ITEMS = [
  { icon: ShieldCheck, label: "Six-tier access control" },
  { icon: Wifi, label: "Works offline in the field" },
  { icon: Languages, label: "Transcribed & translated to English" }
];

/**
 * The eight record types, named EXACTLY as the Android dashboard and the web menu name them
 * (MainActivity EntryMode.label) — an academic who has seen the app should recognise every word.
 */
const RECORD_TYPES = [
  { icon: UserIcon, title: "Artisan", copy: "The maker: craft, lineage, place, identity, provenance." },
  { icon: Brush, title: "Craft", copy: "The tradition itself — technique, origin, regional identity." },
  { icon: Package, title: "Product", copy: "What is made: materials, dimensions, pricing, imagery." },
  { icon: GitBranch, title: "Process", copy: "How it is made, step by ordered step, with media per step." },
  { icon: Wrench, title: "Tool", copy: "The toolkit, and which artisans use each tool." },
  // ⚠ "THE QUESTIONNAIRE" WAS SINGULAR IN THE DATABASE UNTIL 2026-09-13 AND THIS LINE STILL READ AS
  // THOUGH IT WERE. `QuestionnaireSection` carried `code @unique` and a global `@@unique([sortOrder])`,
  // so the schema could hold exactly one instrument; a `Questionnaire` container now owns the
  // sections, questions and sittings, the uniques are composite, and `Workshop.questionnaireId` is
  // what binds one to a workshop (`PUT /workshops/{id}/questionnaire`, admin only). The clause below
  // is deliberately about what a RESEARCHER sees rather than about how it is filed, because for two
  // days those were different answers: the write bound the instrument and the three read paths still
  // resolved the default, so the capture page showed one instrument's questions and stored the
  // answers against the other — see `5ad8c6a`. `resolve_questionnaire_id` is now called with the
  // workshop on every read, and a request that names no workshop still lands on the default, which
  // is what keeps every handset built before that date working.
  {
    icon: ClipboardList,
    title: "Questionnaire",
    copy: "Structured interviews, recorded and auto-transcribed — each workshop can run its own."
  },
  { icon: Images, title: "Miscellaneous Media", copy: "Audio, video and photographs that belong to no one record." },
  { icon: UsersRound, title: "Workshop", copy: "Field expeditions: assignments, date windows, approvals." }
];

/**
 * What a finished transcript is attached to. Not decoration: "nothing arrives as a loose file" is
 * the repository's central claim, and every recording really does land linked to these three.
 */
const TRANSCRIPT_LINKS = [
  { icon: UserIcon, label: "Artisan" },
  { icon: Brush, label: "Craft" },
  { icon: UsersRound, label: "Workshop" }
];

/**
 * The three headline lines, masked and flown up one after another. Only the last is gold — the
 * gradient is a single accent, not a treatment applied to the whole headline.
 */
const HEADLINE = [
  { text: "The interview ends.", gold: false },
  { text: "The knowledge is", gold: false },
  { text: "already preserved.", gold: true }
];

/** The cross-cutting surfaces that sit on top of the records, in the app's own vocabulary. */
const SURFACES = [
  "View Data",
  "Review & approvals",
  "Sharing & access grants",
  "Assigned tasks",
  "CSV & full-dataset export",
  "Edit history & provenance"
];

/**
 * ── THE TWO INSTITUTIONAL MARKS, DECLARED ONCE AND RENDERED TWICE ──────────────────────────────
 *
 * Ported from the sibling repository (`designer-portal/frontend/components/hero/HeroLanding.tsx`),
 * where the owner asked for the same pair of marks and the same colophon here. Each mark is on two
 * surfaces: the hero's masthead corners, and the colophon band above the footer. The two surfaces
 * treat them very differently — one recolours the seal and plates the wordmark, the other plates
 * both and recolours neither — so what is shared is only the pair of facts that must never
 * disagree: where the file is, and where the link goes. A mark renamed in `public/logos/` or an
 * institution that moves host would otherwise be corrected on the surface somebody happened to be
 * looking at and left broken on the other.
 *
 * ⚠ AND NEITHER SURFACE REPORTS A MISSING FILE, WHICH IS THE WHOLE REASON THE PATH IS DECLARED
 * ONCE. The two marks fail DIFFERENTLY. With the PNG aborted at the network layer, Chromium paints
 * its broken-image glyph INSIDE the cream plate at the masthead's top-left corner: an empty `alt`
 * suppresses that glyph only for an image with no intrinsic box, and this one carries
 * `width`/`height` attributes on purpose (they are what reserves its space before the file
 * arrives), so the box exists and the glyph is drawn in it. The seal fails the opposite way and is
 * genuinely silent: a `mask-image` whose source never arrives masks the box out completely, so the
 * corner is simply empty, with `aria-hidden` leaving nothing for a screen reader either.
 *
 * So one fails loudly and one invisibly, and neither failure reaches a log or a test. That is not
 * an argument for an `onError` handler on a static file in `public/` that has never once failed to
 * serve; it is the argument for these two paths having exactly one definition, so that a rename
 * breaks both surfaces at once and is noticed. `e2e/landing-institutions-unit.spec.ts` is the other
 * half of that argument: it reads the filesystem, because a rendering check cannot see this.
 */
const IIT_KHARAGPUR = {
  /** The formal name, verbatim: it is both the visible label in the band and the link's accessible name. */
  name: "Indian Institute of Technology Kharagpur",
  href: "https://www.iitkgp.ac.in/",
  src: "/logos/iit-kharagpur.svg",
  // Intrinsic dimensions, so the browser reserves the right box before the file arrives. The
  // `w-auto` in the band's `markClass` is what makes the rendered width follow from the height.
  width: 268,
  height: 300
};

const DC_HANDICRAFTS = {
  // The office's full formal name, which is what the band prints as this link's visible label. The
  // Ministry line lives in the band's paragraph rather than being repeated here, where it would push
  // that caption to five wrapped lines on a phone.
  //
  // ⚠ THE SIBLING REPOSITORY SOURCES THIS NAME OUT OF ITS OWN BACKEND AND THIS ONE CANNOT, which is
  // the difference that re-grounded the band's prose below. There, `report_templates.py:371` sets
  // `organisation="Office of the Development Commissioner (Handicrafts)"` on a report template, so
  // the caption is a string the codebase already writes. This backend has no report templates at all
  // — `grep -rni "Development Commissioner" backend/` returns nothing — so the name here is the
  // institution's own and nothing in this repository corroborates it. Read the band's paragraph
  // before assuming anything else about that office is checkable from inside this tree.
  name: "Office of the Development Commissioner (Handicrafts)",
  href: "https://handicrafts.nic.in/",
  src: "/logos/dc-handicrafts.png",
  width: 600,
  height: 253
};

/**
 * ── THE MASTHEAD CORNER MARKS ──────────────────────────────────────────────────────────────────
 *
 * THE SEAL IS MASKED WHITE, NOT FILTERED AND NOT RE-DRAWN. `iit-kharagpur.svg` is the best possible
 * case for recolouring, and the file was read rather than assumed: `<svg>` → ONE
 * `<g fill="#291973" fill-rule="evenodd">` → exactly 110 `<path>`, and nothing else in it. No
 * `class`, no inline `style`, no `<style>` block, no `<defs>`, no `stroke`, no gradient, no embedded
 * raster; a sweep for fill attributes returns exactly one hit across the whole 99 KB.
 *
 * It still cannot be recoloured through the cascade, because it is loaded as an `<img>` and an
 * `<img>`-loaded SVG is an isolated document: the page's CSS never reaches inside it, and a
 * `currentColor` written into the file would resolve against that document's own root and come out
 * black. `buti.ts:96` records the same fact about the data URI it exports — "A data URI cannot see
 * `currentColor`" — and reaches the same conclusion this does.
 *
 * So the file is consumed as a **`mask-image`**, exactly as `PageSelvedge.tsx:47-48` consumes the
 * buti: only the ALPHA of the source is read, and the box behind it is painted with a colour of our
 * choosing. That is what makes the result GENUINELY white rather than white-ish — the source colour
 * is discarded rather than lightened, so the navy, black and white would all mask identically.
 * `fill-rule="evenodd"` is what keeps the ring lettering, the "1951" and the motto as real holes: an
 * evenodd hole is alpha 0, so the purple shows through it and the seal does not flatten into a white
 * blob. Both the prefixed and unprefixed properties ship, as `PageSelvedge` does, and the spec
 * counts them against each other — an unprefixed property with no twin paints the whole box white on
 * the engines that need the twin, which is the one failure here that is loud and wrong rather than
 * silent.
 *
 * The three refused alternatives, so nobody re-litigates them: `filter: brightness(0) invert(1)` on
 * the `<img>` (it works, has zero precedent in this tree, produces white from ANY input rather than
 * stating that the mark is white, and cannot be reused for the other mark); inlining the SVG and
 * swapping the one fill to `currentColor` (99 KB of path data into the prerendered payload of the
 * one route everybody lands on, in place of a cacheable static file); a second white copy of the
 * file (two marks to keep in step).
 *
 * ⚠ THE DC MARK CANNOT GET THE SAME TREATMENT, so "render the logos white" can only ever have meant
 * the seal. The PNG is 600 × 253, colour type 3, `PLTE` 256 entries, `tRNS` 182 — checked on disk,
 * not eyeballed. 9,510 of its pixels are OPAQUE PURE WHITE: the emblem's interior is PAINTED white
 * rather than punched out to transparency, so any alpha treatment collapses that white ground, the
 * blue rule around it, the yellow rays and the wordmark into one featureless silhouette.
 *
 * ⚠ AND IT IS NOT LEGIBLE ON THIS ROW UNAIDED, which is the whole reason for the plate. Against the
 * hero's real background — `bg-purple-950` is `oklch(0.255 0.108 305)` = `#2F0D4B` — the emblem is
 * fine: white at 16.41:1, yellow at 10.78:1, blue at 4.93:1. The WORDMARK beside it is not: 4,815 of
 * its pixels are the red `#C3161C`, which is 2.70:1 on that purple, and 44% of the wordmark's ink
 * falls under 3:1. A dark red line on a dark purple ground is invisible to an eye that checked only
 * the emblem — the easy mistake here, because the emblem is the half that looks like the logo.
 *
 * The plate is `bg-logo-cream` — a REAL TOKEN (`tailwind.config.ts:90`, "Brand-native logo colors
 * (Android launcher icon) — never re-themed"), the same `#FAF9F5` that `FieldRepoLogo` paints into
 * its own tile a few pixels away in this very row. So it is not an exception to §1.2's "never
 * hardcode a neutral"; it is the one ladder in the config whose whole purpose is to NOT invert, and
 * naming it is what makes that legible. `bg-[#FAF9F5]` would render identical pixels and read as
 * somebody eyedroppering a colour — exactly the thing a later reader would "fix" into `bg-card` and
 * break in dark mode.
 *
 * ⚠ "BOTH THEMES" IS ONE BACKGROUND HERE, WHICH IS WHY THERE IS NO `dark:` ANYWHERE BELOW. The
 * purple ramp is literal OKLCH and never inverts, so this row is `#2F0D4B` under
 * `data-theme="light"` and under `data-theme="dark"` alike. Every contrast figure above is therefore
 * the figure in BOTH themes rather than an average of two, and a `dark:`-conditional plate here
 * would be theming machinery that can never fire.
 *
 * ── WHY THEY DISAPPEAR BELOW `md` ──────────────────────────────────────────────────────────────
 *
 * There is no free space in this row on a phone: at 360px the wordmark and the "Sign in" button
 * already meet with no gap between them, so anything added does not sit beside the existing content,
 * it pushes it. The marks are therefore `hidden … md:flex`, and below `md` NOTHING RENDERS AND
 * NOTHING MOVES.
 *
 * ⚠ THE BREAKPOINT IS `md` AND NOT `sm`, AND IT WAS MOVED IN THE SIBLING REPOSITORY BECAUSE A
 * MEASUREMENT SAID SO. At `sm` the row fits comfortably in normal type and then `data-larger-text`
 * spends the slack: the root goes 16px to 18px, every length in this row is rem-based and grows with
 * it, and the wordmark wraps to a second line at 640 and 641px and nowhere else. `md` (768px) clears
 * it with about 106px to spare. Do not "restore" this to `sm` without re-running that measurement
 * under `data-larger-text="true"` — it is invisible to anyone testing at a round desktop size.
 *
 * ⚠ THE GROUPS ARE `contents md:flex`, AND THAT IS THE WHOLE "NOTHING MOVES" GUARANTEE. Wrapping
 * each mark and its neighbour in an ordinary flex group changes how the row distributes its
 * shortfall even with the mark hidden — one extra level of `min-width: auto` between the header and
 * the wordmark measured the wordmark 5.1px wider and the button 5.9px narrower at 360px. Below `md`
 * a `display: contents` box is not in the box tree at all, so the header's flex items are once again
 * exactly the wordmark cluster and the button: the same two boxes, the same `justify-between`, the
 * same shrink arithmetic.
 *
 * ⚠ THE MARKS ARE IN THE FLOW, NEVER OVERLAID. An absolutely-positioned pair at `top-6 left-6` /
 * `top-6 right-6` would land on top of the wordmark and the button at every width where the row is
 * already full. Sitting them in the flex row makes the browser reserve their space, so they cannot
 * collide with anything by construction, and each mark rides in a group with the neighbour it
 * belongs to, so `justify-between` still has exactly two things to hold apart. No `gap` on the row
 * and no `min-w-0` on either group: both are load-bearing absences — at 360px the wordmark's right
 * edge and the button's left edge are the same coordinate, so a gap of even 4px would re-wrap the
 * wordmark, and `min-w-0` would remove the min-content floor that is the only thing stopping this
 * row overflowing the viewport. The gaps live inside the two groups instead, where a hidden mark
 * makes them vanish along with it.
 *
 * ⚠ THIS HEADER IS FULL-BLEED, AND THE PARAGRAPH THAT STOOD HERE ARGUED THE OPPOSITE. It read:
 * "THIS HEADER IS CAPPED AT `max-w-6xl` AND THE SIBLING'S IS FULL-BLEED, so the marks sit at the
 * ends of a 1152px row rather than in the screen's literal corners on a wide monitor. That is the
 * one structural difference this port chose deliberately rather than by omission … If the literal
 * corners are ever wanted, the header and the grid below it move together or neither does." It is
 * quoted rather than deleted because its LAST sentence was right and is the whole of this fix,
 * while the cap it defended was the DEFECT and not a decision. On 2026-09-14 the owner reported it
 * as one: "the logos on the top are supposed to go into the margin on the left and the right where
 * there is no text or anything at all, currently it is encroaching into the main text area, it was
 * correctly implemented in the designer application."
 *
 * THE ARITHMETIC THE SIBLING ALREADY WROTE DOWN IS EXACTLY WHAT THAT COMPLAINT DESCRIBES
 * (`designer-portal/frontend/components/hero/HeroLanding.tsx:688`): `mx-auto max-w-6xl` on a 1920px
 * screen leaves (1920 − 1152) / 2 = 384px of flat purple down each side, and a mark held inside
 * that cap lands at x = 408, hard against the copy column below it, while the 384px of margin it
 * was asked to occupy stays empty. So both classes are gone from this row — and the grid below
 * moved WITH it, exactly as the quoted sentence required: `max-w-[120rem]` there is what stops an
 * uncapped bar over a capped grid pulling the copy into two islands on an ultra-wide screen. Those
 * two elements are the whole of the change. Nothing below the hero band is touched; every section
 * under it keeps its own `max-w-6xl`, and `HeroFAQ`'s narrower `max-w-3xl` stays narrower.
 *
 * MEASURED RATHER THAN REASONED, in Chromium against this build and the pre-change one. At 1280,
 * 1536, 1680 and 1920 the DC mark's left edge and the headline's left edge are both 40, and the
 * seal's right edge and the transcript card's right edge are the same number (1240 / 1496 / 1640 /
 * 1880) — so the corner marks ARE the masthead's outer boundary and the hero content lines up on
 * them. Before the change, at 1920, the seal sat at 1463.8…1512 with 408px of empty purple to its
 * right and the DC mark at 408…490.4 with 384px to its left: the complaint, in numbers. Past about
 * 2000px the cap binds and the grid centres inside 1920 while the bar stays full width (at 2200 the
 * marks are at 40 and 2160, the headline at 180) — the one width band where the two disagree, and
 * the trade the cap exists to make. `scrollWidth === clientWidth` at every width tested; nothing
 * here uses `100vw`, which is the classic way to gain a second scrollbar at right angles to the
 * first.
 *
 * ⚠ THE GUTTER STEPS UP AT `md` HERE AND AT `sm` IN THE SIBLING (`:745` and `:805` there), AND THAT
 * DIFFERENCE IS THIS FILE'S OWN "NOTHING MOVES" CLAUSE RATHER THAN AN OVERSIGHT. `sm:px-10` would
 * widen this row from 24px of padding to 40px across 640…767px — a band in which the marks are
 * still `hidden`, so every box in the row would move to make room for ornament that is not on
 * screen, which is exactly what the "NOTHING RENDERS AND NOTHING MOVES" paragraph above forbids.
 * And it would not stop at cosmetic: re-run under `data-larger-text="true"`, where every length in
 * this row is rem-based, and `px-10` is 45px a side rather than 27px — 90px of a 640px viewport
 * against 54px, i.e. 549px left for a row the measurement above records as needing about 550px.
 * That is the 640/641px wrap that paragraph was written about, reintroduced by the padding instead
 * of by the marks. `md` (768px) is the first width at which a mark actually renders, so from there
 * up this row is identical to the sibling's, and below it every measured box — header, wordmark,
 * button, headline, card — is byte-identical to the pre-change page at 320, 360, 390, 414, 639,
 * 640, 641, 700 and 767px, in normal type and in larger text. Do not "align" this to `sm` without
 * re-running both.
 *
 * ── SIZES, AND WHY THE HEIGHTS ARE NOT EQUAL ───────────────────────────────────────────────────
 *
 * The seal is 268 × 300 (portrait, 0.89:1, solid ink) and the wordmark is 600 × 253 (landscape,
 * 2.37:1, mostly the space between letters), so set to one height the landscape mark covers nearly
 * three times the area and visibly dominates the row. The band below equalises MASS instead of
 * height, at a ratio of 0.75. The masthead deliberately does NOT: on 2026-08-31 the owner asked for
 * the IIT seal 50% larger in the hero, and for the seal only, so 1.5 × `h-7` is 2.625rem and 1.5 ×
 * `h-9` is 3.375rem — neither a rung on Tailwind's scale, so both are written as literal arbitrary
 * values, the only form the class scanner can see and the same reason `aspect-[268/300]` is spelled
 * out. The pair now reads 0.48 at `md` and 0.52 at `lg`. That is the requested change and not a
 * regression to quietly repair; if the balance is ever asked for back, raising the DC mark to those
 * two heights × 0.75 is the arithmetic that does it without shrinking the seal again.
 *
 * ⚠ THE SEAL IS THE TALLEST THING IN THIS ROW, WHICH MAKES THE ROW TALLER. The previous ceiling was
 * `FieldRepoLogo` at `h-10` (40px); the seal clears it at both rungs, so the header grows by about
 * 2px at `md` and 14px at `lg` and the hero content below starts that much lower. The row is a flex
 * line with `items-center` and no fixed height, so nothing clips and nothing overlaps; the masthead
 * simply gets taller, which is what asking for a bigger mark inside it means.
 *
 * Neither mark can shift the layout as it loads: the seal's box is `h-[2.625rem] aspect-[268/300]`,
 * pure CSS resolved before any fetch, and the `<img>` reserves its box from its intrinsic
 * `width`/`height`. (`aspect-[268/300]` is the true `267.538 × 299.737` rounded off. The 0.08% error
 * is safe because `mask-size: contain` letterboxes the mark inside its box, so the error becomes a
 * 0.02px sliver of dead space and can never distort the seal. It is spelled out as a literal because
 * Tailwind scans for whole class names and cannot interpolate one from the constant.)
 *
 * ── THE ACCESSIBLE NAMES ───────────────────────────────────────────────────────────────────────
 *
 * Both marks are LINKS, and both carry their destination in the accessible name, because unlike the
 * band there is no visible institution name inside the anchor to serve as one — an unlabelled logo
 * link is precisely the `alt="logo"` trap the band's header warns about. The name is `sr-only` text
 * inside the anchor rather than alt text on the image, which is the same construction and the same
 * reason as below: one name per link, announced once. So the image keeps an empty `alt`, and the
 * seal's masked box is `aria-hidden`, because a `<span>` painted through a mask has nothing to
 * announce.
 */
const SEAL_MASK = `url("${IIT_KHARAGPUR.src}")`;

/**
 * The seal, painted through its own alpha. Declared once at module scope rather than rebuilt per
 * render: it is a constant, and an object literal in JSX is a new object on every frame the hero's
 * scroll transforms cause.
 */
const SEAL_MASK_STYLE: React.CSSProperties = {
  maskImage: SEAL_MASK,
  WebkitMaskImage: SEAL_MASK,
  maskSize: "contain",
  WebkitMaskSize: "contain",
  maskRepeat: "no-repeat",
  WebkitMaskRepeat: "no-repeat",
  maskPosition: "center",
  WebkitMaskPosition: "center",
  backgroundColor: "#ffffff"
};

/**
 * ── THE INSTITUTIONAL BAND ─────────────────────────────────────────────────────────────────────
 *
 * Two marks and one outbound link, sitting between the closing call to action and the footer.
 *
 * WHY IT EXISTS ALONGSIDE THE MASTHEAD MARKS. The corner marks up there are ORNAMENT with a
 * destination: no visible name, no institution's role stated, nothing a reader can quote. This band
 * is the provenance — it names both institutions in full, says what the affiliation IS, and carries
 * the Centre of Excellence link. It is also the only place on this page where LEAVING is the
 * expected gesture, and it is where a reader looks for exactly that. Deleting it as "the same thing
 * twice" would delete the half that carries the meaning and keep the half that carries the picture.
 * It shares the footer's `bg-card`, so the footer's own `border-t` is the hairline between them and
 * no second rule is drawn — do NOT add a `border-t` here.
 *
 * ⚠ NO MOTION ON THIS BAND, AND THAT IS THE POINT RATHER THAN AN OMISSION. Every other section below
 * the fold on this page is `whileInView`, which means framer-motion writes `opacity: 0` into the
 * server-rendered HTML. An attribution and affiliation band is the single worst member of that class
 * to leave invisible when JavaScript does not run, so this one renders statically and has nothing to
 * lose. Do not "bring it in line" with its neighbours by adding a variant — this page's own motion
 * vocabulary (`useHeroMotion.ts`) makes `whileInView` the reflex for a below-fold band, and this is
 * the band it is wrong for. The hover lifts below are CSS and are not an exception to this.
 *
 * ⚠ NO GOLD. Gold's budget on this page is already spent on the hero headline and the printing bed.
 * A third gold surface stops the bed being the signature.
 *
 * ── THE LIGHT PLATE, WHICH IS A DARK-MODE FIX AND NOT A DECORATION ─────────────────────────────
 *
 * `iit-kharagpur.svg` is 110 paths sharing ONE fill, `#291973`, on transparency. Measured against
 * this app's real tokens that is 14.18:1 on the light `--card` and **1.24:1** on the dark one — in
 * dark mode the ring lettering, the "1951" and the motto are not merely dim, they are gone. So it
 * needs a light ground under it, and this repository already has exactly one answer for a mark that
 * must keep its own colours on a surface that would swallow it: `FieldRepoLogo.tsx:5` — "on dark
 * surfaces keep the cream tile". That cream is `#FAF9F5`, and the plate below reaches it through
 * `bg-logo-cream`, the token whose whole purpose is to not invert.
 *
 * ⚠ THE MASTHEAD DOES THE OPPOSITE AND IS NOT INCONSISTENT WITH THIS. Up there the seal is painted
 * white through a mask and needs no plate, while the DC mark keeps one. THIS band is the one whose
 * ground inverts — `bg-card` is a themed token — so the plate here answers a question the masthead
 * does not have. The two surfaces must not be "made to agree".
 *
 * BOTH MARKS GET THE PLATE, THOUGH ONLY ONE NEEDS IT. `dc-handicrafts.png` is full colour — a blue,
 * a yellow, a red and a white ground inside the emblem — and it survives both themes on its own. A
 * plate behind one mark and a bare mark beside it reads as a mistake rather than as a treatment, and
 * the alternative (a `dark:`-conditional plate on one of the two) is new theming machinery on a
 * prerendered page for a problem one shared class solves. It also gives the DC mark's white emblem a
 * warm ground to sit on rather than the page's own white.
 *
 * ⚠ THAT PNG IS INDEXED COLOUR, not RGBA — 256 palette entries with a 182-entry `tRNS` alpha table
 * (checked on disk, not assumed). It renders correctly and its quantisation is invisible at this
 * size, but two things follow. Do not apply a CSS filter to it expecting straight-alpha RGBA
 * behaviour, and do not read a brand hex out of it: each of its three colours is spread over several
 * near-identical palette entries, so the file is not the authority on what the mark's blue IS. If an
 * exact colour is ever needed, take it from the institution rather than from this file.
 *
 * ── OPTICAL SIZING: THE HEIGHTS ARE NOT EQUAL, AND THAT IS THE CORRECTION ──────────────────────
 *
 * The seal at `h-16` is 64 × 57 ≈ 3,650px². The DC mark at `h-12` is 48 × 114 of BOX, but its ink
 * occupies only rows 24…235 of its 253, so the ink is 40 × 109 ≈ 4,380px². The wordmark ends ~20%
 * larger by area, which is right rather than sloppy: a wordmark is mostly the space between letters
 * where a seal is solid ink. The two plates are one fixed size and the marks are centred in them, so
 * the row has a real shared baseline instead of two marks agreeing by coincidence at one width.
 *
 * ── `<img>` AND NOT `next/image` ───────────────────────────────────────────────────────────────
 *
 * `next.config.ts` states it in its own header: "Nothing renders through `next/image` today (media
 * is shown with plain `<img>`/`<audio>` tags…)". Its `remotePatterns` allowlist is about REMOTE
 * hosts and has nothing to say about a file in `public/`, so introducing the optimiser for two
 * static marks on the one prerendered route would be new machinery for no gain.
 * `media/MediaLightbox.tsx:146-147` and `:282-283` are this tree's existing `<img>` call sites and
 * both carry the same eslint suppression; these follow them.
 *
 * ── ACCESSIBILITY: WHY AN EMPTY `alt` IS CORRECT HERE AND IS NOT THE `alt="logo"` TRAP ─────────
 *
 * A logo link whose ONLY content is an image must carry alt text naming where the link goes. These
 * links carry the institution's full name as REAL TEXT inside the same anchor, which is strictly
 * better — it is visible, selectable, translatable, and it survives an image that fails to load.
 * Giving the image the name as well would make a screen reader announce the institution twice per
 * link. The visible text is therefore the accessible name, and `(opens in a new tab)` is appended
 * `sr-only` AFTER it so the visible label is still a prefix of the accessible one (WCAG 2.5.3, Label
 * in Name). The masthead marks reach the same outcome from the opposite direction: no visible text,
 * so the WHOLE name is `sr-only` and the image still takes an empty alt. Either way it is one name
 * per link.
 *
 * ⚠ THAT SUFFIX IS THIS TREE'S FIRST CALL SITE, not a house form copied from a neighbour — a sweep
 * for the phrase finds only a comment in `app/(protected)/search/page.tsx:59` describing the
 * behaviour. It is the wording the sibling repository uses (`StageReferenceField.tsx:444` there), so
 * the two products announce a new tab identically; the spec below pins one announcement per outbound
 * anchor so a second surface cannot drift from it.
 *
 * `target="_blank" rel="noreferrer"` is the house form for an outbound anchor here — ten call sites
 * against two of `rel="noreferrer noopener"` — and `noreferrer` implies `noopener` in every browser
 * this app supports, so the pair is not needed.
 */
const INSTITUTIONS = [
  { ...IIT_KHARAGPUR, markClass: "h-12 w-auto sm:h-16" },
  { ...DC_HANDICRAFTS, markClass: "h-9 w-auto sm:h-12" }
];

/**
 * The Centre of Excellence is a REDIRECT, never an import and never an iframe.
 *
 * The Centre's site is a finished Next.js application with its own CMS — pages and typed sections in
 * Postgres, an editorial studio, revision history. This application's public surface is one
 * prerendered route with no content model at all, so "bring the content over" is really "rebuild a
 * CMS", and a half-built copy of an institutional site is worse than a link to the real one.
 *
 * ⚠ THE HOST IS DELIBERATELY NOT PRINTED IN THE LINK TEXT, AND THE SENTENCE THAT SAID IT SHOULD BE
 * IS QUOTED HERE RATHER THAN DROPPED, because its reasoning was sound and only its premise expired.
 * It read: "The host is printed in the link text on purpose. A reader about to leave an application
 * they are being asked to sign in to should be able to see where they are going before they press
 * it." That is a good rule and this repository follows it elsewhere.
 *
 * It was overturned by direction on 2026-08-30, on a ground the rule does not cover: *"link needs to
 * be there on click, not in text on the website, as we are soon going to change the link to a better
 * one."* A host printed in prose is a SECOND copy of the destination, and the moment the Centre moves
 * to its permanent address that copy becomes a sentence on the landing page confidently naming
 * somewhere the link no longer goes. The transparency rule protects a reader from being sent
 * somewhere unexpected; a stale host printed beside a working link does the opposite of that.
 *
 * SO THE CONSTANT BELOW IS THE ONLY PLACE THE ADDRESS APPEARS, and swapping it is the whole of the
 * change when the new one arrives — no copy to find, no accessible name to re-word.
 * `e2e/landing-institutions-unit.spec.ts` asserts each institutional destination is written exactly
 * once, so a second literal fails a test rather than quietly ageing.
 *
 * WHAT REPLACED THE HOST IS THE CENTRE'S FULL NAME, which is better transparency than a hostname
 * was: "cxa-cms.vercel.app" is a deployment slug that tells a reader nothing about who they are being
 * sent to, while the full legal name does. The anchor still announces the new tab, which is the part
 * of the rule that actually protects the reader.
 *
 * ⚠ THAT RULE IS ABOUT THIS LINK ONLY. The two masthead marks DO print their hosts in their `sr-only`
 * labels, because up there the host is the only thing naming the destination to a screen reader and
 * neither is a URL literal, so neither trips the once-only assertion.
 */
const CENTRE_OF_EXCELLENCE_HREF = "https://cxa-cms.vercel.app/";

/**
 * The Centre's full name, as the owner gave it. Declared rather than inlined so the colophon and any
 * later surface cannot disagree about it, and so the ONE thing a reader is asked to recognise is not
 * buried in JSX.
 *
 * ⚠ IT IS DELIBERATELY NOT TITLE-CASED. "unified AI-enabled craft ecosystem platform" is written here
 * exactly as it was supplied; an editor tidying it into "Unified AI-Enabled Craft Ecosystem Platform"
 * would be restyling an institution's own name, which is not a typographic decision this page gets to
 * make.
 */
const CENTRE_OF_EXCELLENCE_NAME =
  "Centre of Excellence for unified AI-enabled craft ecosystem platform";

/**
 * The public hero — the product's signature dark-purple mesh treatment applied to
 * Field Repository: gold-gradient headline line, GSAP line-mask entrance,
 * ambient orbs, and a live-transcript preview card in place of the note card.
 *
 * Gold is permitted here (and on auth) and nowhere else. Everything below the dark hero band is
 * built from the themed tokens — `bg-card`, `ink-*`, `line-200` — so the page reads correctly in
 * both light and dark; a hardcoded white card would turn into white-on-white in dark mode.
 *
 * Motion is framer-motion throughout — the same library the rest of the page already uses — and
 * every duration passes through heroEntrance(), which honours the OR of the OS preference and the
 * in-app Settings toggle. The entrance is declarative on purpose: an imperative timeline that has
 * to re-select the DOM after React has rendered it can leave an element stranded at its start
 * state (this hero's call-to-action row did exactly that), whereas these props ARE the state.
 */
export default function HeroLanding({ census }: { census?: CorpusCensus }) {
  const rootRef = useRef<HTMLElement>(null);
  const { user, loading } = useAuth();
  const reduce = useHeroReducedMotion();
  const enterHref = !loading && user ? "/dashboard" : "/login";

  const { scrollYProgress } = useScroll({ target: rootRef, offset: ["start start", "end start"] });
  const yContent = useTransform(scrollYProgress, [0, 1], ["0%", reduce ? "0%" : "12%"]);
  const yOrbs = useTransform(scrollYProgress, [0, 1], ["0%", reduce ? "0%" : "22%"]);
  const fade = useTransform(scrollYProgress, [0, 0.7], [1, reduce ? 1 : 0.15]);

  /** The ambient orb drift. `initial` is always the rest state, so the server HTML matches. */
  const drift = (to: { x: string; y: string; scale: number }, seconds: number) => ({
    initial: { x: "0%", y: "0%", scale: 1 },
    animate: reduce ? { x: "0%", y: "0%", scale: 1 } : to,
    transition: reduce
      ? { duration: 0 }
      : { duration: seconds, repeat: Infinity, repeatType: "reverse" as const, ease: "easeInOut" as const }
  });

  return (
    <div className="bg-bg-0">
      {/* ── Hero ─────────────────────────────────────────────────────────── */}
      <section
        ref={rootRef}
        className="relative isolate flex min-h-[100svh] flex-col overflow-hidden bg-purple-950"
        aria-label="Field Repository — living craft documentation"
      >
        {/* Mesh background: two purple orbs + one faint gold, plus fine grain. */}
        <motion.div aria-hidden style={{ y: yOrbs }} className="pointer-events-none absolute inset-0">
          <motion.div
            {...drift({ x: "4%", y: "-4%", scale: 1.05 }, 14)}
            className="absolute -left-40 -top-48 h-[42rem] w-[42rem] rounded-full opacity-80 [will-change:transform]"
            style={{ background: "radial-gradient(circle, oklch(0.47 0.198 305 / 0.5), transparent 62%)" }}
          />
          <motion.div
            {...drift({ x: "-4%", y: "4%", scale: 1.03 }, 17)}
            className="absolute -right-48 top-1/4 h-[40rem] w-[40rem] rounded-full opacity-70 [will-change:transform]"
            style={{ background: "radial-gradient(circle, oklch(0.4 0.18 305 / 0.55), transparent 64%)" }}
          />
          <motion.div
            {...drift({ x: "-3%", y: "-3%", scale: 1.06 }, 21)}
            className="absolute bottom-[-12rem] left-1/3 h-[36rem] w-[36rem] rounded-full opacity-40"
            style={{ background: "radial-gradient(circle, oklch(0.7 0.145 80 / 0.28), transparent 60%)" }}
          />
          {/* State 1 of 3: bare ground. One property change on the grain layer that was already
              here — no new element, no new motion. If it ever reads as visible wallpaper behind
              the headline it is too strong; the ceiling is about 5%. */}
          <div
            className="absolute inset-0 opacity-[0.032]"
            style={{ backgroundImage: BUTI_TILE, backgroundSize: "96px 96px" }}
          />
        </motion.div>

        {/* Top bar: the two institutional marks in the SCREEN'S corners, the wordmark, and sign in.
            FULL-BLEED — no `mx-auto`, no `max-w-*` — because a masthead is a bar, and a mark is only
            in the corner if nothing centres it first. The marks' own header above carries every
            measurement behind this row: why they are in the flow rather than overlaid, why the two
            groups are `contents md:flex`, why there is no `gap` and no `min-w-0`, why the gutter
            steps up at `md` rather than `sm`, and what the cap that used to be here did wrong. */}
        <header className="relative z-10 flex w-full items-center justify-between px-6 pt-6 md:px-10">
          <div className="contents md:flex md:items-center md:gap-4 lg:gap-6">
            {/* Top-LEFT corner: the DC Handicrafts mark, in its own colours, on the cream plate its
                red wordmark needs to survive this purple. See DC_HANDICRAFTS above for the pixel
                counts behind both halves of that sentence. */}
            <a
              href={DC_HANDICRAFTS.href}
              target="_blank"
              rel="noreferrer"
              // Hover is CSS, never a framer prop, so the two reduced-motion blocks in globals.css
              // reach it — the same rule every other interactive element on this page follows.
              className="hidden shrink-0 rounded-md transition hover:-translate-y-0.5 active:translate-y-0 md:flex"
            >
              <span className="flex items-center justify-center rounded-md bg-logo-cream px-2 py-1.5 shadow-md">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={DC_HANDICRAFTS.src}
                  alt=""
                  width={DC_HANDICRAFTS.width}
                  height={DC_HANDICRAFTS.height}
                  className="h-5 w-auto lg:h-7"
                />
              </span>
              <span className="sr-only">
                {DC_HANDICRAFTS.name} — handicrafts.nic.in (opens in a new tab)
              </span>
            </a>
            <div className="flex items-center gap-2.5">
              <FieldRepoLogo className="h-10 w-10 rounded-xl shadow-md" />
              <span className="font-display text-lg font-bold tracking-tight text-white">Field Repository</span>
            </div>
          </div>
          <div className="contents md:flex md:items-center md:gap-4 lg:gap-6">
            <Link
              href={enterHref}
              className="inline-flex h-10 items-center rounded-md border border-white/25 px-5 font-display text-sm font-bold text-white/90 transition hover:border-white/45 hover:bg-white/5 hover:text-white"
            >
              {user ? "Open the app" : "Sign in"}
            </Link>
            {/* Top-RIGHT corner: the IIT Kharagpur seal, painted white through its own alpha. The
                `<span>` is the mark — there is no `<img>` here, because the file is a mask rather
                than a picture and the page's CSS cannot reach inside an `<img>`-loaded SVG. */}
            <a
              href={IIT_KHARAGPUR.href}
              target="_blank"
              rel="noreferrer"
              className="hidden shrink-0 rounded-md transition hover:-translate-y-0.5 active:translate-y-0 md:flex"
            >
              <span aria-hidden className="block aspect-[268/300] h-[2.625rem] lg:h-[3.375rem]" style={SEAL_MASK_STYLE} />
              <span className="sr-only">
                {IIT_KHARAGPUR.name} — iitkgp.ac.in (opens in a new tab)
              </span>
            </a>
          </div>
        </header>

        <motion.div
          style={{ y: yContent, opacity: fade }}
          // CAPPED AT `max-w-[120rem]` (1920px) RATHER THAN UNCAPPED, and it moves with the bar above
          // by requirement rather than by taste — see the masthead header's "THIS HEADER IS
          // FULL-BLEED" paragraph, which quotes the sentence that made the pairing a rule. An
          // uncapped bar over a `max-w-6xl` grid is the misalignment the owner reported from the
          // other side; an uncapped GRID is two islands of copy with a thousand pixels of purple
          // between them past 2000px. 1920 binds on neither of the widths that matter, so up to
          // about 2000px this grid and the header are the same box: the DC mark's left edge and the
          // headline's left edge are one number, the seal's right edge and the transcript card's
          // right edge are another, and the corner marks are the boundary the hero lines up on.
          // `md:px-10` matches the row above at every width where a mark renders, and leaves the
          // sub-`md` layout untouched — the same clause, for the same reason.
          className="mx-auto flex w-full max-w-[120rem] flex-1 flex-col justify-center px-6 pb-24 pt-16 md:px-10"
        >
          <div className="grid items-center gap-14 lg:grid-cols-[1.05fr_0.95fr] lg:gap-10">
            {/* Copy */}
            <div className="max-w-2xl">
              <motion.p {...heroEntrance(reduce, 0.05, 0.5, { y: 18 })} className="eyebrow mb-5 !text-gold-300">
                Living craft documentation
              </motion.p>
              <h1 className="font-display text-4xl font-extrabold leading-[1.05] tracking-tight text-white sm:text-5xl lg:text-6xl">
                {HEADLINE.map((line, index) => (
                  // The mask: each line flies up out of its own overflow-hidden slot.
                  <span key={line.text} className="block overflow-hidden pb-[0.08em]">
                    <motion.span
                      {...heroEntrance(reduce, 0.15 + index * 0.09, 0.9, { yPercent: 115 })}
                      className={line.gold ? "block text-gold-gradient" : "block"}
                    >
                      {line.text}
                    </motion.span>
                  </span>
                ))}
              </h1>
              <motion.p
                {...heroEntrance(reduce, 0.55, 0.6, { y: 20 })}
                className="mt-6 max-w-xl text-lg leading-relaxed text-white/75"
              >
                A field documentation repository for artisan crafts. Record artisans, products,
                processes, tools and workshops, run structured interviews that transcribe themselves,
                send the work up a review ladder, and export a research-ready dataset — captured
                offline, in the field, where the craft actually happens.
              </motion.p>

              <div className="mt-9 flex flex-wrap items-center gap-4">
                <motion.div {...heroEntrance(reduce, 0.7, 0.5, { y: 16 })}>
                  <Link
                    href={enterHref}
                    className="inline-flex h-12 items-center rounded-md bg-purple-700 px-8 font-display text-lg font-bold tracking-tight text-white shadow-cta transition hover:-translate-y-0.5 hover:bg-purple-600 active:translate-y-0 active:scale-[0.98]"
                  >
                    {user ? "Open the app" : "Enter the repository"}
                  </Link>
                </motion.div>
                <motion.div {...heroEntrance(reduce, 0.78, 0.5, { y: 16 })}>
                  <Link
                    href="/guide"
                    className="inline-flex h-12 items-center rounded-md border border-white/25 px-7 font-display text-lg font-bold tracking-tight text-white/90 transition hover:-translate-y-0.5 hover:border-white/45 hover:bg-white/5 hover:text-white active:translate-y-0"
                  >
                    See the walkthrough
                  </Link>
                </motion.div>
              </div>

              <ul className="mt-10 flex flex-wrap gap-x-7 gap-y-3">
                {TRUST_ITEMS.map(({ icon: Icon, label }, index) => (
                  <motion.li
                    key={label}
                    {...heroEntrance(reduce, 0.88 + index * 0.07, 0.45, { y: 12 })}
                    className="flex items-center gap-2 text-sm text-white/60"
                  >
                    <Icon className="h-4 w-4 text-gold-400" aria-hidden />
                    {label}
                  </motion.li>
                ))}
              </ul>
            </div>

            {/*
              THE TRANSCRIPT CARD — anatomy real, wording labelled.

              This card used to print an invented interview turn ("Two days in running water. My
              grandfather taught me...") attributed to an Interviewee and captioned as genuinely
              recorded, transcribed and linked to an artisan, craft and workshop. On a repository
              whose entire product is citable provenance, fabricating a primary source on the
              marketing page is the most expensive thing it could possibly do.

              What replaced it invents nothing. Every structural element here is what the pipeline
              actually produces: the speaker labels are literally the ones the refinement pass emits
              (`**Interviewer:**`, `**Interviewee:**`, and `**Interviewee 1/2:**` when it can tell
              several apart — services/ai.py), the horizontal rule is the Markdown `---` it inserts
              between distinct topics, and the linked records are real. The wording of the turns
              describes itself and is badged "Illustrative", so no sentence on this page can be
              mistaken for something an artisan said. No record id is invented either.

              The language line is worth being precise about: the system deliberately does NOT tag a
              source language. Scribe auto-detects and Deepgram runs `language=multi`, because these
              interviews code-switch mid-sentence and several are in regional languages with no code
              to name. Printing a tidy "hi-IN → en" chip here would have been a fabricated technical
              claim in place of a fabricated quote.
            */}
            <div className="relative">
              <motion.div
                {...heroEntrance(reduce, 0.5, 0.9, { y: 36, rotate: 1.2 })}
                className="glass-dark rounded-xl p-5 shadow-lg"
              >
                <div className="mb-4 flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2 text-sm font-semibold text-white/85">
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gold-500/15 text-gold-300">
                      <Mic className="h-4 w-4" aria-hidden />
                    </span>
                    Questionnaire — transcript
                  </div>
                  <span className="shrink-0 rounded-full border border-white/25 px-2.5 py-1 text-xs font-semibold text-white/70">
                    Illustrative
                  </span>
                </div>
                <div className="space-y-3 rounded-md bg-white/[0.06] p-4 text-sm leading-relaxed text-white/80">
                  <p>
                    <strong className="text-gold-200">Interviewer:</strong> Each question from the
                    questionnaire, in the order it was asked.
                  </p>
                  <p>
                    <strong className="text-white">Interviewee:</strong>{" "}
                    The artisan&rsquo;s answer, transcribed and then translated into English.
                  </p>
                  {/* The Markdown `---` the refinement pass writes between distinct topics. */}
                  <div className="h-px bg-white/10" />
                  <p>
                    <strong className="text-white">Interviewee 2:</strong> Where several artisans sit
                    in on one interview, each one gets their own label.
                  </p>
                </div>
                <ul className="mt-4 flex flex-wrap gap-2">
                  {TRANSCRIPT_LINKS.map(({ icon: Icon, label }) => (
                    <li
                      key={label}
                      className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-2.5 py-1 text-xs font-medium text-white/75"
                    >
                      <Icon className="h-3.5 w-3.5 text-white/50" aria-hidden />
                      {label}
                    </li>
                  ))}
                </ul>
                <p className="mt-4 text-xs leading-relaxed text-white/50">
                  The anatomy of a finished transcript — the wording is illustrative, not an
                  interview from the repository. The spoken language is detected rather than assumed:
                  these recordings code-switch between Hindi and English, and some are in Marwari or
                  Garhwali.
                </p>
              </motion.div>
            </div>
          </div>
        </motion.div>

        <motion.div
          {...heroEntrance(reduce, 1.4, 0.6)}
          aria-hidden
          // Hidden below sm: at 390 the hero column runs the full height of the screen and this
          // chevron sat on top of the transcript card's caption. A phone does not need to be told
          // the page scrolls, so the fix is to remove it rather than to pad around it.
          className="pointer-events-none absolute bottom-6 left-1/2 hidden -translate-x-1/2 text-white/40 sm:block"
        >
          {/* animate-bounce is CSS, so globals.css already stops it under reduced motion. */}
          <ChevronDown className="h-6 w-6 animate-bounce" />
        </motion.div>
      </section>

      {/* ── What the repository holds ────────────────────────────────────── */}
      <section id="records" className="mx-auto max-w-6xl px-6 py-24">
        <p className="eyebrow mb-3">One connected repository</p>
        <h2 className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl">
          Eight record types, linked to each other from the moment they are captured.
        </h2>
        <p className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          An artisan carries into their products; a product carries into the process that makes it
          and the tools it takes; every interview, photograph and recording lands attached to the
          artisan, the craft and the workshop it came from. Nothing arrives as a loose file.
        </p>
        <div className="mt-12 grid grid-cols-2 gap-4 md:grid-cols-4">
          {RECORD_TYPES.map((record) => (
            <div
              key={record.title}
              className="rounded-lg border border-line-200 bg-card p-5 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-10 w-10 items-center justify-center rounded-md bg-purple-700 text-white">
                <record.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-sm font-bold text-ink-900">{record.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{record.copy}</p>
            </div>
          ))}
        </div>
        <div className="mt-8 flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-ink-700">Built on top:</span>
          {SURFACES.map((surface) => (
            <span
              key={surface}
              className="rounded-full border border-line-200 bg-surface-50 px-3 py-1.5 text-xs font-medium text-ink-700"
            >
              {surface}
            </span>
          ))}
        </div>
      </section>

      {/* ── How it works ─────────────────────────────────────────────────── */}
      <HowItWorks />

      {/* ── Walkthrough ──────────────────────────────────────────────────── */}
      <WalkthroughCallout />

      {/* ── The pilot collection, on cloth ───────────────────────────────── */}
      <PrintingBed census={census} />

      {/* ── The six-tier access ladder ───────────────────────────────────── */}
      <AccessLadder />

      {/* ── Built for the whole team ─────────────────────────────────────── */}
      <TeamSection />

      {/* ── FAQ ──────────────────────────────────────────────────────────── */}
      <HeroFAQ />

      {/* ── Final CTA ────────────────────────────────────────────────────── */}
      <section className="relative isolate overflow-hidden grad-brand px-6 py-20 text-center">
        {/* State 3 of 3: the finished length. Fully printed, completely static, and no gold —
            this band never animates, so the bed stays the page's single signature moment. The
            radial mask fades the cloth away from the centre, which both keeps every pixel of
            contrast behind the heading and the buttons untouched and lets the print run out
            toward the selvedges. */}
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 opacity-[0.05]"
          style={{
            backgroundImage: BUTI_TILE,
            backgroundSize: "104px 104px",
            maskImage: "radial-gradient(72% 66% at 50% 50%, transparent 30%, black 88%)",
            WebkitMaskImage: "radial-gradient(72% 66% at 50% 50%, transparent 30%, black 88%)"
          }}
        />
        <h2 className="relative font-display text-3xl font-bold tracking-tight text-white sm:text-4xl">
          Ready to document living craft?
        </h2>
        {/* The last thing a visitor reads before the sign-in button, so it is where the allow-list
            has to be said. It previously described a door that no longer opens: an address nobody
            has admitted now gets a pending request rather than an account, and sending somebody to
            a sign-in screen without telling them makes the refusal there read as a fault. */}
        <p className="relative mx-auto mt-3 max-w-xl text-white/75">
          Sign in with your researcher account, or with Google. Access is by invitation: an
          administrator admits your address first, and a new sign-in from an address that is not yet
          on the list becomes a request for approval rather than an account.
        </p>
        <div className="relative mt-8 flex flex-wrap items-center justify-center gap-4">
          <Link
            href={enterHref}
            className="inline-flex h-12 items-center rounded-md bg-white px-8 font-display text-lg font-bold tracking-tight text-purple-800 shadow-lg transition hover:-translate-y-0.5 active:translate-y-0"
          >
            {user ? "Open the app" : "Enter the repository"}
          </Link>
          <Link
            href="/guide"
            className="inline-flex h-12 items-center rounded-md border border-white/30 px-7 font-display text-lg font-bold tracking-tight text-white transition hover:-translate-y-0.5 hover:bg-white/10 active:translate-y-0"
          >
            Take the walkthrough
          </Link>
        </div>
      </section>

      {/* ── The institutions, and the Centre's own site ──────────────────── */}
      {/* Static by design — see the INSTITUTIONS header for why this band must not be `whileInView`. */}
      <section aria-label="Institutional affiliation" className="bg-card px-6 py-14">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-9 text-center">
          <div className="max-w-2xl">
            <p className="eyebrow mb-3">The institutions behind it</p>
            {/*
              NO `<h2>` HERE, DELIBERATELY. Every other band on this page opens with one, and a
              further heading landing between "Ready to document living craft?" and the footer would
              put a fresh section in the document outline at the exact moment the page has finished
              making its argument. This is a colophon, so it reads as a rule rather than as a band,
              and the `aria-label` on the section is what names it to a screen reader.

              ⚠ THE SECOND SENTENCE IS NOT THE SIBLING REPOSITORY'S AND THE SUBSTITUTION IS THE POINT.
              There the paragraph reads: "The document this app writes is addressed to a real office:
              its default template is a submission to the Office of the Development Commissioner
              (Handicrafts), and the cover carries the Government of India and Ministry of Textiles
              line above it." That sentence is read out of that repository's own backend —
              `report_templates.py:362-371` declares a DCH_STANDARD template with
              `organisation="Office of the Development Commissioner (Handicrafts)"`, and
              `report_builder.py:2664` puts the ministry line on the cover. THIS backend has no
              report templates, no narrative report and no submission at all: it exports a
              research-ready dataset (`services/csv_export.py`, `services/xlsx_report.py`,
              `services/questionnaire_xlsx.py`). Carrying the sentence across would have put a claim
              on a public page that nothing in this repository can support, so it was re-grounded
              rather than copied — which is exactly the failure mode `docs/` calls "copy written from
              another surface's copy inherits its errors".

              WHAT REPLACED IT IS READ OUT OF THIS TREE. `services/place_atlas.py` states that across
              the live corpus every position fix lands inside a box roughly 800 m across at the
              workshop venue in Kharagpur, and `tests/test_place_atlas.py:83-87` carries that venue's
              own postal address — "Centre of Excellence, Handicrafts, Agri Business Incubation
              Foundation (ABIF) Building, Indian Institute of Technology, Kharagpur, West Bengal,
              721302" — while the `place` column records where the craft is FROM: Bagru, Bareilly,
              Kachchh, Almora, Jammu.

              THE THREE SENTENCES HAVE THREE DIFFERENT KINDS OF EVIDENCE, and a later editor should
              know which is which. The affiliation is the owner's, who supplied both marks and all
              three destinations. The venue is read out of the code above. The third names what the
              Office of the Development Commissioner (Handicrafts) IS — a public fact about that
              office's remit, stated as such — and deliberately stops there. Do not strengthen it
              into a claim about funding, sanction or submission: nothing here checks that, and the
              sentence this one replaced is the proof of how easily such a claim travels.
            */}
            <p className="text-base leading-relaxed text-ink-700">
              A Centre of Excellence project at IIT Kharagpur. The fieldwork this repository holds
              was recorded at the Centre of Excellence, Handicrafts on the Kharagpur campus, while
              the crafts themselves come from the artisans&rsquo; own places, a long way from it. The
              Office of the Development Commissioner (Handicrafts), under the Ministry of Textiles,
              is the national office for the craft sector this work documents.
            </p>
          </div>

          <ul className="flex flex-wrap items-start justify-center gap-x-6 gap-y-8 sm:gap-x-12">
            {INSTITUTIONS.map((institution) => (
              <li key={institution.href} className="w-36 sm:w-52">
                <a
                  href={institution.href}
                  target="_blank"
                  rel="noreferrer"
                  // Hover is CSS, never a framer prop, so the two reduced-motion blocks in
                  // globals.css reach it — the same rule every card on this page follows.
                  className="group flex flex-col items-center gap-3 rounded-md transition hover:-translate-y-0.5 active:translate-y-0"
                >
                  <span className="flex h-20 w-32 items-center justify-center rounded-lg border border-line-200 bg-logo-cream shadow-sm transition group-hover:shadow-md sm:h-24 sm:w-40">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={institution.src}
                      alt=""
                      width={institution.width}
                      height={institution.height}
                      className={institution.markClass}
                    />
                  </span>
                  <span className="text-xs font-medium leading-snug text-ink-700 transition group-hover:text-purple-700">
                    {institution.name}
                    <span className="sr-only"> (opens in a new tab)</span>
                  </span>
                </a>
              </li>
            ))}
          </ul>

          {/*
            ── THE CENTRE OF EXCELLENCE, BY ITS FULL NAME AND WITHOUT ITS ADDRESS ─────────────────

            THE DESTINATION IS ON THE ANCHOR AND NOWHERE ELSE — not in the visible copy, and not in
            the accessible name either. Both halves of that are the requirement: a URL read out to a
            screen-reader user is as stale as one printed on screen, and the Centre's address is
            about to change. `CENTRE_OF_EXCELLENCE_HREF` is the single swap point; see its header for
            the sentence this overturned and why.

            THE FULL NAME IS THE LINK TEXT, which is what keeps this compliant with WCAG 2.4.4 rather
            than merely shorter: the anchor reads as a destination on its own in a links list, where
            a deployment slug identified nobody.

            THE MARK IS `<FieldRepoLogo>`, AND IT IS THE CENTRE'S OWN — WHICH IS ALSO THIS
            APPLICATION'S. That is not a substitution and it is not a placeholder: this product is a
            Centre of Excellence project and wears the Centre's mark, which is why the same
            eight-point star is in the masthead above, in the footer below and on the Android
            launcher.

            IT IS THE COMPONENT AND NEVER A FILE. `FieldRepoLogo.tsx:2-3` declares the path data to be
            the Android drawable transcribed verbatim, and `app/icon.svg` carries the identical
            `d="M54 14l7 27…"` string — so the mark already lives in three places that must be edited
            in lockstep, and a copy under `public/logos/` would be a fourth that quietly kept serving
            the old star after a redraw. Rendering the component adds a call site to ONE declaration.
            `e2e/landing-institutions-unit.spec.ts` refuses both shapes of that mistake: no orphan in
            `public/logos/`, and no file there carrying this path.

            NO `alt`, NO `aria-label`, DELIBERATELY. `FieldRepoLogo` marks its own `<svg>`
            `aria-hidden`, so the mark contributes nothing to the accessible name and the anchor
            announces exactly the Centre's full name plus the new-tab notice. Naming the institution
            on the image as well would make a screen reader say it twice — the same failure the two
            institutional images above avoid with an empty alt, for the same reason.
          */}
          <p className="max-w-2xl text-sm leading-relaxed text-ink-500">
            The Centre keeps its own site — its account of itself, its research, and the crafts it
            holds. It is a separate application rather than a section of this one, so this link
            leaves the repository:{" "}
            <a
              href={CENTRE_OF_EXCELLENCE_HREF}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-2 font-medium text-purple-700 underline-offset-2 hover:underline"
            >
              {/* `shrink-0` because the name wraps to two or three lines on a phone and a flex item
                  with an intrinsic aspect ratio is otherwise squeezed into an oval. */}
              <FieldRepoLogo className="h-5 w-5 shrink-0 rounded-md" />
              {CENTRE_OF_EXCELLENCE_NAME}
              {/* Text, then a trailing external-link glyph — `settings/MyAiKeysPanel.tsx:129` and
                  `:137` are the shape this repository already uses for an anchor that leaves the
                  app. */}
              <ExternalLink className="h-3.5 w-3.5 shrink-0" aria-hidden />
              <span className="sr-only">(opens in a new tab)</span>
            </a>
          </p>
        </div>
      </section>

      <footer className="border-t border-line-200 bg-card px-6 py-10">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-4 text-center">
          <div className="flex items-center gap-2">
            <FieldRepoLogo className="h-6 w-6 rounded-md" />
            <span className="font-display font-bold text-ink-900">Field Repository</span>
          </div>
          <nav aria-label="Footer" className="flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-sm">
            <Link href={enterHref} className="text-ink-700 transition hover:text-purple-700">
              Sign in
            </Link>
            <a href="#records" className="text-ink-700 transition hover:text-purple-700">
              What it captures
            </a>
            <a href="#how-it-works" className="text-ink-700 transition hover:text-purple-700">
              How it works
            </a>
            <a href="#access" className="text-ink-700 transition hover:text-purple-700">
              Access ladder
            </a>
            <Link href="/guide" className="text-ink-700 transition hover:text-purple-700">
              Walkthrough
            </Link>
            <a href="#faq" className="text-ink-700 transition hover:text-purple-700">
              FAQ
            </a>
          </nav>
          <p className="text-xs text-ink-500">Field documentation for artisans, crafts and living knowledge.</p>
        </div>
      </footer>
    </div>
  );
}
