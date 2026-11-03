"use client";

import { useEffect, useRef, useState } from "react";
import { motion, useScroll, useSpring, useTransform } from "framer-motion";

import { GuideRail } from "@/components/guide/GuideRail";
import { GuideStepCard } from "@/components/guide/GuideStepCard";
import { scrollToStep } from "@/components/guide/guideMotion";
import { GUIDE_STEPS } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

export function GuideJourney() {
  const reduce = useAppReducedMotion();
  const listRef = useRef<HTMLOListElement>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [expandedId, setExpandedId] = useState<string | null>(GUIDE_STEPS[0].id);

  const { scrollYProgress } = useScroll({ target: listRef, offset: ["start 65%", "end 65%"] });
  const smoothed = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: 0.4 });
  const progress = reduce ? scrollYProgress : smoothed;
  const nodeTop = useTransform(progress, [0, 1], ["0%", "100%"]);

  useEffect(() => {
    const hash = window.location.hash.replace("#", "");
    if (!hash || !GUIDE_STEPS.some((step) => step.id === hash)) return;
    setExpandedId(hash);
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
          className="relative grid gap-4 [--guide-rail:2rem] sm:[--guide-rail:3rem]"
        >
          <div
            aria-hidden
            className="pointer-events-none absolute inset-y-6 left-0 flex w-(--guide-rail) justify-center"
          >
            <div className="relative h-full w-1">
              <span className="absolute inset-0 rounded-full bg-progress-track" />
              <motion.span
                style={{ scaleY: progress }}
                className="absolute inset-0 origin-top rounded-full bg-progress-fill"
              />
              {reduce ? null : (
                <motion.span
                  style={{ top: nodeTop }}
                  className="absolute -left-[3px] mt-[-5px] h-2.5 w-2.5 rounded-full bg-progress-fill ring-4 ring-purple-100 dark:ring-purple-950"
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
