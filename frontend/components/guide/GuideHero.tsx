"use client";

import { motion, useMotionValue, useSpring, useTransform } from "framer-motion";
import { ArrowDown, Compass, ListOrdered, ShieldCheck } from "lucide-react";

import { riseItem, springy, staggerParent } from "@/components/guide/guideMotion";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { useGsapHeadline } from "@/components/guide/useGsapHeadline";

export function GuideHero({ stepCount, onStart }: { stepCount: number; onStart: () => void }) {
  const reduce = useAppReducedMotion();
  const headline = useGsapHeadline<HTMLHeadingElement>(reduce);

  const pointerX = useMotionValue(50);
  const pointerY = useMotionValue(50);
  const smoothX = useSpring(pointerX, { stiffness: 110, damping: 24, mass: 0.6 });
  const smoothY = useSpring(pointerY, { stiffness: 110, damping: 24, mass: 0.6 });

  const glow = useTransform([smoothX, smoothY], (latest: number[]) => {
    const [x, y] = latest;
    return `radial-gradient(30rem 26rem at ${x}% ${y}%, oklch(0.648 0.19 305 / 0.42), transparent 64%)`;
  });

  const facts = [
    { icon: ShieldCheck, text: "Access is by invitation" },
    { icon: ListOrdered, text: `${stepCount} steps, in field order` },
    { icon: Compass, text: "Every record is scoped to a workshop" }
  ];

  return (
    <motion.section
      variants={staggerParent(reduce, 0.08)}
      initial="hidden"
      animate="show"
      onPointerMove={
        reduce
          ? undefined
          : (event) => {
              const box = event.currentTarget.getBoundingClientRect();
              pointerX.set(((event.clientX - box.left) / box.width) * 100);
              pointerY.set(((event.clientY - box.top) / box.height) * 100);
            }
      }
      onPointerLeave={
        reduce
          ? undefined
          : () => {
              pointerX.set(50);
              pointerY.set(50);
            }
      }
      className="surface-dark relative isolate overflow-hidden px-6 py-10 sm:px-10 sm:py-12"
    >
      <motion.div aria-hidden className="pointer-events-none absolute inset-0 -z-10" style={{ backgroundImage: glow }} />

      <motion.p variants={riseItem(reduce)} className="text-[0.8125rem] font-semibold uppercase tracking-[0.14em] text-purple-300">
        Walkthrough
      </motion.p>
      <h2
        ref={headline}
        className="mt-3 max-w-2xl overflow-hidden font-display text-3xl font-bold tracking-tight text-white sm:text-4xl"
      >
        Document a craft, end to end.
      </h2>
      <motion.p variants={riseItem(reduce)} className="mt-4 max-w-2xl text-sm leading-relaxed text-purple-100">
        From opening a workshop to exporting the dataset — each step names its screen, its fields and
        what to watch for.
      </motion.p>

      <motion.ul variants={staggerParent(reduce)} className="mt-7 grid gap-2.5 sm:grid-cols-3">
        {facts.map((fact) => (
          <motion.li
            key={fact.text}
            variants={riseItem(reduce, 10)}
            className="flex items-start gap-2.5 rounded-md border border-white/10 bg-white/5 px-3 py-2.5"
          >
            <fact.icon className="mt-0.5 h-4 w-4 shrink-0 text-purple-300" aria-hidden />
            <span className="text-xs leading-5 text-purple-100">{fact.text}</span>
          </motion.li>
        ))}
      </motion.ul>

      <motion.div variants={riseItem(reduce)} className="mt-8">
        <motion.button
          type="button"
          onClick={onStart}
          whileHover={reduce ? undefined : { y: -2 }}
          whileTap={reduce ? undefined : { scale: 0.97 }}
          transition={springy(reduce)}
          className="inline-flex min-h-10 items-center gap-2 rounded-md bg-purple-700 px-5 py-2.5 text-sm font-medium text-white shadow-cta transition-colors hover:bg-purple-800"
        >
          Start at step 1
          <ArrowDown className="h-4 w-4" aria-hidden />
        </motion.button>
      </motion.div>
    </motion.section>
  );
}
