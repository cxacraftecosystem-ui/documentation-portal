"use client";

import { motion, type Variants } from "framer-motion";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

const STATS = [
  { value: "8", label: "linked record types, artisan to workshop" },
  { value: "3", label: "STT providers with automatic failover" },
  { value: "6-tier", label: "access control, volunteer to master admin" },
  { value: "Offline-first", label: "Android app for the field" }
];

/**
 * Proof strip on the brand gradient — the numbers that describe the system,
 * set in the display face with gold accents (marketing surface only).
 */
export default function StatsBand() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.08 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 16 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section className="relative overflow-hidden grad-brand" aria-label="Field Repository at a glance">
      {/* Quiet mesh echo — one faint gold orb, one deep purple. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-60"
        style={{
          background:
            "radial-gradient(32rem 32rem at 88% 10%, oklch(0.7 0.145 80 / 0.14), transparent 60%), radial-gradient(36rem 36rem at 8% 95%, oklch(0.255 0.108 305 / 0.55), transparent 62%)"
        }}
      />
      <motion.dl
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.4 }}
        variants={container}
        className="relative mx-auto grid max-w-6xl grid-cols-2 gap-x-6 gap-y-10 px-6 py-16 lg:grid-cols-4"
      >
        {STATS.map((stat) => (
          <motion.div key={stat.label} variants={item} className="text-center">
            <dt className="sr-only">{stat.label}</dt>
            <dd className="font-display text-3xl font-extrabold leading-none tracking-tight text-gold-200 sm:text-4xl">
              {stat.value}
            </dd>
            <dd className="mx-auto mt-3 max-w-[16rem] text-sm leading-relaxed text-white/70">{stat.label}</dd>
          </motion.div>
        ))}
      </motion.dl>
    </section>
  );
}
