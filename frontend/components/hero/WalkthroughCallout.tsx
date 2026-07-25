"use client";

import Link from "next/link";
import { motion, type Variants } from "framer-motion";
import { ArrowRight, Compass } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/** The chapters the in-app walkthrough covers, so the link promises something specific. */
const CHAPTERS = [
  "Recording an artisan and carrying them into a product",
  "Running an interview and reading the transcript back",
  "Sending work up the review ladder",
  "Sharing data with another researcher"
];

/**
 * The walkthrough band. `/guide` sits inside the app (it is the web twin of the Android
 * first-run walkthrough), so this links to it and signing in is the first step — the same journey
 * a new researcher takes anyway.
 */
export default function WalkthroughCallout() {
  const reduce = useHeroReducedMotion();

  const reveal: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section className="mx-auto max-w-6xl px-6 pb-24" aria-label="The in-app walkthrough">
      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.3 }}
        variants={reveal}
        className="grid gap-8 rounded-xl border border-line-200 bg-card p-8 shadow-sm sm:p-10 lg:grid-cols-[1fr_auto] lg:items-center lg:gap-12"
      >
        <div>
          <span className="mb-5 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
            <Compass className="h-5 w-5" aria-hidden />
          </span>
          <h2 className="font-display text-2xl font-bold tracking-tight text-ink-900 sm:text-3xl">
            Never documented a craft before? Start with the walkthrough.
          </h2>
          <p className="mt-3 max-w-2xl text-base leading-relaxed text-ink-500">
            Every account gets the same guided tour of the documentation process — what each record
            type is for, what makes a good interview, and how work travels from the field to an
            approved, exportable dataset.
          </p>
          <ul className="mt-6 grid gap-2 sm:grid-cols-2">
            {CHAPTERS.map((chapter) => (
              <li key={chapter} className="flex items-start gap-2.5 text-sm leading-relaxed text-ink-700">
                <ArrowRight className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
                {chapter}
              </li>
            ))}
          </ul>
        </div>
        <Link
          href="/guide"
          className="inline-flex h-12 shrink-0 items-center justify-center gap-2 rounded-md bg-purple-700 px-7 font-display text-base font-bold tracking-tight text-white shadow-cta transition hover:-translate-y-0.5 hover:bg-purple-800 active:translate-y-0"
        >
          Open the walkthrough
          <ArrowRight className="h-4 w-4" aria-hidden />
        </Link>
      </motion.div>
    </section>
  );
}
