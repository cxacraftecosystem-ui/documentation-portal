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
      "Record interviews, photograph products and tools, and log GPS positions on the Android app or the web — with or without a signal. Nothing waits for connectivity."
  },
  {
    icon: Languages,
    title: "Transcribe & translate automatically",
    copy:
      "Every recording moves through a three-provider speech-to-text chain with automatic failover, then arrives as clean English text linked to its artisan, craft, and workshop."
  },
  {
    icon: ClipboardCheck,
    title: "Review & approve up the ladder",
    copy:
      "Reviewers approve, reject, or send work back for revision with comments. Each tier reviews the tiers below it, so quality climbs the same ladder as access."
  },
  {
    icon: FolderDown,
    title: "Explore & export the dataset",
    copy:
      "Browse the whole repository like a file system, grant collaborators tiered access, and — from Professor upwards, or with an explicit grant — export research-ready records, media, and transcripts."
  }
];

/**
 * The four-step product walk — a vertical timeline that alternates sides on
 * large screens, each step scroll-revealed once as it enters the viewport.
 *
 * The spine is the same instrument as the walkthrough's (`components/guide/GuideJourney`): a static
 * track, a scroll-linked fill, and a node riding the head of the fill, all driven by one
 * `useScroll` value. It was a dead grey line here, which read as decoration next to a page that
 * animates everywhere else — and it left the four numbered bubbles looking uniformly "done" before
 * the reader had reached any of them. Sharing the mechanism means the landing page and the
 * walkthrough teach the same gesture: the line fills as you read, and the numbers light as it
 * arrives.
 */
export default function HowItWorks() {
  const reduce = useHeroReducedMotion();
  const listRef = useRef<HTMLOListElement>(null);
  const spineRef = useRef<HTMLDivElement>(null);
  const bubbleRefs = useRef<Array<HTMLSpanElement | null>>([]);

  // The offset stays constant: it is rendered into the server HTML, so making it depend on
  // `reduce` would hydrate to different inline styles. Reduced motion zeroes the duration instead.
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  // Same reading line as the walkthrough — the fill completes while the last step is still on
  // screen rather than after the reader has scrolled past it into the next section.
  const { scrollYProgress } = useScroll({ target: listRef, offset: ["start 65%", "end 65%"] });
  const smoothed = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: 0.4 });
  const progress = reduce ? scrollYProgress : smoothed;
  const nodeTop = useTransform(progress, [0, 1], ["0%", "100%"]);

  // Where each bubble sits as a fraction of the spine's own box, so "the fill has reached step 3"
  // is a measurement rather than an assumption that four cards are the same height. They are not:
  // the copy runs from two lines to four, and evenly-spaced thresholds would light a number while
  // the line was still visibly short of it.
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
    // Card heights change with the viewport (the copy rewraps, and the layout goes from a single
    // column to two alternating ones at `lg`), so the marks are re-measured rather than cached from
    // first paint.
    const observer = new ResizeObserver(measure);
    if (spineRef.current) observer.observe(spineRef.current);
    window.addEventListener("resize", measure);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measure);
    };
  }, []);

  // Integer state, so this re-renders four times over the whole section rather than once per
  // scroll frame; the fill and the node stay on the compositor via motion values.
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
        {/* Timeline spine — left rail on phones, centred on large screens.
            `-ml-px` puts the 2px track's centre on the bubbles' centre line: the bubbles sit at
            `left-5` (and `lg:left-1/2`) pulled back by half their width, so the track has to give
            back half of its own. Nothing in here is centred with `translate` — framer-motion writes
            its own inline transform for `scaleY`, and an inline transform silently beats a Tailwind
            translate class, which is exactly how a fill ends up a pixel off its track. */}
        <div
          ref={spineRef}
          aria-hidden
          className="pointer-events-none absolute bottom-6 left-5 top-2 -ml-px w-0.5 lg:left-1/2"
        >
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
                {/* A plain span, deliberately: giving this a motion transform would fight the
                    `-translate-x-1/2` that holds it on the spine. Colour and shadow carry the state
                    change instead, which needs no transform at all. */}
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
