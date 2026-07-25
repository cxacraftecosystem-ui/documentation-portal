"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import {
  Activity,
  ArrowUpRight,
  CheckCircle2,
  ClipboardCheck,
  LayoutGrid,
  MessageSquare,
  Search,
  Share2,
  type LucideIcon
} from "lucide-react";

import { riseItem, slideItem, springy, staggerParent } from "@/components/guide/guideMotion";
import { GUIDE_STEPS } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/** The last-thing-before-you-pack-up checklist. Each item is a decision that is expensive to
 *  reverse once you are back from the field. */
const CHECKLIST = [
  "Every artisan you spoke to has a record, with Do's and Don'ts filled in.",
  "Every product you photographed has its dimensions, cost of making and selling price.",
  "Every process has its steps in order, and the steps have video.",
  "Every tool has a material, a maker and a replacement cost.",
  "The questionnaire's completion matrix has no unexplained gaps.",
  "Anything you shot that has no home is uploaded to Miscellaneous Media."
];

const NEXT: Array<{ label: string; href: string; icon: LucideIcon; note: string }> = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutGrid, note: "Every screen in this guide, one tap away" },
  { label: "Review", href: "/review", icon: ClipboardCheck, note: "What is waiting on a decision" },
  { label: "My Activity", href: "/activity", icon: Activity, note: "Everything you have recorded so far" },
  { label: "Search", href: "/search", icon: Search, note: "Find a record across the repository" },
  { label: "Sharing", href: "/sharing", icon: Share2, note: "Give a colleague access to your records" },
  { label: "Give app feedback", href: "/feedback", icon: MessageSquare, note: "Tell us what slowed you down" }
];

/**
 * The closing section: a compact recap of the ten steps as a single readable line, the
 * pre-departure checklist, and the screens a researcher goes to once the walkthrough is done.
 *
 * Everything here reveals on scroll with the same staggered vocabulary as the step cards, so
 * the end of the page feels like the same document rather than a footer bolted on.
 */
export function GuideOutro() {
  const reduce = useAppReducedMotion();

  return (
    <div className="mt-12 grid gap-6">
      <motion.section
        variants={staggerParent(reduce)}
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.3 }}
        className="panel p-5"
      >
        <motion.h2 variants={riseItem(reduce)} className="font-display text-lg font-bold text-ink-900">
          The whole process, in one line
        </motion.h2>
        <motion.p variants={riseItem(reduce)} className="mt-1.5 text-sm leading-6 text-ink-700">
          Learn this order and you can work without the guide. It is the same order on the web and in
          the Android app, and the screens carry the same names in both.
        </motion.p>
        <motion.ol variants={staggerParent(reduce, 0.035)} className="mt-4 flex flex-wrap items-center gap-1.5">
          {GUIDE_STEPS.map((step, index) => (
            <motion.li key={step.id} variants={slideItem(reduce, 10)} className="flex items-center gap-1.5">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900">
                <span aria-hidden className="font-display text-purple-700">
                  {index + 1}
                </span>
                {step.label}
              </span>
              {index < GUIDE_STEPS.length - 1 ? (
                <span aria-hidden className="text-ink-300">
                  →
                </span>
              ) : null}
            </motion.li>
          ))}
        </motion.ol>
      </motion.section>

      <motion.section
        variants={staggerParent(reduce)}
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.25 }}
        className="panel p-5"
      >
        <motion.h2 variants={riseItem(reduce)} className="font-display text-lg font-bold text-ink-900">
          Before you leave the field
        </motion.h2>
        <motion.p variants={riseItem(reduce)} className="mt-1.5 text-sm leading-6 text-ink-700">
          A missing field is a phone call. A missing recording is another trip. Run this list while the
          artisan is still in front of you.
        </motion.p>
        <motion.ul variants={staggerParent(reduce, 0.045)} className="mt-4 grid gap-2 sm:grid-cols-2">
          {CHECKLIST.map((item) => (
            <motion.li
              key={item}
              variants={riseItem(reduce, 10)}
              className="flex items-start gap-2.5 rounded-md border border-line-200 bg-surface-50 px-3 py-2.5 text-sm leading-6 text-ink-700"
            >
              <CheckCircle2 className="mt-1 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
              <span>{item}</span>
            </motion.li>
          ))}
        </motion.ul>
      </motion.section>

      <motion.section
        variants={staggerParent(reduce)}
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.2 }}
        className="panel p-5"
      >
        <motion.h2 variants={riseItem(reduce)} className="font-display text-lg font-bold text-ink-900">
          Where to go next
        </motion.h2>
        <motion.div variants={staggerParent(reduce, 0.04)} className="mt-4 grid gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
          {NEXT.map((entry) => (
            <motion.div key={entry.href} variants={riseItem(reduce, 10)}>
              <motion.div
                whileHover={reduce ? undefined : { y: -3 }}
                whileTap={reduce ? undefined : { scale: 0.99 }}
                transition={springy(reduce)}
                className="h-full rounded-md border border-line-200 bg-card transition-shadow hover:shadow-md"
              >
                <Link href={entry.href} className="flex h-full items-start gap-3 p-3.5">
                  <span aria-hidden className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700">
                    <entry.icon className="h-[18px] w-[18px]" aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-1 font-display text-sm font-bold text-ink-900">
                      {entry.label}
                      <ArrowUpRight className="h-3.5 w-3.5 text-ink-300" aria-hidden />
                    </span>
                    <span className="mt-0.5 block text-xs leading-5 text-ink-500">{entry.note}</span>
                  </span>
                </Link>
              </motion.div>
            </motion.div>
          ))}
        </motion.div>
      </motion.section>
    </div>
  );
}
