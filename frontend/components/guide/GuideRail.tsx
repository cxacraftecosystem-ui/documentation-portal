"use client";

import { useState } from "react";
import { AnimatePresence, motion, useMotionValueEvent, useTransform, type MotionValue } from "framer-motion";

import { layoutSpring, springy, swapVariants } from "@/components/guide/guideMotion";
import type { GuideStep } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

const RING_RADIUS = 34;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

/**
 * The sticky navigation rail (large screens only — on phones the spine alone carries the
 * reader).
 *
 * It shows three things, all driven by the same scroll position:
 *  - a progress ring whose stroke is a `useTransform` of the journey's scroll progress, so the
 *    arc is written straight to the DOM without a React re-render per frame;
 *  - the step you are currently reading, swapped through `AnimatePresence` so the outgoing
 *    label leaves before the incoming one arrives;
 *  - the full list of steps, with a shared-layout marker that slides between entries.
 *
 * All three are deliberately silent to assistive technology. The readout changes every time a
 * card crosses the viewport band, so announcing it (an `aria-live` region) would interrupt a
 * screen-reader user ten times on the way down the page for information they already have: the
 * steps are a numbered `<ol>` and each card carries its own number and heading. The rail is a
 * scroll indicator, not an event.
 */
export function GuideRail({
  steps,
  activeIndex,
  progress,
  onJump
}: {
  steps: GuideStep[];
  activeIndex: number;
  progress: MotionValue<number>;
  onJump: (id: string) => void;
}) {
  const reduce = useAppReducedMotion();
  const [percent, setPercent] = useState(0);

  // The ring is drawn as a single dash whose gap shrinks as you scroll.
  const dashOffset = useTransform(progress, [0, 1], [RING_CIRCUMFERENCE, 0]);

  // The numeric readout is the only part that needs React: round hard so we re-render at most
  // 100 times across the whole page rather than once per scroll frame.
  useMotionValueEvent(progress, "change", (latest) => {
    const next = Math.round(Math.min(Math.max(latest, 0), 1) * 100);
    setPercent((current) => (current === next ? current : next));
  });

  const active = steps[activeIndex];

  return (
    <aside className="hidden lg:block">
      <div className="sticky top-28">
        <div className="panel p-5">
          <div className="flex items-center gap-4">
            <div className="relative h-20 w-20 shrink-0">
              <svg viewBox="0 0 80 80" className="h-20 w-20 -rotate-90" aria-hidden>
                {/* Track: the themed border token, not a literal — `line-200` inverts in dark mode. */}
                <circle cx="40" cy="40" r={RING_RADIUS} fill="none" className="stroke-line-200" strokeWidth="6" />
                <motion.circle
                  cx="40"
                  cy="40"
                  r={RING_RADIUS}
                  fill="none"
                  stroke="oklch(0.47 0.198 305)"
                  strokeWidth="6"
                  strokeLinecap="round"
                  strokeDasharray={RING_CIRCUMFERENCE}
                  style={{ strokeDashoffset: dashOffset }}
                />
              </svg>
              <div className="absolute inset-0 grid place-items-center">
                <span className="font-display text-base font-bold text-ink-900" aria-hidden>
                  {percent}%
                </span>
              </div>
            </div>

            {/* Not a live region: see the note above — scrolling past a card is not an
                announcement, and `aria-current="step"` in the list below already carries the
                position for anyone navigating it. */}
            <div className="min-w-0">
              <AnimatePresence mode="wait" initial={false}>
                <motion.div
                  key={active?.id ?? "none"}
                  variants={swapVariants(reduce)}
                  initial="hidden"
                  animate="show"
                  exit="exit"
                >
                  <p className="text-xs font-semibold uppercase tracking-wide text-purple-700">
                    Step {activeIndex + 1} of {steps.length}
                  </p>
                  <p className="mt-1 font-display text-lg font-bold leading-tight text-ink-900">{active?.label}</p>
                  <p className="mt-0.5 text-xs text-ink-500">{active?.action}</p>
                </motion.div>
              </AnimatePresence>
            </div>
          </div>

          <nav className="mt-5 border-t border-line-200 pt-4" aria-label="Walkthrough steps">
            <ol className="grid gap-0.5">
              {steps.map((step, index) => {
                const isActive = index === activeIndex;
                const isPast = index < activeIndex;
                return (
                  <li key={step.id}>
                    <motion.button
                      type="button"
                      onClick={() => onJump(step.id)}
                      whileHover={reduce ? undefined : { x: 3 }}
                      whileTap={reduce ? undefined : { scale: 0.98 }}
                      transition={springy(reduce)}
                      aria-current={isActive ? "step" : undefined}
                      className={`relative flex w-full items-center gap-2.5 rounded-md py-1.5 pl-3 pr-2 text-left text-sm transition-colors ${
                        isActive ? "bg-purple-50 font-semibold text-purple-800" : "text-ink-700 hover:bg-purple-50"
                      }`}
                    >
                      {isActive ? (
                        <motion.span
                          layoutId="guide-rail-marker"
                          transition={layoutSpring(reduce)}
                          aria-hidden
                          className="absolute inset-y-1 left-0 w-[3px] rounded-full bg-purple-700"
                        />
                      ) : null}
                      <span
                        aria-hidden
                        className={`grid h-5 w-5 shrink-0 place-items-center rounded-full text-[0.6875rem] font-bold ${
                          isActive || isPast ? "bg-purple-700 text-white" : "bg-surface-50 text-ink-500 ring-1 ring-line-200"
                        }`}
                      >
                        {index + 1}
                      </span>
                      <span className="truncate">{step.label}</span>
                    </motion.button>
                  </li>
                );
              })}
            </ol>
          </nav>
        </div>
      </div>
    </aside>
  );
}
