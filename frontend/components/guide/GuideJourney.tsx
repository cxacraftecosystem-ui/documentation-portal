"use client";

import { useEffect, useRef, useState } from "react";
import { motion, useScroll, useSpring, useTransform } from "framer-motion";

import { GuideRail } from "@/components/guide/GuideRail";
import { GuideStepCard } from "@/components/guide/GuideStepCard";
import { scrollToStep } from "@/components/guide/guideMotion";
import { GUIDE_STEPS } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * The journey: the ten steps threaded onto a scroll-linked spine, with the sticky rail
 * alongside on large screens.
 *
 * The spine is the page's organising animation. `useScroll` measures how far the reader has
 * travelled through the step list (not the document — the offsets are anchored to the list's
 * own top and bottom), and that single progress value feeds three consumers: the spine's
 * `scaleY` fill, the travelling node's `top`, and the rail's progress ring. None of them
 * re-render React; they are motion values written straight to the DOM.
 *
 * Horizontally, ONE thing owns the axis: `--guide-rail`, the width of the list's first grid
 * column. The spine is centred in a box of that width and every step's numbered bubble is a grid
 * item in that same column, so the track, the fill, the node and the ten numbers share a centre
 * line by construction. They used to be independent guesses (`absolute left-4 sm:left-6` in two
 * places plus `pl-12 sm:pl-16` in a third, reconciled by `-translate-x-1/2`), which is a
 * three-way agreement that has to be re-derived by hand every time a size or a breakpoint moves —
 * and it had already broken: see the note on the spine below.
 *
 * Under reduced motion — the OS preference OR the app's own Settings toggle, see
 * `useAppReducedMotion` — the spring smoothing is dropped (the raw scroll value is used, so the
 * fill tracks the scrollbar exactly with no inertia) and the travelling node is not rendered at
 * all.
 */
export function GuideJourney() {
  const reduce = useAppReducedMotion();
  const listRef = useRef<HTMLOListElement>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  // One card open at a time. The first is open on arrival so the shape of a step is obvious
  // without the reader having to discover that the cards expand.
  const [expandedId, setExpandedId] = useState<string | null>(GUIDE_STEPS[0].id);

  // "start 65%" → progress begins when the list's top passes 65% down the viewport;
  // "end 65%"   → it completes when the list's bottom reaches the same line. Measured: the fill
  // reads 100% with the tenth step's bottom edge still on screen (~58vh at 1280, ~43vh at 360),
  // i.e. the spine completes while you are looking at the last step, not after you have scrolled
  // past it into the outro — and it does reach 100% at every width, with room to spare.
  const { scrollYProgress } = useScroll({ target: listRef, offset: ["start 65%", "end 65%"] });
  const smoothed = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: 0.4 });
  const progress = reduce ? scrollYProgress : smoothed;
  const nodeTop = useTransform(progress, [0, 1], ["0%", "100%"]);

  // Deep links: /guide#questionnaire opens that step and scrolls to it.
  useEffect(() => {
    const hash = window.location.hash.replace("#", "");
    if (!hash || !GUIDE_STEPS.some((step) => step.id === hash)) return;
    setExpandedId(hash);
    // Wait a frame so the expanded card has its final height before we scroll to it.
    const frame = window.requestAnimationFrame(() => scrollToStep(hash, true));
    return () => window.cancelAnimationFrame(frame);
  }, []);

  function jump(id: string) {
    setExpandedId(id);
    scrollToStep(id, reduce);
  }

  return (
    <section className="mt-10 grid gap-8 lg:grid-cols-[260px_minmax(0,1fr)]" aria-label="The documentation process, step by step">
      <GuideRail steps={GUIDE_STEPS} activeIndex={activeIndex} progress={progress} onJump={jump} />

      <div className="relative">
        <ol
          ref={listRef}
          // `--guide-rail` is the single owner of the spine's horizontal axis. It is the width of
          // the list's first grid column (see `GuideStepCard`), and the spine below is centred in
          // a box of exactly that width anchored to the same `left-0`. Track, fill, travelling
          // node and all ten numbered bubbles therefore share one centre line by construction —
          // change the rail width or the bubble size and they still cannot drift apart.
          className="relative grid gap-4 [--guide-rail:2rem] sm:[--guide-rail:3rem]"
        >
          {/* Spine: a static track, a scroll-linked fill, and the node that rides the fill.
              Nothing here is centred with `translateX`. framer-motion writes its own inline
              `transform` for `scaleY` (and for the bubbles' `scale`), and an inline transform
              silently overrides a Tailwind `-translate-x-1/2` from a class — which is exactly how
              the fill ended up 1px off the track and every bubble a full half-width (16px) to the
              right of it. Flex centring inside the rail box cannot be clobbered that way. */}
          <div
            aria-hidden
            className="pointer-events-none absolute inset-y-6 left-0 flex w-[var(--guide-rail)] justify-center"
          >
            <div className="relative h-full w-0.5">
              <span className="absolute inset-0 rounded-full bg-line-200" />
              <motion.span
                style={{ scaleY: progress }}
                className="absolute inset-0 origin-top rounded-full bg-purple-700"
              />
              {reduce ? null : (
                <motion.span
                  style={{ top: nodeTop }}
                  // Centred on the 2px track by margins, not transforms: -4px = (2px − 10px) / 2
                  // horizontally, -5px = half the dot vertically.
                  className="absolute -left-1 -mt-[5px] h-2.5 w-2.5 rounded-full bg-purple-700 ring-4 ring-purple-100"
                />
              )}
            </div>
          </div>

          {GUIDE_STEPS.map((step, index) => (
            <GuideStepCard
              key={step.id}
              step={step}
              index={index}
              expanded={expandedId === step.id}
              onToggle={() => setExpandedId((current) => (current === step.id ? null : step.id))}
              onEnterView={() => setActiveIndex(index)}
            />
          ))}
        </ol>
      </div>
    </section>
  );
}
