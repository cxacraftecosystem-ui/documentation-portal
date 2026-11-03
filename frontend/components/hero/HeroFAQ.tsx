"use client";

import Link from "next/link";
import { motion, type Variants } from "framer-motion";
import { ChevronDown } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

const FAQS = [
  {
    q: "Who can sign in?",
    a: "Only addresses an administrator has admitted. If yours isn’t on the list, signing in sends them a request."
  },
  {
    q: "I signed in with Google and was told I need approval. Why?",
    a: "Google confirms who you are, not that you’re approved. Your request has gone to an administrator; you can sign in once they approve it."
  },
  {
    q: "What happens to my recordings?",
    a: "They’re transcribed, translated into English and linked to their artisan, craft and workshop."
  },
  {
    q: "How does review work?",
    a: "Each tier reviews the tier below. A reviewer approves, rejects or sends work back with comments. Finished tasks wait for approval too."
  },
  {
    q: "Can a researcher speak instead of type?",
    a: "Yes — free-text boxes have a microphone on web and Android. Number and ID fields don’t. Dictation runs on the device; nothing is recorded or uploaded."
  },
  {
    q: "Who can download the data?",
    a: "Professors and above, or anyone granted dataset access. Researchers can also share their own records to download, comment or edit."
  },
  {
    q: "What can a brand-new account actually do?",
    a: "Take interviews, upload media and comment. An admin raises your tier as your role grows."
  },
  {
    q: "Does it work offline?",
    a: "Yes. Both the Android app and the website save offline and send when you’re back online."
  },
  {
    q: "How do I get the Android app?",
    a: "Sign in and open Settings → Get the Android app. Allow installs from your browser when asked; updates arrive automatically."
  },
  {
    q: "What about privacy?",
    a: "Access follows your role, sharing is opt-in and every edit is audited. Media is private. Aadhaar is never stored: records keep only the last four digits (XXXX XXXX 9012). Recordings may be sent for transcription and translation unless the artisan declines; anyone can switch that off."
  }
];

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
          <Link href="/guide" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            The walkthrough
          </Link>{" "}
          covers every screen in the order you will meet them.
        </motion.p>
      </motion.div>
    </section>
  );
}
