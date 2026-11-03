"use client";

import { useEffect, useRef, useState } from "react";
import { motion, useMotionValueEvent, useScroll, useSpring, useTransform, type Variants } from "framer-motion";
import { ClipboardCheck, FolderDown, Languages, Mic } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

const STEPS = [
  {
    icon: Mic,
    title: "Capture in the field",
    copy:
      "Record interviews, photos and GPS on Android or the web — with or without signal. Dictate into any text box."
  },
  {
    icon: Languages,
    title: "Transcribe & translate automatically",
    copy:
      "Every recording arrives as clean English text, linked to its artisan, craft and workshop."
  },
  {
    icon: ClipboardCheck,
    title: "Review & approve up the ladder",
    copy:
      "Each tier reviews the one below — approve, reject or send back with comments. Finished tasks wait for approval."
  },
  {
    icon: FolderDown,
    title: "Explore & export the dataset",
    copy:
      "Browse records as folders, share with tiered access, and export research-ready data."
  }
];

export default function HowItWorks() {
  const reduce = useHeroReducedMotion();
  const listRef = useRef<HTMLOListElement>(null);
  const spineRef = useRef<HTMLDivElement>(null);
  const bubbleRefs = useRef<Array<HTMLSpanElement | null>>([]);

  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  const { scrollYProgress } = useScroll({ target: listRef, offset: ["start 65%", "end 65%"] });
  const smoothed = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: 0.4 });
  const progress = reduce ? scrollYProgress : smoothed;
  const nodeTop = useTransform(progress, [0, 1], ["0%", "100%"]);

  const [marks, setMarks] = useState<number[]>([]);
  const [reachedIndex, setReachedIndex] = useState(-1);

  useEffect(() => {
    function measure() {
      const spine = spineRef.current;
      if (!spine) return;
      const box = spine.getBoundingClientRect();
      if (box.height === 0) return;
      setMarks(
        bubbleRefs.current.map((el) => {
          if (!el) return Number.POSITIVE_INFINITY;
          const r = el.getBoundingClientRect();
          return (r.top + r.height / 2 - box.top) / box.height;
        })
      );
    }
    measure();
    const observer = new ResizeObserver(measure);
    if (spineRef.current) observer.observe(spineRef.current);
    window.addEventListener("resize", measure);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measure);
    };
  }, []);

  useMotionValueEvent(progress, "change", (latest) => {
    let next = -1;
    for (let i = 0; i < marks.length; i += 1) {
      if (latest >= marks[i]) next = i;
    }
    setReachedIndex((current) => (current === next ? current : next));
  });

  return (
    <section id="how-it-works" className="mx-auto max-w-6xl px-6 py-24" aria-label="How Field Repository works">
      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.4 }}
        variants={item}
      >
        <p className="eyebrow mb-3">How it works</p>
        <h2 className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl">
          From a field recording to a research-ready dataset.
        </h2>
      </motion.div>

      <div className="relative mt-14">
        <div
          ref={spineRef}
          aria-hidden
          className="pointer-events-none absolute bottom-6 left-5 top-2 -ml-0.5 w-1 lg:left-1/2"
        >
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

        <ol ref={listRef} className="space-y-10 lg:space-y-14">
          {STEPS.map((step, i) => {
            const flip = i % 2 === 1;
            const reached = i <= reachedIndex;
            return (
              <motion.li
                key={step.title}
                initial="hidden"
                whileInView="show"
                viewport={{ once: true, amount: 0.4 }}
                variants={item}
                className="relative pl-16 lg:grid lg:grid-cols-2 lg:pl-0"
              >
                <span
                  ref={(el) => {
                    bubbleRefs.current[i] = el;
                  }}
                  className={`absolute left-5 top-5 flex h-10 w-10 -translate-x-1/2 items-center justify-center rounded-full font-display text-sm font-bold shadow-md transition-colors duration-300 lg:left-1/2 ${
                    reached
                      ? "grad-brand text-white"
                      : "bg-card text-ink-500 ring-1 ring-line-200"
                  }`}
                  aria-hidden
                >
                  {i + 1}
                </span>
                <div className={flip ? "lg:col-start-2 lg:pl-16" : "lg:col-start-1 lg:pr-16"}>
                  <div className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md">
                    <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                      <step.icon className="h-5 w-5" aria-hidden />
                    </span>
                    <h3 className="font-display text-lg font-bold text-ink-900">
                      <span className="sr-only">Step {i + 1}: </span>
                      {step.title}
                    </h3>
                    <p className="mt-2 text-sm leading-relaxed text-ink-700">{step.copy}</p>
                  </div>
                </div>
              </motion.li>
            );
          })}
        </ol>
      </div>
    </section>
  );
}
