---
name: field-repo-frontend
description: The Field Repository web design system — colour and type tokens, Android-parity naming, and layout rules. Load before ANY frontend UI work in this repo.
---

# Field Repository frontend design system

The visual language of this product — fonts, colours, theme, surfaces — is defined by the tokens
below and by `frontend/tailwind.config.ts`, which is the single source of truth. Information
architecture (feature names, menu labels, card layouts) mirrors the **Android app**
(D:\Portal_Development_Web\android). When in doubt: visual language = the tokens in this file,
wording and structure = Android.

## Fonts

Local variable fonts in `frontend/fonts/` loaded via `next/font/local` in `app/layout.tsx`:
- **Inter** (`--font-inter`) — body/UI. `font-sans`.
- **Plus Jakarta Sans** (`--font-jakarta`) — display/headings. `font-display`.
Never load Google-hosted fonts; never use serif.

## Color (Tailwind v3 tokens in tailwind.config.ts)

- **Brand purple ramp** `purple-50…950` — OKLCH, hue locked at 305°. `purple-700`
  (`oklch(0.47 0.198 305)`) is the ONLY action color (`--primary`); 800 ≈ #58068c.
- **Tinted neutrals**: `ink-900 #1e1b2e` (headings), `ink-700` (body), `ink-500` (muted),
  `ink-300` (placeholders); `line-200 #e4e2ef` (borders); `surface-50 #faf9fd` (tinted panels);
  `bg-0 #f7f6fb` (page canvas). Cards are pure white.
- **Gold ramp** `gold-100…700` (hue ~85°) — marketing surfaces ONLY (hero + auth): gold-200/300
  text on dark purple, gold-700 the only gold text on light, ≤5% of any viewport. Gold never
  replaces purple as the action color and never appears on data screens.
- Semantic: `success-600/100`, `error-600/100`, `amber-500/100/800`.
- Shadows are purple-tinted: `shadow-sm/md/lg` use `rgba(46,16,101,…)`; CTA glow
  `0 8px 24px oklch(0.47 0.198 305 / 0.28)`.
- Gradients — exactly two: `grad-brand` (135°, purple-700→900) and `grad-mesh` (three radial
  orbs: two purple + one faint amber). Gold gradient only for hero headline spans.

## Logo

The ONLY logo is the Android launcher icon, recreated as `components/FieldRepoLogo.tsx`:
an 8-point terracotta (#CC785C) star with a near-black (#181715) center disc on cream (#FAF9F5).
Keep its native colors even on purple surfaces (put it in a cream rounded tile there). The
favicon (`app/icon.svg`) is the same mark. Never reintroduce other marks.

## Naming (Android parity — copy EXACTLY)

Dashboard heading: **"What would you like to do?"**
Tiles in this order (label / action): Artisan/Record artisan, Product/Record product,
Process/Document process, Tool/Record tool, Questionnaire/Take interview,
Miscellaneous Media/Upload media, View Data/Browse records, Sharing/Share data access,
Users/Manage users (admin only), Craft/Add craft, Workshop/Record workshop.
Menu extras: My Activity, Tasks, Assign tools to artisans, Give app feedback, Settings
(master admin), Admin view toggle. Never invent new labels for these.

## Hierarchy rules (mirror the backend exactly)

- **Review ladder**: master admin reviews everyone; every other user reviews creators ranked
  STRICTLY below them. Decisions: Approve / Reject / **Send for revision** (mandatory comments →
  `NEEDS_REVISION`; creator edit resubmits to `PENDING`).
- **Activity visibility**: professor+ see activity of everyone below their rank; admins see the
  same rank and below; master admin sees all.
- **Promotion**: assign roles at or below your own tier (professor+); manage only users strictly
  below your tier; master admin manages everyone.
- **Tasks**: admins and the master admin assign documentation tasks to users below their rank;
  assignees update status (Open → In progress → Done).

## Layout rules

- Dashboard card grid: **2 per row on phones, 3 on tablets and laptops/PCs** —
  `grid-cols-2 md:grid-cols-3`.
- Card anatomy (from Android `DashboardActionCard`): white rounded-2xl card, small dark-purple
  icon tile (rounded-lg, light icon), display-font label, then a filled purple "New …" button
  and an outlined "Update" button where editing exists.
- Page canvas `bg-0`; content in white cards with `line-200` borders and purple-tinted shadows.
- Floating header pill (dynamic island) themed purple; pages pad top by the pill clearance.
- Radius scale: sm 8px, md 12px, lg 16px, xl 24px.

## Content rendering

- Transcripts and any AI text are **Markdown** — render with `react-markdown` + `remark-gfm`
  (bold speaker labels, `---` rules), never as raw text in a `<pre>`.
- Prisma Decimal columns arrive as JSON **strings** — display via `Number()` only.
- Never show internal ids, `Name__id` slugs, or empty fields to end users; folder/record names
  are always the clean human name.

## Auth

The sign-in shell is a split: left brand panel (deep purple, logo in a cream tile, gold-accent
copy), right frosted card on a mesh backdrop. The Android auth screen
(`android/.../ui/AuthScreen.kt`) is built against this same description, so change them together.
Buttons: email+password, then
"Continue with Google" (live), "Continue with Microsoft" and "Continue with Yahoo" — both
render a **"Coming soon"** badge and toast, never a dead request.
