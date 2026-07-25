"use client";

import { motion, useMotionValue, useSpring, useTransform } from "framer-motion";
import { ArrowDown, Compass, ListOrdered, ShieldCheck } from "lucide-react";

import { riseItem, springy, staggerParent } from "@/components/guide/guideMotion";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * The walkthrough's opening band: a dark purple surface (`surface-dark`) carrying the promise
 * of the page, three orienting facts, and the control that drops the reader into step 1.
 *
 * The one piece of ambient motion here is a derived one — a purple wash whose centre is
 * driven by two motion values tracking the pointer, spring-smoothed and composed into a
 * `radial-gradient` string by `useTransform`. Nothing animates on its own: with no pointer
 * (touch, keyboard, or reduced motion from either the OS or the app's Settings toggle) the
 * values never leave their centred rest position, so the band renders as a plain static
 * gradient.
 */
export function GuideHero({ stepCount, onStart }: { stepCount: number; onStart: () => void }) {
  const reduce = useAppReducedMotion();

  // Pointer position as a percentage of the band, defaulting to dead centre.
  const pointerX = useMotionValue(50);
  const pointerY = useMotionValue(50);
  const smoothX = useSpring(pointerX, { stiffness: 110, damping: 24, mass: 0.6 });
  const smoothY = useSpring(pointerY, { stiffness: 110, damping: 24, mass: 0.6 });

  // Derived animation: two motion values → one CSS background string, recomputed off the
  // main React render loop.
  const glow = useTransform([smoothX, smoothY], (latest: number[]) => {
    const [x, y] = latest;
    return `radial-gradient(30rem 26rem at ${x}% ${y}%, oklch(0.648 0.19 305 / 0.42), transparent 64%)`;
  });

  const facts = [
    { icon: ListOrdered, text: `${stepCount} steps, in the order you do them in the field` },
    { icon: Compass, text: "Every record is scoped to a workshop" },
    { icon: ShieldCheck, text: "Everything you submit is reviewed before it counts" }
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
      <motion.h2
        variants={riseItem(reduce)}
        className="mt-3 max-w-2xl font-display text-3xl font-bold tracking-tight text-white sm:text-4xl"
      >
        Document a craft, end to end.
      </motion.h2>
      <motion.p variants={riseItem(reduce)} className="mt-4 max-w-2xl text-sm leading-relaxed text-purple-100">
        This is the whole process, in the order you actually perform it: from opening a workshop to
        exporting the finished dataset. Each step below names the screen it lives on, the fields it
        asks for, and the mistakes that cost people a second trip to the field.
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
