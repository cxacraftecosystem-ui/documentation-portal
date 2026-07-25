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
  // "end 65%"   → it completes when the list's bottom reaches the same line.
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
        <ol ref={listRef} className="relative grid gap-4">
          {/* Spine: a static track, a scroll-linked fill, and the node that rides the fill. */}
          <span
            aria-hidden
            className="pointer-events-none absolute inset-y-6 left-4 w-0.5 -translate-x-1/2 rounded-full bg-line-200 sm:left-6"
          />
          <motion.span
            aria-hidden
            style={{ scaleY: progress }}
            className="pointer-events-none absolute inset-y-6 left-4 w-0.5 -translate-x-1/2 origin-top rounded-full bg-purple-700 sm:left-6"
          />
          {reduce ? null : (
            <div aria-hidden className="pointer-events-none absolute inset-y-6 left-4 w-0.5 sm:left-6">
              <motion.span
                style={{ top: nodeTop }}
                className="absolute left-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-purple-700 ring-4 ring-purple-100"
              />
            </div>
          )}

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
