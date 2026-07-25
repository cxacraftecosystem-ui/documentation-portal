"use client";

import { motion, type Variants } from "framer-motion";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/**
 * The six tiers, in the order and with the exact labels of ROLE_LABELS in lib/permissions.ts and
 * ROLE_RANK in backend/app/core/deps.py. Each line states what the tier ADDS, because the ladder is
 * strictly inclusive: every rank inherits everything below it, and a grantable capability can lift
 * a single power for a lower tier without moving them up.
 */
const TIERS = [
  {
    role: "Crowdsource Volunteer",
    adds: "Take interviews, upload media, comment on records."
  },
  {
    role: "Field Contributor",
    adds: "Create artisans, products, processes and tools; open the review queue."
  },
  {
    role: "Researcher",
    adds: "Review the work of field contributors and volunteers."
  },
  {
    role: "Professor",
    adds: "Crafts, workshops, the questionnaire builder, promotions, full dataset download."
  },
  {
    role: "Admin",
    adds: "Settings hub, task assignment, workshop access grants, accounts."
  },
  {
    role: "Master Admin",
    adds: "Everything, plus managed API keys and global app settings."
  }
];

/**
 * The access ladder as a diagram rather than a paragraph: six rows whose accent bar lengthens with
 * rank, so the inclusive shape of the hierarchy is legible before a word is read. Purple only — the
 * ladder is a system diagram, and gold stays on the hero and auth surfaces.
 */
export default function AccessLadder() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.07 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, x: -12 },
    show: { opacity: 1, x: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="access" className="mx-auto max-w-6xl px-6 py-24" aria-label="The six-tier access ladder">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.2 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          Access is a ladder
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Six tiers, and each one inherits the last.
        </motion.h2>
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          New accounts start at the bottom and are raised by an admin. Individual capabilities —
          dataset download, review, craft and workshop creation, the questionnaire builder — can also
          be granted one at a time, without moving anyone up the ladder.
        </motion.p>

        <ol className="mt-12 space-y-2.5">
          {TIERS.map((tier, index) => (
            <motion.li
              key={tier.role}
              variants={item}
              className="flex items-center gap-4 rounded-md border border-line-200 bg-card p-4 shadow-sm transition hover:shadow-md sm:gap-5"
            >
              <span
                aria-hidden
                className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-purple-50 font-display text-sm font-bold text-purple-700"
              >
                {index + 1}
              </span>
              <div className="min-w-0 flex-1 sm:flex sm:items-baseline sm:gap-5">
                <h3 className="font-display text-sm font-bold text-ink-900 sm:w-52 sm:shrink-0">{tier.role}</h3>
                <p className="mt-1 text-sm leading-relaxed text-ink-500 sm:mt-0">{tier.adds}</p>
              </div>
              {/* The rung: width tracks rank, so the ladder reads as a shape as well as a list. */}
              <span
                aria-hidden
                className="hidden h-1.5 shrink-0 rounded-full bg-purple-700 lg:block"
                style={{ width: `${2 + index * 1.6}rem`, opacity: 0.35 + index * 0.13 }}
              />
            </motion.li>
          ))}
        </ol>
      </motion.div>
    </section>
  );
}
