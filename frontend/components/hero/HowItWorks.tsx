"use client";

import { motion, type Variants } from "framer-motion";
import { ClipboardCheck, FolderDown, Languages, Mic } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

const STEPS = [
  {
    icon: Mic,
    title: "Capture in the field",
    copy:
      "Record interviews, photograph products and tools, and log GPS positions on the Android app or the web — with or without a signal. Nothing waits for connectivity."
  },
  {
    icon: Languages,
    title: "Transcribe & translate automatically",
    copy:
      "Every recording moves through a three-provider speech-to-text chain with automatic failover, then arrives as clean English text linked to its artisan, craft, and workshop."
  },
  {
    icon: ClipboardCheck,
    title: "Review & approve up the ladder",
    copy:
      "Reviewers approve, reject, or send work back for revision with comments. Each tier reviews the tiers below it, so quality climbs the same ladder as access."
  },
  {
    icon: FolderDown,
    title: "Explore & export the dataset",
    copy:
      "Browse the whole repository like a file system, grant collaborators tiered access, and — from Professor upwards, or with an explicit grant — export research-ready records, media, and transcripts."
  }
];

/**
 * The four-step product walk — a vertical timeline that alternates sides on
 * large screens, each step scroll-revealed once as it enters the viewport.
 */
export default function HowItWorks() {
  const reduce = useHeroReducedMotion();

  // The offset stays constant: it is rendered into the server HTML, so making it depend on
  // `reduce` would hydrate to different inline styles. Reduced motion zeroes the duration instead.
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="how-it-works" className="mx-auto max-w-6xl px-6 py-24" aria-label="How Field Repository works">
      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.4 }}
        variants={item}
      >
        <p className="eyebrow mb-3">How it works</p>
        <h2 className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl">
          From a field recording to a research-ready dataset.
        </h2>
      </motion.div>

      <div className="relative mt-14">
        {/* Timeline spine — left rail on phones, centered on large screens. */}
        <span aria-hidden className="absolute bottom-6 left-5 top-2 w-px bg-line-200 lg:left-1/2" />
        <ol className="space-y-10 lg:space-y-14">
          {STEPS.map((step, i) => {
            const flip = i % 2 === 1;
            return (
              <motion.li
                key={step.title}
                initial="hidden"
                whileInView="show"
                viewport={{ once: true, amount: 0.4 }}
                variants={item}
                className="relative pl-16 lg:grid lg:grid-cols-2 lg:pl-0"
              >
                <span
                  className="absolute left-5 top-5 flex h-10 w-10 -translate-x-1/2 items-center justify-center rounded-full grad-brand font-display text-sm font-bold text-white shadow-md lg:left-1/2"
                  aria-hidden
                >
                  {i + 1}
                </span>
                <div className={flip ? "lg:col-start-2 lg:pl-16" : "lg:col-start-1 lg:pr-16"}>
                  <div className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md">
                    <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                      <step.icon className="h-5 w-5" aria-hidden />
                    </span>
                    <h3 className="font-display text-lg font-bold text-ink-900">
                      <span className="sr-only">Step {i + 1}: </span>
                      {step.title}
                    </h3>
                    <p className="mt-2 text-sm leading-relaxed text-ink-700">{step.copy}</p>
                  </div>
                </div>
              </motion.li>
            );
          })}
        </ol>
      </div>
    </section>
  );
}
