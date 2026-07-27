"use client";

import Link from "next/link";
import { motion, type Variants } from "framer-motion";
import { ChevronDown } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

const FAQS = [
  {
    q: "Who can sign in?",
    a: "Anyone with an account — email and password, or Google. New accounts start as Crowdsource Volunteers, and an admin elevates them up the six-tier ladder (field contributor, researcher, professor, admin) as their role in the project grows."
  },
  {
    q: "What happens to my recordings?",
    a: "They upload to secure storage and join the transcription queue, where a chain of three speech-to-text providers with automatic failover transcribes them and translates them into English. The finished transcript is linked back to the artisan, craft, and workshop it belongs to."
  },
  {
    q: "How does review work?",
    a: "Every record enters a peer-review ladder. A reviewer can approve it, reject it, or send it back for revision with mandatory comments — and each tier reviews the work of those ranked below it, with the master admin able to review everyone's."
  },
  {
    q: "Who can download the data?",
    a: "The full dataset opens at Professor and above. Anyone below that needs the dataset-download permission granted explicitly, or a per-record share from the owner. Sharing between researchers is tiered too: download, comment, or edit — requested by one side and granted, changed, or revoked by the other."
  },
  {
    q: "What can a brand-new account actually do?",
    a: "New accounts start as Crowdsource Volunteers: they can take interviews, upload media, and comment on existing records. Creating artisans, products, processes and tools begins at Field Contributor, and an admin raises the tier when the person's role in the project does."
  },
  {
    q: "Does it work offline?",
    a: "The Android app is offline-first — capture interviews, media, and GPS positions with no signal at all, and everything syncs when you are back online. The web portal complements it for review, browsing, and administration."
  },
  {
    q: "What about privacy?",
    a: "Access is governed by the six-tier role ladder, cross-researcher sharing is opt-in per grant, and every edit carries an audited revision history. Media lives in private cloud storage that only signed-in, authorized users can reach. National identifiers are masked wherever a record leaves its owner: an artisan's Aadhaar number is used to make sure the same person documented at two workshops becomes one record, not two, but it renders as XXXX XXXX 9012 on every shared and exported surface — the data browser, CSV, and the .xlsx report — and only the researcher who recorded that artisan, or a professor and above, can read it in full."
  }
];

/**
 * Marketing FAQ — native <details>/<summary> accordion styled to the tokens,
 * so it works with zero JavaScript and no extra dependencies.
 */
export default function HeroFAQ() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.06 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 14 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="faq" className="mx-auto max-w-3xl px-6 py-24" aria-label="Frequently asked questions">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3 text-center">
          Questions
        </motion.p>
        <motion.h2
          variants={item}
          className="text-center font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Answered before you ask.
        </motion.h2>

        <motion.div variants={item} className="mt-10 rounded-lg border border-line-200 bg-card px-6 shadow-sm">
          {FAQS.map((faq) => (
            <details key={faq.q} className="group border-b border-line-200 last:border-b-0">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-4 py-5 font-display text-base font-semibold text-ink-900 transition hover:text-purple-700 [&::-webkit-details-marker]:hidden">
                {faq.q}
                <ChevronDown
                  className="h-4 w-4 shrink-0 text-ink-500 transition-transform duration-200 group-open:rotate-180"
                  aria-hidden
                />
              </summary>
              <p className="pb-5 text-sm leading-relaxed text-ink-700">{faq.a}</p>
            </details>
          ))}
        </motion.div>

        <motion.p variants={item} className="mt-6 text-center text-sm text-ink-500">
          Still unsure where to start?{" "}
          <Link href="/guide" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            The walkthrough
          </Link>{" "}
          covers every screen in the order you will meet them.
        </motion.p>
      </motion.div>
    </section>
  );
}
