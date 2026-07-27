"use client";

import Link from "next/link";
import { useRef } from "react";
import { motion, useScroll, useTransform } from "framer-motion";
import {
  Brush,
  ChevronDown,
  ClipboardList,
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
  { icon: ClipboardList, title: "Questionnaire", copy: "Structured interviews, recorded and auto-transcribed." },
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

        {/* Top bar: logo + sign in */}
        <header className="relative z-10 mx-auto flex w-full max-w-6xl items-center justify-between px-6 pt-6">
          <div className="flex items-center gap-2.5">
            <FieldRepoLogo className="h-10 w-10 rounded-xl shadow-md" />
            <span className="font-display text-lg font-bold tracking-tight text-white">Field Repository</span>
          </div>
          <Link
            href={enterHref}
            className="inline-flex h-10 items-center rounded-md border border-white/25 px-5 font-display text-sm font-bold text-white/90 transition hover:border-white/45 hover:bg-white/5 hover:text-white"
          >
            {user ? "Open the app" : "Sign in"}
          </Link>
        </header>

        <motion.div
          style={{ y: yContent, opacity: fade }}
          className="mx-auto flex w-full max-w-6xl flex-1 flex-col justify-center px-6 pb-24 pt-16"
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
        <p className="relative mx-auto mt-3 max-w-xl text-white/75">
          Sign in with your researcher account, or with Google — new accounts start as
          Crowdsource Volunteers and are elevated by an admin.
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
